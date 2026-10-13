package io.github.excalibase.schema;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Readable second names for relationships whose own name is their key column's (see
 * {@link SchemaInfo#getRelationshipAlias}). A name that would clash with a column, another relationship
 * or another alias of the same table is not given, so an alias never changes what an existing name means.
 *
 * @param byAlias     {@code table.alias} → {@code table.ownName}
 * @param byCanonical {@code table.ownName} → {@code table.alias}
 */
record RelationshipAliases(Map<String, String> byAlias, Map<String, String> byCanonical) {

    private static final String ID_SUFFIX = "_id";
    private static final String CAMEL_ID_SUFFIX = "Id";
    private static final String BY = "By";

    static RelationshipAliases of(SchemaInfo schema) {
        Map<String, String> proposed = new HashMap<>();
        Set<String> clashing = new HashSet<>();
        schema.getAllForwardFks().forEach((key, fk) -> {
            String stripped = fk.isComposite() ? null : withoutId(fk.fkColumn());
            if (stripped != null) {
                String table = owner(key);
                propose(proposed, clashing, table + "." + SchemaInfo.fkColumnFieldName(table, stripped), key);
            }
        });
        Map<String, Long> siblings = schema.getAllReverseFks().entrySet().stream()
                .collect(Collectors.groupingBy(entry -> owner(entry.getKey()) + "|" + entry.getValue().childTable(),
                        Collectors.counting()));
        schema.getAllReverseFks().forEach((key, relation) -> {
            String table = owner(key);
            if (!relation.isComposite() && siblings.get(table + "|" + relation.childTable()) > 1) {
                String column = relation.fkColumn();
                String byKey = NamingUtils.capitalize(NamingUtils.toLowerCamelCase(
                        withoutId(column) != null ? withoutId(column) : column));
                propose(proposed, clashing, table + "." + SchemaInfo.fkFieldName(relation.childTable()) + BY + byKey, key);
            }
        });
        Map<String, String> byAlias = new HashMap<>();
        proposed.forEach((alias, canonical) -> {
            if (!clashing.contains(alias) && isFree(schema, alias)) {
                byAlias.put(alias, canonical);
            }
        });
        Map<String, String> byCanonical = byAlias.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        return new RelationshipAliases(Map.copyOf(byAlias), Map.copyOf(byCanonical));
    }

    /** These aliases restricted to the relationships {@code schema} has, where the name is still free there. */
    RelationshipAliases within(SchemaInfo schema) {
        Map<String, String> kept = byAlias.entrySet().stream()
                .filter(entry -> schema.getAllForwardFks().containsKey(entry.getValue())
                        || schema.getAllReverseFks().containsKey(entry.getValue()))
                .filter(entry -> isFree(schema, entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, String> canonical = kept.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        return new RelationshipAliases(Map.copyOf(kept), Map.copyOf(canonical));
    }

    private static void propose(Map<String, String> proposed, Set<String> clashing, String alias, String canonical) {
        if (alias.equals(canonical) || proposed.putIfAbsent(alias, canonical) != null) {
            clashing.add(alias);
        }
    }

    /** True when no column or relationship of the alias's table already answers to its name. */
    private static boolean isFree(SchemaInfo schema, String aliasKey) {
        String table = owner(aliasKey);
        String name = aliasKey.substring(table.length() + 1);
        return !schema.getAllForwardFks().containsKey(aliasKey)
                && !schema.getAllReverseFks().containsKey(aliasKey)
                && schema.getColumnType(table, name) == null;
    }

    /** {@code home_team_id} → {@code home_team}, {@code homeTeamId} → {@code homeTeam}; null when there is no id. */
    static String withoutId(String column) {
        if (column.length() > ID_SUFFIX.length() && column.toLowerCase(Locale.ROOT).endsWith(ID_SUFFIX)) {
            return column.substring(0, column.length() - ID_SUFFIX.length());
        }
        if (column.length() > CAMEL_ID_SUFFIX.length() && column.endsWith(CAMEL_ID_SUFFIX)
                && Character.isLowerCase(column.charAt(column.length() - CAMEL_ID_SUFFIX.length() - 1))) {
            return column.substring(0, column.length() - CAMEL_ID_SUFFIX.length());
        }
        return null;
    }

    /** A relationship key is {@code <table>.<field>}; the table is everything before the last dot. */
    private static String owner(String key) {
        return key.substring(0, key.lastIndexOf('.'));
    }
}
