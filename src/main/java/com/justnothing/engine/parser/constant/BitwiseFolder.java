package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

/**
 * 位运算常量折叠：& | ^ ~
 */
final class BitwiseFolder {

    private BitwiseFolder() {
    }

    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        return switch (op) {
            case BITWISE_AND  -> NumericUtils.applyLong(a, b, (x, y) -> x & y);
            case BITWISE_OR   -> NumericUtils.applyLong(a, b, (x, y) -> x | y);
            case BITWISE_XOR  -> NumericUtils.applyLong(a, b, (x, y) -> x ^ y);
            default          -> null;
        };
    }
}
