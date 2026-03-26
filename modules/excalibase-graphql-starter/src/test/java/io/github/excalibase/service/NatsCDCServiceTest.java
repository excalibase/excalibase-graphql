package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.DatabaseType;
import io.github.excalibase.model.CDCEvent;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.nats.client.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NatsCDCServiceTest {

    private AppConfig appConfig;
    private ObjectMapper objectMapper;
    private ServiceLookup serviceLookup;
    private FullSchemaService fullSchemaService;
    private IDatabaseSchemaReflector reflector;

    private NatsCDCService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serviceLookup = mock(ServiceLookup.class);
        fullSchemaService = mock(FullSchemaService.class);
        reflector = mock(IDatabaseSchemaReflector.class);
        appConfig = buildAppConfig(true);

        when(serviceLookup.forBean(IDatabaseSchemaReflector.class, "Postgres"))
                .thenReturn(reflector);

        service = new NatsCDCService(appConfig, objectMapper, serviceLookup, fullSchemaService);
    }

    // ── isEnabled / isRunning ────────────────────────────────────────────────

    @Test
    void isEnabled_whenNatsEnabled_returnsTrue() {
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_whenNatsDisabled_returnsFalse() {
        NatsCDCService disabledService = new NatsCDCService(
                buildAppConfig(false), objectMapper, serviceLookup, fullSchemaService);

        assertThat(disabledService.isEnabled()).isFalse();
    }

    @Test
    void isRunning_beforeStart_returnsFalse() {
        // @PostConstruct not called in unit test, so not running yet
        assertThat(service.isRunning()).isFalse();
    }

    // ── DML routing ──────────────────────────────────────────────────────────

    @Test
    void handleNatsMessage_insertEvent_routesToTableSink() throws Exception {
        String json = """
                {"type":"INSERT","schema":"public","table":"products",
                 "data":"{\\"product_id\\":1,\\"name\\":\\"Widget\\"}",
                 "rawMessage":"INSERT","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        Flux<CDCEvent> stream = service.getTableEventStream("products");

        StepVerifier.create(stream.take(1))
                .then(() -> invokeHandleNatsMessage(service, json))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo(CDCEvent.Type.INSERT);
                    assertThat(event.getTable()).isEqualTo("products");
                    assertThat(event.getSchema()).isEqualTo("public");
                })
                .verifyComplete();
    }

    @Test
    void handleNatsMessage_updateEvent_routesToTableSink() throws Exception {
        String json = """
                {"type":"UPDATE","schema":"public","table":"orders",
                 "data":"{\\"order_id\\":42}",
                 "rawMessage":"UPDATE","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        Flux<CDCEvent> stream = service.getTableEventStream("orders");

        StepVerifier.create(stream.take(1))
                .then(() -> invokeHandleNatsMessage(service, json))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo(CDCEvent.Type.UPDATE);
                    assertThat(event.getTable()).isEqualTo("orders");
                })
                .verifyComplete();
    }

    @Test
    void handleNatsMessage_deleteEvent_routesToTableSink() throws Exception {
        String json = """
                {"type":"DELETE","schema":"public","table":"orders",
                 "data":"{\\"order_id\\":99}",
                 "rawMessage":"DELETE","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        Flux<CDCEvent> stream = service.getTableEventStream("orders");

        StepVerifier.create(stream.take(1))
                .then(() -> invokeHandleNatsMessage(service, json))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo(CDCEvent.Type.DELETE);
                    assertThat(event.getTable()).isEqualTo("orders");
                })
                .verifyComplete();
    }

    @Test
    void handleNatsMessage_eventForDifferentTable_doesNotEmitOnUnrelatedStream() {
        String json = """
                {"type":"INSERT","schema":"public","table":"products",
                 "data":"{}","rawMessage":"INSERT","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        Flux<CDCEvent> ordersStream = service.getTableEventStream("orders");

        // Subscribe to orders stream but send an event for products
        AtomicReference<CDCEvent> received = new AtomicReference<>();
        ordersStream.subscribe(received::set);

        invokeHandleNatsMessage(service, json);

        assertThat(received.get()).isNull();
    }

    // ── DDL cache invalidation ───────────────────────────────────────────────

    @Test
    void handleNatsMessage_ddlEventWithSchema_invalidatesSchemaCache() {
        String json = """
                {"type":"DDL","schema":"public","table":null,
                 "data":"ALTER TABLE products ADD COLUMN price numeric",
                 "rawMessage":"DDL","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        invokeHandleNatsMessage(service, json);

        verify(reflector).clearCache("public");
        verify(fullSchemaService).clearCache();
    }

    @Test
    void handleNatsMessage_ddlEventWithNullSchema_callsGlobalClearCache() {
        String json = """
                {"type":"DDL","schema":null,"table":null,
                 "data":"DROP TABLE temp",
                 "rawMessage":"DDL","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        invokeHandleNatsMessage(service, json);

        verify(reflector).clearCache();
        verify(fullSchemaService).clearCache();
    }

    @Test
    void handleNatsMessage_ddlEvent_doesNotEmitToDmlSinks() {
        String ddlJson = """
                {"type":"DDL","schema":"public","table":null,
                 "data":"ALTER TABLE products ADD COLUMN price numeric",
                 "rawMessage":"DDL","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        AtomicReference<CDCEvent> received = new AtomicReference<>();
        service.getTableEventStream("products").subscribe(received::set);

        invokeHandleNatsMessage(service, ddlJson);

        assertThat(received.get()).isNull();
    }

    // ── Resilience ───────────────────────────────────────────────────────────

    @Test
    void handleNatsMessage_malformedJson_acksMessageAndDoesNotThrow() {
        Message mockMessage = mock(Message.class);
        when(mockMessage.getData()).thenReturn("not-valid-json{{{".getBytes());
        when(mockMessage.getSubject()).thenReturn("cdc.public.products");

        invokeHandleNatsMessageDirect(service, mockMessage);

        // Should ack even on parse failure to avoid redelivery loop
        verify(mockMessage, atLeastOnce()).ack();
    }

    @Test
    void handleNatsMessage_heartbeatEvent_doesNotRouteToTableSink() {
        String json = """
                {"type":"HEARTBEAT","schema":null,"table":null,
                 "data":null,"rawMessage":"HEARTBEAT","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        AtomicReference<CDCEvent> received = new AtomicReference<>();
        service.getTableEventStream("any_table").subscribe(received::set);

        invokeHandleNatsMessage(service, json);

        assertThat(received.get()).isNull();
    }

    // ── getTableEventStream ──────────────────────────────────────────────────

    @Test
    void getTableEventStream_returnsSameFluxForSameTable() {
        Flux<CDCEvent> stream1 = service.getTableEventStream("products");
        Flux<CDCEvent> stream2 = service.getTableEventStream("products");

        // Both streams should emit the same events (same underlying sink)
        String json = """
                {"type":"INSERT","schema":"public","table":"products",
                 "data":"{}","rawMessage":"INSERT","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        AtomicReference<CDCEvent> event1 = new AtomicReference<>();
        AtomicReference<CDCEvent> event2 = new AtomicReference<>();

        stream1.subscribe(event1::set);
        stream2.subscribe(event2::set);

        invokeHandleNatsMessage(service, json);

        assertThat(event1.get()).isNotNull();
        assertThat(event2.get()).isNotNull();
        assertThat(event1.get().getType()).isEqualTo(CDCEvent.Type.INSERT);
        assertThat(event2.get().getType()).isEqualTo(CDCEvent.Type.INSERT);
    }

    @Test
    void getTableEventStream_multipleTablesAreIsolated() {
        String productsJson = """
                {"type":"INSERT","schema":"public","table":"products",
                 "data":"{}","rawMessage":"INSERT","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;
        String ordersJson = """
                {"type":"DELETE","schema":"public","table":"orders",
                 "data":"{}","rawMessage":"DELETE","lsn":null,"timestamp":0,"sourceTimestamp":0}
                """;

        AtomicReference<CDCEvent> productsEvent = new AtomicReference<>();
        AtomicReference<CDCEvent> ordersEvent = new AtomicReference<>();

        service.getTableEventStream("products").subscribe(productsEvent::set);
        service.getTableEventStream("orders").subscribe(ordersEvent::set);

        invokeHandleNatsMessage(service, productsJson);
        invokeHandleNatsMessage(service, ordersJson);

        assertThat(productsEvent.get().getType()).isEqualTo(CDCEvent.Type.INSERT);
        assertThat(productsEvent.get().getTable()).isEqualTo("products");
        assertThat(ordersEvent.get().getType()).isEqualTo(CDCEvent.Type.DELETE);
        assertThat(ordersEvent.get().getTable()).isEqualTo("orders");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void invokeHandleNatsMessage(NatsCDCService svc, String json) {
        Message mockMessage = mock(Message.class);
        when(mockMessage.getData()).thenReturn(json.getBytes());
        when(mockMessage.getSubject()).thenReturn("cdc.public.test");
        invokeHandleNatsMessageDirect(svc, mockMessage);
    }

    private void invokeHandleNatsMessageDirect(NatsCDCService svc, Message message) {
        try {
            Method method = NatsCDCService.class.getDeclaredMethod("handleNatsMessage", Message.class);
            method.setAccessible(true);
            method.invoke(svc, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke handleNatsMessage", e);
        }
    }

    private AppConfig buildAppConfig(boolean natsEnabled) {
        AppConfig config = new AppConfig();
        config.setDatabaseType(DatabaseType.POSTGRES);

        AppConfig.CacheConfig cache = new AppConfig.CacheConfig();
        cache.setSchemaTtlMinutes(30);
        config.setCache(cache);

        AppConfig.NatsConfig nats = new AppConfig.NatsConfig();
        nats.setEnabled(natsEnabled);
        nats.setUrl("nats://localhost:4222");
        nats.setStreamName("CDC");
        nats.setSubjectPrefix("cdc");
        config.setNats(nats);

        return config;
    }
}