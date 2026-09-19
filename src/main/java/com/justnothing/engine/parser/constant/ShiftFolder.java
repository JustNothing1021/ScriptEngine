package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

/**
 * 移位运算常量折叠：<< >> >>>
 */
final class ShiftFolder {

    private ShiftFolder() {
    }

    /**
     * 移位折叠。
     * <p>
     * 结果类型与位宽都跟随左操作数（Java 只对左操作数做一元数值提升）：
     * {@code 1 << 2} 是 {@code int}，{@code 1L << 2} 是 {@code long}。
     * 位宽必须跟随，否则 {@code -1 >>> 28} 会按 long 算出 268435455 而不是 int 的 15。
     * </p>
     */
    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        Number left = NumericUtils.toNumber(a);
        int distance = NumericUtils.toNumber(b).intValue();

        if (left instanceof Integer) {
            int value = left.intValue();
            return switch (op) {
                case LEFT_SHIFT           -> value << distance;
                case RIGHT_SHIFT          -> value >> distance;
                case UNSIGNED_RIGHT_SHIFT -> value >>> distance;
                default                   -> null;
            };
        }

        long value = left.longValue();
        return switch (op) {
            case LEFT_SHIFT           -> value << distance;
            case RIGHT_SHIFT          -> value >> distance;
            case UNSIGNED_RIGHT_SHIFT -> value >>> distance;
            default                   -> null;
        };
    }
}
