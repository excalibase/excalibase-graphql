package io.github.excalibase.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.codecs.BsonTypeClassMap;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.UuidRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The official MongoDB sync driver; DocumentDB is reached over the same wire protocol. */
final class DriverPipelineRunner implements PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(DriverPipelineRunner.class);
    private static final long DEFAULT_SELECTION_MILLIS = 5_000;

    private final MongoClient client;
    private final String database;
    private final long maxTimeMillis;

    /** @param sslContext trust for a server whose CA is not in the JVM's store, or null for the default */
    DriverPipelineRunner(String uri, String database, long maxTimeMillis, SSLContext sslContext) {
        ConnectionString connection = new ConnectionString(uri);
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applyConnectionString(connection)
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(withInstantDates());
        if (connection.getServerSelectionTimeout() == null) {
            settings.applyToClusterSettings(cluster ->
                    cluster.serverSelectionTimeout(Math.max(maxTimeMillis, DEFAULT_SELECTION_MILLIS), TimeUnit.MILLISECONDS));
        }
        if (sslContext != null) {
            settings.applyToSslSettings(ssl -> ssl.enabled(true).context(sslContext));
        }
        this.client = MongoClients.create(settings.build());
        this.database = database;
        this.maxTimeMillis = maxTimeMillis;
    }

    /** Dates come back as {@link Instant}, so no code here needs the legacy date class. */
    private static CodecRegistry withInstantDates() {
        CodecRegistry defaults = MongoClientSettings.getDefaultCodecRegistry();
        DocumentCodec documents = new DocumentCodec(defaults, new BsonTypeClassMap(Map.of(BsonType.DATE_TIME, Instant.class)));
        return CodecRegistries.fromRegistries(CodecRegistries.fromCodecs(documents), defaults);
    }

    @Override
    public List<Document> run(String collection, List<Document> pipeline) {
        return client.getDatabase(database).getCollection(collection)
                .aggregate(pipeline)
                .maxTime(maxTimeMillis, TimeUnit.MILLISECONDS)
                .into(new ArrayList<>());
    }

    @Override
    public boolean ping() {
        try {
            client.getDatabase(database).runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            log.warn("MongoDB does not answer a ping: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
