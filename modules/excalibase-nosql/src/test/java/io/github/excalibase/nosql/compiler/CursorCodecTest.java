package io.github.excalibase.nosql.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    @DisplayName("encode then decode roundtrips")
    void roundtrip() {
        Instant now = Instant.parse("2026-04-19T12:34:56Z");
        String id = "11111111-2222-3333-4444-555555555555";
        String encoded = CursorCodec.encode(now, id);

        var decoded = CursorCodec.decode(encoded);
        assertThat(decoded.createdAt()).isEqualTo(now);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("encoded cursor is url-safe base64 without padding")
    void urlSafeBase64() {
        String encoded = CursorCodec.encode(Instant.now(), "test-id");
        assertThat(encoded).doesNotContain("=", "+", "/");
    }

    @Test
    @DisplayName("decode rejects null cursor")
    void decodeNull() {
        assertThatThrownBy(() -> CursorCodec.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("decode rejects malformed base64")
    void decodeMalformed() {
        assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode rejects payload without separator")
    void decodeMissingSeparator() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("no-separator-here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorCodec.decode(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("decode rejects non-ISO-8601 createdAt")
    void decodeBadTimestamp() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-timestamp|some-id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorCodec.decode(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    @DisplayName("decode rejects empty string")
    void decodeEmpty() {
        assertThatThrownBy(() -> CursorCodec.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
