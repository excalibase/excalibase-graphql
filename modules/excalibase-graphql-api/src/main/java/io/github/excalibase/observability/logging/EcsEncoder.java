package io.github.excalibase.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

/**
 * One JSON object per line in the Elastic Common Schema, with the field names, nesting and order of Spring Boot's
 * structured logging, so collectors and queries built on the earlier lines keep working.
 */
public final class EcsEncoder extends EncoderBase<ILoggingEvent> {

    private static final JsonFactory JSON = new JsonFactory();
    private static final byte[] NEW_LINE = {'\n'};
    private static final String ECS_VERSION = "8.11";
    private static final long PID = ProcessHandle.current().pid();

    private String serviceName = "";
    private String serviceVersion = "";

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    @Override
    public void start() {
        if (serviceName.isEmpty() && serviceVersion.isEmpty()) {
            loadApplicationInfo();
        }
        super.start();
    }

    private void loadApplicationInfo() {
        try (var stream = EcsEncoder.class.getResourceAsStream("/app-info.properties")) {
            if (stream != null) {
                Properties info = new Properties();
                info.load(stream);
                serviceName = info.getProperty("name", "");
                serviceVersion = info.getProperty("version", "");
            }
        } catch (IOException unreadable) {
            addError("cannot read app-info.properties", unreadable);
        }
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try (JsonGenerator json = JSON.createGenerator(bytes)) {
            json.writeStartObject();
            json.writeStringField("@timestamp", DateTimeFormatter.ISO_INSTANT.format(event.getInstant()));
            writeLog(json, event);
            writeProcess(json, event);
            writeService(json);
            json.writeStringField("message", event.getFormattedMessage());
            writeMdc(json, event.getMDCPropertyMap());
            writeError(json, event.getThrowableProxy());
            json.writeObjectFieldStart("ecs");
            json.writeStringField("version", ECS_VERSION);
            json.writeEndObject();
            json.writeEndObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        bytes.writeBytes(NEW_LINE);
        return bytes.toByteArray();
    }

    private static void writeLog(JsonGenerator json, ILoggingEvent event) throws IOException {
        json.writeObjectFieldStart("log");
        json.writeStringField("level", event.getLevel().toString());
        json.writeStringField("logger", event.getLoggerName());
        json.writeEndObject();
    }

    private static void writeProcess(JsonGenerator json, ILoggingEvent event) throws IOException {
        json.writeObjectFieldStart("process");
        json.writeNumberField("pid", PID);
        json.writeObjectFieldStart("thread");
        json.writeStringField("name", event.getThreadName());
        json.writeEndObject();
        json.writeEndObject();
    }

    private void writeService(JsonGenerator json) throws IOException {
        json.writeObjectFieldStart("service");
        if (!serviceName.isEmpty()) {
            json.writeStringField("name", serviceName);
        }
        if (!serviceVersion.isEmpty()) {
            json.writeStringField("version", serviceVersion);
        }
        json.writeObjectFieldStart("node");
        json.writeEndObject();
        json.writeEndObject();
    }

    private static void writeMdc(JsonGenerator json, Map<String, String> mdc) throws IOException {
        if (mdc == null) {
            return;
        }
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            json.writeStringField(entry.getKey(), entry.getValue());
        }
    }

    private static void writeError(JsonGenerator json, IThrowableProxy thrown) throws IOException {
        if (thrown == null) {
            return;
        }
        json.writeObjectFieldStart("error");
        json.writeStringField("type", thrown.getClassName());
        json.writeStringField("message", thrown.getMessage());
        json.writeStringField("stack_trace", ThrowableProxyUtil.asString(thrown));
        json.writeEndObject();
    }
}
