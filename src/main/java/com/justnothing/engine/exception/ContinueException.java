package com.justnothing.engine.exception;

public class ContinueException extends RuntimeException {
    public ContinueException() {
        super("Continue statement");
    }
}
