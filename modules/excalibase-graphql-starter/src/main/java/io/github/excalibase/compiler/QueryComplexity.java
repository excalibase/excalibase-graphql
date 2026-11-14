package io.github.excalibase.compiler;

import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measures an operation once, before it is compiled: refuses fragment cycles and an operation that
 * expands to more selections than {@link #MAX_SELECTIONS}, and reports its depth. Each fragment is
 * measured once however often it is spread, so a chain of fragments that each spread the next twice
 * costs the fragment count, not two to the power of it.
 */
final class QueryComplexity {

    /** Selections one operation may expand to, fragments and aliases counted every time they are used. */
    static final int MAX_SELECTIONS = 1_000;

    private final Map<String, FragmentDefinition> fragments;
    private final Map<String, Measure> measured = new HashMap<>();

    private record Measure(long selections, int depth) {
    }

    private QueryComplexity(Map<String, FragmentDefinition> fragments) {
        this.fragments = fragments;
    }

    /** The operation's depth; throws {@link QueryRejectedException} for a cycle or too many selections. */
    static int measure(OperationDefinition operation, Map<String, FragmentDefinition> fragments) {
        QueryComplexity complexity = new QueryComplexity(fragments);
        complexity.measureFragments();
        Measure root = complexity.of(operation.getSelectionSet());
        if (root.selections() > MAX_SELECTIONS) {
            throw new QueryRejectedException("Query selects more than " + MAX_SELECTIONS
                    + " fields once its fragments are expanded");
        }
        return root.depth();
    }

    /** Measures every fragment children first, so a fragment is measured after what it spreads. */
    private void measureFragments() {
        Map<String, Set<String>> spreads = new HashMap<>();
        fragments.forEach((name, fragment) -> spreads.put(name, spreadsIn(fragment.getSelectionSet())));
        for (String name : postOrder(spreads)) {
            measured.put(name, of(fragments.get(name).getSelectionSet()));
        }
    }

    /** Fragment names, each after the fragments it spreads; iterative so a long chain cannot overflow the stack. */
    private static List<String> postOrder(Map<String, Set<String>> spreads) {
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> open = new HashSet<>();
        for (String start : spreads.keySet()) {
            if (done.contains(start)) continue;
            Deque<String> stack = new ArrayDeque<>();
            Deque<java.util.Iterator<String>> cursors = new ArrayDeque<>();
            stack.push(start);
            open.add(start);
            cursors.push(known(spreads, start).iterator());
            while (!stack.isEmpty()) {
                var cursor = cursors.peek();
                if (cursor.hasNext()) {
                    String next = cursor.next();
                    if (open.contains(next)) {
                        throw new QueryRejectedException("Fragment '" + next + "' spreads itself");
                    }
                    if (!done.contains(next)) {
                        stack.push(next);
                        open.add(next);
                        cursors.push(known(spreads, next).iterator());
                    }
                } else {
                    String finished = stack.pop();
                    cursors.pop();
                    open.remove(finished);
                    done.add(finished);
                    order.add(finished);
                }
            }
        }
        return order;
    }

    private static Set<String> known(Map<String, Set<String>> spreads, String name) {
        return spreads.getOrDefault(name, Set.of());
    }

    private Set<String> spreadsIn(SelectionSet selectionSet) {
        Set<String> names = new HashSet<>();
        collectSpreads(selectionSet, names);
        names.retainAll(fragments.keySet());
        return names;
    }

    private static void collectSpreads(SelectionSet selectionSet, Set<String> names) {
        if (selectionSet == null) return;
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof FragmentSpread spread) {
                names.add(spread.getName());
            } else if (selection instanceof Field field) {
                collectSpreads(field.getSelectionSet(), names);
            } else if (selection instanceof InlineFragment inline) {
                collectSpreads(inline.getSelectionSet(), names);
            }
        }
    }

    private Measure of(SelectionSet selectionSet) {
        if (selectionSet == null) return new Measure(0, 0);
        long selections = 0;
        int depth = 0;
        for (Selection<?> selection : selectionSet.getSelections()) {
            Measure part = switch (selection) {
                case Field field -> {
                    Measure child = of(field.getSelectionSet());
                    yield new Measure(1 + child.selections(), 1 + child.depth());
                }
                case InlineFragment inline -> of(inline.getSelectionSet());
                case FragmentSpread spread -> measured.getOrDefault(spread.getName(), new Measure(0, 0));
                default -> new Measure(0, 0);
            };
            selections = Math.min(Long.MAX_VALUE / 2, selections + part.selections());
            depth = Math.max(depth, part.depth());
        }
        return new Measure(selections, depth);
    }
}
