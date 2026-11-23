package io.github.excalibase.config.datasource;

import io.github.excalibase.errors.DataErrorException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CutResultAwareJdbcTemplateTest {

    private static final int FULL = 1024;
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private CutResultAwareJdbcTemplate template;

    @BeforeEach
    void connect() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(statement.executeUpdate()).thenReturn(1500);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(rows.getMetaData()).thenReturn(metadata);
        template = new CutResultAwareJdbcTemplate(dataSource);
    }

    private static SQLWarning warnings(int count) {
        SQLWarning first = new SQLWarning("Data truncated", "01000", 1265);
        SQLWarning tail = first;
        for (int i = 1; i < count; i++) {
            SQLWarning next = new SQLWarning("Data truncated", "01000", 1265);
            tail.setNextWarning(next);
            tail = next;
        }
        return first;
    }

    private void answers(String value) throws Exception {
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(value);
    }

    @Test
    void aFullWarningListBesideANullAnswer_isRefused() throws Exception {
        answers(null);
        when(statement.getWarnings()).thenReturn(warnings(FULL));

        assertThatThrownBy(() -> template.queryForObject("SELECT ?", String.class, 1))
                .isInstanceOf(DataErrorException.class);
    }

    @Test
    void aFullWarningListBesideARealAnswer_isAnswered() throws Exception {
        answers("[1]");
        when(statement.getWarnings()).thenReturn(warnings(FULL));

        assertThat(template.queryForObject("SELECT ?", String.class, 1)).isEqualTo("[1]");
    }

    @Test
    void aBulkWriteWithManyWarnings_succeeds() throws Exception {
        when(statement.getWarnings()).thenReturn(warnings(FULL));

        assertThat(template.update("INSERT INTO t SELECT ?", 1)).isEqualTo(1500);
    }

    @Test
    void aNullAnswerWithFewWarnings_isStillNoData() throws Exception {
        answers(null);
        when(statement.getWarnings()).thenReturn(warnings(3));

        assertThat(template.queryForObject("SELECT ?", String.class, 1)).isNull();
    }

    @Test
    void aNullAnswerLeavesNothingBehind_forTheNextWriteOnTheSameThread() throws Exception {
        answers(null);
        when(statement.getWarnings()).thenReturn(warnings(3));
        assertThat(template.queryForObject("SELECT ?", String.class, 1)).isNull();

        when(statement.getWarnings()).thenReturn(warnings(FULL));

        assertThat(template.update("INSERT INTO t SELECT ?", 1)).isEqualTo(1500);
    }

    @Test
    void aRowWhoseEveryValueIsNull_isAMissingAnswer() throws Exception {
        ResultSetMetaData metadata = rows.getMetaData();
        when(metadata.getColumnName(1)).thenReturn("body");
        when(metadata.getColumnLabel(1)).thenReturn("body");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(null);
        when(statement.getWarnings()).thenReturn(warnings(FULL));

        assertThatThrownBy(() -> template.queryForMap("SELECT ?", 1)).isInstanceOf(DataErrorException.class);
    }
}
