package io.github.excalibase.mysql;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.spi.MutationExecutor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Runs a {@link MysqlWrite} on the caller's connection, whose transaction the caller owns: the write and
 * the read-back see the same rows, and a failure anywhere rolls back every statement.
 */
public class MysqlMutationExecutor implements MutationExecutor {

    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        if (!(compiled.write() instanceof MysqlWrite mutation)) {
            throw new IllegalStateException("Not a MySQL write: " + compiled.write());
        }
        String rows = mutation.write().run(namedJdbc, true);
        return "{\"" + mutation.fieldName() + "\":" + (rows == null ? "null" : rows) + "}";
    }
}
