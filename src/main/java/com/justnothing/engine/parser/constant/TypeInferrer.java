package com.justnothing.engine.parser.constant;

/**
 * 常量折叠结果类型推断。
 */
final class TypeInferrer {

    private TypeInferrer() {
    }

    /**
     * 根据运行时值推断 Java 类型。
     */
    static Class<?> infer(Object result) {
        if (result == null) return null;
        if (result instanceof Integer) return int.class;
        if (result instanceof Long) return long.class;
        if (result instanceof Double) return double.class;
        if (result instanceof Float) return float.class;
        if (result instanceof Boolean) return boolean.class;
        if (result instanceof Character) return char.class;
        if (result instanceof Byte) return byte.class;
        return result.getClass();
    }
}
