package io.github.excalibase.nosql.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-collection JSON Schema validator. Compiles declared schemas once at
 * {@code syncSchema} time and validates every write against the cached schema.
 *
 * <p>Uses Draft 2020-12. When a collection has no schema declared, validation
 * is a no-op (returns empty issue list).
 */
@Component
public class JsonSchemaValidator {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public JsonSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void registerSchema(String collection, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            compiled.remove(collection);
            return;
        }
        JsonNode node = mapper.valueToTree(schema);
        compiled.put(collection, FACTORY.getSchema(node));
    }

    public boolean hasSchema(String collection) {
        return compiled.containsKey(collection);
    }

    public List<Map<String, String>> validate(String collection, Map<String, Object> doc) {
        JsonSchema schema = compiled.get(collection);
        if (schema == null) return List.of();

        JsonNode node = mapper.valueToTree(doc);
        Set<ValidationMessage> messages = schema.validate(node);
        if (messages.isEmpty()) return List.of();

        var issues = new ArrayList<Map<String, String>>();
        for (ValidationMessage msg : messages) {
            var entry = new LinkedHashMap<String, String>();
            entry.put("path", msg.getInstanceLocation().toString());
            entry.put("message", msg.getMessage());
            issues.add(entry);
        }
        return issues;
    }

    public void clear() {
        compiled.clear();
    }
}
