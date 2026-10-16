package io.github.excalibase.spi;

import io.github.excalibase.compiler.SqlCompiler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Executes compiled mutations. Postgres uses single SQL (CTE + RETURNING), MySQL writes and then reads the
 * written rows back by key. Every statement runs on {@code namedJdbc}, which the caller binds to the one
 * connection whose transaction it owns.
 */
public interface MutationExecutor {

    String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                   NamedParameterJdbcTemplate namedJdbc);
}
