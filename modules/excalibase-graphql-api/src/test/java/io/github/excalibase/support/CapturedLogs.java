package io.github.excalibase.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A log capture that can be read while other threads are still logging. */
public class CapturedLogs extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        events.add(event);
    }

    /** The events so far, as a snapshot nobody else can change. */
    public List<ILoggingEvent> events() {
        return List.copyOf(events);
    }
}
