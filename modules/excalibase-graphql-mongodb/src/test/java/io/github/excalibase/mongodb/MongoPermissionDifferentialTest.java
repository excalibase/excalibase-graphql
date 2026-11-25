package io.github.excalibase.mongodb;

class MongoPermissionDifferentialTest extends PermissionDifferentialTest {
    @Override Backend backend() { return Backend.MONGO; }
}
