package io.github.excalibase;

import io.github.excalibase.metadata.ValidateCommand;
import io.github.excalibase.observability.StartupSettings;
import io.micronaut.runtime.Micronaut;
import java.util.Map;

public class ExcalibaseGraphqlApplication {

    public static void main(String[] args) {
        if (args.length > 0 && ValidateCommand.COMMAND.equals(args[0])) {
            System.exit(ValidateCommand.run(args, System.getenv(), System.out, System.err));
        }
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
