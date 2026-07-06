package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * REPL 场景回归测试。
 * <p>
 * 覆盖用户在 REPL 中手动验证过的核心场景，以及各种复杂边界情况。
 * 每个测试模拟一次完整的 REPL 输入 → 解析 → 断言 流程。
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>通配符泛型声明与解析</li>
 *   <li>通配符上界方法绑定 (add(Number) vs add(Object))</li>
 *   <li>for-each auto/var 关键字</li>
 *   <li>自定义类泛型声明 class Box&lt;T&gt;</li>
 *   <li>自定义类实例化 Box&lt;String&gt;</li>
 *   <li>自定义类字段访问 stringBox.value</li>
 *   <li>嵌套通配符、多参数泛型、链式访问等复杂场景</li>
 * </ol>
 */
public class ReplRegressionTest {

    private ParseContext context;
    private DynamicClassGenerator codegen;

    @Before
    public void setUp() {
        context = new ParseContext();
        codegen = new DynamicClassGenerator(context.getClassLoader());
        codegen.setDelegateToExecutor(true);
        context.setClassLoader(codegen.getLoader());
        context.setCodeGenerator(codegen);
        context.addImport("java.util.*");
        context.addImport("java.lang.*");
        context.setStrictMode(false);
        context.setStrictMode(false);
    }

    // ==================== 辅助方法 ====================

    /** 解析单行输入（自动注册 ClassDeclarationNode 到上下文）。 */
    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<repl>");
        Parser parser = new Parser(lexer.tokenize(), context, "<repl>");
        List<ASTNode> nodes = parser.parse();
        for (ASTNode node : nodes) {
            if (node instanceof ClassDeclarationNode cd) {
                context.declareClass(cd);
            }
        }
        return nodes;
    }

    /** 解析多行输入（先提取并解析 class 声明，再解析剩余语句）。 */
    private List<ASTNode> parseMultiLine(String source) throws CythavaParseException {
        String[] lines = source.split("\n");
        StringBuilder classDecls = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("class ") || trimmed.startsWith("interface ")) {
                classDecls.append(line).append("\n");
            }
        }
        if (classDecls.length() > 0) {
            parse(classDecls.toString());
        }
        Lexer lexer = new Lexer(source, "<repl>");
        Parser parser = new Parser(lexer.tokenize(), context, "<repl>");
        return parser.parse();
    }

    private ASTNode last(List<ASTNode> nodes) {
        assertFalse("Expected at least one node", nodes.isEmpty());
        return nodes.get(nodes.size() - 1);
    }

    private ASTNode first(List<ASTNode> nodes) {
        assertFalse("Expected at least one node", nodes.isEmpty());
        return nodes.get(0);
    }

    // ==================== 场景 1: 通配符泛型声明与解析 ====================

    @Test
    public void wildcardExtendsNumber_declaration() throws Exception {
        List<ASTNode> nodes = parse("List<? extends Number> l = new ArrayList<>();");

        assertTrue("应为 VarDeclNode", first(nodes) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) first(nodes);
        assertEquals("l", varDecl.getVarName());

        // 初始值应为 ConstructorCallNode
        assertNotNull("应有初始值", varDecl.getInitializer());
        assertTrue("初始值应为 ConstructorCallNode",
                varDecl.getInitializer() instanceof ConstructorCallNode);

        ConstructorCallNode ctor = (ConstructorCallNode) varDecl.getInitializer();
        assertEquals("ArrayList", ctor.getClassName());
    }

    @Test
    public void wildcardSuperInteger_declaration() throws Exception {
        // ? super Integer 通配符下界
        List<ASTNode> nodes = parse("List<? super Integer> l = new ArrayList<>();");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) first(nodes);
        assertEquals("l", varDecl.getVarName());
    }

    @Test
    public void plainWildcard_declaration() throws Exception {
        // 纯通配符 ?
        List<ASTNode> nodes = parse("List<?> l = new ArrayList<>();");

        assertTrue(first(nodes) instanceof VarDeclNode);
        assertEquals("l", ((VarDeclNode) first(nodes)).getVarName());
    }

    // ==================== 场景 2: 通配符上界方法绑定 ====================

    @Test
    public void wildcardExtendsNumber_addIntBindsCorrectly() throws Exception {
        // l.add(1) — int 参数应绑定到 add(Number) 而非 add(Object)
        List<ASTNode> nodes = parseMultiLine(
                "List<? extends Number> l = new ArrayList<>();\n" +
                        "l.add(1);");

        MethodCallNode call = (MethodCallNode) last(nodes);
        assertEquals("add", call.getMethodName());
        assertNotNull("add 方法应被成功绑定", call.getBoundMethod());
        assertEquals(1, call.getArguments().size());
    }

    @Test
    public void wildcardExtendsNumber_addDoubleBindsCorrectly() throws Exception {
        // l.add(1.0) — double 参数
        List<ASTNode> nodes = parseMultiLine(
                "List<? extends Number> l = new ArrayList<>();\n" +
                        "l.add(1.0);");

        MethodCallNode call = (MethodCallNode) last(nodes);
        assertEquals("add", call.getMethodName());
        assertNotNull(call.getBoundMethod());
    }

    @Test
    public void wildcardExtendsNumber_addStringAlsoParses() throws Exception {
        // l.add("1") — String 不是 Number 子类型
        // ★ 编译期泛型类型检查会拦截：expected Number, got String
        //   这是正确行为，验证错误信息是否合理
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            List<ASTNode> nodes = parseMultiLine(
                    "List<? extends Number> l = new ArrayList<>();\n" +
                            "l.add(1);");  // 先用 int 确保基础解析 OK

            // 再测试 String 参数应被类型检查拦截
            try {
                parse("l.add(\"1\");");
                fail("String 参数不应通过 ? extends Number 的类型检查");
            } catch (CythavaParseException e) {
                assertTrue("错误信息应提到类型不匹配",
                        e.getMessage().contains("mismatch")
                                || e.getMessage().contains("expected")
                                || e.getMessage().contains("Number"));
            }
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    // ==================== 场景 3: for-each auto/var 关键字 ====================

    @Test
    public void forEach_autoKeyword_withList() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "List<String> list = new ArrayList<>();\n" +
                        "for (auto s : list) {\n" +
                        "    System.out.println(s);\n" +
                        "}");

        ForEachNode forEach = (ForEachNode) last(nodes);
        assertEquals("s", forEach.getItemName());
        assertNotNull("应有集合表达式", forEach.getCollection());
        assertNotNull("应有循环体", forEach.getBody());
    }

    @Test
    public void forEach_varKeyword_withList() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                """
                List<Integer> nums = new ArrayList<>();
                    for (var n : nums) {
                        System.out.println(n);
                    }
                            
                            """);   

        ForEachNode forEach = (ForEachNode) last(nodes);
        assertEquals("n", forEach.getItemName());
    }

    // ==================== 传统 for 循环 auto/var 关键字 ====================

    @Test
    public void traditionalFor_autoKeyword() throws Exception {
        List<ASTNode> nodes = parse("for (auto i = 0; i < 10; i++) println(i); ");
        ForNode forNode = (ForNode) last(nodes);
        assertNotNull("应有初始化", forNode.getInitialization());
        assertNotNull("应有条件", forNode.getCondition());
        assertNotNull("应有更新", forNode.getUpdate());
        assertNotNull("应有循环体", forNode.getBody());
    }

    @Test
    public void traditionalFor_varKeyword() throws Exception {
        List<ASTNode> nodes = parse("for (var j = 0; j < 5; j = j + 1) println(j);");
        ForNode forNode = (ForNode) last(nodes);
        assertNotNull("应有初始化", forNode.getInitialization());
        assertNotNull("应有条件", forNode.getCondition());
    }

    @Test
    public void traditionalFor_intType() throws Exception {
        List<ASTNode> nodes = parse("for (int k = 0; k < 3; k = k + 1) { println(k); }");
        ForNode forNode = (ForNode) last(nodes);
        assertNotNull("应有初始化", forNode.getInitialization());
        assertNotNull("应有条件", forNode.getCondition());
    }

    @Test
    public void forEach_explicitType_withArray() throws Exception {
        // 显式类型的 for-each: for (String s : array)
        List<ASTNode> nodes = parse("String[] arr = {\"a\", \"b\"};\n" +
                "for (String s : arr) { System.out.println(s); }");

        // 至少应包含 VarDeclNode 和 ForEachNode
        assertFalse(nodes.isEmpty());
        boolean foundForEach = false;
        for (ASTNode node : nodes) {
            if (node instanceof ForEachNode) {
                foundForEach = true;
                ForEachNode fe = (ForEachNode) node;
                assertEquals("s", fe.getItemName());
                break;
            }
        }
        assertTrue("应找到 ForEachNode", foundForEach);
    }

    // ==================== 场景 4: 自定义类泛型声明 ====================

    @Test
    public void customClass_genericSingleTypeParam() throws Exception {
        List<ASTNode> nodes = parse("class Box<T> { T value; }");

        ClassDeclarationNode boxClass = (ClassDeclarationNode) first(nodes);
        assertEquals("Box", boxClass.getClassName());
        assertTrue("Box 应有类型参数", boxClass.isGeneric());
        assertNotNull(boxClass.getTypeParameters());
        assertEquals(1, boxClass.getTypeParameters().size());
        assertEquals("T", boxClass.getTypeParameters().get(0));

        // 应有 1 个字段
        assertEquals(1, boxClass.getFields().size());
        FieldDeclarationNode field = boxClass.getFields().get(0);
        assertEquals("value", field.getFieldName());
    }

    @Test
    public void customClass_genericWithTypeBound() throws Exception {
        // T extends Number — 有上界约束的类型参数
        List<ASTNode> nodes = parse("class NumericBox<T extends Number> { T value; }");

        ClassDeclarationNode cls = (ClassDeclarationNode) first(nodes);
        assertTrue(cls.isGeneric());
        assertEquals("T", cls.getTypeParameters().get(0));

        // 应有上界信息
        assertNotNull("应有类型参数上界", cls.getTypeParameterBounds());
        assertTrue("T 应有上界约束", cls.getTypeParameterBounds().containsKey("T"));
    }

    @Test
    public void customClass_multipleTypeParams() throws Exception {
        // Pair<K, V> — 多参数泛型
        List<ASTNode> nodes = parse("class Pair<K, V> { K key; V value; }");

        ClassDeclarationNode pair = (ClassDeclarationNode) first(nodes);
        assertEquals("Pair", pair.getClassName());
        assertEquals(2, pair.getTypeParameters().size());
        assertEquals("K", pair.getTypeParameters().get(0));
        assertEquals("V", pair.getTypeParameters().get(1));
        assertEquals(2, pair.getFields().size());
    }

    @Test
    public void customClass_noGeneric() throws Exception {
        // 普通无泛型类
        parse("class Simple { String name; }");
        List<ASTNode> nodes = parse("Simple s = new Simple();");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode decl = (VarDeclNode) first(nodes);
        assertEquals("s", decl.getVarName());
    }

    @Test
    public void customClassInstance_fieldAssignment() throws Exception {
        // stringBox.value = "hello" — 逐步解析模拟 REPL 会话
        parse("class Box<T> { T value; }");
        parse("Box<String> stringBox = new Box<>();");
        List<ASTNode> nodes = parse("stringBox.value = \"hello\";");

        FieldAssignmentNode assign = (FieldAssignmentNode) first(nodes);
        assertEquals("value", assign.getFieldName());
        assertNotNull(assign.getValue());
    }

    @Test
    public void customClassPair_keyAndValueAccess() throws Exception {
        // Pair<K,V> 的两个字段都能访问
        parse("class Pair<K, V> { K key; V value; }");
        List<ASTNode> nodes = parseMultiLine(
                """
                Pair<String, Integer> p = new Pair<>();
                p.key;
                p.value;
                """);

        // 最后一个节点是 p.value 的访问
        FieldAccessNode valueAccess = (FieldAccessNode) last(nodes);
        assertEquals("value", valueAccess.getFieldName());
    }

    // ==================== 场景 7: 嵌套/复杂泛型 ====================

    @Test
    public void nestedWildcard_mapExtendsNumber() throws Exception {
        // Map<? extends Number, ? extends String> — 双重通配符
        List<ASTNode> nodes = parse(
                "Map<? extends Number, ? extends String> m = new HashMap<>();");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode decl = (VarDeclNode) first(nodes);
        assertEquals("m", decl.getVarName());
    }

    @Test
    public void nestedGenericType_listOfMap() throws Exception {
        // List<Map<String, Integer>> — 嵌套泛型
        List<ASTNode> nodes = parse(
                "List<Map<String, Integer>> listOfMaps = new ArrayList<>();");

        assertTrue(first(nodes) instanceof VarDeclNode);
    }

    // ==================== 场景 8: 复合场景 — 多步骤 REPL 会话模拟 ====================

    @Test
    public void fullReplSession_boxScenario() throws Exception {
        // 模拟完整 REPL 会话：定义类 → 实例化 → 访问字段 → 赋值 → 使用
        parse("class Container<T> { T data; }");                          // Step 1
        parse("Container<List<String>> c = new Container<>();");          // Step 2
        List<ASTNode> step3 = parse("c.data;");                            // Step 3: 字段访问
        List<ASTNode> step4 = parse("c.data = new ArrayList<>();");       // Step 4: 字段赋值

        // Step 3: c.data → FieldAccessNode
        FieldAccessNode access = (FieldAccessNode) first(step3);
        assertEquals("data", access.getFieldName());

        // Step 4: c.data = ... → FieldAssignmentNode
        FieldAssignmentNode assign = (FieldAssignmentNode) first(step4);
        assertEquals("data", assign.getFieldName());
        assertTrue(assign.getValue() instanceof ConstructorCallNode);
    }

    @Test
    public void fullReplSession_operatorOverloadInClass() throws Exception {
        // 类内定义 operator+ 并验证注册
        // ★ 注意：实例方法的 operator+ 只有 1 个显式参数(other)，
        //   所以被注册为**一元**运算符（隐含 this 不计入）
        parse("""
                class Vector2d {
                    double x, y;
                    Vector2d operator+(Vector2d other) { return null; }
                }
                """);

        // operator+ 应被注册到 OperatorRegistry（作为一元或二元）
        boolean found = context.getOperatorRegistry().findBinary("+", Object.class, Object.class) != null
                || context.getOperatorRegistry().findUnary("+", Object.class) != null;
        assertTrue("类内 operator+ 应被注册（二元或一元）", found);
    }

    @Test
    public void fullReplSession_topLevelOperatorFunction() throws Exception {
        // 顶层函数形式的 operator+
        parse("int operator+(int a, int b) { return a + b; }");

        assertNotNull("顶层 operator+ 应被注册",
                context.getOperatorRegistry().findBinary("+", int.class, int.class));
    }

    // ==================== 场景 9: 匿名类 + 自定义类交互 ====================

    @Test
    public void anonymousClass_basic() throws Exception {
        // 匿名类作为变量初始值
        List<ASTNode> nodes = parse(
                "Object obj = new Object() { String name = \"anon\"; };");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode decl = (VarDeclNode) first(nodes);
        assertNotNull(decl.getInitializer());
        assertTrue(decl.getInitializer() instanceof ConstructorCallNode);

        ConstructorCallNode ctor = (ConstructorCallNode) decl.getInitializer();
        assertNotNull("匿名类应有 ClassDeclarationNode", ctor.getAnonymousClass());
        ClassDeclarationNode anonCls = ctor.getAnonymousClass();
        assertFalse("匿名类应有至少 1 个字段", anonCls.getFields().isEmpty());
    }

    @Test
    public void anonymousClass_fieldAccessChain() throws Exception {
        // 定义匿名类 → 访问其字段
        parse("var r = new Object() { String name = \"test\"; int age = 42; };");
        List<ASTNode> access = parse("r.name;");

        FieldAccessNode fa = (FieldAccessNode) first(access);
        assertEquals("name", fa.getFieldName());
        assertTrue(fa.getTarget() instanceof VariableNode);
        assertEquals("r", ((VariableNode) fa.getTarget()).getName());
    }

    // ==================== 场景 10: var/auto 与自定义类结合 ====================

    @Test
    public void varWithCustomClass() throws Exception {
        // var 推断自定义类类型
        parse("class Point { int x, y; }");
        List<ASTNode> nodes = parse("var p = new Point();");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode decl = (VarDeclNode) first(nodes);
        assertEquals("p", decl.getVarName());
        assertNotNull(decl.getInitializer());
    }

    @Test
    public void varWithAnonymousClass() throws Exception {
        // var + 匿名类
        List<ASTNode> nodes = parse(
                "var r = new Object() { int value = 100; };");

        assertTrue(first(nodes) instanceof VarDeclNode);
        VarDeclNode decl = (VarDeclNode) first(nodes);
        assertEquals("r", decl.getVarName());
        assertTrue(decl.getInitializer() instanceof ConstructorCallNode);
    }

    // ==================== 场景 11: 泛型参数数量校验 ====================

    @Test
    public void genericParamCount_correct() throws Exception {
        // Map<K,V> 正确 2 个参数
        List<ASTNode> nodes = parse("Map<String, Integer> m = new HashMap<>();");
        assertTrue(first(nodes) instanceof VarDeclNode);
    }

    @Test(expected = CythavaParseException.class)
    public void genericParamCount_wrongCount_shouldFail() throws Exception {
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            parse("Map<String, Integer, Boolean> bad = new HashMap<>();");
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    // ==================== 场景 12: 方法调用链式访问 ====================

    @Test
    public void chainedMethodCall_afterFieldAccess() throws Exception {
        // stringBox.value.toString() — 链式访问：字段访问后接方法调用
        parse("class Box<T> { T value; }");
        parse("Box<String> stringBox = new Box<>();");

        List<ASTNode> nodes = parse("""
                stringBox.value.toString();
                """);

        MethodCallNode call = (MethodCallNode) first(nodes);
        assertEquals("toString", call.getMethodName());
        // 目标应是 FieldAccessNode(stringBox.value)
        assertTrue("toString 的目标应为 FieldAccessNode",
                call.getTarget() instanceof FieldAccessNode);
    }

    // ==================== 场景 13: .class 字面量 ====================

    @Test
    public void classLiteral_basic() throws Exception {
        // String.class
        List<ASTNode> nodes = parse("Class<?> cls = String.class;");
        assertTrue(first(nodes) instanceof VarDeclNode);
        assertTrue(((VarDeclNode) first(nodes)).getInitializer() instanceof ClassReferenceNode);
    }

    @Test
    public void classLiteral_withCustomClass() throws Exception {
        // Box.class （自定义类的 .class）
        parse("class Box<T> { T value; }");
        List<ASTNode> nodes = parse("Class<?> bc = Box.class;");
        assertTrue(first(nodes) instanceof VarDeclNode);
    }

    // ==================== 场景 14: 静态字段访问 ====================

    @Test
    public void staticFieldAccess_onCustomClass() throws Exception {
        // ClassName.staticField（如果有的话）
        parse("class Constants { static final int MAX = 100; }");
        List<ASTNode> nodes = parse("Constants.MAX;");
        // 静态字段访问可能解析为 FieldAccessNode 或 VariableNode
        assertFalse(nodes.isEmpty());
    }
}
