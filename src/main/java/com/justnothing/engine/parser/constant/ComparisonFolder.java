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
            case EQUAL                  -> equalsValue(a, b);
            case NOT_EQUAL              -> !equalsValue(a, b);
            case LESS_THAN              -> compare(a, b) < 0;
            case GREATER_THAN           -> compare(a, b) > 0;
            case LESS_THAN_OR_EQUAL     -> compare(a, b) <= 0;
            case GREATER_THAN_OR_EQUAL  -> compare(a, b) >= 0;
            default                    -> null;
        };
    }

    /**
     * 相等判断：两个操作数都是数值时按 Java 的二元数值提升比较（{@code 1 == 1L}、{@code 1 == 1.0}
     * 为真），否则退化为 {@code Objects.equals}。
     * <p>
     * 不能对数值直接用 {@code Objects.equals}：字面量在这里是原生包装对象，
     * {@code Integer.valueOf(1).equals(Long.valueOf(1))} 恒为 false，
     * 会把 {@code 1 == 1L} 折叠成常量 false —— 与运行期语义（数值提升）也不一致。
     * </p>
     */
    private static boolean equalsValue(Object a, Object b) {
        if (isNumeric(a) && isNumeric(b)) {
            // 浮点分支直接用原生 == 比较，保持 NaN != NaN 的语义
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return toDouble(a) == toDouble(b);
            }
            if (a instanceof Long || b instanceof Long) return toLong(a) == toLong(b);
            return toInt(a) == toInt(b);
        }
        return Objects.equals(a, b);
    }

    /** 比较两个操作数；数值类型按 Java 的二元数值提升。 */
    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (isNumeric(a) && isNumeric(b)) {
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(toDouble(a), toDouble(b));
            }
            if (a instanceof Long || b instanceof Long) return Long.compare(toLong(a), toLong(b));
            return Integer.compare(toInt(a), toInt(b));
        }
        // 只有同类 Comparable 才能直接 compareTo：不同类（如 Integer vs Long）走
        // compareTo 会抛 ClassCastException，那种情况交给运行期报可读的类型错误
        if (a instanceof Comparable<?> ca && a.getClass() == b.getClass()) {
            return ((Comparable<Object>) ca).compareTo(b);
        }
        throw new IllegalArgumentException(
                "Cannot compare " + a.getClass() + " with " + b.getClass());
    }

    /** Java 里 char 也是数值类型（参与二元数值提升时提升为 int）。 */
    private static boolean isNumeric(Object o) {
        return o instanceof Number || o instanceof Character;
    }

    private static int toInt(Object o) {
        return o instanceof Character c ? c : ((Number) o).intValue();
    }

    private static long toLong(Object o) {
        return o instanceof Character c ? c : ((Number) o).longValue();
    }

    private static double toDouble(Object o) {
        return o instanceof Character c ? c : ((Number) o).doubleValue();
    }
}
