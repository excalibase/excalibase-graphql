package io.github.excalibase.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds a request until the test releases it (or 10 s pass), so a test can start a shutdown while it is in flight. */
@Controller("/test-proj/held")
@Requires(property = "test.held-endpoint", value = "true")
class HeldEndpoint {

    static volatile CountDownLatch started = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);

    static void reset() {
        started = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    @Get
    @ExecuteOn(TaskExecutors.BLOCKING)
    String held() throws InterruptedException {
        started.countDown();
        release.await(10, TimeUnit.SECONDS);
        return "finished";
    }
}
