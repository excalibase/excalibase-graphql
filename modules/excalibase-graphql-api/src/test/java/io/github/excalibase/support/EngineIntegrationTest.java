package io.github.excalibase.support;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * A test against the real engine on a real socket. A subclass names its settings in
 * {@link #getProperties()}, typically {@link EngineProperties#singleDatabase}.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class EngineIntegrationTest implements TestPropertyProvider {

    @Inject
    protected EmbeddedServer server;

    protected EngineClient http;

    @BeforeEach
    void connect() {
        http = new EngineClient(URI.create(server.getURL().toString() + "/"));
    }
}
