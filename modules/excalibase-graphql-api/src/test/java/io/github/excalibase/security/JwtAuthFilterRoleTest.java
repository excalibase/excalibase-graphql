package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaUnavailableException;
import io.github.excalibase.schema.StubbedSchemaManager;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private static MutableHttpRequest<?> request(String bearer, String requestedRole) {
        MutableHttpRequest<?> request = HttpRequest.POST("/" + PROJECT + "/graphql", "");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (requestedRole != null) {
            request.header(RoleResolver.ROLE_HEADER, requestedRole);
        }
        return request;
    }

    /** What the filter decided: a refusal, or the principal and guards it hands to the handler. */
    private static final class Outcome {
        final HttpResponse<?> refusal;
        final Principal principal;
        final boolean rlsRegistered;

        Outcome(HttpResponse<?> refusal, HttpRequest<?> request, MutablePropagatedContext context) {
            this.refusal = refusal;
            this.principal = (Principal) request.getAttribute(SecurityConstants.PRINCIPAL_ATTR).orElse(null);
            this.rlsRegistered = context.getContext().find(RequestContextElement.class)
                    .map(element -> element.guard() != null).orElse(false);
        }

        int status() {
            return refusal.code();
        }

        String body() {
            return String.valueOf(refusal.body());
        }
    }

    private static Outcome run(MutableHttpRequest<?> request, AccessPlans plans) {
        MutablePropagatedContext context = MutablePropagatedContext.of(PropagatedContext.empty());
        HttpResponse<?> refusal = new JwtAuthFilter(jwtService, plans).filter(request, context);
        return new Outcome(refusal, request, context);
    }

    private static Outcome run(MutableHttpRequest<?> request) {
        return run(request, StubbedSchemaManager.withDocument(new SchemaInfo(),
                StubbedSchemaManager.document(PROJECT), (sql, params) -> false));
    }

    @Test
    void unreadableDatabase_refusesTheRequestWith503() throws Exception {
        AccessPlans restoring = mock(AccessPlans.class);
        when(restoring.planFor(any())).thenThrow(new SchemaUnavailableException("being restored",
                new SQLException("Communications link failure")));

        Outcome outcome = run(request(token(Map.of("role", "user")), null), restoring);

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.body()).contains("project_unavailable");
        assertThat(outcome.rlsRegistered).isFalse();
    }

    @Test
    void unreadablePermissions_refuseTheRequestWith503() throws Exception {
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

        Outcome outcome = run(request(token(Map.of("role", "user")), null),
                StubbedSchemaManager.withProvider(new SchemaInfo(), down));

        assertThat(outcome.status()).isEqualTo(503);
        assertThat(outcome.body()).contains("permissions_unavailable");
        assertThat(outcome.rlsRegistered).isFalse();
    }

    @Test
    void theRequestsResolvedEngine_isReleasedWhenTheRequestEnds() throws Exception {
        AccessPlans plans = mock(AccessPlans.class);

        run(request(token(Map.of("role", "user")), null), plans);

        verify(plans).planFor(any());
        verify(plans).requestFinished();
    }

    @Test
    void theRequestsResolvedEngine_isReleasedWhenPermissionsAreUnavailable() throws Exception {
        AccessPlans plans = mock(AccessPlans.class);
        when(plans.planFor(any())).thenThrow(new PermissionsUnavailableException("control plane down"));

        Outcome outcome = run(request(token(Map.of("role", "user")), null), plans);

        assertThat(outcome.status()).isEqualTo(503);
        verify(plans).requestFinished();
    }

    @Test
    void noToken_runsAsAnon() {
        Outcome outcome = run(request(null, null));

        assertThat(outcome.principal.role()).isEqualTo(Principal.ANON);
        assertThat(outcome.rlsRegistered).isTrue();
    }

    @Test
    void userToken_runsAsItsDefaultRole() throws Exception {
        Outcome outcome = run(request(token(Map.of("role", "user")), null));

        assertThat(outcome.principal.role()).isEqualTo("user");
        assertThat(outcome.principal.claims().userId()).isEqualTo("42");
    }

    @Test
    void allowedRoleHeader_picksThatRole() throws Exception {
        Outcome outcome = run(request(token(Map.of("role", "user", "allowed_roles", List.of("user", "editor"))), "editor"));

        assertThat(outcome.principal.role()).isEqualTo("editor");
    }

    @Test
    void roleHeaderNotAllowed_is403WithCode() throws Exception {
        Outcome outcome = run(request(token(Map.of("role", "user")), "editor"));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.body()).contains("\"code\":\"role_not_allowed\"");
    }

    @Test
    void noTokenWithNonAnonRoleHeader_is403WithCode() {
        Outcome outcome = run(request(null, "user"));

        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.body()).contains("\"code\":\"role_not_allowed\"");
    }

    @Test
    void tokenWithoutRole_is401InvalidRoleClaim() throws Exception {
        Outcome outcome = run(request(token(Map.of()), null));

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.body()).contains("\"code\":\"invalid_role_claim\"");
    }

    @Test
    void serviceToken_bypassesAndRegistersNoRowSecurity() throws Exception {
        Outcome outcome = run(request(token(Map.of("role", "service", "scope", "service")), null));

        assertThat(outcome.principal.bypass()).isTrue();
        assertThat(outcome.rlsRegistered).isFalse();
    }

    @Test
    void serviceTokenActingAsUser_carriesTheSessionHeadersAndRowSecurity() throws Exception {
        MutableHttpRequest<?> request = request(token(Map.of("role", "service", "scope", "service")), "user");
        request.header("X-Excalibase-User-Id", "77");

        Outcome outcome = run(request);

        Principal principal = outcome.principal;
        assertThat(principal.role()).isEqualTo("user");
        assertThat(principal.bypass()).isFalse();
        assertThat(principal.impersonationHeaders()).containsEntry("x-excalibase-user-id", "77");
        assertThat(outcome.rlsRegistered).isTrue();
    }
}
