package io.github.excalibase.mongodb;

class DocumentDbEngineTypeDriftTest extends EngineTypeDriftTest {
    @Override Backend backend() { return Backend.DOCUMENTDB; }
}
