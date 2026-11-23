package io.github.excalibase.overload;

/** Whether the connection bound is reached, for code outside this package that holds a connection longer than it must. */
public interface ConnectionPressure {

    /** True when every connection slot is taken. */
    boolean isFull();
}
