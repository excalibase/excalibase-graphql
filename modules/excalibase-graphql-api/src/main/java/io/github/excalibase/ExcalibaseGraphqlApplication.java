package io.github.excalibase;

import io.github.excalibase.observability.StartupSettings;
import io.micronaut.runtime.Micronaut;
import java.util.Map;

public class ExcalibaseGraphqlApplication {

    public static void main(String[] args) {
        NativeImageDefaults.apply(System.getProperties());
        Map<String, Object> settings = StartupSettings.from(System.getenv());
        StartupSettings.applyLoggerLevels(settings);
        Micronaut.build(args)
                .mainClass(ExcalibaseGraphqlApplication.class)
                .banner(false)
                .properties(settings)
                .start();
    }
}
