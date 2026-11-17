package io.github.excalibase.config;

import io.github.excalibase.metadata.ReloadingMetadataSource;
import io.github.excalibase.permissions.NoPermissionSource;
import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.github.excalibase.security.TokenFileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code metadata.source} is a flag: unset, the deployment behaves exactly as before. */
class PermissionSourceSelectionTest {

    private static final TokenFileSource TOKEN = new TokenFileSource("", "t");

    @TempDir
    Path dir;

    private PermissionProvider pick(String source, String policyUrl, String path) {
        return pickUrl(source, policyUrl, path, "");
    }

    private PermissionProvider pickUrl(String source, String policyUrl, String path, String urlLocation) {
        return new AppConfig().permissionSource(source, policyUrl, path, 5000, urlLocation, "", "", false, 5000, 1000,
                1000, "demo", "", TOKEN, 30_000, 300_000, 2_000, null);
    }

    @Test
    void withMetadataSourceUnset_noPolicyUrlMeansNoSource() {
        assertThat(pick("", "", "")).isInstanceOf(NoPermissionSource.class);
    }

    @Test
    void withMetadataSourceUnset_aPolicyUrlMeansProvisioning() {
        assertThat(pick("", "http://provisioning/api", "")).isInstanceOf(ProvisioningPermissionProvider.class);
    }

    @Test
    void withMetadataSourceFile_theFileIsTheSource() throws IOException {
        Path file = Files.writeString(dir.resolve("permissions.yaml"), "format: 1\nprojectId: demo\ntables: []\n");

        PermissionProvider source = pick("file", "", file.toString());

        assertThat(source).isInstanceOf(ReloadingMetadataSource.class);
        assertThat(source.permissionsFor("demo").tables()).isEmpty();
        ((ReloadingMetadataSource) source).close();
    }

    @Test
    void aFileSourceAlongsideAPolicyUrl_isRefused() {
        assertThatThrownBy(() -> pick("file", "http://provisioning/api", "/x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withMetadataSourceUrl_theUrlIsTheSource() {
        PermissionProvider source = pickUrl("url", "", "", "http://127.0.0.1:1/permissions.yaml");

        assertThat(source).isInstanceOf(ReloadingMetadataSource.class);
        assertThat(((ReloadingMetadataSource) source).name()).isEqualTo("url");
        ((ReloadingMetadataSource) source).close();
    }

    @Test
    void aUrlSourceOverPlainHttpToAnOutsideHost_isRefused() {
        assertThatThrownBy(() -> pickUrl("url", "", "", "http://config.example.com/p.yaml"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-insecure");
    }
}
