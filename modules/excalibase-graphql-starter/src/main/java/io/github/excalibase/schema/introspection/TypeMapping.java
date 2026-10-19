package io.github.excalibase.schema.introspection;

import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import io.github.excalibase.ArrayColumns;
import graphql.schema.GraphQLOutputType;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;

/**
 * Pure, stateless mapping from database column types to GraphQL scalars,
 * plus the category predicates used when picking filter input types.
 */
final class TypeMapping {

    private TypeMapping() {}

    static GraphQLInputType mapInputType(String dbType) {
        if (dbType == null) return GraphQLString;
        String type = dbType.toLowerCase();
        if (type.equals("bigint") || type.equals("int8")) return ExtendedScalars.GraphQLBigInteger;
        if (type.contains("int")) return GraphQLInt;
        if (type.contains("float") || type.contains("double") || type.contains("numeric")
                || type.contains("decimal") || type.contains("real")) return GraphQLFloat;
        if (type.contains("bool")) return GraphQLBoolean;
        // A JSON column answers JSON values, so it takes them as input too.
        if (isJsonType(type)) return ExtendedScalars.Json;
        return GraphQLString;
    }

    /** A column's input type: a list of its element scalar for an array column, else its scalar. */
    static GraphQLInputType mapColumnInputType(String dbType) {
        return ArrayColumns.isArray(dbType) ? GraphQLList.list(arrayElementType(dbType)) : mapInputType(dbType);
    }

    static GraphQLOutputType mapColumnType(String dbType) {
        // Scalars and lists of them implement both GraphQLInputType and GraphQLOutputType.
        return (GraphQLOutputType) mapColumnInputType(dbType);
    }

    /** The scalar an array column's elements take; json elements come back as JSON values. */
    static GraphQLInputType arrayElementType(String arrayType) {
        String element = ArrayColumns.elementType(arrayType);
        return isJsonType(element) ? ExtendedScalars.Json : mapInputType(element);
    }

    static boolean isIntegerType(String dbType) {
        if (dbType == null) return false;
        String type = dbType.toLowerCase();
        return type.equals("smallint") || type.equals("int2")
                || type.equals("int") || type.equals("integer") || type.equals("int4")
                || type.equals("bigint") || type.equals("int8")
                || type.equals("serial") || type.equals("serial4")
                || type.equals("bigserial") || type.equals("serial8")
                || type.equals("smallserial") || type.equals("serial2");
    }

    static boolean isFloatType(String dbType) {
        if (dbType == null) return false;
        String type = dbType.toLowerCase();
        return type.equals("numeric") || type.equals("decimal")
                || type.equals("real") || type.equals("float4")
                || type.equals("double precision") || type.equals("float8")
                || type.equals("money");
    }

    static boolean isBooleanType(String dbType) {
        if (dbType == null) return false;
        String type = dbType.toLowerCase();
        return type.equals("bool") || type.equals("boolean");
    }

    static boolean isDateTimeType(String dbType) {
        if (dbType == null) return false;
        String type = dbType.toLowerCase();
        return type.equals("date")
                || type.equals("time") || type.equals("timetz")
                || type.equals("time with time zone") || type.equals("time without time zone")
                || type.equals("timestamp") || type.equals("timestamptz")
                || type.equals("timestamp with time zone") || type.equals("timestamp without time zone")
                || type.equals("interval");
    }

    static boolean isJsonType(String dbType) {
        if (dbType == null) return false;
        String type = dbType.toLowerCase();
        return type.equals("json") || type.equals("jsonb");
    }
}
