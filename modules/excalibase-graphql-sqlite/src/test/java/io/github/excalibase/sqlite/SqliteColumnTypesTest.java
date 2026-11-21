package io.github.excalibase.sqlite;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteColumnTypesTest {

    @ParameterizedTest
    @CsvSource({"INTEGER,integer", "INT,integer", "BIGINT,bigint", "UNSIGNED BIG INT,bigint", "SMALLINT,smallint",
            "VARCHAR(20),text", "TEXT,text", "'',text", "BLOB,bytea", "REAL,double precision", "DOUBLE PRECISION,double precision",
            "FLOAT,double precision", "NUMERIC,numeric", "'DECIMAL(10,2)',numeric", "BOOLEAN,boolean", "JSON,json",
            "DATETIME,timestamp", "TIMESTAMPTZ,timestamptz", "DATE,date", "UUID,uuid", "WHATEVER,text"})
    void declaredType_isReflectedAsTheEngineKnowsIt(String declared, String expected) {
        assertThat(SqliteColumnTypes.engineType(declared)).isEqualTo(expected);
    }
}
