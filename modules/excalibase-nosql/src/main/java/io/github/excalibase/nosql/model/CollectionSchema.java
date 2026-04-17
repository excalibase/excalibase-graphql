package io.github.excalibase.nosql.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record CollectionSchema(
        String name,
        Map<String, FieldType> fields,
        List<IndexDef> indexes,
        Set<String> indexedFields,
        String searchField,
        VectorDef vector
) {

    public CollectionSchema {
        fields = Map.copyOf(fields);
        indexes = List.copyOf(indexes);
        indexedFields = Set.copyOf(indexedFields);
    }

    /**
     * Check which filter fields lack an index. Returns warnings (never throws).
     * Queries always execute — unindexed fields use sequential scan.
     */
    public List<String> checkIndexes(Set<String> filterFields) {
        var warnings = new java.util.ArrayList<String>();
        for (String field : filterFields) {
            if ("id".equals(field)) continue;
            if (!indexedFields.contains(field)) {
                warnings.add("Field '" + field + "' is not indexed on collection '" + name +
                        "'. This query will use a sequential scan.");
            }
        }
        return warnings;
    }
}
