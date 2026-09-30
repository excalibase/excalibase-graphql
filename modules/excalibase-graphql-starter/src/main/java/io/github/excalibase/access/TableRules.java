package io.github.excalibase.access;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.PresetValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One role's permissions on one table, resolved against the reflected schema: every column named here
 * exists, and an operation that is absent does not exist for the role. Without a select only an insert
 * can be held: update and delete are narrowed by the select filter, so they need one.
 */
record TableRules(String table, Optional<SelectRule> select, Optional<InsertRule> insert,
                  Optional<UpdateRule> update, Optional<DeleteRule> delete) {

    /** True when the role may insert into the table but not read it. */
    boolean insertOnly() {
        return select.isEmpty();
    }

    /** @param limit the role's own row cap, or null */
    record SelectRule(BoolExp filter, Set<String> columns, Integer limit, boolean aggregations) {
        SelectRule {
            columns = ordered(columns);
        }
    }

    /** @param columns what the client may set: the permission's columns without the presets */
    record InsertRule(BoolExp check, Set<String> columns, Map<String, PresetValue> presets) {
        InsertRule {
            columns = ordered(columns);
            presets = Collections.unmodifiableMap(new LinkedHashMap<>(presets));
        }
    }

    record UpdateRule(BoolExp filter, BoolExp check, Set<String> columns, Map<String, PresetValue> presets) {
        UpdateRule {
            columns = ordered(columns);
            presets = Collections.unmodifiableMap(new LinkedHashMap<>(presets));
        }
    }

    record DeleteRule(BoolExp filter) {
    }

    private static Set<String> ordered(Set<String> columns) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(columns));
    }
}
