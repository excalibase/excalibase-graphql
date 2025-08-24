package io.github.excalibase.postgres.util

import spock.lang.Specification

class PostgresArrayParameterHandlerTest extends Specification {

    def typeConverter = Mock(PostgresTypeConverter)
    def paramHandler = new PostgresArrayParameterHandler(typeConverter)

    def "should map BIT VARYING correctly and not confuse with CHARACTER VARYING"() {
        expect: "BIT VARYING types should map to 'varbit'"
        paramHandler.mapToPGArrayTypeName(baseType) == expectedMapping
        
        where:
        baseType              | expectedMapping
        "bit varying"         | "varbit"
        "bit varying(20)"     | "varbit" 
        "varbit"              | "varbit"
        "varbit(16)"          | "varbit"
        "BIT VARYING"         | "varbit"  // case insensitive
        "VARBIT"              | "varbit"
    }
    
    def "should map CHARACTER VARYING correctly and not confuse with BIT VARYING"() {
        expect: "CHARACTER VARYING types should map to 'text'"
        paramHandler.mapToPGArrayTypeName(baseType) == expectedMapping
        
        where:
        baseType                   | expectedMapping
        "character varying"        | "text"
        "character varying(255)"   | "text"
        "varchar"                  | "text"
        "varchar(100)"             | "text"
        "text"                     | "text"
        "CHARACTER VARYING"        | "text"  // case insensitive
        "VARCHAR"                  | "text"
    }
    
    def "should not map CHARACTER VARYING as BIT type"() {
        given: "various character varying type names"
        def charVaryingTypes = [
            "character varying",
            "character varying(255)", 
            "varchar",
            "varchar(100)",
            "text"
        ]
        
        expect: "none should be mapped as varbit"
        charVaryingTypes.every { type ->
            paramHandler.mapToPGArrayTypeName(type) != "varbit"
        }
        
        and: "all should be mapped as text"
        charVaryingTypes.every { type ->
            paramHandler.mapToPGArrayTypeName(type) == "text"
        }
    }
    
    def "should handle edge cases with 'varying' substring correctly"() {
        expect: "only proper BIT VARYING types should map to varbit"
        paramHandler.mapToPGArrayTypeName(baseType) == expectedMapping
        
        where:
        baseType                      | expectedMapping
        "varying"                     | "text"           // standalone 'varying' -> text default
        "some_varying_field"          | "text"           // field containing 'varying' -> text default  
        "bit varying"                 | "varbit"         // proper BIT VARYING -> varbit
        "character varying"           | "text"           // proper CHARACTER VARYING -> text
    }
}