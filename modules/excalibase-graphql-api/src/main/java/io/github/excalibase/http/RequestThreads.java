package io.github.excalibase.http;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/** The executor that runs request handlers and, when it is a pool of platform threads, the pool itself. */
public record RequestThreads(ExecutorService executor, ThreadPoolExecutor pool) {

    static RequestThreads virtual(ExecutorService executor) {
        return new RequestThreads(executor, null);
    }

    static RequestThreads platform(ThreadPoolExecutor pool) {
        return new RequestThreads(pool, pool);
    }
}
