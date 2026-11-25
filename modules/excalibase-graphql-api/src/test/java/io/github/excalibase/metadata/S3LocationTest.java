package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3LocationTest {

    private static S3Settings settings(String endpoint, String bucket, String key, Boolean pathStyle,
                                       boolean allowInsecure) {
        return new S3Settings(endpoint, bucket, key, "eu-west-1", pathStyle, allowInsecure,
                S3Settings.Credentials.literal("id", "secret"), Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(10));
    }

    private static URI uri(String endpoint, String key, Boolean pathStyle) {
        return S3Location.require(settings(endpoint, "meta", key, pathStyle, false), "demo");
    }

    @Test
    void anEndpointOverride_isPathStyleByDefault() {
        assertThat(uri("http://127.0.0.1:9000", "permissions.yaml", null))
                .hasToString("http://127.0.0.1:9000/meta/permissions.yaml");
    }

    @Test
    void noEndpoint_meansAwsAndVirtualHostedStyle() {
        assertThat(uri("", "permissions.yaml", null)).hasToString("https://meta.s3.eu-west-1.amazonaws.com/permissions.yaml");
    }

    @Test
    void virtualHostedStyleCanBeAskedForOnACustomEndpoint() {
        assertThat(uri("https://s3.example.com", "p.yaml", false)).hasToString("https://meta.s3.example.com/p.yaml");
    }

    @Test
    void pathStyleCanBeAskedForOnAws() {
        assertThat(uri("", "p.yaml", true)).hasToString("https://s3.eu-west-1.amazonaws.com/meta/p.yaml");
    }

    @Test
    void theKey_isEncodedPerSegment_andProjectIdIsSubstituted() {
        assertThat(uri("http://localhost:9000", "{projectId}/my docs/a+b.yaml", null))
                .hasToString("http://localhost:9000/meta/demo/my%20docs/a%2Bb.yaml");
    }

    @Test
    void aDefaultPortSpelledOut_isDropped_soTheSignedHostMatchesTheSentOne() {
        assertThat(uri("https://s3.example.com:443", "p.yaml", true)).hasToString("https://s3.example.com/meta/p.yaml");
    }

    @Test
    void anUpperCaseHost_isLowerCased_soTheSignedHostMatchesTheSentOne() {
        assertThat(uri("http://LOCALHOST:9000", "p.yaml", null)).hasToString("http://localhost:9000/meta/p.yaml");
        assertThat(uri("https://S3.Example.COM", "p.yaml", false)).hasToString("https://meta.s3.example.com/p.yaml");
    }

    @Test
    void plainHttpOutsideTheMachineOrCluster_needsAnExplicitOptIn() {
        assertThatThrownBy(() -> S3Location.require(settings("http://storage.example.com", "meta", "p.yaml", null, false), "demo"))
                .hasMessageContaining("allow-insecure");
        assertThat(S3Location.require(settings("http://storage.example.com", "meta", "p.yaml", null, true), "demo")).isNotNull();
        assertThat(S3Location.require(settings("http://minio.storage.svc:9000", "meta", "p.yaml", null, false), "demo"))
                .isNotNull();
    }

    @Test
    void unusableSettings_areRefused_withoutRepeatingTheValue() {
        assertThatThrownBy(() -> uri("ftp://h", "p.yaml", null)).hasMessageContaining("metadata.s3.endpoint")
                .hasMessageNotContaining("ftp");
        assertThatThrownBy(() -> uri("https://user:pw@h", "p.yaml", null)).hasMessageNotContaining("pw");
        assertThatThrownBy(() -> uri("https://h/extra/path", "p.yaml", null)).hasMessageContaining("no path");
        assertThatThrownBy(() -> uri("https://h?x=1", "p.yaml", null)).hasMessageContaining("no path");
        assertThatThrownBy(() -> S3Location.require(settings("https://h", "", "p.yaml", null, false), "demo"))
                .hasMessageContaining("metadata.s3.bucket");
        assertThatThrownBy(() -> S3Location.require(settings("https://h", "Bad_Bucket", "p.yaml", null, false), "demo"))
                .hasMessageContaining("metadata.s3.bucket");
        assertThatThrownBy(() -> uri("https://h", " ", null)).hasMessageContaining("metadata.s3.key");
        assertThatThrownBy(() -> uri("https://h", "a/../b", null)).hasMessageContaining("metadata.s3.key");
        assertThatThrownBy(() -> S3Location.require(new S3Settings("https://h", "meta", "k", "", null, false,
                S3Settings.Credentials.literal("i", "s"), Duration.ofSeconds(5), Duration.ofSeconds(3),
                Duration.ofSeconds(10)), "demo")).hasMessageContaining("metadata.s3.region");
    }

    @Test
    void settings_neverPrintTheirCredentials() {
        S3Settings s = settings("https://h", "meta", "k", null, false);

        assertThat(s.toString()).doesNotContain("secret");
        assertThat(s.credentials().toString()).doesNotContain("secret");
        assertThat(new S3Credentials.Resolved("id", "secret", "tok").toString()).doesNotContain("secret", "tok");
    }
}
