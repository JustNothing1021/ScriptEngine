package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

/**
 * 移位运算常量折叠：<< >> >>>
 */
final class ShiftFolder {

    private ShiftFolder() {
    }

    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        return switch (op) {
            case LEFT_SHIFT               -> NumericUtils.applyLong(a, b, (x, y) -> x << y);
            case RIGHT_SHIFT              -> NumericUtils.applyLong(a, b, (x, y) -> x >> y);
            case UNSIGNED_RIGHT_SHIFT     -> NumericUtils.applyLong(a, b, (x, y) -> x >>> y);
            default                      -> null;
        };
    }
}
