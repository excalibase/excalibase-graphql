package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.PermissionDocs;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.micronaut.web.router.Router;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * EXC-737: a table named like a route word ({@code graphql}) is reached on the REST surface at
 * {@code /{project}/api/v1/graphql}. That path ends in the GraphQL suffix, so it must not be taken for the GraphQL
 * route: it gets the project check, the token check and the role's permissions exactly like {@code /api/v1/orders}.
 * The permissions document grants anon nothing on {@code graphql}.
 */
class ReservedTableNameIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-reserved";
    private static final String ANON_SECRET = "shhh";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-reserved-table.sql");

    @Inject
    Router router;

    static ECPrivateKey privateKey;
    static HttpServer controlPlane;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = gen.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            controlPlane = HttpServer.create(new InetSocketAddress(0), 0);
            controlPlane.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] payload = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.getResponseBody().close();
            });
            String document = PermissionDocs.document(PROJECT, 1,
                    PermissionDocs.entry("public.orders", "anon", PermissionDocs.selectAll()),
                    PermissionDocs.entry("public.graphql", "user", PermissionDocs.selectAll()));
            PermissionDocs.serve(controlPlane, PROJECT, () -> document);
            controlPlane.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set up the control plane stub", e);
        }
    }

    @AfterAll
    static void teardown() {
        if (controlPlane != null) controlPlane.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", PROJECT);
        properties.put("app.security.auth.jwks-url", "http://localhost:" + controlPlane.getAddress().getPort() + "/.well-known/jwks.json");
        properties.put("app.security.rls.policy-url", "http://localhost:" + controlPlane.getAddress().getPort() + "/api");
        properties.put("app.security.rls.policy-pat", "test-pat");
        properties.put("app.security.rls.policy-ttl-ms", "0");
        return properties;
    }

    private String token(String project) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf("u-1")).claim("projectId", project)
                .claim("role", "user").claim("scope", "authenticated")
                .audience("excalibase:" + project).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z"))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    /** The permissions document: anon reads orders; the signed-in role reads graphql; nobody else gets graphql. */
    private HttpAnswer getGraphqlTable(String path, String... headers) {
        List<String> all = new ArrayList<>(List.of("Accept-Profile", "public"));
        all.addAll(List.of(headers));
        return http.get("/" + PROJECT + path, all.toArray(String[]::new));
    }

    @Test
    @DisplayName("the control: anon reads a table it is granted, and is refused one that does not exist")
    void controlPermissionsApply() {
        assertThat(getGraphqlTable("/api/v1/orders").status()).isEqualTo(200);
        assertThat(getGraphqlTable("/api/v1/missing_table").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a role granted the table named graphql reads it at /api/v1/graphql: the path is a REST table, not the GraphQL route")
    void grantedRoleReadsTheTable() throws Exception {
        HttpAnswer answer = getGraphqlTable("/api/v1/graphql", "Authorization", "Bearer " + token(PROJECT));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains(ANON_SECRET);
    }

    @Test
    @DisplayName("anon asking the REST table named graphql is refused by its permissions, however the path is spelled")
    void anonDoesNotReadAGrantlessTableNamedGraphql() {
        List<String> served = new ArrayList<>();
        for (String path : List.of("/api/v1/graphql", "/api/v1/graphql?select=*", "/api/v1/GRAPHQL", "/api/v1/%67raphql",
                "/api/v1/graphql/", "/api/v1//graphql", "/api/v1/./graphql", "/api/v1/graphql;x=1",
                "/api%2Fv1/graphql", "/%61pi/v1/graphql")) {
            HttpAnswer answer = getGraphqlTable(path);
            if (answer.status() == 200 || answer.body().contains(ANON_SECRET)) {
                served.add(path + " -> " + answer.status());
            }
        }
        assertThat(served).as("anon was served the graphql table").isEmpty();
        HttpAnswer plain = getGraphqlTable("/api/v1/graphql");
        assertThat(plain.status()).isEqualTo(404);
        assertThat(plain.body()).as("refused by the role's permissions, not as an unparsed project").doesNotContain("project_not_found");
    }

    @Test
    @DisplayName("on /api/v1/graphql an unserved project is the project 404, and a token for another project is refused")
    void projectIsCheckedOnTheReservedWord() throws Exception {
        HttpAnswer unknown = http.get("/other-proj/api/v1/graphql", "Accept-Profile", "public");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).contains("project_not_found");

        HttpAnswer wrongProject = getGraphqlTable("/api/v1/graphql", "Authorization", "Bearer " + token("someone-else"));
        assertThat(wrongProject.status()).isIn(401, 403);
        assertThat(wrongProject.body()).doesNotContain(ANON_SECRET);
    }

    @Test
    @DisplayName("every project route, with any route word in each variable, still names the project the router matched")
    void everyRouteKeepsItsProjectWhateverFillsItsVariables() {
        List<String> misread = new ArrayList<>();
        router.uriRoutes().map(route -> route.getUriMatchTemplate().toString())
                .filter(template -> template.startsWith("/{projectId}/graphql") || template.startsWith("/{projectId}/api/v1")).distinct().forEach(template -> {
                    for (String word : List.of("graphql", "realtime", "api", "v1", "rpc", "x")) {
                        String path = template.replace("{projectId}", PROJECT).replaceAll("\\{[^}]+}", word);
                        if (!PROJECT.equals(ProjectPath.fromUri(path))) {
                            misread.add(path + " -> " + ProjectPath.fromUri(path));
                        }
                    }
                });

        assertThat(misread).as("route paths whose project the security filters would not read").isEmpty();
    }
}
