package io.github.excalibase.sqlite;

import io.github.excalibase.compiler.KeyedMutationExecutor;

/** Runs SQLite's keyed writes on the caller's connection and transaction. */
public class SqliteMutationExecutor extends KeyedMutationExecutor {

    public SqliteMutationExecutor() {
        super("SQLite");
    }
}
