package io.github.excalibase.testsupport;

import com.github.dockerjava.api.model.Image;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailFastPullPolicyTest {

    private static final DockerImageName MINIO = DockerImageName.parse("pgsty/minio:RELEASE.1");
    private static final String RATE_LIMIT = "toomanyrequests: You have reached your unauthenticated pull rate limit";

    private final AtomicInteger pulls = new AtomicInteger();
    private final List<Long> pauses = new ArrayList<>();

    private FailFastPullPolicy policy(FailFastPullPolicy.Presence presence, FailFastPullPolicy.Puller puller) {
        return new FailFastPullPolicy(presence, puller, pauses::add);
    }

    private FailFastPullPolicy.Puller failing(RuntimeException failure) {
        return image -> {
            pulls.incrementAndGet();
            throw failure;
        };
    }

    @Test
    void anImageAlreadyOnTheHost_isNotPulled() {
        FailFastPullPolicy policy = policy(image -> true, image -> pulls.incrementAndGet());

        assertThat(policy.shouldPull(MINIO)).isFalse();
        assertThat(pulls).hasValue(0);
    }

    @Test
    void aMissingImage_isPulledHere_soTheLibraryHasNothingLeftToRetry() {
        FailFastPullPolicy policy = policy(image -> false, image -> pulls.incrementAndGet());

        assertThat(policy.shouldPull(MINIO)).isFalse();
        assertThat(pulls).hasValue(1);
    }

    @Test
    void aRateLimit_failsAtOnce_namingTheImage_andPointsAtDockerLogin() {
        FailFastPullPolicy policy = policy(image -> false, failing(new IllegalStateException(RATE_LIMIT)));

        assertThatThrownBy(() -> policy.shouldPull(MINIO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pgsty/minio:RELEASE.1")
                .hasMessageContaining("toomanyrequests")
                .hasMessageContaining("docker login");
        assertThat(pulls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void afterARateLimit_everyLaterAskFailsWithoutPullingAgain() {
        FailFastPullPolicy policy = policy(image -> false, failing(new IllegalStateException(RATE_LIMIT)));

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toomanyrequests");

        assertThat(pulls).hasValue(1);
    }

    @Test
    void everyClearRefusal_isCached_notOnlyTheRateLimit() {
        for (String refusal : List.of("unauthorized: authentication required", "pull access denied for x",
                "HTTP 403 forbidden", "manifest unknown: manifest unknown", "HTTP 404")) {
            AtomicInteger own = new AtomicInteger();
            FailFastPullPolicy policy = policy(image -> false, image -> {
                own.incrementAndGet();
                throw new IllegalStateException(refusal);
            });

            assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);

            assertThat(own).as(refusal).hasValue(1);
        }
    }

    @Test
    void aTransientError_isRetriedOnceAfterAShortPause_andNotCached() {
        AtomicInteger calls = new AtomicInteger();
        FailFastPullPolicy policy = policy(image -> false, image -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("connection reset by peer");
            }
        });

        assertThat(policy.shouldPull(MINIO)).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(pauses).hasSize(1);
    }

    @Test
    void aTransientError_thatPersists_failsAfterTheRetry_andIsNotCached() {
        FailFastPullPolicy policy = policy(image -> false, failing(new IllegalStateException("HTTP 503 unavailable")));

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503").hasMessageNotContaining("docker login");
        assertThat(pulls).hasValue(2);

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThat(pulls).hasValue(4);
    }

    @Test
    void aStalledPull_isATimeout_withItsOwnMessage_theInterruptFlagCleared_andNotCached() {
        AtomicInteger calls = new AtomicInteger();
        FailFastPullPolicy policy = policy(image -> false, image -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new InterruptedException("no progress");
        });

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pgsty/minio:RELEASE.1")
                .hasMessageContaining("no progress")
                .hasMessageNotContaining("docker login")
                .hasMessageNotContaining("rate limit");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFailureForOneImage_doesNotBlockAnother() {
        DockerImageName postgres = DockerImageName.parse("postgres:16-alpine");
        FailFastPullPolicy policy = policy(image -> false, image -> {
            if (image.equals(MINIO)) {
                throw new IllegalStateException(RATE_LIMIT);
            }
        });

        assertThatThrownBy(() -> policy.shouldPull(MINIO)).isInstanceOf(IllegalStateException.class);
        assertThat(policy.shouldPull(postgres)).isFalse();
    }

    /** Against the real daemon: an image it holds is never sent to a registry. */
    @Test
    void anImageTheDaemonHolds_isNotPulled() throws Exception {
        List<Image> held = DockerClientFactory.lazyClient().listImagesCmd().exec();
        assertThat(held).as("a daemon with at least one image").isNotEmpty();
        var client = DockerClientFactory.lazyClient();
        String local = "excalibase-test-support/held";
        client.tagImageCmd(held.getFirst().getId(), local, "present").exec();
        try {
            // Were the daemon's answer ignored, this would go to Docker Hub, which has no such repository.
            assertThat(new FailFastPullPolicy().shouldPull(DockerImageName.parse(local + ":present"))).isFalse();
        } finally {
            client.removeImageCmd(local + ":present").exec();
        }
    }
}
