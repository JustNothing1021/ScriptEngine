package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import com.justnothing.engine.security.*;
import org.junit.Before;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * 安全模块综合测试：SecurityGate / BasicPermissionChecker / SandboxConfig / PermissionType。
 *
 * <p>覆盖 5 大场景：
 * <ol>
 *   <li>PermissionType 枚举完整性</li>
 *   <li>BasicPermissionChecker 规则匹配（精确/通配符/defaultAllow/deny 优先级）</li>
 *   <li>SandboxConfig 预置配置</li>
 *   <li>SecurityGate 集成（null 模式零开销）</li>
 *   <li>端到端：ScriptRunner + SandboxConfig 拦截危险操作</li>
 * </ol>
 */
public class SecurityModuleTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
    }

    // ==================== 1. PermissionType 枚举 ====================

    @Test
    public void permissionType_hasAllCategories() {
        // 解释器级别
        assertNotNull(PermissionType.CLASS_ACCESS);
        assertNotNull(PermissionType.METHOD_CALL);
        assertNotNull(PermissionType.FIELD_READ);
        assertNotNull(PermissionType.FIELD_WRITE);
        assertNotNull(PermissionType.NEW_INSTANCE);
        assertNotNull(PermissionType.REFLECTION);
        // I/O 级别
        assertNotNull(PermissionType.FILE_READ);
        assertNotNull(PermissionType.FILE_WRITE);
        assertNotNull(PermissionType.NETWORK);
        assertNotNull(PermissionType.EXEC);
        // 系统级别
        assertNotNull(PermissionType.THREAD_CREATE);
        assertNotNull(PermissionType.SYSTEM_EXIT);
        assertNotNull(PermissionType.UNSAFE);
    }

    @Test
    public void permissionType_hasIdAndDescription() {
        for (PermissionType pt : PermissionType.values()) {
            assertNotNull("PermissionType " + pt.name() + " missing id", pt.getId());
            assertNotNull("PermissionType " + pt.name() + " missing description", pt.getDescription());
            assertFalse(pt.getId().isEmpty());
            assertFalse(pt.getDescription().isEmpty());
        }
    }

    // ==================== 2. BasicPermissionChecker 规则匹配 ====================

    @Test
    public void checker_permissive_allowsEverything() {
        IPermissionChecker c = BasicPermissionChecker.permissive();
        assertTrue(c.hasPermission(PermissionType.FILE_DELETE));
        assertTrue(c.hasClassAccess("java.io.File"));
        assertTrue(c.hasMethodAccess("java.lang.Runtime", "exec", "(String)"));
        assertTrue(c.hasFieldAccess("java.lang.System", "out"));
        assertTrue(c.hasNewInstanceAccess("java.lang.ProcessBuilder"));
        // check 方法不抛异常
        c.checkPermission(PermissionType.UNSAFE);
        c.checkClassAccess("sun.misc.Unsafe");
        c.checkMethodAccess("java.lang.Class", "forName", "(String)");
        c.checkFieldAccess("java.lang.System", "in");
        c.checkNewInstance("java.lang.Thread");
    }

    @Test
    public void checker_restrictive_deniesEverything() {
        IPermissionChecker c = BasicPermissionChecker.restrictive();
        assertFalse(c.hasPermission(PermissionType.CLASS_ACCESS));
        assertFalse(c.hasClassAccess("java.lang.String"));
        assertFalse(c.hasMethodAccess("java.lang.String", "length", "()"));
        assertFalse(c.hasNewInstanceAccess("java.lang.Object"));
    }

    @Test
    public void checker_wildcardMatch() {
        IPermissionChecker c = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowClass("java.util.*")
                .denyClass("java.util.concurrent.*")
                .build();

        assertTrue(c.hasClassAccess("java.util.ArrayList"));
        assertTrue(c.hasClassAccess("java.util.HashMap"));
        assertFalse(c.hasClassAccess("java.util.concurrent.ConcurrentHashMap"));
        assertFalse(c.hasClassAccess("java.io.File")); // not in allow list, default deny
    }

    @Test
    public void checker_wildcardStopsAtPackageBoundary() {
        // 包通配符的语义是"该包 + 其子包"，但必须停在包边界上。
        // 修复前是 name.startsWith(prefix) 且不补 '.'，于是 `android.*` 会连带拒绝
        // androidx.*（过度拦截）。
        IPermissionChecker allowed = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowClass("java.lang.*")
                .build();
        assertTrue(allowed.hasClassAccess("java.lang.String"));
        assertTrue(allowed.hasClassAccess("java.lang.invoke.MethodHandles")); // 子包同样命中
        assertFalse(allowed.hasClassAccess("java.langX.Foo")); // 仅前缀相同，不是子包

        IPermissionChecker denied = BasicPermissionChecker.builder()
                .defaultAllow()
                .denyClass("android.*")
                .build();
        assertFalse(denied.hasClassAccess("android.os.Handler"));
        assertTrue(denied.hasClassAccess("androidx.appcompat.app.AppCompatActivity"));
    }

    @Test
    public void checker_exactDenyOverExactAllow() {
        // deny > allow in priority
        IPermissionChecker c = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowClass("java.lang.*")
                .denyClass("java.lang.Runtime")
                .build();

        assertTrue(c.hasClassAccess("java.lang.String"));
        assertTrue(c.hasClassAccess("java.lang.Math"));
        assertFalse(c.hasClassAccess("java.lang.Runtime")); // explicit deny wins
    }

    @Test
    public void checker_methodPatternMatch() {
        IPermissionChecker c = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowPermission(PermissionType.METHOD_CALL) // ★ defaultDeny 需要显式允许
                .allowClass("java.lang.*")
                .denyMethod("java.lang.Class.forName")
                .denyMethod("java.lang.Class.getDeclared*")
                .build();

        assertTrue(c.hasMethodAccess("java.lang.String", "length", "()"));
        assertTrue(c.hasMethodAccess("java.lang.Class", "getName", "()"));
        assertFalse(c.hasMethodAccess("java.lang.Class", "forName", "(String)"));
        assertFalse(c.hasMethodAccess("java.lang.Class", "getDeclaredFields", "()"));
    }

    @Test
    public void checker_permissionTypeAllowDeny() {
        IPermissionChecker c = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowPermission(PermissionType.FIELD_READ)
                .allowPermission(PermissionType.METHOD_CALL)
                .denyPermission(PermissionType.NEW_INSTANCE)
                .build();

        assertTrue(c.hasPermission(PermissionType.FIELD_READ));
        assertTrue(c.hasPermission(PermissionType.METHOD_CALL));
        assertFalse(c.hasPermission(PermissionType.NEW_INSTANCE));
        assertFalse(c.hasPermission(PermissionType.FILE_WRITE)); // default deny
    }

    @Test
    public void checker_fieldPatternMatch() {
        IPermissionChecker c = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowPermission(PermissionType.FIELD_READ) // ★ defaultDeny 需要显式允许
                .allowClass("java.lang.*")
                .denyField("java.lang.System.out") // block stdout access
                .build();

        assertTrue(c.hasFieldAccess("java.lang.Integer", "MAX_VALUE"));
        assertFalse(c.hasFieldAccess("java.lang.System", "out"));
    }

    // ==================== 3. SandboxConfig 预置配置 ====================

    @Test
    public void sandboxConfig_default_disallowsMostIO() {
        SandboxConfig cfg = SandboxConfig.DEFAULT;
        assertFalse(cfg.isDiskReadAllowed());
        assertFalse(cfg.isDiskWriteAllowed());
        assertFalse(cfg.isNetworkAllowed());
        assertFalse(cfg.isProcessCreateAllowed());
        assertFalse(cfg.isReflectionAllowed());
        assertTrue(cfg.isLocalSocketAllowed()); // 默认允许本地 socket
    }

    @Test
    public void sandboxConfig_full_allowsEverything() {
        SandboxConfig cfg = SandboxConfig.FULL;
        assertTrue(cfg.isDiskReadAllowed());
        assertTrue(cfg.isDiskWriteAllowed());
        assertTrue(cfg.isNetworkAllowed());
        assertTrue(cfg.isProcessCreateAllowed());
        assertTrue(cfg.isReflectionAllowed());
        assertTrue(cfg.isSystemExitAllowed());
        assertTrue(cfg.isUnsafeAllowed());
        assertTrue(cfg.isNativeAllowed());
    }

    @Test
    public void sandboxConfig_minimal_allowsReadOnly() {
        SandboxConfig cfg = SandboxConfig.MINIMAL;
        assertTrue(cfg.isDiskReadAllowed());
        assertFalse(cfg.isDiskWriteAllowed());
        assertFalse(cfg.isFileDeleteAllowed());
        assertFalse(cfg.isNetworkAllowed());
    }

    @Test
    public void sandboxConfig_sandbox_hasPermissionChecker() {
        SandboxConfig cfg = SandboxConfig.SANDBOX;
        assertNotNull("SANDBOX 必须有权限检查器", cfg.getPermissionChecker());

        IPermissionChecker pc = cfg.getPermissionChecker();
        // SANDBOX 应该禁止危险类
        assertFalse(pc.hasClassAccess("java.lang.Runtime"));
        assertFalse(pc.hasClassAccess("java.io.File"));
        // 但允许安全类
        assertTrue(pc.hasClassAccess("java.lang.String"));
        assertTrue(pc.hasClassAccess("java.util.ArrayList"));
    }

    @Test
    public void sandboxConfig_expressionOnly_noIO() {
        SandboxConfig cfg = SandboxConfig.EXPRESSION_ONLY;
        assertFalse(cfg.isDiskReadAllowed());
        assertFalse(cfg.isDiskWriteAllowed());
        assertFalse(cfg.isNetworkAllowed());
        assertFalse(cfg.isThreadCreateAllowed());
    }

    @Test
    public void sandboxConfig_builder_customConfig() {
        SandboxConfig cfg = SandboxConfig.builder()
                .allowDiskRead()
                .allowDiskWrite()
                .allowNetwork()
                .denyThreadCreate()
                .permissionChecker(BasicPermissionChecker.permissive())
                .build();

        assertTrue(cfg.isDiskReadAllowed());
        assertTrue(cfg.isDiskWriteAllowed());
        assertTrue(cfg.isNetworkAllowed());
        assertFalse(cfg.isThreadCreateAllowed());
        assertSame(BasicPermissionChecker.permissive(), cfg.getPermissionChecker());
    }

    // ==================== 4. SecurityGate 基础操作 ====================

    @Test
    public void securityGate_permissive_isEnabledFalse() {
        SecurityGate gate = SecurityGate.permissive();
        assertFalse(gate.isEnabled());
        assertNull(gate.getChecker());
        // 不抛异常
        gate.checkPermission(PermissionType.UNSAFE);
    }

    @Test
    public void securityGate_withChecker_isEnabledTrue() {
        SecurityGate gate = new SecurityGate(BasicPermissionChecker.permissive());
        assertTrue(gate.isEnabled());
        assertNotNull(gate.getChecker());
    }

    @Test
    public void securityGate_nullChecker_sameAsPermissive() {
        SecurityGate gate1 = SecurityGate.permissive();
        SecurityGate gate2 = new SecurityGate(null);
        assertEquals(gate1.isEnabled(), gate2.isEnabled());
    }

    // ==================== 5. 端到端：ScriptRunner + SandboxConfig ====================

    @Test
    public void e2e_noRestriction_normalExecution() {
        // 无限制模式：正常执行
        Object result = runner.executeWithResult("1 + 2");
        assertEquals(3, result);
    }

    @Test
    public void e2e_noRestriction_classAccess() {
        // 无限制模式：可以访问任意类
        Object result = runner.executeWithResult("Math.max(10, 20)");
        assertEquals(20, result);
    }

    @Test
    public void e2e_sandbox_blocksRuntimeExec() {
        runner.applySandboxConfig(SandboxConfig.SANDBOX);

        try {
            runner.executeWithResult("java.lang.Runtime.getRuntime().exec(\"id\")");
            fail("应该被 SecurityException 拦截");
        } catch (Exception e) {
            // 可能是 SecurityException 或其包装异常
            String msg = getRootCauseMessage(e);
            assertTrue("期望包含 'SecurityGate' 或 '拒绝' 或 'denied'，实际: " + msg,
                    msg.contains("SecurityGate") || msg.contains("拒绝")
                            || msg.contains("denied") || msg.contains("blocked"));
        }
    }

    @Test
    public void e2e_sandbox_allowsSafeOperations() {
        runner.applySandboxConfig(SandboxConfig.SANDBOX);

        // 数学运算应该正常
        Object result = runner.executeWithResult("42 * 100");
        assertEquals(4200, result);

        // 字符串操作应该正常
        result = runner.executeWithResult("\"hello\".toUpperCase()");
        assertEquals("HELLO", result);

        // List 操作应该正常（用单行语句避免多行解析问题）
        result = runner.executeWithResult("new java.util.ArrayList<>().size()");
        assertEquals(0, result);
    }

    @Test
    public void e2e_clearSandbox_restoresFullAccess() {
        // 先设置沙箱
        runner.applySandboxConfig(SandboxConfig.SANDBOX);
        // 清除沙箱
        runner.applySandboxConfig(null);
        // 应该恢复正常执行
        Object result = runner.executeWithResult("1 + 2 + 3");
        assertEquals(6, result);
    }

    @Test
    public void e2e_customChecker_fineGrainedControl() {
        // 自定义检查器：只允许 java.lang 和 java.util
        IPermissionChecker custom = BasicPermissionChecker.builder()
                .defaultDeny()
                .allowClass("java.lang.*")
                .allowClass("java.util.*")
                .allowPermission(PermissionType.METHOD_CALL)
                .allowPermission(PermissionType.FIELD_READ)
                .allowPermission(PermissionType.NEW_INSTANCE)
                .build();

        runner.setPermissionChecker(custom);

        // 允许的操作
        Object result = runner.executeWithResult("\"test\".length()");
        assertEquals(4, result);

        result = runner.executeWithResult("new java.util.ArrayList<>().size()");
        assertEquals(0, result);
    }

    @Test
    public void e2e_setterGetter_roundTrip() {
        assertNull("初始无检查器", runner.getPermissionChecker());

        IPermissionChecker custom = BasicPermissionChecker.createMinimal();
        runner.setPermissionChecker(custom);
        assertSame(custom, runner.getPermissionChecker());

        runner.setPermissionChecker(null);
        assertNull(runner.getPermissionChecker());
    }

    @Test
    public void e2e_fullPreset_allowsDangerousOps() {
        runner.applySandboxConfig(SandboxConfig.FULL);
        // FULL 模式下系统级权限全部开放，解释器级也用 permissive
        Object result = runner.executeWithResult("new Object().getClass().getName()");
        assertEquals("java.lang.Object", result);
    }

    // ==================== 6. builtin 反射入口与 gate 接线 ====================

    @Test
    public void e2e_builtinReflectionHonorsGate() {
        // getField / setField / invokeMethod / analyze / cast / isInstanceOf 自己做反射，
        // 修复前完全不经过 SecurityGate —— 是绕过策略的后门
        IPermissionChecker denyString = BasicPermissionChecker.builder()
                .defaultAllow()
                .denyClass("java.lang.String")
                .build();
        runner.setPermissionChecker(denyString);

        // 基准：解释器直接调用被拦
        assertSecurityDenied("\"abc\".length()");
        // 后门入口：必须走同一套检查
        assertSecurityDenied("invokeMethod(\"abc\", \"length\")");
        assertSecurityDenied("getField(\"abc\", \"value\")");
        assertSecurityDenied("analyze(\"abc\")");
        assertSecurityDenied("isInstanceOf(\"abc\", \"java.lang.String\")");
        // lambda 在子 EvalContext 中执行，builtin 实例是新创建的 → gate 必须继承下去
        assertSecurityDenied("map([\"abc\"], s -> invokeMethod(s, \"length\"))");
    }

    @Test
    public void e2e_builtinReflectionWorksWithoutGate() {
        // 零开销模式（checker == null）下这些入口必须照常工作
        assertNull(runner.getPermissionChecker());
        assertEquals(3, runner.executeWithResult("invokeMethod(\"abc\", \"length\")"));
        assertNotNull(runner.executeWithResult("getField(\"abc\", \"value\")"));
        assertEquals(Boolean.TRUE, runner.executeWithResult("isInstanceOf(\"abc\", \"java.lang.String\")"));
    }

    @Test
    public void e2e_runLaterRequiresThreadPermission() {
        runner.setPermissionChecker(BasicPermissionChecker.builder()
                .defaultAllow()
                .denyPermission(PermissionType.THREAD_CREATE)
                .build());
        assertSecurityDenied("runLater(() -> 1);");
    }

    // ==================== 工具方法 ====================

    /** 断言脚本被安全策略拦截（异常链最深处是 SecurityException）。 */
    private void assertSecurityDenied(String script) {
        try {
            runner.executeWithResult(script);
            fail("应当被安全策略拦截: " + script);
        } catch (Throwable e) {
            String msg = getRootCauseMessage(e);
            assertTrue("期望安全策略拒绝，实际: " + msg, msg.contains("SecurityGate"));
        }
    }

    private static String getRootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : "";
    }
}
