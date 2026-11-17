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
        return new AppConfig().permissionSource(source, policyUrl, path, 5000, "demo", "", TOKEN, 30_000, 300_000,
                2_000, null);
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
}
