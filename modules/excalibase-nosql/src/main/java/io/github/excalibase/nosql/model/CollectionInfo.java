package io.github.excalibase.nosql.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CollectionInfo {

    private final Map<String, CollectionSchema> collections = new ConcurrentHashMap<>();

    public void addCollection(String name, CollectionSchema schema) {
        collections.put(name, schema);
    }

    public Optional<CollectionSchema> getCollection(String name) {
        return Optional.ofNullable(collections.get(name));
    }

    public boolean hasCollection(String name) {
        return collections.containsKey(name);
    }

    public Set<String> getCollectionNames() {
        return Set.copyOf(collections.keySet());
    }

    public void removeCollection(String name) {
        collections.remove(name);
    }

    public void clearAll() {
        collections.clear();
    }
}
