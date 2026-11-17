package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlLocationTest {

    @Test
    void https_isAlwaysAccepted() {
        assertThat(UrlLocation.require("https://config.example.com/p.yaml", "demo", false).getHost())
                .isEqualTo("config.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/p", "http://127.0.0.1/p", "http://[::1]:9/p",
            "http://permissions/p", "http://perms.team.svc/p", "http://perms.team.svc.cluster.local/p"})
    void http_isAcceptedForLocalAndClusterInternalHosts(String location) {
        assertThat(UrlLocation.require(location, "demo", false)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/p", "http://10.1.2.3/p", "http://perms.example.org:8080/p"})
    void http_isRefusedForOtherHosts_withoutAllowInsecure(String location) {
        assertThatThrownBy(() -> UrlLocation.require(location, "demo", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-insecure");
    }

    @Test
    void http_isAcceptedForOtherHosts_withAllowInsecure() {
        assertThat(UrlLocation.require("http://example.com/p", "demo", true)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ftp://example.com/p", "file:///etc/passwd", "not a url", "https:///p",
            "https://user:secret@example.com/p"})
    void otherLocations_areRefused(String location) {
        assertThatThrownBy(() -> UrlLocation.require(location, "demo", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata.url.location")
                .hasMessageNotContaining("secret");
    }

    @Test
    void projectIdPlaceholder_isFilled() {
        assertThat(UrlLocation.require("https://c.example.com/{projectId}/p.yaml", "demo", false).getPath())
                .isEqualTo("/demo/p.yaml");
    }
}
