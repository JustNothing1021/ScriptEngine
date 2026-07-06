package com.justnothing.engine.security;

/**
 * 权限检查器接口，用于在脚本执行时对反射操作进行细粒度控制。
 *
 * <p>实现类应基于 {@link PermissionType} 枚举和类名/方法名/字段名的
 * 白名单/黑名单模式来决定是否允许特定操作。
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>{@code BasicPermissionChecker} — 基于规则的检查器，支持通配符模式匹配</li>
 *   <li>自定义实现 — 可对接 ACL 数据库、远程策略服务器等</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>所有 check* 方法在不允许时抛出 {@link SecurityException}</li>
 *   <li>所有 has* 方法返回 boolean，不抛异常</li>
 *   <li>目标参数使用字符串（类全限定名），不依赖已加载的 Class 对象</li>
 * </ul>
 *
 * @see BasicPermissionChecker
 * @see PermissionType
 * @see SandboxConfig
 */
public interface IPermissionChecker {

    /**
     * 检查是否拥有指定类型的权限（无目标）。
     */
    boolean hasPermission(PermissionType type);

    /**
     * 检查是否拥有针对指定目标的权限。
     *
     * @param type 权限类型
     * @param target 目标描述（如文件路径、主机地址等）
     */
    boolean hasPermission(PermissionType type, String target);

    /**
     * 检查是否可以访问指定类。
     *
     * @param className 类的全限定名（如 "java.io.File"）
     */
    boolean hasClassAccess(String className);

    /**
     * 检查是否可以调用指定方法。
     *
     * @param className  类的全限定名
     * @param methodName 方法名
     * @param signature  方法签名（简化版，如 "(String,int)"）
     */
    boolean hasMethodAccess(String className, String methodName, String signature);

    /**
     * 检查是否可以访问指定字段。
     *
     * @param className  类的全限定名
     * @param fieldName 字段名
     */
    boolean hasFieldAccess(String className, String fieldName);

    /**
     * 检查是否可以创建指定类的实例。
     *
     * @param className 类的全限定名
     */
    boolean hasNewInstanceAccess(String className);

    // ========== check* 方法 — 不允许时抛异常 ==========

    void checkPermission(PermissionType type) throws SecurityException;

    void checkPermission(PermissionType type, String target) throws SecurityException;

    void checkClassAccess(String className) throws SecurityException;

    void checkMethodAccess(String className, String methodName, String signature) throws SecurityException;

    void checkFieldAccess(String className, String fieldName) throws SecurityException;

    void checkNewInstance(String className) throws SecurityException;

    // ========== 预置实例工厂 ==========

    static IPermissionChecker getPermissive() {
        return BasicPermissionChecker.permissive();
    }

    static IPermissionChecker getRestrictive() {
        return BasicPermissionChecker.restrictive();
    }
}
