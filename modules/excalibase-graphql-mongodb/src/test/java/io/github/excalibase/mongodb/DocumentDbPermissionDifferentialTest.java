package io.github.excalibase.mongodb;

class DocumentDbPermissionDifferentialTest extends PermissionDifferentialTest {
    @Override Backend backend() { return Backend.DOCUMENTDB; }
}
