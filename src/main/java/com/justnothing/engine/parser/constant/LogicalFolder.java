package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

/**
 * 逻辑运算常量折叠：&& ||
 */
final class LogicalFolder {

    private LogicalFolder() {
    }

    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        if (!(a instanceof Boolean la) || !(b instanceof Boolean lb)) {
            return null;
        }
        return switch (op) {
            case LOGICAL_AND -> la && lb;
            case LOGICAL_OR  -> la || lb;
            default         -> null;
        };
    }
}
