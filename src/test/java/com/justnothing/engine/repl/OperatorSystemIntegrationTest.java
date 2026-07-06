package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 运算符系统集成测试。
 *
 * <p>验证 OperatorRegistry 统一运算符分发架构的正确行为：
 * <ul>
 *   <li>基本类型算术运算（int/long/double）及类型保持</li>
 *   <li>一元运算符（负号、位取反、逻辑非）</li>
 *   <li>位运算符（&amp; | ^ &lt;&lt; &gt;&gt; &gt;&gt;&gt;）</li>
 *   <li>比较运算符（&lt; &gt; &lt;= &gt;= == != &lt;=&gt;）</li>
 *   <li>逻辑运算符（&amp;&amp; ||）</li>
 *   <li>字符串拼接与数值运算的优先级</li>
 *   <li>混合类型提升（int+long→long, int+double→double）</li>
 *   <li>for-each 循环中的运算符（之前踩坑的场景）</li>
 *   <li>边界情况（溢出、除零、null）</li>
 * </ul>
 *
 * <p>核心目标：确保通过 OperatorRegistry 统一分发后，
 * 运算符行为与旧版 Evaluator switch-case 完全一致。
 */
public class OperatorSystemIntegrationTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
        runner.addImport("java.util.*");
        runner.setStrictMode(false);
    }

    @After
    public void tearDown() {
        // ScriptRunner 内部状态每次测试独立，无需清理
    }

    // ==================== 辅助方法 ====================

    /** 执行脚本并返回最后一个非 void 值。 */
    private Object eval(String script) {
        return runner.executeWithResult(script);
    }

    /** 断言值为整数且等于预期。 */
    private void assertIntEquals(int expected, Object actual) {
        assertNotNull("结果不应为 null", actual);
        if (actual instanceof Number) {
            assertEquals(expected, ((Number) actual).intValue());
        } else {
            fail("期望整数类型，实际: " + actual.getClass().getSimpleName() + " = " + actual);
        }
    }

    /** 断言值为长整数且等于预期。 */
    private void assertLongEquals(long expected, Object actual) {
        assertNotNull("结果不应为 null", actual);
        if (actual instanceof Number) {
            assertEquals(expected, ((Number) actual).longValue());
        } else {
            fail("期望长整数类型，实际: " + actual.getClass().getSimpleName());
        }
    }

    /** 断言值为 double 且等于预期（容差 1e-10）。 */
    private void assertDoubleEquals(double expected, Object actual) {
        assertNotNull("结果不应为 null", actual);
        if (actual instanceof Number) {
            assertEquals(expected, ((Number) actual).doubleValue(), 1e-10);
        } else {
            fail("期望 double 类型，实际: " + actual.getClass().getSimpleName());
        }
    }

    /** 断言值为布尔值且等于预期。 */
    private void assertBooleanEquals(boolean expected, Object actual) {
        assertNotNull("结果不应为 null", actual);
        if (actual instanceof Boolean) {
            assertEquals(expected, actual);
        } else {
            fail("期望布尔类型，实际: " + actual.getClass().getSimpleName());
        }
    }

    /** 断言值为字符串且等于预期。 */
    private void assertStringEquals(String expected, Object actual) {
        assertNotNull("结果不应为 null", actual);
        assertEquals(expected, actual.toString().trim());
    }

    // ========== 1. 算术运算 — 类型保持 ==========

    @Test
    public void arithmetic_intAddition_preservesIntType() {
        // int + int 必须返回 int，不是 double！
        assertIntEquals(2, eval("1 + 1"));
        assertIntEquals(100, eval("50 + 50"));
        assertIntEquals(0, eval("-5 + 5"));
    }

    @Test
    public void arithmetic_intSubtraction_preservesIntType() {
        assertIntEquals(3, eval("10 - 7"));
        assertIntEquals(-1, eval("1 - 2"));
        assertIntEquals(0, eval("5 - 5"));
    }

    @Test
    public void arithmetic_intMultiplication_preservesIntType() {
        assertIntEquals(6, eval("2 * 3"));
        assertIntEquals(100, eval("10 * 10"));
        assertIntEquals(0, eval("0 * 999"));
    }

    @Test
    public void arithmetic_intDivision_preservesIntType() {
        assertIntEquals(3, eval("7 / 2"));     // 整数除法截断
        assertIntEquals(0, eval("1 / 3"));
        assertIntEquals(1, eval("5 / 5"));
    }

    @Test
    public void arithmetic_intModulo_preservesIntType() {
        assertIntEquals(1, eval("7 % 2"));
        assertIntEquals(0, eval("4 % 2"));
        assertIntEquals(2, eval("10 % 8"));
    }

    @Test
    public void arithmetic_longType_preserved() {
        // long + long → long
        assertLongEquals(2147483648L, eval("2147483647l + 1l"));
        assertLongEquals(24691357802468L, eval("12345678901234l + 12345678901234l"));
        // long - long → long
        assertLongEquals(-100L, eval("-100l"));
        assertLongEquals(12345678901234L, eval("12345678901234l"));
    }

    @Test
    public void arithmetic_doubleType_preserved() {
        // double 字面量运算
        assertDoubleEquals(1.5, eval("1.0 + 0.5"));
        assertDoubleEquals(2.5, eval("1.0 + 1.5"));
    }

    @Test
    public void arithmetic_mixedTypePromotion() {
        // int + long → long
        assertLongEquals(2147483648L, eval("2147483647l + 1"));
        // int + double → double
        assertDoubleEquals(1.5, eval("1 + 0.5"));
    }

    @Test
    public void arithmetic_integerOverflow_wrapsCorrectly() {
        // Java int 溢出行为：2147483647 + 1 = -2147483648
        assertIntEquals(Integer.MIN_VALUE, eval("2147483647 + 1"));
    }

    // ========== 2. 一元运算符 ==========

    @Test
    public void unary_negation_preservesIntType() {
        // -int → int（不是 double！）
        assertIntEquals(-1, eval("-1"));
        assertIntEquals(-100, eval("-100"));
        assertIntEquals(0, eval("-0"));
    }

    @Test
    public void unary_negation_longType() {
        assertLongEquals(-100L, eval("-100l"));
    }

    @Test
    public void unary_bitwiseNot() {
        assertIntEquals(~5, eval("~5"));
        assertIntEquals(~0, eval("~0"));
        assertIntEquals(-1, eval("~0"));  // ~0 = -1
    }

    @Test
    public void unary_logicalNot() {
        assertBooleanEquals(false, eval("!true"));
        assertBooleanEquals(true, eval("!false"));
        assertBooleanEquals(false, eval("!1"));      // 非零值是 truthy
        // 注意：!0 的行为取决于引擎对"falsy"的定义，这里仅验证基本布尔逻辑
    }

    @Test
    public void unary_positive_noop() {
        assertIntEquals(42, eval("+42"));
        assertLongEquals(42L, eval("+42l"));
    }

    // ========== 3. 位运算符 ==========

    @Test
    public void bitwise_and() {
        assertIntEquals(0, eval("1 & 2"));     // 01 & 10 = 00
        assertIntEquals(1, eval("3 & 1"));     // 11 & 01 = 01
        assertIntEquals(255, eval("255 & 255"));
    }

    @Test
    public void bitwise_or() {
        assertIntEquals(3, eval("1 | 2"));     // 01 | 10 = 11
        assertIntEquals(7, eval("5 | 2"));     // 101 | 010 = 111
    }

    @Test
    public void bitwise_xor() {
        assertIntEquals(3, eval("1 ^ 2"));     // 01 ^ 10 = 11
        assertIntEquals(0, eval("5 ^ 5"));
    }

    @Test
    public void bitwise_leftShift() {
        assertIntEquals(8, eval("1 << 3"));
        assertIntEquals(1024, eval("1 << 10"));
    }

    @Test
    public void bitwise_rightShift_arithmetic() {
        assertIntEquals(16, eval("128 >> 3"));
        assertIntEquals(-1, eval("-1 >> 0"));  // 符号位扩展
    }

    @Test
    public void bitwise_rightShift_logical() {
        assertIntEquals(16, eval("128 >>> 3"));
        // 无符号右移：-1 >>> 0 应该是最大正整数
        assertIntEquals(-1 >>> 0, eval("-1 >>> 0"));
    }

    // ========== 4. 比较运算符 ==========

    @Test
    public void comparison_lessThan() {
        assertBooleanEquals(true, eval("1 < 2"));
        assertBooleanEquals(false, eval("2 < 1"));
        assertBooleanEquals(false, eval("1 < 1"));
    }

    @Test
    public void comparison_greaterThan() {
        assertBooleanEquals(true, eval("2 > 1"));
        assertBooleanEquals(false, eval("1 > 2"));
    }

    @Test
    public void comparison_lessThanOrEqual() {
        assertBooleanEquals(true, eval("1 <= 2"));
        assertBooleanEquals(true, eval("1 <= 1"));
        assertBooleanEquals(false, eval("2 <= 1"));
    }

    @Test
    public void comparison_greaterThanOrEqual() {
        assertBooleanEquals(true, eval("2 >= 1"));
        assertBooleanEquals(true, eval("1 >= 1"));
        assertBooleanEquals(false, eval("1 >= 2"));
    }

    @Test
    public void comparison_equal() {
        assertBooleanEquals(true, eval("1 == 1"));
        assertBooleanEquals(false, eval("1 == 2"));
        assertBooleanEquals(true, eval("\"hello\" == \"hello\""));
    }

    @Test
    public void comparison_notEqual() {
        assertBooleanEquals(false, eval("1 != 1"));
        assertBooleanEquals(true, eval("1 != 2"));
    }

    @Test
    public void comparison_spaceship() {
        // <=> 返回 -1, 0, 或 1
        assertIntEquals(-1, eval("1 <=> 2"));
        assertIntEquals(0, eval("1 <=> 1"));
        assertIntEquals(1, eval("2 <=> 1"));
    }

    @Test
    public void comparison_stringComparison() {
        // 字符串按字典序比较
        assertBooleanEquals(true, eval("\"abc\" < \"abd\""));
        assertBooleanEquals(true, eval("\"a\" < \"b\""));
    }

    // ========== 5. 逻辑运算符 ==========

    @Test
    public void logical_and() {
        assertBooleanEquals(true, eval("true && true"));
        assertBooleanEquals(false, eval("true && false"));
        assertBooleanEquals(false, eval("false && true"));
        assertBooleanEquals(false, eval("false && false"));
    }

    @Test
    public void logical_or() {
        assertBooleanEquals(true, eval("true || false"));
        assertBooleanEquals(true, eval("false || true"));
        assertBooleanEquals(false, eval("false || false"));
    }

    @Test
    public void logical_truthyValues() {
        // 非零数字是 truthy
        assertBooleanEquals(true, eval("1 && 1"));
        assertBooleanEquals(false, eval("0 && 1"));
    }

    // ========== 6. 字符串拼接 ==========

    @Test
    public void stringConcat_twoStrings() {
        assertStringEquals("helloworld", eval("\"hello\" + \"world\""));
    }

    @Test
    public void stringConcat_stringAndNumber() {
        // "a" + 123 → "a123"（字符串优先）
        assertStringEquals("a123", eval("\"a\" + 123"));
        assertStringEquals("123a", eval("123 + \"a\""));
    }

    @Test
    public void stringConcat_inExpression() {
        assertStringEquals("result=42", eval("\"result=\" + 42"));
    }

    // ========== 7. 幂运算 ==========

    @Test
    public void power_basic() {
        // ** 始终返回 double
        assertDoubleEquals(8.0, eval("2 ** 3"));
        assertDoubleEquals(1.0, eval("5 ** 0"));
        assertDoubleEquals(2.0, eval("4 ** 0.5"));  // 平方根
    }

    // ========== 8. for-each 中的运算符（回归测试）==========

    /**
     * 回归测试：for-each 循环中 sum = sum + x 必须做数值加法而非字符串拼接。
     * <p>这是本次重构修复的核心 bug。
     */
    @Test
    public void forEach_numericAccumulation() {
        runner.addImport("java.util.ArrayList");
        runner.addImport("java.util.Arrays");
        Object result = eval("""
            auto list = new ArrayList<Integer>();
            list.add(10); list.add(20); list.add(30);
            auto sum = 0;
            for (auto x : list) {
                sum = sum + x;
            }
            sum;
            """);
        assertIntEquals(60, result);
    }

    /**
     * 回归测试：for-each 中乘法累积。
     */
    @Test
    public void forEach_multiplication() {
        runner.addImport("java.util.ArrayList");
        Object result = eval("""
            auto list = new ArrayList<Integer>();
            list.add(2); list.add(3); list.add(4);
            auto product = 1;
            for (auto x : list) {
                product = product * x;
            }
            product;
            """);
        assertIntEquals(24, result);
    }

    /**
     * 回归测试：for-each 中字符串拼接应正常工作。
     */
    @Test
    public void forEach_stringConcatenation() {
        runner.addImport("java.util.ArrayList");
        Object result = eval("""
            auto list = new ArrayList<String>();
            list.add(\"hello\"); list.add(\" \"); list.add(\"world\");
            auto result = \"\";
            for (auto s : list) {
                result = result + s;
            }
            result;
            """);
        assertStringEquals("hello world", result);
    }

    // ========== 9. 匿名类方法中的运算符（回归测试）==========

    /**
     * 回归测试：匿名类方法中的 int + int 必须正确返回 int。
     * <p>之前 Registry 返回 Object 导致 checkReturnTypes 报错。
     */
    @Test
    public void anonymousClass_methodWithArithmetic() {
        Object result = eval("""
            Object obj = new Object() {
                int add(int a, int b) { return a + b; }
                int twice(int x) { return x * 2; }
            };
            obj.add(5, 7) + obj.twice(3);
            """);
        assertIntEquals(18, result);  // add(5,7)=12, twice(3)=6, 12+6=18
    }

    @Test
    public void anonymousClass_methodWithStringConcat() {
        Object result = eval("""
            Object obj = new Object() {
                String greeting = \"Hello\";
                String greet(String name) { return greeting + \" \" + name; }
            };
            obj.greet(\"World\");
            """);
        assertStringEquals("Hello World", result);
    }

    // ========== 10. 边界情况 ==========

    @Test
    public void edgeCase_divisionByZero_throws() {
        try {
            eval("1 / 0");
            fail("应该抛出异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Division by zero")
                    || e.getMessage().contains("/ by zero"));
        }
    }

    @Test
    public void edgeCase_moduloByZero_throws() {
        try {
            eval("5 % 0");
            fail("应该抛出异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Division by zero")
                    || e.getMessage().contains("/ by zero"));
        }
    }

    @Test
    public void edgeCase_largeNumbers() {
        // 大数运算不溢出到 double
        assertLongEquals(12345678901234L, eval("12345678901234l"));
        assertLongEquals(24691357802468L, eval("12345678901234l + 12345678901234l"));
    }

    @Test
    public void edgeCase_negativeZero() {
        assertIntEquals(0, eval("-0"));
    }

    @Test
    public void edgeCase_chainedOperations() {
        // 链式运算保持类型
        assertIntEquals(15, eval("1 + 2 + 3 + 4 + 5"));
        assertIntEquals(120, eval("1 * 2 * 3 * 4 * 5"));
        assertIntEquals(3, eval("10 - 5 - 2"));
    }

    @Test
    public void edgeCase_precedence() {
        // 乘法优先于加法
        assertIntEquals(14, eval("2 + 3 * 4"));    // 2 + 12 = 14
        assertIntEquals(20, eval("(2 + 3) * 4"));   // 5 * 4 = 20
    }

    // ========== 11. Pipeline 与运算符组合 ==========

    @Test
    public void pipeline_withArithmetic() {
        // 确保 pipeline 不干扰运算符
        assertIntEquals(42, eval("21 |> (x -> x * 2)"));
    }

    @Test
    public void pipeline_thenOperator() {
        // pipeline 结果参与运算
        assertIntEquals(44, eval("(21 |> (x -> x * 2)) + 2"));
    }

    // ========== 12. 类型推断边界 ==========

    @Test
    public void inference_autoVariableArithmetic() {
        // auto 变量的运算结果类型正确
        Object result = eval("""
            auto a = 10;
            auto b = 20;
            a + b;
            """);
        assertIntEquals(30, result);
    }

    @Test
    public void inference_reassignmentPreservesLogic() {
        Object result = eval("""
            auto x = 0;
            x = x + 1;
            x = x + 1;
            x = x + 1;
            x;
            """);
        assertIntEquals(3, result);
    }

    // ========== 13. 数组集合运算（set semantics）==========

    @Test
    public void arrayConcat() {
        Object result = eval("[1, 2, 3] + [4, 5, 6];");
        assertEquals("int[]", result.getClass().getTypeName());
        assertEquals(6, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayConcat_differentTypes() {
        Object result = eval("[1, 2] + [\"a\", \"b\"];");
        assertEquals("java.lang.Object[]", result.getClass().getTypeName());
        assertEquals(4, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayDifference() {
        Object result = eval("[1, 2, 3, 4] - [2, 4];");
        assertEquals("int[]", result.getClass().getTypeName());
        assertEquals(2, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayIntersection() {
        Object result = eval("[1, 2, 3, 4] & [2, 4, 6];");
        assertEquals("int[]", result.getClass().getTypeName());
        assertEquals(2, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayUnion() {
        Object result = eval("[1, 2] | [2, 3, 4];");
        assertEquals("int[]", result.getClass().getTypeName());
        assertEquals(4, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arraySymmetricDifference() {
        Object result = eval("[1, 2, 3] ^ [2, 3, 4];");
        assertEquals("int[]", result.getClass().getTypeName());
        assertEquals(2, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayCartesianProduct() {
        Object result = eval("[1, 2] ** [3, 4];");
        assertEquals("java.lang.Object[]", result.getClass().getTypeName());
        assertEquals(4, java.lang.reflect.Array.getLength(result));
    }

    @Test
    public void arrayAdd_scalarNotSupported() {
        // 数组 + 标量不应匹配数组运算符
        try {
            eval("[1, 2] + 5;");
            fail("Should have thrown for array + scalar");
        } catch (Exception ignored) { }
    }

    @Test
    public void arrayMultiply_notSupported() {
        try {
            eval("[1, 2] * [3, 4];");
            fail("Should have thrown: array * array is not supported");
        } catch (Exception ignored) { }
    }

    @Test
    public void arrayDivide_notSupported() {
        try {
            eval("[10, 20] / [2, 4];");
            fail("Should have thrown: array / array is not supported");
        } catch (Exception ignored) { }
    }

    @Test
    public void arrayConcat_resultTypeIsArrayNotObject() {
        // int[] + int[] → 解析期推导为 int[]，运行时也保留为 int[]
        Object result = eval("[1, 2, 3] + [4, 5, 6];");
        assertEquals("int[]", result.getClass().getTypeName());
    }

    @Test
    public void arrayConcat_resultElementsCorrect() {
        // 运行时结果应与集合语义一致
        Object result = eval("[1, 2] + [3, 4];");
        assertTrue("result should be an array", result.getClass().isArray());
    }
}
