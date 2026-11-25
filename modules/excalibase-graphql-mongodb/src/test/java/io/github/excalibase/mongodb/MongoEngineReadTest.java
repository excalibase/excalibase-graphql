package io.github.excalibase.mongodb;

class MongoEngineReadTest extends EngineReadTest {
    @Override Backend backend() { return Backend.MONGO; }
}
