package io.github.excalibase.mysql;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.compiler.TwoPhaseWrites;
import io.github.excalibase.spi.MutationExecutor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs each root field's {@link MysqlWrite}, in order, on the caller's connection, whose transaction the caller owns: the write and
 * the read-back see the same rows, and a failure anywhere rolls back every statement.
 */
public class MysqlMutationExecutor implements MutationExecutor {

    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        if (!(compiled.write() instanceof TwoPhaseWrites fields)) {
            throw new IllegalStateException("Not a MySQL write: " + compiled.write());
        }
        List<String> answers = new ArrayList<>();
        for (TwoPhaseWrites.Entry field : fields.entries()) {
            if (!(field.write() instanceof MysqlWrite mutation)) {
                throw new IllegalStateException("Not a MySQL write: " + field.write());
            }
            String rows = mutation.write().run(namedJdbc, true);
            answers.add("\"" + field.responseKey() + "\":" + (rows == null ? "null" : rows));
        }
        return "{" + String.join(",", answers) + "}";
    }
}
