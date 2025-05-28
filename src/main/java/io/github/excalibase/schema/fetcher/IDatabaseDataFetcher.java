package io.github.excalibase.schema.fetcher;

import graphql.schema.DataFetcher;

import java.util.List;
import java.util.Map;

public interface IDatabaseDataFetcher {
    DataFetcher<List<Map<String, Object>>> createTableDataFetcher(String tableName);

    DataFetcher<Map<String, Object>> createConnectionDataFetcher(String tableName);

    DataFetcher<Map<String, Object>> createRelationshipDataFetcher(
            String tableName,
            String foreignKeyColumn,
            String referencedTable,
            String referencedColumn);
}
