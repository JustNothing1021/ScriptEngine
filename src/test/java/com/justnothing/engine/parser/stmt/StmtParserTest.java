package com.justnothing.engine.parser.stmt;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.StmtParser;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * StmtParser 语句解析测试。
 * <p>
 * 覆盖所有 Cythava/Java 语句类型。
 * </p>
 */
public class StmtParserTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        context.setStrictMode(false);  // 测试允许未声明变量
    }

    // ==================== 辅助方法 ====================

    private StmtParser createParser(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        List<Token> tokens = lexer.tokenize();
        return new StmtParser(tokens, context, "<test>");
    }

    private ASTNode parse(String source) throws CythavaParseException {
        return createParser(source).parseNextStatement();
    }

    private List<ASTNode> parseBlockBody(String source) throws CythavaParseException {
        StmtParser parser = createParser(source);
        // 先消费掉开头的 {
        parser.parseNextStatement(); // 这会消费整个块
        // 用 parseBlockBody 需要在 { 之后调用，这里用 parseNextStatement 就够了
        return null; // 占位
    }

    @SuppressWarnings("unchecked")
    private <T extends ASTNode> T assertStmt(Class<T> expectedType, String source) {
        try {
            ASTNode node = parse(source);
            if (!expectedType.isInstance(node)) {
                fail("Expected " + expectedType.getSimpleName() + " but got "
                        + node.getClass().getSimpleName() + " for: " + source);
            }
            return (T) node;
        } catch (CythavaParseException e) {
            fail("Unexpected parse error for '" + source + "': " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    private void assertParseError(String source) {
        try {
            parse(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException expected) {
            // ok
        }
    }

    private void assertParseErrorContaining(String source, String expectedFragment) {
        try {
            parse(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException expected) {
            assertTrue("错误信息应包含 '" + expectedFragment + "'，实际: " + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }

    // ==================== 表达式语句 ====================

    @Test
    public void expressionStatement() {
        // x = 1; 是赋值表达式语句
        ASTNode node = assertStmt(ASTNode.class, "x = 1;");
        assertNotNull(node);
    }

    @Test
    public void methodCallStatement() {
        ASTNode node = assertStmt(ASTNode.class, "foo();");
        assertNotNull(node);
    }

    // ==================== 泛型尖括号 vs 比较运算符的消歧 ====================

    /** {@code x < y;} 是比较表达式语句，不是"类型 x<y>"的声明。
     *  修复前：只要看到裸名字后面跟 {@code <} 就判定为声明，随即按类型解析而报
     *  "Expected '>' after type arguments"。 */
    @Test
    public void lessThanIsComparisonNotGenericType() {
        BinaryOpNode node = assertStmt(BinaryOpNode.class, "x < y;");
        assertEquals(BinaryOpNode.Operator.LESS_THAN, node.getOperator());
    }

    /** 尖括号内容不是类型实参的形状（{@code &&}）→ 比较表达式语句。 */
    @Test
    public void logicalAndInsideAngleBracketsIsComparison() {
        BinaryOpNode node = assertStmt(BinaryOpNode.class, "a < b && c > d;");
        assertEquals(BinaryOpNode.Operator.LOGICAL_AND, node.getOperator());
    }

    /** 括号配对且后面紧跟声明符 → 仍是变量声明（与 javac 取向一致）。 */
    @Test
    public void angleBracketsFollowedByDeclaratorIsDeclaration() {
        ASTNode node = assertStmt(ASTNode.class, "List<String> xs = null;");
        assertTrue("应解析为变量声明，实际为 " + node.getClass().getSimpleName(),
                node instanceof VarDeclNode);
    }

    // ==================== 块语句 ====================

    @Test
    public void emptyBlock() {
        BlockNode node = assertStmt(BlockNode.class, "{}");
        assertTrue(node.getStatements().isEmpty());
    }

    @Test
    public void blockWithStatements() {
        BlockNode node = assertStmt(BlockNode.class, "{ x = 1; y = 2; }");
        assertFalse(node.getStatements().isEmpty());
    }

    // ==================== If 语句 ====================

    @Test
    public void simpleIf() {
        IfNode node = assertStmt(IfNode.class, "if (x > 0) { x = 1; }");
        assertNotNull(node.getCondition());
        assertNotNull(node.getThenBlock());
        assertNull(node.getElseBlock());
    }

    @Test
    public void ifElse() {
        IfNode node = assertStmt(IfNode.class, "if (x > 0) { a; } else { b; }");
        assertNotNull(node.getThenBlock());
        assertNotNull(node.getElseBlock());
    }

    @Test
    public void ifElseIf() {
        IfNode node = assertStmt(IfNode.class, "if (a) { 1; } else if (b) { 2; } else { 3; }");
        assertNotNull(node.getCondition());
        assertNotNull(node.getElseBlock());
    }

    // ==================== While 循环 ====================

    @Test
    public void whileLoop() {
        WhileNode node = assertStmt(WhileNode.class, "while (x < 10) { x = x + 1; }");
        assertNotNull(node.getCondition());
        assertNotNull(node.getBody());
    }

    // ==================== Do-While 循环 ====================

    @Test
    public void doWhileLoop() {
        DoWhileNode node = assertStmt(DoWhileNode.class, "do { x = x - 1; } while (x > 0);");
        assertNotNull(node.getBody());
        assertNotNull(node.getCondition());
    }

    // ==================== For 循环 ====================

    @Test
    public void traditionalForLoop() {
        ForNode node = assertStmt(ForNode.class, "for (i = 0; i < 10; i = i + 1) { ; }");
        assertNotNull(node);
    }

    @Test
    public void forWithEmptyInit() {
        ForNode node = assertStmt(ForNode.class, "for (; x < 10; ) { ; }");
        assertNotNull(node);
    }

    // ==================== Return 语句 ====================

    @Test
    public void returnValue() {
        ReturnNode node = assertStmt(ReturnNode.class, "return x;");
        assertNotNull(node.getValue());
    }

    @Test
    public void returnVoid() {
        ReturnNode node = assertStmt(ReturnNode.class, "return;");
        assertNull(node.getValue());
    }

    // ==================== Break / Continue ====================

    @Test
    public void breakStatement() {
        BreakNode node = assertStmt(BreakNode.class, "break;");
        assertNotNull(node);
    }

    @Test
    public void breakWithLabel() {
        BreakNode node = assertStmt(BreakNode.class, "break outer;");
        assertNotNull(node);
    }

    @Test
    public void continueStatement() {
        ContinueNode node = assertStmt(ContinueNode.class, "continue;");
        assertNotNull(node);
    }

    // ==================== Throw 语句 ====================

    @Test
    public void throwStatement() {
        ThrowNode node = assertStmt(ThrowNode.class, "throw new Exception(\"error\");");
        assertNotNull(node.getExpression());
    }

    // ==================== Assert 语句 ====================

    @Test
    public void assertWithoutMessage() {
        AssertNode node = assertStmt(AssertNode.class, "assert x > 0;");
        assertNotNull(node.getCondition());
        assertNull(node.getMessage());
        assertFalse(node.hasMessage());
    }

    @Test
    public void assertWithMessage() {
        AssertNode node = assertStmt(AssertNode.class, "assert x > 0 : \"x must be positive\";");
        assertNotNull(node.getCondition());
        assertNotNull(node.getMessage());
        assertTrue(node.hasMessage());
    }

    @Test
    public void assertMessageMayBeNonLiteral() {
        AssertNode node = assertStmt(AssertNode.class, "assert x > 0 : \"bad x: \" + x;");
        assertNotNull(node.getMessage());
    }

    @Test
    public void assertRequiresSemicolon() {
        assertParseError("assert x > 0");
    }

    // ==================== static_assert 语句 ====================

    private void assertStaticAssertPasses(String source) {
        try {
            parse(source);
        } catch (CythavaParseException e) {
            fail("static_assert 应通过: " + source + "，实际: " + e.getMessage());
        }
    }

    @Test
    public void staticAssertPassesOnTrueConstant() throws CythavaParseException {
        ASTNode node = parse("static_assert(1 + 1 == 2);");
        // 语法糖：判定通过后不留节点，只剩一个空语句占位
        assertTrue("static_assert 应被消解为空语句，实际 " + node.getClass().getSimpleName(),
                node instanceof LiteralNode);
    }

    @Test
    public void staticAssertWithMessage() {
        assertStaticAssertPasses("static_assert(2 > 1, \"sanity\");");
    }

    @Test
    public void staticAssertFoldsArithmetic() {
        assertStaticAssertPasses("static_assert(1 + 2 * 3 == 7);");
    }

    @Test
    public void staticAssertFoldsBitwise() {
        assertStaticAssertPasses("static_assert((1 | 2) == 3);");
    }

    @Test
    public void staticAssertFoldsUnaryLogicalNot() {
        assertStaticAssertPasses("static_assert(!(1 > 2));");
    }

    @Test
    public void staticAssertFoldsLogicalAnd() {
        assertStaticAssertPasses("static_assert((1 < 2) && (3 > 2));");
    }

    @Test
    public void staticAssertFoldsTernary() {
        assertStaticAssertPasses("static_assert(1 + 2 == 3 ? true : false);");
    }

    @Test
    public void staticAssertFailsOnFalseConstant() {
        assertParseErrorContaining("static_assert(1 > 2);", "条件不成立");
    }

    @Test
    public void staticAssertFailureMessageIsReported() {
        assertParseErrorContaining("static_assert(false, \"math is broken\");", "math is broken");
    }

    @Test
    public void staticAssertRejectsNonBooleanConstant() {
        assertParseErrorContaining("static_assert(1 + 1);", "布尔常量表达式");
    }

    @Test
    public void staticAssertRejectsRuntimeExpression() {
        assertParseErrorContaining("static_assert(x > 0);", "编译期常量表达式");
    }

    @Test
    public void staticAssertRequiresParentheses() {
        assertParseError("static_assert true;");
    }

    // ==================== yield 语句 ====================

    @Test
    public void yieldStatement() {
        YieldNode node = assertStmt(YieldNode.class, "yield 5;");
        assertNotNull(node.getValue());
    }

    @Test
    public void yieldIsRestrictedIdentifier() {
        // 后面跟运算符时 yield 是普通标识符：yield + 1 不能被当成 yield 语句
        ASTNode node = assertStmt(ASTNode.class, "yield + 1;");
        assertFalse("yield + 1 应解析为加法表达式，实际 " + node.getClass().getSimpleName(),
                node instanceof YieldNode);
    }

    // ==================== synchronized 语句 ====================

    @Test
    public void synchronizedStatement() {
        SynchronizedNode node = assertStmt(SynchronizedNode.class, "synchronized (lock) { x = 1; }");
        assertNotNull(node.getLock());
        assertTrue(node.getBody() instanceof BlockNode);
    }

    @Test
    public void synchronizedStatementWithoutBlock() {
        SynchronizedNode node = assertStmt(SynchronizedNode.class, "synchronized (lock) x = 1;");
        assertNotNull(node.getBody());
    }

    @Test
    public void synchronizedModifierIsNotAStatement() {
        // 不带 '(' 的 synchronized 是修饰符，不是语句 → 语句级解析报错
        assertParseError("synchronized;");
    }

    // ==================== Try-Catch ====================

    @Test
    public void tryCatchFinally() {
        TryNode node = assertStmt(TryNode.class,
                "try { risky(); } catch (Exception e) { handle(e); } finally { cleanup(); }");
        assertNotNull(node.getTryBlock());
        assertFalse(node.getCatchClauses().isEmpty());
        assertNotNull(node.getFinallyBlock());
    }

    @Test
    public void tryCatchOnly() {
        TryNode node = assertStmt(TryNode.class,
                "try { risky(); } catch (Exception e) { handle(e); }");
        assertNotNull(node.getTryBlock());
        assertFalse(node.getCatchClauses().isEmpty());
    }

    // ==================== Switch 语句 ====================

    // ==================== Async ====================

    @Test
    public void asyncBlock() {
        AsyncNode node = assertStmt(AsyncNode.class, "async { fetch(); }");
        assertNotNull(node.getExpression());
    }

    // ==================== 变量声明 ====================

    @Test
    public void variableDeclarationWithType() {
        ASTNode node = assertStmt(ASTNode.class, "int x = 42;");
        assertNotNull(node);
    }

    @Test
    public void variableDeclarationWithoutType() {
        ASTNode node = assertStmt(ASTNode.class, "y = 10;");
        assertNotNull(node);
    }

    @Test
    public void variableDeclarationNoInitializer() {
        ASTNode node = assertStmt(ASTNode.class, "String name;");
        assertNotNull(node);
    }

    // ==================== 标签语句 ====================

    @Test
    public void labeledStatement() {
        LabeledStatementNode node = assertStmt(LabeledStatementNode.class, "loop: while (true) { ; }");
        assertEquals("loop", node.getLabel());
        assertNotNull(node.getStatement());
    }

    // ==================== 空语句 ====================

    @Test
    public void emptyStatement() {
        // 单独分号是空语句 — 解析器应该能处理（跳过或返回某种节点）
        ASTNode node = assertStmt(ASTNode.class, ";");
        assertNotNull(node);
    }
}
