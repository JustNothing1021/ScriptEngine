package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.nodes.BinaryOpNode;

/**
 * 算术运算常量折叠：+ - * / % ** //
 */
final class ArithmeticFolder {

    private ArithmeticFolder() {
    }

    static Object fold(BinaryOpNode.Operator op, Object a, Object b) {
        return switch (op) {
            case ADD       -> foldAdd(a, b);
            case SUBTRACT  -> NumericUtils.apply(a, b, (x, y) -> x - y, (x, y) -> x - y);
            case MULTIPLY  -> NumericUtils.apply(a, b, (x, y) -> x * y, (x, y) -> x * y);
            case DIVIDE    -> foldDiv(a, b);
            case MODULO    -> foldMod(a, b);
            case POWER     -> NumericUtils.applyDouble(a, b, Math::pow);
            case INT_DIVIDE -> foldIntDiv(a, b);
            default       -> null;
        };
    }

    private static Object foldAdd(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return String.valueOf(a) + b;
        }
        return NumericUtils.apply(a, b, Long::sum, Double::sum);
    }

    private static Object foldDiv(Object a, Object b) {
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return NumericUtils.apply(a, b, (x, y) -> x / y, (x, y) -> x / y);
    }

    private static Object foldMod(Object a, Object b) {
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return NumericUtils.apply(a, b, (x, y) -> x % y, (x, y) -> x % y);
    }

    private static Object foldIntDiv(Object a, Object b) {
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return Math.floorDiv(NumericUtils.toNumber(a).longValue(), NumericUtils.toNumber(b).longValue());
    }
}
