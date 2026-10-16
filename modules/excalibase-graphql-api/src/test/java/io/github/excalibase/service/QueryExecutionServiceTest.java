package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler.CompiledQuery;
import io.github.excalibase.compiler.TwoPhaseWrite;
import io.github.excalibase.spi.MutationExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryExecutionServiceTest {

    @Mock NamedParameterJdbcTemplate namedJdbc;
    @Mock DataSource dataSource;

    private QueryExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new QueryExecutionService(namedJdbc, dataSource, objectMapper);
    }

    private CompiledQuery selectQuery(String sql) {
        return new CompiledQuery(sql, Map.of());
    }

    @Test
    @DisplayName("executeQuery wraps the JSON result under the 'data' key")
    void executeQuery_wrapsJsonInData() throws Exception {
        when(namedJdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("{\"users\":[{\"id\":1}]}");

        ResponseEntity<Object> response = service.executeQuery(selectQuery("SELECT ..."), new MapSqlParameterSource());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("data");
    }

    @Test
    @DisplayName("executeQuery returns empty data map when JDBC returns null")
    void executeQuery_nullResult_returnsEmptyDataMap() throws Exception {
        when(namedJdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(null);

        ResponseEntity<Object> response = service.executeQuery(selectQuery("SELECT ..."), new MapSqlParameterSource());

        assertThat(response.getBody()).isEqualTo(Map.of("data", Map.of()));
    }

    @Test
    @DisplayName("a two-phase write runs every statement on one connection and commits once")
    void twoPhaseWrite_runsOnOneConnectionInOneTransaction() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        MutationExecutor exec = (c, p, jdbc) -> {
            jdbc.getJdbcTemplate().execute("INSERT INTO users (name) VALUES ('a')");
            jdbc.getJdbcTemplate().execute("SELECT 1");
            return "{\"createUser\":{\"id\":7}}";
        };

        ResponseEntity<Object> response = service.executeInContext(twoPhase(), new MapSqlParameterSource(), exec);

        verify(dataSource, times(1)).getConnection();
        verify(connection, times(2)).createStatement();
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertThat(response.getBody()).isEqualTo(Map.of("data", new ObjectMapper().readTree("{\"createUser\":{\"id\":7}}")));
    }

    @Test
    @DisplayName("a two-phase write that fails rolls back everything it wrote")
    void twoPhaseWrite_failure_rollsBack() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        MutationExecutor exec = (c, p, jdbc) -> {
            throw new IllegalArgumentException("duplicate key");
        };

        assertThatThrownBy(() -> service.executeInContext(twoPhase(), new MapSqlParameterSource(), exec))
                .isInstanceOf(IllegalArgumentException.class);

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }

    private static CompiledQuery twoPhase() {
        return CompiledQuery.twoPhase(new TwoPhaseWrite() { }, Map.of());
    }
}
