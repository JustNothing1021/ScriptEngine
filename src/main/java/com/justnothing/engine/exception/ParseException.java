package com.justnothing.engine.exception;

import com.justnothing.engine.ast.SourceLocation;

/**
 * 解析异常
 * <p>
 * 在解析脚本时抛出的异常。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class ParseException extends ScriptException {
    
    public ParseException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public ParseException(String message, SourceLocation location, ErrorCode errorCode) {
        super(message, location, errorCode);
    }
    
    public ParseException(String message, SourceLocation location, ErrorCode errorCode, Throwable cause) {
        super(message, location, errorCode, cause);
    }
}
