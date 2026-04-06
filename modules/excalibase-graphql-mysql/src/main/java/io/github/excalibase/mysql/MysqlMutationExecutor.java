package io.github.excalibase.mysql;

import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.compiler.SqlCompiler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class MysqlMutationExecutor implements MutationExecutor {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public MysqlMutationExecutor(JdbcTemplate jdbcTemplate, TransactionTemplate txTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
    }

    @Override
    public String execute(SqlCompiler.CompiledQuery compiled, MapSqlParameterSource params,
                          NamedParameterJdbcTemplate namedJdbc) {
        return txTemplate.execute(status -> {
            if (compiled.isDeleteBeforeSelect()) {
                String result = namedJdbc.queryForObject(compiled.sql(), params, String.class);
                namedJdbc.update(compiled.dmlSql(), params);
                return result;
            }
            namedJdbc.update(compiled.dmlSql(), params);
            if (compiled.lastInsertIdParam() != null) {
                Long lastId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                params.addValue(compiled.lastInsertIdParam(), lastId);
            }
            return namedJdbc.queryForObject(compiled.sql(), params, String.class);
        });
    }
}
