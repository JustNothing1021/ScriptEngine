package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.JType;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.justnothing.engine.ast.GenericType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 自定义类方法解析与类型推断专项测试（严苛版）。
 * <p>
 * 基于 {@link TypeInferenceTestFixtures} 测试辅助类，验证：
 * <ul>
 * <li>自定义类的字段访问类型推断（反射）</li>
 * <li>自定义类的方法调用返回类型推断</li>
 * <li>方法重载选择：int vs double vs String vs Object</li>
 * <li>链式调用类型传播</li>
 * <li>继承链上的字段/方法解析（子类→父类）</li>
 * <li>泛型方法的返回类型（List/Map）</li>
 * <li>数组字段元素类型提取</li>
 * </ul>
 */
public class TypeInferenceTestCustom {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        // 通配符导入：使 resolveClass() 通过 tryNestedVariants(.→$) 找到嵌套类
        context.addImport("com.justnothing.engine.repl.TypeInferenceTestFixtures");
        context.addImport("com.justnothing.engine.repl.*");
        // Java 常用包导入（使 Map/List/ArrayList/HashMap 等无需全限定名）
        context.addImport("java.util.*");
        context.addImport("java.lang.*");
        // 注册短名到符号表（标识符消歧需要）
        context.declareClass("TypeInferenceTestFixtures");
        context.declareClass("ParentFixture");
        context.declareClass("ChildFixture");
        // 测试场景关闭严格标识符解析：允许未声明的变量被解析为 VariableNode
        // （测试关注 AST 结构正确性，而非运行时变量是否存在）
        context.setStrictMode(false);
        // 关闭严格类型解析：未知类型回退为 Object.class 而非报错
        context.setStrictMode(false);
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

    private void assertType(ASTNode node, Class<?> expected) {
        JType actual = context.getType(node);
        assertNotNull("Node " + node.getClass().getSimpleName() + " should have type info", actual);
        assertEquals("Type mismatch for " + node.getClass().getSimpleName(),
                expected, actual.getRawType());
    }

    private void assertHasType(ASTNode node) {
        assertNotNull("Node " + node.getClass().getSimpleName() + " should have type info",
                context.getType(node));
    }

    // ==================== 1. 自定义类字段访问 ====================

    @Test
    public void customField_intField_isInt() throws Exception {
        // TypeInferenceTestFixtures 的 intField 字段 → int
        ASTNode node = parseSingle("TypeInferenceTestFixtures.intField");
        assertTrue("Should be FieldAccessNode", node instanceof FieldAccessNode);
        assertType(node, int.class);
    }

    @Test
    public void customField_stringField_isString() throws Exception {
        ASTNode node = parseSingle("TypeInferenceTestFixtures.stringField");
        assertType(node, String.class);
    }

    @Test
    public void customField_doubleField_isDouble() throws Exception {
        ASTNode node = parseSingle("TypeInferenceTestFixtures.doubleField");
        assertType(node, double.class);
    }

    @Test
    public void customField_objectField_isObject() throws Exception {
        ASTNode node = parseSingle("TypeInferenceTestFixtures.objectField");
        assertType(node, Object.class);
    }

    // ==================== 2. 自定义类方法调用 ====================

    @Test
    public void customMethod_getInt_returnsInt() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.getInt()");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        assertType(call, int.class);
    }

    @Test
    public void customMethod_getLong_returnsLong() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.getLong()");
        assertType(call, long.class);
    }

    @Test
    public void customMethod_getDouble_returnsDouble() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.getDouble()");
        assertType(call, double.class);
    }

    @Test
    public void customMethod_getString_returnsString() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.getString()");
        assertType(call, String.class);
    }

    @Test
    public void customMethod_getBoolean_returnsBoolean() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.getBoolean()");
        assertType(call, boolean.class);
    }

    @Test
    public void customMethod_doNothing_returnsVoid() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.doNothing()");
        // void 方法调用至少应有类型信息
        assertHasType(call);
    }

    // ==================== 3. 方法重载选择 ====================

    @Test
    public void overload_intArg_selectsIntOverload() throws Exception {
        // overloaded(int) 应被选中（参数是 int 字面量）
        ASTNode call = parseSingle("TypeInferenceTestFixtures.overloaded(42)");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull("Should have bound method", mc.getBoundMethod());
        assertEquals("Should select overloaded(int)",
                int.class, mc.getBoundMethod().getParameterTypes()[0]);
        // 返回值类型应为 String
        assertType(call, String.class);
    }

    @Test
    public void overload_doubleArg_selectsDoubleOverload() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.overloaded(3.14)");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should select overloaded(double)",
                double.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_stringArg_selectsStringOverload() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.overloaded(\"hello\")");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        assertEquals("Should select overloaded(String)",
                String.class, mc.getBoundMethod().getParameterTypes()[0]);
    }

    @Test
    public void overload_twoArgs_selectsTwoParamOverload() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.overloaded(1, 2)");
        MethodCallNode mc = (MethodCallNode) call;
        assertNotNull(mc.getBoundMethod());
        Class<?>[] params = mc.getBoundMethod().getParameterTypes();
        assertEquals("Should have 2 parameters", 2, params.length);
        assertEquals("First param should be int", int.class, params[0]);
        assertEquals("Second param should be int", int.class, params[1]);
    }

    // ==================== 4. 链式调用类型传播 ====================

    @Test
    public void chainCall_returnsSelfType() throws Exception {
        // chain() 返回 TypeInferenceTestFixtures 自身
        ASTNode call = parseSingle("TypeInferenceTestFixtures.chain()");
        assertType(call, TypeInferenceTestFixtures.class);
    }

    @Test
    public void chainedFieldAccess_afterMethod() throws Exception {
        // chain().intField → 先调 chain() 得到 Fixtures 实例，再访问 intField
        ASTNode access = parseSingle("TypeInferenceTestFixtures.chain().intField");
        assertTrue("Should be FieldAccessNode", access instanceof FieldAccessNode);
        assertType(access, int.class);
    }

    @Test
    public void chainedMethodCall_afterMethod() throws Exception {
        // chain().getInt() → 链式调用
        ASTNode call = parseSingle("TypeInferenceTestFixtures.chain().getInt()");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        assertType(call, int.class);
    }

    // ==================== 5. 数组字段元素类型 ====================

    @Test
    public void arrayField_indexAccess_inferElementType() throws Exception {
        // intArrayField[0] → 元素类型 int
        ASTNode access = parseSingle("TypeInferenceTestFixtures.intArrayField[0]");
        assertTrue("Should be ArrayAccessNode", access instanceof ArrayAccessNode);
        assertType(access, int.class);
    }

    // ==================== 6. 泛型方法返回类型 ====================

    @Test
    public void genericMethod_stringList_returnsList() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.stringList()");
        // stringList() 返回 List<String> → 原始类型为 List
        assertType(call, java.util.List.class);
    }

    @Test
    public void genericMethod_stringIntMap_returnsMap() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.stringIntMap()");
        assertType(call, java.util.Map.class);
    }

    // ==================== 7. 变量持有自定义类型后的成员访问 ====================

    @Test
    public void variableOfCustomType_fieldAccess_propagatesType() throws Exception {
        // 声明变量后通过变量访问字段
        parse("TypeInferenceTestFixtures f;");
        ASTNode field = parseSingle("f.intField");
        // f 是 TypeInferenceTestFixtures 类型，f.intField 应为 int
        assertTrue("Should be FieldAccessNode", field instanceof FieldAccessNode);
        assertType(field, int.class);
    }

    @Test
    public void variableOfCustomType_methodCall_propagatesReturnType() throws Exception {
        parse("TypeInferenceTestFixtures f;");
        ASTNode call = parseSingle("f.getInt()");
        assertTrue("Should be MethodCallNode", call instanceof MethodCallNode);
        assertType(call, int.class);
    }

    // ==================== 8. 继承链字段/方法解析 ====================

    @Test
    public void childField_ownField_resolvesCorrectly() throws Exception {
        // TypeInferenceTestFixtures.ChildFixture.childInt → 子类自身字段（点号语法，ClassResolver 转
        // $）
        ASTNode field = parseSingle("TypeInferenceTestFixtures.ChildFixture.childInt");
        assertType(field, int.class);
    }

    @Test
    public void childField_parentField_inherited() throws Exception {
        // TypeInferenceTestFixtures.ChildFixture.parentInt → 从父类继承的字段
        ASTNode field = parseSingle("TypeInferenceTestFixtures.ChildFixture.parentInt");
        assertType(field, int.class);
    }

    @Test
    public void childMethod_ownMethod_resolvesCorrectly() throws Exception {
        ASTNode call = parseSingle("TypeInferenceTestFixtures.ChildFixture.childMethod()");
        assertType(call, String.class);
    }

    @Test
    public void childMethod_parentMethod_inherited() throws Exception {
        // TypeInferenceTestFixtures.ChildFixture.parentMethod() → 从父类继承的方法
        ASTNode call = parseSingle("TypeInferenceTestFixtures.ChildFixture.parentMethod()");
        assertType(call, String.class);
    }

    @Test
    public void childMethod_overridden_resolvesToOverride() throws Exception {
        // TypeInferenceTestFixtures.ChildFixture.overridden() → 子类覆盖版本
        ASTNode call = parseSingle("TypeInferenceTestFixtures.ChildFixture.overridden()");
        assertType(call, String.class);
    }

    // ==================== 9. 匿名类变量声明（bug regression test） ====================

    @Test
    public void anonymousClassDeclaration_variableIsRegistered() throws Exception {
        // Object obj = new Object() { int x; }; → obj 必须注册到符号表
        // （之前 bug：匿名类体解析失败导致整条语句崩溃，obj 未注册）
        parse("Object obj = new Object() { int x; };");
        // 后续引用 obj 不应报 "Cannot find symbol"
        ASTNode ref = parseSingle("obj");
        assertNotNull("obj should be parsed as a valid node", ref);
        // obj 声明为 Object 类型
        assertType(ref, Object.class);
    }

    @Test
    public void anonymousClassDeclaration_canAccessField() throws Exception {
        // 验证匿名类声明后变量可用且类型正确
        parse("Object obj = new Object() { int x; };");
        // obj 不应找不到符号
        assertTrue("obj should be known variable", context.isKnownVariable("obj"));
    }

    @Test
    public void anonymousClassFieldAccess_resolvesFromAnonBody() throws Exception {
        // Object obj = new Object() { int x; }; obj.x → x 应从匿名类体中解析为 int
        parse("Object obj = new Object() { int x; };");
        ASTNode access = parseSingle("obj.x");
        assertTrue("Should be FieldAccessNode", access instanceof FieldAccessNode);
        assertType(access, int.class);
    }

    @Test
    public void newObjectWithoutParens_throwsSyntaxError() throws Exception {
        try {
            parseSingle("Object obj = new Object { int x; };");
            fail("Should have thrown CythavaParseException for missing () before {");
        } catch (CythavaParseException e) {
            assertTrue("Error should mention parentheses or brace",
                    e.getMessage().contains("(") || e.getMessage().contains("{"));
        }
    }

    @Test
    public void anonymousClassBody_missingMethodName_throwsSyntaxError() throws Exception {
        // void (String y) {} — 方法声明缺少方法名，应报语法错误而非卡死
        try {
            parseSingle("Object obj = new Object() { int x; void m(int a) {} void (String y) {} };");
            fail("Should have thrown CythavaParseException for missing method name");
        } catch (CythavaParseException e) {
            assertTrue("Error should mention member declaration",
                    e.getMessage().contains("member declaration"));
        }
    }

    // ==================== 10. ForEachNode 解析 ====================

    @Test
    public void forEach_withoutType() throws Exception {
        // for (x : list) { ... } — for 是语句，用 parse() 而非 parseSingle()
        List<ASTNode> nodes = parse("for (x : items) { x; }");
        assertEquals("Should have 1 statement", 1, nodes.size());
        assertTrue("Should be ForEachNode", nodes.get(0) instanceof ForEachNode);
        ForEachNode forEach = (ForEachNode) nodes.get(0);
        assertEquals("itemName should be x", "x", forEach.getItemName());
        assertNull("itemType should be null when not specified", forEach.getItemType());
        assertNotNull("collection should not be null", forEach.getCollection());
        assertNotNull("body should not be null", forEach.getBody());
    }

    @Test
    public void forEach_withType() throws Exception {
        // for (int x : list) { ... }
        List<ASTNode> nodes = parse("for (int x : items) { x; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be ForEachNode", nodes.get(0) instanceof ForEachNode);
        ForEachNode forEach = (ForEachNode) nodes.get(0);
        assertEquals("itemName should be x", "x", forEach.getItemName());
        assertEquals("itemType should be int", int.class, forEach.getItemType());
    }

    @Test
    public void forEach_withStringType() throws Exception {
        // for (String s : list) { ... }
        List<ASTNode> nodes = parse("for (String s : names) { s; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be ForEachNode", nodes.get(0) instanceof ForEachNode);
        ForEachNode forEach = (ForEachNode) nodes.get(0);
        assertEquals("s", forEach.getItemName());
        assertEquals(String.class, forEach.getItemType());
    }

    @Test
    public void traditionalFor_notConfusedWithForEach() throws Exception {
        // for(int i=0; i<10; i++) { } — 传统 for 不应被误判为 for-each
        List<ASTNode> nodes = parse("for (int i = 0; i < 10; i++) { i; }");
        assertEquals(1, nodes.size());
        assertTrue("Traditional for should be ForNode", nodes.get(0) instanceof ForNode);
        ForNode forNode = (ForNode) nodes.get(0);
    }

    // ==================== 11. FunctionDefNode 解析 ====================

    @Test
    public void functionDef_basic() throws Exception {
        // function greet() { "hello"; }
        List<ASTNode> nodes = parse("function greet() { \"hello\"; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be FunctionDefNode", nodes.get(0) instanceof FunctionDefNode);
        FunctionDefNode func = (FunctionDefNode) nodes.get(0);
        assertEquals("greet", func.getFunctionName());
        assertNull("No explicit returnType", func.getReturnType());
        assertTrue("Parameters should be empty", func.getParameters().isEmpty());
        assertNotNull("body should not be null", func.getBody());
    }

    @Test
    public void functionDef_withReturnType() throws Exception {
        // function int add(int a, int b) { return a + b; }
        List<ASTNode> nodes = parse("function int add(int a, int b) { return a + b; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be FunctionDefNode", nodes.get(0) instanceof FunctionDefNode);
        FunctionDefNode func = (FunctionDefNode) nodes.get(0);
        assertEquals("add", func.getFunctionName());
        assertNotNull("returnType should not be null", func.getReturnType());
        assertEquals("Return type should be int", "int", func.getReturnType().getTypeName());
        assertEquals("Should have 2 parameters", 2, func.getParameters().size());
        assertEquals("First param name", "a", func.getParameters().get(0).name());
        assertEquals("Second param name", "b", func.getParameters().get(1).name());
    }

    @Test
    public void functionDef_withParams() throws Exception {
        // function process(x, y) { x + y; }
        List<ASTNode> nodes = parse("function process(x, y) { x + y; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be FunctionDefNode", nodes.get(0) instanceof FunctionDefNode);
        FunctionDefNode func = (FunctionDefNode) nodes.get(0);
        assertEquals("process", func.getFunctionName());
        assertEquals(2, func.getParameters().size());
        assertEquals("x", func.getParameters().get(0).name());
        assertEquals("y", func.getParameters().get(1).name());
    }

    @Test
    public void functionDef_singleStatementBody() throws Exception {
        // function foo() return 42; — 单语句体不需要 {}
        List<ASTNode> nodes = parse("function foo() return 42;");
        assertEquals(1, nodes.size());
        assertTrue("Should be FunctionDefNode", nodes.get(0) instanceof FunctionDefNode);
        FunctionDefNode func = (FunctionDefNode) nodes.get(0);
        assertEquals("foo", func.getFunctionName());
        assertNotNull(func.getBody());
    }

    @Test
    public void functionDef_noParams() throws Exception {
        // function main() { ... }
        List<ASTNode> nodes = parse("function main() { int x = 1; }");
        assertEquals(1, nodes.size());
        assertTrue("Should be FunctionDefNode", nodes.get(0) instanceof FunctionDefNode);
        FunctionDefNode func = (FunctionDefNode) nodes.get(0);
        assertEquals("main", func.getFunctionName());
        assertTrue("No params expected", func.getParameters().isEmpty());
    }

    // ==================== 12. 匿名类方法体完整解析 ====================

    @Test
    public void anonymousClass_withMethodReturningField() throws Exception {
        // Object obj = new Object() { int x; int getX() { return x; } };
        // 解析成功且方法体被正确解析（不是空 BlockNode）
        List<ASTNode> nodes = parse("Object obj = new Object() { int x; int getX() { return x; } };");
        assertEquals("Should have 1 statement", 1, nodes.size());
        ASTNode node = nodes.get(0);
        assertTrue("Should be VarDeclNode but was: " + node.getClass().getSimpleName(), node instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) node;
        // 值应该是 ConstructorCallNode，其匿名类体包含字段和方法
        assertTrue("Value should be ConstructorCallNode", varDecl.getInitializer() instanceof ConstructorCallNode);
        ConstructorCallNode newObj = (ConstructorCallNode) varDecl.getInitializer();
        assertNotNull("Anonymous class should exist", newObj.getAnonymousClass());
        ClassDeclarationNode anonClass = newObj.getAnonymousClass();
        // 应该有 1 个字段和 1 个方法
        assertEquals("Should have 1 field", 1, anonClass.getFields().size());
        assertEquals("Should have 1 method", 1, anonClass.getMethods().size());
        // 字段 x
        assertEquals("x", anonClass.getFields().get(0).getFieldName());
        // 方法 getX — 方法体不应为 null
        MethodDeclarationNode method = anonClass.getMethods().get(0);
        assertEquals("getX", method.getMethodName());
        assertNotNull("Method body should not be null", method.getBody());
    }

    @Test
    public void anonymousClass_withMultipleMethods() throws Exception {
        List<ASTNode> nodes = parse("Object obj = new Object() { int x; void m1(int x) {} void m1(String x) {} };");
        assertEquals(1, nodes.size());
        assertTrue("Should be VarDeclNode", nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        ConstructorCallNode newObj = (ConstructorCallNode) varDecl.getInitializer();
        ClassDeclarationNode anonClass = newObj.getAnonymousClass();
        // 1 个字段 + 2 个方法
        assertEquals("Should have 1 field", 1, anonClass.getFields().size());
        assertEquals("Should have 2 methods", 2, anonClass.getMethods().size());

        MethodDeclarationNode m1_int = anonClass.getMethods().get(0);
        MethodDeclarationNode m1_str = anonClass.getMethods().get(1);
        assertEquals("m1", m1_int.getMethodName());
        assertEquals("m1", m1_str.getMethodName());
        assertEquals(1, m1_int.getParameters().size());
        assertEquals(1, m1_str.getParameters().size());
        assertEquals(int.class, m1_int.getParameters().get(0).getType().getResolvedClass());
        assertEquals(String.class, m1_str.getParameters().get(0).getType().getResolvedClass());
    }

    @Test
    public void anonymousClass_withPublicModifier() throws Exception {
        List<ASTNode> nodes = parse("Object obj = new Object() { public int f1; };");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        ConstructorCallNode newObj = (ConstructorCallNode) varDecl.getInitializer();
        ClassDeclarationNode anonClass = newObj.getAnonymousClass();
        assertEquals("Should have 1 field", 1, anonClass.getFields().size());
        FieldDeclarationNode field = anonClass.getFields().get(0);
        assertEquals("f1", field.getFieldName());
    }

    @Test
    public void anonymousClass_withPublicMethod() throws Exception {
        List<ASTNode> nodes = parse("Object obj = new Object() { public int m1() { return 1; } };");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        ConstructorCallNode newObj = (ConstructorCallNode) varDecl.getInitializer();
        ClassDeclarationNode anonClass = newObj.getAnonymousClass();
        assertEquals("Should have 0 fields", 0, anonClass.getFields().size());
        assertEquals("Should have 1 method", 1, anonClass.getMethods().size());
        MethodDeclarationNode method = anonClass.getMethods().get(0);
        assertEquals("m1", method.getMethodName());
        assertNotNull(method.getBody());
    }

    @Test
    public void anonymousClass_methodWithReturnLiteral() throws Exception {
        // int m1() { return null; } — 返回 null 的 int 方法应报返回值不匹配错误
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            try {
                parse("Object obj = new Object() { int m1() { return null; } };");
                fail("Should throw: int method returning null");
            } catch (CythavaParseException e) {
                assertTrue("Error should mention return type mismatch",
                        e.getMessage().contains("declares return type")
                                || e.getMessage().contains("returns 'null'"));
            }
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void anonymousClass_methodWithStringReturnToInt() throws Exception {
        // int m2() { return "1"; } — String 不能赋给 int
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            try {
                parse("Object obj = new Object() { int m2() { return \"1\"; } };");
                fail("Should throw: int method returning String");
            } catch (CythavaParseException e) {
                assertTrue("Error should mention type mismatch",
                        e.getMessage().contains("declares return type")
                                || e.getMessage().contains("returns 'String'"));
            }
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void anonymousClass_voidMethodWithValueReturn_throws() throws Exception {
        // void m() { return 1; } — void 方法不能返回值
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            try {
                parse("Object obj = new Object() { void m() { return 1; } };");
                fail("Should throw: void method returning value");
            } catch (CythavaParseException e) {
                assertTrue("Error should mention void",
                        e.getMessage().contains("void")
                        && e.getMessage().contains("returns a value"));
            }
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void anonymousClass_numericWideningReturn_ok() throws Exception {
        // int m() { return (byte)1; } — byte → int 数值提升应该通过
        List<ASTNode> nodes = parse("Object obj = new Object() { int m() { return (byte)1; } };");
        assertEquals(1, nodes.size());
        assertTrue("Should parse successfully", nodes.get(0) instanceof VarDeclNode);
    }

    @Test
    public void anonymousClass_complexMixedMembers() throws Exception {
        // 混合字段、public/private 方法、多种返回类型的复杂匿名类
        String source = "Object obj = new Object() { "
                + "private int count;"
                + "public int getCount() { return count; }"
                + "void setCount(int c) { count = c; }"
                + "String info() { return \"count=\" + count; }"
                + "};";
        List<ASTNode> nodes = parse(source);
        assertEquals(1, nodes.size());
        assertTrue("Should be VarDeclNode", nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        ConstructorCallNode newObj = (ConstructorCallNode) varDecl.getInitializer();
        ClassDeclarationNode anonClass = newObj.getAnonymousClass();
        // 1 个字段 + 3 个方法
        assertEquals("Should have 1 field", 1, anonClass.getFields().size());
        assertEquals("Should have 3 methods", 3, anonClass.getMethods().size());

        // 验证字段
        assertEquals("count", anonClass.getFields().get(0).getFieldName());

        // 验证方法名和顺序
        assertEquals("getCount", anonClass.getMethods().get(0).getMethodName());
        assertEquals("setCount", anonClass.getMethods().get(1).getMethodName());
        assertEquals("info", anonClass.getMethods().get(2).getMethodName());
    }

    // ==================== 13. 泛型类型与钻石操作符 ====================

    @Test
    public void genericType_mapDeclaration() throws Exception {
        // Map<String, String> s = new HashMap<>() — 钻石操作符应能正确解析
        List<ASTNode> nodes = parse("Map<String, String> s = new HashMap<>();");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        assertEquals("s", varDecl.getVarName());
        assertNotNull(varDecl.getDeclaredType());
        assertEquals(Map.class, varDecl.getDeclaredType().getRawType());
        // 初始化器应为 ConstructorCallNode
        assertNotNull(varDecl.getInitializer());
        assertTrue(varDecl.getInitializer() instanceof ConstructorCallNode);
    }

    @Test
    public void genericType_listDeclaration() throws Exception {
        // List<Integer> list = new ArrayList<>()
        List<ASTNode> nodes = parse("List<Integer> list = new ArrayList<>();");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        assertEquals("list", varDecl.getVarName());
        assertNotNull(varDecl.getDeclaredType());
        assertEquals(List.class, varDecl.getDeclaredType().getRawType());
        assertTrue(varDecl.getInitializer() instanceof ConstructorCallNode);
    }

    @Test
    public void genericType_display_noNullText() throws Exception {
        // 验证 ConstructorCallNode 的 formatString 不含 "null" 文字
        // (修复前: operator token 的 text 为 null, StringBuilder.append(null) → "null")

        // 钻石操作符
        List<ASTNode> nodes = parse("new HashMap<>();");
        ConstructorCallNode ctor = (ConstructorCallNode) nodes.get(0);
        String formatted = ctor.formatString(1);
        assertFalse("diamond operator formatString should not contain 'null': " + formatted,
                formatted.contains("null"));
        assertTrue("diamond should show plain class name", formatted.contains("HashMap"));
        assertFalse("diamond has no type args", formatted.contains("<"));

        // 带显式泛型参数
        List<ASTNode> nodes2 = parse("new ArrayList<String>();");
        ConstructorCallNode ctor2 = (ConstructorCallNode) nodes2.get(0);
        String formatted2 = ctor2.formatString(1);
        assertFalse("generic constructor formatString should not contain 'null': " + formatted2,
                formatted2.contains("null"));
        assertTrue("should show ArrayList<String>", formatted2.contains("ArrayList<String>"));

        // 多个泛型参数
        List<ASTNode> nodes3 = parse("new HashMap<String, Integer>();");
        ConstructorCallNode ctor3 = (ConstructorCallNode) nodes3.get(0);
        String formatted3 = ctor3.formatString(1);
        assertFalse("multi-arg generic formatString should not contain 'null': " + formatted3,
                formatted3.contains("null"));
    }

    @Test
    public void genericType_argumentsParsed() throws Exception {
        // 验证泛型参数被正确解析为结构化 GenericType 对象（不是空列表）
        List<ASTNode> nodes = parse("new HashMap<String, String>();");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof ConstructorCallNode);

        ConstructorCallNode ctor = (ConstructorCallNode) nodes.get(0);
        GenericType type = ctor.getType();

        // rawType 应该是 HashMap（通过 java.util.* 导入解析）
        assertEquals(HashMap.class, type.getRawType());

        // typeArguments 应该有 2 个元素（不是空的！）
        assertTrue("typeArguments should be populated", type.isGeneric());
        assertEquals(2, type.getTypeArguments().size());

        // 第一个参数: String
        assertEquals(String.class, type.getTypeArguments().get(0).getRawType());
        // 第二个参数: String
        assertEquals(String.class, type.getTypeArguments().get(1).getRawType());
    }

    @Test
    public void genericType_nestedGenerics() throws Exception {
        // 嵌套泛型: Map<String, List<Integer>>
        // 注意：>> 在 Lexer 中被识别为右移操作符，暂时用空格分隔避免
        // （后续可像 TypeParser 一样实现 pendingAngleBrackets 兼容）
        List<ASTNode> nodes = parse("new java.util.HashMap<String, java.util.ArrayList<Integer> >();");
        ConstructorCallNode ctor = (ConstructorCallNode) nodes.get(0);
        GenericType type = ctor.getType();

        assertEquals(2, type.getTypeArguments().size());
        // 第一个: String
        assertEquals(String.class, type.getTypeArguments().get(0).getRawType());
        // 第二个: ArrayList<Integer> — 嵌套泛型
        GenericType nested = type.getTypeArguments().get(1);
        assertNotNull("nested type argument should exist", nested.getRawType());
        assertTrue("nested should have type arguments", nested.isGeneric());
    }

    @Test(expected = CythavaParseException.class)
    public void genericType_invalidTypeName_throws() throws Exception {
        // 未定义的类型名 'a' 在泛型参数中应报错（严格模式下）
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            parse("new HashMap<a, a>();");
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void genericType_diamondOperator_emptyArgs() throws Exception {
        // 钻石操作符 <> → typeArguments 为空列表（待推断）
        List<ASTNode> nodes = parse("new HashMap<>();");
        ConstructorCallNode ctor = (ConstructorCallNode) nodes.get(0);
        GenericType type = ctor.getType();

        assertFalse("diamond operator should have no type arguments", type.isGeneric());
        assertEquals(0, type.getTypeArguments().size());
        assertEquals(HashMap.class, type.getRawType());
    }

    // ==================== 13. 泛型方法调用校验 ====================

    @Test
    public void genericMethodCall_putStringString_ok() throws Exception {
        // Map<String, String>.put("key", "value") → 参数匹配 ✓
        List<ASTNode> nodes = parse(
                "Map<String, String> m = new HashMap<>();" +
                        "m.put(\"hello\", \"world\");");
        assertEquals(2, nodes.size());

        // 第二条语句是方法调用
        assertTrue(nodes.get(1) instanceof MethodCallNode);
        MethodCallNode call = (MethodCallNode) nodes.get(1);
        assertEquals("put", call.getMethodName());
        assertNotNull("method should be bound", call.getBoundMethod());
        // put(K,V) 替换后 → put(String,String)，参数都是 String 字面量，应通过校验
    }

    @Test(expected = CythavaParseException.class)
    public void genericMethodCall_putIntString_mismatch() throws Exception {
        // Map<String, String>.put(123, "x") → 第一个参数 int 不匹配 String ✗
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            parse(
                    "Map<String, String> m = new HashMap<>();" +
                            "m.put(123, \"x\");");
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void genericMethodCall_listAdd() throws Exception {
        // List<Integer>.add(42) → int 可装箱为 Integer ✓
        List<ASTNode> nodes = parse(
                "List<Integer> list = new ArrayList<>();" +
                        "list.add(42);");
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(1) instanceof MethodCallNode);
        MethodCallNode call = (MethodCallNode) nodes.get(1);
        assertEquals("add", call.getMethodName());
        assertNotNull(call.getBoundMethod());
    }

    @Test(expected = CythavaParseException.class)
    public void genericMethodCall_listAddWrongType() throws Exception {
        // List<String>.add(42) → int 不能赋值给 String ✗
        boolean prevStrict = context.isStrictMode();
        context.setStrictMode(true);
        try {
            parse(
                    "List<String> list = new ArrayList<>();" +
                            "list.add(42);");
        } finally {
            context.setStrictMode(prevStrict);
        }
    }

    @Test
    public void genericMethodCall_mapGet() throws Exception {
        // Map<String, Integer>.get("key") → 返回 Integer（或 null）
        List<ASTNode> nodes = parse(
                "Map<String, Integer> m = new HashMap<>();" +
                        "m.get(\"test\");");
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(1) instanceof MethodCallNode);
        MethodCallNode call = (MethodCallNode) nodes.get(1);
        assertEquals("get", call.getMethodName());
        assertNotNull(call.getBoundMethod());
    }

    // ==================== 14. 匿名类：重载方法互调 + 复杂类型推导 ====================

    @Test
    public void anonymousClass_overloadedMethodsCallingEachOther() throws Exception {
        // 匿名类中有两个重载的 process 方法：
        // process(int x) { return process(x, "default"); } — 调用重载版本
        // process(int x, String label) { return x + ":" + label; }
        List<ASTNode> nodes = parse(
                "Object calc = new Object() {" +
                        "  String process(int x) { return process(x, \"default\"); }" +
                        "  String process(int x, String label) { return x + \":\" + label; }" +
                        "};" +
                        "calc;");
        assertEquals(2, nodes.size());

        VarDeclNode varDecl = (VarDeclNode) nodes.get(0);
        assertTrue(varDecl.getInitializer() instanceof ConstructorCallNode);
        ConstructorCallNode ctor = (ConstructorCallNode) varDecl.getInitializer();
        ClassDeclarationNode anonClass = ctor.getAnonymousClass();

        // 验证有两个重载方法
        List<com.justnothing.engine.ast.nodes.MethodDeclarationNode> methods = anonClass.getMethods();
        assertEquals("should have 2 overloaded methods", 2, methods.size());

        // 验证方法名和返回类型
        boolean foundSingleParam = false, foundDoubleParam = false;
        for (com.justnothing.engine.ast.nodes.MethodDeclarationNode method : methods) {
            if (method.getMethodName().equals("process")) {
                if (method.getParameters().size() == 1)
                    foundSingleParam = true;
                if (method.getParameters().size() == 2)
                    foundDoubleParam = true;
            }
        }
        assertTrue("should have process(int)", foundSingleParam);
        assertTrue("should have process(int, String)", foundDoubleParam);
    }

    @Test
    public void anonymousClass_fieldAccessInMethod() throws Exception {
        // 方法体内访问匿名类字段
        List<ASTNode> nodes = parse(
                "Object obj = new Object() {" +
                        "  int baseValue = 10;" +
                        "  int getAdjusted(int delta) { return baseValue + delta; }" +
                        "};" +
                        "obj;");
        assertEquals(2, nodes.size());

        ConstructorCallNode ctor = (ConstructorCallNode) ((VarDeclNode) nodes.get(0)).getInitializer();
        ClassDeclarationNode anonClass = ctor.getAnonymousClass();

        // 有 1 个字段 + 1 个方法
        assertEquals(1, anonClass.getFields().size());
        assertEquals(1, anonClass.getMethods().size());

        com.justnothing.engine.ast.nodes.MethodDeclarationNode method = anonClass.getMethods().get(0);
        assertEquals("getAdjusted", method.getMethodName());
        assertEquals("int", method.getReturnType().getTypeName());
    }

    @Test
    public void anonymousClass_publicFieldAndMethodMixed() throws Exception {
        // 混合 public/private 字段和方法
        List<ASTNode> nodes = parse(
                "Object container = new Object() {" +
                        "  public String name = \"test\";" +
                        "  private int count = 0;" +
                        "  public String getName() { return name; }" +
                        "  public int increment() { count = count + 1; return count; }" +
                        "};" +
                        "container;");
        assertEquals(2, nodes.size());

        ConstructorCallNode ctor = (ConstructorCallNode) ((VarDeclNode) nodes.get(0)).getInitializer();
        ClassDeclarationNode anonClass = ctor.getAnonymousClass();

        assertEquals("should have 2 fields", 2, anonClass.getFields().size());
        assertEquals("should have 2 methods", 2, anonClass.getMethods().size());
    }

    @Test
    public void anonymousClass_methodWithComplexBody() throws Exception {
        // 方法体包含 if/else、局部变量声明等复杂逻辑
        List<ASTNode> nodes = parse(
                "Object util = new Object() {" +
                        "  int max(int a, int b) {" +
                        "    if (a > b) { return a; } else { return b; }" +
                        "  }" +
                        "  int abs(int x) {" +
                        "    if (x < 0) { return -x; }" +
                        "    return x;" +
                        "  }" +
                        "};" +
                        "util;");
        assertEquals(2, nodes.size());

        ConstructorCallNode ctor = (ConstructorCallNode) ((VarDeclNode) nodes.get(0)).getInitializer();
        ClassDeclarationNode anonClass = ctor.getAnonymousClass();
        List<com.justnothing.engine.ast.nodes.MethodDeclarationNode> methods = anonClass.getMethods();

        assertEquals(2, methods.size());

        // max 方法应有 if-else 体
        com.justnothing.engine.ast.nodes.MethodDeclarationNode maxMethod = methods.stream()
                .filter(m -> m.getMethodName().equals("max")).findFirst().orElseThrow();
        assertTrue(maxMethod.getBody() != null);

        // abs 方法也应有 if 体
        com.justnothing.engine.ast.nodes.MethodDeclarationNode absMethod = methods.stream()
                .filter(m -> m.getMethodName().equals("abs")).findFirst().orElseThrow();
        assertTrue(absMethod.getBody() != null);
    }

    @Test
    public void anonymousClass_numericWideningInReturn() throws Exception {
        // byte→int 数值提升在匿名类方法中应正常工作
        List<ASTNode> nodes = parse(
                "Object helper = new Object() {" +
                        "  int toInt(byte b) { return b; }" +
                        "};" +
                        "helper;");
        assertEquals(2, nodes.size());

        ConstructorCallNode ctor = (ConstructorCallNode) ((VarDeclNode) nodes.get(0)).getInitializer();
        ClassDeclarationNode anonClass = ctor.getAnonymousClass();
        assertEquals(1, anonClass.getMethods().size());
        // byte→int 提升应通过（不抛异常）
    }

    // ==================== 15. 嵌套类解析 ====================

    @Test
    public void nestedClass_staticReference() throws Exception {
        // Map.Entry 作为静态嵌套类引用
        List<ASTNode> nodes = parse("Map.Entry e;");
        assertEquals(1, nodes.size());
        // 诊断：打印实际节点类型
        System.out
                .println("[DIAG] nestedClass_staticReference: node type = " + nodes.get(0).getClass().getSimpleName());
        assertTrue(nodes.get(0) instanceof VarDeclNode);

        VarDeclNode vd = (VarDeclNode) nodes.get(0);
        assertNotNull(vd.getDeclaredType());
        // Map.Entry 应被解析为 java.util.Map$Entry（通过 java.util.* import + tryNestedVariants
        // .→$）
        assertEquals(Map.Entry.class, vd.getDeclaredType().getRawType());
    }

    @Test
    public void nestedClass_fullyQualified() throws Exception {
        // 完全限定名的嵌套类
        List<ASTNode> nodes = parse("java.util.Map.Entry entry;");
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof VarDeclNode);

        VarDeclNode vd = (VarDeclNode) nodes.get(0);
        assertNotNull(vd.getDeclaredType());
        // 完全限定嵌套类应能正确解析（通过 $ 分隔或 import 解析）
        assertEquals(Map.Entry.class, vd.getDeclaredType().getRawType());
    }

    @Test
    public void nestedClass_memberAccessOnType() throws Exception {
        // 通过成员访问表达式引用嵌套类: Map.Entry
        List<ASTNode> nodes = parse("Class<?> type = Map.Entry.class;");
        assertEquals(1, nodes.size());
        // Map.Entry 应被正确识别为嵌套类引用
        assertTrue(nodes.get(0) instanceof VarDeclNode);
    }

    // ==================== 16. 综合类型推导场景 ====================

    @Test
    public void complex_genericChain() throws Exception {
        // 复杂泛型链: Map<String, List<Integer>> 的操作链
        List<ASTNode> nodes = parse(
                "Map<String, List<Integer>> data = new java.util.HashMap<>();" +
                        "data.put(\"nums\", new ArrayList<>());" +
                        "List<Integer> nums = data.get(\"nums\");");
        assertEquals(3, nodes.size());

        // 第一条: 声明
        assertTrue(nodes.get(0) instanceof VarDeclNode);
        GenericType declType = ((VarDeclNode) nodes.get(0)).getDeclaredType();
        assertNotNull(declType);
        assertEquals(Map.class, declType.getRawType());
        assertTrue("should be generic with 2 args", declType.isGeneric());

        // 第二条: put 调用
        assertTrue(nodes.get(1) instanceof MethodCallNode);
        MethodCallNode putCall = (MethodCallNode) nodes.get(1);
        assertEquals("put", putCall.getMethodName());
        assertNotNull(putCall.getBoundMethod());

        // 第三条: get 调用 + 赋值给 List<Integer>
        assertTrue(nodes.get(2) instanceof VarDeclNode);
    }

    @Test
    public void forEach_withGenericTypeCollection() throws Exception {
        // for-each 遍历泛型集合
        List<ASTNode> nodes = parse(
                "List<String> names = new ArrayList<>();" +
                        "for (String n : names) { n.length(); }");
        assertEquals(2, nodes.size());

        // 第二条是 ForEachNode
        assertTrue(nodes.get(1) instanceof ForEachNode);
        ForEachNode forEach = (ForEachNode) nodes.get(1);
        assertEquals("n", forEach.getItemName());
        assertEquals(String.class, forEach.getItemType());
    }

    @Test
    public void functionDef_withGenericReturnType() throws Exception {
        // 函数定义返回泛型类型
        List<ASTNode> nodes = parse(
                """
                function List<String> makeList() {
                    return new ArrayList<>();
                }
                """

        );
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0) instanceof FunctionDefNode);

        FunctionDefNode fn = (FunctionDefNode) nodes.get(0);
        assertEquals("makeList", fn.getFunctionName());
        assertNotNull(fn.getReturnType());
    }
}
