package com.justnothing.engine.exception;


/**
 * {@code yield} 语句的控制流异常。
 * <p>
 * 与 {@link ReturnException} 同构：求值到 {@code yield expr} 时携带值向上抛出，
 * 由最近的 switch（表达式）求值捕获，从而让 {@code yield} 能穿过嵌套块/循环。
 * </p>
 */
public class YieldException extends RuntimeException {
    private final Object value;

    public YieldException(Object value) {
        super("Yield statement");
        this.value = value;
    }

    public Object getValue() {
        return value;
    }
}
