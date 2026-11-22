package io.github.excalibase.access;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.compile.PermissionDialect;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules of a document that the reflected schema does not fit, found by the same resolver that builds a role's
 * plan, so what is reported is exactly what the engine would drop. Nothing is changed or logged.
 */
public final class RuleProblems {

    private RuleProblems() {
    }

    public static List<RuleProblem> find(PermissionDialect dialect, SchemaInfo reflected, PermissionSet document) {
        List<RuleProblem> found = new ArrayList<>();
        document.tables().forEach(entry -> RulesResolver.inspect(dialect, reflected, entry, found::add));
        return List.copyOf(found);
    }
}
