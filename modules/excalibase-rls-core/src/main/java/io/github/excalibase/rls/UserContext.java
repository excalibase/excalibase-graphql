package io.github.excalibase.rls;

import java.util.Set;

public interface UserContext {

    String userId();

    String tenantId();

    Set<String> roles();

    Set<String> groupIds();

    default Object resolveVariable(String name) {
        return null;
    }
}
