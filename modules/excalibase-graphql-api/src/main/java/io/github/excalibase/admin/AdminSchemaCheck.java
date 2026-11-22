package io.github.excalibase.admin;

import io.github.excalibase.access.RuleProblem;
import io.github.excalibase.access.RuleProblems;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.GraphqlSchemaManager;

import java.util.List;
import java.util.function.Function;

/**
 * Judges a document against the schema this replica has loaded, with the engine's own resolver. Until a schema is
 * loaded there is nothing to judge against and the answer is no warnings: a save never waits for it.
 */
final class AdminSchemaCheck {

    private final GraphqlSchemaManager schemas;

    AdminSchemaCheck(GraphqlSchemaManager schemas) {
        this.schemas = schemas;
    }

    /** The check for the schema as it is now, or null when none is loaded. */
    Function<PermissionSet, List<RuleProblem>> current() {
        GraphqlSchemaManager.EngineState state = schemas.getEngineState();
        if (state == null) {
            return null;
        }
        return document -> RuleProblems.find(state.compiler().dialect().permissionDialect(),
                state.compiler().schemaInfo(), document);
    }
}
