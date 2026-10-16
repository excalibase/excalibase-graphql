package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.BoolExp.Exists;
import io.github.excalibase.permissions.BoolExp.Not;
import io.github.excalibase.permissions.BoolExp.Or;
import io.github.excalibase.permissions.BoolExp.Relationship;
import io.github.excalibase.permissions.BoolExp.True;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Asks the database what memory cannot decide about one row image: either whether the live row with
 * the image's primary key passes the filter, or whether the image's own values do, which still holds
 * once the row is gone.
 */
public final class RowProbe {

    private RowProbe() {
    }

    /**
     * @return {@code SELECT EXISTS (...)} for the row whose key {@code row} carries, or empty when the
     *         table has no primary key or the image lacks a usable value for one of its columns
     */
    public static Optional<SqlFragment> build(PermissionDialect dialect, BoolExp filter, String tableKey,
                                              Map<String, Object> row, SchemaInfo schema, SessionBinding binding,
                                              ParamNamer namer) {
        if (!schema.hasPrimaryKey(tableKey)) {
            return Optional.empty();
        }
        TableRef table = TableRef.resolve(dialect, schema, tableKey);
        String alias = namer.nextAlias();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : schema.getPrimaryKeys(tableKey)) {
            ColumnType type = ColumnType.of(dialect, schema, tableKey, key);
            Optional<Object> value = row.get(key) == null ? Optional.empty() : canonical(type, row.get(key));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            String param = namer.nextParam();
            params.put(param, type.sqlValue(value.get()));
            conditions.add(type.compared(alias + "." + dialect.quote(key), false) + " = " + type.placeholder(param));
        }
        SqlFragment matches = FilterCompiler.compile(dialect, filter, tableKey, alias, schema, binding, namer);
        params.putAll(matches.params());
        return Optional.of(new SqlFragment("SELECT EXISTS (SELECT 1 FROM " + table.sql() + " " + alias + " WHERE "
                + String.join(" AND ", conditions) + " AND (" + matches.sql() + "))", params));
    }

    /**
     * The columns of {@code tableKey} the filter reads: compared columns and the join columns of its
     * relationships. An image must carry each of them to be judged by its own values.
     */
    public static Set<String> columnsRead(BoolExp filter, String tableKey, SchemaInfo schema) {
        Set<String> columns = new LinkedHashSet<>();
        read(filter, tableKey, schema).forEach(columns::add);
        return Collections.unmodifiableSet(columns);
    }

    /**
     * @return {@code SELECT EXISTS (...)} over the image as one typed row, or empty when the image lacks a
     *         column the filter reads, holds a value that is not of the column's type, or has a column the
     *         dialect cannot type as the table does
     */
    public static Optional<SqlFragment> ofImage(PermissionDialect dialect, BoolExp filter, String tableKey,
                                                Map<String, Object> image, SchemaInfo schema, SessionBinding binding,
                                                ParamNamer namer) {
        Set<String> columns = columnsRead(filter, tableKey, schema);
        String alias = namer.nextAlias();
        List<String> placeholders = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (String column : columns) {
            if (!image.containsKey(column)) {
                return Optional.empty();
            }
            ColumnType type = ColumnType.of(dialect, schema, tableKey, column);
            Object raw = image.get(column);
            Optional<Object> value = raw == null ? Optional.empty() : canonical(type, raw);
            String param = namer.nextParam();
            Optional<String> placeholder = type.typedPlaceholder(param);
            if ((raw != null && value.isEmpty()) || placeholder.isEmpty()) {
                return Optional.empty();
            }
            params.put(param, value.map(type::sqlValue).orElse(null));
            placeholders.add(placeholder.get());
            names.add(dialect.quote(column));
        }
        SqlFragment matches = FilterCompiler.compileOverImage(dialect, filter, tableKey, alias, schema, binding, namer);
        params.putAll(matches.params());
        String from = columns.isEmpty() ? "" : dialect.sql().imageRow(alias, placeholders, names);
        return Optional.of(new SqlFragment("SELECT EXISTS (SELECT 1" + from + " WHERE " + matches.sql() + ")", params));
    }

    private static Stream<String> read(BoolExp filter, String tableKey, SchemaInfo schema) {
        return switch (filter) {
            case True ignored -> Stream.empty();
            case And and -> and.operands().stream().flatMap(operand -> read(operand, tableKey, schema));
            case Or or -> or.operands().stream().flatMap(operand -> read(operand, tableKey, schema));
            case Not not -> read(not.operand(), tableKey, schema);
            case ColumnCompare compare -> Stream.of(compare.column());
            case Relationship relationship ->
                    RelationshipJoin.resolve(schema, tableKey, relationship.name()).outerColumns().stream();
            case Exists ignored -> Stream.empty();
        };
    }

    private static Optional<Object> canonical(ColumnType type, Object raw) {
        try {
            return Optional.of(type.canonical(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
