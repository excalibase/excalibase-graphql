package io.github.excalibase.rest.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** The rows of the JSON array a database answered with. */
final class DatabaseRows {

    private DatabaseRows() {
    }

    /** No answer is no rows; an answer that is not JSON is a failure, never an empty list. */
    static List<JsonNode> parse(ObjectMapper mapper, Object body) {
        if (body == null) {
            return List.of();
        }
        try {
            List<JsonNode> rows = new ArrayList<>();
            mapper.readTree(body.toString()).forEach(rows::add);
            return rows;
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("the database's answer is not JSON", unreadable);
        }
    }
}
