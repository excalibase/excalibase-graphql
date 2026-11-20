package io.github.excalibase.mongodb;

class MongoEngineTypeDriftTest extends EngineTypeDriftTest {
    @Override Backend backend() { return Backend.MONGO; }
}
