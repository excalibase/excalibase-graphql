package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.rls.InMemoryPolicyProvider;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.rls.jdbc.QuoteStyle;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    private static RlsPolicyEnforcer enforcer() {
        return new RlsPolicyEnforcer(new InMemoryPolicyProvider(), QuoteStyle.ANSI);
    }

    private String token(String audience, String tokenUse) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("alice@test.com")
                .claim("userId", 42)
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

    private MockHttpServletResponse callFilter(JwtService jwtService, String bearer, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/" + PROJECT + "/graphql");
        request.setRequestURI("/" + PROJECT + "/graphql");
        request.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthFilter(jwtService, enforcer()).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a token for another project gets 401 with extensions.code=aud_mismatch")
    void wrongProjectAudience_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                callFilter(service, token(AUD_PREFIX + OTHER_PROJECT, null), chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"aud_mismatch\"");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a missing aud gets 401 with extensions.code=aud_mismatch")
    void missingAudience_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);

        MockHttpServletResponse response = callFilter(service, token(null, null), mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"aud_mismatch\"");
    }

    @Test
    @DisplayName("a refresh credential gets 401 with extensions.code=refresh_token_not_accepted")
    void refreshToken_401WithCode() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);

        MockHttpServletResponse response =
                callFilter(service, token(AUD_PREFIX + PROJECT, "refresh"), mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"refresh_token_not_accepted\"");
    }

    @Test
    @DisplayName("the matching audience passes through to the chain")
    void matchingAudience_reachesChain() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(true, AUD_PREFIX);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = callFilter(service, token(AUD_PREFIX + PROJECT, null), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("with the requirement off an audience-less token still reaches the chain")
    void missingAudience_acceptedWhenFlagOff() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = callFilter(service, token(null, null), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a route with no project in its path gets no project from the token either")
    void projectlessPath_doesNotTakeTheTokensProject() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(false, AUD_PREFIX);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRequestURI("/actuator/health");
        request.addHeader("Authorization", "Bearer " + token(AUD_PREFIX + PROJECT, null));
        String[] tenantSeen = {"unset"};
        FilterChain chain = (req, res) -> tenantSeen[0] = TenantContext.getTenantId();

        new JwtAuthFilter(service, enforcer()).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(tenantSeen[0]).isNull();
    }
}
