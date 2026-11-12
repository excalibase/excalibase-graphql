package io.github.excalibase.support;

import java.lang.reflect.Field;

/** Sets a private field the app fills from configuration, for a test that builds the class by hand. */
public final class Fields {

    private Fields() {
    }

    public static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot set " + name + " on " + target.getClass().getName(), failure);
        }
    }
}
