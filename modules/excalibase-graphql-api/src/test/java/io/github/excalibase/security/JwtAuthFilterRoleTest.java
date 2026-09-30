package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.StubbedSchemaManager;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The HTTP edge resolves one role per request and hands it on as the request's {@link Principal}. */
class JwtAuthFilterRoleTest {

    private static final String PROJECT = "proj_abc";

    private static ECPrivateKey privateKey;
    private static JwtService jwtService;

    @BeforeAll
    static void setup() throws Exception {
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

    private static MockHttpServletRequest request(String bearer, String requestedRole) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/" + PROJECT + "/graphql");
        request.setRequestURI("/" + PROJECT + "/graphql");
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        if (requestedRole != null) {
            request.addHeader(RoleResolver.ROLE_HEADER, requestedRole);
        }
        return request;
    }

    private static final class Capture {
        final AtomicReference<Principal> principal = new AtomicReference<>();
        final AtomicReference<Boolean> rlsRegistered = new AtomicReference<>();
        final FilterChain chain = (req, res) -> {
            principal.set((Principal) req.getAttribute(SecurityConstants.PRINCIPAL_ATTR));
            rlsRegistered.set(RlsContext.current() != null);
        };
    }

    private static MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        var plans = StubbedSchemaManager.withDocument(new SchemaInfo(),
                StubbedSchemaManager.document(PROJECT), (sql, params) -> false);
        new JwtAuthFilter(jwtService, plans).doFilter(request, response, chain);
        return response;
    }

    @Test
    void unreadablePermissions_refuseTheRequestWith503() throws Exception {
        Capture capture = new Capture();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PermissionProvider down = new PermissionProvider() {
            @Override
            public PermissionSet permissionsFor(String projectId) {
                throw new PermissionsUnavailableException("control plane down");
            }

            @Override
            public void evict(String projectId) {
                // nothing cached
            }
        };

        new JwtAuthFilter(jwtService, StubbedSchemaManager.withProvider(new SchemaInfo(), down))
                .doFilter(request(token(Map.of("role", "user")), null), response, capture.chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("permissions_unavailable");
        assertThat(capture.principal.get()).isNull();
    }

    @Test
    void noToken_runsAsAnon() throws Exception {
        Capture capture = new Capture();

        run(request(null, null), capture.chain);

        assertThat(capture.principal.get().role()).isEqualTo(Principal.ANON);
        assertThat(capture.rlsRegistered.get()).isTrue();
    }

    @Test
    void userToken_runsAsItsDefaultRole() throws Exception {
        Capture capture = new Capture();

        run(request(token(Map.of("role", "user")), null), capture.chain);

        assertThat(capture.principal.get().role()).isEqualTo("user");
        assertThat(capture.principal.get().claims().userId()).isEqualTo("42");
    }

    @Test
    void allowedRoleHeader_picksThatRole() throws Exception {
        Capture capture = new Capture();

        run(request(token(Map.of("role", "user", "allowed_roles", List.of("user", "editor"))), "editor"),
                capture.chain);

        assertThat(capture.principal.get().role()).isEqualTo("editor");
    }

    @Test
    void roleHeaderNotAllowed_is403WithCode() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = run(request(token(Map.of("role", "user")), "editor"), chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"role_not_allowed\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void noTokenWithNonAnonRoleHeader_is403WithCode() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = run(request(null, "user"), chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"role_not_allowed\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void tokenWithoutRole_is401InvalidRoleClaim() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = run(request(token(Map.of()), null), chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"invalid_role_claim\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void serviceToken_bypassesAndRegistersNoRowSecurity() throws Exception {
        Capture capture = new Capture();

        run(request(token(Map.of("role", "service", "scope", "service")), null), capture.chain);

        assertThat(capture.principal.get().bypass()).isTrue();
        assertThat(capture.rlsRegistered.get()).isFalse();
    }

    @Test
    void serviceTokenActingAsUser_carriesTheSessionHeadersAndRowSecurity() throws Exception {
        Capture capture = new Capture();
        MockHttpServletRequest request = request(token(Map.of("role", "service", "scope", "service")), "user");
        request.addHeader("X-Excalibase-User-Id", "77");

        run(request, capture.chain);

        Principal principal = capture.principal.get();
        assertThat(principal.role()).isEqualTo("user");
        assertThat(principal.bypass()).isFalse();
        assertThat(principal.impersonationHeaders()).containsEntry("x-excalibase-user-id", "77");
        assertThat(capture.rlsRegistered.get()).isTrue();
    }
}
