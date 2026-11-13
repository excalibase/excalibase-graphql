package io.github.excalibase.config.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.security.JwtService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A request is an upgrade when any value of any Upgrade header says websocket. The server upgrades it then, so the
 * handshake checks (origin, project, token) must run whatever else the header lists first.
 */
class WsHandshakeDoubledUpgradeTest {

    private static final String PATH_PROJECT = "proj-a";
    private static final String LISTED = "https://app.example.com";
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

    private static String tokenFor(String project) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("alice@test.com").claim("userId", 42)
                .claim("projectId", project).claim("role", "user")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private static WsHandshakeFilter filter() {
        return new WsHandshakeFilter(new ProjectCorsConfigurationSource(request -> List.of(LISTED),
                ProjectCorsConfigurationSource.configFor(List.of())), jwtService);
    }

    private static MutableHttpRequest<?> request(String... upgradeLines) {
        MutableHttpRequest<?> request = HttpRequest.GET("/" + PATH_PROJECT + "/api/v1/realtime").header("Host", "api.excalibase.io");
        for (String line : upgradeLines) {
            request.getHeaders().add("Upgrade", line);
        }
        return request;
    }

    private static String[][] spellings() {
        return new String[][] {{"foo", "websocket"}, {"websocket", "foo"}, {"foo, websocket"}, {"h2c", "WebSocket"}};
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("an Origin off the allowlist is refused however the Upgrade header is spelled")
    void badOriginIsRefused(int index) {
        MutableHttpRequest<?> request = request(spellings()[index]).header("Origin", "https://evil.example");

        HttpResponse<?> refusal = filter().filter(request);

        assertThat(refusal).isNotNull();
        assertThat(refusal.code()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("a token for another project is refused however the Upgrade header is spelled")
    void tokenForAnotherProjectIsRefused(int index) throws Exception {
        MutableHttpRequest<?> request = request(spellings()[index]).header("Authorization", "Bearer " + tokenFor("proj-b"));

        HttpResponse<?> refusal = filter().filter(request);

        assertThat(refusal).isNotNull();
        assertThat(refusal.code()).isIn(401, 403);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("the path project is captured as the session's project however the Upgrade header is spelled")
    void pathProjectIsCaptured(int index) {
        MutableHttpRequest<?> request = request(spellings()[index]);

        assertThat(filter().filter(request)).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) request.getAttribute(WsHandshakeFilter.ATTRIBUTES).orElseThrow();
        assertThat(attributes).containsEntry(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, PATH_PROJECT);
    }

    @Test
    @DisplayName("a request whose Upgrade values never say websocket is not an upgrade")
    void otherUpgradeIsIgnored() {
        MutableHttpRequest<?> request = request("h2c", "foo").header("Origin", "https://evil.example");

        assertThat(filter().filter(request)).isNull();
    }
}
