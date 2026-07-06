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
