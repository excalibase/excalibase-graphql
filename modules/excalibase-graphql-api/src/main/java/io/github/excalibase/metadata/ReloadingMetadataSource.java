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
 * document. A source only supplies a {@link Loader}.
 */
public final class ReloadingMetadataSource implements MetadataSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReloadingMetadataSource.class);
    public static final String VERSION_METRIC = "excalibase.metadata.version";
    public static final String REFUSED_METRIC = "excalibase.metadata.refused";

    private final String name;
    private final Loader loader;
    private final Reader parser;
    private final MetadataWriter writer;
    private final Duration interval;
    private final Duration settle;
    private final MeterRegistry meters;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicReference<Refusal> refusal = new AtomicReference<>();
    private final AtomicLong counter = new AtomicLong();
    private final List<ObjLongConsumer<String>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService poller;
    /** The last content judged, good or bad; the same content is not judged, logged or counted again. */
    private String lastJudgedVersion;
    /** A changed document seen once; it is judged only when the next poll sees the same content. */
    private String pendingVersion;
    /** Consecutive polls that saw a changed document they could not yet believe. */
    private int unsettledPolls;
    private String lastReadError;

    /** What a source reads: the text and the version it calls it. */
    public record Raw(String text, String version) {
    }

    /** The one thing a source implements: read the current document. */
    @FunctionalInterface
    public interface Loader {
        /** @throws IOException the document cannot be read right now */
        Raw load() throws IOException;
    }

    /** Turns text into a validated document; {@link MetadataDocumentParser#load} is the engine's. */
    @FunctionalInterface
    public interface Reader {
        MetadataDocument parse(String text, long version);
    }

    /** @param category what the status reports; the full reason goes to the log only */
    private record Refusal(String category, Instant at) {
    }

    static final int UNSETTLED_WARNING_POLLS = 6;
    public static final Duration DEFAULT_SETTLE = Duration.ofMillis(200);

    public ReloadingMetadataSource(String name, Loader loader, Reader parser,
                                   BooleanSupplier writable, Duration interval, MeterRegistry meters) {
        this(name, loader, parser, MetadataWriter.readOnly(writable), interval, DEFAULT_SETTLE, meters);
    }

    public ReloadingMetadataSource(String name, Loader loader, Reader parser,
                                   MetadataWriter writer, Duration interval, MeterRegistry meters) {
        this(name, loader, parser, writer, interval, DEFAULT_SETTLE, meters);
    }

    public ReloadingMetadataSource(String name, Loader loader, Reader parser,
                                   BooleanSupplier writable, Duration interval, Duration settle,
                                   MeterRegistry meters) {
        this(name, loader, parser, MetadataWriter.readOnly(writable), interval, settle, meters);
    }

    /**
     * @param settle how long {@link #start()} waits before its second read when the first one found a document,
     *               so a file caught mid-write is not served
     */
    public ReloadingMetadataSource(String name, Loader loader, Reader parser,
                                   MetadataWriter writer, Duration interval, Duration settle,
                                   MeterRegistry meters) {
        this.settle = Objects.requireNonNull(settle, "settle");
        this.name = Objects.requireNonNull(name, "name");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
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
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        refresh();
    }

    private void pollSafely() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.error("metadata_refresh_failed source={}", name, e);
        }
    }

    /**
     * One poll. A document that differs from what is served is not believed on first sight: a writer that has
     * not finished can leave a cut-off line that parses as a wider rule. It is judged only once a following poll
     * reads the very same content.
     */
    public synchronized void refresh() {
        Raw raw;
        try {
            raw = loader.load();
        } catch (IOException e) {
            readFailed(e);
            return;
        }
        lastReadError = null;
        Snapshot held = current.get();
        if (held != null && held.sourceVersion().equals(raw.version())) {
            lastJudgedVersion = raw.version();
            clearPending();
            refusal.set(null);
            return;
        }
        if (raw.version().equals(lastJudgedVersion)) {
            clearPending();
            return;
        }
        if (!raw.version().equals(pendingVersion)) {
            pendingVersion = raw.version();
            warnWhenNotSettling();
            return;
        }
        judge(raw);
    }

    private void judge(Raw raw) {
        try {
            swap(parser.parse(raw.text(), counter.get() + 1), raw);
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
        clearPending();
    }

    private void clearPending() {
        pendingVersion = null;
        unsettledPolls = 0;
    }

    /** A document that keeps changing is never served; say so once, or a starved replica stays invisible. */
    private void warnWhenNotSettling() {
        if (++unsettledPolls == UNSETTLED_WARNING_POLLS) {
            log.warn("metadata_change_not_settling source={} polls={} serving={}", name, unsettledPolls,
                    current.get() == null ? "nothing (every read is refused)" : "version " + counter.get());
        }
    }

    private void readFailed(IOException error) {
        lastJudgedVersion = null;
        clearPending();
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

    private void swap(MetadataDocument document, Raw raw) {
        PermissionSet permissions = document.permissions();
        boolean replacing = current.get() != null;
        long version = counter.incrementAndGet();
        current.set(new Snapshot(permissions, version, raw.version(), Instant.now(), document.verifier(), raw.text()));
        refusal.set(null);
        log.info("metadata_swapped source={} version={} source_version={}", name, version, raw.version());
        if (replacing) {
            announce(permissions.projectId(), version);
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
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new PermissionsUnavailableException("no valid metadata has been loaded from the " + name
                    + " source, so every read is refused");
        }
        if (!snapshot.permissions().projectId().equals(projectId)) {
            throw new UnknownProjectException(projectId);
        }
        return snapshot.permissions();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<Snapshot> current() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public boolean writable() {
        return writer.writable();
    }

    @Override
    public SaveOutcome save(String text, String expectedVersion) {
        if (!writer.writable()) {
            return new SaveOutcome.ReadOnly();
        }
        try {
            parser.parse(text, counter.get() + 1);
        } catch (MetadataDocumentException e) {
            return new SaveOutcome.Invalid(List.of(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("metadata_save_not_validated source={}", name, e);
            return new SaveOutcome.Failed("validation_failed");
        }
        try {
            return reloaded(writer.write(text, expectedVersion));
        } catch (WriteConflictException e) {
            return new SaveOutcome.Conflict(e.currentVersion());
        } catch (IOException | RuntimeException e) {
            log.error("metadata_save_failed source={} serving={} error={}", name, "last good version " + counter.get(),
                    e.getMessage());
            return new SaveOutcome.Failed("write_failed");
        }
    }

    /**
     * Takes the saved document up through the ordinary reload: parse, validate, swap. A save is complete when it is
     * written, so the settle read is not waited for; a second read stands in for the poll that would see it again.
     */
    private synchronized SaveOutcome reloaded(String writtenVersion) {
        refresh();
        Snapshot served = current.get();
        if (served == null || !served.sourceVersion().equals(writtenVersion)) {
            refresh();
            served = current.get();
        }
        if (served != null && served.sourceVersion().equals(writtenVersion)) {
            return new SaveOutcome.Saved(served.version(), writtenVersion);
        }
        log.error("metadata_saved_not_applied source={} serving={}", name,
                served == null ? "nothing (every read is refused)" : "last good version " + counter.get());
        return new SaveOutcome.NotApplied("reload_refused");
    }

    @Override
    public Status status() {
        Snapshot snapshot = current.get();
        Refusal last = refusal.get();
        return new Status(name, snapshot == null ? 0 : snapshot.version(),
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
