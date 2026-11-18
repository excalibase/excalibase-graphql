package io.github.excalibase.security;

/** Verifies a bearer token and says who it is for; the metadata document's JWT settings supply one. */
@FunctionalInterface
public interface TokenVerifier {

    /**
     * @param expectedProjectId the project in the request path, or null when the route carries none
     * @throws JwtVerificationException the token cannot be trusted
     */
    JwtClaims verify(String token, String expectedProjectId);
}
