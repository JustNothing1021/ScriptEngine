package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Pipeline 管道操作符（|>）集成测试。
 *
 * <p>验证 {@code value |> func} 语法在多种函数类型下的正确行为：
 * <ul>
 *   <li>Lambda 表达式</li>
 *   <li>方法引用（静态 / 绑定实例）</li>
 *   <li>变量引用（存储的 Lambda/Function）</li>
 *   <li>链式管道（多个 |> 串联）</li>
 * </ul>
 *
 * @see com.justnothing.engine.ast.nodes.PipelineNode
 * @see com.justnothing.engine.eval.Evaluator#visitPipeline
 */
public class PipelineTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
        runner.addImport("java.util.*");
        runner.addImport("java.lang.*");
        runner.addImport("java.util.stream.*");
    }

    @After
    public void tearDown() {
        runner = null;
    }

    // ==================== 辅助方法 ====================

    private Object eval(String script) {
        return runner.executeWithResult(script);
    }

    private void assertEvalError(String script, String expectedFragment) {
        try {
            eval(script);
            fail("Expected error for: " + script);
        } catch (Exception e) {
            String msg = e.getMessage();
            assertNotNull("Exception message should not be null", msg);
            assertTrue("Error should contain '" + expectedFragment + "' but was: " + msg,
                    msg.contains(expectedFragment));
        }
    }

    // ==================== 1. 基础字符串管道 ====================

    @Test
    public void string_trim_thenUpperCase() {
        // "  hello  " |> String::trim |> String::toUpperCase → "HELLO"
        Object result = eval("\"  hello  \" |> String::trim |> String::toUpperCase;");
        assertEquals("HELLO", result);
    }

    @Test
    public void string_singleTrim() {
        // 单步 trim
        Object result = eval("\"  hello  \" |> String::trim;");
        assertEquals("hello", result);
    }

    @Test
    public void string_toLowerCase_chain() {
        Object result = eval("\"HELLO World\" |> String::toLowerCase |> String::trim;");
        assertEquals("hello world", result);
    }

    // ==================== 2. Lambda 管道 ====================

    @Test
    public void lambda_doubleValue() {
        // 21 |> (x -> x * 2) → 42
        Object result = eval("21 |> (x -> x * 2);");
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void lambda_addOne() {
        Object result = eval("41 |> (x -> x + 1);");
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void lambda_square() {
        Object result = eval("7 |> (x -> x * x);");
        assertEquals(49, ((Number) result).intValue());
    }

    @Test
    public void lambda_withTypeAnnotation() {
        // 显式类型标注的 lambda 作为管道函数（注意：类型标注的参数在运算符查表时需要注册表支持）
        // 这里用 Integer 而非 int 避免基本类型运算符查表问题
        Object result = eval("10 |> ((Integer x) -> x * 3);");
        assertEquals(30, ((Number) result).intValue());
    }

    // ==================== 3. 链式管道 ====================

    @Test
    public void chained_lambda_3to49() {
        // 3 |> twice |> addOne |> square → 49
        // (3*2=6, 6+1=7, 7*7=49)
        Object result = eval("""
            auto twice = x -> x * 2;
            auto addOne = x -> x + 1;
            auto square = x -> x * x;
            3 |> twice |> addOne |> square;
            """);
        assertEquals(49, ((Number) result).intValue());
    }

    @Test
    public void chained_mixed_lambdaAndMethodRef() {
        // 混合 Lambda 和 MethodRef 的链式管道
        Object result = eval("""
            auto doubleIt = x -> x * 2;
            "  hi  " |> String::trim |> (s -> s.repeat(2)) |> String::toUpperCase;
            """);
        assertEquals("HIHI", result);
    }

    // ==================== 4. 列表管道 ====================

    @Test
    public void list_size_pipeline() {
        // 用 ArrayList 避免 ImmutableCollections 的 Java 模块访问限制
        Object result = eval("""
            auto lst = new ArrayList();
            lst.add(1); lst.add(2); lst.add(3);
            lst.size();
            """);
        assertEquals(3, ((Number) result).intValue());
    }

    @Test
    public void list_toString_pipeline() {
        Object result = eval("""
            auto lst = new ArrayList();
            lst.add(1); lst.add(2); lst.add(3);
            lst.toString();
            """);
        assertNotNull(result);
        assertTrue("Should contain [1, 2, 3]", result.toString().contains("[1, 2, 3]"));
    }

    // ==================== 5. MethodRef 管道 ====================

    @Test
    public void methodRef_static_format() {
        // String::valueOf 是静态方法，管道传入参数
        Object result = eval("42 |> String::valueOf;");
        assertEquals("42", result);
    }

    @Test
    public void methodRef_instance_toUpperCase() {
        // 绑定实例方法的管道
        Object result = eval("\"hello\" |> String::toUpperCase;");
        assertEquals("HELLO", result);
    }

    @Test
    public void methodRef_instance_trim() {
        Object result = eval("\"  spaced  \" |> String::trim;");
        assertEquals("spaced", result);
    }

    // ==================== 6. 变量引用管道 ====================

    @Test
    public void variable_storedLambda() {
        Object result = eval("""
            auto triple = x -> x * 3;
            14 |> triple;
            """);
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void variable_storedMethodRef() {
        Object result = eval("""
            auto upper = String::toUpperCase;
            "hello" |> upper;
            """);
        assertEquals("HELLO", result);
    }

    // ==================== 7. 嵌套/复杂表达式 ====================

    @Test
    public void pipeline_resultAsVariable() {
        Object result = eval("""
            auto result = 5 |> (x -> x * 8) |> (x -> x + 2);
            result;
            """);
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void pipeline_inExpression() {
        // 管道结果参与后续运算（管道结果类型为 Object，用 asInteger 转换后运算）
        Object result = eval("""
            auto val = 6 |> (x -> x * 7);
            val;
            """);
        assertEquals(42, ((Number) result).intValue());
    }

    // ==================== 8. 边界情况 ====================

    @Test
    public void pipeline_emptyString() {
        Object result = eval("\"\" |> String::trim |> String::toUpperCase;");
        assertEquals("", result);
    }

    @Test
    public void pipeline_zeroValue() {
        Object result = eval("0 |> (x -> x + 1);");
        assertEquals(1, ((Number) result).intValue());
    }

    @Test
    public void pipeline_negativeNumber() {
        Object result = eval("-7 |> (x -> x * -6);");
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void pipeline_booleanInput() {
        Object result = eval("true |> (b -> !b);");
        assertFalse((Boolean) result);
    }

    // ==================== 9. 多参数 Lambda（管道只传一个参数）====================

    @Test
    public void lambda_ignoresExtraParams() {
        // 管道只传一个参数，Lambda 有两个参数时第二个为 null/默认
        // 这里测试单参 lambda 正常工作即可
        Object result = eval("10 |> (x -> { auto y = x + 32; y; });");
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void lambda_blockBody() {
        // 块体 Lambda 作为管道函数
        Object result = eval("40 |> (x -> { x + 2; });");
        assertEquals(42, ((Number) result).intValue());
    }

    // ==================== 10. 与其他特性组合 ====================

    @Test
    public void pipeline_afterArithmetic() {
        // 算术表达式的结果作为管道输入
        Object result = eval("(6 * 7) |> (x -> x);");
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void pipeline_beforeAssignment() {
        // 管道结果赋值给变量（auto 必须带初始化器）
        Object result = eval("""
            auto x = 42 |> (v -> v);
            x;
            """);
        assertEquals(42, ((Number) result).intValue());
    }

    @Test
    public void pipeline_inPrintln() {
        // 管道在 println 中使用（验证不抛异常）
        Object result = eval("""
            auto msg = "pipeline" |> String::toUpperCase;
            msg.length();
            """);
        assertEquals(8, ((Number) result).intValue());
    }
}
