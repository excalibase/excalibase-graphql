package io.github.excalibase.postgres;

import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.compiler.SqlCompiler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class PostgresMutationExecutor implements MutationExecutor {
    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        return namedJdbc.queryForObject(compiled.sql(), params, String.class);
    }
}
