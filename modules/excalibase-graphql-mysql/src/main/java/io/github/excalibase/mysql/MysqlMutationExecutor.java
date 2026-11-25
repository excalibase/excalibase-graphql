package io.github.excalibase.mysql;

import io.github.excalibase.compiler.KeyedMutationExecutor;

/** Runs MySQL's keyed writes on the caller's connection and transaction. */
public class MysqlMutationExecutor extends KeyedMutationExecutor {

    public MysqlMutationExecutor() {
        super("MySQL");
    }
}
