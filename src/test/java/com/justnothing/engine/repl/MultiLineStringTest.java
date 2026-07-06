package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 多行字符串（"""...""" / f"""...""")集成测试。
 *
 * <p>验证三引号语法的正确行为：
 * <ul>
 *   <li>原始多行字符串（保留换行、缩进修剪）</li>
 *   <li>多行插值字符串（${expr} / $var）</li>
 *   <li>与 Pipeline 的组合使用</li>
 *   <li>边界情况（空内容、单行、特殊字符）</li>
 * </ul>
 *
 */
public class MultiLineStringTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
        runner.addImport("java.util.*");
        runner.addImport("java.lang.*");
    }

 

    // ==================== 辅助方法 ====================

    private Object eval(String script) {
        return runner.executeWithResult(script);
    }

    // ========== Lexer 诊断 ==========

    @Test
    public void testLexerDirectMultiLine() throws Exception {
        // 直接测试 Lexer 对 """ 的 tokenization
        String input = "auto s = \"\"\"\n    hello\n    world\n\"\"\";\ns;";
        var lexer = new com.justnothing.engine.lexer.Lexer(input, "<test>");
        var tokens = lexer.tokenize();
        StringBuilder sb = new StringBuilder();
        for (var t : tokens) {
            sb.append(t.type()).append(":").append(t.value())
              .append("[").append(t.location().getLine()).append(":")
              .append(t.location().getColumn()).append("] ");
        }
        boolean found = false;
        for (var t : tokens) {
            if (t.type() == com.justnothing.engine.lexer.TokenType.LITERAL_MULTI_LINE_STRING) {
                found = true;
            }
        }
        assertTrue("Should have LITERAL_MULTI_LINE_STRING token", found);
    }

    @Test
    public void testLexerDirectSingleLine() throws Exception {
        String input = "\"\"\"hello\"\"\"";
        var lexer = new com.justnothing.engine.lexer.Lexer(input, "<test>");
        var tokens = lexer.tokenize();
        assertEquals(2, tokens.size()); // ML_STRING + EOF
    }

    @Test
    public void testPreprocessorMultiLine() throws Exception {
        // 测试 Preprocessor 是否正确保留 """
        String input = "auto s = \"\"\"\n    hello\n    world\n\"\"\";\ns;";
        var pp = new com.justnothing.engine.preprocessor.Preprocessor();
        String output = pp.process(input);
        // 预处理器不应破坏 """ 结构
        assertTrue("Output should contain triple quote", output.contains("\"\"\""));
    }

    @Test
    public void testPreprocessorThenLexer() throws Exception {
        // 完整链路：Preprocessor → Lexer → Parser
        String input = "auto s = \"\"\"\n    hello\n    world\n\"\"\";\ns;";
        var pp = new com.justnothing.engine.preprocessor.Preprocessor();
        String processed = pp.process(input);

        var lexer = new com.justnothing.engine.lexer.Lexer(processed, "<test>");
        var tokens = lexer.tokenize();
        boolean foundML = false;
        for (var t : tokens) {
            if (t.type() == com.justnothing.engine.lexer.TokenType.LITERAL_MULTI_LINE_STRING) {
                foundML = true;
            }
        }
        assertTrue("Should have ML token after preprocessing", foundML);
    }

    @Test
    public void testMLStringConcat() throws Exception {
        // 最小复现：两个单行 ML 字符串拼接
        Object r1 = eval("auto a = \"\"\"hello\"\"\";");
        Object r2 = eval("auto b = \"\"\"world\"\"\";");
        Object r3 = eval("a + b;");
        assertEquals("helloworld", r3);
    }

    // ========== 基础 Raw 多行字符串 ==========

    @Test
    public void testBasicMultiLineString() {
        Object result = eval("""
                auto s = \"\"\"
                    hello
                    world
                    \"\"\";
                s;
                """);
        assertEquals("hello\nworld", result);
    }

    @Test
    public void testMultiLineStringPreservesNewlines() {
        Object result = eval("""
                auto s = \"\"\"
                    line1
                    line2
                    line3
                    \"\"\";
                s;
                """);
        assertEquals("line1\nline2\nline3", result);
    }

    @Test
    public void testSingleLineMultiLineString() {
        Object result = eval("""
                auto s = \"\"\"hello\"\"\";
                s;
                """);
        assertEquals("hello", result);
    }

    @Test
    public void testEmptyMultiLineString() {
        Object result = eval("""
                auto s = \"\"\"\"\"\";
                s;
                """);
        assertEquals("", result);
    }

    // ========== 缩进修剪 (Python 风格) ==========

    @Test
    public void testIndentTrimming() {
        Object result = eval("""
                auto s = \"\"\"
                        indented
                            more indented
                        back
                    \"\"\";
                s;
                """);
        // 最小公共缩进是 4 空格（"indented" 那行的前导空白）
        assertTrue(result.toString().startsWith("indented"));
    }

    @Test
    public void testIndentTrimmingWithTabs() {
        Object result = eval("""
                auto s = \"\"\"
                \t\ttabbed
                \t\tnormal
                \"\"\";
                s;
                """);
        String str = result.toString();
        assertTrue(str.contains("tabbed"));
        assertTrue(str.contains("normal"));
    }

    // ========== 特殊字符保留 ==========

    @Test
    public void testRawModeNoEscapeProcessing() {
        Object result = eval("""
                auto s = \"\"\"
                    \\n\\t\\r not escaped
                    path: C:\\\\Users\\\\test
                    regex: \\d+
                    \"\"\";
                s;
                """);
        String str = result.toString();
        // Raw 模式下反斜杠原样保留
        assertTrue(str.contains("\\n\\t\\r not escaped"));
        assertTrue(str.contains("\\d+"));
    }

    @Test
    public void testBackslashDollarPreserved() {
        Object result = eval("""
                auto s = \"\"\"
                    price: $100
                    template: ${var}
                    \"\"\";
                s;
                """);
        String str = result.toString();
        assertTrue(str.contains("$100"));
        assertTrue(str.contains("${var}"));
    }

    @Test
    public void testQuotesInside() {
        Object result = eval("""
                auto s = \"\"\"
                    he said "hello"
                    she said 'world'
                    \"\"\";
                s;
                """);
        String str = result.toString();
        assertTrue(str.contains("\"hello\""));
        assertTrue(str.contains("'world'"));
    }

    // ========== 多行插值字符串 f"""...""" ==========

    @Test
    public void testInterpolatedMultiLineBasic() {
        Object result = eval("""
                auto name = "Alice";
                auto age = 30;
                auto msg = f\"\"\"
                    Name: ${name}
                    Age: ${age}
                    \"\"\";
                msg;
                """);
        String str = result.toString();
        assertTrue(str.contains("Name: Alice"));
        assertTrue(str.contains("Age: 30"));
    }

    @Test
    public void testInterpolatedDollarVar() {
        Object result = eval("""
                auto name = "Bob";
                auto msg = f\"\"\"
                    Hello, $name!
                    \"\"\";
                msg;
                """);
        assertEquals("Hello, Bob!", result.toString().trim());
    }

    @Test
    public void testInterpolatedWithExpression() {
        Object result = eval("""
                auto x = 10;
                auto y = 20;
                auto msg = f\"\"\"
                    Sum: ${x + y}
                    Product: ${x * y}
                    \"\"\";
                msg;
                """);
        String str = result.toString();
        assertTrue(str.contains("Sum: 30"));
        assertTrue(str.contains("Product: 200"));
    }

    @Test
    public void testInterpolatedMixedTextAndExpr() {
        Object result = eval("""
                auto items = [1, 2, 3];
                auto msg = f\"\"\"
                    Items: ${items}
                    First: ${items[0]}
                    Last: ${items[items.length - 1]}
                    \"\"\";
                msg;
                """);
        String str = result.toString();
        assertTrue(str.contains("Items: [1, 2, 3]"));
        assertTrue(str.contains("First: 1"));
        assertTrue(str.contains("Last: 3"));
    }

    // ========== 与其他功能组合 ==========

    @Test
    public void testMultiLineStringInPipeline() {
        Object result = eval("""
                auto sql = \"\"\"
                    hello world
                    foo bar
                    \"\"\";
                sql |> String::trim |> String::toUpperCase;
                """);
        assertEquals("HELLO WORLD\nFOO BAR", result.toString());
    }

    @Test
    public void testMultiLineStringAsFunctionArg() {
        Object result = eval("""
                auto s = \"\"\"
                    line1
                    line2
                    \"\"\";
                println(s);
                s.lines();
                """);
        assertNotNull(result);
    }

    @Test
    public void testMultiLineStringConcatenation() {
        Object result = eval("""
                auto a = \"\"\"hello\"\"\";
                auto b = \"\"\" world\"\"\";
                a + b;
                """);
        assertEquals("hello world", result);
    }

    // ========== 边界情况 ==========

    @Test
    public void testOnlyNewlines() {
        Object result = eval("""
                auto s = \"\"\"

                    \"\"\";
                s;
                """);
        assertEquals("", result);
    }

    @Test
    public void testManyLines() {
        StringBuilder code = new StringBuilder();
        code.append("auto s = \"\"\"\n");
        for (int i = 1; i <= 20; i++) {
            code.append("    line").append(i).append("\n");
        }
        code.append("\"\"\";\ns;");
        Object result = eval(code.toString());
        String[] lines = result.toString().split("\n");
        assertEquals(20, lines.length);
        assertEquals("line1", lines[0]);
        assertEquals("line20", lines[19]);
    }

    // ========== 诊断测试：验证嵌套数组访问修复 ==========

    @Test
    public void testDiagnosticNestedArrayAccess() {
        // 普通表达式中的嵌套数组访问
        Object result = eval("auto items = [1, 2, 3]; items[items.length - 1];");
        assertEquals(3, result);
    }

    @Test
    public void testDiagnosticFStringLengthAccess() {
        // f-string 中的数组 length 访问
        Object result = eval("auto arr = [10, 20, 30]; auto msg = f\"Length: ${arr.length}\"; msg;");
        assertEquals("Length: 3", result.toString().trim());
    }
}
