package io.github.excalibase.postgres;

import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.security.WriteGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.compiler.SqlKeywords.FROM;
import static io.github.excalibase.compiler.SqlKeywords.SELECT;
import static io.github.excalibase.compiler.SqlKeywords.WHERE;

/** The permission checks the rows a statement writes must pass, as SQL over the CTE that wrote them. */
final class WriteChecks {

    private WriteChecks() {
    }

    /** The rows one CTE writes, and the permission checks (INSERT, UPDATE or both) they must pass. */
    record WrittenRows(String cteAlias, String table, List<RlsOp> checks) {
    }

    /** One condition per check, true when some written row fails it; empty without a write guard. */
    static List<String> violations(List<WrittenRows> written, Map<String, Object> params, MutationBuilder shared) {
        WriteGuard guard = RlsContext.writeGuard();
        List<String> violations = new ArrayList<>();
        if (guard == null) return violations;
        for (WrittenRows rows : written) {
            for (RlsOp operation : rows.checks()) {
                String rowAlias = shared.dialect().randAlias();
                RlsWhereContributor.Contribution check = guard.check(rows.table(), rowAlias, operation);
                if (check != null) {
                    params.putAll(check.params());
                    violations.add("EXISTS (" + SELECT + "1" + FROM + rows.cteAlias() + " " + rowAlias
                            + WHERE + "(" + check.sql() + ") IS NOT TRUE)");
                }
            }
        }
        return violations;
    }
}
