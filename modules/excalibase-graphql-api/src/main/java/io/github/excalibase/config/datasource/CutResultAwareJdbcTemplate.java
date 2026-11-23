package io.github.excalibase.config.datasource;

import io.github.excalibase.errors.ResultTooLarge;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
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

    /** The column every compiled read answers in, and what an empty answer of it reads. */
    private static final String ANSWER = "body";
    private static final String NO_ROWS = "[]";

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

    /** No answer: nothing, or a list with no row, or only rows with no value (a REST read answers one row map). */
    private static boolean isMissing(Object answer) {
        if (answer instanceof Collection<?> rows) {
            return rows.stream().allMatch(CutResultAwareJdbcTemplate::isMissingRow);
        }
        return answer == null;
    }

    /**
     * A row of no value: nothing, or a row map (a REST read's) whose answer column holds no rows: NULL, or the empty
     * array a dialect coalesces a NULL aggregate into. With a full warning list the two cannot be told from a read
     * that matched nothing, so that is refused too.
     */
    private static boolean isMissingRow(Object row) {
        if (row == null) {
            return true;
        }
        if (row instanceof Map<?, ?> values) {
            return values.values().stream().allMatch(Objects::isNull)
                    || values.containsKey(ANSWER) && (values.get(ANSWER) == null || NO_ROWS.equals(values.get(ANSWER)));
        }
        return false;
    }
}
