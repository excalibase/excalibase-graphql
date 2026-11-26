package io.github.excalibase.mongodb;

import org.bson.Document;

import java.util.List;

/** Runs one aggregation pipeline against a collection. The only place the engine touches the driver. */
interface PipelineRunner extends AutoCloseable {

    List<Document> run(String collection, List<Document> pipeline);

    /** Whether the database answers a ping now. */
    default boolean ping() {
        return true;
    }

    @Override
    void close();
}
