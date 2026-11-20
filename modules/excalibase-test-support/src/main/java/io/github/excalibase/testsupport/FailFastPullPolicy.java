package io.github.excalibase.testsupport;

import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.images.TimeLimitedLoggedPullImageResultCallback;
import org.testcontainers.utility.DockerImageName;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Pulls a missing test image once, and fails at once when the registry refuses (a Docker Hub rate limit
 * is the usual cause). Left to the library, a refused pull is retried for minutes per container, and a
 * handful of test classes sharing one image then spend the job's whole time limit (EXC-792).
 * Set as {@code pull.policy} in this module's {@code testcontainers.properties}, which every module
 * with container tests picks up through its test dependency on this module.
 *
 * <p>Only a clear refusal is remembered (rate limit, 401, 403, 404, denied, unknown manifest): asking the
 * registry again cannot help. A transient error (a reset connection, a 5xx) gets one retry after a short
 * pause and is not remembered. A pull that stops making progress is a timeout, reported as one.
 */
public final class FailFastPullPolicy implements ImagePullPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(FailFastPullPolicy.class);
    private static final long RETRY_PAUSE_MILLIS = 2_000;
    private static final Pattern RATE_LIMIT = Pattern.compile("toomanyrequests|rate limit");
    private static final Pattern REFUSAL = Pattern.compile(
            "toomanyrequests|rate limit|unauthorized|denied|forbidden|manifest unknown|not found|\\b(401|403|404)\\b");

    /** Pulls an image or throws; an interrupt means the pull stopped making progress. */
    @FunctionalInterface
    interface Puller {
        void pull(DockerImageName image) throws InterruptedException;
    }

    /** Whether the daemon already holds an image. */
    @FunctionalInterface
    interface Presence {
        boolean isPresent(DockerImageName image);
    }

    private final Presence presence;
    private final Puller puller;
    private final LongConsumer pause;
    private final Map<String, IllegalStateException> refused = new ConcurrentHashMap<>();

    public FailFastPullPolicy() {
        this(FailFastPullPolicy::heldByDaemon, FailFastPullPolicy::pullFromDaemon, FailFastPullPolicy::sleep);
    }

    FailFastPullPolicy(Presence presence, Puller puller, LongConsumer pause) {
        this.presence = presence;
        this.puller = puller;
        this.pause = pause;
    }

    @Override
    public boolean shouldPull(DockerImageName image) {
        String name = image.asCanonicalNameString();
        IllegalStateException earlier = refused.get(name);
        if (earlier != null) {
            throw new IllegalStateException(earlier.getMessage(), earlier);
        }
        if (!presence.isPresent(image)) {
            pullWithOneRetry(name, image);
        }
        return false;
    }

    private void pullWithOneRetry(String name, DockerImageName image) {
        try {
            attempt(name, image);
        } catch (TransientPullFailure first) {
            LOG.warn("Pulling test image {} failed ({}); retrying once", name, first.getMessage());
            pause.accept(RETRY_PAUSE_MILLIS);
            try {
                attempt(name, image);
            } catch (TransientPullFailure second) {
                throw new IllegalStateException("Test image " + name + " could not be pulled: " + second.getMessage()
                        + " (retried once).", second);
            }
        }
    }

    private void attempt(String name, DockerImageName image) {
        try {
            puller.pull(image);
        } catch (InterruptedException e) {
            Thread.interrupted();
            throw new IllegalStateException("Pulling test image " + name + " stalled: no progress, so the pull was "
                    + "abandoned. This is a timeout, not a registry refusal; run the job again.", e);
        } catch (RuntimeException e) {
            String message = messages(e);
            if (!REFUSAL.matcher(message.toLowerCase(Locale.ROOT)).find()) {
                throw new TransientPullFailure(e.getMessage(), e);
            }
            IllegalStateException failure = new IllegalStateException("Test image " + name + " could not be pulled: "
                    + e.getMessage() + ". Not retrying." + (RATE_LIMIT.matcher(message.toLowerCase(Locale.ROOT)).find()
                    ? " This is Docker Hub's rate limit: run `docker login` on this host (CI logs in with "
                    + "DOCKERHUB_USERNAME and DOCKERHUB_TOKEN) or pre-pull the image." : ""), e);
            refused.put(name, failure);
            throw failure;
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            all.append(cause.getMessage()).append(' ');
        }
        return all.toString();
    }

    /** A pull error that asking again may cure; never remembered. */
    private static final class TransientPullFailure extends RuntimeException {
        TransientPullFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static boolean heldByDaemon(DockerImageName image) {
        try {
            DockerClientFactory.lazyClient().inspectImageCmd(image.asCanonicalNameString()).exec();
            return true;
        } catch (NotFoundException _) {
            return false;
        }
    }

    private static void pullFromDaemon(DockerImageName image) throws InterruptedException {
        LOG.info("Pulling test image {}", image.asCanonicalNameString());
        PullImageCmd command = DockerClientFactory.lazyClient().pullImageCmd(image.getUnversionedPart())
                .withTag(image.getVersionPart());
        try {
            command.exec(new TimeLimitedLoggedPullImageResultCallback(LOG)).awaitCompletion();
        } catch (NotFoundException | DockerClientException e) {
            // As the library does: an image published for one platform only is asked for as linux/amd64.
            command.withPlatform("linux/amd64").exec(new TimeLimitedLoggedPullImageResultCallback(LOG))
                    .awaitCompletion();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry a pull", e);
        }
    }
}
