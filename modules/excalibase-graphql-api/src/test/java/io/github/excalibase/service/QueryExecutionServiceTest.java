package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler.CompiledQuery;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("executeTwoPhase delegates to MutationExecutor and wraps the result")
    void executeTwoPhase_delegatesToMutationExecutor() throws Exception {
        CompiledQuery compiled = selectQuery("");
        MutationExecutor exec = (c, p, j) -> "{\"insertUser\":{\"id\":7}}";

        ResponseEntity<Object> response = service.executeTwoPhase(compiled, new MapSqlParameterSource(), exec);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("data");
    }

    @Test
    @DisplayName("executeTwoPhase returns empty data when executor returns null")
    void executeTwoPhase_nullResult_returnsEmptyDataMap() throws Exception {
        MutationExecutor exec = (c, p, j) -> null;

        ResponseEntity<Object> response = service.executeTwoPhase(selectQuery(""), new MapSqlParameterSource(), exec);

        assertThat(response.getBody()).isEqualTo(Map.of("data", Map.of()));
    }
}
