package io.github.excalibase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(scanBasePackages = "io.github.excalibase",
        exclude = {DataSourceAutoConfiguration.class, JdbcRepositoriesAutoConfiguration.class})
public class ExcalibaseGraphqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExcalibaseGraphqlApplication.class, args);
    }
}
