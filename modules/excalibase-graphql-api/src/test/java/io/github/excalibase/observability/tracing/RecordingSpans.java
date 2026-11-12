package io.github.excalibase.observability.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An exporter that keeps what it is given, so a test reads the spans the engine ended. */
public final class RecordingSpans implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> spans() {
        return List.copyOf(spans);
    }

    public void clear() {
        spans.clear();
    }
}
