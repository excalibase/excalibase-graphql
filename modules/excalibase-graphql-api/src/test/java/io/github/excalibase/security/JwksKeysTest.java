package io.github.excalibase.security;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** EXC-723: the auth service's keys are refreshed without blocking requests, kept when it fails, and re-read on a new key id. */
class JwksKeysTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration MIN_RETRY = Duration.ofSeconds(10);

    private static JwksKeys.Key key(String id) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new JwksKeys.Key(id, (ECPublicKey) generator.generateKeyPair().getPublic());
    }

    @Test
    void aFailedRefreshKeepsServingTheKeysHeld() throws Exception {
        long[] now = {0};
        JwksKeys.Key held = key("a");
        JwksKeys keys = new JwksKeys(() -> {
            throw new java.io.IOException("auth is down");
        }, TTL, MIN_RETRY, () -> now[0], List.of(held));

        now[0] += TTL.toMillis() + 1;

        assertThat(keys.keysFor("a")).containsExactly(held);
    }

    @Test
    void aHangingRefreshDoesNotBlockOtherRequests() throws Exception {
        long[] now = {0};
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JwksKeys.Key held = key("a");
        JwksKeys keys = new JwksKeys(() -> {
            fetching.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of(held);
        }, TTL, MIN_RETRY, () -> now[0], List.of(held));
        now[0] += TTL.toMillis() + 1;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> refresher = pool.submit(() -> keys.keysFor("a"));
            assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<JwksKeys.Key>> other = pool.submit(() -> keys.keysFor("a"));

            assertThat(other.get(2, TimeUnit.SECONDS)).containsExactly(held);
            release.countDown();
            refresher.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anUnknownKeyIdRefetchesOnceAndPicksUpTheRotatedKey() throws Exception {
        long[] now = {0};
        JwksKeys.Key old = key("old");
        JwksKeys.Key rotated = key("new");
        AtomicInteger fetches = new AtomicInteger();
        JwksKeys keys = new JwksKeys(() -> {
            fetches.incrementAndGet();
            return List.of(old, rotated);
        }, TTL, MIN_RETRY, () -> now[0], List.of(old));
        now[0] += MIN_RETRY.toMillis() + 1;

        assertThat(keys.keysFor("new")).containsExactly(rotated);
        assertThat(keys.keysFor("never-seen")).containsExactly(old, rotated);
        assertThat(keys.keysFor("another")).containsExactly(old, rotated);

        assertThat(fetches).as("a stream of unknown key ids reaches the auth service once per retry window").hasValue(1);
    }

    @Test
    void aTokenWithoutAKeyIdIsVerifiedAgainstEveryKey() throws Exception {
        JwksKeys.Key first = key("a");
        JwksKeys.Key second = key("b");
        JwksKeys keys = new JwksKeys(() -> List.of(first, second), TTL, MIN_RETRY, () -> 0L, List.of(first, second));

        assertThat(keys.keysFor(null)).containsExactly(first, second);
    }

    @Test
    void anAuthServiceThatNeverAnswersFailsStartupWithinSeconds() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            String url = "http://localhost:" + silent.getLocalPort() + "/.well-known/jwks.json";

            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> new JwtService(url, 30)).isInstanceOf(IllegalStateException.class));
        }
    }
}
