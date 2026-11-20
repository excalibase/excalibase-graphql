package io.github.excalibase.support;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailFastPullPolicyTest {

    private static final DockerImageName MINIO = DockerImageName.parse("pgsty/minio:RELEASE.1");
    private static final String RATE_LIMIT = "toomanyrequests: You have reached your unauthenticated pull rate limit";

    private final AtomicInteger pulls = new AtomicInteger();

    @Test
    void anImageAlreadyOnTheHost_isNotPulled() {
        FailFastPullPolicy policy = new FailFastPullPolicy(image -> true, image -> pulls.incrementAndGet());

        assertThat(policy.shouldPull(MINIO)).isFalse();
        assertThat(pulls).hasValue(0);
    }

    @Test
    void aMissingImage_isPulledHere_soTheLibraryHasNothingLeftToRetry() {
        FailFastPullPolicy policy = new FailFastPullPolicy(image -> false, image -> pulls.incrementAndGet());

        assertThat(policy.shouldPull(MINIO)).isFalse();
        assertThat(pulls).hasValue(1);
    }

    @Test
    void aFailedPull_failsAtOnce_namingTheImageAndTheCause() {
        FailFastPullPolicy policy = new FailFastPullPolicy(image -> false, image -> {
            throw new IllegalStateException(RATE_LIMIT);
        });

        assertThatThrownBy(() -> policy.shouldPull(MINIO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pgsty/minio:RELEASE.1")
                .hasMessageContaining("toomanyrequests")
                .hasMessageContaining("docker login");
    }

    @Test
    void afterOneFailedPull_everyLaterAskFailsWithoutPullingAgain() {
        FailFastPullPolicy policy = new FailFastPullPolicy(image -> false, image -> {
            pulls.incrementAndGet();
            throw new IllegalStateException(RATE_LIMIT);
        });

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toomanyrequests");

        assertThat(pulls).hasValue(1);
    }

    @Test
    void aFailureForOneImage_doesNotBlockAnother() {
        DockerImageName postgres = DockerImageName.parse("postgres:16-alpine");
        FailFastPullPolicy policy = new FailFastPullPolicy(image -> false, image -> {
            if (image.equals(MINIO)) {
                throw new IllegalStateException(RATE_LIMIT);
            }
        });

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThat(policy.shouldPull(postgres)).isFalse();
    }
}
