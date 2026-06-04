package io.github.excalibase.rls;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The result of {@link ColumnMasker#plan(String, UserContext, Operation)}:
 * which columns should be hidden, which should have their value masked
 * (and how), and the per-column {@link PartialMaskSpec} when the masking
 * mode is {@link MaskMode#PARTIAL}.
 *
 * <p>v1.6 implements {@link MaskMode#HIDE} (drop column entirely) and
 * {@link MaskMode#NULL} (replace value with null). {@link MaskMode#PARTIAL},
 * {@link MaskMode#HASH} and {@link MaskMode#CUSTOM} are reserved and will
 * throw {@link UnsupportedOperationException} when {@link #apply(Map)}
 * encounters them, until v1.7 ships dialect-aware rendering.
 */
public final class MaskingPlan {

    private final Set<String> hidden;
    private final Map<String, MaskMode> masked;
    private final Map<String, PartialMaskSpec> partialSpecs;

    public MaskingPlan(Set<String> hidden,
                       Map<String, MaskMode> masked,
                       Map<String, PartialMaskSpec> partialSpecs) {
        this.hidden = (hidden == null) ? Set.of() : Set.copyOf(hidden);
        this.masked = (masked == null) ? Map.of() : Map.copyOf(masked);
        this.partialSpecs = (partialSpecs == null) ? Map.of() : Map.copyOf(partialSpecs);
    }

    /** Columns that must be excluded from result projections / response shapes. */
    public Set<String> hidden() { return hidden; }

    /** Columns whose value is transformed at read time, keyed by the transformation mode. */
    public Map<String, MaskMode> masked() { return masked; }

    /** Per-column partial-mask spec when the mode is {@link MaskMode#PARTIAL}. */
    public Map<String, PartialMaskSpec> partialSpecs() { return partialSpecs; }

    /**
     * Mutates {@code row} in place: removes hidden columns, applies value
     * masking. Used by the realtime fanout path and by non-SQL consumers
     * that fetch rows and apply masking client-side.
     */
    public void apply(Map<String, Object> row) {
        for (String col : hidden) {
            row.remove(col);
        }
        for (Map.Entry<String, MaskMode> e : masked.entrySet()) {
            String col = e.getKey();
            MaskMode mode = e.getValue();
            switch (mode) {
                case NULL -> row.put(col, null);
                case HIDE -> row.remove(col);   // defensive — shouldn't appear here
                case PARTIAL -> throw new UnsupportedOperationException(
                    "MaskMode PARTIAL is not implemented in v1.6 (RFC 0007 v1.7 ships dialect-aware rendering)");
                case HASH -> throw new UnsupportedOperationException(
                    "MaskMode HASH is not implemented in v1.6 (RFC 0007 v1.7 ships dialect-aware rendering)");
                case CUSTOM -> throw new UnsupportedOperationException(
                    "MaskMode CUSTOM is not implemented in v1.6 (RFC 0007 v1.7 ships registry-driven application)");
            }
        }
    }

    /** Returns true iff the plan has no effect — the row passes through untouched. */
    public boolean isEmpty() {
        return hidden.isEmpty() && masked.isEmpty();
    }

    static MaskingPlan empty() {
        return new MaskingPlan(Set.of(), Map.of(), Map.of());
    }

    /** Builder used by {@link ColumnMasker}. */
    static final class Builder {
        private final Set<String> hidden = new HashSet<>();
        private final Map<String, MaskMode> masked = new HashMap<>();
        private final Map<String, PartialMaskSpec> partialSpecs = new HashMap<>();

        /**
         * Add (or replace) a per-column entry, applying the most-restrictive
         * precedence: HIDE > NULL > HASH > PARTIAL > none.
         */
        void put(String column, MaskMode mode, PartialMaskSpec spec, String customKey) {
            if (mode == MaskMode.HIDE) {
                hidden.add(column);
                masked.remove(column);
                partialSpecs.remove(column);
                return;
            }
            // If HIDE already locked in, nothing weaker overrides it.
            if (hidden.contains(column)) return;
            MaskMode prior = masked.get(column);
            if (prior == null || precedence(mode) > precedence(prior)) {
                masked.put(column, mode);
                if (spec != null) partialSpecs.put(column, spec);
                else partialSpecs.remove(column);
            }
        }

        MaskingPlan build() {
            return new MaskingPlan(
                Collections.unmodifiableSet(hidden),
                Collections.unmodifiableMap(masked),
                Collections.unmodifiableMap(partialSpecs));
        }

        private static int precedence(MaskMode m) {
            // HIDE handled separately. Higher number wins among the value-replacing modes.
            return switch (m) {
                case HIDE -> 100;
                case NULL -> 80;
                case HASH -> 60;
                case PARTIAL -> 40;
                case CUSTOM -> 20;
            };
        }
    }
}
