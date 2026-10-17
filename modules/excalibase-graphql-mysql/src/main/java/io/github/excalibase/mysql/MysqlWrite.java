package io.github.excalibase.mysql;

import io.github.excalibase.compiler.KeyedWrite;
import io.github.excalibase.compiler.TwoPhaseWrites;

import java.util.Set;

/** A GraphQL mutation field on MySQL: its rows are written and read back by key, as REST's are. */
record MysqlWrite(String table, Set<String> checkReads, KeyedWrite write) implements TwoPhaseWrites.Write {
}
