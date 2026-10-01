package io.github.excalibase.compiler;

import java.util.List;

/**
 * A mutation run as statements, in order, in one transaction. A nested insert needs this: Hasura inserts
 * the rows an object relationship points at, then the row, then the rows of its array relationships,
 * each as its own statement, so a row's check sees every row inserted before it in the mutation and none
 * inserted after. When an operation holds a nested insert, every field of it runs this way, in order.
 */
public record MutationSequence(List<Step> steps) {

    public MutationSequence {
        steps = List.copyOf(steps);
    }

    /** One root field of the operation, answered under {@code responseKey}. */
    public sealed interface Step permits Statement, NestedInsert {
        String responseKey();
    }

    /** A field answered by one statement whose single column is the field's JSON value. */
    public record Statement(String responseKey, String sql) implements Step {
    }

    /**
     * A {@code create}/{@code createMany} field whose rows nest others. Each root inserts one or more rows
     * of the field's table; {@code outputSql} then answers the field from every root row, bound as a JSON
     * array to {@code rowsParam}, and from the number of rows the whole tree wrote, bound to
     * {@code countParam}.
     */
    public record NestedInsert(String responseKey, List<InsertNode> roots, String outputSql, String rowsParam,
                               String countParam) implements Step {
        public NestedInsert {
            roots = List.copyOf(roots);
        }
    }

    /**
     * One INSERT statement. Its SQL answers one row of two columns: how many rows it wrote, and those rows
     * as a JSON array; it raises {@code permission_check_failed} when a written row fails its check. The
     * row it hangs off, if any, is bound to {@code parentRowParam} as such an array; {@code before} run
     * first and {@code after} run once it wrote its one row.
     */
    public record InsertNode(String table, String sql, String parentRowParam, List<Before> before,
                             List<InsertNode> after) {
        public InsertNode {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    /** The row an object relationship points at: inserted first, its written row bound to {@code rowParam}. */
    public record Before(String relationship, String rowParam, InsertNode node) {
    }
}
