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

    public void validateQuery(Set<String> filterFields, boolean allowScan) {
        if (allowScan) return;
        for (String field : filterFields) {
            if ("id".equals(field)) continue;
            if (!indexedFields.contains(field)) {
                throw new IllegalArgumentException(
                        "Field '" + field + "' is not indexed on collection '" + name + "'. " +
                        "Add an index or pass allowScan: true");
            }
        }
    }
}
