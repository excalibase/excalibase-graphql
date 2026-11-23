package io.github.excalibase.config.datasource;

import io.github.excalibase.errors.ResultTooLarge;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A template that refuses an answer the database cut short, which it reports only as a warning on the statement. */
public class CutResultAwareJdbcTemplate extends JdbcTemplate {

    public CutResultAwareJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void handleWarnings(Statement statement) throws SQLException {
        ResultTooLarge.refuseIfCut(statement.getWarnings());
        super.handleWarnings(statement);
    }
}
