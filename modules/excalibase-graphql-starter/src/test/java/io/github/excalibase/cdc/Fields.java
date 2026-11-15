package io.github.excalibase.cdc;

import java.lang.reflect.Field;

/** Sets a private field of a service under test, as the test needs the state the service reaches at runtime. */
final class Fields {

    private Fields() {
    }

    static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot set " + name, failure);
        }
    }
}
