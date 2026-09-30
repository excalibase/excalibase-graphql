package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;

/**
 * A relationship field resolved to the join it stands for. Names are the ones the GraphQL schema
 * gives foreign keys, read from the same {@link SchemaInfo} maps and in the same order (forward,
 * then reverse) as the query builder, so a permission names exactly what a query can select.
 *
 * @param targetColumns columns of {@code targetTable}, pairwise equal to {@code outerColumns}
 */
record RelationshipJoin(String targetTable, List<String> targetColumns, List<String> outerColumns) {

    static RelationshipJoin resolve(SchemaInfo schema, String tableKey, String name) {
        SchemaInfo.FkInfo forward = schema.getForwardFk(tableKey, name);
        if (forward != null) {
            return new RelationshipJoin(forward.refTable(), List.copyOf(forward.refColumns()), List.copyOf(forward.fkColumns()));
        }
        SchemaInfo.ReverseFkInfo reverse = schema.getReverseFk(tableKey, name);
        if (reverse != null) {
            return new RelationshipJoin(reverse.childTable(), List.copyOf(reverse.fkColumns()), List.copyOf(reverse.refColumns()));
        }
        throw new PermissionEvaluationException(PermissionEvaluationException.UNKNOWN_RELATIONSHIP,
                "Unknown relationship " + name + " on " + tableKey);
    }
}
