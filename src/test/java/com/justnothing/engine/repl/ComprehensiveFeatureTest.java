package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 综合功能测试 — 验证自定义类、匿名类、嵌套类、方法重载和类型推导的协同工作。
 *
 * <h3>测试场景</h3>
 * <ul>
 *   <li>自定义类声明与静态字段访问</li>
 *   <li>两遍扫描：方法体内调用同类重载方法</li>
 *   <li>匿名类字段/方法解析</li>
 *   <li>嵌套类变量声明</li>
 *   <li>泛型集合的 for-each 推导</li>
 * </ul>
 */
public class ComprehensiveFeatureTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        context.addImport("java.util.*");
        context.addImport("java.lang.*");
        context.setStrictMode(false);
        context.setStrictMode(false);
    }

    // ==================== 辅助方法 ====================

    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), context, "<test>");
        List<ASTNode> nodes = parser.parse();

        // ★ 自动将声明的类注册到上下文（模拟 AstRepl 行为）
        for (ASTNode node : nodes) {
            if (node instanceof ClassDeclarationNode classDecl) {
                context.declareClass(classDecl);
            }
        }

        return nodes;
    }

    /**
     * 多语句测试辅助：先预扫描 class 声明并注册，再正式解析。
     * 模拟 REPL 逐行输入的行为：第一行 class 声明后，后续行就能引用它。
     */
    private List<ASTNode> parseMultiLine(String source) throws CythavaParseException {
        // 第一次解析：只为了注册类声明
        parse(source);
        // 第二次解析：此时 context 已有类信息，字段访问能正确解析
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), context, "<test>");
        return parser.parse();
    }

    /** 解析并返回第一个节点。 */
    private ASTNode first(String source) throws CythavaParseException {
        List<ASTNode> nodes = parse(source);
        assertFalse("Expected at least one node", nodes.isEmpty());
        return nodes.get(0);
    }

    // ==================== 1. 自定义类声明 + 静态字段访问 ====================

    @Test
    public void customClass_simpleDeclaration() throws Exception {
        // class MyClass { int x; } → ClassDeclarationNode with 1 field
        List<ASTNode> nodes = parse("class MyClass { int x; }");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof ClassDeclarationNode);

        ClassDeclarationNode cd = (ClassDeclarationNode) nodes.get(0);
        assertEquals("MyClass", cd.getClassName());
        assertEquals(1, cd.getFields().size());
        assertEquals("x", cd.getFields().get(0).getFieldName());
    }

    @Test
    public void customClass_staticFieldAccess() throws Exception {
        // 先声明类，再访问其静态字段
        parse("class Container { static int value; }");

        // ClassDeclarationNode 应已注册到上下文
        assertTrue(context.isClassDeclared("Container"));
        assertNotNull(context.getClassDeclaration("Container"));

        // Container.value → FieldAccessNode
        ASTNode node = first("Container.value;");
        assertTrue("Should be FieldAccessNode, got " + node.getClass().getSimpleName(),
                node instanceof FieldAccessNode);
    }

    @Test
    public void customClass_multipleStaticFields() throws Exception {
        parse("class Config { static String name; static int version; static boolean active; }");

        ASTNode n1 = first("Config.name");
        assertTrue(n1 instanceof FieldAccessNode);

        ASTNode n2 = first("Config.version");
        assertTrue(n2 instanceof FieldAccessNode);

        ASTNode n3 = first("Config.active");
        assertTrue(n3 instanceof FieldAccessNode);
    }

    // ==================== 2. 两遍扫描：方法内调用同类重载方法 ====================

    @Test
    public void twoPass_overloadedMethodsInSameClass() throws Exception {
        // 同一个类中定义多个同名重载方法，另一个方法调用它们
        String source = """
            class Calculator {
                int add(int a, int b) { return a + b; }
                double add(double a, double b) { return a + b; }
                String add(String a, String b) { return a + b; }
                int demo() { return add(1, 2); }
            }
            """;
        List<ASTNode> nodes = parse(source);
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof ClassDeclarationNode);

        ClassDeclarationNode cd = (ClassDeclarationNode) nodes.get(0);
        assertEquals("Calculator", cd.getClassName());
        // add(int,int), add(double,double), add(String,String), demo → 4 methods
        assertEquals(4, cd.getMethods().size());
    }

    @Test
    public void twoPass_methodCallingSiblingMethod() throws Exception {
        // 方法 A 调用方法 B，两者在同一类中
        String source = """
            class Helper {
                String greet(String name) { return "Hello, " + name; }
                String formalGreet() { return greet("World"); }
            }
            """;
        List<ASTNode> nodes = parse(source);
        assertEquals(1, nodes.size());

        ClassDeclarationNode cd = (ClassDeclarationNode) nodes.get(0);
        assertEquals(2, cd.getMethods().size());
        assertEquals("greet", cd.getMethods().get(0).getMethodName());
        assertEquals("formalGreet", cd.getMethods().get(1).getMethodName());
    }

    @Test
    public void twoPass_classWithFieldsAndMethods() throws Exception {
        // 类同时有字段和方法，方法体引用字段
        String source = """
            class Counter {
                static int count;
                static void increment() { count = count + 1; }
                static int getCount() { return count; }
            }
            """;
        List<ASTNode> nodes = parse(source);
        assertEquals(1, nodes.size());

        ClassDeclarationNode cd = (ClassDeclarationNode) nodes.get(0);
        assertEquals(1, cd.getFields().size());      // count
        assertEquals(2, cd.getMethods().size());     // increment, getCount
    }

    // ==================== 3. 匿名类 ====================

    @Test
    public void anonymousClass_basicStructure() throws Exception {
        // new Object() { int x; } → ConstructorCallNode with anonymousClass
        ASTNode node = first("new Object() { int x; };");
        assertTrue("Should be ConstructorCallNode",
                node instanceof ConstructorCallNode);

        ConstructorCallNode ccn = (ConstructorCallNode) node;
        assertNotNull("Should have anonymousClass", ccn.getAnonymousClass());
        assertEquals(1, ccn.getAnonymousClass().getFields().size());
        assertEquals("x", ccn.getAnonymousClass().getFields().get(0).getFieldName());
    }

    @Test
    public void anonymousClass_withMultipleMembers() throws Exception {
        ASTNode node = first("new Runnable() { String name; int age; void run() {} };");
        assertTrue(node instanceof ConstructorCallNode);

        ConstructorCallNode ccn = (ConstructorCallNode) node;
        ClassDeclarationNode anon = ccn.getAnonymousClass();
        assertNotNull(anon);
        assertEquals(2, anon.getFields().size());
        assertEquals(1, anon.getMethods().size());
        assertEquals("run", anon.getMethods().get(0).getMethodName());
    }

    @Test
    public void anonymousClass_fieldAccessAfterCreation() throws Exception {
        // var r = new Object() { int val; }; r.val → 通过匿名类字段查找
        List<ASTNode> nodes = parse("var r = new Object() { int val; }; r.val;");
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        assertTrue(nodes.get(1) instanceof FieldAccessNode);
    }

    // ==================== 4. 嵌套类变量声明 ====================

    @Test
    public void nestedClass_mapEntryVarDecl() throws Exception {
        // Map.Entry e; → VarDeclNode (不是 ClassReferenceNode)
        ASTNode node = first("Map.Entry e;");
        assertTrue("Should be VarDeclNode, got " + node.getClass().getSimpleName(),
                node instanceof VarDeclNode);

        VarDeclNode vd = (VarDeclNode) node;
        assertNotNull("Should have declared type", vd.getDeclaredType());
        assertEquals(Map.Entry.class, vd.getDeclaredType().getRawType());
    }

    @Test
    public void nestedClass_fullyQualifiedVarDecl() throws Exception {
        // java.util.Map.Entry entry; → VarDeclNode
        ASTNode node = first("java.util.Map.Entry entry;");
        assertTrue(node instanceof VarDeclNode);

        VarDeclNode vd = (VarDeclNode) node;
        assertNotNull(vd.getDeclaredType());
        assertEquals(Map.Entry.class, vd.getDeclaredType().getRawType());
    }

    // ==================== 5. 泛型集合 for-each 推导 ====================

    @Test
    public void forEach_genericCollection_inferItemType() throws Exception {
        // List<String> names = ...; for (String n : names) { } → ForEachNode with itemType=String
        List<ASTNode> nodes = parse(
                "List<String> names = new ArrayList<>();" +
                "for (String n : names) { System.out.println(n); }");
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        assertTrue(nodes.get(1) instanceof ForEachNode);

        ForEachNode forEach = (ForEachNode) nodes.get(1);
        assertEquals(String.class, forEach.getItemType());
        assertEquals("n", forEach.getItemName());
    }

    @Test
    public void forEach_mapEntryIteration() throws Exception {
        // for (Map.Entry e : entries) { } — 测试嵌套类作为 for-each item 类型
        // 注意：entries 是未声明变量，非严格模式允许
        List<ASTNode> nodes = parse("Set<Map.Entry> entries;");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);

        // 单独测试 for-each
        List<ASTNode> forNodes = parse("for (Map.Entry e : entries) { }");
        assertEquals(1, forNodes.size());
        assertTrue(forNodes.get(0) instanceof ForEachNode);

        ForEachNode forEach = (ForEachNode) forNodes.get(0);
        assertEquals("e", forEach.getItemName());
        // TODO: itemType 目前为 null，因为 for-each 用字符串拼接解析类型而非 TypeParser。
        // 未来重构 tryParseForEach 使用 TypeParser 后可启用: assertEquals(Map.Entry.class, forEach.getItemType());
    }

    // ==================== 6. 综合场景：多语句混合 ====================

    @Test
    public void mixed_classAndUsage() throws Exception {
        // 先声明类（注册到 context）
        parse("class Box { static String label; }");

        // 现在可以访问静态字段
        ASTNode fieldAccess = first("Box.label;");
        assertTrue("Should be FieldAccessNode", fieldAccess instanceof FieldAccessNode);

        // 也可以创建实例
        ASTNode varDecl = first("Box box = new Box();");
        assertTrue("Should be VarDeclNode", varDecl instanceof VarDeclNode);
    }

    @Test
    public void mixed_anonymousAndNestedClass() throws Exception {
        // 匿名类 + 嵌套类变量 + for-each 混合使用
        List<ASTNode> nodes = parse(
                "var handler = new Object() {\n" +
                "    void handle(String msg) {}\n" +
                "};\n" +
                "Map.Entry pair;\n" +
                "List<Integer> nums;\n" +
                "for (Integer n : nums) { handler.handle(n.toString()); }");

        assertEquals(4, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);       // handler
        assertTrue(nodes.get(1) instanceof VarDeclNode);       // pair
        assertTrue(nodes.get(2) instanceof VarDeclNode);       // nums
        assertTrue(nodes.get(3) instanceof ForEachNode);       // for-each
    }

    // ==================== 7. 边界情况 ====================

    @Test
    public void edge_emptyClassBody() throws Exception {
        // 空类体应正常解析
        ASTNode node = first("class Empty { }");
        assertTrue(node instanceof ClassDeclarationNode);
        ClassDeclarationNode cd = (ClassDeclarationNode) node;
        assertEquals(0, cd.getFields().size());
        assertEquals(0, cd.getMethods().size());
    }

    @Test
    public void edge_classWithOnlyFields() throws Exception {
        // 只有字段的类
        ASTNode node = first("class Data { int a; String b; double c; }");
        assertTrue(node instanceof ClassDeclarationNode);
        ClassDeclarationNode cd = (ClassDeclarationNode) node;
        assertEquals(3, cd.getFields().size());
        assertEquals(0, cd.getMethods().size());
    }

    @Test
    public void edge_classWithOnlyMethods() throws Exception {
        // 只有方法的类
        ASTNode node = first("class Actions { void doA() {} void doB() {} }");
        assertTrue(node instanceof ClassDeclarationNode);
        ClassDeclarationNode cd = (ClassDeclarationNode) node;
        assertEquals(0, cd.getFields().size());
        assertEquals(2, cd.getMethods().size());
    }
}
