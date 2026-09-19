package com.justnothing.engine.parser;

import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * 解析错误报告测试。
 * <p>
 * 覆盖错误报告的三个能力：
 * <ul>
 *   <li>一次解析报告多处错误（语句级错误恢复 + 聚合），而不是只报第一个</li>
 *   <li>错误消息带精确位置（line/column）</li>
 *   <li>错误分类走结构化 errorCode / isSemanticError，而不是匹配消息关键字</li>
 * </ul>
 * </p>
 */
public class ParseErrorReportingTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
    }

    /** 解析源码并返回抛出的解析错误（源码必须解析失败）。 */
    private CythavaParseException parseError(String source) {
        try {
            new Parser(new Lexer(source, "<test>").tokenize(), context, "<test>").parse();
            fail("Expected CythavaParseException for: " + source);
            return null;
        } catch (CythavaParseException e) {
            return e;
        }
    }

    // ==================== 多错误聚合 ====================

    @Test
    public void reportsAllErrorsInOnePass() {
        // 修复前：遇到第一个错误即中止，只报告一条
        CythavaParseException e = parseError("int a = ; int b = ;");
        assertTrue("应聚合多条错误", e.getMessage().contains("Found 2 error(s):"));
    }

    @Test
    public void reportsAllErrorsInsideMethodBody() {
        // 方法体内的多个错误也应全部报出（语句级恢复作用于所有块）
        CythavaParseException e = parseError("class A { void m() { int x = ; int y = ; } }");
        assertTrue(e.getMessage().contains("Found 2 error(s):"));
    }

    @Test
    public void reportsErrorsBothInClassFieldAndMethodBody() {
        CythavaParseException e = parseError("class B { int v = ; void m() { int q = ; } }");
        assertTrue(e.getMessage().contains("Found 3 error(s):"));
    }

    @Test
    public void aggregatedErrorsKeepRealCause() {
        // 修复前：声明解析失败后回退到语句解析并重复解析同一段输入，
        // 报出派生的 "Duplicate variable declaration"，掩盖真正的错误
        CythavaParseException e = parseError("int a = ; int b = ;");
        assertTrue(e.getMessage().contains("Expected expression"));
        assertFalse("不应报出重复声明这类派生错误",
                e.getMessage().contains("Duplicate variable declaration"));
    }

    @Test
    public void singleErrorIsNotWrapped() {
        // 只有一处错误时保持原始异常（消息不带聚合头）
        CythavaParseException e = parseError("int a = ;");
        assertFalse(e.getMessage().contains("Found "));
        assertTrue(e.getMessage().contains("Expected expression"));
    }

    // ==================== 错误位置 ====================

    @Test
    public void errorMessageCarriesLineAndColumn() {
        CythavaParseException e = parseError("int a = ;");
        assertNotNull(e.getLocation());
        assertEquals(1, e.getLocation().getLine());
        assertEquals(9, e.getLocation().getColumn()); // 缺失初始化表达式的 ';'
    }

    @Test
    public void duplicateDeclarationErrorCarriesLocation() {
        // 修复前：ParseContext 抛出重复声明时位置传 null → 消息里没有 (at line ..)
        CythavaParseException e = parseError("int x = 1; int x = 2;");
        assertNotNull(e.getLocation());
        assertEquals(16, e.getLocation().getColumn());
        assertTrue(e.getMessage().contains("at line 1, column 16"));
    }

    @Test
    public void errorPositionsAreDistinctPerError() {
        CythavaParseException e = parseError("int a = ; int b = ;");
        assertTrue(e.getMessage().contains("column 9"));
        assertTrue(e.getMessage().contains("column 19"));
    }

    // ==================== 结构化错误分类 ====================

    @Test
    public void unknownSymbolIsStructuredSemanticError() {
        CythavaParseException e = parseError("int a = 1; unknownThing;");
        assertTrue(e.isSemanticError());
        assertTrue(e.getMessage().contains("Cannot find symbol"));
    }

    @Test
    public void duplicateDeclarationIsReportedWithScopeCode() {
        CythavaParseException e = parseError("int x = 1; int x = 2;");
        assertTrue(e.getMessage().contains("Duplicate variable declaration"));
    }

    @Test
    public void forLoopInitVariableDoesNotLeakBetweenLoops() {
        // 修复前：for 初始化变量泄漏到外层，第二次同名循环报
        // "Expected ';' after for initialization" 这类误导性错误
        try {
            List<Token> tokens = new Lexer("for (int i = 0;;) break; for (int i = 0;;) break;", "<test>")
                    .tokenize();
            new Parser(tokens, context, "<test>").parse();
        } catch (CythavaParseException e) {
            fail("同名 for 循环不应报错: " + e.getMessage());
        }
    }

    @Test
    public void recoveryKeepsParsingSubsequentValidStatements() {
        // 第一处错误之后仍能继续解析；后续真实错误也要被报出
        CythavaParseException e = parseError("int a = ; int b = 2; unknownThing;");
        assertTrue(e.getMessage().contains("Expected expression"));
        assertTrue(e.getMessage().contains("Cannot find symbol: 'unknownThing'"));
    }
}
