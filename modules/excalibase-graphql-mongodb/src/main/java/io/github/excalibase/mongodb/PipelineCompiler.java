package io.github.excalibase.mongodb;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the single aggregation pipeline of a request. The first stage is always the caller's mandatory
 * row filter; the request's own filter is a separate stage after it, so nothing the request carries can
 * rewrite or precede the permission filter.
 */
final class PipelineCompiler {

    /** Skipping still reads every skipped document, so a deep offset is a cost the caller does not get to set. */
    static final int MAX_OFFSET = 100_000;

    private PipelineCompiler() {}

    static List<Document> list(CollectionDef collection, Cond security, ListQuery query, Projection projection,
                               int maxRows) {
        int limit = query.limit();
        int offset = query.offset();
        if (limit < 1 || limit > maxRows) {
            throw new RequestException("limit must be between 1 and " + maxRows);
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new RequestException("offset must be between 0 and " + MAX_OFFSET);
        }
        List<Document> stages = new ArrayList<>();
        stages.add(match(security));
        stages.add(match(query.filter()));
        stages.add(new Document("$sort", sort(collection, query.order())));
        if (offset > 0) {
            stages.add(new Document("$skip", (long) offset));
        }
        stages.add(new Document("$limit", (long) limit));
        stages.add(new Document("$project", project(projection)));
        return stages;
    }

    static List<Document> byId(CollectionDef collection, Cond security, String id, Projection projection) {
        Object stored = ValueCodec.parseId(collection.idKind(), id);
        List<Document> stages = new ArrayList<>();
        stages.add(match(security));
        stages.add(new Document("$match", new Document(CollectionDef.MONGO_ID, new Document("$eq", stored))));
        stages.add(new Document("$limit", 1L));
        stages.add(new Document("$project", project(projection)));
        return stages;
    }

    private static Document match(Cond cond) {
        return new Document("$match", cond.doc());
    }

    private static Document sort(CollectionDef collection, List<SortKey> order) {
        Document sort = new Document();
        for (SortKey key : order) {
            if (!collection.declares(key.field())) {
                throw new RequestException("Unknown field \"" + key.field() + "\" on " + collection.name());
            }
            sort.putIfAbsent(CollectionDef.storedName(key.field()), key.ascending() ? 1 : -1);
        }
        sort.putIfAbsent(CollectionDef.MONGO_ID, 1);
        return sort;
    }

    private static Document project(Projection projection) {
        Document project = new Document();
        for (String field : projection.select()) {
            if (!CollectionDef.ID_FIELD.equals(field)) {
                project.put(field, 1);
            }
        }
        for (String field : projection.nulled()) {
            project.put(field, new Document("$literal", null));
        }
        // an exclusion-only projection ({_id: 0}) would return every other field, and an empty one is an error
        boolean idAsked = projection.select().contains(CollectionDef.ID_FIELD);
        project.put(CollectionDef.MONGO_ID, idAsked || project.isEmpty() ? 1 : 0);
        return project;
    }
}
