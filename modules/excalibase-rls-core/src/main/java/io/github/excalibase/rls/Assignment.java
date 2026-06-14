package io.github.excalibase.rls;

import java.util.Objects;

public record Assignment(TargetType targetType, String targetId) {
    public Assignment {
        Objects.requireNonNull(targetType, "targetType");
        if (targetType != TargetType.ALL && (targetId == null || targetId.isBlank())) {
            throw new IllegalArgumentException("targetId required when targetType != ALL");
        }
    }

    public static Assignment all() {
        return new Assignment(TargetType.ALL, null);
    }

    public static Assignment role(String roleName) {
        return new Assignment(TargetType.ROLE, roleName);
    }

    public static Assignment user(String userId) {
        return new Assignment(TargetType.USER, userId);
    }

    public static Assignment group(String groupId) {
        return new Assignment(TargetType.GROUP, groupId);
    }
}
