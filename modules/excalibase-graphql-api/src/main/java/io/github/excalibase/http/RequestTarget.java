package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.netty.NettyHttpRequest;

/** The request target as the client wrote it, which the framework's own URI parsing normalises or rewrites. */
final class RequestTarget {

    private RequestTarget() {
    }

    /** The path of the target, percent-escapes and all, without the query. */
    static String rawPath(HttpRequest<?> request) {
        String target = request instanceof NettyHttpRequest<?> netty
                ? netty.getNativeRequest().uri()
                : request.getUri().toString();
        int end = target.length();
        for (char delimiter : new char[] {'?', '#'}) {
            int index = target.indexOf(delimiter);
            end = index >= 0 ? Math.min(end, index) : end;
        }
        return target.substring(0, end);
    }
}
