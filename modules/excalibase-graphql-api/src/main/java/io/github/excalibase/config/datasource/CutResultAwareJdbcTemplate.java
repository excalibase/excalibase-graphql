package io.github.excalibase.config.datasource;

import io.github.excalibase.errors.ResultTooLarge;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * A template that refuses an answer the database cut short, which it reports only as a warning on the statement.
 * It notes whether a read's answer came back missing, because a full warning list is only suspect then.
 */
public class CutResultAwareJdbcTemplate extends JdbcTemplate {

    private final ThreadLocal<Boolean> answerMissing = new ThreadLocal<>();

    public CutResultAwareJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public <T> T query(PreparedStatementCreator creator, PreparedStatementSetter setter, ResultSetExtractor<T> extractor)
            throws DataAccessException {
        answerMissing.remove();
        return super.query(creator, setter, results -> {
            T answer = extractor.extractData(results);
            answerMissing.set(isMissing(answer));
            return answer;
        });
    }

    @Override
    protected void handleWarnings(Statement statement) throws SQLException {
        boolean missing = Boolean.TRUE.equals(answerMissing.get());
        answerMissing.remove();
        ResultTooLarge.refuseIfCut(statement.getWarnings(), missing);
        super.handleWarnings(statement);
    }

    /** No answer: nothing, or a list with no row, or only rows with no value. */
    private static boolean isMissing(Object answer) {
        if (answer instanceof Collection<?> rows) {
            return rows.stream().allMatch(row -> row == null);
        }
        return answer == null;
    }
}
