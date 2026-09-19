package io.github.excalibase.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenFileSource}: the provisioning token arrives as a
 * file that the platform rotates in place without restarting the pod, so every
 * call must read through to the current contents — with a short throttle so a
 * hot path does not stat the file on every request, and a last-known-good
 * fallback so a transient read failure never drops the token.
 */
class TokenFileSourceTest {

    private final long[] now = {1_000L};

    private TokenFileSource source(Path path, String fallback) {
        return new TokenFileSource(path == null ? null : path.toString(), fallback, () -> now[0]);
    }

    @Test
    @DisplayName("null path falls back to the configured literal token")
    void nullPathUsesLiteral() {
        assertThat(source(null, "literal-pat").get()).isEqualTo("literal-pat");
    }

    @Test
    @DisplayName("blank path falls back to the configured literal token")
    void blankPathUsesLiteral() {
        assertThat(new TokenFileSource("   ", "literal-pat", () -> now[0]).get()).isEqualTo("literal-pat");
    }

    @Test
    @DisplayName("missing file falls back to the configured literal token")
    void missingFileUsesLiteral(@TempDir Path dir) {
        assertThat(source(dir.resolve("absent"), "literal-pat").get()).isEqualTo("literal-pat");
    }

    @Test
    @DisplayName("reads the file contents with trailing newline stripped")
    void readsTrimmedContents(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "  file-pat\n");

        assertThat(source(file, "literal-pat").get()).isEqualTo("file-pat");
    }

    @Test
    @DisplayName("a rewritten file is picked up once the 5s throttle has elapsed")
    void picksUpRotatedToken(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "first\n");
        TokenFileSource tokenSource = source(file, "literal-pat");
        assertThat(tokenSource.get()).isEqualTo("first");

        Files.writeString(file, "second\n");
        now[0] += 6_000L;

        assertThat(tokenSource.get()).isEqualTo("second");
    }

    @Test
    @DisplayName("within the 5s window the cached value is served, the file is not re-read")
    void cachesWithinThrottleWindow(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "first\n");
        TokenFileSource tokenSource = source(file, "literal-pat");
        assertThat(tokenSource.get()).isEqualTo("first");

        Files.writeString(file, "second\n");
        now[0] += 4_000L;

        assertThat(tokenSource.get()).isEqualTo("first");
    }

    @Test
    @DisplayName("a file deleted after a successful read keeps serving the last known good value")
    void deletedFileKeepsLastKnownGood(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "first\n");
        TokenFileSource tokenSource = source(file, "literal-pat");
        assertThat(tokenSource.get()).isEqualTo("first");

        Files.delete(file);
        now[0] += 6_000L;

        assertThat(tokenSource.get()).isEqualTo("first");
    }

    @Test
    @DisplayName("an empty file falls back rather than authenticating with an empty token")
    void emptyFileFallsBackToLiteral(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "\n");

        assertThat(source(file, "literal-pat").get()).isEqualTo("literal-pat");
    }

    @Test
    @DisplayName("a same-length rotation is still detected once the throttle elapses")
    void detectsSameLengthRotation(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "aaaa\n");
        TokenFileSource tokenSource = source(file, "literal-pat");
        assertThat(tokenSource.get()).isEqualTo("aaaa");

        Files.writeString(file, "bbbb\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(9_999_999L));
        now[0] += 6_000L;

        assertThat(tokenSource.get()).isEqualTo("bbbb");
    }

    @Test
    @DisplayName("require returns the current token when one resolves")
    void requireReturnsResolvedToken(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "file-pat\n");

        assertThat(source(file, "literal-pat").require()).isEqualTo("file-pat");
    }

    @Test
    @DisplayName("require fails explicitly when neither file nor literal resolves a token")
    void requireFailsWhenNothingConfigured(@TempDir Path dir) {
        assertThatThrownBy(() -> source(dir.resolve("absent"), "").require())
                .isInstanceOf(TokenUnavailableException.class);
        assertThatThrownBy(() -> source(null, null).require())
                .isInstanceOf(TokenUnavailableException.class);
    }

    @Test
    @DisplayName("the failure message never carries the token value")
    void requireFailureDoesNotLeakToken(@TempDir Path dir) {
        assertThatThrownBy(() -> source(dir.resolve("absent"), "   ").require())
                .hasMessageNotContaining("   ")
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("concurrent readers all observe a non-null token")
    void isThreadSafe(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("token"), "shared\n");
        TokenFileSource tokenSource = source(file, "literal-pat");

        Thread[] readers = new Thread[8];
        boolean[] ok = new boolean[readers.length];
        for (int i = 0; i < readers.length; i++) {
            final int index = i;
            readers[i] = Thread.ofVirtual().start(() -> ok[index] = "shared".equals(tokenSource.get()));
        }
        for (Thread reader : readers) {
            reader.join();
        }

        assertThat(ok).containsOnly(true);
    }
}
