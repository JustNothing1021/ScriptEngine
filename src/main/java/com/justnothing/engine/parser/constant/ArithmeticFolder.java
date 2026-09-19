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
        // 浮点除零是 IEEE 754 定义的 Infinity / NaN，不报错 —— 必须和运行期（OperatorRegistry
        // 的 Number/Number 除法）保持一致，否则同一表达式折叠与否会得到不同结果
        if (isFloatingPoint(a) || isFloatingPoint(b)) {
            return NumericUtils.applyDouble(a, b, (x, y) -> x / y);
        }
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return NumericUtils.apply(a, b, (x, y) -> x / y, (x, y) -> x / y);
    }

    private static Object foldMod(Object a, Object b) {
        if (isFloatingPoint(a) || isFloatingPoint(b)) {
            return NumericUtils.applyDouble(a, b, (x, y) -> x % y);
        }
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return NumericUtils.apply(a, b, (x, y) -> x % y, (x, y) -> x % y);
    }

    /** 注意不能用 {@link NumericUtils#isInteger}：它对 1.0 也返回 true。 */
    private static boolean isFloatingPoint(Object value) {
        return value instanceof Double || value instanceof Float;
    }

    private static Object foldIntDiv(Object a, Object b) {
        if (NumericUtils.toNumber(b).doubleValue() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return Math.floorDiv(NumericUtils.toNumber(a).longValue(), NumericUtils.toNumber(b).longValue());
    }
}
