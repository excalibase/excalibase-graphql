package io.github.excalibase.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;

/**
 * Reads JSON or YAML text into a tree. Anything that could make the text mean something other than what it
 * says is refused: duplicate keys, anchors and aliases, merge keys ({@code <<}), keys that are not text, and
 * nesting beyond the parsers' limits. Dates stay text.
 */
final class ConfigText {

    private ConfigText() {
    }

    static JsonNode read(String text, ObjectMapper json) {
        if (text.isBlank()) {
            throw new MetadataDocumentException("document: is empty");
        }
        try {
            return text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")
                    ? json.readTree(text) : yaml(text, json);
        } catch (JsonProcessingException e) {
            var location = e.getLocation();
            throw new MetadataDocumentException("json: " + e.getOriginalMessage()
                    + (location == null ? "" : " at line " + location.getLineNr()), e);
        }
    }

    private static JsonNode yaml(String text, ObjectMapper json) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(MetadataDocumentParser.MAX_BYTES);
        try {
            Yaml yaml = new Yaml(new TextDates(options));
            Node root = yaml.compose(new StringReader(text));
            if (root != null) {
                requirePlain(root);
            }
            return json.valueToTree(yaml.load(text));
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            String where = mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
            throw new MetadataDocumentException("yaml: " + e.getProblem() + where, e);
        } catch (YAMLException e) {
            throw new MetadataDocumentException("yaml: " + e.getMessage(), e);
        }
    }

    /** The format has no use for anchors or merges, and each is a way for part of a rule to vanish unseen. */
    private static void requirePlain(Node node) {
        if (node.getAnchor() != null) {
            throw new MetadataDocumentException("yaml: anchors and aliases are not supported (line "
                    + (node.getStartMark().getLine() + 1) + ")");
        }
        if (node instanceof MappingNode mapping) {
            for (NodeTuple entry : mapping.getValue()) {
                requireTextKey(entry.getKeyNode());
                requirePlain(entry.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            sequence.getValue().forEach(ConfigText::requirePlain);
        }
    }

    private static void requireTextKey(Node key) {
        int line = key.getStartMark().getLine() + 1;
        if (Tag.MERGE.equals(key.getTag())) {
            throw new MetadataDocumentException("yaml: merge keys (<<) are not supported (line " + line + ")");
        }
        if (!Tag.STR.equals(key.getTag())) {
            throw new MetadataDocumentException("yaml: keys must be text (line " + line + ")");
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
