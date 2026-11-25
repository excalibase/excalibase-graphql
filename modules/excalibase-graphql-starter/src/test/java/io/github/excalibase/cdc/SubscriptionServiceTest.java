package io.github.excalibase.cdc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SubscriptionServiceTest {

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService();
    }

    @Test
    @DisplayName("publish(null, event) with null tenant routes to null-tenant subscriber (backward compat)")
    void publishWithoutTenant_routesToNullTenantSubscriber() {
        List<CDCEvent> received = new ArrayList<>();
        Disposable disposable = service.subscribe(null, "public.customer")
                .subscribe(received::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "public.customer"));
            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1000L);
            service.publish(null, event);

            awaitTrue(() -> received.size() == 1);
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().table()).isEqualTo("customer");
        } finally {
            disposable.dispose();
        }
    }

    @Test
    @DisplayName("publish(tenantA, event) delivers ONLY to tenantA subscriber, not tenantB")
    void publish_tenantScoped_isolatesTenants() {
        List<CDCEvent> aReceived = new ArrayList<>();
        List<CDCEvent> bReceived = new ArrayList<>();
        Disposable a = service.subscribe("tenant-a", "public.customer").subscribe(aReceived::add);
        Disposable b = service.subscribe("tenant-b", "public.customer").subscribe(bReceived::add);
        try {
            awaitTrue(() -> service.hasSubscribers("tenant-a", "public.customer")
                    && service.hasSubscribers("tenant-b", "public.customer"));

            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1L);
            service.publish("tenant-a", event);

            awaitTrue(() -> aReceived.size() == 1);
            assertThat(aReceived).hasSize(1);
            assertThat(bReceived).isEmpty();
        } finally {
            a.dispose();
            b.dispose();
        }
    }

    @Test
    @DisplayName("publish(tenantA, event) NOT delivered to null-tenant subscriber on same table")
    void publish_withTenant_notDeliveredToNullTenantSubscriber() {
        List<CDCEvent> nullTenantReceived = new ArrayList<>();
        Disposable d = service.subscribe(null, "public.customer").subscribe(nullTenantReceived::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "public.customer"));
            CDCEvent event = new CDCEvent("INSERT", "public", "customer", "{\"id\":1}", 1L);
            service.publish("tenant-a", event);

            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofMillis(500))
                    .untilAsserted(() -> assertThat(nullTenantReceived).isEmpty());
        } finally {
            d.dispose();
        }
    }

    @Test
    @DisplayName("hasSubscribers(tenantId, table) tracks subscribers per tenant")
    void hasSubscribers_isTenantScoped() {
        assertThat(service.hasSubscribers("tenant-a", "public.customer")).isFalse();
        Disposable d = service.subscribe("tenant-a", "public.customer").subscribe(e -> {});
        try {
            awaitTrue(() -> service.hasSubscribers("tenant-a", "public.customer"));
            assertThat(service.hasSubscribers("tenant-a", "public.customer")).isTrue();
            assertThat(service.hasSubscribers("tenant-b", "public.customer")).isFalse();
        } finally {
            d.dispose();
        }
    }

    @Test
    @DisplayName("an event of a reserved schema is dropped, even for a subscriber named on it")
    void publish_dropsEventsOfReservedSchemas() {
        List<CDCEvent> internal = new ArrayList<>();
        List<CDCEvent> ordinary = new ArrayList<>();
        Disposable meta = service.subscribe(null, "excalibase_meta.t").subscribe(internal::add);
        Disposable auth = service.subscribe(null, "auth.users").subscribe(internal::add);
        Disposable plain = service.subscribe(null, "public.t").subscribe(ordinary::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "excalibase_meta.t")
                    && service.hasSubscribers(null, "auth.users") && service.hasSubscribers(null, "public.t"));

            service.publish(null, new CDCEvent("INSERT", "excalibase_meta", "t", "{\"id\":1}", 1L));
            service.publish(null, new CDCEvent("INSERT", "AUTH", "users", "{\"id\":1}", 2L));
            service.publish(null, new CDCEvent("INSERT", "public", "t", "{\"id\":2}", 3L));

            awaitTrue(() -> ordinary.size() == 1);
            assertThat(internal).isEmpty();
        } finally {
            meta.dispose();
            auth.dispose();
            plain.dispose();
        }
    }

    private static void awaitTrue(BooleanSupplier cond) {
        await().atMost(Duration.ofSeconds(2)).until(cond::getAsBoolean);
    }

    @Test
    @DisplayName("schema.table keys cannot collide: public.secret_x and public_secret.x are different tables")
    void keysDoNotCollide() {
        List<CDCEvent> received = new ArrayList<>();
        Disposable d = service.subscribe(null, "public.secret_x").subscribe(received::add);
        try {
            awaitTrue(() -> service.hasSubscribers(null, "public.secret_x"));
            service.publish(null, new CDCEvent("INSERT", "public_secret", "x", "{}", 1L));
            service.publish(null, new CDCEvent("INSERT", "public", "secret_x", "{}", 2L));
            awaitTrue(() -> received.size() == 1);
            assertThat(received.get(0).schema()).isEqualTo("public");
        } finally {
            d.dispose();
        }
    }
}
