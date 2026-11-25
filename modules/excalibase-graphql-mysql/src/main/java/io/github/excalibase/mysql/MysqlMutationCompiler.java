package io.github.excalibase.mysql;

import io.github.excalibase.compiler.KeyedMutationCompiler;

/** MySQL has no {@code RETURNING}: its rows are written and read back by primary key. */
public class MysqlMutationCompiler extends KeyedMutationCompiler {
}
