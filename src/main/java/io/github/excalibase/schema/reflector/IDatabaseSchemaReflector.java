package io.github.excalibase.schema.reflector;

import io.github.excalibase.model.TableInfo;

import java.util.Map;

public interface IDatabaseSchemaReflector {
    Map<String, TableInfo> reflectSchema();
}
