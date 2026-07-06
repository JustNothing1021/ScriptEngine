package com.justnothing.engine.exception;

import com.justnothing.engine.ast.ASTNode;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 求值异常
 * <p>
 * 在求值AST节点时抛出的异常。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class EvaluationException extends ScriptException {
    private final ASTNode node;

    public EvaluationException(String message, ErrorCode errorCode, ASTNode node) {
        super(message, node != null ? node.getLocation() : null, errorCode);
        this.node = node;
    }


    public EvaluationException(String message, ErrorCode errorCode, @NotNull Throwable cause, ASTNode node) {
        super(message, node != null ? node.getLocation() : null, errorCode, cause);
        this.node = node;
    }

    public @Nullable ASTNode getNode() {
        return node;
    }
}
