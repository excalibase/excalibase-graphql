package io.github.excalibase.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Saves the {@code db} source as a compare-and-set in one bounded transaction (the same {@code SET LOCAL} timeouts
 * as a read). The newest row's hash is compared with the version the writer started from; the new document is then
 * added as row {@code version + 1}. The primary key is the guard: of two writers that saw the same newest row, one
 * commits and the other waits on the duplicate key and gets it, which is a conflict. No row is locked, so a user
 * with SELECT and INSERT on the table can save; UPDATE is not needed. Rows are added, not updated, so older
 * documents stay as history. No column is added to the table.
 */
final class DbDocumentWriter implements MetadataWriter {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NEWEST = "SELECT version, document FROM " + DbDocumentLoader.TABLE
            + " ORDER BY version DESC LIMIT 1";
    private static final String ADD = "INSERT INTO " + DbDocumentLoader.TABLE + " (version, document) VALUES (?, ?)";

    private final DbDocumentLoader loader;

    DbDocumentWriter(DbDocumentLoader loader) {
        this.loader = loader;
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public String write(String text, String expectedVersion) throws IOException, WriteConflictException {
        try (Connection connection = loader.open()) {
            connection.setAutoCommit(false);
            loader.bound(connection);
            return committed(connection, text, expectedVersion);
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new WriteConflictException(currentVersion());
            }
            throw new IOException(DbDocumentLoader.describe(e));
        }
    }

    private static String committed(Connection connection, String text, String expectedVersion)
            throws SQLException, WriteConflictException {
        try {
            String version = add(connection, text, expectedVersion);
            connection.commit();
            return version;
        } catch (WriteConflictException e) {
            connection.rollback();
            throw e;
        }
    }

    private static String add(Connection connection, String text, String expectedVersion)
            throws SQLException, WriteConflictException {
        long newest = 0;
        String current = NO_DOCUMENT;
        try (PreparedStatement statement = connection.prepareStatement(NEWEST);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                newest = rows.getLong(1);
                current = FileMetadataSource.sha256(rows.getString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!current.equals(expectedVersion)) {
            throw new WriteConflictException(current);
        }
        try (PreparedStatement statement = connection.prepareStatement(ADD)) {
            statement.setLong(1, newest + 1);
            statement.setString(2, text);
            statement.executeUpdate();
        }
        return FileMetadataSource.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private String currentVersion() {
        try {
            return loader.load().version();
        } catch (IOException _) {
            return "unknown";
        }
    }
}
