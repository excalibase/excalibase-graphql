package io.github.excalibase.observability.requests;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestMetricsConfigTest {

    private static final int METRICS_PORT = 9464;

    private static List<Connector> connectors(RequestMetrics metrics, String address) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (metrics != null) {
            beans.registerSingleton("requestMetrics", metrics);
        }
        ObjectProvider<RequestMetrics> provider = beans.getBeanProvider(RequestMetrics.class);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new RequestMetricsConfig().requestMetricsPort(provider, METRICS_PORT, address).customize(factory);
        return factory.getAdditionalTomcatConnectors();
    }

    @Test
    @DisplayName("off: no internal connector at all")
    void offOpensNoPort() {
        assertThat(connectors(null, "127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("no address: the internal port listens on every interface, as Kubernetes runs it")
    void noAddressBindsEveryInterface() {
        List<Connector> added = connectors(new RequestMetrics(10), "");
        assertThat(added).hasSize(1);
        assertThat(added.get(0).getPort()).isEqualTo(METRICS_PORT);
        assertThat(added.get(0).getProperty("address")).isNull();
    }

    @Test
    @DisplayName("an address: the internal port binds it alone, so a single host keeps it off tenant networks")
    void addressBindsOnlyThatAddress() {
        Connector connector = connectors(new RequestMetrics(10), " 127.0.0.1 ").get(0);
        assertThat(connector.getPort()).isEqualTo(METRICS_PORT);
        assertThat(String.valueOf(connector.getProperty("address"))).contains("127.0.0.1");
    }

    @Test
    @DisplayName("an address that does not resolve stops the start instead of binding every interface")
    void unresolvableAddressRefusesToStart() {
        assertThatThrownBy(() -> connectors(new RequestMetrics(10), "no-such-host.invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-host.invalid");
    }
}
