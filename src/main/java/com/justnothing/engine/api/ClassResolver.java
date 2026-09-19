package com.justnothing.engine.api;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClassResolver {

    private static final List<ClassLoader> registeredLoaders = new CopyOnWriteArrayList<>();
    private static ClassLoader primaryClassLoader = null;
    private static final Object loaderLock = new Object();
    private static final Map<String, Object> classCache = new ConcurrentHashMap<>();
    /**
     * 带 import 解析的独立缓存。
     * <p>直接查找（findClass）失败的黑名单不应阻断 import 解析：简单名（如 String）
     * 直接查找必然失败，但可通过 java.lang.* 等通配符 import 找到。若两者共用
     * 同一黑名单，resolveClass 先 findClass 后 findClassWithImports 的顺序会导致
     * findClass 先写入 NOT_FOUND，findClassWithImports 直接命中黑名单而永远无法
     * 通过 import 解析。因此这里使用独立的缓存，避免两类查找互相污染。
     * <p>key 中还包含 import 集合的签名：同一个类名在不同 import 集合下结果可能不同
     * （新增 import 后原本找不到的简单名可能变得可解析），若不按 import 分区，
     * 跨上下文会命中过期的"未找到"结论，从而迫使调用方每次 addImport 都清空整个
     * 黑名单（会造成 forName 次数按 import 条数成倍放大）。
     */
    private static final Map<String, Object> importClassCache = new ConcurrentHashMap<>();
    /** importClassCache 的容量上限，超过时整体清空，避免无界增长。 */
    private static final int IMPORT_CACHE_LIMIT = 4096;
    /** 哨兵对象，标记"已查找但不存在"的类（黑名单） */
    private static final Object NOT_FOUND = new Object();

    public static void clearClassCache() {
        classCache.clear();
        importClassCache.clear();
    }

    /** 仅清空黑名单（找不到的类的缓存），保留已找到的类的缓存 */
    public static void clearBlacklist() {
        classCache.entrySet().removeIf(e -> e.getValue() == NOT_FOUND);
        importClassCache.entrySet().removeIf(e -> e.getValue() == NOT_FOUND);
    }

    public static int getCacheSize() {
        return classCache.size() + importClassCache.size();
    }

    public static void registerClassLoader(ClassLoader loader) {
        if (loader == null) return;
        if (!registeredLoaders.contains(loader)) {
            registeredLoaders.add(loader);
        }
    }

    public static void setPrimaryClassLoader(ClassLoader loader) {
        synchronized (loaderLock) {
            primaryClassLoader = loader;
            registerClassLoader(loader);
        }
    }

    public static ClassLoader getPrimaryClassLoader() {
        return primaryClassLoader;
    }

    public static boolean isTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == Void.class && actual == Void.class) return true;
        if (actual == Void.class) return !expected.isPrimitive();
        if (expected.isPrimitive()) return isPrimitiveWrapperMatch(expected, actual);
        return expected.isAssignableFrom(actual);
    }

    private static boolean isPrimitiveWrapperMatch(Class<?> primitive, Class<?> wrapper) {
        if (primitive == int.class) return wrapper == Integer.class;
        if (primitive == long.class) return wrapper == Long.class;
        if (primitive == float.class) return wrapper == Float.class;
        if (primitive == double.class) return wrapper == Double.class;
        if (primitive == boolean.class) return wrapper == Boolean.class;
        if (primitive == char.class) return wrapper == Character.class;
        if (primitive == byte.class) return wrapper == Byte.class;
        if (primitive == short.class) return wrapper == Short.class;
        return false;
    }

    public static boolean isApplicableArgs(Class<?>[] methodArgsTypes, List<Class<?>> usingArgTypes, boolean isVarArgs) {
        if (isVarArgs) {
            if (methodArgsTypes.length == 0) return false;
            Class<?> varArgsType = methodArgsTypes[methodArgsTypes.length - 1];
            if (varArgsType.isArray()) {
                Class<?> varArgsComponentType = varArgsType.getComponentType();
                int fixedParamCount = methodArgsTypes.length - 1;
                if (usingArgTypes.size() < fixedParamCount) return false;
                for (int i = 0; i < fixedParamCount; i++) {
                    if (!isTypeCompatible(methodArgsTypes[i], usingArgTypes.get(i))) return false;
                }
                for (int i = fixedParamCount; i < usingArgTypes.size(); i++) {
                    if (!isTypeCompatible(varArgsComponentType, usingArgTypes.get(i))) return false;
                }
                return true;
            }
        }
        if (methodArgsTypes.length != usingArgTypes.size()) return false;
        for (int i = 0; i < methodArgsTypes.length; i++) {
            if (!isTypeCompatible(methodArgsTypes[i], usingArgTypes.get(i))) return false;
        }
        return true;
    }

    public static boolean isApplicableArgs(Class<?>[] methodArgsTypes, Class<?>[] usingArgTypes, boolean isVarArgs) {
        return isApplicableArgs(methodArgsTypes, Arrays.asList(usingArgTypes), isVarArgs);
    }

    public static Class<?> findClass(String className) {
        return findClass(className, null);
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        switch (className) {
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "boolean": return boolean.class;
            case "char": return char.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "void": return void.class;
        }

        // 查缓存
        Object cached = classCache.get(className);
        if (cached != null) return cached == NOT_FOUND ? null : (Class<?>) cached;

        Class<?> clazz = findClassInternal(className, classLoader);
        if (clazz != null) {
            classCache.put(className, clazz);
            return clazz;
        }

        if (className.contains(".")) {
            clazz = tryNestedVariants(className, classLoader);
            if (clazz != null) {
                classCache.put(className, clazz);
                return clazz;
            }
        }

        // 黑名单缓存
        classCache.put(className, NOT_FOUND);
        return null;
    }

    public static Class<?> findClassWithImports(String className, ClassLoader classLoader, List<String> imports) {
        String cacheKey = importCacheKey(className, imports);
        Object cached = importClassCache.get(cacheKey);
        if (cached != null) return cached == NOT_FOUND ? null : (Class<?>) cached;

        Class<?> result = findClassWithImportsInternal(className, classLoader, imports);
        if (importClassCache.size() >= IMPORT_CACHE_LIMIT) {
            importClassCache.clear();
        }
        importClassCache.put(cacheKey, result != null ? result : NOT_FOUND);
        return result;
    }

    /**
     * 构造 import 感知的缓存键：{@code 类名 \0 import 签名}。
     * <p>签名是 import 列表的精确串联，保证不同 import 集合互不命中。
     */
    private static String importCacheKey(String className, List<String> imports) {
        StringBuilder sb = new StringBuilder(className.length() + 16 * imports.size() + 1);
        sb.append(className).append('\u0000');
        for (int i = 0; i < imports.size(); i++) {
            sb.append(imports.get(i)).append('\u0001');
        }
        return sb.toString();
    }

    private static Class<?> findClassWithImportsInternal(String className, ClassLoader classLoader, List<String> imports) {
        switch (className) {
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "boolean": return boolean.class;
            case "char": return char.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "void": return void.class;
        }

        if (className.contains(".")) {
            Class<?> clazz = findClass(className, classLoader);
            if (clazz != null) return clazz;
        }

        int importCount = imports.size();
        for (int idx = 0; idx < importCount; idx++) {
            String importStmt = imports.get(idx);
            String fullClassName;

            if (importStmt.endsWith(".*")) {
                fullClassName = importStmt.substring(0, importStmt.length() - 2) + "." + className;
            } else {
                int lastDot = importStmt.lastIndexOf('.');
                int shortNameLen = importStmt.length() - lastDot - 1;
                if (lastDot < 0 || className.length() != shortNameLen ||
                        !importStmt.regionMatches(lastDot + 1, className, 0, shortNameLen)) {
                    continue;
                }
                fullClassName = importStmt;
            }

            // 注意：这里刻意走带缓存的 findClass（key = 展开后的全限定名），而不是裸的
            // findClassInternal。展开名与 import 集合无关，是"该全限定名是否存在"的纯事实，
            // 因此可以全局缓存：新增 import 时只需探测新增的前缀，不必对所有旧前缀重试，
            // 否则每 addImport 一次就要把全部前缀重新 forName 一遍。
            Class<?> clazz = findClass(fullClassName, classLoader);
            if (clazz != null) return clazz;
        }
        return null;
    }

    private static Class<?> tryNestedVariants(String dottedName, ClassLoader loader) {
        char[] chars = dottedName.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            if (chars[i] != '.') continue;
            // 启发式前置判断：$ 变体只对"外部类$嵌套类"有意义。
            // 只有满足以下之一才值得尝试，否则每次都要白白 Class.forName 一次：
            //   1) dot 之后的部分首字母大写（嵌套类名惯例，如 Map.Entry / java.util.Map.Entry）；
            //   2) dot 之前的部分本身就是一个已解析的具体类（如 Map.Entry，Map 已 import）。
            // 普通的"变量.成员"（player.get）与"包名.短标识符"（java.lang.enemies）都不会触发。
            if (!Character.isUpperCase(chars[i + 1])
                    && !isResolvableClassName(dottedName.substring(0, i), loader)) {
                continue;
            }
            chars[i] = '$';
            Class<?> clazz = findClassInternal(new String(chars), loader);
            if (clazz != null) return clazz;
            chars[i] = '.';
        }
        return null;
    }

    /** 前缀是否解析为已知类（走缓存，避免重复探测）。 */
    private static boolean isResolvableClassName(String name, ClassLoader loader) {
        return findClass(name, loader) != null;
    }

    protected static Class<?> findClassInternal(String className, ClassLoader preferredLoader) {
        if (preferredLoader != null) {
            Class<?> clazz = findInLoader(className, preferredLoader);
            if (clazz != null) return clazz;
        }
        if (primaryClassLoader != null) {
            Class<?> clazz = findInLoader(className, primaryClassLoader);
            if (clazz != null) return clazz;
        }
        for (ClassLoader loader : registeredLoaders) {
            if (loader == preferredLoader || loader == primaryClassLoader) continue;
            Class<?> clazz = findInLoader(className, loader);
            if (clazz != null) return clazz;
        }
        return findInLoader(className, null);
    }

    private static Class<?> findInLoader(String className, ClassLoader loader) {
        try {
            if (loader != null) return Class.forName(className, false, loader);
            else return Class.forName(className);
        } catch (Exception e) {
            return null;
        }
    }

    public static Method findMethod(String className, String methodName, Object... paramTypes) {
        return findMethod(className, methodName, null, paramTypes);
    }

    public static Method findMethod(String className, String methodName, ClassLoader preferredLoader, Object... paramTypes) {
        Class<?> clazz = findClassInternal(className, preferredLoader);
        if (clazz == null) return null;
        Method[] methods = clazz.getDeclaredMethods();
        for (Method m : methods) {
            if (m.getName().equals(methodName) && isApplicableArgs(m.getParameterTypes(), (Class<?>[]) paramTypes, m.isVarArgs()))
                return m;
        }
        return null;
    }

    public static List<Method> findAllMethods(String className, String methodName) {
        return findAllMethods(className, methodName, null);
    }

    public static List<Method> findAllMethods(String className, String methodName, ClassLoader preferredLoader) {
        Class<?> clazz = findClassInternal(className, preferredLoader);
        if (clazz == null) return new ArrayList<>();
        Method[] methods = clazz.getDeclaredMethods();
        List<Method> result = new ArrayList<>();
        for (Method m : methods) {
            if (m.getName().equals(methodName)) result.add(m);
        }
        return result;
    }

    public static Class<?> findClassOrFail(String className) throws ClassNotFoundException {
        return findClassOrFail(className, null);
    }

    public static Class<?> findClassOrFail(String className, ClassLoader preferredLoader) throws ClassNotFoundException {
        Class<?> clazz = findClassInternal(className, preferredLoader);
        if (clazz == null) throw new ClassNotFoundException("Class not found: " + className);
        return clazz;
    }

    public static Class<?> findClassWithImportsOrFail(String className, ClassLoader classLoader, List<String> imports) throws ClassNotFoundException {
        Class<?> clazz = findClassWithImports(className, classLoader, imports);
        if (clazz == null) throw new ClassNotFoundException("Class not found: " + className);
        return clazz;
    }

    public static Field findStaticField(String className, String fieldName, ClassLoader classLoader) {
        return findStaticField(className, fieldName, classLoader, true, true);
    }

    public static Field findStaticField(String className, String fieldName, ClassLoader preferredLoader, boolean accessSuper, boolean accessInterfaces) {
        Class<?> clazz = findClassInternal(className, preferredLoader);
        if (clazz == null) return null;
        return findStaticField(clazz, fieldName, accessSuper, accessInterfaces);
    }

    public static Field findStaticField(@NotNull Class<?> clazz, String fieldName, boolean accessSuper, boolean accessInterfaces) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers())) return field;
                current = current.getSuperclass();
            } catch (NoSuchFieldException e) {
                if (!accessSuper) break;
                current = current.getSuperclass();
            }
        }
        if (accessInterfaces) {

            for (Class<?> _interface : clazz.getInterfaces()) {
                try {
                    return _interface.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }
}
