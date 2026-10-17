package io.github.excalibase.mysql;

import io.github.excalibase.compiler.KeyedWrite;
import io.github.excalibase.compiler.TwoPhaseWrite;

/** A GraphQL mutation field on MySQL: its rows are written and read back by key, as REST's are. */
record MysqlWrite(String fieldName, KeyedWrite write) implements TwoPhaseWrite {
}
