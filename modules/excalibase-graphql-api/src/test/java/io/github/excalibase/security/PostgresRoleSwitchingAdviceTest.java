package io.github.excalibase.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresRoleSwitchingAdviceTest {

    private final PostgresRoleSwitchingAdvice advice = new PostgresRoleSwitchingAdvice();

    @Test
    @DisplayName("maps RoleNotAllowedException to 403 with GraphQL-shaped error body")
    void mapsToForbiddenWithGraphqlErrorEnvelope() {
        RoleNotAllowedException ex = new RoleNotAllowedException("role 'hacker' not allowed");

        ResponseEntity<Object> response = advice.handleRoleNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().get("message")).isEqualTo("role 'hacker' not allowed");
    }

    @Test
    @DisplayName("message text from exception flows verbatim into the envelope")
    void preservesMessageText() {
        RoleNotAllowedException ex = new RoleNotAllowedException("Invalid role: must match [a-zA-Z_][a-zA-Z0-9_]*");

        ResponseEntity<Object> response = advice.handleRoleNotAllowed(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors.getFirst().get("message"))
                .isEqualTo("Invalid role: must match [a-zA-Z_][a-zA-Z0-9_]*");
    }
}
