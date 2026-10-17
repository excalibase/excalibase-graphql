package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.security.PermissionCheckFailedException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Writes on a database without RETURNING (MySQL), for REST and GraphQL alike. The written rows are read back by primary key: the key
 * sent or generated for an insert, the keys an update matched (locked before it runs, then followed if it
 * changes them), and a delete's rows are read, locked, before they go. A permission check is evaluated on
 * the rows found again by those keys, and every written row must be found.
 */
public final class KeyedWrites {

    private static final String KEY_PARAM = "rk_";
    private static final String FOR_UPDATE = " FOR UPDATE";

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

    public KeyedWrite insert(List<Insert> inserts, boolean many) {
        return insert(inserts, many, false);
    }

    /** @param upsert an upsert's row counts twice when it updates: it is checked by its key alone */
    private KeyedWrite insert(List<Insert> inserts, boolean many, boolean upsert) {
        return (jdbc, represent) -> {
            boolean keyed = represent || check != null || upsert;
            if (keyed) requireKeys();
            List<List<Object>> written = new ArrayList<>();
            int count = 0;
            for (Insert insert : inserts) {
                KeyHolder generated = new GeneratedKeyHolder();
                count += jdbc.update(insert.sql(), new MapSqlParameterSource(insert.params()), generated);
                if (keyed) written.add(keyOf(insert.values(), generated));
            }
            if (check != null || upsert) verify(jdbc, written, upsert ? written.size() : count);
            return represent ? read(jdbc, written, many) : null;
        };
    }

    /**
     * An upsert of a row whose key is sent: an INSERT when no row holds the key, else an UPDATE of that row
     * alone, so a collision on any other unique key, however its columns were filled, fails as the database's
     * own duplicate error rather than updating a row the caller never addressed. The key is locked first
     * ({@code FOR UPDATE}); under READ COMMITTED, which takes no gap lock, a concurrent insert of the same key
     * makes the INSERT fail as a duplicate, never write twice.
     *
     * @param set       the UPDATE's assignments, null for DO NOTHING
     * @param updatable which existing rows the update may overwrite, its condition empty when every row: an
     *                  existing row outside it is kept as it is and returns none
     */
    public KeyedWrite upsert(Insert insert, Condition set, Condition updatable) {
        KeyedWrite write = insert(List.of(insert), false, true);
        List<Object> key = keys.stream().map(insert.values()::get).toList();
        return (jdbc, represent) -> {
            Map<String, Object> params = new LinkedHashMap<>();
            if (jdbc.queryForList(existsSql(key, params, null), params, Integer.class).isEmpty()) {
                return write.run(jdbc, represent);
            }
            if (set == null || !updatable(jdbc, key, updatable)) return null;
            Map<String, Object> updateParams = new LinkedHashMap<>(set.params());
            jdbc.update(updateSql(set.sql(), key, updateParams), updateParams);
            verify(jdbc, List.of(key), 1);
            return represent ? read(jdbc, List.of(key), false) : null;
        };
    }

    private boolean updatable(NamedParameterJdbcTemplate jdbc, List<Object> key, Condition updatable) {
        if (updatable.sql().isEmpty()) return true;
        Map<String, Object> filtered = new LinkedHashMap<>(updatable.params());
        return !jdbc.queryForList(existsSql(key, filtered, updatable.sql()), filtered, Integer.class).isEmpty();
    }

    private String updateSql(String assignments, List<Object> key, Map<String, Object> params) {
        return UPDATE + quotedTable + SPACE + alias + SET + assignments + WHERE + keyPredicate(List.of(key), params);
    }

    /** {@code where} selects the rows {@code dml} updates; {@code set} is what it writes, by column. */
    public KeyedWrite update(String dml, String where, Map<String, Object> params, Map<String, Object> set) {
        return (jdbc, represent) -> {
            if (!represent && check == null) {
                jdbc.update(dml, params);
                return null;
            }
            requireKeys();
            List<List<Object>> matched = jdbc.query(lockedKeysSql(where), params, (rs, rowNum) -> {
                List<Object> key = new ArrayList<>();
                for (int i = 1; i <= keys.size(); i++) key.add(rs.getObject(i));
                return key;
            });
            jdbc.update(dml, params);
            List<List<Object>> changed = matched.stream().map(key -> {
                List<Object> moved = new ArrayList<>(key);
                for (int i = 0; i < keys.size(); i++) {
                    if (set.containsKey(keys.get(i))) moved.set(i, set.get(keys.get(i)));
                }
                return moved;
            }).toList();
            if (check != null) verify(jdbc, changed, changed.size());
            return represent ? read(jdbc, changed, true) : null;
        };
    }

    /** {@code where} selects the rows {@code dml} deletes; they are read, locked, before it runs. */
    public KeyedWrite delete(String dml, String where, Map<String, Object> params) {
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
                + (condition == null ? "" : AND + parens(condition)) + FOR_UPDATE;
    }

    private String lockedKeysSql(String where) {
        return SELECT + joinCols(keys.stream().map(this::column).toList()) + FROM + quotedTable + SPACE + alias
                + WHERE + parens(where) + FOR_UPDATE;
    }

    private String lockedRowsSql(String where) {
        return SELECT + readBack.objectSql() + FROM + quotedTable + SPACE + alias + WHERE + parens(where) + readable()
                + FOR_UPDATE;
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
        return columns + IN + parens(String.join(COMMA_SEP, tuples));
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
