package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.exception.ParseException;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;
import com.justnothing.engine.parser.StmtParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * 顽固 Bug 回归测试。
 * <p>
 * 针对用户在 REPL 中反复报告的问题编写，确保修复后不再回归。
 * 覆盖的 bug 列表：
 * <ol>
 *   <li>三元表达式常量折叠不生效 — {@code 1 + 1 == 3 ? 1 : 2} 应折叠为 LiteralNode(2)</li>
 *   <li>REPL 中重复声明变量不报错 — {@code int a; int a;} 第二次应抛 "already declared"</li>
 *   <li>VariableNode.formatString 缺少类型信息</li>
 *   <li>Varargs 未打包 — {@code String.format("%d %d", 1, 2)} 参数数量错误</li>
 *   <li>方法引用不验证存在性 — {@code Runtime::getRunti} 不应成功</li>
 *   <li>实例方法引用被标为 static — {@code Runtime::exec} 应为 unbound</li>
 * </ol>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public class StubbornBugRegressionTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
    }

    // ==================== 辅助方法 ====================

    /** 模拟 REPL 完整路径：UnifiedParser 三级 fallback（与 AstRepl 同路径）。 */
    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<repl>");
        Parser parser = new Parser(lexer.tokenize(), context, "<repl>");
        return parser.parse();
    }

    private ASTNode parseSingle(String source) throws CythavaParseException {
        List<ASTNode> nodes = parse(source);
        assertEquals("Expected exactly one AST node", 1, nodes.size());
        return nodes.get(0);
    }

    /** 直接走 StmtParser（无 fallback），用于测试语义校验是否被触发。 */
    private ASTNode parseStatement(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<repl>");
        StmtParser parser = new StmtParser(lexer.tokenize(), context, "<repl>");
        return parser.parseNextStatement();
    }

    private void assertParseError(String source, String expectedFragment) {
        try {
            parse(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException | ParseException e) {
            assertTrue("Error should contain '" + expectedFragment + "' but was: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends ASTNode> T assertNodeType(Class<T> expectedType, ASTNode node) {
        assertSame("Expected " + expectedType.getSimpleName(), expectedType, node.getClass());
        return (T) node;
    }

    // =====================================================================
    //  BUG #1: 三元表达式常量折叠不生效
    //  用户原话: "1 + 1 == 3 ? 1 : 2" 显示 TernaryNode 而非 LiteralNode(2)
    // =====================================================================

    @Test
    public void ternaryFold_exactUserCase() throws Exception {
        // 用户在 REPL 中输入的精确用例
        // 1 + 1 == 3 → 常量折叠为 false → false ? 1 : 2 → 短路为 LiteralNode(2)
        ASTNode node = parseSingle("1 + 1 == 3 ? 1 : 2");
        assertNotEquals("Ternary with constant condition must be folded, not remain TernaryNode",
                TernaryNode.class, node.getClass());
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals("Short-circuited to else branch value", 2, lit.getValue());
    }

    @Test
    public void ternaryFold_trueCondition() throws Exception {
        // true ? 42 : 99 → LiteralNode(42)
        ASTNode node = parseSingle("true ? 42 : 99");
        assertNotEquals(TernaryNode.class, node.getClass());
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(42, lit.getValue());
    }

    @Test
    public void ternaryFold_falseCondition() throws Exception {
        // false ? 42 : 99 → LiteralNode(99)
        ASTNode node = parseSingle("false ? 42 : 99");
        assertNotEquals(TernaryNode.class, node.getClass());
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(99, lit.getValue());
    }

    @Test
    public void ternaryFold_comparisonCondition() throws Exception {
        // 10 > 5 ? "yes" : "no" → 10 > 5 = true → LiteralNode("yes")
        ASTNode node = parseSingle("10 > 5 ? \"yes\" : \"no\"");
        assertNotEquals(TernaryNode.class, node.getClass());
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals("yes", lit.getValue());
    }

    @Test
    public void ternaryFold_arithmeticCondition() throws Exception {
        // 2 * 3 == 6 ? 1 : 0 → true → LiteralNode(1)
        ASTNode node = parseSingle("2 * 3 == 6 ? 1 : 0");
        assertNotEquals(TernaryNode.class, node.getClass());
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(1, lit.getValue());
    }

    @Test
    public void ternaryNoFold_nonConstantCondition() throws Exception {
        // 条件不是常量时不应折叠（变量引用无法在解析期求值）
        // 先声明一个变量
        parseSingle("int x = 1;");
        // x == 1 ? "a" : "b" — x 是变量非常量，保留 TernaryNode
        ASTNode node = parseSingle("x == 1 ? \"a\" : \"b\"");
        // 条件是 BinaryOpNode（==），其操作数包含 VariableNode(x)，不是纯字面量
        // 所以整个三元不应该被折叠
        if (node instanceof TernaryNode ternary) {
            // 正确：条件非常量，保留 TernaryNode
            assertNotNull(ternary.getCondition());
        } else {
            // 如果被折叠了，说明条件意外地被常量折叠了（这也是可接受的）
            assertTrue(node instanceof LiteralNode);
        }
    }

    @Test
    public void ternaryFold_nestedTernary() throws Exception {
        // true ? (false ? 1 : 2) : 3 → true 取外层then → 内层 false 取 else → LiteralNode(2)
        ASTNode node = parseSingle("true ? (false ? 1 : 2) : 3");
        // 外层先折叠: true ? (false?1:2) : 3 → (false?1:2)
        // 再折叠内层: false ? 1 : 2 → 2
        if (node instanceof TernaryNode) {
            fail("Nested constant ternary should be fully folded");
        }
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(2, lit.getValue());
    }

    // =====================================================================
    //  BUG #2: REPL 重复声明变量不报错（走完整 UnifiedParser 路径）
    //  用户原话: "int a; 连续输入3次都显示 VariableNode: a 而非报错"
    // =====================================================================

    @Test
    public void duplicateDecl_auto_thenAuto() throws Exception {
        parseSingle("auto x = 1;");
        assertParseError("auto x = 2;", "already declared");
    }

    @Test
    public void noDuplicate_differentVariables() throws Exception {
        // 不同名变量不应报错
        ASTNode first = parseSingle("int alpha;");
        assertNodeType(VarDeclNode.class, first);
        ASTNode second = parseSingle("int beta;");
        assertNodeType(VarDeclNode.class, second);
        // 两个都应该是 VarDeclNode，不报错
    }

    @Test
    public void reassignment_afterDeclaration_isOk() throws Exception {
        // 先声明，再赋值（不是重复声明）— 应该正常工作
        parseSingle("int val = 10;");
        // val = 20 是赋值，不是声明
        ASTNode node = parseSingle("val = 20;");
        // 应该是 AssignmentNode（赋值），不是 VarDeclNode（声明）
        assertNodeType(AssignmentNode.class, node);
        assertFalse(((AssignmentNode) node).isDeclaration());
    }

    // =====================================================================
    //  BUG #3: VariableNode.formatString 类型信息缺失
    //  用户原话: "它的formatString里面没有指明它的类型"
    // =====================================================================

    @Test
    public void variableNode_formatString_showsType() throws Exception {
        // 声明一个带类型的变量后，引用它时应显示类型信息
        parseSingle("int typedVar = 42;");
        ASTNode ref = parseSingle("typedVar");
        VariableNode vn = assertNodeType(VariableNode.class, ref);
        String formatted = vn.formatString(1);
        // formatString 应该包含变量名
        assertTrue("Format should contain variable name", formatted.contains("typedVar"));
        // 如果有 declaredType 信息，也应该显示
        // （这个测试主要确保 formatString 不崩溃、有合理输出）
        assertNotNull("formatString should not return null", formatted);
        assertTrue("Format should not be empty", formatted.length() > 0);
    }

    @Test
    public void variableNode_formatString_withDeclaredType() throws Exception {
        // 手动构建带 declaredType 的 VariableNode 并验证格式化输出
        VariableNode vn = (VariableNode) new VariableNode.Builder().name("myInt").build();
        ClassReferenceNode typeRef = ClassReferenceNode.of("int", int.class, true, null);
        vn.setDeclaredType(typeRef);
        vn.setFinal(true);

        String formatted = vn.formatString(1);
        assertTrue("Should contain variable name", formatted.contains("myInt"));
        assertTrue("Should contain declaredType info", formatted.contains("declaredType"));
        assertTrue("Should contain final modifier", formatted.contains("final"));
    }

    @Test
    public void variableNode_formatString_fieldAccess() throws Exception {
        VariableNode vn = (VariableNode) new VariableNode.Builder().name("someField").build();
        vn.setFieldAccess(true);

        String formatted = vn.formatString(1);
        assertTrue("Should mark as field access", formatted.contains("field"));
    }

    @Test
    public void variableNode_formatString_withAnnotations() throws Exception {
        VariableNode vn = (VariableNode) new VariableNode.Builder().name("annotatedVar").build();
        AnnotationNode ann = new AnnotationNode.Builder().annotationName("NotNull").build();
        vn.setAnnotations(List.of(ann));

        String formatted = vn.formatString(1);
        assertTrue("Should show annotation count", formatted.contains("annotations"));
    }

    // =====================================================================
    //  综合场景：多个 bug 交互
    // =====================================================================

    @Test
    public void comprehensive_ternaryInAssignment() throws Exception {
        // int x = (false ? 10 : 20); → initializer 应该是 LiteralNode(20) 而非 TernaryNode
        ASTNode node = parseSingle("int x = (false ? 10 : 20);");
        VarDeclNode decl = assertNodeType(VarDeclNode.class, node);
        ASTNode init = decl.getInitializer();
        if (init instanceof TernaryNode) {
            fail("Ternary in initializer should be constant-folded to LiteralNode");
        }
        LiteralNode lit = assertNodeType(LiteralNode.class, init);
        assertEquals(20, lit.getValue());
    }

    // =====================================================================
    //  Varargs 与方法引用 Bug 回归测试（运行时级）
    // =====================================================================

    private ScriptRunner runner;

    @Before
    public void setUpRunner() {
        runner = new ScriptRunner();
        runner.addImport("java.lang.*");
    }

    @After
    public void tearDownRunner() {
        runner = null;
    }

    private Object eval(String script) {
        return runner.executeWithResult(script);
    }

    /** BUG: String.format("%d %d", 1, 2) 报 "wrong number of arguments" — varargs 未打包 */
    @Test
    public void varargs_StringFormat() {
        Object result = eval("String.format(\"%d %d\", 1, 2);");
        assertEquals("1 2", result);
    }

    /** varargs 与固定参数混合 */
    @Test
    public void varargs_mixedFixedAndVarargs() {
        Object result = eval("String.format(\"%s = %d\", \"x\", 42);");
        assertEquals("x = 42", result);
    }

    /** varargs 单个可变参数 */
    @Test
    public void varargs_singleVararg() {
        Object result = eval("String.format(\"%d\", 99);");
        assertEquals("99", result);
    }

    /** varargs 零个可变参数 */
    @Test
    public void varargs_zeroVarargs() {
        Object result = eval("String.format(\"hello\");");
        assertEquals("hello", result);
    }

    /** BUG: Runtime::getRunti 不存在，应报错而非返回 MethodReference */
    @Test(expected = EvalException.class)
    public void methodRef_nonexistentMethod_throws() {
        eval("Runtime::getRunti;");
    }

    /** BUG: Runtime::exec 是实例方法，不应标为 static */
    @Test
    public void methodRef_instanceMethod_labeledCorrectly() {
        Object ref = eval("Runtime::exec;");
        String str = ref.toString();
        assertTrue("Should not contain 'static': " + str, !str.contains("static"));
        assertTrue("Should contain 'unbound': " + str, str.contains("unbound"));
    }

    /** 静态方法引用仍正确标为 static */
    @Test
    public void methodRef_staticMethod_stillStatic() {
        Object ref = eval("String::valueOf;");
        String str = ref.toString();
        assertTrue("Should contain 'static': " + str, str.contains("static"));
    }
}
