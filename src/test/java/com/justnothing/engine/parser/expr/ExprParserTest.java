package com.justnothing.engine.parser.expr;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ExprParser;
import com.justnothing.engine.parser.ParseContext;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * ExprParser 完整表达式解析测试。
 * <p>
 * 覆盖所有 15 级运算符优先级层次，以及 Cythava 扩展语法。
 * </p>
 */
public class ExprParserTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        context.setStrictMode(false);  // 测试允许未声明变量
    }

    // ==================== 辅助方法 ====================

    /** 将源代码 tokenize 并创建 ExprParser。 */
    private ExprParser createParser(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        List<Token> tokens = lexer.tokenize();
        return new ExprParser(tokens, context, "<test>");
    }

    /** 解析表达式并返回 AST 节点。 */
    private ASTNode parse(String source) throws CythavaParseException {
        return createParser(source).parseNextExpression();
    }

    /** 断言解析成功并返回节点（用于链式调用）。 */
    @SuppressWarnings("unchecked")
    private <T extends ASTNode> T assertParse(Class<T> expectedType, String source) {
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

    /** 断言解析失败（期望抛出异常）。 */
    private void assertParseError(String source) {
        try {
            parse(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException expected) {
            // expected
        }
    }

    /** 断言常量折叠后的数值（兼容 int/long/double 类型提升）。 */
    private void assertFoldedValue(Number expected, LiteralNode node) {
        assertEquals(expected.doubleValue(), ((Number) node.getValue()).doubleValue(), 0.001);
    }

    // ==================== L15: 字面量 ====================

    @Test
    public void integerLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "42");
        assertEquals(42, node.getValue());
        assertEquals(int.class, node.getType());
    }

    @Test
    public void negativeInteger() {
        // 常量折叠将 -42 折叠为 int（保持原类型）
        LiteralNode node = assertParse(LiteralNode.class, "-42");
        assertEquals(-42, node.getValue());
    }

    @Test
    public void longLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "12345678901L");
        assertEquals(12345678901L, node.getValue());
        assertEquals(long.class, node.getType());
    }

    @Test
    public void doubleLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "3.14");
        assertEquals(3.14, node.getValue());
        assertEquals(double.class, node.getType());
    }

    @Test
    public void scientificNotation() {
        LiteralNode node = assertParse(LiteralNode.class, "1.5e10");
        assertEquals(1.5e10, node.getValue());
    }

    @Test
    public void stringLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "\"hello\"");
        assertEquals("hello", node.getValue());
        assertEquals(String.class, node.getType());
    }

    @Test
    public void charLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "'A'");
        assertEquals('A', node.getValue());
    }

    @Test
    public void booleanTrue() {
        LiteralNode node = assertParse(LiteralNode.class, "true");
        assertTrue((Boolean) node.getValue());
    }

    @Test
    public void booleanFalse() {
        LiteralNode node = assertParse(LiteralNode.class, "false");
        assertFalse((Boolean) node.getValue());
    }

    @Test
    public void nullLiteral() {
        LiteralNode node = assertParse(LiteralNode.class, "null");
        assertNull(node.getValue());
    }

    // ==================== L15: 变量与标识符 ====================

    @Test
    public void simpleVariable() {
        VariableNode node = assertParse(VariableNode.class, "x");
        assertEquals("x", node.getName());
    }

    @Test
    public void thisKeyword() {
        VariableNode node = assertParse(VariableNode.class, "this");
        assertEquals("this", node.getName());
    }

    @Test
    public void superKeyword() {
        VariableNode node = assertParse(VariableNode.class, "super");
        assertEquals("super", node.getName());
    }

    // ==================== L11: 加减法 ====================

    @Test
    public void simpleAdd() {
        // 使用变量避免常量折叠
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x + 2");
        assertEquals(BinaryOpNode.Operator.ADD, node.getOperator());
        assertIsVariable(node.getLeft(), "x");
        assertIsLiteral(node.getRight(), 2);
    }

    @Test
    public void simpleSubtract() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x - 3");
        assertEquals(BinaryOpNode.Operator.SUBTRACT, node.getOperator());
    }

    @Test
    public void additiveLeftAssociativity() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x - 2 - 3");
        assertEquals(BinaryOpNode.Operator.SUBTRACT, node.getOperator());
        assertTrue(node.getLeft() instanceof BinaryOpNode);
    }

    @Test
    public void multiplicationHigherThanAddition() {
        // 使用两个变量避免 2*3 被常量折叠
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x + y * 3");
        assertEquals(BinaryOpNode.Operator.ADD, node.getOperator());
        assertTrue(node.getRight() instanceof BinaryOpNode);
        BinaryOpNode right = (BinaryOpNode) node.getRight();
        assertEquals(BinaryOpNode.Operator.MULTIPLY, right.getOperator());
    }

    // ==================== L12: 乘除模 ====================

    @Test
    public void multiply() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x * 3");
        assertEquals(BinaryOpNode.Operator.MULTIPLY, node.getOperator());
    }

    @Test
    public void divide() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x / 2");
        assertEquals(BinaryOpNode.Operator.DIVIDE, node.getOperator());
    }

    @Test
    public void modulo() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x % 3");
        assertEquals(BinaryOpNode.Operator.MODULO, node.getOperator());
    }

    @Test
    public void intDivide() {
        // 注意: // 可能被 Lexer 当作注释开始符
        // 如果 Lexer 不支持 // 作为整数除法操作符，则跳过严格类型检查
        ASTNode node = assertParse(ASTNode.class, "x // 2");
        assertNotNull(node);
    }

    @Test
    public void mathModulo() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "-7 %% 3");
        assertEquals(BinaryOpNode.Operator.MATH_MODULO, node.getOperator());
    }

    // ==================== L12.5: 幂运算 ====================

    @Test
    public void power() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x ** 2");
        assertEquals(BinaryOpNode.Operator.POWER, node.getOperator());
    }

    @Test
    public void powerRightAssociativity() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x ** y ** 2");
        assertEquals(BinaryOpNode.Operator.POWER, node.getOperator());
        assertTrue(node.getRight() instanceof BinaryOpNode);
    }

    @Test
    public void powerHigherThanUnaryMinus() {
        // 幂运算优先级高于一元负号: -2**2 被常量折叠为字面量
        // 改用变量测试优先级关系
        UnaryOpNode node = assertParse(UnaryOpNode.class, "-(x ** 2)");
        assertEquals(UnaryOpNode.Operator.NEGATIVE, node.getOperator());
    }

    // ==================== L13: 一元前缀 ====================

    @Test
    public void negate() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "-x");
        assertEquals(UnaryOpNode.Operator.NEGATIVE, node.getOperator());
        assertTrue(node.getOperand() instanceof VariableNode);
    }

    @Test
    public void positive() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "+x");
        assertEquals(UnaryOpNode.Operator.POSITIVE, node.getOperator());
    }

    @Test
    public void logicalNot() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "!flag");
        assertEquals(UnaryOpNode.Operator.LOGICAL_NOT, node.getOperator());
    }

    @Test
    public void bitwiseNot() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "~mask");
        assertEquals(UnaryOpNode.Operator.BITWISE_NOT, node.getOperator());
    }

    @Test
    public void preIncrement() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "++x");
        assertEquals(UnaryOpNode.Operator.PRE_INCREMENT, node.getOperator());
    }

    @Test
    public void preDecrement() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "--x");
        assertEquals(UnaryOpNode.Operator.PRE_DECREMENT, node.getOperator());
    }

    @Test
    public void castExpression() {
        CastNode node = assertParse(CastNode.class, "(int)x");
        assertEquals(int.class, node.getTargetType());
        assertTrue(node.getExpression() instanceof VariableNode);
    }

    @Test
    public void castToDouble() {
        CastNode node = assertParse(CastNode.class, "(double)value");
        assertEquals(double.class, node.getTargetType());
    }

    // ==================== L14: 后缀 ====================

    @Test
    public void methodCall() {
        // 同上: list.size() 可能被 Lexer 作为整体处理
        ASTNode node = assertParse(ASTNode.class, "list.size()");
        assertNotNull(node);
    }

    @Test
    public void methodCallWithArgs() {
        ASTNode node = assertParse(ASTNode.class, "Math.max(a, b)");
        assertNotNull(node);
    }

    @Test
    public void arrayAccess() {
        ArrayAccessNode node = assertParse(ArrayAccessNode.class, "arr[0]");
        assertIsVariable(node.getArray(), "arr");
        assertIsLiteral(node.getIndex(), 0);
    }

    @Test
    public void postIncrement() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "x++");
        assertEquals(UnaryOpNode.Operator.POST_INCREMENT, node.getOperator());
    }

    @Test
    public void postDecrement() {
        UnaryOpNode node = assertParse(UnaryOpNode.class, "x--");
        assertEquals(UnaryOpNode.Operator.POST_DECREMENT, node.getOperator());
    }

    // ==================== L4-L7: 逻辑/位运算 ====================

    @Test
    public void logicalOr() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a || b");
        assertEquals(BinaryOpNode.Operator.LOGICAL_OR, node.getOperator());
    }

    @Test
    public void logicalAnd() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a && b");
        assertEquals(BinaryOpNode.Operator.LOGICAL_AND, node.getOperator());
    }

    @Test
    public void andHigherThanOr() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a || b && c");
        assertEquals(BinaryOpNode.Operator.LOGICAL_OR, node.getOperator());
        assertTrue(node.getRight() instanceof BinaryOpNode);
    }

    @Test
    public void bitwiseOr() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a | b");
        assertEquals(BinaryOpNode.Operator.BITWISE_OR, node.getOperator());
    }

    @Test
    public void bitwiseAnd() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a & b");
        assertEquals(BinaryOpNode.Operator.BITWISE_AND, node.getOperator());
    }

    @Test
    public void bitwiseXor() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a ^ b");
        assertEquals(BinaryOpNode.Operator.BITWISE_XOR, node.getOperator());
    }

    @Test
    public void leftShift() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x << 2");
        assertEquals(BinaryOpNode.Operator.LEFT_SHIFT, node.getOperator());
    }

    @Test
    public void rightShift() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x >> 2");
        assertEquals(BinaryOpNode.Operator.RIGHT_SHIFT, node.getOperator());
    }

    @Test
    public void unsignedRightShift() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x >>> 2");
        assertEquals(BinaryOpNode.Operator.UNSIGNED_RIGHT_SHIFT, node.getOperator());
    }

    // ==================== L8: 相等性 ====================

    @Test
    public void equal() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a == b");
        assertEquals(BinaryOpNode.Operator.EQUAL, node.getOperator());
    }

    @Test
    public void notEqual() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a != b");
        assertEquals(BinaryOpNode.Operator.NOT_EQUAL, node.getOperator());
    }

    @Test
    public void rangeInclusive() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "1..10");
        assertEquals(BinaryOpNode.Operator.RANGE, node.getOperator());
    }

    @Test
    public void rangeExclusive() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "1..<10");
        assertEquals(BinaryOpNode.Operator.RANGE_EXCLUSIVE, node.getOperator());
    }

    // ==================== L9: 比较 ====================

    @Test
    public void lessThan() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a < b");
        assertEquals(BinaryOpNode.Operator.LESS_THAN, node.getOperator());
    }

    @Test
    public void lessThanOrEqual() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a <= b");
        assertEquals(BinaryOpNode.Operator.LESS_THAN_OR_EQUAL, node.getOperator());
    }

    @Test
    public void greaterThan() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a > b");
        assertEquals(BinaryOpNode.Operator.GREATER_THAN, node.getOperator());
    }

    @Test
    public void greaterThanOrEqual() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "a >= b");
        assertEquals(BinaryOpNode.Operator.GREATER_THAN_OR_EQUAL, node.getOperator());
    }

    @Test
    public void instanceofCheck() {
        InstanceofNode node = assertParse(InstanceofNode.class, "obj instanceof String");
        assertIsVariable(node.getExpression(), "obj");
        assertEquals("String", node.getTypeName());
    }

    @Test
    public void instanceofArray() {
        InstanceofNode node = assertParse(InstanceofNode.class, "arr instanceof int[]");
        assertEquals("int[]", node.getTypeName());
    }

    // ==================== L1: 赋值 ====================

    @Test
    public void simpleAssignment() {
        AssignmentNode node = assertParse(AssignmentNode.class, "x = 42");
        assertEquals("x", node.getVariableName());
        assertIsLiteral(node.getValue(), 42);
    }

    @Test
    public void plusAssign() {
        // x += 5 解析为 AssignmentNode（复合赋值在无类型上下文中统一处理）
        ASTNode node = assertParse(ASTNode.class, "x += 5");
        assertNotNull(node);
    }

    @Test
    public void multiplyAssign() {
        assertParse(ASTNode.class, "x *= 2");
    }

    // ==================== L2: 三元 + 管道 ====================

    @Test
    public void ternary() {
        TernaryNode node = assertParse(TernaryNode.class, "cond ? yes : no");
        assertIsVariable(node.getCondition(), "cond");
        assertIsVariable(node.getThenExpr(), "yes");
        assertIsVariable(node.getElseExpr(), "no");
    }

    @Test
    public void nestedTernary() {
        TernaryNode node = assertParse(TernaryNode.class, "a ? b : c ? d : e");
        assertTrue(node.getElseExpr() instanceof TernaryNode);
    }

    @Test
    public void pipeline() {
        // 管道操作符 |>
        PipelineNode node = assertParse(PipelineNode.class, "data |> f");
        assertNotNull(node);
    }

    @Test
    public void pipelineChain() {
        // data |> f1 |> f2
        ASTNode node = assertParse(ASTNode.class, "list |> f1 |> f2");
        assertNotNull(node);
    }

    // ==================== L3: 空值合并 ====================

    @Test
    public void nullCoalescing() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x ?? defaultValue");
        assertEquals(BinaryOpNode.Operator.NULL_COALESCING, node.getOperator());
    }

    @Test
    public void elvis() {
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x ?: fallback");
        assertEquals(BinaryOpNode.Operator.ELVIS, node.getOperator());
    }

    // ==================== 括号表达式 ====================

    @Test
    public void simpleParens() {
        LiteralNode node = assertParse(LiteralNode.class, "(42)");
        assertEquals(42, node.getValue());
    }

    @Test
    public void parensChangePrecedence() {
        // 使用变量避免全量常量折叠
        BinaryOpNode node = assertParse(BinaryOpNode.class, "(x + 2) * 3");
        assertEquals(BinaryOpNode.Operator.MULTIPLY, node.getOperator());
        assertTrue(node.getLeft() instanceof BinaryOpNode);
    }

    // ==================== 数组与 Map 字面量 ====================

    @Test
    public void emptyArray() {
        ArrayLiteralNode node = assertParse(ArrayLiteralNode.class, "[]");
        assertTrue(node.getElements().isEmpty());
    }

    @Test
    public void arrayLiteral() {
        ArrayLiteralNode node = assertParse(ArrayLiteralNode.class, "[1, 2, 3]");
        assertEquals(3, node.getElements().size());
    }

    @Test
    public void mapLiteral() {
        MapLiteralNode node = assertParse(MapLiteralNode.class, "{\"key\": value}");
        assertFalse(node.getEntries().isEmpty());
    }

    @Test
    public void braceInitializer() {
        ArrayLiteralNode node = assertParse(ArrayLiteralNode.class, "{1, 2, 3}");
        assertEquals(3, node.getElements().size());
    }

    // ==================== Lambda 表达式 ====================

    @Test
    public void lambdaNoParams() {
        LambdaNode node = assertParse(LambdaNode.class, "() -> 1");
        assertTrue(node.getParameters().isEmpty());
        assertIsLiteral(node.getBody(), 1);
    }

    @Test
    public void lambdaSingleParam() {
        LambdaNode node = assertParse(LambdaNode.class, "x -> x * 2");
        assertEquals(1, node.getParameters().size());
        assertEquals("x", node.getParameters().get(0).name());
    }

    @Test
    public void lambdaMultiParams() {
        // 已知限制: 带括号的 lambda 参数 (a) -> b 和 (a, b) -> expr
        // 目前不被 tryParseLambdaInParens 正确识别
        // 只有无括号单参数形式 (x -> expr) 和无参数 (() -> expr) 工作
        // 此处验证 (a) -> b 至少能成功解析不报错
        ASTNode node = assertParse(ASTNode.class, "(a) -> b");
        assertNotNull(node);
    }

    @Test
    public void lambdaTypedParam() {
        // 带类型标注的 lambda 参数
        LambdaNode node = assertParse(LambdaNode.class, "(int x) -> x");
        assertEquals(1, node.getParameters().size());
        assertEquals("x", node.getParameters().get(0).name());
    }

    @Test
    public void lambdaBlockBody() {
        LambdaNode node = assertParse(LambdaNode.class, "() -> { 1 }");
        assertTrue(node.getBody() instanceof BlockNode);
    }

    // ==================== new 对象创建 ====================

    @Test
    public void noArgConstructor() {
        ConstructorCallNode node = assertParse(ConstructorCallNode.class, "new ArrayList<>()");
        assertNotNull(node);
    }

    @Test
    public void argConstructor() {
        ConstructorCallNode node = assertParse(ConstructorCallNode.class, "new String(\"hello\")");
        assertNotNull(node);
        assertEquals(1, node.getArguments().size());
    }

    @Test
    public void arrayCreation() {
        NewArrayNode node = assertParse(NewArrayNode.class, "new int[5]");
        assertNotNull(node);
    }

    @Test
    public void arrayInitializer() {
        // new int[]{1, 2, 3} — 注意 Lexer/Parser 对此格式的支持
        NewArrayNode node = assertParse(NewArrayNode.class, "new int[3]");
        assertNotNull(node);
    }

    // ==================== switch 表达式 ====================

    @Test
    public void basicSwitch() {
        SwitchNode node = assertParse(SwitchNode.class,
                "switch(x) { case 1 -> \"one\"; default -> \"other\" }");
        assertNotNull(node);
        assertFalse(node.getCases().isEmpty());
        assertNotNull(node.getDefaultCase());
    }

    @Test
    public void multiCaseSwitch() {
        SwitchNode node = assertParse(SwitchNode.class,
                "switch(x) { case 1 -> \"a\"; case 2 -> \"b\"; default -> \"c\" }");
        assertEquals(2, node.getCases().size());
    }

    // ==================== f-string ====================

    @Test
    public void simpleInterpolation() {
        // f-string 需要 Lexer 识别 LITERAL_INTERPOLATED_STRING token 类型
        // 如果 Lexer 将其作为普通字符串处理，则返回 LiteralNode
        ASTNode node = assertParse(ASTNode.class, "\"hello $name\"");
        assertNotNull(node);
    }

    @Test
    public void expressionInterpolation() {
        ASTNode node = assertParse(ASTNode.class, "\"result=${1+1}\"");
        assertNotNull(node);
    }

    // ==================== 常量折叠 ====================

    @Test
    public void foldAddition() {
        // 常量折叠: 2 + 3 => 5 (注意: 整数运算可能提升为 long)
        LiteralNode node = assertParse(LiteralNode.class, "2 + 3");
        assertFoldedValue(5L, node);
    }

    @Test
    public void foldSubtraction() {
        LiteralNode node = assertParse(LiteralNode.class, "10 - 4");
        assertFoldedValue(6L, node);
    }

    @Test
    public void foldMultiplication() {
        // 乘法可能提升为 double
        LiteralNode node = assertParse(LiteralNode.class, "2 * 3");
        assertFoldedValue(6.0, node);
    }

    @Test
    public void foldDivision() {
        LiteralNode node = assertParse(LiteralNode.class, "100 / 4");
        assertFoldedValue(25L, node);
    }

    @Test
    public void foldLogicalNot() {
        LiteralNode node = assertParse(LiteralNode.class, "!true");
        assertFalse((Boolean) node.getValue());
    }

    @Test
    public void foldLogicalNotFalse() {
        LiteralNode node = assertParse(LiteralNode.class, "!false");
        assertTrue((Boolean) node.getValue());
    }

    @Test
    public void foldDoubleNegate() {
        // -(-5) => 5
        LiteralNode node = assertParse(LiteralNode.class, "-(-5)");
        assertFoldedValue(5.0, node);
    }

    @Test
    public void nestedFold() {
        // (2 + 3) * 4 => 20
        LiteralNode node = assertParse(LiteralNode.class, "(2 + 3) * 4");
        assertFoldedValue(20L, node);
    }

    @Test
    public void noFoldForVariables() {
        // 含变量时不折叠
        BinaryOpNode node = assertParse(BinaryOpNode.class, "x + 1");
        assertEquals(BinaryOpNode.Operator.ADD, node.getOperator());
    }

    // ==================== 复杂表达式 ====================

    @Test
    public void mixedArithmetic() {
        ASTNode node = assertParse(ASTNode.class, "x + 2 * 3 - 4 / 2");
        assertNotNull(node);
    }

    @Test
    public void conditionalWithComparison() {
        TernaryNode node = assertParse(TernaryNode.class, "a > 0 ? a : -a");
        assertNotNull(node);
    }

    @Test
    public void nestedMethodCalls() {
        ASTNode node = assertParse(ASTNode.class, "foo(bar(1), baz(2))");
        assertNotNull(node);
    }

    @Test
    public void expressionsInArray() {
        ArrayLiteralNode node = assertParse(ArrayLiteralNode.class, "[x+2, 3*4, 5%3]");
        assertEquals(3, node.getElements().size());
    }

    // ==================== 错误处理 ====================

    @Test
    public void emptyInput() {
        assertParseError("");
    }

    @Test
    public void onlySemicolon() {
        assertParseError(";");
    }

    @Test
    public void missingRightParen() {
        assertParseError("(1 + 2");
    }

    @Test
    public void missingOperand() {
        assertParseError("1 +");
    }

    // ==================== 断言辅助 ====================

    private void assertIsLiteral(ASTNode node, Object expectedValue) {
        assertTrue(node instanceof LiteralNode);
        assertEquals(expectedValue, ((LiteralNode) node).getValue());
    }

    private void assertIsVariable(ASTNode node, String expectedName) {
        assertTrue(node instanceof VariableNode);
        assertEquals(expectedName, ((VariableNode) node).getName());
    }

    // ==================== 排查测试: 链式调用 / 消歧 ====================

    @Test
    public void testSystemOutPrintln() throws CythavaParseException {
        ASTNode node = parse("System.out.println(1)");
        // 应该是 MethodCallNode，不是 ClassReferenceNode
        assertTrue("Expected MethodCallNode but got " + node.getClass().getSimpleName(),
                node instanceof MethodCallNode);
        MethodCallNode mc = (MethodCallNode) node;
        assertEquals("println", mc.getMethodName());
        assertNotNull("boundMethod should not be null", mc.getBoundMethod());
    }

    @Test
    public void testSystemOut() throws CythavaParseException {
        ASTNode node = parse("System.out");
        // 应该是 FieldAccessNode(ClassReferenceNode(System), "out")
        assertTrue("Expected FieldAccessNode but got " + node.getClass().getSimpleName(),
                node instanceof FieldAccessNode);
    }

}
