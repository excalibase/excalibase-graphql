package io.github.excalibase.rest.controller;

/** An rpc function name that is not an identifier. */
class InvalidFunctionNameException extends RuntimeException {

    // Fixed text: the name comes from the caller and must not reach the logs.
    InvalidFunctionNameException() {
        super("Invalid function name");
    }
}
