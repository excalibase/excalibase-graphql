package io.github.excalibase.mongodb;

import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.spi.QueryEngineProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderGuardTest {

    private static Map<String, Object> settings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("uri", "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=100");
        settings.put("database", "app");
        settings.put("collections", List.of(Map.of("name", "orders", "id", "objectId",
                "fields", Map.of("owner", "string"))));
        return settings;
    }

    @Test
    void isDiscoveredThroughTheServiceLoaderUnderBothNames() {
        List<String> types = ServiceLoader.load(QueryEngineProvider.class).stream()
                .flatMap(p -> p.get().supportedTypes().stream()).toList();

        assertThat(types).contains("mongodb", "documentdb");
    }

    @Test
    void refusesToServeACollectionNobodyGovernsAndNobodyOpened() {
        Map<String, Object> declared = settings();
        MongoQueryEngineProvider provider = new MongoQueryEngineProvider();
        assertThatThrownBy(() -> provider.create(declared))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("orders")
                .hasMessageContaining("policies");
    }

    @Test
    void refusesACollectionWhosePoliciesNeverAllowAnything() {
        Map<String, Object> settings = settings();
        settings.put("policies", List.of(Fixtures.policy(PolicyEffect.DENY, Fixtures.rule("owner", RuleOperator.EQ, "x"))));

        MongoQueryEngineProvider provider = new MongoQueryEngineProvider();
        assertThatThrownBy(() -> provider.create(settings))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("orders");
    }

    @Test
    void servesACollectionOnlyWhenGovernedOrNamedOpen() {
        Map<String, Object> governed = settings();
        governed.put("policies", List.of(Fixtures.policy(PolicyEffect.ALLOW, Fixtures.rule("owner", RuleOperator.EQ, "x"))));
        Map<String, Object> open = settings();
        open.put("openCollections", List.of("orders"));

        try (var a = new MongoQueryEngineProvider().create(governed); var b = new MongoQueryEngineProvider().create(open)) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }

    @Test
    void anOpenCollectionThatIsNotDeclaredIsATypo() {
        Map<String, Object> settings = settings();
        settings.put("openCollections", List.of("ordrs"));

        MongoQueryEngineProvider provider = new MongoQueryEngineProvider();
        assertThatThrownBy(() -> provider.create(settings))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ordrs");
    }

    @Test
    void refusesToStartWithoutAConnection() {
        Map<String, Object> settings = settings();
        settings.remove("uri");
        settings.put("openCollections", List.of("*"));

        MongoQueryEngineProvider provider = new MongoQueryEngineProvider();
        assertThatThrownBy(() -> provider.create(settings))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("uri");
    }

    @Test
    void anEngineWhoseDatabaseCannotBeReachedIsNotHealthy() {
        Map<String, Object> settings = settings();
        settings.put("openCollections", List.of("*"));

        try (var engine = new MongoQueryEngineProvider().create(settings)) {
            assertThat(engine.healthy()).isFalse();
        }
    }
}
