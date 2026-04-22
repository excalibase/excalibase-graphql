package io.github.excalibase.nosql.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaValidatorTest {

    JsonSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JsonSchemaValidator(new ObjectMapper());
    }

    @Test
    @DisplayName("no schema registered → validate is a no-op")
    void noSchema_noOp() {
        var issues = validator.validate("any", Map.of("anything", 1));
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("valid doc passes validation")
    void validDoc_passes() {
        validator.registerSchema("users", Map.of(
                "type", "object",
                "required", List.of("email"),
                "properties", Map.of("email", Map.of("type", "string"))
        ));

        var issues = validator.validate("users", Map.of("email", "a@b.com"));
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("missing required field reports issue")
    void missingRequired_reportsIssue() {
        validator.registerSchema("users", Map.of(
                "type", "object",
                "required", List.of("email"),
                "properties", Map.of("email", Map.of("type", "string"))
        ));

        var issues = validator.validate("users", Map.of("name", "Vu"));
        assertThat(issues).isNotEmpty();
        assertThat(issues.getFirst()).containsKey("path").containsKey("message");
    }

    @Test
    @DisplayName("wrong type reports issue")
    void wrongType_reportsIssue() {
        validator.registerSchema("users", Map.of(
                "type", "object",
                "properties", Map.of("age", Map.of("type", "integer"))
        ));

        var issues = validator.validate("users", Map.of("age", "not-a-number"));
        assertThat(issues).isNotEmpty();
    }

    @Test
    @DisplayName("null schema removes registration")
    void nullSchema_removes() {
        validator.registerSchema("users", Map.of("type", "object",
                "required", List.of("email"),
                "properties", Map.of("email", Map.of("type", "string"))));
        validator.registerSchema("users", null);

        var issues = validator.validate("users", Map.of("nothing", 1));
        assertThat(issues).isEmpty();
    }
}
