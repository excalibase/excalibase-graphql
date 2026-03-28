package io.github.excalibase;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Executes compiled mutations. Postgres uses single SQL (CTE + RETURNING),
 * MySQL uses two-phase DML + SELECT in a transaction.
 */
public interface MutationExecutor {

    String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                   NamedParameterJdbcTemplate namedJdbc);
}
