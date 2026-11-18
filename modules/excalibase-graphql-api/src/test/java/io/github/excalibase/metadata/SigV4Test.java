package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The signer against the worked example AWS publishes for SigV4 on S3 (a ranged GET of one object). */
class SigV4Test {

    private static final S3Credentials.Resolved AWS_EXAMPLE = new S3Credentials.Resolved("AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "");
    private static final String EMPTY_BODY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant WHEN = Instant.parse("2013-05-24T00:00:00Z");

    @Test
    void matchesTheAwsPublishedExample() {
        TreeMap<String, String> range = new TreeMap<>(Map.of("range", "bytes=0-9"));

        Map<String, String> headers = SigV4.sign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                EMPTY_BODY, AWS_EXAMPLE, "us-east-1", WHEN, range);

        assertThat(headers.get("Authorization")).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        assertThat(headers).containsEntry("x-amz-date", "20130524T000000Z")
                .containsEntry("x-amz-content-sha256", EMPTY_BODY);
    }

    @Test
    void aSessionTokenIsSignedAndSent() {
        S3Credentials.Resolved temporary = new S3Credentials.Resolved("AK", "SK", "token-value");

        Map<String, String> headers = SigV4.sign("GET", URI.create("https://h.example/b/k"), EMPTY_BODY, temporary,
                "eu-west-1", WHEN);

        assertThat(headers).containsEntry("x-amz-security-token", "token-value");
        assertThat(headers.get("Authorization")).contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;"
                + "x-amz-security-token").doesNotContain("SK");
    }

    @Test
    void aNonDefaultPortIsPartOfTheSignedHost_andADefaultOneIsNot() {
        String withPort = SigV4.sign("GET", URI.create("http://minio.local:9000/b/k"), EMPTY_BODY, AWS_EXAMPLE,
                "us-east-1", WHEN).get("Authorization");
        String without = SigV4.sign("GET", URI.create("http://minio.local/b/k"), EMPTY_BODY, AWS_EXAMPLE,
                "us-east-1", WHEN).get("Authorization");
        String defaultSpelled = SigV4.sign("GET", URI.create("http://minio.local:80/b/k"), EMPTY_BODY, AWS_EXAMPLE,
                "us-east-1", WHEN).get("Authorization");

        assertThat(withPort).isNotEqualTo(without);
        assertThat(defaultSpelled).isEqualTo(without);
    }
}
