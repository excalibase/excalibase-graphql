package io.github.excalibase.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A real database the tests run against, started once per JVM: the official mongo image, and the upstream
 * DocumentDB local image (the gateway and the extension together, the one artifact that has both).
 */
final class Backend {

    static final Backend MONGO = new Backend("mongo:7", 27017, false);
    static final Backend DOCUMENTDB = new Backend("ghcr.io/documentdb/documentdb/documentdb-local:latest", 10260, true);

    private static final String DOCDB_USER = "tester";
    private static final String DOCDB_PASSWORD = "Tester-Pass-1234";

    private final String image;
    private final int port;
    private final boolean documentDb;
    private GenericContainer<?> container;
    private IllegalStateException unreachable;

    private Backend(String image, int port, boolean documentDb) {
        this.image = image;
        this.port = port;
        this.documentDb = documentDb;
    }

    String name() {
        return documentDb ? "documentdb" : "mongodb";
    }

    synchronized String uri() {
        if (unreachable != null) {
            throw unreachable;
        }
        if (container == null) {
            GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(port);
            if (documentDb) {
                started.withCommand("--username", DOCDB_USER, "--password", DOCDB_PASSWORD)
                        .waitingFor(Wait.forLogMessage(".*Gateway ready to accept connections.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(5)));
            } else {
                started.waitingFor(Wait.forLogMessage(".*[Ww]aiting for connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
            }
            started.start();
            container = started;
            String address = address();
            try (MongoClient client = clientFor(address)) {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
            } catch (RuntimeException e) {
                unreachable = new IllegalStateException(name() + " started but cannot be reached: " + e.getMessage(), e);
                throw unreachable;
            }
        }
        return address();
    }

    private String address() {
        String hostPort = container.getHost() + ":" + container.getMappedPort(port);
        return documentDb
                ? "mongodb://" + DOCDB_USER + ":" + DOCDB_PASSWORD + "@" + hostPort
                        + "/?tls=true&tlsAllowInvalidHostnames=true&authMechanism=SCRAM-SHA-256"
                : "mongodb://" + hostPort + "/?directConnection=true";
    }

    /** DocumentDB's local image presents a self-signed certificate; the tests trust it, nothing else does. */
    SSLContext sslContext() {
        if (!documentDb) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    MongoClient client() {
        return clientFor(uri());
    }

    private MongoClient clientFor(String uri) {
        MongoClientSettings.Builder settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(20, TimeUnit.SECONDS));
        if (documentDb) {
            settings.applyToSslSettings(ssl -> ssl.enabled(true).context(sslContext()));
        }
        return MongoClients.create(settings.build());
    }

    /** Replaces the collection's contents. */
    void seed(String database, String collection, List<Document> documents) {
        try (MongoClient client = client()) {
            var target = client.getDatabase(database).getCollection(collection);
            target.drop();
            target.insertMany(documents);
        }
    }
}
