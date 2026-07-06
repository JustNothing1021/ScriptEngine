package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.UnaryOpNode;

/**
 * 一元运算常量折叠：+ - ! ~
 */
final class UnaryFolder {

    private UnaryFolder() {
    }

    static Object fold(UnaryOpNode.Operator op, Object value) {
        if (value instanceof Number n) {
            return switch (op) {
                case POSITIVE     -> value;
                case NEGATIVE     -> negate(n, value);
                case LOGICAL_NOT  -> n.intValue() == 0;
                case BITWISE_NOT  -> bitwiseNot(n, value);
                default           -> null;
            };
        }
        if (value instanceof Boolean b) {
            return switch (op) {
                case LOGICAL_NOT -> !b;
                default         -> null;
            };
        }
        return null;
    }


    // 防止Java给三元表达式里的东西全都提升成double了
    private static Object negate(Number n, Object value) {
        if (value instanceof Integer) {
            return -(n.intValue());
        }
        if (value instanceof Long) {
            return -(n.longValue());
        }
        if (value instanceof Float) {
            return -(n.floatValue());
        }
        if (value instanceof Double) {
            return -(n.doubleValue());
        }
        if (value instanceof Short) {
            return -(n.shortValue());
        }
        if (value instanceof Byte) {
            return -(n.byteValue());
        }
        return -(n.doubleValue());
    }

    private static Object bitwiseNot(Number n, Object value) {
        if (value instanceof Integer) {
            return ~n.intValue();
        }
        if (value instanceof Long) {
            return ~n.longValue();
        }
        if (value instanceof Short) {
            return (short) ~n.shortValue();
        }
        if (value instanceof Byte) {
            return (byte) ~n.byteValue();
        }
        return ~n.longValue();
    }
}
