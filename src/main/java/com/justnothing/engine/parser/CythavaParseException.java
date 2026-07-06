package com.justnothing.engine.parser;

import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.exception.ErrorCode;

import java.util.Locale;

/**
 * Cythava 解析器专用异常。
 * <p>
 * 封装解析过程中的语法错误和语义错误，携带位置信息以便生成精确的错误消息。
 * </p>
 */
public class CythavaParseException extends Exception {

    private final transient SourceLocation location;
    private final ErrorCode errorCode;
    private boolean sematicError = false;

    public CythavaParseException(String message, SourceLocation location) {
        this(message, location, ErrorCode.PARSE_INVALID_SYNTAX);
    }

    public CythavaParseException(String message, SourceLocation location, ErrorCode errorCode) {
        super(formatMessage(message, location));
        this.location = location;
        this.errorCode = errorCode;
    }

    public CythavaParseException(String message, SourceLocation location, ErrorCode errorCode, boolean isSematicError) {
        super(formatMessage(message, location));
        this.location = location;
        this.sematicError = isSematicError;
        this.errorCode = errorCode;
    }
    public SourceLocation getLocation() {
        return location;
    }

    public boolean isSemanticError() {
        return sematicError;
    }
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static String formatMessage(String message, SourceLocation loc) {
        if (loc == null) {
            return message;
        }
        return String.format(Locale.getDefault(), "%s (at line %d, column %d)", message, loc.getLine(), loc.getColumn());
    }
}
