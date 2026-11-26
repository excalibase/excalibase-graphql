package io.github.excalibase.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The collections a document database serves, declared in a file (YAML or JSON) with a top-level {@code collections:}
 * list. They are declared, never sampled: the exposed API must not change when the data does.
 */
public final class DocumentCollections {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 1024 * 1024;

    private DocumentCollections() {
    }

    /** @throws IllegalStateException the file is missing, too big, or does not hold a {@code collections:} list */
    public static List<Map<String, Object>> read(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("No collections declared: set app.mongodb.collections-file "
                    + "(APP_MONGODB_COLLECTIONS_FILE) to a file with a collections: list");
        }
        String text;
        try {
            Path path = Path.of(file);
            if (Files.size(path) > MAX_BYTES) {
                throw new IllegalStateException("The collections file is larger than " + MAX_BYTES + " bytes");
            }
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The collections file cannot be read: " + e.getClass().getSimpleName(), e);
        }
        try {
            JsonNode collections = ConfigText.read(text, JSON).path("collections");
            if (!collections.isArray()) {
                throw new IllegalStateException("The collections file needs a top-level collections: list");
            }
            return JSON.convertValue(collections, new TypeReference<>() { });
        } catch (MetadataDocumentException e) {
            throw new IllegalStateException("The collections file is not valid: " + e.getMessage(), e);
        }
    }
}
