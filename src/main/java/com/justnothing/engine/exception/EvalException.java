package com.justnothing.engine.exception;

public class EvalException extends RuntimeException {
    private final ErrorCode errorCode;

    public EvalException(String message) {
        super(message);
        this.errorCode = ErrorCode.EVAL_ERROR;
    }

    public EvalException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public EvalException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.EVAL_ERROR;
    }

    public EvalException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
