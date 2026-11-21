package io.github.excalibase.sqlite;

import io.github.excalibase.compiler.KeyedMutationCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static io.github.excalibase.compiler.SqlKeywords.DEFAULT;
import static io.github.excalibase.compiler.SqlKeywords.INSERT_INTO;
import static io.github.excalibase.compiler.SqlKeywords.VALUES;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.parens;

/**
 * SQLite can {@code RETURNING}, but a write there is judged against the permission's check after it runs, so rows
 * are written and read back by primary key, as MySQL's are.
 */
public class SqliteMutationCompiler extends KeyedMutationCompiler {

    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"(?:[^\"]++|\"\")*+\"");

    /**
     * One row's INSERT with the columns it sets. SQLite has no {@code DEFAULT} in a VALUES list, so a column the row
     * leaves out is left out of the statement and takes its default as it would in a single insert.
     */
    @Override
    protected String insertStatement(String table, String columns, String rowValues) {
        List<String> names = QUOTED_IDENTIFIER.matcher(columns).results().map(MatchResult::group).toList();
        List<String> values = List.of(rowValues.substring(1, rowValues.length() - 1).split(", "));
        List<String> setColumns = new ArrayList<>();
        List<String> setValues = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (!DEFAULT.equals(values.get(i))) {
                setColumns.add(names.get(i));
                setValues.add(values.get(i));
            }
        }
        if (setColumns.isEmpty()) {
            return INSERT_INTO + table + " DEFAULT VALUES";
        }
        return INSERT_INTO + table + parens(joinCols(setColumns)) + VALUES + parens(joinCols(setValues));
    }
}
