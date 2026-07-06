package com.justnothing.engine.security;

/**
 * 沙箱配置，定义脚本执行的运行时权限约束。
 *
 * <p>包含两类权限维度：
 * <ol>
 *   <li><b>系统级权限</b>（boolean 标志）：磁盘 I/O、网络、线程、进程、反射等。
 *       这些权限由 OS 层沙箱（如 Android BlockGuard）或运行时拦截器强制执行。</li>
 *   <li><b>解释器级权限</b>（{@link IPermissionChecker}）：类/方法/字段级别的细粒度访问控制，
 *       由 Evaluator 的 {@code SecurityGate} 在每次反射操作前检查。</li>
 * </ol>
 *
 * <h3>预置配置</h3>
 * <table>
 *   <tr><th>预设</th><th>说明</th></tr>
 *   <tr><td>{@link #DEFAULT}</td><td>默认安全策略（大部分禁用）</td></tr>
 *   <tr><td>{@link #SANDBOX}</td><td>完整沙箱（含 IPermissionChecker）</td></tr>
 *   <tr><td>{@link #EXPRESSION_ONLY}</td><td>仅表达式求值</td></tr>
 *   <tr><td>{@link #MINIMAL}</td><td>最小权限（允许读文件）</td></tr>
 *   <tr><td>{@link #FULL}</td><td>完全无限制</td></tr>
 * </table>
 *
 * <h3>兼容性</h3>
 * <p>此类的 API 与旧版 {@code com.justnothing.javainterpreter.security.SandboxConfig}
 * 完全兼容（相同的字段、Builder 方法、预置常量），可直接替换使用。
 *
 * @see IPermissionChecker
 * @see BasicPermissionChecker
 * @see PermissionType
 */
public class SandboxConfig {

    // ===== 系统级权限标志 =====
    private final boolean diskReadAllowed;
    private final boolean diskWriteAllowed;
    private final boolean fileDeleteAllowed;
    private final boolean networkAllowed;
    private final boolean localSocketAllowed;
    private final boolean threadCreateAllowed;
    private final boolean threadModifyAllowed;
    private final boolean processCreateAllowed;
    private final boolean reflectionAllowed;
    private final boolean systemExitAllowed;
    private final boolean systemPropertyAllowed;
    private final boolean systemEnvAllowed;
    private final boolean classLoaderAllowed;
    private final boolean unsafeAllowed;
    private final boolean nativeAllowed;

    // ===== 解释器级权限 =====
    private final IPermissionChecker permissionChecker;

    // ==================== 预置常量 ====================

    /** 默认安全策略：禁止大部分操作，无解释器级检查器。 */
    public static final SandboxConfig DEFAULT = builder()
            .denyDiskRead().denyDiskWrite().denyFileDelete()
            .denyNetwork().allowLocalSocket()
            .denyThreadCreate().denyThreadModify()
            .denyProcessCreate().denyReflection()
            .denySystemExit().denySystemProperty()
            .denySystemEnv().denyClassLoader()
            .denyUnsafe().denyNative()
            .build();

    /** 完整沙箱：DEFAULT + 解释器级 BasicPermissionChecker.createSandbox()。 */
    public static final SandboxConfig SANDBOX = builder()
            .denyDiskRead().denyDiskWrite().denyFileDelete()
            .denyNetwork().denyLocalSocket()
            .denyThreadCreate().denyThreadModify()
            .denyProcessCreate().denyReflection()
            .denySystemExit().denySystemProperty()
            .denySystemEnv().denyClassLoader()
            .denyUnsafe().denyNative()
            .permissionChecker(BasicPermissionChecker.createSandbox())
            .build();

    /** 仅表达式求值：不允许任何 I/O 和副作用操作。 */
    public static final SandboxConfig EXPRESSION_ONLY = builder()
            .denyDiskRead().denyDiskWrite().denyFileDelete()
            .denyNetwork().denyLocalSocket()
            .denyThreadCreate().denyThreadModify()
            .denyProcessCreate().denyReflection()
            .denySystemExit().denySystemProperty()
            .denySystemEnv().denyClassLoader()
            .denyUnsafe().denyNative()
            .build();

    /** 最小权限：允许读文件，其余受限。 */
    public static final SandboxConfig MINIMAL = builder()
            .allowDiskRead().denyDiskWrite().denyFileDelete()
            .denyNetwork().allowLocalSocket()
            .denyThreadCreate().denyThreadModify()
            .denyProcessCreate().denyReflection()
            .denySystemExit().denySystemProperty()
            .denySystemEnv().denyClassLoader()
            .denyUnsafe().denyNative()
            .build();

    /** 完全无限制。 */
    public static final SandboxConfig FULL = builder()
            .allowDiskRead().allowDiskWrite().allowFileDelete()
            .allowNetwork().allowLocalSocket()
            .allowThreadCreate().allowThreadModify()
            .allowProcessCreate().allowReflection()
            .allowSystemExit().allowSystemProperty()
            .allowSystemEnv().allowClassLoader()
            .allowUnsafe().allowNative()
            .permissionChecker(BasicPermissionChecker.permissive()) // ★ FULL = 无任何解释器级限制
            .build();

    // ==================== 构造 ====================

    private SandboxConfig(Builder builder) {
        this.diskReadAllowed = builder.diskReadAllowed;
        this.diskWriteAllowed = builder.diskWriteAllowed;
        this.fileDeleteAllowed = builder.fileDeleteAllowed;
        this.networkAllowed = builder.networkAllowed;
        this.localSocketAllowed = builder.localSocketAllowed;
        this.threadCreateAllowed = builder.threadCreateAllowed;
        this.threadModifyAllowed = builder.threadModifyAllowed;
        this.processCreateAllowed = builder.processCreateAllowed;
        this.reflectionAllowed = builder.reflectionAllowed;
        this.systemExitAllowed = builder.systemExitAllowed;
        this.systemPropertyAllowed = builder.systemPropertyAllowed;
        this.systemEnvAllowed = builder.systemEnvAllowed;
        this.classLoaderAllowed = builder.classLoaderAllowed;
        this.unsafeAllowed = builder.unsafeAllowed;
        this.nativeAllowed = builder.nativeAllowed;
        this.permissionChecker = builder.permissionChecker;
    }

    // ==================== Getter ====================

    public boolean isDiskReadAllowed() { return diskReadAllowed; }
    public boolean isDiskWriteAllowed() { return diskWriteAllowed; }
    public boolean isFileDeleteAllowed() { return fileDeleteAllowed; }
    public boolean isNetworkAllowed() { return networkAllowed; }
    public boolean isLocalSocketAllowed() { return localSocketAllowed; }
    public boolean isThreadCreateAllowed() { return threadCreateAllowed; }
    public boolean isThreadModifyAllowed() { return threadModifyAllowed; }
    public boolean isProcessCreateAllowed() { return processCreateAllowed; }
    public boolean isReflectionAllowed() { return reflectionAllowed; }
    public boolean isSystemExitAllowed() { return systemExitAllowed; }
    public boolean isSystemPropertyAllowed() { return systemPropertyAllowed; }
    public boolean isSystemEnvAllowed() { return systemEnvAllowed; }
    public boolean isClassLoaderAllowed() { return classLoaderAllowed; }
    public boolean isUnsafeAllowed() { return unsafeAllowed; }
    public boolean isNativeAllowed() { return nativeAllowed; }

    /** 获取解释器级权限检查器，可能为 null 表示不做解释器级检查。 */
    public IPermissionChecker getPermissionChecker() { return permissionChecker; }

    /** 同 getPermissionChecker()，兼容旧 API 别名。 */
    public IPermissionChecker getAstPermissionChecker() { return permissionChecker; }

    public static Builder builder() { return new Builder(); }

    // ==================== Builder ====================

    public static final class Builder {
        private boolean diskReadAllowed = false;
        private boolean diskWriteAllowed = false;
        private boolean fileDeleteAllowed = false;
        private boolean networkAllowed = false;
        private boolean localSocketAllowed = true;
        private boolean threadCreateAllowed = false;
        private boolean threadModifyAllowed = false;
        private boolean processCreateAllowed = false;
        private boolean reflectionAllowed = false;
        private boolean systemExitAllowed = false;
        private boolean systemPropertyAllowed = false;
        private boolean systemEnvAllowed = false;
        private boolean classLoaderAllowed = false;
        private boolean unsafeAllowed = false;
        private boolean nativeAllowed = false;
        private IPermissionChecker permissionChecker = createDefaultChecker();

        private static IPermissionChecker createDefaultChecker() {
            return BasicPermissionChecker.builder()
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
                    .denyClass("java.lang.ThreadGroup")
                    .denyClass("java.io.*")
                    .denyClass("java.net.*")
                    .denyClass("java.nio.*")
                    .denyClass("java.lang.reflect.*")
                    .denyClass("java.security.*")
                    .denyClass("sun.misc.Unsafe")
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

        // ===== 磁盘 =====
        public Builder allowDiskRead() { diskReadAllowed = true; return this; }
        public Builder denyDiskRead() { diskReadAllowed = false; return this; }
        public Builder allowDiskWrite() { diskWriteAllowed = true; return this; }
        public Builder denyDiskWrite() { diskWriteAllowed = false; return this; }

        // ===== 文件 =====
        public Builder allowFileDelete() { fileDeleteAllowed = true; return this; }
        public Builder denyFileDelete() { fileDeleteAllowed = false; return this; }

        // ===== 网络 =====
        public Builder allowNetwork() { networkAllowed = true; return this; }
        public Builder denyNetwork() { networkAllowed = false; return this; }
        public Builder allowLocalSocket() { localSocketAllowed = true; return this; }
        public Builder denyLocalSocket() { localSocketAllowed = false; return this; }

        // ===== 线程/进程 =====
        public Builder allowThreadCreate() { threadCreateAllowed = true; return this; }
        public Builder denyThreadCreate() { threadCreateAllowed = false; return this; }
        public Builder allowThreadModify() { threadModifyAllowed = true; return this; }
        public Builder denyThreadModify() { threadModifyAllowed = false; return this; }
        public Builder allowProcessCreate() { processCreateAllowed = true; return this; }
        public Builder denyProcessCreate() { processCreateAllowed = false; return this; }

        // ===== 反射 =====
        public Builder allowReflection() { reflectionAllowed = true; return this; }
        public Builder denyReflection() { reflectionAllowed = false; return this; }

        // ===== 系统 =====
        public Builder allowSystemExit() { systemExitAllowed = true; return this; }
        public Builder denySystemExit() { systemExitAllowed = false; return this; }
        public Builder allowSystemProperty() { systemPropertyAllowed = true; return this; }
        public Builder denySystemProperty() { systemPropertyAllowed = false; return this; }
        public Builder allowSystemEnv() { systemEnvAllowed = true; return this; }
        public Builder denySystemEnv() { systemEnvAllowed = false; return this; }
        public Builder allowClassLoader() { classLoaderAllowed = true; return this; }
        public Builder denyClassLoader() { classLoaderAllowed = false; return this; }

        // ===== 危险 =====
        public Builder allowUnsafe() { unsafeAllowed = true; return this; }
        public Builder denyUnsafe() { unsafeAllowed = false; return this; }
        public Builder allowNative() { nativeAllowed = true; return this; }
        public Builder denyNative() { nativeAllowed = false; return this; }

        // ===== 解释器级权限 =====
        public Builder permissionChecker(IPermissionChecker checker) {
            this.permissionChecker = checker; return this;
        }

        public SandboxConfig build() {
            return new SandboxConfig(this);
        }
    }
}
