package io.github.excalibase.schema;

/** The project's database runs no change stream, so a subscription is refused with this message. */
public class SubscriptionsUnsupportedException extends RuntimeException {

    public SubscriptionsUnsupportedException(String message) {
        super(message);
    }
}
