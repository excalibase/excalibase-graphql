package io.github.excalibase.nosql.compiler;

import java.util.Map;

public record FindOptions(int limit, int offset, Map<String, Object> sort) {

    public FindOptions {
        if (limit <= 0) limit = 30;
        if (limit > 1000) limit = 1000;
        if (offset < 0) offset = 0;
    }
}
