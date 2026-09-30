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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides, for one subscription, which change images reach the subscriber and with which columns
 * (docs/features/permissions.md §5, realtime). Each image is judged by the select filter in memory.
 * What memory cannot decide is asked of the database: for a row that exists (an INSERT, an UPDATE's
 * new image) by its key, and for a row that is gone (a DELETE, an UPDATE's old image) by the image's
 * own values, which is why such an image must carry every column the filter reads and the subscriber
 * is sent. Only the select columns are delivered.
 */
final class SelectChangeFilter implements ChangeFilter {

    private static final Logger log = LoggerFactory.getLogger(SelectChangeFilter.class);

    private static final String UPDATE = "UPDATE";
    private static final String INSERT = "INSERT";
    private static final String OLD = "old";
    private static final String NEW = "new";
    private static final String PROBE_PREFIX = "probe";

    private final String table;
    private final BoolExp filter;
    private final Set<String> columns;
    private final Set<String> formerImageColumns;
    private final RowPredicate predicate;
    private final SchemaInfo reflected;
    private final SessionBinding binding;
    private final IncompleteImageWarning warning;

    SelectChangeFilter(String table, TableRules.SelectRule select, SchemaInfo reflected, SessionBinding binding,
                       IncompleteImageWarning warning) {
        this.table = table;
        this.filter = select.filter();
        this.columns = select.columns();
        this.reflected = reflected;
        this.binding = binding;
        this.warning = warning;
        this.predicate = RowPredicate.compile(filter, table, reflected, binding);
        Set<String> needed = new LinkedHashSet<>(RowProbe.columnsRead(filter, table, reflected));
        needed.addAll(columns);
        this.formerImageColumns = Set.copyOf(needed);
    }

    @Override
    public Optional<Map<String, Object>> render(String eventType, Map<String, Object> data, ProbeRunner probes) {
        if (!UPDATE.equalsIgnoreCase(eventType)) {
            return INSERT.equalsIgnoreCase(eventType) ? current(data, probes) : former(data, probes);
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        if (data.get(OLD) instanceof Map<?, ?> image) {
            former(asRow(image), probes).ifPresent(row -> kept.put(OLD, row));
        }
        if (data.get(NEW) instanceof Map<?, ?> image) {
            current(asRow(image), probes).ifPresent(row -> kept.put(NEW, row));
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(kept);
    }

    /** An image of a row as it now exists: the database may be asked about the row by its key. */
    private Optional<Map<String, Object>> current(Map<String, Object> image, ProbeRunner probes) {
        return judged(image, () -> RowProbe.build(filter, table, image, reflected, binding,
                new ParamNamer(PROBE_PREFIX)), probes);
    }

    /** An image of a row that is gone: judged by its own values, so it must carry them all. */
    private Optional<Map<String, Object>> former(Map<String, Object> image, ProbeRunner probes) {
        List<String> missing = formerImageColumns.stream().filter(column -> !image.containsKey(column)).sorted().toList();
        if (!missing.isEmpty()) {
            warning.withheld(table, missing);
            return Optional.empty();
        }
        return judged(image, () -> RowProbe.ofImage(filter, table, image, reflected, binding,
                new ParamNamer(PROBE_PREFIX)), probes);
    }

    private Optional<Map<String, Object>> judged(Map<String, Object> image, Supplier<Optional<SqlFragment>> probeQuery,
                                                 ProbeRunner probes) {
        boolean passes = switch (predicate.test(image)) {
            case MATCH -> true;
            case NO_MATCH -> false;
            case NEEDS_DATABASE -> probeQuery.get().map(query -> probe(query, probes)).orElse(false);
        };
        return passes ? Optional.of(projected(image)) : Optional.empty();
    }

    private boolean probe(SqlFragment query, ProbeRunner probes) {
        try {
            return probes.exists(query.sql(), query.params());
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
