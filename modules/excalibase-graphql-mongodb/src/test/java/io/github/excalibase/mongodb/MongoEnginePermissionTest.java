package io.github.excalibase.mongodb;

class MongoEnginePermissionTest extends EnginePermissionTest {
    @Override Backend backend() { return Backend.MONGO; }
}
