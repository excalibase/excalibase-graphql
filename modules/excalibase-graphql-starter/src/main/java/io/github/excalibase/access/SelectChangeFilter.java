package io.github.excalibase.access;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.compile.ParamNamer;
import io.github.excalibase.permissions.compile.RowPredicate;
import io.github.excalibase.permissions.compile.RowProbe;
import io.github.excalibase.permissions.compile.SessionBinding;
import io.github.excalibase.permissions.compile.SqlFragment;
import io.github.excalibase.schema.SchemaInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, for one subscription, which change images reach the subscriber and with which columns
 * (docs/features/permissions.md §5, realtime). Each image is judged by the select filter in memory;
 * what memory cannot decide is asked of the database for an image of a row that exists now (an
 * INSERT, an UPDATE's new image) and withheld otherwise. Only the select columns are delivered.
 */
final class SelectChangeFilter implements ChangeFilter {

    private static final Logger log = LoggerFactory.getLogger(SelectChangeFilter.class);

    private static final String UPDATE = "UPDATE";
    private static final String OLD = "old";
    private static final String NEW = "new";

    private final String table;
    private final BoolExp filter;
    private final Set<String> columns;
    private final RowPredicate predicate;
    private final SchemaInfo reflected;
    private final SessionBinding binding;

    SelectChangeFilter(String table, TableRules.SelectRule select, SchemaInfo reflected, SessionBinding binding) {
        this.table = table;
        this.filter = select.filter();
        this.columns = select.columns();
        this.reflected = reflected;
        this.binding = binding;
        this.predicate = RowPredicate.compile(filter, table, reflected, binding);
    }

    @Override
    public Optional<Map<String, Object>> render(String eventType, Map<String, Object> data, ProbeRunner probes) {
        if (!UPDATE.equalsIgnoreCase(eventType)) {
            boolean exists = "INSERT".equalsIgnoreCase(eventType);
            return visible(data, exists, probes);
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String side : List.of(OLD, NEW)) {
            if (data.get(side) instanceof Map<?, ?> image) {
                visible(asRow(image), NEW.equals(side), probes).ifPresent(row -> kept.put(side, row));
            }
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(kept);
    }

    private Optional<Map<String, Object>> visible(Map<String, Object> image, boolean rowExists, ProbeRunner probes) {
        boolean passes = switch (predicate.test(image)) {
            case MATCH -> true;
            case NO_MATCH -> false;
            case NEEDS_DATABASE -> rowExists && probe(image, probes);
        };
        return passes ? Optional.of(projected(image)) : Optional.empty();
    }

    private boolean probe(Map<String, Object> image, ProbeRunner probes) {
        Optional<SqlFragment> query = RowProbe.build(filter, table, image, reflected, binding, new ParamNamer("probe"));
        if (query.isEmpty()) {
            return false;
        }
        try {
            return probes.exists(query.get().sql(), query.get().params());
        } catch (RuntimeException e) {
            log.warn("realtime_probe_failed table={} reason={}", table, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> projected(Map<String, Object> image) {
        Map<String, Object> row = new LinkedHashMap<>();
        image.forEach((column, value) -> {
            if (columns.contains(column)) {
                row.put(column, value);
            }
        });
        return row;
    }

    private static Map<String, Object> asRow(Map<?, ?> image) {
        Map<String, Object> row = new LinkedHashMap<>();
        image.forEach((key, value) -> row.put(String.valueOf(key), value));
        return row;
    }
}
