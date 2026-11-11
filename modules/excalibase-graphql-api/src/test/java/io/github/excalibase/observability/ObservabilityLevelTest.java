package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.SecurityConstants;

class ObservabilityLevelTest {

    private static final String LEVEL = "excalibase.observability.level";

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new ObservabilityLevelEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    private static ApplicationContextRunner runner(Map<String, Object> properties) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
                    new ObservabilityLevelEnvironmentPostProcessor()
                            .postProcessEnvironment(context.getEnvironment(), null);
                })
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration.class, ObservationAutoConfiguration.class))
                .withUserConfiguration(HttpTimerFilterConfig.class);
    }

    private static boolean observed(ObservationRegistry registry, String name) {
        return !Observation.createNotStarted(name, registry).isNoop();
    }

    @Test
    void anUnsetLevelIsFull() {
        assertThat(ObservabilityLevel.parse(null)).isEqualTo(ObservabilityLevel.FULL);
        assertThat(ObservabilityLevel.parse("  ")).isEqualTo(ObservabilityLevel.FULL);
    }

    @Test
    void levelsParseCaseInsensitively() {
        assertThat(ObservabilityLevel.parse("lite")).isEqualTo(ObservabilityLevel.LITE);
        assertThat(ObservabilityLevel.parse(" OFF ")).isEqualTo(ObservabilityLevel.OFF);
        assertThat(ObservabilityLevel.parse("Full")).isEqualTo(ObservabilityLevel.FULL);
    }

    @Test
    void anUnknownLevelFailsWithTheValidChoices() {
        assertThatThrownBy(() -> ObservabilityLevel.parse("verbose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase.observability.level")
                .hasMessageContaining("verbose")
                .hasMessageContaining("full, lite, off");
    }

    @Test
    void anUnknownLevelStopsStartup() {
        assertThatThrownBy(() -> environment(Map.of(LEVEL, "fulll")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fulll");
    }

    @Test
    void fullAddsNothingToTheEnvironment() {
        StandardEnvironment before = new StandardEnvironment();
        StandardEnvironment after = environment(Map.of());

        assertThat(after.getPropertySources()).hasSize(before.getPropertySources().size() + 1);
        assertThat(after.getProperty("management.observations.enable.http")).isNull();
        assertThat(after.getProperty("management.observations.enable.spring.security")).isNull();
        assertThat(after.getProperty("management.observations.enable.jdbc")).isNull();
        assertThat(after.getProperty("management.observations.enable.tomcat")).isNull();
        assertThat(after.getProperty("management.tracing.sampling.probability")).isNull();
    }

    @Test
    void liteTurnsOffOnlyTheHttpAndSecurityObservations() {
        StandardEnvironment environment = environment(Map.of(LEVEL, "lite"));

        assertThat(environment.getProperty("management.observations.enable.http")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.spring.security")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.jdbc")).isNull();
        assertThat(environment.getProperty("management.observations.enable.tomcat")).isNull();
    }

    @Test
    void offTurnsOffTheSameObservationsAsLiteWithoutTheTimer() {
        StandardEnvironment environment = environment(Map.of(LEVEL, "off"));

        assertThat(environment.getProperty("management.observations.enable.http")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.spring.security")).isEqualTo("false");
        assertThat(environment.getProperty("management.observations.enable.jdbc")).isNull();
        assertThat(environment.getProperty("management.observations.enable.tomcat")).isNull();
    }

    @Test
    void anExplicitObservationSettingStillWinsOverTheLevel() {
        StandardEnvironment environment = environment(
                Map.of(LEVEL, "lite", "management.observations.enable.http", "true"));

        assertThat(environment.getProperty("management.observations.enable.http")).isEqualTo("true");
    }

    @Test
    void fullKeepsEveryObservationAndRegistersNoTimerFilter() {
        runner(Map.of()).run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);
            for (String name : new String[] {"http.server.requests", "spring.security.http.secured.requests",
                    "jdbc.query", "tomcat.connections"}) {
                assertThat(observed(registry, name)).as(name).isTrue();
            }
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isFalse();
        });
    }

    @Test
    void liteDropsHttpAndSecurityObservationsAndRegistersTheTimerFilter() {
        runner(Map.of(LEVEL, "lite")).run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);
            assertThat(observed(registry, "http.server.requests")).isFalse();
            assertThat(observed(registry, "spring.security.http.secured.requests")).isFalse();
            assertThat(observed(registry, "jdbc.query")).isTrue();
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isTrue();
        });
    }

    @Test
    void liteWithTheHttpObservationExplicitlyOnDoesNotRegisterTheTimerFilterSoNothingIsCountedTwice() {
        runner(Map.of(LEVEL, "lite", "management.observations.enable.http", "true")).run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);
            assertThat(observed(registry, "http.server.requests")).isTrue();
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isFalse();
        });
    }

    @Test
    void offDropsTheHttpAndSecurityObservationsAndRegistersNoTimerFilter() {
        runner(Map.of(LEVEL, "off")).run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);
            assertThat(observed(registry, "http.server.requests")).isFalse();
            assertThat(observed(registry, "spring.security.http.secured.requests")).isFalse();
            assertThat(observed(registry, "jdbc.query")).isTrue();
            assertThat(context.getBean(FilterRegistrationBean.class).isEnabled()).isFalse();
        });
    }

    @Test
    void theObservationConventionStillLabelsTenantsExactlyAsBefore() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/acme/api/v1/customers");
        request.setAttribute(SecurityConstants.JWT_CLAIMS_ATTR,
                new JwtClaims("u1", "proj-1", "acme-org", "Shop", "Acme Inc", "user", "a@b.c", null, 0));
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());

        var tags = new TenantObservationConvention().getLowCardinalityKeyValues(context);

        assertThat(tags.stream().map(tag -> tag.getKey() + "=" + tag.getValue()))
                .contains("tenant.id=proj-1", "org.slug=acme-org", "project.name=Shop", "org.name=Acme Inc",
                        "method=GET", "outcome=SUCCESS", "exception=none");
    }
}
