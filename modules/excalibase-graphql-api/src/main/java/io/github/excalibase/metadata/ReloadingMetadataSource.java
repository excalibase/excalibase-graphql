package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.security.UnknownProjectException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.ObjLongConsumer;

/**
 * The reload loop every metadata source shares: load, validate, then swap one reference. Whatever goes wrong
 * (the source cannot be read, the document is refused) the last good version keeps being served and the
 * reason is logged and reported; until a first good load every read is refused, never answered with an empty
 * document. A source only supplies a {@link MetadataLoader}.
 */
public final class ReloadingMetadataSource implements MetadataSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReloadingMetadataSource.class);
    public static final String VERSION_METRIC = "excalibase.metadata.version";
    public static final String REFUSED_METRIC = "excalibase.metadata.refused";

    private final String name;
    private final MetadataLoader loader;
    private final MetadataDocumentParser parser;
    private final BooleanSupplier writable;
    private final Duration interval;
    private final Duration settle;
    private final MeterRegistry meters;
    private final AtomicReference<MetadataSnapshot> current = new AtomicReference<>();
    private final AtomicReference<Refusal> refusal = new AtomicReference<>();
    private final AtomicLong counter = new AtomicLong();
    private final List<ObjLongConsumer<String>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService poller;
    /** The last content judged, good or bad; the same content is not judged, logged or counted again. */
    private String lastJudgedVersion;
    /** A changed document seen once; it is judged only when the next poll sees the same content. */
    private String pendingVersion;
    private String lastReadError;

    /** @param category what the status reports; the full reason goes to the log only */
    private record Refusal(String category, Instant at) {
    }

    public static final Duration DEFAULT_SETTLE = Duration.ofMillis(200);

    public ReloadingMetadataSource(String name, MetadataLoader loader, MetadataDocumentParser parser,
                                   BooleanSupplier writable, Duration interval, MeterRegistry meters) {
        this(name, loader, parser, writable, interval, DEFAULT_SETTLE, meters);
    }

    /**
     * @param settle how long {@link #start()} waits before its second read when the first one found a document,
     *               so a file caught mid-write is not served
     */
    public ReloadingMetadataSource(String name, MetadataLoader loader, MetadataDocumentParser parser,
                                   BooleanSupplier writable, Duration interval, Duration settle,
                                   MeterRegistry meters) {
        this.settle = Objects.requireNonNull(settle, "settle");
        this.name = Objects.requireNonNull(name, "name");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writable = Objects.requireNonNull(writable, "writable");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /** Loads once, then polls. A failed first load is not fatal: reads are refused until a later one works. */
    public synchronized void start() {
        Gauge.builder(VERSION_METRIC, counter, AtomicLong::get).tag("source", name)
                .strongReference(true).register(meters);
        refresh();
        if (current.get() == null && pendingVersion != null) {
            settleThenRead();
        }
        if (poller == null) {
            poller = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "metadata-" + name + "-poll");
                thread.setDaemon(true);
                return thread;
            });
            poller.scheduleWithFixedDelay(this::pollSafely, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void settleThenRead() {
        try {
            Thread.sleep(settle.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        refresh();
    }

    private void pollSafely() {
        try {
            refresh();
        } catch (RuntimeException | Error e) {
            log.error("metadata_refresh_failed source={}", name, e);
        }
    }

    /**
     * One poll. A document that differs from what is served is not believed on first sight: a writer that has
     * not finished can leave a cut-off line that parses as a wider rule. It is judged only once a following poll
     * reads the very same content.
     */
    public synchronized void refresh() {
        RawMetadata raw;
        try {
            raw = loader.load();
        } catch (IOException e) {
            readFailed(e);
            return;
        }
        lastReadError = null;
        MetadataSnapshot held = current.get();
        if (held != null && held.sourceVersion().equals(raw.version())) {
            lastJudgedVersion = raw.version();
            pendingVersion = null;
            refusal.set(null);
            return;
        }
        if (raw.version().equals(lastJudgedVersion)) {
            return;
        }
        if (!raw.version().equals(pendingVersion)) {
            pendingVersion = raw.version();
            return;
        }
        judge(raw);
    }

    private void judge(RawMetadata raw) {
        try {
            swap(parser.parse(raw.text(), counter.get() + 1), raw.version());
            judged(raw.version());
        } catch (MetadataDocumentException e) {
            judged(raw.version());
            refuse("invalid_document", raw.version(), e.getMessage());
        } catch (RuntimeException e) {
            // Not judged: the same content is tried again on the next poll.
            refuse("unexpected_error", raw.version(), e.toString());
        }
    }

    private void judged(String version) {
        lastJudgedVersion = version;
        pendingVersion = null;
    }

    private void readFailed(IOException error) {
        lastJudgedVersion = null;
        pendingVersion = null;
        String reason = "cannot read the document: " + error;
        if (!reason.equals(lastReadError)) {
            lastReadError = reason;
            refuse("unreadable", "unreadable", reason);
        }
    }

    private void refuse(String category, String version, String reason) {
        refusal.set(new Refusal(category, Instant.now()));
        Counter.builder(REFUSED_METRIC).tag("source", name).register(meters).increment();
        log.error("metadata_refused source={} version={} serving={} error={}", name, version,
                current.get() == null ? "nothing (every read is refused)" : "last good version " + counter.get(), reason);
    }

    private void swap(MetadataDocument document, String sourceVersion) {
        boolean replacing = current.get() != null;
        long version = counter.incrementAndGet();
        current.set(new MetadataSnapshot(document, version, sourceVersion, Instant.now()));
        refusal.set(null);
        log.info("metadata_swapped source={} version={} source_version={}", name, version, sourceVersion);
        if (replacing) {
            announce(document.projectId(), version);
        }
    }

    private void announce(String projectId, long version) {
        for (ObjLongConsumer<String> listener : listeners) {
            try {
                listener.accept(projectId, version);
            } catch (RuntimeException e) {
                log.error("metadata_change_listener_failed source={} project={}", name, projectId, e);
            }
        }
    }

    @Override
    public PermissionSet permissionsFor(String projectId) {
        MetadataSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new PermissionsUnavailableException("no valid metadata has been loaded from the " + name
                    + " source, so every read is refused");
        }
        if (!snapshot.document().projectId().equals(projectId)) {
            throw new UnknownProjectException(projectId);
        }
        return snapshot.document().permissions();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<MetadataSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public boolean writable() {
        return writable.getAsBoolean();
    }

    @Override
    public MetadataStatus status() {
        MetadataSnapshot snapshot = current.get();
        Refusal last = refusal.get();
        return new MetadataStatus(name, snapshot == null ? 0 : snapshot.version(),
                snapshot == null ? null : snapshot.sourceVersion(),
                last == null ? null : last.category(), last == null ? null : last.at());
    }

    @Override
    public void evict(String projectId) {
        // the document is held whole, there is nothing per project to drop
    }

    @Override
    public void onChange(ObjLongConsumer<String> listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void close() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }
}
