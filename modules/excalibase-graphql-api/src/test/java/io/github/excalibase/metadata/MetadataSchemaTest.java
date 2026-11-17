package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The checked-in JSON Schema and the engine's parser must agree on what a document looks like. */
class MetadataSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MetadataDocumentParser parser = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT);

    private static Path schemaFile() {
        Path here = Path.of("").toAbsolutePath();
        while (!Files.exists(here.resolve("docs/schemas/metadata-v1.json"))) {
            here = here.getParent();
        }
        return here.resolve("docs/schemas/metadata-v1.json");
    }

    private JsonSchema schema() throws IOException {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(MAPPER.readTree(Files.readString(schemaFile())));
    }

    private boolean schemaAccepts(String yaml) throws IOException {
        JsonNode node = MAPPER.valueToTree(new Yaml().load(yaml));
        return schema().validate(node).isEmpty();
    }

    private boolean parserAccepts(String text) {
        try {
            parser.parse(text, 1);
            return true;
        } catch (MetadataDocumentException e) {
            return false;
        }
    }

    @Test
    void goodDocuments_areAcceptedByBoth() throws IOException {
        for (String good : List.of(GOOD_YAML, GOOD_YAML_TWO_RULES)) {
            assertThat(schemaAccepts(good)).as(good).isTrue();
            assertThat(parserAccepts(good)).as(good).isTrue();
        }
    }

    @Test
    void documentsTheStructureForbids_areRefusedByBoth() throws IOException {
        List<String> bad = List.of(
                BAD_UNKNOWN_KEY,
                WITH_JWT,
                BAD_RULE,
                "projectId: demo\ntables: []\n",
                "format: 2\nprojectId: demo\ntables: []\n",
                GOOD_YAML + "version: 3\n",
                "format: 1\nprojectId: demo\njwt:\n  sharedSecretFrom: hunter2\ntables: []\n",
                "format: 1\nprojectId: demo\njwt:\n  algorithms: [none]\ntables: []\n",
                GOOD_YAML.replace("role: user", "role: service"),
                GOOD_YAML.replace("public.orders", "Orders"));
        for (String document : bad) {
            assertThat(schemaAccepts(document)).as("schema: " + document).isFalse();
            assertThat(parserAccepts(document)).as("parser: " + document).isFalse();
        }
    }

    @Test
    void theSampleInDeployStandalone_isValidToBoth() throws IOException {
        Path root = schemaFile().getParent().getParent().getParent();
        String sample = Files.readString(root.resolve("deploy/standalone/metadata/permissions.yaml"));

        assertThat(schemaAccepts(sample)).isTrue();
        assertThat(new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "my-app").parse(sample, 1).tables())
                .isNotEmpty();
    }

    @Test
    void theSchemaIsPublishedUnderItsOwnId() throws IOException {
        assertThat(MAPPER.readTree(Files.readString(schemaFile())).get("$id").asText())
                .isEqualTo("https://excalibase.io/schemas/metadata-v1.json");
    }
}
