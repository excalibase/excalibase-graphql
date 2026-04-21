package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler.CompiledQuery;
import io.github.excalibase.compiler.SqlCompiler.ProcedureCallInfo;
import io.github.excalibase.compiler.SqlCompiler.ProcedureCallParam;
import io.github.excalibase.security.JwtClaims;
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
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        return new CompiledQuery(sql, Map.of(), null, null, false, null);
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

    @Test
    @DisplayName("executeProcedureCall rejects an unsafe procedure name")
    void executeProcedureCall_unsafeName_throws() {
        ProcedureCallInfo info = new ProcedureCallInfo("public.my$proc; DROP TABLE --", List.of());
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "myProc", info);

        assertThatThrownBy(() -> service.executeProcedureCall(compiled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid procedure name");
    }

    @Test
    @DisplayName("executeProcedureCall sets IN param with setObject and executes CALL")
    void executeProcedureCall_inParam_setsViaSetObject() throws Exception {
        Connection conn = mockConn();
        CallableStatement cs = mockCs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareCall(anyString())).thenReturn(cs);

        ProcedureCallInfo info = new ProcedureCallInfo("public.my_proc",
                List.of(new ProcedureCallParam("rental_id", "IN", "integer", 42)));
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "myProc", info);

        ResponseEntity<Object> response = service.executeProcedureCall(compiled);

        verify(cs).setObject(1, 42, Types.BIGINT);
        verify(cs).execute();
        assertThat(response.getBody()).isEqualTo(Map.of("data", Map.of("myProc", "{\"result\":\"OK\"}")));
    }

    @Test
    @DisplayName("executeProcedureCall sets null via setNull when IN param value is null")
    void executeProcedureCall_nullInParam_setsNull() throws Exception {
        Connection conn = mockConn();
        CallableStatement cs = mockCs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareCall(anyString())).thenReturn(cs);

        ProcedureCallInfo info = new ProcedureCallInfo("public.p",
                List.of(new ProcedureCallParam("x", "IN", "varchar", null)));
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "p", info);

        service.executeProcedureCall(compiled);

        verify(cs).setNull(eq(1), anyInt());
        verify(cs, never()).setObject(anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("executeProcedureCall registers OUT parameters and collects results")
    void executeProcedureCall_outParam_registeredAndCollected() throws Exception {
        Connection conn = mockConn();
        CallableStatement cs = mockCs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareCall(anyString())).thenReturn(cs);
        when(cs.getObject(1)).thenReturn(true);

        ProcedureCallInfo info = new ProcedureCallInfo("public.ok",
                List.of(new ProcedureCallParam("paid", "OUT", "boolean", null)));
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "ok", info);

        ResponseEntity<Object> response = service.executeProcedureCall(compiled);

        verify(cs).registerOutParameter(1, Types.BOOLEAN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(((String) data.get("ok"))).contains("paid").contains("true");
    }

    @Test
    @DisplayName("executeProcedureCall handles INOUT params — sets input AND registers output")
    void executeProcedureCall_inoutParam_bothInAndOut() throws Exception {
        Connection conn = mockConn();
        CallableStatement cs = mockCs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareCall(anyString())).thenReturn(cs);
        when(cs.getObject(1)).thenReturn(10);

        ProcedureCallInfo info = new ProcedureCallInfo("public.inout_p",
                List.of(new ProcedureCallParam("counter", "INOUT", "integer", 5)));
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "inoutP", info);

        service.executeProcedureCall(compiled);

        verify(cs).setObject(1, 5, Types.BIGINT);
        verify(cs).registerOutParameter(1, Types.BIGINT);
    }

    @Test
    @DisplayName("executeProcedureCall with zero params builds CALL name() without placeholders")
    void executeProcedureCall_noParams_buildsEmptyArgCall() throws Exception {
        Connection conn = mockConn();
        CallableStatement cs = mockCs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareCall(anyString())).thenReturn(cs);

        ProcedureCallInfo info = new ProcedureCallInfo("public.no_args", List.of());
        CompiledQuery compiled = new CompiledQuery("", Map.of(), null, null, true, "noArgs", info);

        service.executeProcedureCall(compiled);

        verify(conn).prepareCall("CALL public.no_args()");
        verify(cs).execute();
    }

    @Test
    @DisplayName("executeWithRlsContext sets config, runs query, commits, and wraps result")
    void executeWithRlsContext_setsConfigAndCommits() throws Exception {
        Connection conn = mockConn();
        PreparedStatement configStmt = mockPs();
        PreparedStatement querySt = mockPs();
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString()))
                .thenReturn(configStmt, configStmt, configStmt, querySt);
        when(querySt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("{\"users\":[{\"id\":1}]}");

        CompiledQuery compiled = new CompiledQuery("SELECT 1", Map.of(), null, null, false, null);
        JwtClaims claims = new JwtClaims("42", "p1", "acme", "app", "admin", "u@e.com", null, 0L);

        ResponseEntity<Object> response = service.executeWithRlsContext(compiled, "42", claims);

        verify(configStmt, times(3)).execute();
        verify(conn).commit();
        verify(conn).setAutoCommit(false);
        verify(conn).setAutoCommit(true);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("executeWithRlsContext rolls back when execution fails")
    void executeWithRlsContext_sqlError_rollsBack() throws Exception {
        Connection conn = mockConn();
        PreparedStatement configStmt = mockPs();
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(anyString())).thenReturn(configStmt);
        when(configStmt.execute()).thenThrow(new java.sql.SQLException("boom"));

        CompiledQuery compiled = new CompiledQuery("SELECT 1", Map.of(), null, null, false, null);

        assertThatThrownBy(() -> service.executeWithRlsContext(compiled, "42", null))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("boom");
        verify(conn).rollback();
        verify(conn).setAutoCommit(true);
    }

    @Test
    @DisplayName("executeWithRlsContext skips project/role set_config when jwtClaims is null")
    void executeWithRlsContext_nullClaims_skipsProjectAndRole() throws Exception {
        Connection conn = mockConn();
        PreparedStatement configStmt = mockPs();
        PreparedStatement querySt = mockPs();
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(false);
        when(conn.prepareStatement(anyString())).thenReturn(configStmt, querySt);
        when(querySt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        CompiledQuery compiled = new CompiledQuery("SELECT 1", Map.of(), null, null, false, null);

        service.executeWithRlsContext(compiled, "42", null);

        verify(configStmt, times(1)).execute();
    }

    private Connection mockConn() throws Exception {
        return org.mockito.Mockito.mock(Connection.class);
    }

    private CallableStatement mockCs() {
        return org.mockito.Mockito.mock(CallableStatement.class);
    }

    private PreparedStatement mockPs() {
        return org.mockito.Mockito.mock(PreparedStatement.class);
    }
}
