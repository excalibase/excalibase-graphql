package io.github.excalibase.compiler;

import io.github.excalibase.spi.MutationExecutor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs each root field's {@link KeyedTableWrite}, in order, on the caller's connection, whose transaction the caller
 * owns: the write and the read-back see the same rows, and a failure anywhere rolls back every statement.
 */
public abstract class KeyedMutationExecutor implements MutationExecutor {

    private final String databaseName;

    protected KeyedMutationExecutor(String databaseName) {
        this.databaseName = databaseName;
    }

    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        if (!(compiled.write() instanceof TwoPhaseWrites(List<TwoPhaseWrites.Entry> entries))) {
            throw new IllegalStateException("Not a " + databaseName + " write: " + compiled.write());
        }
        List<String> answers = new ArrayList<>();
        for (TwoPhaseWrites.Entry field : entries) {
            if (!(field.write() instanceof KeyedTableWrite mutation)) {
                throw new IllegalStateException("Not a " + databaseName + " write: " + field.write());
            }
            String rows = mutation.write().run(namedJdbc, true);
            answers.add("\"" + field.responseKey() + "\":" + (rows == null ? "null" : rows));
        }
        return "{" + String.join(",", answers) + "}";
    }
}
