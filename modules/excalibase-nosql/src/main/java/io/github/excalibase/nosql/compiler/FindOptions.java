package io.github.excalibase.nosql.compiler;

import java.util.Map;

/**
 * Find query options.
 *
 * <p>Pagination modes:
 * <ul>
 *   <li>{@code cursorMode=false} — classic offset/sort pagination
 *       ({@code opts.sort}, {@code opts.offset})</li>
 *   <li>{@code cursorMode=true}  — keyset pagination: always ordered by
 *       {@code (created_at DESC, id DESC)}. {@code opts.sort} and
 *       {@code opts.offset} are ignored. {@code opts.cursor} is the opaque
 *       cursor from the previous page, or {@code null} for the first page.</li>
 * </ul>
 */
public record FindOptions(int limit, int offset, Map<String, Object> sort,
                          String cursor, boolean cursorMode) {

    public FindOptions {
        if (limit <= 0) limit = 30;
        if (limit > 1000) limit = 1000;
        if (offset < 0) offset = 0;
    }

    public FindOptions(int limit, int offset, Map<String, Object> sort) {
        this(limit, offset, sort, null, false);
    }

    public FindOptions(int limit, int offset, Map<String, Object> sort, String cursor) {
        this(limit, offset, sort, cursor, cursor != null);
    }
}
