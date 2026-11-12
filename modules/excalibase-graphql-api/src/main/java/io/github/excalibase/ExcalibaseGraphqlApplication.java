package io.github.excalibase;

import io.github.excalibase.config.ExcalibaseRuntimeHints;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Only the packages that hold components are scanned; ComponentScanCoverageTest fails when a component is
// added outside them.
@SpringBootApplication(scanBasePackages = {"io.github.excalibase.cdc", "io.github.excalibase.config",
        "io.github.excalibase.controller", "io.github.excalibase.observability", "io.github.excalibase.overload",
        "io.github.excalibase.rest", "io.github.excalibase.schema", "io.github.excalibase.service"},
        exclude = {DataSourceAutoConfiguration.class, JdbcRepositoriesAutoConfiguration.class,
                DataSourceObservationAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@ImportRuntimeHints(ExcalibaseRuntimeHints.class)
public class ExcalibaseGraphqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExcalibaseGraphqlApplication.class, args);
    }
}
