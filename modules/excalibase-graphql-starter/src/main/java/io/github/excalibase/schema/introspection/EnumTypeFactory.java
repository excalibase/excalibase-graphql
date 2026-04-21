package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.schema.GraphQLEnumType.newEnum;

/**
 * Builds GraphQL enum types from {@link SchemaInfo#getEnumTypes()}.
 * Returns a map keyed on the raw enum key (possibly schema-qualified
 * "schema.enum") so downstream factories can look up the enum by the
 * same key {@code SchemaInfo#getEnumType(table, col)} returns.
 */
public final class EnumTypeFactory {

    public Map<String, GraphQLEnumType> build(SchemaInfo schemaInfo) {
        Map<String, GraphQLEnumType> enumTypeMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : schemaInfo.getEnumTypes().entrySet()) {
            enumTypeMap.put(entry.getKey(), buildEnum(entry.getKey(), entry.getValue()));
        }
        return enumTypeMap;
    }

    private GraphQLEnumType buildEnum(String key, List<String> values) {
        String enumName = NamingHelpers.typeName(key);
        GraphQLEnumType.Builder enumBuilder = newEnum().name(enumName);
        if (key.contains(".")) {
            String enumSchema = key.substring(0, key.indexOf('.'));
            String rawEnum = key.substring(key.indexOf('.') + 1);
            enumBuilder.description("Enum " + rawEnum + " from schema " + enumSchema);
        }
        for (String label : values) {
            enumBuilder.value(label);
        }
        return enumBuilder.build();
    }
}
