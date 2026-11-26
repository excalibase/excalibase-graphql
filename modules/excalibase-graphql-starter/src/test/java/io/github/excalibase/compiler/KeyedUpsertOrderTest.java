package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A keyed upsert on a dialect that inserts first never takes the lookup's gap lock for a new key (EXC-816). */
class KeyedUpsertOrderTest {

    private static final String INSERT_SQL = "INSERT INTO t (id) VALUES (:id)";
    private static final String UPDATE_PREFIX = "UPDATE";
    private static final int MYSQL_DUPLICATE = 1062;

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SqlDialect dialect = mock(SqlDialect.class, CALLS_REAL_METHODS);
    private KeyedWrites.Insert insert;
    private KeyedWrites.Condition set;
    private KeyedWrites.Condition everyRow;
    private KeyedWrites writes;

    @BeforeEach
    void setUp() {
        when(dialect.quoteIdentifier(anyString())).thenAnswer(call -> "`" + call.getArgument(0) + "`");
        when(jdbc.getJdbcOperations()).thenReturn(mock(JdbcOperations.class));
        when(jdbc.queryForObject(anyString(), anyMap(), ArgumentMatchers.<RowMapper<long[]>>any()))
                .thenReturn(new long[] {1, 0});
        insert = new KeyedWrites.Insert(INSERT_SQL, Map.of("id", 1), Map.of("id", 1));
        set = new KeyedWrites.Condition("t.`name` = :n", Map.of("n", "x"));
        everyRow = new KeyedWrites.Condition("", Map.of());
        writes = new KeyedWrites(dialect, new KeyedWrites.Target("t", "`t`", "t", List.of("id"), false), null, null);
    }

    private void insertsFirst(boolean on) {
        when(dialect.insertsBeforeLookup()).thenReturn(on);
        when(dialect.isDuplicateKey(any())).thenAnswer(call -> call.getArgument(0) instanceof DuplicateKeyException);
    }

    private void insertRaises(RuntimeException failure) {
        when(jdbc.update(eq(INSERT_SQL), any(SqlParameterSource.class), any(KeyHolder.class))).thenThrow(failure);
    }

    private void insertWrites(int rows) {
        when(jdbc.update(eq(INSERT_SQL), any(SqlParameterSource.class), any(KeyHolder.class))).thenReturn(rows);
    }

    private void lookupFinds(boolean found) {
        when(jdbc.queryForList(contains("FOR UPDATE"), anyMap(), eq(Integer.class)))
                .thenReturn(found ? List.of(1) : List.of());
    }

    private static DuplicateKeyException duplicate() {
        return new DuplicateKeyException("duplicate", new SQLException("duplicate", "23000", MYSQL_DUPLICATE));
    }

    @Test
    void aNewKey_isInsertedWithoutAnyLockingLookup_whenTheDialectInsertsFirst() throws Exception {
        insertsFirst(true);
        insertWrites(1);

        writes.upsert(insert, set, everyRow).run(jdbc, false);

        verify(jdbc, never()).queryForList(anyString(), anyMap(), eq(Integer.class));
        verify(jdbc, never()).update(contains(UPDATE_PREFIX), anyMap());
    }

    @Test
    void aDuplicateKey_fallsBackToLockingTheRowAndUpdatingIt() throws Exception {
        insertsFirst(true);
        insertRaises(duplicate());
        lookupFinds(true);

        writes.upsert(insert, set, everyRow).run(jdbc, false);

        var order = inOrder(jdbc);
        order.verify(jdbc).update(eq(INSERT_SQL), any(SqlParameterSource.class), any(KeyHolder.class));
        order.verify(jdbc).queryForList(contains("FOR UPDATE"), anyMap(), eq(Integer.class));
        order.verify(jdbc).update(contains(UPDATE_PREFIX), anyMap());
    }

    @Test
    void aDuplicateOfAnotherUniqueKey_isTheDatabasesOwnError() {
        DuplicateKeyException failure = duplicate();
        insertsFirst(true);
        insertRaises(failure);
        lookupFinds(false);
        KeyedWrite write = writes.upsert(insert, set, everyRow);

        assertThatThrownBy(() -> write.run(jdbc, false)).isSameAs(failure);
        verify(jdbc, never()).update(contains(UPDATE_PREFIX), anyMap());
    }

    @Test
    void anyOtherFailure_isNotTurnedIntoAnUpdate() {
        IllegalStateException failure = new IllegalStateException("not a duplicate");
        insertsFirst(true);
        insertRaises(failure);
        KeyedWrite write = writes.upsert(insert, set, everyRow);

        assertThatThrownBy(() -> write.run(jdbc, false)).isSameAs(failure);
        verify(jdbc, never()).queryForList(anyString(), anyMap(), eq(Integer.class));
    }

    @Test
    void aDialectThatLooksFirst_updatesAnExistingRowWithoutInserting() throws Exception {
        insertsFirst(false);
        lookupFinds(true);

        String row = writes.upsert(insert, set, everyRow).run(jdbc, false);

        assertThat(row).isNull();
        verify(jdbc, never()).update(eq(INSERT_SQL), any(SqlParameterSource.class), any(KeyHolder.class));
        verify(jdbc).update(contains(UPDATE_PREFIX), anyMap());
    }

    @Test
    void anOnConflictUpsert_stillLooksFirst_becauseItsRowMayNotSendThePrimaryKey() throws Exception {
        insertsFirst(true);
        when(jdbc.query(contains("FOR UPDATE"), anyMap(), ArgumentMatchers.<RowMapper<List<Object>>>any()))
                .thenReturn(List.<List<Object>>of(List.of(1)));
        KeyedWrites.Insert byEmail = new KeyedWrites.Insert(INSERT_SQL, Map.of("email", "a"), Map.of("email", "a"));

        writes.upsertOn(List.of("email"), byEmail, set, everyRow).run(jdbc, false);

        verify(jdbc, never()).update(eq(INSERT_SQL), any(SqlParameterSource.class), any(KeyHolder.class));
        verify(jdbc).update(contains(UPDATE_PREFIX), anyMap());
    }
}
