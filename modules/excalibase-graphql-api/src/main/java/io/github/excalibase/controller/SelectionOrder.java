package io.github.excalibase.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import io.github.excalibase.json.JsonFieldOrder;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a GraphQL answer's fields in the order the query selected them, as the spec requires: the database
 * builds each object as JSON whose keys it sorts (Postgres jsonb, MySQL JSON).
 */
final class SelectionOrder {

    private static final String DATA_KEY = "data";

    private SelectionOrder() {
    }

    /** {@code answer} with its data in {@code query}'s selection order; anything else as it is. */
    static ResponseEntity<Object> apply(ResponseEntity<Object> answer, String query) {
        if (!(answer.getBody() instanceof Map<?, ?> body) || !(body.get(DATA_KEY) instanceof JsonNode data)) {
            return answer;
        }
        Document document = Parser.parse(query);
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.isEmpty()) {
            return answer;
        }
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        document.getDefinitionsOfType(FragmentDefinition.class)
                .forEach(fragment -> fragments.put(fragment.getName(), fragment));
        Map<String, Object> ordered = new LinkedHashMap<>();
        body.forEach((key, value) -> ordered.put(String.valueOf(key), value));
        ordered.put(DATA_KEY, order(data, operations.getFirst().getSelectionSet(), fragments));
        return ResponseEntity.status(answer.getStatusCode()).headers(answer.getHeaders()).body(ordered);
    }

    private static JsonNode order(JsonNode node, SelectionSet selections, Map<String, FragmentDefinition> fragments) {
        if (node == null || selections == null) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode items = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> items.add(order(item, selections, fragments)));
            return items;
        }
        if (!node.isObject()) {
            return node;
        }
        List<Field> fields = fields(selections, fragments);
        List<String> keys = fields.stream().map(SelectionOrder::key).toList();
        JsonNode ordered = JsonFieldOrder.ordered(node, keys);
        for (Field field : fields) {
            JsonNode value = ordered.get(key(field));
            if (value != null && field.getSelectionSet() != null) {
                ((ObjectNode) ordered).set(key(field),
                        order(value, field.getSelectionSet(), fragments));
            }
        }
        return ordered;
    }

    /** The fields of {@code selections}, fragments spread in place. */
    private static List<Field> fields(SelectionSet selections, Map<String, FragmentDefinition> fragments) {
        List<Field> fields = new ArrayList<>();
        for (Selection<?> selection : selections.getSelections()) {
            switch (selection) {
                case Field field -> fields.add(field);
                case InlineFragment inline -> fields.addAll(fields(inline.getSelectionSet(), fragments));
                case FragmentSpread spread -> {
                    FragmentDefinition fragment = fragments.get(spread.getName());
                    if (fragment != null) fields.addAll(fields(fragment.getSelectionSet(), fragments));
                }
                default -> { /* no other selection kind */ }
            }
        }
        return fields;
    }

    private static String key(Field field) {
        return field.getAlias() != null ? field.getAlias() : field.getName();
    }
}
