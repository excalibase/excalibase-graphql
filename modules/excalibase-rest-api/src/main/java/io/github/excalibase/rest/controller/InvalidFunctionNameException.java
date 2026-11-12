package io.github.excalibase.rest.controller;

/** An rpc function name that is not an identifier. */
class InvalidFunctionNameException extends RuntimeException {

    InvalidFunctionNameException(String name) {
        super("Invalid function name: " + name);
    }
}
