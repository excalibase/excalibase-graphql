package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilterErrorsTest {

    @Test
    @DisplayName("a refusal from a security filter is typed as the servlet stack typed it, with its charset")
    void contentTypeNamesTheCharset() {
        HttpResponse<String> refusal = FilterErrors.write(HttpStatus.UNAUTHORIZED, "Invalid or expired token",
                "invalid_token");

        assertThat(refusal.getContentType().orElseThrow().toString()).isEqualTo("application/json;charset=ISO-8859-1");
        assertThat(refusal.body()).isEqualTo("{\"errors\":[{\"message\":\"Invalid or expired token\","
                + "\"extensions\":{\"code\":\"invalid_token\"}}]}");
    }
}
