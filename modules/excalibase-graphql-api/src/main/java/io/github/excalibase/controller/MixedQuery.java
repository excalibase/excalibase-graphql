package io.github.excalibase.controller;

import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;

import java.util.List;
import java.util.Optional;

/**
 * A query whose root selects both introspection ({@code __schema}, {@code __type}, {@code __typename}) and
 * data. Introspection answers the first; the SQL compiler answers the second, which is the same document
 * with the introspection fields left out.
 */
final class MixedQuery {

    private MixedQuery() {
    }

    /** The query's data part, or empty when the root selects introspection only. */
    static Optional<String> dataPart(String query) {
        Document document = Parser.parse(query);
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.isEmpty()) {
            return Optional.empty();
        }
        OperationDefinition operation = operations.getFirst();
        List<Selection> data = operation.getSelectionSet().getSelections().stream()
                .filter(selection -> !(selection instanceof Field field && field.getName().startsWith("__")))
                .map(Selection.class::cast)
                .toList();
        if (data.isEmpty()) {
            return Optional.empty();
        }
        OperationDefinition dataOperation = operation.transform(builder ->
                builder.selectionSet(SelectionSet.newSelectionSet(data).build()));
        Document dataDocument = document.transform(builder -> builder.definitions(document.getDefinitions().stream()
                .map(definition -> definition == operation ? dataOperation : definition)
                .toList()));
        return Optional.of(AstPrinter.printAst(dataDocument));
    }
}
