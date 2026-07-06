package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Lambda 解析回归测试 — 排查 "(name) -> { ... }" 报 Cannot find symbol: 'name' 的问题
 *
 * 根因假设：tryParseLambdaInParens 内部 body 解析失败时抛出语义异常，
 * parseParenExpressionOrLambda 的 catch 块因 isSemanticError=true 直接 rethrow，
 * 导致 fallback 到普通括号表达式路径，此时 name 作为裸标识符解析失败。
 */
public class LambdaStrictModeTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
        // 默认 strictMode=true（不手动设 false）
        runner.addImport("java.util.HashMap");
        runner.addImport("java.util.ArrayList");
    }

    @After
    public void tearDown() {
        runner = null;
    }

    // ========== Case 1: 单参数无括号 lambda ==========

    @Test
    public void singleParamNoParens() {
        Object result = runner.executeWithResult("auto func = x -> 42;");
        assertNotNull("单参数无括号 lambda 应该成功", result);
    }

    // ========== Case 2: 单参数有括号 lambda（核心复现案例）==========

    @Test
    public void singleParamWithParens() {
        Object result = runner.executeWithResult("auto func = (name) -> 42;");
        assertNotNull("单参数有括号 lambda 应该成功", result);
    }

    @Test
    public void singleParamWithParens_useParam() {
        Object result = runner.executeWithResult("auto func = (name) -> name.length();");
        assertNotNull("lambda 参数应该可访问", result);
    }

    // ========== Case 3: 无括号 lambda + body 内类型错误 ==========

    @Test
    public void singleParamNoParens_bodyTypeError() {
        try {
            runner.execute("auto func = x -> { auto error = new HashMap<String, String, ThisCausesATypeError>(); };");
            fail("预期类型错误");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertFalse("不应报告 Cannot find symbol（lambda 回退 bug）",
                    msg.contains("Cannot find symbol"));
        }
    }

    // ========== Case 4: 有括号 lambda + body 内类型错误（最可能触发 bug）==========

    @Test
    public void singleParamWithParens_bodyTypeError() {
        try {
            runner.execute("auto func = (x) -> { auto error = new HashMap<String, String, ThisCausesATypeError>(); };");
            fail("预期类型错误");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertFalse("BUG! 不应报告 Cannot find symbol: 'x'，说明 lambda 回退到了普通括号表达式",
                    msg.contains("Cannot find symbol"));
        }
    }

    // ========== Case 5: 模拟 MiniApp 的 createPlayer lambda ==========

    @Test
    public void miniAppStyle_lambda() {
        Object result = runner.executeWithResult("""
            auto createPlayer = (name) -> {
                auto player = HashMap.new();
                player.put("name", name);
                player.put("hp", 100);
                player;
            };
            auto p = createPlayer("Hero");
            p.get("name");
            """);
        assertEquals("Hero", result);
    }
}
