package com.justnothing.engine.util;

import com.justnothing.engine.ast.nodes.ClassReferenceNode;

/**
 * ASM 类型描述符工具 — 纯函数式，无状态。
 * <p>
 * 将 Java 类型转换为 JVM 字节码描述符格式：
 * <pre>
 *   int          → I
 *   String       → Ljava/lang/String;
 *   void         → V
 *   int[]        → [I
 *   java.util.Map → Ljava/util/Map;
 * </pre>
 */
public final class DescriptorUtils {

    private DescriptorUtils() {}

    // ==================== 名称转换 ====================

    /** 点分隔类名 → 斜杠分隔内部名：{@code "java.lang.Object" → "java/lang/Object"} */
    public static String toInternalName(String className) {
        return className.replace('.', '/');
    }

    /** 内部名 → 点分隔类名 */
    public static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    // ==================== 字段/参数类型描述符 ====================

    /**
     * 从 ClassReferenceNode 生成字段或参数的类型描述符。
     * <p>
     * 处理基本类型、引用类型、数组维度。
     * 泽型信息在字节码层面被擦除（raw type）。
     * </p>
     *
     * @param typeRef 类型引用节点（可能为 null）
     * @return ASM 描述符字符串，如 {@code "I"}, {@code "Ljava/lang/String;"}, {@code "[I"}
     */
    public static String fieldDescriptor(ClassReferenceNode typeRef) {
        if (typeRef == null) return "Ljava/lang/Object;"; // 未知类型默认 Object

        // 基本类型
        Class<?> resolved = typeRef.getResolvedClass();
        if (resolved != null && resolved.isPrimitive()) {
            return primitiveDescriptor(resolved);
        }

        // 引用类型 + 数组维度
        StringBuilder sb = new StringBuilder();
        int arrayDepth = typeRef.getArrayDepth();
        for (int i = 0; i < arrayDepth; i++) {
            sb.append('[');
        }

        if (resolved != null) {
            sb.append('L').append(toInternalName(resolved.getName())).append(';');
        } else {
            // 无 resolvedClass 时用 originalTypeName
            String name = typeRef.getOriginalTypeName();
            if (name == null || name.isEmpty()) name = "java.lang.Object";
            sb.append('L').append(toInternalName(name)).append(';');
        }
        return sb.toString();
    }

    /** 从 Java Class 对象获取原始类型描述符。 */
    public static String descriptor(Class<?> type) {
        if (!type.isArray() && type.isPrimitive()) {
            return primitiveDescriptor(type);
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return 'L' + toInternalName(type.getName()) + ';';
    }

    /** 方法描述符：(参数...)返回值 */
    public static String methodDescriptor(Class<?>[] paramTypes, Class<?> returnType) {
        StringBuilder sb = new StringBuilder("(");
        if (paramTypes != null) {
            for (Class<?> p : paramTypes) {
                sb.append(descriptor(p));
            }
        }
        return sb.append(')').append(descriptor(returnType)).toString();
    }

    // ==================== 基本类型映射 ====================

    public static String primitiveDescriptor(Class<?> primitiveType) {
        if (primitiveType == void.class)    return "V";
        if (primitiveType == boolean.class)  return "Z";
        if (primitiveType == byte.class)     return "B";
        if (primitiveType == char.class)     return "C";
        if (primitiveType == short.class)    return "S";
        if (primitiveType == int.class)      return "I";
        if (primitiveType == long.class)     return "J";
        if (primitiveType == float.class)    return "F";
        if (primitiveType == double.class)   return "D";
        throw new IllegalArgumentException("Not a primitive type: " + primitiveType);
    }

    /** 包装类 → 基本类型；非包装类原样返回。 */
    public static Class<?> unwrap(Class<?> type) {
        if (type == Integer.class)   return int.class;
        if (type == Long.class)      return long.class;
        if (type == Float.class)     return float.class;
        if (type == Double.class)    return double.class;
        if (type == Boolean.class)   return boolean.class;
        if (type == Byte.class)      return byte.class;
        if (type == Character.class) return char.class;
        if (type == Short.class)     return short.class;
        return type;
    }
}
