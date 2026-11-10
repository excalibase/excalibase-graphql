package io.github.excalibase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void selectionOrderUsesTheGivenDocument() throws Exception {
        Document document = Parser.parse("{ t { b a } }");
        var data = MAPPER.readTree("{\"t\":{\"a\":1,\"b\":2}}");

        ResponseEntity<Object> ordered = SelectionOrder.apply(ResponseEntity.ok(Map.of("data", data)), document);

        assertThat(((Map<?, ?>) ordered.getBody()).get("data").toString()).isEqualTo("{\"t\":{\"b\":2,\"a\":1}}");
    }

    @Test
    void mixedQueryKeepsOnlyTheDataFields() {
        Document document = Parser.parse("{ __typename t { a } }");

        var dataPart = MixedQuery.dataPart(document);

        assertThat(dataPart).isPresent();
        assertThat(AstPrinter.printAst(dataPart.get())).doesNotContain("__typename").contains("t");
    }

    @Test
    void mixedQueryOfIntrospectionOnlyHasNoDataPart() {
        assertThat(MixedQuery.dataPart(Parser.parse("{ __schema { types { name } } }"))).isEmpty();
    }
}
