package io.github.excalibase;

import io.github.excalibase.observability.StartupSettings;
import io.micronaut.runtime.Micronaut;

public class ExcalibaseGraphqlApplication {

    public static void main(String[] args) {
        Micronaut.build(args)
                .mainClass(ExcalibaseGraphqlApplication.class)
                .properties(StartupSettings.from(System.getenv()))
                .start();
    }
}
