package io.github.excalibase.mongodb;

import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.MaskingPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.github.excalibase.rls.UserContext;

/** What one caller may see of every collection: the part of the schema that depends on column rules. */
record MaskView(Map<String, MaskingPlan> plans) {

    MaskView {
        plans = Map.copyOf(plans);
    }

    static MaskView of(Catalog catalog, ColumnSecurity columns, UserContext caller) {
        Map<String, MaskingPlan> plans = new LinkedHashMap<>();
        catalog.all().forEach(def -> plans.put(def.name(), columns.plan(def.name(), caller)));
        return new MaskView(plans);
    }

    /** Fields that may be selected: everything but what is hidden. */
    Set<String> selectable(CollectionDef def) {
        Set<String> fields = new TreeSet<>(def.fields().keySet());
        fields.removeAll(plan(def).hidden());
        fields.add(CollectionDef.ID_FIELD);
        return fields;
    }

    /** Fields a request may filter or order by. A masked field is excluded too: filtering by it would reveal it. */
    Set<String> filterable(CollectionDef def) {
        Set<String> fields = selectable(def);
        fields.removeAll(plan(def).masked().keySet());
        return fields;
    }

    Set<String> nulled(CollectionDef def) {
        Set<String> fields = new TreeSet<>();
        plan(def).masked().forEach((field, mode) -> {
            if (mode == MaskMode.NULL && !plan(def).hidden().contains(field)) {
                fields.add(field);
            }
        });
        return fields;
    }

    /** Identifies the schema this view needs; callers with the same rules share one. */
    String key() {
        StringBuilder key = new StringBuilder();
        new TreeSet<>(plans.keySet()).forEach(name -> key.append(name).append(':')
                .append(new TreeSet<>(plans.get(name).hidden())).append(new TreeSet<>(plans.get(name).masked().keySet()))
                .append(';'));
        return key.toString();
    }

    private MaskingPlan plan(CollectionDef def) {
        return plans.get(def.name());
    }
}
