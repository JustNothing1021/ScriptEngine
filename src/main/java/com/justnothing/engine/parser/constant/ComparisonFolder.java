package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

import java.util.Objects;

/**
 * 比较运算常量折叠：== != < > <= >= <=>
 */
final class ComparisonFolder {

    private ComparisonFolder() {
    }

    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        return switch (op) {
            case EQUAL                  -> Objects.equals(a, b);
            case NOT_EQUAL              -> !Objects.equals(a, b);
            case LESS_THAN              -> compare(a, b) < 0;
            case GREATER_THAN           -> compare(a, b) > 0;
            case LESS_THAN_OR_EQUAL     -> compare(a, b) <= 0;
            case GREATER_THAN_OR_EQUAL  -> compare(a, b) >= 0;
            default                    -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (a instanceof Comparable<?> ca && b instanceof Comparable<?>) {
            return ((Comparable<Object>) ca).compareTo(b);
        }
        throw new IllegalArgumentException(
                "Cannot compare " + a.getClass() + " with " + b.getClass());
    }
}
