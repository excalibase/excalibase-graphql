package io.github.excalibase;

import io.github.excalibase.config.ExcalibaseRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication(scanBasePackages = "io.github.excalibase",
        exclude = {DataSourceAutoConfiguration.class, JdbcRepositoriesAutoConfiguration.class})
@ImportRuntimeHints(ExcalibaseRuntimeHints.class)
public class ExcalibaseGraphqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExcalibaseGraphqlApplication.class, args);
    }
}
