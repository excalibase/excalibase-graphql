package io.github.excalibase.mongodb;

import io.github.excalibase.rls.ColumnMasker;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.MaskingPlan;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.ResourceMatcher;
import io.github.excalibase.rls.UserContext;

import java.util.List;
import java.util.Set;

/**
 * Column-level security from the shared {@link ColumnPolicy} model: HIDE removes a field from the schema the
 * caller is served, NULL returns it as null. Masks that cannot be rendered exactly are refused at startup.
 */
final class ColumnSecurity {

    private static final Set<MaskMode> RENDERABLE = Set.of(MaskMode.HIDE, MaskMode.NULL);

    private final ColumnMasker masker;

    ColumnSecurity(Catalog catalog, List<ColumnPolicy> policies) {
        policies.forEach(policy -> validate(catalog, policy));
        this.masker = new ColumnMasker(policies);
    }

    MaskingPlan plan(String collection, UserContext caller) {
        return masker.plan(collection, caller, Operation.SELECT);
    }

    private static void validate(Catalog catalog, ColumnPolicy policy) {
        for (CollectionDef def : catalog.all()) {
            if (!ResourceMatcher.matches(policy.resource(), def.name())) {
                continue;
            }
            if (!RENDERABLE.contains(policy.mode())) {
                throw new IllegalStateException("Column policy \"" + policy.name() + "\" uses " + policy.mode()
                        + ", which MongoDB cannot render yet: use HIDE or NULL");
            }
            for (String column : policy.columns()) {
                if (CollectionDef.ID_FIELD.equals(column) || CollectionDef.MONGO_ID.equals(column)) {
                    throw new IllegalStateException("Column policy \"" + policy.name()
                            + "\" masks the id of " + def.name() + ", which every request needs");
                }
                if (!def.fields().containsKey(column)) {
                    throw new IllegalStateException("Column policy \"" + policy.name() + "\" masks \"" + column
                            + "\", which " + def.name() + " does not declare");
                }
            }
        }
    }
}
