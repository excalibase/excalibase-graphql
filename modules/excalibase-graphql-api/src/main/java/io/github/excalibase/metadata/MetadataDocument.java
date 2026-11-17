package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionSet;

import java.util.Optional;

/** A validated metadata document: its format, the project it is for, the optional JWT section and the rules. */
public record MetadataDocument(int format, String projectId, Optional<JwtSection> jwt, PermissionSet permissions) {
}
