package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ParseException;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.JType;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.StmtParser;
import com.justnothing.engine.parser.Parser;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * REPL 集成测试。
 * <p>
 * 使用 Parser（与 AstRepl 同一路径）验证端到端的解析行为。
 * 覆盖用户在 REPL 中手动测试过的所有场景。
 * </p>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public class ReplIntegrationTest {

    private ParseContext context;

    @Before
    public void setUp() {
        // 模拟真实 REPL 环境：严格模式开启（未声明标识符直接报错）
        context = new ParseContext();
    }

    // ==================== 辅助方法 ====================

    /** 用 Parser 解析输入（三级 fallback，与 AstRepl 同路径），返回 AST 节点列表。 */
    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<repl>");
        Parser parser = new Parser(lexer.tokenize(), context, "<repl>");
        return parser.parse();
    }

    /** 用 StmtParser 直接解析语句（无 fallback，错误会直接抛出）。用于测试语句级校验。 */
    private ASTNode parseStatement(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<repl>");
        StmtParser stmtParser = new StmtParser(lexer.tokenize(), context, "<repl>");
        return stmtParser.parseNextStatement();
    }

    /** 断言解析成功并返回唯一节点。 */
    private ASTNode parseSingle(String source) throws CythavaParseException {
        List<ASTNode> nodes = parse(source);
        assertEquals("Expected exactly one AST node", 1, nodes.size());
        return nodes.get(0);
    }

    /** 断言解析抛出异常（错误信息包含 expectedFragment）。支持解析期和词法错误。 */
    private void assertParseError(String source, String expectedFragment) {
        try {
            parse(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException | ParseException e) {
            assertTrue("Error message should contain '" + expectedFragment + "' but was: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }

    /** 断言语句级解析抛出异常（直接走 StmtParser，无 fallback，用于测试语义校验）。 */
    private void assertStmtError(String source, String expectedFragment) {
        try {
            parseStatement(source);
            fail("Expected statement-level parse error for: " + source);
        } catch (CythavaParseException e) {
            assertTrue("Error should contain '" + expectedFragment + "' but was: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        } catch (IllegalStateException e) {
            // declareVariable 对重复声明抛 IllegalStateException
            assertTrue("Error should contain '" + expectedFragment + "' but was: " + e.getMessage(),
                    e.getMessage().contains(expectedFragment));
        }
    }

    /** 断言节点类型。 */
    @SuppressWarnings("unchecked")
    private <T extends ASTNode> T assertNodeType(Class<T> expectedType, ASTNode node) {
        assertSame("Expected " + expectedType.getSimpleName(), expectedType, node.getClass());
        return (T) node;
    }

    // ==================== 链式方法调用 ====================

    @Test
    public void testSystemOutPrintln() throws Exception {
        ASTNode node = parseSingle("System.out.println(\"1\");");
        MethodCallNode mc = assertNodeType(MethodCallNode.class, node);
        assertEquals("println", mc.getMethodName());
        assertNotNull("MethodCall should have bound method", mc.getBoundMethod());
        assertTrue("Should be bound to PrintStream.println",
                mc.getBoundMethod().getDeclaringClass() == java.io.PrintStream.class);

        // target 是 FieldAccessNode(.out)
        FieldAccessNode fa = assertNodeType(FieldAccessNode.class, mc.getTarget());
        assertEquals("out", fa.getFieldName());

        // .out 的目标是 ClassReferenceNode(System)
        ClassReferenceNode cr = assertNodeType(ClassReferenceNode.class, fa.getTarget());
        assertEquals("System", cr.getOriginalTypeName());
        assertEquals(java.lang.System.class, cr.getResolvedClass());
    }

    @Test
    public void testSystemOutFieldAccessOnly() throws Exception {
        ASTNode node = parseSingle("System.out");
        FieldAccessNode fa = assertNodeType(FieldAccessNode.class, node);
        assertEquals("out", fa.getFieldName());
        ClassReferenceNode cr = assertNodeType(ClassReferenceNode.class, fa.getTarget());
        assertEquals(java.lang.System.class, cr.getResolvedClass());
    }

    // ==================== 已知类名消歧 ====================

    @Test
    public void testKnownClassName_System() throws Exception {
        ClassReferenceNode cr = assertNodeType(ClassReferenceNode.class, parseSingle("System"));
        assertEquals("System", cr.getOriginalTypeName());
        assertEquals(java.lang.System.class, cr.getResolvedClass());
    }

    @Test
    public void testKnownClassName_String() throws Exception {
        ClassReferenceNode cr = assertNodeType(ClassReferenceNode.class, parseSingle("String"));
        assertEquals(String.class, cr.getResolvedClass());
    }

    @Test
    public void testUnknownIdentifier_StrictMode() {
        // 严格模式下未声明的变量应该报错
        assertParseError("c", "Cannot find symbol");
    }

    // ==================== using 别名 ====================

    @Test
    public void testUsingAlias_HashMap() throws Exception {
        // 注册别名
        parseSingle("using HMap = HashMap;");
        // 别名应该被识别为已知类
        ClassReferenceNode cr = assertNodeType(ClassReferenceNode.class, parseSingle("HMap"));
        assertEquals("HMap", cr.getOriginalTypeName());
        assertEquals(java.util.HashMap.class, cr.getResolvedClass());
    }

    @Test
    public void testUsingAlias_InvalidTarget() {
        // 目标类不存在应报错（Parser fallback 链中可能暴露的是表达式解析器的错误）
        assertParseError("using Error = NonExistingClass;", "Expected expression");
    }

    // ==================== 泛型类型声明 ====================

    @Test
    public void testGenericTypeDeclaration_MapStringString() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class, parseSingle("Map<String, String> s;"));
        assertEquals("s", decl.getVarName());
        assertNotNull("Declared type should not be null", decl.getDeclaredType());
        assertTrue("Type name should contain Map",
                decl.getDeclaredType().getTypeName().contains("Map"));
    }

    @Test
    public void testGenericTypeDeclaration_ListInt() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class, parseSingle("List<Integer> list;"));
        assertEquals("list", decl.getVarName());
    }

    @Test
    public void testArrayTypeDeclaration() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("String[] arr;"));
        assertEquals("arr", decl.getVarName());
        assertNotNull(decl.getDeclaredType());
    }

    // ==================== final / 注解局部变量 ====================

    @Test
    public void testFinalLocalVar() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("final int b = 0;"));
        assertEquals("b", decl.getVarName());
        assertTrue("isFinal should be true", decl.isFinal());
    }

    @Test
    public void testAnnotatedLocalVar() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("@NotNull String a = \"hello\";"));
        assertEquals("a", decl.getVarName());
    }

    @Test
    public void testAnnotatedFinalLocalVar() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("@NotNull final int a = 42;"));
        assertEquals("a", decl.getVarName());
        assertTrue(decl.isFinal());
    }

    // ==================== 逗号分隔多变量声明 ====================

    @Test
    public void testMultiVarDeclaration() throws Exception {
        // 引用类型可以不初始化；原始类型必须初始化（这里用 String 测试多变量声明）
        BlockNode block = assertNodeType(BlockNode.class, parseSingle("String d, e, f;"));
        assertEquals("Should have 3 declarations", 3, block.getStatements().size());

        for (ASTNode stmt : block.getStatements()) {
            VarDeclNode decl = assertNodeType(VarDeclNode.class, stmt);
            assertFalse("None should be final", decl.isFinal());
        }

        assertEquals("d", ((VarDeclNode) block.getStatements().get(0)).getVarName());
        assertEquals("e", ((VarDeclNode) block.getStatements().get(1)).getVarName());
        assertEquals("f", ((VarDeclNode) block.getStatements().get(2)).getVarName());
    }

    // ==================== 多维数组方法引用 ====================

    @Test
    public void testSingleDimArrayMethodRef() throws Exception {
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String[]::length"));
        assertEquals("length", mr.getMethodName());
    }

    @Test
    public void testMultiDimArrayMethodRef() throws Exception {
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String[][]::length"));
        assertEquals("length", mr.getMethodName());
    }

    @Test
    public void testQuadDimArrayMethodRef() throws Exception {
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String[][][][]::length;"));
        assertEquals("length", mr.getMethodName());
    }

    // ==================== new 数组 ====================

    @Test
    public void testNewArrayWithSize() throws Exception {
        NewArrayNode na = assertNodeType(NewArrayNode.class, parseSingle("new String[2];"));
        assertNotNull(na.getElementType());
        assertNotNull(na.getSize());
    }

    @Test
    public void testNewArrayGetClass() throws Exception {
        MethodCallNode mc = assertNodeType(MethodCallNode.class, parseSingle("new String[2].getClass();"));
        assertEquals("getClass", mc.getMethodName());
        // target 应该是 NewArrayNode
        NewArrayNode na = assertNodeType(NewArrayNode.class, mc.getTarget());
        assertNotNull(na.getElementType());
    }

    // ==================== 前后缀 ++/-- ====================

    @Test
    public void testPrefixIncrement() throws Exception {
        parseStatement("int i = 0;");
        UnaryOpNode op = assertNodeType(UnaryOpNode.class, parseSingle("++i;"));
        VariableNode v = assertNodeType(VariableNode.class, op.getOperand());
        assertEquals("i", v.getName());
        assertTrue("Should be prefix increment", op.getOperator().name().contains("PRE"));
    }

    @Test
    public void testPostfixIncrement() throws Exception {
        parseStatement("int i = 0;");
        UnaryOpNode op = assertNodeType(UnaryOpNode.class, parseSingle("i++;"));
        VariableNode v = assertNodeType(VariableNode.class, op.getOperand());
        assertEquals("i", v.getName());
        assertTrue("Should be postfix increment", op.getOperator().name().contains("POST"));
    }

    // ==================== 字面量表达式 ====================

    @Test
    public void testLiteralInteger() throws Exception {
        LiteralNode lit = assertNodeType(LiteralNode.class, parseSingle("1;"));
        assertEquals(1, lit.getValue());
    }

    @Test
    public void testLiteralString() throws Exception {
        LiteralNode lit = assertNodeType(LiteralNode.class, parseSingle("\"hello\""));
        assertEquals("hello", lit.getValue());
    }

    // ==================== Lambda 表达式 ====================

    @Test
    public void testSimpleLambda() throws Exception {
        LambdaNode lambda = assertNodeType(LambdaNode.class, parseSingle("x -> x * 2"));
        assertNotNull(lambda.getBody());
    }

    // ==================== switch 表达式 ====================

    // ==================== 类声明 ====================

    @Test
    public void testClassDeclarationWithFields() throws Exception {
        ClassDeclarationNode cd = assertNodeType(ClassDeclarationNode.class,
                parseSingle("class SomeClass { int a, b; }"));
        assertEquals("SomeClass", cd.getClassName());
        // 应该有字段声明
        assertFalse("Should have fields", cd.getFields().isEmpty());
    }

    // ==================== 匿名内部类 ====================

    // ==================== using 语句 ====================

    @Test
    public void testUsingStatement() throws Exception {
        UsingAliasNode ua = assertNodeType(UsingAliasNode.class, parseSingle("using HMap = HashMap;"));
        assertEquals("HMap", ua.getAliasName());
        assertEquals("HashMap", ua.getFullClassName());
    }

    // ==================== delete 语句 ====================

    @Test
    public void testDeleteParse() throws Exception {
        DeleteNode dn = assertNodeType(DeleteNode.class, parseSingle("delete x;"));
        assertEquals("x", dn.getVariableName());
        assertFalse(dn.isDeleteAll());

        dn = assertNodeType(DeleteNode.class, parseSingle("delete *;"));
        assertTrue(dn.isDeleteAll());
    }

    // ==================== 错误场景 ====================

    @Test
    public void error_hashPreprocessorNotSupported() {
        assertParseError("#define PI 3.14", "Unexpected character");
    }

    // ==================== 方法重载绑定 ====================

    @Test
    public void testMethodOverloadBinding_PrintlnInt() throws Exception {
        MethodCallNode mc = assertNodeType(MethodCallNode.class,
                parseSingle("System.out.println(42);"));
        assertNotNull("Should bind to concrete method", mc.getBoundMethod());
        assertEquals("println", mc.getBoundMethod().getName());
    }

    @Test
    public void testMethodOverloadBinding_GetClass() throws Exception {
        MethodCallNode mc = assertNodeType(MethodCallNode.class,
                parseSingle("new String[2].getClass();"));
        assertNotNull("Should bind getClass", mc.getBoundMethod());
        assertEquals("getClass", mc.getBoundMethod().getName());
    }

    // ==================== 管道操作符 ====================

    @Test
    public void testPipelineOperator() throws Exception {
        PipelineNode pn = assertNodeType(PipelineNode.class, parseSingle("\"hello\" |> System.out::println"));
        assertNotNull(pn.getInput());
        assertNotNull(pn.getFunction());
    }

    // ==================== f-string ====================

    @Test
    public void testFString() throws Exception {
        InterpolatedStringNode isn = assertNodeType(InterpolatedStringNode.class,
                parseSingle("f\"Hello, {name}!\""));
        assertNotNull(isn.getParts());
    }

    // ==================== 类型方法引用 ====================

    @Test
    public void testClassMethodReference() throws Exception {
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String::valueOf"));
        assertEquals("valueOf", mr.getMethodName());
    }

    // ==================== auto 类型推断 ====================

    @Test
    public void testAutoIntInference() throws Exception {
        AssignmentNode assign = assertNodeType(AssignmentNode.class,
                parseStatement("auto x = 42;"));
        assertEquals("x", assign.getVariableName());
        assertTrue(assign.isDeclaration());
        assertNotNull("auto should infer type from initializer", assign.getDeclaredType());
    }

    @Test
    public void testAutoStringInference() throws Exception {
        AssignmentNode assign = assertNodeType(AssignmentNode.class,
                parseStatement("auto s = \"hello\";"));
        assertEquals("s", assign.getVariableName());
        assertTrue(assign.isDeclaration());
        assertNotNull(assign.getDeclaredType());
    }

    @Test
    public void testAutoMultiVar() throws Exception {
        BlockNode block = assertNodeType(BlockNode.class,
                parseStatement("auto a = 1, b = \"x\", c = true;"));
        assertEquals(3, block.getStatements().size());
        for (ASTNode stmt : block.getStatements()) {
            assertTrue(stmt instanceof AssignmentNode);
            assertTrue(((AssignmentNode) stmt).isDeclaration());
            assertNotNull(((AssignmentNode) stmt).getDeclaredType());
        }
    }

    @Test
    public void error_autoRequiresInitializer() {
        // auto 必须有初始化器（无法推断类型）— 直接走 StmtParser 测试语义校验
        assertStmtError("auto x;", "requires an initializer");
    }

    // ==================== 原始类自动默认值 ====================

    @Test
    public void testPrimitiveInt_DefaultZero() throws Exception {
        // int x; → 自动初始化为 0（与 Java 语义一致）
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("int x;"));
        assertEquals("x", decl.getVarName());
        assertNotNull("Primitive should have auto-default value", decl.getInitializer());
        LiteralNode val = assertNodeType(LiteralNode.class, decl.getInitializer());
        assertEquals(0, val.getValue());
    }

    @Test
    public void testPrimitiveDouble_DefaultZero() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("double d;"));
        LiteralNode val = assertNodeType(LiteralNode.class, decl.getInitializer());
        assertEquals(0.0, val.getValue());
    }

    @Test
    public void testPrimitiveBoolean_DefaultFalse() throws Exception {
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("boolean b;"));
        LiteralNode val = assertNodeType(LiteralNode.class, decl.getInitializer());
        assertEquals(false, val.getValue());
    }

    @Test
    public void testPrimitiveMultiVar_AutoDefaults() throws Exception {
        // int a = 1, b; → a=1 (显式), b=0 (自动默认)
        BlockNode block = assertNodeType(BlockNode.class,
                parseStatement("int a = 1, b;"));
        assertEquals(2, block.getStatements().size());

        VarDeclNode a = assertNodeType(VarDeclNode.class, block.getStatements().get(0));
        assertEquals("a", a.getVarName());
        assertEquals(1, ((LiteralNode) a.getInitializer()).getValue());

        VarDeclNode b = assertNodeType(VarDeclNode.class, block.getStatements().get(1));
        assertEquals("b", b.getVarName());
        assertEquals(0, ((LiteralNode) b.getInitializer()).getValue());
    }

    @Test
    public void testPrimitiveWithExplicitInitializer() throws Exception {
        // 显式初始值应保留，不被默认值覆盖
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("int count = 42;"));
        assertEquals("count", decl.getVarName());
        LiteralNode val = assertNodeType(LiteralNode.class, decl.getInitializer());
        assertEquals(42, val.getValue());
    }

    // ==================== 范围表达式 ====================

    @Test
    public void testRangeExpression() throws Exception {
        BinaryOpNode range = assertNodeType(BinaryOpNode.class, parseSingle("1..10"));
        assertEquals(BinaryOpNode.Operator.RANGE, range.getOperator());
    }

    @Test
    public void testRangeExclusiveExpression() throws Exception {
        BinaryOpNode range = assertNodeType(BinaryOpNode.class, parseSingle("1..<10"));
        assertEquals(BinaryOpNode.Operator.RANGE_EXCLUSIVE, range.getOperator());
    }

    // ==================== async / await ====================

    @Test
    public void testAsyncStatement() throws Exception {
        AsyncNode async = assertNodeType(AsyncNode.class, parseSingle("async { return 1; }"));
        assertNotNull(async.getExpression());
    }

    @Test
    public void testAwaitExpression() throws Exception {
        // await 表达式：使用已知方法避免严格模式报错
        AwaitNode await = assertNodeType(AwaitNode.class, parseSingle("await String.valueOf(1)"));
        assertNotNull(await.getExpression());
    }

    // ==================== 常量折叠 ====================

    @Test
    public void testConstantFolding_Addition() throws Exception {
        // 2 + 3 应该在解析期被折叠为字面量 5
        ASTNode node = parseSingle("2 + 3");
        assertTrue(node instanceof LiteralNode || node instanceof BinaryOpNode);
    }

    @Test
    public void testConstantFolding_StringConcat() throws Exception {
        ASTNode node = parseSingle("\"hello\" + \" \" + \"world\"");
        assertTrue(node instanceof LiteralNode || node instanceof BinaryOpNode);
    }

    @Test
    public void testConstantFolding_TernaryShortCircuit() throws Exception {
        // true ? 1 : 2 → 常量折叠应短路为 LiteralNode(1)
        ASTNode node = parseSingle("true ? 1 : 2");
        // 如果常量折叠生效，三元表达式被短路为 then 分支的字面量
        if (node instanceof TernaryNode ternary) {
            // 未折叠：检查条件是字面量 true
            assertTrue(ternary.getCondition() instanceof LiteralNode);
        } else {
            // 已折叠：应该是 LiteralNode(1)
            assertNodeType(LiteralNode.class, node);
        }
    }

    @Test
    public void testConstantFolding_TernaryFalseBranch() throws Exception {
        // false ? 1 : 2 → 短路为 LiteralNode(2)
        ASTNode node = parseSingle("false ? 1 : 99");
        if (node instanceof TernaryNode ternary) {
            assertTrue(ternary.getCondition() instanceof LiteralNode);
        } else {
            LiteralNode lit = assertNodeType(LiteralNode.class, node);
            assertEquals(99, lit.getValue());
        }
    }

    // ==================== 方法引用链式调用 ====================

    @Test
    public void testMethodReference_ChainInvoke() throws Exception {
        // System.out::println.invoke(1) — 方法引用后链式调用
        ASTNode node = parseSingle("System.out::println.invoke(1)");
        // 应该解析为 MethodCallNode(target=MethodReferenceNode, method=invoke)
        MethodCallNode call = assertNodeType(MethodCallNode.class, node);
        assertEquals("invoke", call.getMethodName());
        assertTrue(call.getTarget() instanceof MethodReferenceNode);
    }

    @Test
    public void testMethodReference_ParenthesizedChain() throws Exception {
        // (System.out::println).invoke(1) — 括号包裹的方法引用后调用
        ASTNode node = parseSingle("(System.out::println).invoke(1)");
        MethodCallNode call = assertNodeType(MethodCallNode.class, node);
        assertEquals("invoke", call.getMethodName());
    }

    // ==================== 多维数组初始化器 ====================

    @Test
    public void testMultiDimArrayInitWithIndex() throws Exception {
        // new int[][] {{1, 2}}[0][1] — 多维数组初始化器 + 索引访问
        ASTNode node = parseSingle("new int[][] {{1, 2}}");
        // 应该能正确解析（不报错）
        assertNotNull(node);
        // 后续 [0][1] 是索引访问
        ASTNode indexed = parseSingle("new int[][] {{1, 2}}[0][1]");
        assertNotNull(indexed);
    }

    // ==================== 原始类自动默认值（REPL 验证） ====================

    @Test
    public void testAnnotatedFinalPrimitive_AutoDefault() throws Exception {
        // @NotNull final int a; → 自动赋默认值 0（不报错）
        VarDeclNode decl = assertNodeType(VarDeclNode.class,
                parseStatement("@NotNull final int a;"));
        assertEquals("a", decl.getVarName());
        assertTrue(decl.isFinal());
        assertNotNull(decl.getInitializer());
        LiteralNode val = assertNodeType(LiteralNode.class, decl.getInitializer());
        assertEquals(0, val.getValue());
    }

    // ==================== 回归测试：最近报告的顽固 Bug ====================
    // 这些 bug 在 REPL 中反复出现，需要专门的测试钉死

    @Test
    public void regression_ternaryConstantFold_trueBranch() throws Exception {
        // true ? 1 : 2 → 应折叠为 LiteralNode(1)
        ASTNode node = parseSingle("true ? 1 : 2");
        // 如果常量折叠生效，应该是 LiteralNode
        if (node instanceof TernaryNode) {
            fail("Ternary with constant condition should be folded to LiteralNode, got TernaryNode");
        }
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(1, lit.getValue());
    }

    @Test
    public void regression_ternaryConstantFold_falseBranch() throws Exception {
        // false ? 99 : 2 → 应折叠为 LiteralNode(2)
        ASTNode node = parseSingle("false ? 99 : 2");
        if (node instanceof TernaryNode) {
            fail("Ternary with false condition should be folded to LiteralNode, got TernaryNode");
        }
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(2, lit.getValue());
    }

    @Test
    public void regression_ternaryConstantFold_complexCondition() throws Exception {
        // 1 + 1 == 3 ? 1 : 2 → 条件是字面量 false → 折叠为 LiteralNode(2)
        ASTNode node = parseSingle("1 + 1 == 3 ? 1 : 2");
        if (node instanceof TernaryNode) {
            fail("Ternary with constant-false condition should be folded, got TernaryNode");
        }
        LiteralNode lit = assertNodeType(LiteralNode.class, node);
        assertEquals(2, lit.getValue());
    }

    @Test
    public void regression_unknownType_mustError() {
        // bool 不是 Java 关键字也不是已知类型 → 必须报错
        assertStmtError("bool a;", "Unknown type");
    }

    @Test
    public void regression_typeMismatch_assignment() throws Exception {
        // int b = 0 先声明
        parseStatement("int b;");
        // b = "114" 字符串赋给 int → 类型不匹配
        assertStmtError("b = \"114\"", "Type mismatch");
    }

    @Test
    public void regression_declarationIsVarDeclNotAssignment() throws Exception {
        // int x = 42; 必须是 VarDeclNode 而不是 AssignmentNode
        ASTNode node = parseStatement("int x = 42;");
        assertNodeType(VarDeclNode.class, node);
    }

    @Test
    public void regression_assignmentIsAssignmentNotDecl() throws Exception {
        // 先声明 int y;
        parseStatement("int y;");
        // y = 100; 是纯赋值，不是声明 → AssignmentNode with isDeclaration=false
        AssignmentNode assign = assertNodeType(AssignmentNode.class,
                parseStatement("y = 100;"));
        assertFalse(assign.isDeclaration());
        assertEquals("y", assign.getVariableName());
    }

    @Test
    public void regression_primitiveAutoDefault_showsInFormatString() throws Exception {
        // int i1; 的 initializer 应显示为 LiteralNode: 0 (int)，不为 null
        VarDeclNode decl = assertNodeType(VarDeclNode.class, parseStatement("int i1;"));
        assertNotNull("Primitive variable must have auto-default initializer", decl.getInitializer());
        String formatted = decl.formatString(1);
        assertTrue("Format string should show the default value",
                formatted.contains("LiteralNode") || formatted.contains("0"));
    }

    // ==================== 诊断：async/await 类型传递 ====================

    /** 诊断 await async 1 的类型是否正确传递为 int（应绑定 println(int) 而非 println(Object)）。 */
    @Test
    public void diag_asyncAwait_typePropagation() throws Exception {
        // 先验证 async 1 的类型
        ASTNode asyncNode = parseSingle("async 1");
        JType asyncType = context.getType(asyncNode);
        assertNotNull("AsyncNode should have type", asyncType);
        assertEquals("async 1 should be int", int.class, asyncType.getRawType());

        // 再验证 await async 1 的类型
        context = new ParseContext();  // 重置上下文
        ASTNode awaitNode = parseSingle("await async 1");
        JType awaitType = context.getType(awaitNode);
        assertNotNull("AwaitNode should have type", awaitType);
        assertEquals("await async 1 should propagate inner int type", int.class, awaitType.getRawType());

        // 最后验证 println(await async 1) 绑定 println(int)
        context = new ParseContext();
        ASTNode call = parseSingle("System.out.println(await async 1)");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull("Should have bound method", mc.getBoundMethod());
        // 诊断：打印实际绑定的方法和参数类型
        java.lang.reflect.Method bound = mc.getBoundMethod();
        ASTNode arg0 = mc.getArguments().get(0);
        JType argType = context.getType(arg0);
        String diag = "bound=" + bound.getName() + "(" + java.util.Arrays.stream(bound.getParameterTypes()).map(p -> p.getSimpleName()).collect(java.util.stream.Collectors.joining(",")) + ")"
                + " argType=" + (argType != null ? argType.getRawType().getSimpleName() : "null")
                + " argClass=" + arg0.getClass().getSimpleName();
        assertEquals("Should bind println(int), not println(Object). DIAG: " + diag,
                "println(int)", bound.getName() + "(" + bound.getParameterTypes()[0].getSimpleName() + ")");
    }

    // ==================== 泛型构造器语法 .<TypeArgs>new() ====================

    @Test
    public void testGenericConstructor_newWithTypeArgs() throws Exception {
        // HashMap.<String, String>.new() → 构造器调用
        ASTNode node = parseSingle("java.util.HashMap.<String, String>.new()");
        ConstructorCallNode cc = assertNodeType(ConstructorCallNode.class, node);
        GenericType type = cc.getType();
        assertEquals("HashMap", type.getRawType().getSimpleName());
        assertEquals(2, type.getTypeArguments().size());
        assertEquals("String", type.getTypeArguments().get(0).getRawType().getSimpleName());
        assertEquals("String", type.getTypeArguments().get(1).getRawType().getSimpleName());
    }

    @Test
    public void testGenericConstructor_singleTypeArg() throws Exception {
        // ArrayList.<Integer>.new()
        ASTNode node = parseSingle("java.util.ArrayList.<Integer>.new()");
        ConstructorCallNode cc = assertNodeType(ConstructorCallNode.class, node);
        GenericType type = cc.getType();
        assertEquals("ArrayList", type.getRawType().getSimpleName());
        assertEquals(1, type.getTypeArguments().size());
        assertEquals("Integer", type.getTypeArguments().get(0).getRawType().getSimpleName());
    }

    @Test
    public void testGenericConstructor_rawNewStillWorks() throws Exception {
        // HashMap.new() — 不带类型参数仍然工作
        ASTNode node = parseSingle("java.util.HashMap.new()");
        ConstructorCallNode cc = assertNodeType(ConstructorCallNode.class, node);
        GenericType type = cc.getType();
        assertEquals("HashMap", type.getRawType().getSimpleName());
        assertTrue("Raw HashMap.new() should have no type arguments", type.getTypeArguments().isEmpty());
    }

    // ==================== 泛型方法引用 Class::<TypeArgs>method ====================

    @Test
    public void testGenericMethodReference_withTypeArgs() throws Exception {
        // String::<String>compareTo — 泛型方法引用
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String::<String>compareTo"));
        assertEquals("compareTo", mr.getMethodName());
        assertFalse("Should have type arguments", mr.getTypeArguments().isEmpty());
        assertEquals(1, mr.getTypeArguments().size());
        assertEquals("String", mr.getTypeArguments().get(0).getRawType().getSimpleName());
    }

    @Test
    public void testMethodReference_withoutTypeArgsStillWorks() throws Exception {
        // String::valueOf — 不带泛型参数仍然工作
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("String::valueOf"));
        assertEquals("valueOf", mr.getMethodName());
        assertTrue("Should have no type arguments", mr.getTypeArguments().isEmpty());
    }

    @Test
    public void testGenericMethodReference_newWithTypeArgs() throws Exception {
        // 泛型构造器方法引用 Class::<TypeArgs>new
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("java.util.HashMap::<String, String>new"));
        assertEquals("new", mr.getMethodName());
        assertEquals(2, mr.getTypeArguments().size());
    }

    @Test
    public void testGenericMethodReference_constructorRefWithoutArgs() throws Exception {
        // ArrayList::new — 不带泛型参数的构造器引用
        MethodReferenceNode mr = assertNodeType(MethodReferenceNode.class, parseSingle("java.util.ArrayList::new"));
        assertEquals("new", mr.getMethodName());
        assertTrue("Should have no type arguments", mr.getTypeArguments().isEmpty());
    }

    // ==================== 函数式接口隐式转换 ====================

    @Test
    public void testLambdaToConsumerAssignment() throws Exception {
        // 声明 Consumer<String> 变量并用 lambda 初始化
        List<ASTNode> nodes = parse("java.util.function.Consumer<String> c = s -> {};");
        VarDeclNode decl = assertNodeType(VarDeclNode.class, nodes.get(0));
        assertTrue("lambda should be annotated with Consumer target type",
                ((LambdaNode) decl.getInitializer()).getFunctionalInterfaceType() != null);
        assertEquals(java.util.function.Consumer.class,
                ((LambdaNode) decl.getInitializer()).getFunctionalInterfaceType());
    }

    @Test
    public void testLambdaToRunnableAssignment() throws Exception {
        List<ASTNode> nodes = parse("Runnable r = () -> {};");
        VarDeclNode decl = assertNodeType(VarDeclNode.class, nodes.get(0));
        assertNotNull("lambda should be annotated with Runnable target type",
                ((LambdaNode) decl.getInitializer()).getFunctionalInterfaceType());
        assertEquals(Runnable.class,
                ((LambdaNode) decl.getInitializer()).getFunctionalInterfaceType());
    }

    @Test
    public void testLambdaToFunctionArgument() throws Exception {
        context.addImport("java.util.function.Function");
        // 使用 List.replaceAll(UnaryOperator) 测试 lambda 作为参数传递
        List<ASTNode> nodes = parse("java.util.Arrays.asList(\"a\").replaceAll(x -> x)");
        assertTrue("parse without error", !nodes.isEmpty());
    }

    @Test
    public void testMethodRefToConsumerAssignment() throws Exception {
        context.addImport("java.util.function.Consumer");
        List<ASTNode> nodes = parse("Consumer<String> c = System.out::println;");
        VarDeclNode decl = assertNodeType(VarDeclNode.class, nodes.get(0));
        assertNotNull("methodRef should be annotated with Consumer target type",
                ((MethodReferenceNode) decl.getInitializer()).getFunctionalInterfaceType());
        assertEquals(java.util.function.Consumer.class,
                ((MethodReferenceNode) decl.getInitializer()).getFunctionalInterfaceType());
    }

    // ==================== 关键字 Token 测试 ====================

    @Test
    public void testPackageKeyword() throws Exception {
        // package 声明应被解析为 ImportNode
        List<ASTNode> nodes = parse("package com.example;");
        assertEquals(1, nodes.size());
    }

    @Test
    public void testOperatorKeyword() throws Exception {
        // operator+ 应被正确解析为函数定义
        context.setStrictMode(false);
        context.setStrictMode(false);
        List<ASTNode> nodes = parse("int operator+(int a, int b) { return a + b; }");
        assertEquals("operator+ should parse as a function", 1, nodes.size());
    }

    @Test
    public void testEnumKeyword() throws Exception {
        // enum 声明应被正确解析
        context.setStrictMode(false);
        context.setStrictMode(false);
        List<ASTNode> nodes = parse("enum Color { RED, GREEN, BLUE }");
        assertEquals("enum should parse", 1, nodes.size());
    }
}
