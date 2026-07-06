package com.justnothing.engine.util;

import com.justnothing.engine.builtins.Lambda;
import com.justnothing.engine.builtins.MethodReference;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.eval.Value;
import com.justnothing.engine.exception.ErrorCode;

import java.util.HashMap;
import java.util.Map;

public final class CastUtils {

    private CastUtils() {}

    private static final Map<Class<?>, Class<?>> WRAPPER_TO_PRIMITIVE = new HashMap<>();
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();

    static {
        WRAPPER_TO_PRIMITIVE.put(Boolean.class, boolean.class);
        WRAPPER_TO_PRIMITIVE.put(Byte.class, byte.class);
        WRAPPER_TO_PRIMITIVE.put(Short.class, short.class);
        WRAPPER_TO_PRIMITIVE.put(Character.class, char.class);
        WRAPPER_TO_PRIMITIVE.put(Integer.class, int.class);
        WRAPPER_TO_PRIMITIVE.put(Long.class, long.class);
        WRAPPER_TO_PRIMITIVE.put(Float.class, float.class);
        WRAPPER_TO_PRIMITIVE.put(Double.class, double.class);
        for (Map.Entry<Class<?>, Class<?>> e : WRAPPER_TO_PRIMITIVE.entrySet()) {
            PRIMITIVE_TO_WRAPPER.put(e.getValue(), e.getKey());
        }
    }

    public static Object castObject(Object obj, Class<?> targetType) {
        if (obj == null) return null;
        if (targetType == null || targetType == Object.class) return obj;
        if (targetType.isInstance(obj)) return obj;

        if (targetType.isInterface()) {
            if (obj instanceof Lambda lambda) {
                return lambda.asInterface(targetType);
            }
            if (obj instanceof MethodReference ref) {
                return ref.asInterface(targetType);
            }
        }

        if (targetType == String.class) {
            return stringCast(obj);
        }

        if (targetType.isPrimitive()) {
            return primitiveCast(obj, targetType);
        }

        if (WRAPPER_TO_PRIMITIVE.containsKey(targetType)) {
            return wrapperCast(obj, WRAPPER_TO_PRIMITIVE.get(targetType));
        }

        if (obj instanceof String s && isNumericType(targetType)) {
            return parseNumericString(s, targetType);
        }

        try {
            return targetType.cast(obj);
        } catch (ClassCastException e) {
            throw new EvalException("Cannot cast " + obj.getClass().getSimpleName() + " to " + targetType.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
        }
    }

    public static Value castValue(Value value, Class<?> targetType) {
        Object obj = value.asJavaObject();
        Object result = castObject(obj, targetType);
        return Value.of(result);
    }

    private static Object stringCast(Object obj) {
        if (obj instanceof Value.VoidValue) {
            throw new EvalException("Cannot convert void to String", ErrorCode.EVAL_TYPE_MISMATCH);
        }
        return obj.toString();
    }

    private static Object primitiveCast(Object obj, Class<?> target) {
        if (obj instanceof Number n) {
            return numericConversion(n, target);
        }
        if (obj instanceof Character c) {
            return numericConversion((int) c, target);
        }
        if (obj instanceof Boolean b) {
            if (target == boolean.class) return b;
            throw new EvalException("Cannot cast boolean to " + target.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
        }
        if (obj instanceof String s) {
            return parseNumericString(s, target);
        }
        Class<?> wrapped = PRIMITIVE_TO_WRAPPER.get(target);
        if (wrapped != null && wrapped.isInstance(obj)) {
            return unbox(obj, target);
        }
        throw new EvalException("Cannot cast " + obj.getClass().getSimpleName() + " to " + target.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
    }

    private static Object wrapperCast(Object obj, Class<?> primitiveTarget) {
        if (obj instanceof Number n) {
            return numericConversion(n, primitiveTarget);
        }
        if (obj instanceof Character c) {
            return numericConversion((int) c, primitiveTarget);
        }
        if (obj instanceof Boolean b && primitiveTarget == boolean.class) {
            return b;
        }
        if (WRAPPER_TO_PRIMITIVE.get(obj.getClass()) == primitiveTarget) {
            return unbox(obj, primitiveTarget);
        }
        if (obj instanceof String s) {
            return parseNumericString(s, primitiveTarget);
        }
        throw new EvalException("Cannot cast " + obj.getClass().getSimpleName() + " to " + PRIMITIVE_TO_WRAPPER.get(primitiveTarget).getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
    }

    private static Object numericConversion(Number n, Class<?> target) {
        if (target == int.class) return n.intValue();
        if (target == long.class) return n.longValue();
        if (target == double.class) return n.doubleValue();
        if (target == float.class) return n.floatValue();
        if (target == byte.class) return n.byteValue();
        if (target == short.class) return n.shortValue();
        if (target == char.class) return (char) n.intValue();
        throw new EvalException("Cannot cast number to " + target.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
    }

    private static Object parseNumericString(String s, Class<?> target) {
        try {
            if (target == int.class || target == Integer.class) return Integer.parseInt(s);
            if (target == long.class || target == Long.class) return Long.parseLong(s);
            if (target == double.class || target == Double.class) return Double.parseDouble(s);
            if (target == float.class || target == Float.class) return Float.parseFloat(s);
            if (target == byte.class || target == Byte.class) return Byte.parseByte(s);
            if (target == short.class || target == Short.class) return Short.parseShort(s);
        } catch (NumberFormatException e) {
            throw new EvalException("Cannot parse string '" + s + "' as " + target.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
        }
        throw new EvalException("Cannot parse string as " + target.getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
    }

    private static Object unbox(Object obj, Class<?> primitiveTarget) {
        if (primitiveTarget == int.class) return ((Integer) obj).intValue();
        if (primitiveTarget == long.class) return ((Long) obj).longValue();
        if (primitiveTarget == double.class) return ((Double) obj).doubleValue();
        if (primitiveTarget == float.class) return ((Float) obj).floatValue();
        if (primitiveTarget == byte.class) return ((Byte) obj).byteValue();
        if (primitiveTarget == short.class) return ((Short) obj).shortValue();
        if (primitiveTarget == char.class) return ((Character) obj).charValue();
        if (primitiveTarget == boolean.class) return ((Boolean) obj).booleanValue();
        return obj;
    }

    private static boolean isNumericType(Class<?> type) {
        return type == int.class || type == Integer.class ||
               type == long.class || type == Long.class ||
               type == double.class || type == Double.class ||
               type == float.class || type == Float.class ||
               type == byte.class || type == Byte.class ||
               type == short.class || type == Short.class;
    }
}
