package io.github.excalibase.postgres.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresUserContextServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PostgresUserContextService service;

    @BeforeEach
    void setUp() {
        service = new PostgresUserContextService(jdbcTemplate);
    }

    @Test
    void shouldSetUserIdSessionVariable() {
        // Given
        String userId = "user-123";

        // When
        service.setUserContext(userId, null);

        // Then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sqlCaptor.capture());

        String executedSql = sqlCaptor.getValue();
        assertThat(executedSql).isEqualTo("SET request.user_id = 'user-123'");
    }

    @Test
    void shouldSetUserIdAndAdditionalClaims() {
        // Given
        String userId = "user-123";
        Map<String, String> claims = new HashMap<>();
        claims.put("tenant_id", "acme");
        claims.put("department_id", "5");

        // When
        service.setUserContext(userId, claims);

        // Then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(3)).execute(sqlCaptor.capture());

        var executedSqls = sqlCaptor.getAllValues();
        assertThat(executedSqls).contains(
            "SET request.user_id = 'user-123'",
            "SET request.jwt.tenant_id = 'acme'",
            "SET request.jwt.department_id = '5'"
        );
    }

    @Test
    void shouldSkipWhenUserIdIsNull() {
        // When
        service.setUserContext(null, null);

        // Then
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void shouldSkipWhenUserIdIsEmpty() {
        // When
        service.setUserContext("", null);

        // Then
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void shouldSkipNullClaimValues() {
        // Given
        String userId = "user-123";
        Map<String, String> claims = new HashMap<>();
        claims.put("tenant_id", "acme");
        claims.put("department_id", null); // null value

        // When
        service.setUserContext(userId, claims);

        // Then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture()); // Only 2: user_id + tenant_id

        var executedSqls = sqlCaptor.getAllValues();
        assertThat(executedSqls).contains(
            "SET request.user_id = 'user-123'",
            "SET request.jwt.tenant_id = 'acme'"
        );
        assertThat(executedSqls).noneMatch(sql -> sql.contains("department_id"));
    }

    @Test
    void shouldSanitizeVariableName() {
        // Given
        String userId = "user-123";
        Map<String, String> claims = new HashMap<>();
        claims.put("tenant-id", "acme"); // Contains dash

        // When
        service.setUserContext(userId, claims);

        // Then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());

        var executedSqls = sqlCaptor.getAllValues();
        // Dash should be replaced with underscore
        assertThat(executedSqls).anyMatch(sql -> sql.contains("request.jwt.tenant_id"));
    }

    @Test
    void shouldEscapeSingleQuotesInValue() {
        // Given
        String userId = "user-123";
        Map<String, String> claims = new HashMap<>();
        claims.put("name", "O'Brien"); // Contains single quote

        // When
        service.setUserContext(userId, claims);

        // Then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());

        var executedSqls = sqlCaptor.getAllValues();
        // Single quote should be escaped
        assertThat(executedSqls).anyMatch(sql -> sql.contains("O''Brien"));
    }

    @Test
    void shouldHandleJdbcTemplateException() {
        // Given
        String userId = "user-123";
        doThrow(new RuntimeException("Database error")).when(jdbcTemplate).execute(anyString());

        // When/Then - should not throw exception
        assertThatCode(() -> service.setUserContext(userId, null))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldClearUserContext() {
        // When
        service.clearUserContext();

        // Then
        verify(jdbcTemplate).execute("RESET ALL");
    }

    @Test
    void shouldHandleClearUserContextException() {
        // Given
        doThrow(new RuntimeException("Database error")).when(jdbcTemplate).execute("RESET ALL");

        // When/Then - should not throw exception
        assertThatCode(() -> service.clearUserContext())
            .doesNotThrowAnyException();
    }
}
