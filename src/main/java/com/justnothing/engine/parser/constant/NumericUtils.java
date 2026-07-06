package com.justnothing.engine.parser.constant;

/**
 * 数值辅助工具。
 */
final class NumericUtils {

    private NumericUtils() {
    }

    /** 判断一个数值对象是否为整数类型（无小数部分）。 */
    static boolean isInteger(Object value) {
        if (value instanceof Integer || value instanceof Long ||
            value instanceof Short || value instanceof Byte) {
            return true;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == n.longValue() && !Double.isInfinite(n.doubleValue());
        }
        return false;
    }

    static Number toNumber(Object value) {
        return (Number) value;
    }

    /**
     * 对两个数值执行运算。优先保持原始类型（Integer→Integer，Long→Long），混合时提升为 long。
     */
    static Object apply(Object a, Object b, LongOp longOp, DoubleOp doubleOp) {
        if (a instanceof Integer && b instanceof Integer) {
            // 两个 Integer 保持 Integer 类型（避免 Long/Integer 比较失败）
            return (int) longOp.apply(((Number) a).longValue(), ((Number) b).longValue());
        }
        if (isInteger(a) && isInteger(b)) {
            return longOp.apply(toNumber(a).longValue(), toNumber(b).longValue());
        }
        return doubleOp.apply(toNumber(a).doubleValue(), toNumber(b).doubleValue());
    }

    static Object applyLong(Object a, Object b, LongOp op) {
        return op.apply(toNumber(a).longValue(), toNumber(b).longValue());
    }

    static Object applyDouble(Object a, Object b, DoubleOp op) {
        return op.apply(toNumber(a).doubleValue(), toNumber(b).doubleValue());
    }

    @FunctionalInterface
    interface LongOp { long apply(long a, long b); }

    @FunctionalInterface
    interface DoubleOp { double apply(double a, double b); }
}
