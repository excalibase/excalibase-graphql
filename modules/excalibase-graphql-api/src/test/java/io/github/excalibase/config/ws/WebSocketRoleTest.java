package io.github.excalibase.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket sessions run as one role, resolved by the same rules as HTTP: on the upgrade
 * request (headers) and again at {@code connection_init} (payload), for GraphQL and realtime.
 */
class WebSocketRoleTest {

    private static final String PROJECT = "p1";
    private static ECPrivateKey privateKey;
    private static JwtService jwtService;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) keyPair.getPrivate();
        jwtService = new JwtService((ECPublicKey) keyPair.getPublic()).requireAudience(false, null);
    }

    private static String token(Map<String, Object> extraClaims) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("alice@test.com")
                .claim("userId", 42)
                .claim("projectId", PROJECT)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        extraClaims.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private static Principal principalOf(Map<String, Object> attributes) {
        return (Principal) attributes.get(GraphQLWebSocketHandler.SESSION_PRINCIPAL_KEY);
    }

    @Nested
    class Handshake {

        private final Map<String, Object> attributes = new HashMap<>();
        private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        private boolean handshake(String bearer, String requestedRole, Map<String, String> extraHeaders) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + PROJECT + "/graphql");
            request.setRequestURI("/" + PROJECT + "/graphql");
            if (bearer != null) {
                request.addHeader("Authorization", "Bearer " + bearer);
            }
            if (requestedRole != null) {
                request.addHeader("X-Excalibase-Role", requestedRole);
            }
            extraHeaders.forEach(request::addHeader);
            return new JwtHandshakeInterceptor(jwtService).beforeHandshake(new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), attributes);
        }

        @Test
        void noToken_noRole_runsAsAnon() {
            assertThat(handshake(null, null, Map.of())).isTrue();
            assertThat(principalOf(attributes).role()).isEqualTo(Principal.ANON);
        }

        @Test
        void noToken_nonAnonRole_isRefusedWith403() {
            assertThat(handshake(null, "user", Map.of())).isFalse();
            assertThat(servletResponse.getStatus()).isEqualTo(403);
            assertThat(servletResponse.getHeader("X-Auth-Reason")).contains("role_not_allowed");
        }

        @Test
        void userToken_allowedRoleHeader_runsAsThatRole() throws Exception {
            String bearer = token(Map.of("role", "user", "allowed_roles", List.of("user", "editor")));

            assertThat(handshake(bearer, "editor", Map.of())).isTrue();
            assertThat(principalOf(attributes).role()).isEqualTo("editor");
        }

        @Test
        void userToken_roleNotAllowed_isRefusedWith403() throws Exception {
            assertThat(handshake(token(Map.of("role", "user")), "editor", Map.of())).isFalse();
            assertThat(servletResponse.getStatus()).isEqualTo(403);
        }

        @Test
        void tokenWithoutRole_isRefusedWith401() throws Exception {
            assertThat(handshake(token(Map.of()), null, Map.of())).isFalse();
            assertThat(servletResponse.getStatus()).isEqualTo(401);
            assertThat(servletResponse.getHeader("X-Auth-Reason")).contains("invalid_role_claim");
        }

        @Test
        void serviceTokenActingAsUser_carriesTheSessionHeaders() throws Exception {
            String bearer = token(Map.of("role", "service", "scope", "service"));

            assertThat(handshake(bearer, "user", Map.of("X-Excalibase-User-Id", "77"))).isTrue();
            Principal principal = principalOf(attributes);
            assertThat(principal.role()).isEqualTo("user");
            assertThat(principal.bypass()).isFalse();
            assertThat(principal.impersonationHeaders()).containsEntry("x-excalibase-user-id", "77");
        }

        @Test
        void serviceToken_bypasses() throws Exception {
            assertThat(handshake(token(Map.of("role", "service", "scope", "service")), null, Map.of())).isTrue();
            assertThat(principalOf(attributes).bypass()).isTrue();
        }
    }

    @Nested
    class ConnectionInit {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> sent = new ArrayList<>();
        private WebSocketSession session;

        @BeforeEach
        void session() throws Exception {
            session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("ws-" + System.nanoTime());
            when(session.isOpen()).thenReturn(true);
            Map<String, Object> attributes = new ConcurrentHashMap<>();
            attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, PROJECT);
            when(session.getAttributes()).thenReturn(attributes);
            doAnswer(invocation -> {
                sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
        }

        private <T> ObjectProvider<T> provider(T value) {
            return new ObjectProvider<>() {
                @Override public T getObject() { return value; }
                @Override public T getObject(Object... args) { return value; }
                @Override public T getIfAvailable() { return value; }
                @Override public T getIfUnique() { return value; }
            };
        }

        private TextWebSocketHandler graphqlHandler() {
            GraphQLWebSocketHandler handler = new GraphQLWebSocketHandler(new SubscriptionService(), mapper,
                    provider(jwtService), provider((RlsPolicyEnforcer) null), new WebSocketHeartbeat(0),
                    provider((RealtimeExposureGate) null));
            ReflectionTestUtils.setField(handler, "jwtEnabled", true);
            return handler;
        }

        private TextWebSocketHandler realtimeHandler() {
            RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(new SubscriptionService(), mapper,
                    provider(jwtService), provider((RlsPolicyEnforcer) null), new WebSocketHeartbeat(0),
                    provider((RealtimeExposureGate) null));
            ReflectionTestUtils.setField(handler, "jwtEnabled", true);
            return handler;
        }

        private void init(TextWebSocketHandler handler, Map<String, Object> payload) throws Exception {
            handler.handleMessage(session, new TextMessage(mapper.writeValueAsString(
                    Map.of("type", "connection_init", "payload", payload))));
        }

        private Principal principal() {
            return principalOf(session.getAttributes());
        }

        private void assertAcked() throws Exception {
            assertThat(sent).isNotEmpty();
            assertThat(mapper.readTree(sent.getLast()).get("type").asText()).isEqualTo("connection_ack");
            verify(session, never()).close(any(CloseStatus.class));
        }

        private void assertClosedWith(String code) throws Exception {
            assertThat(mapper.readTree(sent.getLast()).get("type").asText()).isEqualTo("connection_error");
            assertThat(sent.getLast()).contains(code);
            verify(session).close(any(CloseStatus.class));
        }

        @Test
        void graphql_payloadRole_picksAnAllowedRole() throws Exception {
            String bearer = token(Map.of("role", "user", "allowed_roles", List.of("user", "editor")));

            init(graphqlHandler(), Map.of("Authorization", "Bearer " + bearer, "role", "editor"));

            assertAcked();
            assertThat(principal().role()).isEqualTo("editor");
        }

        @Test
        void graphql_payloadHeaderRole_matchedCaseInsensitively_notAllowed_closes() throws Exception {
            String bearer = token(Map.of("role", "user"));
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + bearer);
            headers.put("x-excalibase-role", "editor");

            init(graphqlHandler(), Map.of("headers", headers));

            assertClosedWith("role_not_allowed");
            assertThat(principal()).isNull();
        }

        @Test
        void graphql_noToken_runsAsAnon() throws Exception {
            init(graphqlHandler(), Map.of());

            assertAcked();
            assertThat(principal().role()).isEqualTo(Principal.ANON);
        }

        @Test
        void graphql_tokenWithoutRole_closesWithInvalidRoleClaim() throws Exception {
            init(graphqlHandler(), Map.of("Authorization", "Bearer " + token(Map.of())));

            assertClosedWith("invalid_role_claim");
        }

        @Test
        void graphql_afterHandshakeToken_payloadRoleIsResolvedAgainstThoseClaims() throws Exception {
            Map<String, Object> handshakeAttributes = new HashMap<>();
            MockHttpServletRequest upgrade = new MockHttpServletRequest("GET", "/" + PROJECT + "/graphql");
            upgrade.setRequestURI("/" + PROJECT + "/graphql");
            upgrade.addHeader("Authorization", "Bearer "
                    + token(Map.of("role", "user", "allowed_roles", List.of("user", "editor"))));
            new JwtHandshakeInterceptor(jwtService).beforeHandshake(new ServletServerHttpRequest(upgrade),
                    new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class),
                    handshakeAttributes);
            session.getAttributes().putAll(handshakeAttributes);

            init(graphqlHandler(), Map.of("role", "editor"));

            assertAcked();
            assertThat(principal().role()).isEqualTo("editor");
        }

        @Test
        void realtime_noToken_nonAnonRole_closes() throws Exception {
            init(realtimeHandler(), Map.of("role", "user"));

            assertClosedWith("role_not_allowed");
        }

        @Test
        void realtime_serviceToken_bypasses() throws Exception {
            String bearer = token(Map.of("role", "service", "scope", "service"));

            init(realtimeHandler(), Map.of("Authorization", "Bearer " + bearer));

            assertAcked();
            assertThat(principal().bypass()).isTrue();
        }

        @Test
        void realtime_userToken_runsAsTheDefaultRole() throws Exception {
            init(realtimeHandler(), Map.of("Authorization", "Bearer " + token(Map.of("role", "user"))));

            assertAcked();
            assertThat(principal().role()).isEqualTo("user");
        }
    }
}
