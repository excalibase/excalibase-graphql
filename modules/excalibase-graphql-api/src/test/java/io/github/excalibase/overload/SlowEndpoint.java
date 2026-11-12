package io.github.excalibase.overload;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds a request for 200 ms and records the most requests in flight at once. */
@Controller("/test-proj/slow")
@Requires(property = "test.slow-endpoint", value = "true")
class SlowEndpoint {

    static final AtomicInteger ACTIVE = new AtomicInteger();
    static final AtomicInteger PEAK = new AtomicInteger();

    @Get
    @ExecuteOn(TaskExecutors.BLOCKING)
    String slow() throws InterruptedException {
        PEAK.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
        try {
            Thread.sleep(200);
        } finally {
            ACTIVE.decrementAndGet();
        }
        return "ok";
    }
}
