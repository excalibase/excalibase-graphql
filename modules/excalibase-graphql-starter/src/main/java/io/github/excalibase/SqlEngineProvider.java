package io.github.excalibase;

import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;
import java.util.List;

/**
 * SPI interface for database engine providers.
 * Implementations register via META-INF/services/io.github.excalibase.SqlEngineProvider.
 */
public interface SqlEngineProvider {
    List<String> supportedTypes();
    SqlDialect createDialect();
    SchemaLoader createSchemaLoader();
    MutationCompiler createMutationCompiler();
    MutationExecutor createMutationExecutor();
}
