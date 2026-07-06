package com.justnothing.engine.exception;

import com.justnothing.engine.ast.SourceLocation;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 脚本异常基类
 * <p>
 * 所有脚本相关异常的基类，包含错误位置和错误代码。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public abstract class ScriptException extends RuntimeException {
    private SourceLocation location;

    private final ErrorCode errorCode;
    private List<String> scriptCallStack = new ArrayList<>();

    public ScriptException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ScriptException(String message, SourceLocation location, ErrorCode errorCode) {
        super(message);
        this.location = location;
        this.errorCode = errorCode;
    }
    
    public ScriptException(String message, SourceLocation location, ErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.location = location;
        this.errorCode = errorCode;
    }

    public int getLine() {
        return location != null ? location.getLine() : -1;
    }

    public int getColumn() {
        return location != null ? location.getColumn() : -1;
    }

    @NotNull
    public String getSource() {
        return location != null ? location.getSource() : "<unlocated>";
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
    
    public void setScriptCallStack(List<String> callStack) {
        this.scriptCallStack = callStack != null ? new ArrayList<>(callStack) : new ArrayList<>();
    }
    
    public List<String> getScriptCallStack() {
        return Collections.unmodifiableList(scriptCallStack);
    }
    
    public boolean hasScriptCallStack() {
        return !scriptCallStack.isEmpty();
    }
    
    public String formatScriptCallStack() {
        if (scriptCallStack.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Script CallStack:\n");
        for (int i = scriptCallStack.size() - 1; i >= 0; i--) {
            sb.append("  at ").append(scriptCallStack.get(i)).append("\n");
        }
        return sb.toString();
    }
    
    @Override
    public String getMessage() {
        return String.format(
                Locale.getDefault(),
                "%s (In file %s, Line %d, Column %d) [%s]",
                super.getMessage(), getSource(), getLine(), getColumn(), errorCode.name());
    }
}
