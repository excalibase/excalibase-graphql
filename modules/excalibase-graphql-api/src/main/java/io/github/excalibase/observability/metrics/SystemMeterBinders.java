package io.github.excalibase.observability.metrics;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.DiskSpaceMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.io.File;

/** The JVM and disk meters Micronaut does not register but Spring Boot did; the rest of the JVM meters are Micronaut's. */
@Factory
class SystemMeterBinders {

    @Singleton
    MeterBinder jvmInfo() {
        return new JvmInfoMetrics();
    }

    @Singleton
    MeterBinder jvmCompilation() {
        return new JvmCompilationMetrics();
    }

    @Singleton
    MeterBinder jvmHeapPressure() {
        return new JvmHeapPressureMetrics();
    }

    @Singleton
    MeterBinder diskSpace() {
        return new DiskSpaceMetrics(new File("."));
    }
}
