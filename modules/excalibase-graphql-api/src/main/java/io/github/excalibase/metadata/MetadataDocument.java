package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.security.TokenVerifier;

/**
 * A validated document as the engine serves it: the rules and, when the document has a {@code jwt:} section,
 * the verifier built from it. They are swapped in together.
 *
 * @param verifier null when the document has no JWT section
 */
public record MetadataDocument(PermissionSet permissions, TokenVerifier verifier) {
}
