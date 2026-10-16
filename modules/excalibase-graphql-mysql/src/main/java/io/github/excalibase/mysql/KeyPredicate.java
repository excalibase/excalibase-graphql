package io.github.excalibase.mysql;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.ArrayList;
import java.util.List;

import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.param;
import static io.github.excalibase.compiler.SqlKeywords.parens;

/** A predicate matching exactly the given primary keys, each value bound as a parameter. */
final class KeyPredicate {

    private KeyPredicate() {
    }

    /** {@code FALSE} for no keys; {@code ref IN (...)} for one column; {@code (refs) IN ((...), ...)} otherwise. */
    static String of(List<String> keyRefs, List<List<Object>> keys, MapSqlParameterSource params, String prefix) {
        if (keys.isEmpty()) {
            return "FALSE";
        }
        List<String> tuples = new ArrayList<>();
        for (int row = 0; row < keys.size(); row++) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < keyRefs.size(); column++) {
                String name = prefix + "_" + row + "_" + column;
                params.addValue(name, keys.get(row).get(column));
                values.add(param(name));
            }
            tuples.add(keyRefs.size() == 1 ? values.getFirst() : parens(joinCols(values)));
        }
        String target = keyRefs.size() == 1 ? keyRefs.getFirst() : parens(joinCols(keyRefs));
        return target + " IN " + parens(joinCols(tuples));
    }
}
