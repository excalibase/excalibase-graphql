package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Closing the engine closes the configured database's pool as well as the projects' pools. (EXC-772) */
class ConfiguredPoolShutdownTest {

    private static final String SECRET = "test-hmac-secret-0123456789abcdef0123456789";

    @Test
    void theConfiguredPoolIsClosedWithTheContext() {
        DataSource routing;
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.auth.hmac-secret", SECRET,
                "app.nats.enabled", "false",
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1"))) {
            routing = context.getBean(DataSource.class);
            assertThat(((DynamicRoutingDataSource) routing).configured()).containsInstanceOf(HikariDataSource.class);
        }

        HikariDataSource pool = (HikariDataSource) ((DynamicRoutingDataSource) routing).configured().orElseThrow();
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void closingTheRoutingSourceClosesTheConfiguredPool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:sqlite::memory:");
        pool.setInitializationFailTimeout(-1);

        new DynamicRoutingDataSource(null, pool).close();

        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void aPoolThatIsSlowToClose_doesNotHoldShutdownUp() {
        CountDownLatch release = new CountDownLatch(1);
        try (var _ = new Release(release)) {
            AutoCloseablePool slow = new AutoCloseablePool(release);
            long begun = System.nanoTime();

            new DynamicRoutingDataSource(null, slow).close();

            assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofSeconds(2));
        }
    }

    /** Lets the slow pool finish closing when the test is done. */
    private record Release(CountDownLatch latch) implements AutoCloseable {
        @Override
        public void close() {
            latch.countDown();
        }
    }

    /** A pool whose close waits, as one does for a database that does not answer. */
    private static final class AutoCloseablePool extends AbstractDataSource implements AutoCloseable {
        private final CountDownLatch release;

        private AutoCloseablePool(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("closed");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("closed");
        }

        @Override
        public void close() throws InterruptedException {
            release.await(30, TimeUnit.SECONDS);
        }
    }
}
