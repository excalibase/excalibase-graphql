package io.github.excalibase.metadata;

import io.github.excalibase.permissions.InsertPermission;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataDocumentParserTest {

    private final MetadataDocumentParser parser =
            new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT);

    @Test
    void aYamlDocument_isParsedWithTheVersionTheSourceGives() {
        PermissionSet permissions = parser.parse(GOOD_YAML, 7);

        assertThat(permissions.projectId()).isEqualTo("demo");
        assertThat(permissions.version()).isEqualTo(7);
        assertThat(permissions.tables()).hasSize(1);
        assertThat(permissions.tables().get(0).table()).isEqualTo("public.orders");
    }

    @Test
    void aJsonDocument_isParsedTheSame() {
        assertThat(parser.parse(GOOD_JSON, 1).tables()).hasSize(1);
    }

    @Test
    void theFunctionSections_mayBeLeftOut() {
        assertThat(parser.parse(GOOD_YAML, 1).functions()).isEmpty();
    }

    @Test
    void aMissingOrUnknownFormat_isRefused() {
        assertThatThrownBy(() -> parser.parse("projectId: demo\ntables: []\n", 1))
                .hasMessageContaining("format");
        assertThatThrownBy(() -> parser.parse("format: 2\nprojectId: demo\ntables: []\n", 1))
                .hasMessageContaining("format");
    }

    @Test
    void anUnknownTopLevelKey_isRefused_andSoIsAVersionTheDocumentCannotSet() {
        assertThatThrownBy(() -> parser.parse(BAD_UNKNOWN_KEY, 1))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("surprise");
        assertThatThrownBy(() -> parser.parse(GOOD_YAML + "version: 9\n", 1))
                .hasMessageContaining("version");
    }

    @Test
    void aRuleThatBreaksTheGrammar_isRefusedByTheSameParserWithItsPath() {
        assertThatThrownBy(() -> parser.parse(BAD_RULE, 1))
                .isInstanceOf(MetadataDocumentException.class)
                .hasMessageContaining("tables[0].select.filter");
    }

    @Test
    void duplicateKeys_areRefused_inYamlAndInJson() {
        assertThatThrownBy(() -> parser.parse(GOOD_YAML + "projectId: other\n", 1))
                .isInstanceOf(MetadataDocumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"format\":1,\"format\":1,\"projectId\":\"demo\"}", 1))
                .isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void aDocumentOverOneMiB_isRefused() {
        String big = GOOD_YAML + "# " + "x".repeat(MetadataDocumentParser.MAX_BYTES) + "\n";

        assertThatThrownBy(() -> parser.parse(big, 1)).hasMessageContaining("1 MiB");
    }

    @Test
    void yamlAliasBombs_areRefused() {
        StringBuilder bomb = new StringBuilder("format: 1\nprojectId: demo\ntables: []\na0: &a0 [x]\n");
        for (int level = 1; level < 12; level++) {
            bomb.append("a").append(level).append(": &a").append(level).append(" [");
            for (int i = 0; i < 10; i++) {
                bomb.append(i == 0 ? "" : ", ").append("*a").append(level - 1);
            }
            bomb.append("]\n");
        }

        String document = bomb.toString();

        assertThatThrownBy(() -> parser.parse(document, 1)).isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void aSyntaxError_namesTheLine() {
        assertThatThrownBy(() -> parser.parse(BAD_SYNTAX, 1))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("line");
    }

    @Test
    void aDocumentThatIsNotAnObject_isRefused() {
        assertThatThrownBy(() -> parser.parse("- a\n- b\n", 1)).hasMessageContaining("object");
        assertThatThrownBy(() -> parser.parse("", 1)).isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void aRuleNamingAReservedSchema_isRefused() {
        assertThatThrownBy(() -> parser.parse(BAD_RESERVED, 1))
                .hasMessageContaining("excalibase_meta").hasMessageContaining("reserved");
        assertThatThrownBy(() -> parser.parse(GOOD_YAML.replace("public.orders", "auth.users"), 1))
                .hasMessageContaining("reserved");
    }

    @Test
    void aSchemaAddedByConfiguration_isReservedToo() {
        MetadataDocumentParser configured = new MetadataDocumentParser(ReservedSchemas.fromConfig("legacy"), PROJECT);

        assertThatThrownBy(() -> configured.parse(GOOD_YAML.replace("public.orders", "legacy.t"), 1))
                .hasMessageContaining("legacy");
    }

    @Test
    void aTrackedFunctionInAReservedSchema_isRefused() {
        String document = """
                format: 1
                projectId: demo
                tables: []
                functions:
                  - function: auth.login
                    exposedAs: MUTATION
                    inferPermissions: false
                    sessionArgument: null
                functionPermissions: []
                """;

        assertThatThrownBy(() -> parser.parse(document, 1)).hasMessageContaining("reserved");
    }

    @Test
    void aProjectThatIsNotTheDeployments_isRefused() {
        assertThatThrownBy(() -> parser.parse(withProject("someone-else"), 1))
                .hasMessageContaining("someone-else").hasMessageContaining("demo");
    }

    @Test
    void withoutAnExpectedProject_anyProjectIsAccepted() {
        MetadataDocumentParser open = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), null);

        assertThat(open.parse(withProject("anything"), 1).projectId()).isEqualTo("anything");
    }

    @Test
    void anUnquotedYamlDate_staysTheTextTheAuthorWrote() {
        String document = GOOD_YAML + "    insert:\n      check: {}\n      columns: \"*\"\n"
                + "      set:\n        note: 2024-01-01\n";

        InsertPermission insert = parser.parse(document, 1).tables().get(0).insert().orElseThrow();

        assertThat(insert.set()).containsEntry("note", new Literal("2024-01-01"));
    }

    @Test
    void aFormatThatOnlyFitsAnIntAfterOverflow_isRefused() {
        assertThatThrownBy(() -> parser.parse("format: 4294967297\nprojectId: demo\ntables: []\n", 1))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("format");
    }

    @Test
    void yamlMergeKeys_areRefused_soNoFilterIsSilentlyLost() {
        String merged = GOOD_YAML.replace("      filter: {}\n",
                "      <<: {filter: {\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}}\n      filter: {}\n");

        assertThatThrownBy(() -> parser.parse(merged, 1))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("merge");
    }

    @Test
    void yamlAnchorsAndAliases_areRefused() {
        String anchored = GOOD_YAML.replace("filter: {}", "filter: &all {}")
                + "  - table: public.secrets\n    role: user\n    select:\n      filter: *all\n      columns: \"*\"\n";

        assertThatThrownBy(() -> parser.parse(anchored, 1))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("anchor");
    }

    @Test
    void aNullKey_isARefusal_notAnException() {
        assertThatThrownBy(() -> parser.parse(GOOD_YAML + "~: x\n", 1))
                .isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void jsonNestedThousandsDeep_isARefusal_notANullPointer() {
        String deep = "[".repeat(5000) + "]".repeat(5000);

        assertThatThrownBy(() -> parser.parse(deep, 1)).isInstanceOf(MetadataDocumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"a\":" + deep + "}", 1))
                .isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void yamlNestedThousandsDeep_isARefusal() {
        String deep = "a: " + "[".repeat(5000) + "]".repeat(5000);

        assertThatThrownBy(() -> parser.parse(deep, 1)).isInstanceOf(MetadataDocumentException.class);
    }
}
