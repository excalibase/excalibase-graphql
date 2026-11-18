package io.github.excalibase.metadata;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A local identity provider: serves a JWKS and an OIDC discovery document, and signs tokens with its keys. */
final class JwksStub implements AutoCloseable {

    static final String AUDIENCE = "https://api.demo";

    final RSAKey rsa;
    final ECKey ec;
    final AtomicInteger jwksHits = new AtomicInteger();
    private final HttpServer server;
    private volatile List<JWK> served;
    private volatile boolean failing;
    private volatile String discoveryIssuer;
    private volatile String discoveryJwksUri;
    private volatile String redirectTarget;

    JwksStub() {
        try {
            rsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
            ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
            served = List.of(rsa.toPublicJWK(), ec.toPublicJWK());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                jwksHits.incrementAndGet();
                if (failing) {
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    reply(exchange, new JWKSet(served).toString());
                }
                exchange.close();
            });
            server.createContext("/.well-known/openid-configuration", exchange -> {
                if (redirectTarget != null) {
                    redirect(exchange);
                } else {
                    reply(exchange, "{\"issuer\":\"" + (discoveryIssuer == null ? issuer() : discoveryIssuer)
                            + "\",\"jwks_uri\":\"" + (discoveryJwksUri == null ? jwksUrl() : discoveryJwksUri)
                            + "\"}");
                }
                exchange.close();
            });
            server.createContext("/moved", exchange -> {
                redirect(exchange);
                exchange.close();
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", redirectTarget);
        exchange.sendResponseHeaders(302, -1);
    }

    /** Answers /moved (the key set) and the discovery document with a redirect to {@code target}. */
    void redirectTo(String target) {
        redirectTarget = target;
    }

    String movedUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/moved";
    }

    void discoveryNames(String issuerName, String jwksUri) {
        discoveryIssuer = issuerName;
        discoveryJwksUri = jwksUri;
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    String jwksUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    void serve(JWK... keys) {
        served = List.of(keys);
    }

    void failFromNowOn() {
        failing = true;
    }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject("auth0|alice").issuer(issuer()).audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)));
    }

    String rs256(JWTClaimsSet claims) {
        try {
            return sign(new RSASSASigner(rsa), JWSAlgorithm.RS256, rsa.getKeyID(), claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String es256(JWTClaimsSet claims) {
        try {
            return sign(new ECDSASigner(ec), JWSAlgorithm.ES256, ec.getKeyID(), claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(JWSSigner signer, JWSAlgorithm alg, String keyId, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(keyId).build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hs256(byte[] secret, JWTClaimsSet claims) {
        try {
            return sign(new MACSigner(secret), JWSAlgorithm.HS256, null, claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String unsigned(JWTClaimsSet claims) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8)) + ".";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
