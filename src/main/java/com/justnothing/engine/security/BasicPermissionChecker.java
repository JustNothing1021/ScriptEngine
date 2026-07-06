package com.justnothing.engine.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于规则列表的权限检查器实现。
 *
 * <p>支持以下维度的访问控制：
 * <ul>
 *   <li>{@link PermissionType} 级别的 allow/deny + defaultAllow/defaultDeny</li>
 *   <li>类级别的通配符白名单/黑名单（如 "java.lang.*"、"java.io.File"）</li>
 *   <li>方法级别的精确/通配符匹配（如 "java.lang.Class.forName"）</li>
 *   <li>字段级别的精确/通配符匹配</li>
 * </ul>
 *
 * <h3>匹配优先级</h3>
 * <ol>
 *   <li>精确 deny > 精确 allow > 通配符 deny > 通配符 allow > defaultAllow</li>
 * </ol>
 *
 * <h3>预置配置</h3>
 * <ul>
 *   <li>{@link #permissive()} — 全部放行</li>
 *   <li>{@link #restrictive()} — 全部拒绝</li>
 *   <li>{@link #createSandbox()} — 沙箱模式（禁止危险类和方法）</li>
 *   <li>{@link #createExpressionOnly()} — 仅表达式求值</li>
 *   <li>{@link #createMinimal()} — 最小权限（允许读文件）</li>
 * </ul>
 */
public class BasicPermissionChecker implements IPermissionChecker {

    private final Set<PermissionType> allowedPermissions;
    private final Set<PermissionType> deniedPermissions;
    private final Set<String> allowedClasses;
    private final Set<String> deniedClasses;
    private final Set<String> allowedMethods;
    private final Set<String> deniedMethods;
    private final Set<String> allowedFields;
    private final Set<String> deniedFields;
    private final boolean defaultAllow;

    private BasicPermissionChecker(Builder builder) {
        this.allowedPermissions = Set.copyOf(builder.allowedPermissions);
        this.deniedPermissions = Set.copyOf(builder.deniedPermissions);
        this.allowedClasses = Set.copyOf(builder.allowedClasses);
        this.deniedClasses = Set.copyOf(builder.deniedClasses);
        this.allowedMethods = Set.copyOf(builder.allowedMethods);
        this.deniedMethods = Set.copyOf(builder.deniedMethods);
        this.allowedFields = Set.copyOf(builder.allowedFields);
        this.deniedFields = Set.copyOf(builder.deniedFields);
        this.defaultAllow = builder.defaultAllow;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static IPermissionChecker permissive() {
        return PERMISSIVE_INSTANCE;
    }

    public static IPermissionChecker restrictive() {
        return RESTRICTIVE_INSTANCE;
    }

    // ==================== 预置配置 ====================

    public static BasicPermissionChecker createMinimal() {
        return builder()
                .defaultDeny()
                .allowPermission(PermissionType.CLASS_ACCESS)
                .allowPermission(PermissionType.FIELD_READ)
                .allowPermission(PermissionType.METHOD_CALL)
                .allowClass("java.lang.*")
                .allowClass("java.util.*")
                .allowClass("java.math.*")
                .denyClass("java.lang.Runtime")
                .denyClass("java.lang.ProcessBuilder")
                .denyClass("java.lang.System")
                .denyClass("java.lang.ClassLoader")
                .denyClass("java.lang.Thread")
                .denyClass("java.io.*")
                .denyClass("java.net.*")
                .denyClass("java.nio.*")
                .denyClass("java.lang.reflect.*")
                .denyClass("java.security.*")
                .denyClass("sun.*")
                .denyClass("com.android.*")
                .denyClass("android.*")
                .denyMethod("java.lang.Object.getClass")
                .denyMethod("java.lang.Class.getClassLoader")
                .denyMethod("java.lang.Class.forName")
                .denyMethod("java.lang.Class.getConstructor")
                .denyMethod("java.lang.Class.getMethod")
                .denyMethod("java.lang.Class.getField")
                .denyMethod("java.lang.Class.getDeclared*")
                .build();
    }

    public static BasicPermissionChecker createExpressionOnly() {
        return builder()
                .defaultDeny()
                .allowPermission(PermissionType.CLASS_ACCESS)
                .allowPermission(PermissionType.FIELD_READ)
                .allowPermission(PermissionType.METHOD_CALL)
                .allowPermission(PermissionType.NEW_INSTANCE)
                .allowPermission(PermissionType.ARRAY_CREATE)
                .allowClass("java.lang.*")
                .allowClass("java.util.*")
                .allowClass("java.math.*")
                .denyClass("java.lang.Runtime")
                .denyClass("java.lang.ProcessBuilder")
                .denyClass("java.lang.System")
                .denyClass("java.lang.ClassLoader")
                .denyClass("java.lang.Thread")
                .denyClass("java.io.*")
                .denyClass("java.net.*")
                .denyClass("java.nio.*")
                .denyClass("java.lang.reflect.*")
                .denyClass("java.security.*")
                .denyClass("sun.*")
                .denyClass("com.android.*")
                .denyClass("android.*")
                .denyPermission(PermissionType.FILE_READ)
                .denyPermission(PermissionType.FILE_WRITE)
                .denyPermission(PermissionType.EXEC)
                .denyPermission(PermissionType.NETWORK)
                .denyPermission(PermissionType.THREAD_CREATE)
                .denyPermission(PermissionType.SYSTEM_EXIT)
                .denyPermission(PermissionType.REFLECTION)
                .denyMethod("java.lang.Object.getClass")
                .denyMethod("java.lang.Class.getClassLoader")
                .denyMethod("java.lang.Class.forName")
                .denyMethod("java.lang.Class.getConstructor")
                .denyMethod("java.lang.Class.getMethod")
                .denyMethod("java.lang.Class.getField")
                .denyMethod("java.lang.Class.getDeclared*")
                .build();
    }

    public static BasicPermissionChecker createSandbox() {
        return builder()
                .defaultDeny()
                .allowPermission(PermissionType.CLASS_ACCESS)
                .allowPermission(PermissionType.FIELD_READ)
                .allowPermission(PermissionType.METHOD_CALL)
                .allowPermission(PermissionType.NEW_INSTANCE)
                .allowPermission(PermissionType.ARRAY_CREATE)
                .allowClass("java.lang.*")
                .allowClass("java.util.*")
                .allowClass("java.math.*")
                .allowClass("java.text.*")
                .denyClass("java.lang.Runtime")
                .denyClass("java.lang.ProcessBuilder")
                .denyClass("java.lang.System")
                .denyClass("java.lang.ClassLoader")
                .denyClass("java.lang.Thread")
                .denyClass("java.io.*")
                .denyClass("java.net.*")
                .denyClass("java.nio.*")
                .denyClass("java.lang.reflect.*")
                .denyClass("java.security.*")
                .denyClass("sun.*")
                .denyClass("com.android.*")
                .denyClass("android.*")
                .denyMethod("java.lang.Object.getClass")
                .denyMethod("java.lang.Class.getClassLoader")
                .denyMethod("java.lang.Class.forName")
                .denyMethod("java.lang.Class.getConstructor")
                .denyMethod("java.lang.Class.getMethod")
                .denyMethod("java.lang.Class.getField")
                .denyMethod("java.lang.Class.getDeclared*")
                .build();
    }

    public static IPermissionChecker createNoRestrictions() {
        return builder().defaultAllow().build();
    }

    // ==================== 单例 ====================

    private static final IPermissionChecker PERMISSIVE_INSTANCE = new IPermissionChecker() {
        @Override public boolean hasPermission(PermissionType t) { return true; }
        @Override public boolean hasPermission(PermissionType t, String target) { return true; }
        @Override public boolean hasClassAccess(String c) { return true; }
        @Override public boolean hasMethodAccess(String c, String m, String s) { return true; }
        @Override public boolean hasFieldAccess(String c, String f) { return true; }
        @Override public boolean hasNewInstanceAccess(String c) { return true; }
        @Override public void checkPermission(PermissionType t) {}
        @Override public void checkPermission(PermissionType t, String target) {}
        @Override public void checkClassAccess(String c) {}
        @Override public void checkMethodAccess(String c, String m, String s) {}
        @Override public void checkFieldAccess(String c, String f) {}
        @Override public void checkNewInstance(String c) {}
    };

    private static final IPermissionChecker RESTRICTIVE_INSTANCE = new IPermissionChecker() {
        private void denied(String what) {
            throw new SecurityException("SecurityGate: 操作被安全策略阻止 - " + what);
        }
        @Override public boolean hasPermission(PermissionType t) { return false; }
        @Override public boolean hasPermission(PermissionType t, String target) { return false; }
        @Override public boolean hasClassAccess(String c) { return false; }
        @Override public boolean hasMethodAccess(String c, String m, String s) { return false; }
        @Override public boolean hasFieldAccess(String c, String f) { return false; }
        @Override public boolean hasNewInstanceAccess(String c) { return false; }
        @Override public void checkPermission(PermissionType t) { denied(t.getDescription()); }
        @Override public void checkPermission(PermissionType t, String target) { denied(t.getDescription() + " on " + target); }
        @Override public void checkClassAccess(String c) { denied("类访问: " + c); }
        @Override public void checkMethodAccess(String c, String m, String s) { denied("方法调用: " + c + "." + m); }
        @Override public void checkFieldAccess(String c, String f) { denied("字段访问: " + c + "." + f); }
        @Override public void checkNewInstance(String c) { denied("实例化: " + c); }
    };

    // ==================== 匹配逻辑 ====================

    private boolean matchesPattern(String name, Set<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.endsWith(".*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (name.startsWith(prefix)) return true;
            } else if (pattern.equals(name)) {
                return true;
            } else if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (name.matches(regex)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasPermission(PermissionType type) {
        if (deniedPermissions.contains(type)) return false;
        if (allowedPermissions.contains(type)) return true;
        return defaultAllow;
    }

    @Override
    public boolean hasPermission(PermissionType type, String target) {
        return hasPermission(type);
    }

    @Override
    public boolean hasClassAccess(String className) {
        if (matchesPattern(className, deniedClasses)) return false;
        if (matchesPattern(className, allowedClasses)) return true;
        return defaultAllow;
    }

    @Override
    public boolean hasMethodAccess(String className, String methodName, String signature) {
        String fullMethodName = className + "." + methodName;
        if (matchesPattern(fullMethodName, deniedMethods)) return false;
        if (matchesPattern(fullMethodName, allowedMethods)) return true;
        return hasClassAccess(className) && hasPermission(PermissionType.METHOD_CALL);
    }

    @Override
    public boolean hasFieldAccess(String className, String fieldName) {
        String fullFieldName = className + "." + fieldName;
        if (matchesPattern(fullFieldName, deniedFields)) return false;
        if (matchesPattern(fullFieldName, allowedFields)) return true;
        return hasClassAccess(className) && hasPermission(PermissionType.FIELD_READ);
    }

    @Override
    public boolean hasNewInstanceAccess(String className) {
        return hasClassAccess(className) && hasPermission(PermissionType.NEW_INSTANCE);
    }

    // ==================== check 实现 ====================

    @Override
    public void checkPermission(PermissionType type) throws SecurityException {
        if (!hasPermission(type)) throw new SecurityException(
                "SecurityGate: 权限被拒绝 - " + type.getDescription());
    }

    @Override
    public void checkPermission(PermissionType type, String target) throws SecurityException {
        if (!hasPermission(type, target)) throw new SecurityException(
                "SecurityGate: 权限被拒绝 - " + type.getDescription() + " on " + target);
    }

    @Override
    public void checkClassAccess(String className) throws SecurityException {
        if (!hasClassAccess(className)) throw new SecurityException(
                "SecurityGate: 类访问被拒绝 - " + className);
    }

    @Override
    public void checkMethodAccess(String className, String methodName, String signature) throws SecurityException {
        if (!hasMethodAccess(className, methodName, signature)) throw new SecurityException(
                "SecurityGate: 方法调用被拒绝 - " + className + "." + methodName);
    }

    @Override
    public void checkFieldAccess(String className, String fieldName) throws SecurityException {
        if (!hasFieldAccess(className, fieldName)) throw new SecurityException(
                "SecurityGate: 字段访问被拒绝 - " + className + "." + fieldName);
    }

    @Override
    public void checkNewInstance(String className) throws SecurityException {
        if (!hasNewInstanceAccess(className)) throw new SecurityException(
                "SecurityGate: 实例化被拒绝 - " + className);
    }

    // ==================== Builder ====================

    public static class Builder {
        private final Set<PermissionType> allowedPermissions = new HashSet<>();
        private final Set<PermissionType> deniedPermissions = new HashSet<>();
        private final Set<String> allowedClasses = new HashSet<>();
        private final Set<String> deniedClasses = new HashSet<>();
        private final Set<String> allowedMethods = new HashSet<>();
        private final Set<String> deniedMethods = new HashSet<>();
        private final Set<String> allowedFields = new HashSet<>();
        private final Set<String> deniedFields = new HashSet<>();
        private boolean defaultAllow = true;

        public Builder defaultAllow() { this.defaultAllow = true; return this; }
        public Builder defaultDeny() { this.defaultAllow = false; return this; }

        public Builder allowPermission(PermissionType... types) {
            allowedPermissions.addAll(Arrays.asList(types)); return this;
        }
        public Builder denyPermission(PermissionType... types) {
            deniedPermissions.addAll(Arrays.asList(types)); return this; }

        public Builder allowClass(String... classNames) {
            allowedClasses.addAll(Arrays.asList(classNames)); return this;
        }
        public Builder denyClass(String... classNames) {
            deniedClasses.addAll(Arrays.asList(classNames)); return this; }

        public Builder allowMethod(String... methodNames) {
            allowedMethods.addAll(Arrays.asList(methodNames)); return this;
        }
        public Builder denyMethod(String... methodNames) {
            deniedMethods.addAll(Arrays.asList(methodNames)); return this; }

        public Builder allowField(String... fieldNames) {
            allowedFields.addAll(Arrays.asList(fieldNames)); return this;
        }
        public Builder denyField(String... fieldNames) {
            deniedFields.addAll(Arrays.asList(fieldNames)); return this; }

        public BasicPermissionChecker build() {
            return new BasicPermissionChecker(this);
        }
    }
}
