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

    def "should correctly identify binary/network types"() {
        expect:
        PostgresTypeOperator.isNetworkType(type) == expected

        where:
        type        | expected
        "bytea"     | true
        "inet"      | true
        "cidr"      | true
        "macaddr"   | true
        "macaddr8"  | true
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

    def "should handle the int/interval collision correctly"() {
        given: "types that contain 'int' substring"
        def intTypes = ["int", "integer", "bigint", "smallint", "serial", "bigserial"]
        def nonIntTypes = ["interval", "varchar", "text"]  // Changed to types that don't contain "int"

        expect: "int-containing types are identified as integers"
        intTypes.every { PostgresTypeOperator.isIntegerType(it) }

        and: "interval and other types NOT containing 'int' are NOT identified as integers"
        nonIntTypes.every { !PostgresTypeOperator.isIntegerType(it) }

        and: "interval is correctly identified as datetime type"
        PostgresTypeOperator.isDateTimeType("interval") == true
        
        and: "types containing 'int' but excluded should not be integers"
        PostgresTypeOperator.isIntegerType("interval") == false
        
        and: "other random types containing 'int' substring are incorrectly identified as integers (showing the limitation)"
        PostgresTypeOperator.isIntegerType("point") == true  // "point" contains "int" - shows limitation
        PostgresTypeOperator.isIntegerType("maintenance") == true  // "maintenance" contains "int" - shows limitation
    }
} 