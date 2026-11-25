package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.StubbedSchemaManager;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXC-11 at the HTTP edge: an audience that does not cover the project in the
 * URL must produce a 401 carrying the {@code aud_mismatch} code, and the
 * request must never reach the chain.
 */
class JwtAuthFilterAudienceTest {

    private static final String PROJECT = "proj_abc";
    private static final String OTHER_PROJECT = "proj_xyz";
    private static final String AUD_PREFIX = "excalibase:";

    static ECPrivateKey privateKey;
    static ECPublicKey publicKey;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = gen.generateKeyPair();
        privateKey = (ECPrivateKey) kp.getPrivate();
        publicKey = (ECPublicKey) kp.getPublic();
    }

    private static AccessPlans enforcer() {
        return StubbedSchemaManager.withDocument(new SchemaInfo(), StubbedSchemaManager.document(PROJECT),
                (sql, params) -> false);
    }

    private String token(String audience, String tokenUse) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(String.valueOf(42))
                .claim("projectId", PROJECT)
                .claim("role", "user")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        if (audience != null) {
            builder.audience(audience);
        }
        if (tokenUse != null) {
            builder.claim("token_use", tokenUse);
        }
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    /** The refusal the filter answers, or null when the request goes on to the handler. */
    private HttpResponse<?> callFilter(JwtService jwtService, String bearer) {
        MutableHttpRequest<?> request = HttpRequest.POST("/" + PROJECT + "/graphql", "");
        request.header("Authorization", "Bearer " + bearer);
        return new JwtAuthFilter(jwtService, enforcer())
                .filter(request, MutablePropagatedContext.of(PropagatedContext.empty()));
    }

    @Test
    @DisplayName("a token for another project gets 401 with extensions.code=aud_mismatch")
    void wrongProjectAudience_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);
        HttpResponse<?> response = callFilter(service, token(AUD_PREFIX + OTHER_PROJECT, null));

        assertThat((response == null ? 200 : response.code())).isEqualTo(401);
        assertThat(String.valueOf(response.body())).contains("\"code\":\"aud_mismatch\"");
    }

    @Test
    @DisplayName("a missing aud gets 401 with extensions.code=aud_mismatch")
    void missingAudience_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);

        HttpResponse<?> response = callFilter(service, token(null, null));

        assertThat((response == null ? 200 : response.code())).isEqualTo(401);
        assertThat(String.valueOf(response.body())).contains("\"code\":\"aud_mismatch\"");
    }

    @Test
    @DisplayName("a refresh credential gets 401 with extensions.code=refresh_token_not_accepted")
    void refreshToken_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);

        HttpResponse<?> response = callFilter(service, token(AUD_PREFIX + PROJECT, "refresh"));

        assertThat((response == null ? 200 : response.code())).isEqualTo(401);
        assertThat(String.valueOf(response.body())).contains("\"code\":\"refresh_token_not_accepted\"");
    }

    @Test
    @DisplayName("the matching audience passes through to the chain")
    void matchingAudience_reachesChain() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);
        HttpResponse<?> response = callFilter(service, token(AUD_PREFIX + PROJECT, null));

        assertThat((response == null ? 200 : response.code())).isEqualTo(200);
    }

    @Test
    @DisplayName("with the requirement off an audience-less token still reaches the chain")
    void missingAudience_acceptedWhenFlagOff() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        HttpResponse<?> response = callFilter(service, token(null, null));

        assertThat((response == null ? 200 : response.code())).isEqualTo(200);
    }

    @Test
    @DisplayName("a route with no project in its path is refused, whatever the token says")
    void projectlessPath_isRefusedAndTakesNothingFromTheToken() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        MutableHttpRequest<?> request = HttpRequest.GET("/actuator/health");
        request.header("Authorization", "Bearer " + token(AUD_PREFIX + PROJECT, null));
        MutablePropagatedContext context = MutablePropagatedContext.of(PropagatedContext.empty());

        HttpResponse<?> refusal = new JwtAuthFilter(service, enforcer()).filter(request, context);

        assertThat(refusal).isNotNull();
        assertThat(refusal.code()).isEqualTo(404);
        assertThat(String.valueOf(refusal.body())).contains("\"code\":\"project_not_found\"");
        assertThat(context.getContext().find(RequestContextElement.class)).isEmpty();
    }

    @Test
    @DisplayName("a project-less path that no project route serves is refused with no token at all")
    void projectlessPath_withoutAToken_isRefused() {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        MutablePropagatedContext context = MutablePropagatedContext.of(PropagatedContext.empty());

        HttpResponse<?> refusal = new JwtAuthFilter(service, enforcer()).filter(HttpRequest.GET("/proj/other"), context);

        assertThat(refusal).isNotNull();
        assertThat(refusal.code()).isEqualTo(404);
    }

    @Test
    @DisplayName("a request already admitted as management is not this filter's to refuse")
    void managementRequest_passes() {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        MutableHttpRequest<?> request = HttpRequest.GET("/actuator/health");
        request.setAttribute(KnownProjectFilter.MANAGEMENT_ATTR, true);
        MutablePropagatedContext context = MutablePropagatedContext.of(PropagatedContext.empty());

        assertThat(new JwtAuthFilter(service, enforcer()).filter(request, context)).isNull();
    }
}
