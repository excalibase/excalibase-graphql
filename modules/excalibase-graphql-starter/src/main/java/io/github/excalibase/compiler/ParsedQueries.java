package io.github.excalibase.compiler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import graphql.language.Document;
import graphql.parser.Parser;

import java.util.function.Function;

/**
 * Cache of parsed GraphQL documents keyed by query text, bounded by the total length of the cached queries
 * rather than by entry count: one query may be a million characters. A parsed document is immutable and does
 * not depend on schema, role or project, so nothing here needs eviction beyond the bound. A query that fails
 * to parse is never cached, and a single query above {@code budget / }{@link #SINGLE_QUERY_SHARE_DIVISOR}
 * is parsed but not kept, so one caller cannot push everyone else out.
 */
public final class ParsedQueries {

    /** Heap retained per query character, worst case: measured at 82 bytes with uncompressed pointers (ZGC). */
    static final long RETAINED_BYTES_PER_CHAR = 128;
    public static final double MAX_HEAP_SHARE = 0.5;
    static final int SINGLE_QUERY_SHARE_DIVISOR = 16;

    private final Function<String, Document> parser;
    private final Cache<String, Document> documents;
    private final long maxQueryLength;

    /** @param maxWeight total query characters the cache may hold */
    public ParsedQueries(long maxWeight, Function<String, Document> parser) {
        if (maxWeight <= 0) {
            throw new IllegalArgumentException("Parse cache weight must be positive: " + maxWeight);
        }
        this.parser = parser;
        this.maxQueryLength = Math.max(1, maxWeight / SINGLE_QUERY_SHARE_DIVISOR);
        this.documents = Caffeine.newBuilder()
                .maximumWeight(maxWeight)
                .weigher((String query, Document document) -> query.length())
                .build();
    }

    /** A cache holding {@code heapShare} of {@code maxMemoryBytes}; the share is above 0 and at most {@link #MAX_HEAP_SHARE}. */
    public static ParsedQueries forHeapShare(double heapShare, long maxMemoryBytes) {
        requireValidHeapShare(heapShare);
        long weight = (long) (maxMemoryBytes * heapShare) / RETAINED_BYTES_PER_CHAR;
        return new ParsedQueries(Math.max(weight, 1), Parser::parse);
    }

    public static void requireValidHeapShare(double heapShare) {
        if (!(heapShare > 0 && heapShare <= MAX_HEAP_SHARE)) {
            throw new IllegalArgumentException(
                    "Parse cache heap share must be above 0 and at most " + MAX_HEAP_SHARE + ": " + heapShare);
        }
    }

    /** The parsed {@code query}; throws the parser's exception for invalid GraphQL. */
    public Document parse(String query) {
        Document cached = documents.getIfPresent(query);
        if (cached != null) {
            return cached;
        }
        Document parsed = parser.apply(query);
        if (query.length() <= maxQueryLength) {
            documents.put(query, parsed);
        }
        return parsed;
    }

    /** Total length of the cached queries, after pending evictions. */
    public long weight() {
        documents.cleanUp();
        return documents.asMap().keySet().stream().mapToLong(String::length).sum();
    }

    public long entries() {
        documents.cleanUp();
        return documents.estimatedSize();
    }
}
