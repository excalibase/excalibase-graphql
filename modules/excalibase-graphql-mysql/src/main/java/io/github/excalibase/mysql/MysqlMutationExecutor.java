package io.github.excalibase.mysql;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.spi.MutationExecutor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link MysqlWrite} on the caller's connection, whose transaction the caller owns: the write and
 * the read-back see the same rows, and a failure anywhere rolls back every statement.
 */
public class MysqlMutationExecutor implements MutationExecutor {

    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        if (!(compiled.write() instanceof MysqlWrite write)) {
            throw new IllegalStateException("Not a MySQL write: " + compiled.write());
        }
        return switch (write) {
            case MysqlWrite.Insert insert -> insert(insert, params, namedJdbc);
            case MysqlWrite.Update update -> update(update, params, namedJdbc);
            case MysqlWrite.Delete delete -> delete(delete, params, namedJdbc);
        };
    }

    private static String insert(MysqlWrite.Insert insert, MapSqlParameterSource params,
                                 NamedParameterJdbcTemplate namedJdbc) {
        List<List<Object>> written = new ArrayList<>();
        for (int row = 0; row < insert.statements().size(); row++) {
            KeyHolder generated = new GeneratedKeyHolder();
            namedJdbc.update(insert.statements().get(row), params, generated);
            written.add(keyOf(insert, insert.sentKeys().get(row), generated));
        }
        return read(insert.readBack(), written, params, namedJdbc);
    }

    /** The row's key as sent, with the one key column it left out filled from the AUTO_INCREMENT value. */
    private static List<Object> keyOf(MysqlWrite.Insert insert, List<Object> sent, KeyHolder generated) {
        List<Object> key = new ArrayList<>(sent);
        List<Integer> missing = new ArrayList<>();
        for (int column = 0; column < key.size(); column++) {
            if (key.get(column) == null) {
                missing.add(column);
            }
        }
        if (missing.isEmpty()) {
            return key;
        }
        List<Map<String, Object>> keys = generated.getKeyList();
        if (missing.size() == 1 && keys.size() == 1 && keys.getFirst().size() == 1) {
            key.set(missing.getFirst(), keys.getFirst().values().iterator().next());
            return key;
        }
        List<String> unsent = missing.stream().map(insert.keyColumns()::get).toList();
        throw new IllegalArgumentException("A row inserted into " + insert.table() + " has no value for its key "
                + unsent + " and the database generated none: send the key");
    }

    private static String update(MysqlWrite.Update update, MapSqlParameterSource params,
                                 NamedParameterJdbcTemplate namedJdbc) {
        List<String> keyRefs = update.readBack().keyRefs();
        List<List<Object>> locked = namedJdbc.query(update.lockSql(), params, (rs, rowNum) -> {
            List<Object> key = new ArrayList<>();
            for (int column = 1; column <= keyRefs.size(); column++) {
                key.add(rs.getObject(column));
            }
            return key;
        });
        if (locked.isEmpty()) {
            return read(update.readBack(), locked, params, namedJdbc);
        }
        namedJdbc.update(update.updateSql(locked, params), params);
        List<List<Object>> written = new ArrayList<>();
        for (List<Object> key : locked) {
            List<Object> after = new ArrayList<>(key);
            update.keyAssignments().forEach(after::set);
            written.add(after);
        }
        return read(update.readBack(), written, params, namedJdbc);
    }

    private static String delete(MysqlWrite.Delete delete, MapSqlParameterSource params,
                                 NamedParameterJdbcTemplate namedJdbc) {
        String result = namedJdbc.queryForObject(delete.selectSql(), params, String.class);
        namedJdbc.update(delete.deleteSql(), params);
        return result;
    }

    private static String read(MysqlWrite.KeyedRead readBack, List<List<Object>> keys, MapSqlParameterSource params,
                               NamedParameterJdbcTemplate namedJdbc) {
        return namedJdbc.queryForObject(readBack.sql(keys, params), params, String.class);
    }
}
