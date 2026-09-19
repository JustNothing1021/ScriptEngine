package com.justnothing.engine.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析期反射结果缓存。
 * <p>
 * {@link Class#getMethods()} 与 {@link Class#getDeclaredFields()} 每次调用都要复制（并排序）
 * 内部数组，而解析期会对每个方法调用点全量取一次方法列表（实测一次 361 行脚本约 190 次
 * {@code getMethods()}），是解析期的显著开销。这里按 {@link Class} 缓存结果。
 * </p>
 * <p>
 * 线程安全（ConcurrentHashMap）；容量超限时整体清空，避免在反复生成动态类的场景下无界增长。
 * </p>
 */
public final class ReflectCache {

    private static final int CACHE_LIMIT = 2048;

    private static final Map<Class<?>, Method[]> METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> DECLARED_FIELDS = new ConcurrentHashMap<>();

    private ReflectCache() {
    }

    /** 缓存的 {@link Class#getMethods()}（已把声明类不可访问的方法改挂到可访问的父类型上）。 */
    public static Method[] methods(Class<?> clazz) {
        Method[] cached = METHODS.get(clazz);
        if (cached != null) {
            return cached;
        }
        Method[] raw = clazz.getMethods();
        Method[] methods = new Method[raw.length];
        for (int i = 0; i < raw.length; i++) {
            methods[i] = MethodResolver.accessible(raw[i]);
        }
        if (METHODS.size() >= CACHE_LIMIT) {
            METHODS.clear();
        }
        METHODS.put(clazz, methods);
        return methods;
    }

    /** 缓存的 {@link Class#getDeclaredFields()}。 */
    public static Field[] declaredFields(Class<?> clazz) {
        Field[] cached = DECLARED_FIELDS.get(clazz);
        if (cached != null) {
            return cached;
        }
        Field[] fields = clazz.getDeclaredFields();
        if (DECLARED_FIELDS.size() >= CACHE_LIMIT) {
            DECLARED_FIELDS.clear();
        }
        DECLARED_FIELDS.put(clazz, fields);
        return fields;
    }
}
