package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3CredentialsTest {

    @TempDir
    Path dir;

    @Test
    void literalValues_areUsed_andTheSessionTokenIsOptional() throws IOException {
        S3Credentials.Resolved resolved = S3Credentials.load(S3Settings.Credentials.literal("id", "secret"));

        assertThat(resolved.accessKeyId()).isEqualTo("id");
        assertThat(resolved.secretAccessKey()).isEqualTo("secret");
        assertThat(resolved.sessionToken()).isEmpty();
    }

    @Test
    void aFileIsReadOnEveryLoad_soARotationNeedsNoRestart() throws IOException {
        Path secret = Files.writeString(dir.resolve("secret"), "first\n");
        S3Settings.Credentials credentials = new S3Settings.Credentials("id", "", "", "", secret.toString(), "");

        assertThat(S3Credentials.load(credentials).secretAccessKey()).isEqualTo("first");
        Files.writeString(secret, "second\n");
        assertThat(S3Credentials.load(credentials).secretAccessKey()).isEqualTo("second");
    }

    @Test
    void aLiteralWinsOverAFile() throws IOException {
        Path secret = Files.writeString(dir.resolve("secret"), "from-file");
        S3Settings.Credentials credentials = new S3Settings.Credentials("id", "from-literal", "", "",
                secret.toString(), "");

        assertThat(S3Credentials.load(credentials).secretAccessKey()).isEqualTo("from-literal");
    }

    @Test
    void missingCredentials_orAnUnreadableFile_failWithoutNamingAValue() {
        assertThatThrownBy(() -> S3Credentials.load(S3Settings.Credentials.NONE)).isInstanceOf(IOException.class)
                .hasMessageContaining("access key");
        S3Settings.Credentials gone = new S3Settings.Credentials("id", "", "", "", dir.resolve("nope").toString(), "");
        assertThatThrownBy(() -> S3Credentials.load(gone)).isInstanceOf(IOException.class)
                .hasMessageNotContaining(dir.toString());
    }

    @Test
    void isConfigured_saysWhetherBothKeysHaveASource() {
        assertThat(S3Settings.Credentials.literal("a", "b").configured()).isTrue();
        assertThat(S3Settings.Credentials.NONE.configured()).isFalse();
        assertThat(new S3Settings.Credentials("a", "", "", "", "", "").configured()).isFalse();
        assertThat(new S3Settings.Credentials("", "", "", "/run/id", "/run/secret", "").configured()).isTrue();
    }

    @Test
    void theEnvironment_isReadUnderTheStandardAwsNames_andOurOwnWin() {
        S3Settings.Credentials fromAws = S3Credentials.fromEnvironment(Map.of("AWS_ACCESS_KEY_ID", "aws-id",
                "AWS_SECRET_ACCESS_KEY", "aws-secret", "AWS_SESSION_TOKEN", "aws-token"), "", "", "", "", "", "");
        assertThat(fromAws.accessKeyId()).isEqualTo("aws-id");
        assertThat(fromAws.sessionToken()).isEqualTo("aws-token");

        S3Settings.Credentials ours = S3Credentials.fromEnvironment(Map.of("AWS_ACCESS_KEY_ID", "aws-id",
                "AWS_SECRET_ACCESS_KEY", "aws-secret"), "own-id", "own-secret", "", "", "", "");
        assertThat(ours.accessKeyId()).isEqualTo("own-id");
        assertThat(ours.secretAccessKey()).isEqualTo("own-secret");
    }

    @Test
    void anOwnFileSetting_isNotOverriddenByAmbientAwsVariables() {
        Map<String, String> ambient = Map.of("AWS_ACCESS_KEY_ID", "ambient-id", "AWS_SECRET_ACCESS_KEY", "ambient-secret");

        S3Settings.Credentials chosen = S3Credentials.fromEnvironment(ambient, "", "", "", "/run/id", "/run/secret", "");

        assertThat(chosen.accessKeyId()).isEmpty();
        assertThat(chosen.secretAccessKey()).isEmpty();
        assertThat(chosen.accessKeyIdFile()).isEqualTo("/run/id");
        assertThat(chosen.secretAccessKeyFile()).isEqualTo("/run/secret");
    }

    @Test
    void aStrayAwsSessionToken_isNotAttachedToStaticKeysOfOurOwn() {
        Map<String, String> ambient = Map.of("AWS_SESSION_TOKEN", "stray", "AWS_SESSION_TOKEN_FILE", "/stray");

        S3Settings.Credentials chosen = S3Credentials.fromEnvironment(ambient, "own-id", "own-secret", "", "", "", "");

        assertThat(chosen.sessionToken()).isEmpty();
        assertThat(chosen.sessionTokenFile()).isEmpty();
        assertThat(chosen.accessKeyId()).isEqualTo("own-id");
    }

    @Test
    void withNoSettingOfOurOwn_theWholeSetComesFromAws() {
        S3Settings.Credentials chosen = S3Credentials.fromEnvironment(Map.of("AWS_ACCESS_KEY_ID_FILE", "/aws/id",
                "AWS_SECRET_ACCESS_KEY_FILE", "/aws/secret"), "", "", "", "", "", "");

        assertThat(chosen.accessKeyIdFile()).isEqualTo("/aws/id");
        assertThat(chosen.secretAccessKeyFile()).isEqualTo("/aws/secret");
    }
}
