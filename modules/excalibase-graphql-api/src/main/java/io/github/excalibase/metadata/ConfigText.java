package io.github.excalibase.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;

/** Reads JSON or YAML text into a tree, refusing duplicate keys and alias bombs, and keeping dates as text. */
final class ConfigText {

    private static final int MAX_ALIASES = 10;

    private ConfigText() {
    }

    static JsonNode read(String text, ObjectMapper json) {
        if (text.isBlank()) {
            throw new MetadataDocumentException("document: is empty");
        }
        try {
            return text.stripLeading().startsWith("{") ? json.readTree(text) : yaml(text, json);
        } catch (JsonProcessingException e) {
            throw new MetadataDocumentException("json: " + e.getOriginalMessage() + " at line "
                    + e.getLocation().getLineNr(), e);
        }
    }

    private static JsonNode yaml(String text, ObjectMapper json) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setCodePointLimit(MetadataDocumentParser.MAX_BYTES);
        try {
            return json.valueToTree(new Yaml(new TextDates(options)).load(text));
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            String where = mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
            throw new MetadataDocumentException("yaml: " + e.getProblem() + where, e);
        } catch (YAMLException e) {
            throw new MetadataDocumentException("yaml: " + e.getMessage(), e);
        }
    }

    /** An unquoted {@code 2024-01-01} stays the text the author wrote instead of becoming a timestamp. */
    private static final class TextDates extends SafeConstructor {
        TextDates(LoaderOptions options) {
            super(options);
            yamlConstructors.put(Tag.TIMESTAMP, new ConstructYamlStr());
        }
    }
}
