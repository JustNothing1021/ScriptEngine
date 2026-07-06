package com.justnothing.engine.preprocessor;

public class PreprocessorException extends RuntimeException {

    public PreprocessorException(String message) {
        super(message);
    }

    public PreprocessorException(String message, Throwable cause) {
        super(message, cause);
    }
}
