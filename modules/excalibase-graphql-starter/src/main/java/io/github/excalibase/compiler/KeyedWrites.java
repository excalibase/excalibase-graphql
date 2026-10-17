package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.*;

/**
 * Writes on a database without RETURNING (MySQL), for REST and GraphQL alike. The written rows are read back by primary key: the key
 * sent or generated for an insert, the keys an update matched (locked before it runs, then followed if it
 * changes them), and a delete's rows are read, locked, before they go.
 */
public final class KeyedWrites {

    private static final String KEY_PARAM = "rk_";
    private static final String FOR_UPDATE = " FOR UPDATE";
    private static final String UNIQUE_PARAM = "uq_";
    private static final String PRIMARY = "PRIMARY";
    private static final String DUPLICATE_STATE = "23000";
    private static final int DUPLICATE_ENTRY = 1062;

    /** How a written row reads: its JSON object over {@code alias}, and the select filter it must pass. */
    public record ReadBack(String objectSql, String readable, Map<String, Object> params) {}

    /** One insert statement and the values it writes, by column. */
    public record Insert(String sql, Map<String, Object> params, Map<String, Object> values) {}

    private final SqlDialect dialect;
    private final String table;
    private final String quotedTable;
    private final String alias;
    private final List<String> keys;
    private final ReadBack readBack;

    /** @param readBack null when the caller may not read the table: rows are written, none is represented */
    public KeyedWrites(SqlDialect dialect, String table, String quotedTable, String alias, List<String> keys,
                ReadBack readBack) {
        this.dialect = dialect;
        this.table = table;
        this.quotedTable = quotedTable;
        this.alias = alias;
        this.keys = keys;
        this.readBack = readBack;
    }

    public KeyedWrite insert(List<Insert> inserts, boolean many) {
        return (jdbc, represent) -> {
            if (represent) requireKeys();
            List<List<Object>> written = new ArrayList<>();
            for (Insert insert : inserts) {
                KeyHolder generated = new GeneratedKeyHolder();
                jdbc.update(insert.sql(), new MapSqlParameterSource(insert.params()), generated);
                if (represent) written.add(keyOf(insert.values(), generated));
            }
            return represent ? read(jdbc, written, many) : null;
        };
    }

    /**
     * An upsert of a row whose key is sent; {@code doNothing} keeps an existing row as it is and returns none.
     * ON DUPLICATE KEY also fires on any other unique key, updating a row the caller never addressed; Postgres
     * conflicts on the primary key only, so a collision on another unique key fails as a duplicate first.
     */
    public KeyedWrite upsert(Insert insert, boolean doNothing) {
        KeyedWrite write = insert(List.of(insert), false);
        if (!doNothing) {
            return (jdbc, represent) -> {
                requireNoOtherUniqueCollision(jdbc, insert.values());
                return write.run(jdbc, represent);
            };
        }
        return (jdbc, represent) -> {
            List<Object> key = keys.stream().map(insert.values()::get).toList();
            Map<String, Object> params = new LinkedHashMap<>();
            if (!jdbc.queryForList(existsSql(key, params), params, Integer.class).isEmpty()) return null;
            return write.run(jdbc, represent);
        };
    }

    /** {@code where} selects the rows {@code dml} updates; {@code set} is what it writes, by column. */
    public KeyedWrite update(String dml, String where, Map<String, Object> params, Map<String, Object> set) {
        return (jdbc, represent) -> {
            if (!represent) {
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
            return read(jdbc, changed, true);
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

    /** Throws the database's own duplicate-key error when another row holds one of {@code values}' unique keys. */
    private void requireNoOtherUniqueCollision(NamedParameterJdbcTemplate jdbc, Map<String, Object> values) {
        List<Object> key = keys.stream().map(values::get).toList();
        for (Map.Entry<String, List<String>> unique : otherUniqueKeys(jdbc).entrySet()) {
            if (!unique.getValue().stream().allMatch(column -> values.get(column) != null)) continue;
            Map<String, Object> params = new LinkedHashMap<>();
            if (!jdbc.queryForList(collisionSql(unique.getValue(), values, key, params), params, Integer.class).isEmpty()) {
                String message = "Duplicate entry for key '" + unique.getKey() + "'";
                throw new DuplicateKeyException(message,
                        new SQLIntegrityConstraintViolationException(message, DUPLICATE_STATE, DUPLICATE_ENTRY));
            }
        }
    }

    /** Unique indexes other than the primary key, by name, with their columns in index order. */
    private Map<String, List<String>> otherUniqueKeys(NamedParameterJdbcTemplate jdbc) {
        Map<String, List<String>> unique = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query(showIndexSql(), (RowCallbackHandler) rs -> {
            String index = rs.getString("Key_name");
            if (rs.getInt("Non_unique") != 0 || PRIMARY.equals(index)) return;
            String column = rs.getString("Column_name");
            if (column == null) {
                throw new IllegalArgumentException("Upsert into " + table + " cannot check its unique expression index "
                        + index + " on " + dialect.databaseName());
            }
            unique.computeIfAbsent(index, name -> new ArrayList<>()).add(column);
        });
        return unique;
    }

    private String showIndexSql() {
        return "SHOW INDEX FROM " + quotedTable;
    }

    /** A row other than {@code key} holding {@code values} on {@code columns}, locked. */
    private String collisionSql(List<String> columns, Map<String, Object> values, List<Object> key,
                                Map<String, Object> params) {
        List<String> equal = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = UNIQUE_PARAM + i;
            params.put(name, values.get(columns.get(i)));
            equal.add(column(columns.get(i)) + ASSIGN + PARAM_PREFIX + name);
        }
        return SELECT + "1" + FROM + quotedTable + SPACE + alias + WHERE + String.join(AND, equal)
                + AND + NOT + parens(keyPredicate(List.of(key), params)) + LIMIT + "1" + FOR_UPDATE;
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
            if (value == null && keys.size() == 1 && !generated.getKeyList().isEmpty()) {
                value = generated.getKeyList().getFirst().values().iterator().next();
            }
            if (value == null) {
                throw new IllegalArgumentException("Cannot return the row written to " + table + ": its key "
                        + column + " was neither sent nor generated");
            }
            key.add(value);
        }
        return key;
    }

    private String read(NamedParameterJdbcTemplate jdbc, List<List<Object>> written, boolean many) {
        if (readBack == null || written.isEmpty()) return many ? "[]" : null;
        Map<String, Object> params = new LinkedHashMap<>(readBack.params());
        List<String> rows = jdbc.queryForList(readSql(written, params), params, String.class);
        if (many) return array(rows);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String existsSql(List<Object> key, Map<String, Object> params) {
        return SELECT + "1" + FROM + quotedTable + SPACE + alias + WHERE + keyPredicate(List.of(key), params) + FOR_UPDATE;
    }

    private String lockedKeysSql(String where) {
        return SELECT + joinCols(keys.stream().map(this::column).toList()) + FROM + quotedTable + SPACE + alias
                + WHERE + parens(where) + FOR_UPDATE;
    }

    private String lockedRowsSql(String where) {
        return SELECT + readBack.objectSql() + FROM + quotedTable + SPACE + alias + WHERE + parens(where) + readable()
                + FOR_UPDATE;
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
