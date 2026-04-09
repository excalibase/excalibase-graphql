package io.github.excalibase.security;

public record JwtClaims(long userId, String projectId, String orgSlug, String projectName, String role, String email) {}
