package io.github.excalibase.support;

import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.images.TimeLimitedLoggedPullImageResultCallback;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls a missing test image once, and fails at once when the registry refuses (a Docker Hub rate limit
 * is the usual cause). Left to the library, a refused pull is retried for minutes per container, and a
 * handful of test classes sharing one image then spend the job's whole time limit (EXC-792). Set as
 * {@code pull.policy} in {@code testcontainers.properties}, so every Testcontainers image goes through it.
 */
public final class FailFastPullPolicy implements ImagePullPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(FailFastPullPolicy.class);

    /** Pulls an image or throws. */
    @FunctionalInterface
    interface Puller {
        void pull(DockerImageName image);
    }

    /** Whether the daemon already holds an image. */
    @FunctionalInterface
    interface Presence {
        boolean isPresent(DockerImageName image);
    }

    private final Presence presence;
    private final Puller puller;
    private final Map<String, IllegalStateException> refused = new ConcurrentHashMap<>();

    public FailFastPullPolicy() {
        this(FailFastPullPolicy::heldByDaemon, FailFastPullPolicy::pullFromDaemon);
    }

    FailFastPullPolicy(Presence presence, Puller puller) {
        this.presence = presence;
        this.puller = puller;
    }

    @Override
    public boolean shouldPull(DockerImageName image) {
        String name = image.asCanonicalNameString();
        IllegalStateException earlier = refused.get(name);
        if (earlier != null) {
            throw new IllegalStateException(earlier.getMessage(), earlier);
        }
        if (!presence.isPresent(image)) {
            pullOnce(name, image);
        }
        return false;
    }

    private void pullOnce(String name, DockerImageName image) {
        try {
            puller.pull(image);
        } catch (RuntimeException e) {
            IllegalStateException failure = new IllegalStateException("Test image " + name + " could not be pulled: "
                    + e.getMessage() + ". Not retrying. If this is a Docker Hub rate limit, run `docker login` on this "
                    + "host (CI logs in with DOCKERHUB_USERNAME and DOCKERHUB_TOKEN) or pre-pull the image.", e);
            refused.put(name, failure);
            throw failure;
        }
    }

    private static boolean heldByDaemon(DockerImageName image) {
        try {
            DockerClientFactory.lazyClient().inspectImageCmd(image.asCanonicalNameString()).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private static void pullFromDaemon(DockerImageName image) {
        LOG.info("Pulling test image {}", image.asCanonicalNameString());
        try {
            DockerClientFactory.lazyClient().pullImageCmd(image.getUnversionedPart())
                    .withTag(image.getVersionPart())
                    .exec(new TimeLimitedLoggedPullImageResultCallback(LOG))
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pulling", e);
        }
    }
}
