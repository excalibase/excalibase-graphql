package io.github.excalibase.postgres.constant

import spock.lang.Specification

class PostgresTypeOperatorTest extends Specification {

    def "should correctly identify integer types"() {
        expect:
        PostgresTypeOperator.isIntegerType(type) == expected

        where:
        type          | expected
        "int"         | true
        "integer"     | true
        "bigint"      | true
        "smallint"    | true
        "serial"      | true
        "bigserial"   | true
        "int2"        | true
        "int4"        | true
        "int8"        | true
        "serial2"     | true
        "serial4"     | true
        "serial8"     | true
        "smallserial" | true
        "interval"    | false  // This is the key test case - interval should NOT be integer
        "text"        | false
        "varchar"     | false
        null          | false
        ""            | false
    }

    def "should correctly identify floating point types"() {
        expect:
        PostgresTypeOperator.isFloatingPointType(type) == expected

        where:
        type                | expected
        "numeric"           | true
        "decimal"           | true
        "real"              | true
        "double precision"  | true
        "float"             | true
        "double"            | true
        "numeric(10,2)"     | true
        "float4"            | true
        "float8"            | true
        "int"               | false
        "text"              | false
        null                | false
        ""                  | false
    }

    def "should correctly identify boolean types"() {
        expect:
        PostgresTypeOperator.isBooleanType(type) == expected

        where:
        type        | expected
        "boolean"   | true
        "bool"      | true
        "int"       | false
        "text"      | false
        null        | false
        ""          | false
    }

    def "should correctly identify JSON types"() {
        expect:
        PostgresTypeOperator.isJsonType(type) == expected

        where:
        type      | expected
        "json"    | true
        "jsonb"   | true
        "text"    | false
        "int"     | false
        null      | false
        ""        | false
    }

    def "should correctly identify date/time types"() {
        expect:
        PostgresTypeOperator.isDateTimeType(type) == expected

        where:
        type                      | expected
        "timestamp"               | true
        "timestamptz"             | true
        "timestamp with time zone"| true
        "date"                    | true
        "time"                    | true
        "timetz"                  | true
        "time with time zone"     | true
        "interval"                | true  // interval IS a datetime type
        "int"                     | false
        "text"                    | false
        null                      | false
        ""                        | false
    }

    def "should correctly identify UUID types"() {
        expect:
        PostgresTypeOperator.isUuidType(type) == expected

        where:
        type      | expected
        "uuid"    | true
        "text"    | false
        "int"     | false
        null      | false
        ""        | false
    }

    def "should correctly identify network types"() {
        expect:
        PostgresTypeOperator.isNetworkType(type) == expected

        where:
        type        | expected
        "inet"      | true
        "cidr"      | true
        "macaddr"   | true
        "macaddr8"  | true
        "text"      | false
        "int"       | false
        "bytea"     | false  // bytea is binary, not network
        null        | false
        ""          | false
    }

    def "should correctly identify binary types"() {
        expect:
        PostgresTypeOperator.isBinaryType(type) == expected

        where:
        type        | expected
        "bytea"     | true
        "inet"      | false  // inet is network, not binary
        "cidr"      | false  // cidr is network, not binary
        "text"      | false
        "int"       | false
        null        | false
        ""          | false
    }

    def "should correctly identify bit types"() {
        expect:
        PostgresTypeOperator.isBitType(type) == expected

        where:
        type      | expected
        "bit"     | true
        "varbit"  | true
        "text"    | false
        "int"     | false
        null      | false
        ""        | false
    }

    def "should correctly identify XML types"() {
        expect:
        PostgresTypeOperator.isXmlType(type) == expected

        where:
        type      | expected
        "xml"     | true
        "text"    | false
        "int"     | false
        null      | false
        ""        | false
    }

    def "should correctly identify array types"() {
        expect:
        PostgresTypeOperator.isArrayType(type) == expected

        where:
        type          | expected
        "int[]"       | true
        "text[]"      | true
        "varchar[]"   | true
        "int"         | false
        "text"        | false
        null          | false
        ""            | false
    }

    def "should extract base type from array types"() {
        expect:
        PostgresTypeOperator.getBaseArrayType(type) == expected

        where:
        type                 | expected
        "integer[]"          | "integer"
        "varchar[]"          | "varchar"
        "order_status[]"     | "order_status"
        "address[]"          | "address"
        "integer"            | "integer"
        "varchar"            | "varchar"
        "order_status"       | "order_status"
        null                 | null
    }

    def "should handle case insensitivity correctly"() {
        expect:
        PostgresTypeOperator.isIntegerType("INT") == true
        PostgresTypeOperator.isIntegerType("Integer") == true
        PostgresTypeOperator.isIntegerType("BIGINT") == true
        PostgresTypeOperator.isIntegerType("INTERVAL") == false  // Still false even in uppercase
        
        PostgresTypeOperator.isFloatingPointType("NUMERIC") == true
        PostgresTypeOperator.isFloatingPointType("DOUBLE PRECISION") == true
        
        PostgresTypeOperator.isBooleanType("BOOLEAN") == true
        PostgresTypeOperator.isBooleanType("BOOL") == true
        
        PostgresTypeOperator.isJsonType("JSON") == true
        PostgresTypeOperator.isJsonType("JSONB") == true
    }

    def "should handle exact type matching correctly (no false positives)"() {
        given: "types that contain 'int' substring but are NOT integer types"
        def actualIntTypes = ["int", "integer", "bigint", "smallint", "serial", "bigserial", "int2", "int4", "int8"]
        def typesContainingInt = ["interval", "point", "maintenance", "print", "tint", "hint"]

        expect: "actual integer types are correctly identified"
        actualIntTypes.every { PostgresTypeOperator.isIntegerType(it) }

        and: "types containing 'int' substring but NOT integer types are correctly rejected"
        typesContainingInt.every { !PostgresTypeOperator.isIntegerType(it) }

        and: "interval is correctly identified as datetime type, not integer"
        PostgresTypeOperator.isDateTimeType("interval") == true
        PostgresTypeOperator.isIntegerType("interval") == false
        
        and: "point type is correctly NOT identified as integer (fixes old bug)"
        PostgresTypeOperator.isIntegerType("point") == false  // FIXED: no longer false positive
        PostgresTypeOperator.isIntegerType("maintenance") == false  // FIXED: no longer false positive
    }

    def "should handle type specifications with precision/scale"() {
        expect: "types with precision/scale are correctly normalized"
        PostgresTypeOperator.isFloatingPointType("numeric(10,2)") == true
        PostgresTypeOperator.isFloatingPointType("decimal(5,2)") == true
        PostgresTypeOperator.isTextType("varchar(255)") == true
        PostgresTypeOperator.isTextType("char(10)") == true
        PostgresTypeOperator.isBitType("bit(8)") == true
        PostgresTypeOperator.isBitType("varbit(64)") == true
    }

    def "should correctly identify text types"() {
        expect:
        PostgresTypeOperator.isTextType(type) == expected

        where:
        type                  | expected
        "text"                | true
        "varchar"             | true
        "varchar(255)"        | true
        "character varying"   | true
        "char"                | true
        "char(10)"            | true
        "character"           | true
        "bpchar"              | true
        "int"                 | false
        "json"                | false
        null                  | false
        ""                    | false
    }

} 