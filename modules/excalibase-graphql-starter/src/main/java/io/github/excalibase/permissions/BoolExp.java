package io.github.excalibase.permissions;

import java.util.List;
import java.util.Objects;

/**
 * A permission's boolean expression (docs/features/permissions.md §4), parsed but not yet bound to a
 * schema: column and relationship names are resolved against the reflected tables when the access
 * plan is built.
 */
public sealed interface BoolExp {

    BoolExp TRUE = new True();

    /** {@code {}}: every row. */
    record True() implements BoolExp {
    }

    /** {@code _and}; an empty list is true. */
    record And(List<BoolExp> operands) implements BoolExp {
        public And {
            operands = List.copyOf(operands);
        }
    }

    /** {@code _or}; an empty list is false. */
    record Or(List<BoolExp> operands) implements BoolExp {
        public Or {
            operands = List.copyOf(operands);
        }
    }

    record Not(BoolExp operand) implements BoolExp {
        public Not {
            Objects.requireNonNull(operand, "operand");
        }
    }

    record ColumnCompare(String column, ComparisonOperator operator, Operand operand) implements BoolExp {
        public ColumnCompare {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /** A row exists in the table the relationship {@code name} leads to, matching {@code where}. */
    record Relationship(String name, BoolExp where) implements BoolExp {
        public Relationship {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(where, "where");
        }
    }

    /** {@code _exists}: a row of an unrelated, schema-qualified table matches {@code where}. */
    record Exists(String table, BoolExp where) implements BoolExp {
        public Exists {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(where, "where");
        }
    }
}
