package io.github.excalibase.security;

public record JwtClaims(String userId, String projectId, String orgSlug, String projectName, String role, String email) {}
