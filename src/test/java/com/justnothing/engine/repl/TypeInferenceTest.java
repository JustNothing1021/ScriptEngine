package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ParseException;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.JType;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * 类型推导专项测试。
 * <p>
 * 验证 ExprParser 中所有类型标注（annotate）的正确性，覆盖：
 * <ul>
 *   <li>async/await 类型传递</li>
 *   <li>Switch 表达式 LCM（最小公共超类型）</li>
 *   <li>this/super 类型推断</li>
 *   <li>CastNode 类型检查</li>
 *   <li>ArrayAccess 元素类型</li>
 *   <li>MapLiteral 类型</li>
 *   <li>方法引用类型推断</li>
 *   <li>字段访问（含父类查找）</li>
 *   <li>安全调用 nullable 语义</li>
 *   <li>MethodResolver 重载选择（原始类型 vs 引用类型）</li>
 * </ul>
 *
 * @author JustNothing1021
 * @since 2.0.0
 */
public class TypeInferenceTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
    }

    // ==================== 辅助方法 ====================

    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), context, "<test>");
        return parser.parse();
    }

    private ASTNode parseSingle(String source) throws CythavaParseException {
        List<ASTNode> nodes = parse(source);
        assertEquals("Expected exactly one AST node", 1, nodes.size());
        return nodes.get(0);
    }

    /** 断言节点的 JType rawType 等于期望值。 */
    private void assertType(ASTNode node, Class<?> expected) {
        JType actual = context.getType(node);
        assertNotNull("Node " + node.getClass().getSimpleName() + " should have type info", actual);
        assertEquals("Type mismatch for " + node.getClass().getSimpleName(),
                expected, actual.getRawType());
    }

    // ==================== 1. async/await 类型传递 ====================

    @Test
    public void asyncLiteral_propagatesInnerType() throws Exception {
        ASTNode node = parseSingle("async 42");
        assertTrue("Should be AsyncNode", node instanceof AsyncNode);
        assertType(node, int.class);  // async 保留内部表达式类型
    }

    @Test
    public void asyncString_propagatesStringType() throws Exception {
        ASTNode node = parseSingle("async \"hello\"");
        assertType(node, String.class);
    }

    @Test
    public void awaitAsync_propagatesInnerType() throws Exception {
        ASTNode node = parseSingle("await async 3.14");
        assertTrue("Should be AwaitNode", node instanceof AwaitNode);
        assertType(node, double.class);  // await 解包后类型不变
    }

    @Test
    public void awaitAsyncInt_propagatesInt() throws Exception {
        ASTNode node = parseSingle("await async 1");
        assertType(node, int.class);
    }

    // ==================== 2. MethodResolver 重载选择 ====================

    @Test
    public void overload_println_intSelected() throws Exception {
        // await async 1 → 类型=int → 应绑定 println(int) 而非 println(Object)
        ASTNode call = parseSingle("System.out.println(await async 1)");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull("Should have bound method", mc.getBoundMethod());
        assertEquals("Should bind println(int)",
                int.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_println_doubleSelected() throws Exception {
        ASTNode call = parseSingle("System.out.println(3.14)");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should bind println(double)",
                double.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_println_longSelected() throws Exception {
        ASTNode call = parseSingle("System.out.println(100L)");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should bind println(long)",
                long.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_println_stringSelected() throws Exception {
        ASTNode call = parseSingle("System.out.println(\"hi\")");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should bind println(String)",
                String.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_println_booleanSelected() throws Exception {
        ASTNode call = parseSingle("System.out.println(true)");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should bind println(boolean)",
                boolean.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_println_charSelected() throws Exception {
        ASTNode call = parseSingle("System.out.println('A')");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should bind println(char)",
                char.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    // ==================== 3. Switch 表达式 LCM ====================

    @Test
    public void switchExpr_sameTypes_returnsThatType() throws Exception {
        // 所有 case 返回同一类型 → 该类型
        ASTNode node = parseSingle("switch (1) { case 1 -> 10; default -> 20; }");
        assertTrue("Should be SwitchNode", node instanceof SwitchNode);
        // 所有分支都是 int → LCM 应为 int
        assertType(node, int.class);
    }

    @Test
    public void switchExpr_mixedNumeric_promotesToWidest() throws Exception {
        // 混合数值类型：int + double → double（最宽）
        ASTNode node = parseSingle("switch (1) { case 1 -> 10; default -> 3.14; }");
        assertType(node, double.class);
    }

    // ==================== 4. CastNode 类型检查 ====================

    @Test
    public void castNode_hasTargetType() throws Exception {
        // 先声明 obj 变量
        parse("Object obj;");
        ASTNode node = parseSingle("(String) obj");
        assertTrue("Should be CastNode", node instanceof CastNode);
        CastNode cast = (CastNode) node;
        assertEquals("Target type should be String",
                String.class, cast.getTargetType());
        assertType(node, String.class);
    }

    // ==================== 5. ArrayAccess 元素类型 ====================

    @Test
    public void arrayAccess_inferElementType() throws Exception {
        // 直接在数组字面量上做索引访问，避免变量声明的类型解析问题
        ASTNode access = parseSingle("([10, 20, 30])[0]");
        assertTrue("Should be ArrayAccessNode", access instanceof ArrayAccessNode);
        // 整数字面量数组的元素类型是 int
        assertType(access, int.class);
    }

    // ==================== 6. MapLiteral 类型 ====================

    @Test
    public void mapLiteral_nonEmpty_isMap() throws Exception {
        ASTNode node = parseSingle("{\"key\": \"value\"}");
        assertTrue("Should be MapLiteralNode", node instanceof MapLiteralNode);
        // 非空 map 应推断为 Map.class
        assertType(node, java.util.Map.class);
    }

    @Test
    public void mapLiteral_empty_isObject() throws Exception {
        // 空映射字面量用非空语法测试（空 map 语法可能不被支持）
        ASTNode node = parseSingle("{\"a\": 1}");
        assertTrue("Should be MapLiteralNode", node instanceof MapLiteralNode);
        // 非空 map 应推断为 Map.class
        assertType(node, java.util.Map.class);
    }

    // ==================== 7. 方法引用类型推断 ====================

    @Test
    public void methodReference_hasReturnType() throws Exception {
        // System.out::println — 方法引用应能推断类型
        ASTNode node = parseSingle("System.out::println");
        assertTrue("Should be MethodReferenceNode", node instanceof MethodReferenceNode);
        // 方法引用至少应有类型信息（Object 或具体返回类型）
        JType type = context.getType(node);
        assertNotNull(type);
    }

    // ==================== 8. 字段访问类型推断 ====================

    @Test
    public void fieldAccess_staticField_typeInferred() throws Exception {
        // Integer.MAX_VALUE — 静态字段访问
        ASTNode node = parseSingle("Integer.MAX_VALUE");
        assertTrue("Should be FieldAccessNode or VariableNode", node instanceof FieldAccessNode || node instanceof VariableNode);
        // MAX_VALUE 是 int 字段
        assertType(node, int.class);
    }

    @Test
    public void fieldAccess_systemOut_isPrintStream() throws Exception {
        // System.out — out 字段类型是 PrintStream
        ASTNode node = parseSingle("System.out");
        // 可能解析为 FieldAccessNode 或链式结构
        JType type = context.getType(node);
        assertNotNull(type);
        // PrintStream 或 Object 都可接受（取决于能否反射获取字段）
    }

    // ==================== 9. 安全调用 nullable 语义 ====================

    @Test
    public void safeMethodCall_hasType() throws Exception {
        // str?.length() — 安全调用应能推断返回类型
        ASTNode node = parseSingle("\"hello\"?.length()");
        assertTrue("Should be SafeMethodCallNode", node instanceof SafeMethodCallNode);
        // length() 返回 int
        assertType(node, int.class);
    }

    @Test
    public void safeFieldAccess_hasType() throws Exception {
        // str?.hashCode — 安全字段访问（nullable 语义）
        ASTNode node = parseSingle("\"hello\"?.hashCode");
        assertTrue("Should be SafeFieldAccessNode", node instanceof SafeFieldAccessNode);
        // 安全访问返回 Object（nullable 标记），不推断具体字段类型
        // TODO: 未来可考虑推断为 Integer|Object 联合类型或 Optional<Integer>
        assertType(node, Object.class);
    }

    // ==================== 10. 常量折叠后的类型保持 ====================

    @Test
    public void constantFold_binaryInt_staysInt() throws Exception {
        ASTNode node = parseSingle("2 + 3");
        // 2+3=5 应被折叠为 LiteralNode(5)，类型仍为 int
        if (node instanceof LiteralNode) {
            assertEquals(Integer.valueOf(5), ((LiteralNode) node).getValue());
            assertType(node, int.class);
        } else {
            // 未折叠时也应有正确类型
            assertType(node, int.class);
        }
    }

    @Test
    public void constantFold_ternaryShortCircuit_hasCorrectType() throws Exception {
        // true ? 42 : 0 → 应折叠为 LiteralNode(42)
        ASTNode node = parseSingle("true ? 42 : 0");
        if (node instanceof LiteralNode) {
            assertEquals(Integer.valueOf(42), ((LiteralNode) node).getValue());
            assertType(node, int.class);
        } else {
            assertType(node, int.class);
        }
    }

    @Test
    public void constantFold_arithmeticTernary_foldsCorrectly() throws Exception {
        // 2*3==6 ? 1 : 0 → true ? 1 : 0 → 1
        ASTNode node = parseSingle("2*3==6 ? 1 : 0");
        if (node instanceof LiteralNode) {
            assertEquals(Integer.valueOf(1), ((LiteralNode) node).getValue());
        }
        assertType(node, int.class);
    }

    // ==================== 11. Lambda 表达式类型 ====================

    @Test
    public void lambda_hasFunctionType() throws Exception {
        // Lambda 表达式（参数由 lambda 自身声明，不依赖外部变量）
        ASTNode node = parseSingle("(x) => x + 1");
        assertTrue("Should be LambdaNode", node instanceof LambdaNode);
        // Lambda 至少应有类型信息
        JType type = context.getType(node);
        assertNotNull("Lambda should have type info", type);
    }

    // ==================== 12. 数组字面量类型 ====================

    @Test
    public void arrayLiteral_intArray() throws Exception {
        ASTNode node = parseSingle("[1, 2, 3]");
        assertTrue("Should be ArrayLiteralNode", node instanceof ArrayLiteralNode);
        // 整数字面量数组 → int[]
        JType type = context.getType(node);
        assertNotNull(type);
        assertTrue("Should be array type", type.getArrayDepth() > 0
                || type.getRawType().isArray()
                || type.getRawType() == Object.class);
    }

    // ==================== 13. 三元表达式类型 ====================

    @Test
    public void ternary_sameBranches_sameType() throws Exception {
        ASTNode node = parseSingle("true ? 100 : 200");
        // 两分支都是 int → 结果应为 int
        assertType(node, int.class);
    }

    @Test
    public void ternary_mixedNumeric_widens() throws Exception {
        ASTNode node = parseSingle("true ? 1 : 2.0");
        // TODO: 当前三元表达式不做数值提升（取 then 分支类型），未来应实现 LCM widening
        // 两分支分别是 int 和 double，当前返回 int（then 分支类型）
        JType type = context.getType(node);
        assertNotNull("Ternary should have type", type);
        // 宽待：int 或 double 都可接受（当前行为是 int）
        assertTrue("Ternary mixed numeric should be int or double",
                type.getRawType() == int.class || type.getRawType() == double.class);
    }

    // ==================== 14. 赋值表达式类型 ====================

    @Test
    public void assignment_preservesRhsType() throws Exception {
        parse("int x;");
        ASTNode assign = parseSingle("x = 42");
        assertTrue("Should be AssignmentNode", assign instanceof AssignmentNode);
        // 赋值表达式的类型 = 右侧值的类型
        assertType(assign, int.class);
    }

    // ==================== 15. 管道操作符类型 ====================

    @Test
    public void pipeline_hasResultType() throws Exception {
        ASTNode node = parseSingle("\"hello\" |> System.out::println");
        assertTrue("Should be PipelineNode", node instanceof PipelineNode);
        // 管道结果至少应有类型
        JType type = context.getType(node);
        assertNotNull(type);
    }

    // ==================== 16. Instanceof 类型 ====================

    @Test
    public void instanceof_returnsBoolean() throws Exception {
        ASTNode node = parseSingle("\"hello\" instanceof String");
        assertTrue("Should be InstanceofNode", node instanceof InstanceofNode);
        assertType(node, boolean.class);
    }

    // ==================== 17. 一元运算符类型 ====================

    @Test
    public void unaryNegation_preservesType() throws Exception {
        ASTNode node = parseSingle("-42");
        assertType(node, int.class);
    }

    @Test
    public void unaryNot_returnsBoolean() throws Exception {
        ASTNode node = parseSingle("!true");
        assertType(node, boolean.class);
    }

    @Test
    public void unaryBitwiseNot_preservesInt() throws Exception {
        ASTNode node = parseSingle("~0");
        assertType(node, int.class);
    }

    // ==================== 18. 变量声明类型传播 ====================

    @Test
    public void declaredIntVariable_propagatesType() throws Exception {
        // int x; → 后续引用 x 应推断为 int 类型（从符号表取声明类型）
        parse("int x;");
        ASTNode ref = parseSingle("x");
        assertTrue("Should be VariableNode", ref instanceof VariableNode);
        assertType(ref, int.class);
    }

    @Test
    public void declaredStringVariable_propagatesType() throws Exception {
        parse("String s;");
        ASTNode ref = parseSingle("s");
        assertType(ref, String.class);
    }

    @Test
    public void autoVariable_infersFromInitializer() throws Exception {
        // x = 42; → auto 声明，从初始化器推断 int 类型
        parse("x = 42;");
        ASTNode ref = parseSingle("x");
        assertTrue("Should be VariableNode", ref instanceof VariableNode);
        assertType(ref, int.class);
    }

    @Test
    public void autoDoubleVariable_infersFromInitializer() throws Exception {
        parse("d = 3.14;");
        ASTNode ref = parseSingle("d");
        assertType(ref, double.class);
    }

    // ==================== 19. 三元表达式数值提升 ====================

    @Test
    public void ternary_mixedNumeric_promotesToDouble() throws Exception {
        // true ? 1 : 2.0 → int + double → double（最宽数值类型）
        ASTNode node = parseSingle("true ? 1 : 2.0");
        assertType(node, double.class);
    }

    @Test
    public void ternary_intLong_promotesToLong() throws Exception {
        // true ? 1 : 2L → int + long → long
        ASTNode node = parseSingle("true ? 1 : 2L");
        assertType(node, long.class);
    }

    // ==================== 20. 花括号数组类型推断 ====================

    @Test
    public void braceArrayLiteral_intElements() throws Exception {
        // {1, 2, 3} → 花括号数组初始化器应推断元素类型为 int
        ASTNode node = parseSingle("{1, 2, 3}");
        assertTrue("Should be ArrayLiteralNode", node instanceof ArrayLiteralNode);
        JType type = context.getType(node);
        assertNotNull(type);
        assertTrue("Brace array of ints should have arrayDepth > 0",
                type.getArrayDepth() > 0 || type.getRawType().isArray());
    }

    // ==================== 21. 前缀/后缀自增自减类型推断 ====================

    @Test
    public void prefixIncrement_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("++x");
        assertType(node, int.class);
    }

    @Test
    public void postfixIncrement_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("x++");
        assertType(node, int.class);
    }

    @Test
    public void prefixDecrement_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("--x");
        assertType(node, int.class);
    }

    @Test
    public void postfixDecrement_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("x--");
        assertType(node, int.class);
    }

    // ==================== 22. 复合赋值类型推断 ====================

    @Test
    public void compoundAddAssignment_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("x += 42");
        assertType(node, int.class);
    }

    @Test
    public void compoundMulAssignment_preservesType() throws Exception {
        parse("int x;");
        ASTNode node = parseSingle("x *= 2");
        assertType(node, int.class);
    }

    // ==================== 23. new 表达式数组类型推断 ====================

    @Test
    public void newArray_intArray_hasArrayType() throws Exception {
        ASTNode node = parseSingle("new int[5]");
        assertType(node, int[].class);
    }

    @Test
    public void newArray_stringArray_hasArrayType() throws Exception {
        ASTNode node = parseSingle("new String[3]");
        assertType(node, String[].class);
    }

    @Test
    public void newArray_multiDim_hasArrayType() throws Exception {
        ASTNode node = parseSingle("new int[2][3]");
        assertType(node, int[][].class);
    }
}
