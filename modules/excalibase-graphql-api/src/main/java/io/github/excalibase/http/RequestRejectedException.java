package io.github.excalibase.http;

/** A request target the firewall refuses before any route sees it. */
public class RequestRejectedException extends RuntimeException {

    public RequestRejectedException(String reason) {
        super(reason);
    }
}
