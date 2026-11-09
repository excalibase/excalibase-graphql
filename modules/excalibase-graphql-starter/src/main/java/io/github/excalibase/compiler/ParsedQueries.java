package io.github.excalibase.compiler;

import graphql.language.Document;
import graphql.parser.Parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded least-recently-used cache of parsed GraphQL documents, keyed by query text. A parsed document is
 * immutable and does not depend on schema, role or project, so it needs no eviction beyond the size bound.
 * A query that fails to parse is not cached: the syntax error is raised again on every attempt.
 */
public final class ParsedQueries {

    public static final String SIZE_PROPERTY = "excalibase.graphql.parse-cache-size";
    private static final int DEFAULT_SIZE = 512;
    private static final ParsedQueries SHARED = new ParsedQueries(configuredSize(), Parser::parse);

    private final Function<String, Document> parser;
    private final Map<String, Document> documents;

    public ParsedQueries(int maxEntries, Function<String, Document> parser) {
        this.parser = parser;
        this.documents = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Document> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public static ParsedQueries shared() {
        return SHARED;
    }

    /** The parsed {@code query}; throws the parser's exception for invalid GraphQL. */
    public Document parse(String query) {
        synchronized (documents) {
            Document cached = documents.get(query);
            if (cached != null) {
                return cached;
            }
        }
        Document parsed = parser.apply(query);
        synchronized (documents) {
            documents.put(query, parsed);
        }
        return parsed;
    }

    public int size() {
        synchronized (documents) {
            return documents.size();
        }
    }

    private static int configuredSize() {
        String configured = System.getProperty(SIZE_PROPERTY, System.getenv("EXCALIBASE_GRAPHQL_PARSE_CACHE_SIZE"));
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SIZE;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException invalid) {
            return DEFAULT_SIZE;
        }
    }
}
