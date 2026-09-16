package io.github.excalibase.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Supplies the provisioning token, read from a file that the platform mounts
 * (Kubernetes Secret / compose volume) and rotates in place without restarting
 * the process. Callers must therefore read through {@link #get()} on every use
 * instead of capturing the value once at construction.
 *
 * <p>Falls back to a literal configured token when no file path is set, which is
 * how standalone and dev deployments still work. The file is re-stat'ed at most
 * once every {@value #REFRESH_INTERVAL_MS} ms; a read failure keeps serving the
 * last known good value rather than dropping authentication. The token value is
 * never logged.
 */
public final class TokenFileSource {

    private static final Logger log = LoggerFactory.getLogger(TokenFileSource.class);
    private static final long REFRESH_INTERVAL_MS = 5_000L;

    private final Path path;
    private final String literalToken;
    private final LongSupplier clock;

    private record Snapshot(String token, long lastModifiedMs, long size, long checkedAtMs) {}

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public TokenFileSource(String filePath, String literalToken) {
        this(filePath, literalToken, System::currentTimeMillis);
    }

    /** Clock is injectable so the refresh throttle is testable without sleeping. */
    public TokenFileSource(String filePath, String literalToken, LongSupplier clock) {
        this.path = (filePath == null || filePath.isBlank()) ? null : Path.of(filePath.trim());
        this.literalToken = literalToken;
        this.clock = clock;
    }

    /** The token to authenticate with right now: current file contents, else the configured literal. */
    public String get() {
        if (path == null) {
            return literalToken;
        }
        long nowMs = clock.getAsLong();
        Snapshot current = snapshot.get();
        if (current != null && nowMs - current.checkedAtMs() < REFRESH_INTERVAL_MS) {
            return current.token();
        }
        return refresh(current, nowMs);
    }

    private synchronized String refresh(Snapshot seen, long nowMs) {
        Snapshot current = snapshot.get();
        if (current != seen && current != null && nowMs - current.checkedAtMs() < REFRESH_INTERVAL_MS) {
            return current.token();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            long lastModifiedMs = attributes.lastModifiedTime().toMillis();
            long size = attributes.size();
            if (current != null && current.lastModifiedMs() == lastModifiedMs && current.size() == size) {
                snapshot.set(new Snapshot(current.token(), lastModifiedMs, size, nowMs));
                return current.token();
            }
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                log.warn("token_file_empty path={}", path);
                return keepLastKnownGood(current, nowMs);
            }
            snapshot.set(new Snapshot(token, lastModifiedMs, size, nowMs));
            return token;
        } catch (IOException e) {
            log.warn("token_file_unreadable path={} reason={}", path, e.getClass().getSimpleName());
            return keepLastKnownGood(current, nowMs);
        }
    }

    /** A rotation window or a transient failure must not drop the token mid-flight. */
    private String keepLastKnownGood(Snapshot current, long nowMs) {
        if (current == null) {
            return literalToken;
        }
        snapshot.set(new Snapshot(current.token(), current.lastModifiedMs(), current.size(), nowMs));
        return current.token();
    }
}
