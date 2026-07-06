package com.justnothing.engine.security;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 安全门卫，在 Evaluator 执行每次反射操作前进行权限检查。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>零开销模式</b>：当 {@code checker == null} 时，所有检查方法都是空操作（直接返回）</li>
 *   <li><b>基于名字的检查</b>：从已解析的 Method/Field/Constructor/Class 对象中提取类名和方法名，
 *       传递给 {@link IPermissionChecker} 进行白名单/黑名单匹配</li>
 *   <li><b>统一异常</b>：所有拦截操作抛出 {@link SecurityException}</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 在 Evaluator 的反射调用前插入：
 * SecurityGate sg = evalContext.getSecurityGate();
 * if (sg != null) sg.beforeMethodCall(method);
 * Object result = method.invoke(target, args);
 * }</pre>
 *
 * @see IPermissionChecker
 * @see BasicPermissionChecker
 */
public final class SecurityGate {

    private final IPermissionChecker checker;

    /**
     * 创建一个无限制的安全门卫（所有操作放行）。
     */
    public static SecurityGate permissive() {
        return new SecurityGate(null);
    }

    /**
     * 创建带权限检查器的安全门卫。
     *
     * @param checker 权限检查器，为 null 时等同于 {@link #permissive()}
     */
    public SecurityGate(IPermissionChecker checker) {
        this.checker = checker;
    }

    /** 是否启用安全检查（checker != null）。 */
    public boolean isEnabled() {
        return checker != null;
    }

    /** 获取内部的权限检查器，可能为 null。 */
    public IPermissionChecker getChecker() {
        return checker;
    }

    // ==================== 方法调用 ====================

    /** 在 Method.invoke() 前调用。 */
    public void beforeMethodCall(Method method) throws SecurityException {
        if (checker == null) return;
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        String signature = makeSignature(method);
        checker.checkMethodAccess(className, methodName, signature);
    }

    // ==================== 字段访问 ====================

    /** 在 Field.get() / Field.getBoolean() 等读取操作前调用。 */
    public void beforeFieldRead(Field field) throws SecurityException {
        if (checker == null) return;
        checker.checkFieldAccess(field.getDeclaringClass().getName(), field.getName());
        checker.checkPermission(PermissionType.FIELD_READ);
    }

    /** 在 Field.set() / Field.setBoolean() 等写入操作前调用。 */
    public void beforeFieldWrite(Field field) throws SecurityException {
        if (checker == null) return;
        checker.checkFieldAccess(field.getDeclaringClass().getName(), field.getName());
        checker.checkPermission(PermissionType.FIELD_WRITE);
    }

    // ==================== 构造器调用 ====================

    /** 在 Constructor.newInstance() 前调用。 */
    public void beforeConstructorCall(Constructor<?> ctor) throws SecurityException {
        if (checker == null) return;
        checker.checkNewInstance(ctor.getDeclaringClass().getName());
    }

    // ==================== 类访问 ====================

    /** 在 Class.forName() 或类字面量引用前调用。 */
    public void beforeClassAccess(Class<?> clazz) throws SecurityException {
        if (checker == null) return;
        checker.checkClassAccess(clazz.getName());
    }

    /** 在 Class.forName(String) 前调用（尚未加载到 Class 对象时使用字符串名）。 */
    public void beforeClassAccessByName(String className) throws SecurityException {
        if (checker == null) return;
        checker.checkClassAccess(className);
    }

    // ==================== 通用权限 ====================

    /** 检查通用权限类型（无目标对象）。 */
    public void checkPermission(PermissionType type) throws SecurityException {
        if (checker == null) return;
        checker.checkPermission(type);
    }

    /** 检查通用权限类型（有目标描述）。 */
    public void checkPermission(PermissionType type, String target) throws SecurityException {
        if (checker == null) return;
        checker.checkPermission(type, target);
    }

    // ==================== 内部工具 ====================

    private static String makeSignature(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(p.getSimpleName()).append(",");
        }
        return sb.append(")").toString();
    }
}
