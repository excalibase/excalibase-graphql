package io.github.excalibase.mysql;

import io.github.excalibase.compiler.TwoPhaseWrite;

import java.util.List;
import java.util.Map;

/**
 * A MySQL write. MySQL has no {@code RETURNING}, so the rows a write touched are identified by primary key
 * and read back by it, on the request's connection and inside its transaction.
 */
sealed interface MysqlWrite extends TwoPhaseWrite {

    /**
     * One INSERT per row. {@code sentKeys} holds each row's key values as sent, null where the client sent
     * none and the database must generate it.
     */
    record Insert(String table, List<String> keyColumns, List<String> statements, List<List<Object>> sentKeys,
                  KeyedRead readBack) implements MysqlWrite {}

    /**
     * {@code lockSql} selects the matched rows' keys {@code FOR UPDATE}; {@code updateHead} is completed by a
     * predicate on those keys; {@code keyAssignments} are the key columns the write sets, by key position.
     */
    record Update(String lockSql, String updateHead, Map<Integer, Object> keyAssignments,
                  KeyedRead readBack) implements MysqlWrite {}

    /** The rows are read before they are deleted. */
    record Delete(String selectSql, String deleteSql) implements MysqlWrite {}

    /** Reads rows by key: {@code head}, a predicate on {@code keyRefs}, then {@code tail}. */
    record KeyedRead(String head, String tail, List<String> keyRefs) {}
}
