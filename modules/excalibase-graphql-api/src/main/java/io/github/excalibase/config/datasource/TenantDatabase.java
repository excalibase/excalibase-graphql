package io.github.excalibase.config.datasource;

import io.github.excalibase.service.DatabaseEngine;

import javax.sql.DataSource;

/** A project's connection pool and the engine it runs, both from the project's vault record. */
public record TenantDatabase(DataSource dataSource, DatabaseEngine engine) {}
