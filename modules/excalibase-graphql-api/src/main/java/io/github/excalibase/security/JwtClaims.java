package io.github.excalibase.security;

public record JwtClaims(long userId, String projectId, String role, String email) {}
