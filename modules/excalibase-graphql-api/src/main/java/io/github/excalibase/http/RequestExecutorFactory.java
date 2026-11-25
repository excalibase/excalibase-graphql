package io.github.excalibase.http;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads that run request handlers: a virtual thread per request, named as before, unless
 * {@code spring.threads.virtual.enabled=false}, which gives a pool of platform threads capped by
 * {@code server.tomcat.threads.max} and named after the port.
 */
@Factory
class RequestExecutorFactory {

    private static final long IDLE_SECONDS = 60;

    @Singleton
    @Named(TaskExecutors.BLOCKING)
    @Replaces(value = ExecutorService.class, named = TaskExecutors.BLOCKING)
    ExecutorService requestExecutor(RequestThreads threads) {
        return threads.executor();
    }

    @Singleton
    RequestThreads requestThreads(
            @Value("${spring.threads.virtual.enabled:true}") boolean virtual,
            @Value("${server.tomcat.threads.max:200}") int maxThreads,
            @Value("${micronaut.server.port:10000}") int port) {
        if (virtual) {
            return RequestThreads.virtual(
                    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tomcat-handler-", 0).factory()));
        }
        ThreadPoolExecutor pool = new ThreadPoolExecutor(maxThreads, maxThreads, IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), platformThreads("http-nio-" + port + "-exec-"));
        pool.allowCoreThreadTimeOut(true);
        return RequestThreads.platform(pool);
    }

    private static ThreadFactory platformThreads(String prefix) {
        AtomicInteger next = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
