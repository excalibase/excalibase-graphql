package io.github.excalibase.permissions;

/**
 * A permission document that breaks the contract grammar. The whole document is refused:
 * applying the valid parts of it could grant what the invalid part was meant to restrict.
 */
public class PermissionDocumentException extends RuntimeException {

    public PermissionDocumentException(String message) {
        super(message);
    }
}
