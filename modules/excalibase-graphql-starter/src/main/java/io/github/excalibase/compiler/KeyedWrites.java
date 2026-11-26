package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.security.PermissionCheckFailedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Writes on a database without RETURNING (MySQL), for REST and GraphQL alike. The written rows are read back by primary key: the key
 * sent or generated for an insert, the keys an update matched (locked before it runs, then followed if it
 * changes them), and a delete's rows are read, locked, before they go. A permission check is evaluated on
 * the rows found again by those keys, and every written row must be found.
 */
public final class KeyedWrites {

    private static final String KEY_PARAM = "rk_";
    private static final String CONFLICT_PARAM = "cf_";

    /** How a written row reads: its JSON object over {@code alias}, and the select filter it must pass. */
    public record ReadBack(String objectSql, String readable, Map<String, Object> params, boolean aggregate) {
        public ReadBack(String objectSql, String readable, Map<String, Object> params) {
            this(objectSql, readable, params, false);
        }

        /** One object aggregated over the written rows, e.g. {@code affected_rows} for a caller who cannot read them. */
        public static ReadBack aggregate(String objectSql, Map<String, Object> params) {
            return new ReadBack(objectSql, "", params, true);
        }
    }

    /** One insert statement and the values it writes, by column. */
    public record Insert(String sql, Map<String, Object> params, Map<String, Object> values) {}

    /** A condition over the target's alias, with the parameters it binds. */
    public record Condition(String sql, Map<String, Object> params) {}

    /** The written table; {@code generated} when its key is one AUTO_INCREMENT column the database fills. */
    public record Target(String table, String quotedTable, String alias, List<String> keys, boolean generated) {}

    private final SqlDialect dialect;
    private final String table;
    private final String quotedTable;
    private final String alias;
    private final List<String> keys;
    private final boolean generatedKey;
    private final ReadBack readBack;
    private final Condition check;

    /**
     * @param readBack null when the caller may not read the table: rows are written, none is represented
     * @param check what every written row must pass, or null
     */
    public KeyedWrites(SqlDialect dialect, Target target, ReadBack readBack, Condition check) {
        this.dialect = dialect;
        this.table = target.table();
        this.quotedTable = target.quotedTable();
        this.alias = target.alias();
        this.keys = target.keys();
        this.generatedKey = target.generated();
        this.readBack = readBack;
        this.check = check;
    }

    /** {@code write}, after the dialect's own preparation of a write (see {@link SqlDialect#beforeWrite}). */
    private KeyedWrite locked(KeyedWrite write) {
        return (jdbc, represent) -> {
            dialect.beforeWrite(jdbc.getJdbcOperations(), quotedTable);
            return write.run(jdbc, represent);
        };
    }

    /** Several writes run in order, their rows answered as one array; a write that answers none adds none. */
    public static KeyedWrite all(List<KeyedWrite> writes) {
        return (jdbc, represent) -> {
            List<String> rows = new ArrayList<>();
            for (KeyedWrite write : writes) {
                String row = write.run(jdbc, represent);
                if (row != null) rows.add(row);
            }
            return represent ? array(rows) : null;
        };
    }

    public KeyedWrite insert(List<Insert> inserts, boolean many) {
        return locked(insert(inserts, many, false));
    }

    /** @param upsert an upsert's row counts twice when it updates: it is checked by its key alone */
    private KeyedWrite insert(List<Insert> inserts, boolean many, boolean upsert) {
        return (jdbc, represent) -> {
            boolean verified = check != null || upsert;
            boolean keyed = represent || verified;
            if (keyed) requireKeys();
            List<List<Object>> written = new ArrayList<>();
            int count = 0;
            for (Insert insert : inserts) {
                count += insertRow(jdbc, insert, keyed ? written : null);
            }
            if (verified) verify(jdbc, written, upsert ? written.size() : count);
            return represent ? read(jdbc, written, many) : null;
        };
    }

    /** Runs one insert; its key is added to {@code written} when the rows are keyed (non-null). */
    private int insertRow(NamedParameterJdbcTemplate jdbc, Insert insert, List<List<Object>> written) {
        KeyHolder generated = new GeneratedKeyHolder();
        int count = jdbc.update(insert.sql(), new MapSqlParameterSource(insert.params()), generated);
        if (written != null) written.add(keyOf(insert.values(), generated));
        return count;
    }

    /**
     * An upsert of a row whose key is sent: an INSERT when no row holds the key, else an UPDATE of that row
     * alone, so a collision on any other unique key, however its columns were filled, fails as the database's
     * own duplicate error rather than updating a row the caller never addressed. A dialect that
     * {@linkplain SqlDialect#insertsBeforeLookup inserts first} (MySQL) tries the INSERT and updates only after a
     * duplicate key, so no gap lock is held for a missing key. Otherwise the key is locked first ({@code FOR UPDATE}),
     * which under REPEATABLE READ locks the gap a missing key falls in: two requests upserting the same new key can
     * deadlock, one is aborted with error 1213, and the transaction's owner runs it again, a bounded number of
     * times after a random pause ({@link WriteRetry}). Either way the key is never written twice.
     *
     * @param set       the UPDATE's assignments, null for DO NOTHING
     * @param updatable which existing rows the update may overwrite, its condition empty when every row: an
     *                  existing row outside it is kept as it is and returns none
     */
    public KeyedWrite upsert(Insert insert, Condition set, Condition updatable) {
        return locked(upsertOnKey(insert, set, updatable));
    }

    private KeyedWrite upsertOnKey(Insert insert, Condition set, Condition updatable) {
        KeyedWrite write = insert(List.of(insert), false, true);
        List<Object> key = keys.stream().map(insert.values()::get).toList();
        Function<NamedParameterJdbcTemplate, Optional<List<Object>>> existing = jdbc -> {
            Map<String, Object> params = new LinkedHashMap<>();
            return jdbc.queryForList(existsSql(key, params, null), params, Integer.class).isEmpty()
                    ? Optional.empty() : Optional.of(key);
        };
        return upsertWith(write, existing, dialect.insertsBeforeLookup(), set, updatable);
    }

    /**
     * Looks for the existing row first, or, when {@code insertFirst}, inserts first and looks only after a
     * duplicate key: the gap lock a missing key's lookup takes is then never held, so two upserts of one new key
     * cannot deadlock on it.
     */
    private KeyedWrite upsertWith(KeyedWrite write, Function<NamedParameterJdbcTemplate, Optional<List<Object>>> existing,
                                  boolean insertFirst, Condition set, Condition updatable) {
        return (jdbc, represent) -> {
            if (!insertFirst) {
                Optional<List<Object>> found = existing.apply(jdbc);
                return found.isPresent() ? updateExisting(jdbc, found.get(), set, updatable, represent)
                        : write.run(jdbc, represent);
            }
            try {
                return write.run(jdbc, represent);
            } catch (DataAccessException failure) {
                return afterRefusedInsert(failure, write, existing, new Update(set, updatable), jdbc, represent);
            }
        };
    }

    private record Update(Condition set, Condition updatable) {}

    /**
     * The database refused the INSERT. A row that exists is the one the caller addressed, so it is updated: the
     * refusal may be a partial row's own (a NOT NULL column with no default) that the existing row already
     * satisfies. With no row, a duplicate means it was deleted since, so the INSERT runs once more; anything else
     * is the database's own error. A lost lock is neither: it is rethrown for the transaction's owner to retry.
     */
    private String afterRefusedInsert(DataAccessException failure, KeyedWrite write,
                                      Function<NamedParameterJdbcTemplate, Optional<List<Object>>> existing,
                                      Update update, NamedParameterJdbcTemplate jdbc, boolean represent) {
        if (failure instanceof PessimisticLockingFailureException || WriteRetry.isLockConflict(failure)) {
            // The transaction lost a lock and may be rolled back whole: going on would commit only what follows.
            throw failure;
        }
        Optional<List<Object>> found = existing.apply(jdbc);
        if (found.isPresent()) {
            return updateExisting(jdbc, found.get(), update.set(), update.updatable(), represent);
        }
        if (dialect.isDuplicateKey(failure)) {
            return write.run(jdbc, represent);
        }
        throw failure;
    }

    /**
     * As {@link #upsert}, the existing row found by {@code conflict}, a unique key other than the primary key
     * whose every column is sent: PostgREST's {@code on_conflict}. More than one row holding those values
     * means the columns are not a unique key, and the write is refused.
     */
    public KeyedWrite upsertOn(List<String> conflict, Insert insert, Condition set, Condition updatable) {
        return locked(upsertOnUnique(conflict, insert, set, updatable));
    }

    private KeyedWrite upsertOnUnique(List<String> conflict, Insert insert, Condition set, Condition updatable) {
        KeyedWrite write = insert(List.of(insert), false, true);
        // Lookup first: a row addressed by another unique key may not send the primary key, and its INSERT would fail on that.
        return upsertWith(write, jdbc -> findByConflict(jdbc, conflict, insert), false, set, updatable);
    }

    private Optional<List<Object>> findByConflict(NamedParameterJdbcTemplate jdbc, List<String> conflict, Insert insert) {
        requireKeys();
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < conflict.size(); i++) {
            params.put(CONFLICT_PARAM + i, insert.values().get(conflict.get(i)));
            matches.add(column(conflict.get(i)) + ASSIGN + PARAM_PREFIX + CONFLICT_PARAM + i);
        }
        List<List<Object>> found = jdbc.query(lockedKeysSql(String.join(AND, matches)), params,
                (rs, rowNum) -> keyOf(rs));
        if (found.size() > 1) {
            throw new IllegalArgumentException("on_conflict " + String.join(",", conflict) + " is not a unique key of "
                    + table);
        }
        return found.stream().findFirst();
    }

    private String updateExisting(NamedParameterJdbcTemplate jdbc, List<Object> key, Condition set,
                                  Condition updatable, boolean represent) {
        if (set == null || !updatable(jdbc, key, updatable)) return null;
        Map<String, Object> updateParams = new LinkedHashMap<>(set.params());
        jdbc.update(updateSql(set.sql(), key, updateParams), updateParams);
        verify(jdbc, List.of(key), 1);
        return represent ? read(jdbc, List.of(key), false) : null;
    }

    private boolean updatable(NamedParameterJdbcTemplate jdbc, List<Object> key, Condition updatable) {
        if (updatable.sql().isEmpty()) return true;
        Map<String, Object> filtered = new LinkedHashMap<>(updatable.params());
        return !jdbc.queryForList(existsSql(key, filtered, updatable.sql()), filtered, Integer.class).isEmpty();
    }

    private String updateSql(String assignments, List<Object> key, Map<String, Object> params) {
        return UPDATE + quotedTable + dialect.updateAliasKeyword() + alias + SET + assignments + WHERE + keyPredicate(List.of(key), params);
    }

    /** {@code where} selects the rows {@code dml} updates; {@code set} is what it writes, by column. */
    public KeyedWrite update(String dml, String where, Map<String, Object> params, Map<String, Object> set) {
        return locked(updateRows(dml, where, params, set));
    }

    private KeyedWrite updateRows(String dml, String where, Map<String, Object> params, Map<String, Object> set) {
        return (jdbc, represent) -> {
            if (!represent && check == null) {
                jdbc.update(dml, params);
                return null;
            }
            requireKeys();
            List<List<Object>> matched = jdbc.query(lockedKeysSql(where), params, (rs, rowNum) -> keyOf(rs));
            jdbc.update(dml, params);
            List<List<Object>> changed = matched.stream().map(key -> moved(key, set)).toList();
            if (check != null) verify(jdbc, changed, changed.size());
            return represent ? read(jdbc, changed, true) : null;
        };
    }

    private List<Object> keyOf(ResultSet row) throws SQLException {
        List<Object> key = new ArrayList<>();
        for (int i = 1; i <= keys.size(); i++) key.add(row.getObject(i));
        return key;
    }

    /** {@code key} after an update that sets {@code set}: a key column it sets takes the new value. */
    private List<Object> moved(List<Object> key, Map<String, Object> set) {
        List<Object> moved = new ArrayList<>(key);
        for (int i = 0; i < keys.size(); i++) {
            if (set.containsKey(keys.get(i))) moved.set(i, set.get(keys.get(i)));
        }
        return moved;
    }

    /** {@code where} selects the rows {@code dml} deletes; they are read, locked, before it runs. */
    public KeyedWrite delete(String dml, String where, Map<String, Object> params) {
        return locked(deleteRows(dml, where, params));
    }

    private KeyedWrite deleteRows(String dml, String where, Map<String, Object> params) {
        return (jdbc, represent) -> {
            String rows = null;
            if (represent) {
                rows = "[]";
                if (readBack != null) {
                    Map<String, Object> readParams = new LinkedHashMap<>(params);
                    readParams.putAll(readBack.params());
                    rows = array(jdbc.queryForList(lockedRowsSql(where), readParams, String.class));
                }
            }
            jdbc.update(dml, params);
            return rows;
        };
    }

    /**
     * Every written row is found again by its key, and passes the check when there is one; anything else rolls
     * the write back.
     */
    private void verify(NamedParameterJdbcTemplate jdbc, List<List<Object>> written, int count) {
        if (written.size() != count) throw new IllegalArgumentException(unreadable());
        if (written.isEmpty()) return;
        Map<String, Object> params = check == null ? new LinkedHashMap<>() : new LinkedHashMap<>(check.params());
        long[] found = jdbc.queryForObject(verifySql(written, params), params,
                (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)});
        if (found[0] != count) throw new IllegalArgumentException(unreadable());
        if (found[1] > 0) throw new PermissionCheckFailedException();
    }

    /** How many of the written rows exist, and how many of those fail the check. */
    private String verifySql(List<List<Object>> written, Map<String, Object> params) {
        String failing = check == null ? "0" : "COALESCE(SUM((" + check.sql() + ") IS NOT TRUE), 0)";
        return SELECT + "COUNT(*), " + failing + FROM + quotedTable
                + SPACE + alias + WHERE + keyPredicate(written, params);
    }

    private String unreadable() {
        return "The rows written to " + table + " cannot be found again by their primary key";
    }

    private void requireKeys() {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(table + " has no primary key: " + dialect.databaseName()
                    + " cannot return the rows written to it");
        }
    }

    private List<Object> keyOf(Map<String, Object> values, KeyHolder generated) {
        List<Object> key = new ArrayList<>();
        for (String column : keys) {
            Object value = values.get(column);
            if (value == null && generatedKey && !generated.getKeyList().isEmpty()) {
                value = generated.getKeyList().getFirst().values().iterator().next();
            }
            if (value == null) {
                throw new IllegalArgumentException("Cannot return the row written to " + table + ": its key "
                        + column + " was neither sent nor generated by AUTO_INCREMENT");
            }
            key.add(value);
        }
        return key;
    }

    private String read(NamedParameterJdbcTemplate jdbc, List<List<Object>> written, boolean many) {
        if (readBack != null && readBack.aggregate()) return aggregate(jdbc, written);
        if (readBack == null || written.isEmpty()) return many ? "[]" : null;
        Map<String, Object> params = new LinkedHashMap<>(readBack.params());
        List<String> rows = jdbc.queryForList(readSql(written, params), params, String.class);
        if (many) return array(rows);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The row keyed {@code key}, locked, when it also meets {@code condition} (when set). */
    private String existsSql(List<Object> key, Map<String, Object> params, String condition) {
        return SELECT + "1" + FROM + quotedTable + SPACE + alias + WHERE + keyPredicate(List.of(key), params)
                + (condition == null ? "" : AND + parens(condition)) + dialect.lockRows();
    }

    private String lockedKeysSql(String where) {
        return SELECT + joinCols(keys.stream().map(this::column).toList()) + FROM + quotedTable + SPACE + alias
                + WHERE + parens(where) + dialect.lockRows();
    }

    private String lockedRowsSql(String where) {
        return SELECT + readBack.objectSql() + FROM + quotedTable + SPACE + alias + WHERE + parens(where) + readable()
                + dialect.lockRows();
    }

    private String aggregate(NamedParameterJdbcTemplate jdbc, List<List<Object>> written) {
        Map<String, Object> params = new LinkedHashMap<>(readBack.params());
        return jdbc.queryForObject(aggregateSql(written, params), params, String.class);
    }

    private String aggregateSql(List<List<Object>> written, Map<String, Object> params) {
        String rows = written.isEmpty() ? "1 = 0" : keyPredicate(written, params);
        return SELECT + readBack.objectSql() + FROM + quotedTable + SPACE + alias + WHERE + rows;
    }

    private String readSql(List<List<Object>> written, Map<String, Object> params) {
        return SELECT + readBack.objectSql() + FROM + quotedTable + SPACE + alias + WHERE
                + keyPredicate(written, params) + readable() + ORDER_BY + joinCols(keys.stream().map(this::column).toList());
    }

    /** {@code (k1, k2) IN ((:a, :b), ...)}, or {@code k IN (:a, ...)} for a single-column key. */
    private String keyPredicate(List<List<Object>> written, Map<String, Object> params) {
        List<String> tuples = new ArrayList<>();
        for (int row = 0; row < written.size(); row++) {
            List<String> refs = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String name = KEY_PARAM + row + UNDERSCORE + i;
                params.put(name, written.get(row).get(i));
                refs.add(PARAM_PREFIX + name);
            }
            tuples.add(keys.size() == 1 ? refs.getFirst() : parens(String.join(COMMA_SEP, refs)));
        }
        String columns = keys.size() == 1 ? column(keys.getFirst())
                : parens(String.join(COMMA_SEP, keys.stream().map(this::column).toList()));
        return columns + IN + (keys.size() == 1 ? parens(String.join(COMMA_SEP, tuples)) : dialect.rowValueList(tuples));
    }

    private String readable() {
        return readBack.readable().isEmpty() ? "" : AND + parens(readBack.readable());
    }

    private String column(String name) {
        return alias + DOT + dialect.quoteIdentifier(name);
    }

    private static String array(List<String> rows) {
        return "[" + String.join(", ", rows) + "]";
    }
}
