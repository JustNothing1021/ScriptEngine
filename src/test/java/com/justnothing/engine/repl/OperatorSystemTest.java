package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.OperatorRegistry;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Operator 系统（运算符重载）集成测试。
 */
public class OperatorSystemTest {

    private ParseContext context;
    private DynamicClassGenerator codegen;

    @Before
    public void setUp() {
        context = new ParseContext();
        // 显式设置空 Registry（标记为外部管理，跳过自动注册 builtins）
        context.setOperatorRegistry(new OperatorRegistry());
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

    private List<ASTNode> parse(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), context, "<test>");
        List<ASTNode> nodes = parser.parse();
        for (ASTNode node : nodes) {
            if (node instanceof ClassDeclarationNode cd) {
                context.declareClass(cd);
            }
        }
        return nodes;
    }

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
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), context, "<test>");
        return parser.parse();
    }

    private ASTNode last(List<ASTNode> nodes) {
        assertFalse("Expected at least one node", nodes.isEmpty());
        return nodes.get(nodes.size() - 1);
    }

    // ==================== 1. OperatorRegistry 基础 ====================

    @Test
    public void registry_emptyInitially() {
        assertTrue("新注册表应该为空", context.getOperatorRegistry().isEmpty());
    }

    @Test
    public void registry_registerAndFindBinary() throws Exception {
        context.getOperatorRegistry().registerBinary(
                "+", Object.class, Object.class,
                String.class, null);

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findBinary("+", Object.class, Object.class);
        assertNotNull(overload);
        assertEquals("+", overload.operator());
        assertEquals(2, overload.parameterTypes().size());
        assertEquals(String.class, overload.returnType());
    }

    @Test
    public void registry_registerAndFindUnary() throws Exception {
        context.getOperatorRegistry().registerUnary(
                "-", int.class, int.class, null);

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findUnary("-", int.class);
        assertNotNull(overload);
        assertEquals("-", overload.operator());
        assertEquals(1, overload.parameterTypes().size());
    }

    @Test
    public void registry_findBinaryCompatible_widening() throws Exception {
        context.getOperatorRegistry().registerBinary(
                "+", Number.class, Number.class,
                double.class, null);

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findBinaryCompatible("+", Integer.class, Double.class);
        assertNotNull("Integer+Double 应兼容匹配 Number+Number", overload);
        assertEquals(double.class, overload.returnType());
    }

    @Test
    public void registry_exactMatchPreferredOverWidening() throws Exception {
        context.getOperatorRegistry().registerBinary(
                "+", String.class, String.class,
                String.class, null);
        context.getOperatorRegistry().registerBinary(
                "+", Object.class, Object.class,
                Object.class, null);

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findBinaryCompatible("+", String.class, String.class);
        assertNotNull(overload);
        assertEquals("应优先返回精确匹配", String.class, overload.returnType());
    }

    @Test
    public void registry_unsupportedOperator_throws() {
        try {
            context.getOperatorRegistry().registerBinary(
                    "???", Object.class, Object.class, Object.class, null);
            fail("不支持的运算符应抛出异常");
        } catch (IllegalArgumentException expected) {
            // 预期异常
        }
    }

    @Test
    public void registry_clear() throws Exception {
        context.getOperatorRegistry().registerBinary(
                "+", int.class, int.class, int.class, null);
        assertFalse(context.getOperatorRegistry().isEmpty());

        context.getOperatorRegistry().clear();
        assertTrue(context.getOperatorRegistry().isEmpty());
        assertNull(context.getOperatorRegistry().findBinary("+", int.class, int.class));
    }

    // ==================== 2. DeclParser 自动注册 operator 方法 ====================

    @Test
    public void declParser_autoRegistersOperatorMethod() throws Exception {
        parse("int operator+(int a, int b) { return a + b; }");

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findBinary("+", int.class, int.class);
        assertNotNull("operator+ 方法应被自动注册到注册表", overload);
        assertEquals(int.class, overload.returnType());
        assertTrue("实现应为 MethodDeclarationNode 或 FunctionDefNode",
                overload.implementation() instanceof MethodDeclarationNode
                        || overload.implementation() instanceof FunctionDefNode);
    }

    @Test
    public void declParser_autoRegistersMultipleOperators() throws Exception {
        parse("int operator+(int a, int b) { return a + b; }");
        parse("int operator*(int a, int b) { return a * b; }");

        assertNotNull(context.getOperatorRegistry().findBinary("+", int.class, int.class));
        assertNotNull(context.getOperatorRegistry().findBinary("*", int.class, int.class));
        assertEquals(2, context.getOperatorRegistry().getAllOverloads().size());
    }

    @Test
    public void declParser_operatorInClassAlsoRegistered() throws Exception {
        parse("class Math { int operator+(int a, int b) { return a + b; } }");

        OperatorRegistry.Overload overload = context.getOperatorRegistry()
                .findBinary("+", int.class, int.class);
        assertNotNull("类内定义的 operator+ 也应被注册", overload);
    }

    @Test
    public void declParser_nonOperatorMethodsNotRegistered() throws Exception {
        parse("int add(int a, int b) { return a + b; }");

        assertTrue("普通方法不应出现在运算符注册表中",
                context.getOperatorRegistry().isEmpty()
                        || context.getOperatorRegistry().getAllOverloads().stream()
                                .noneMatch(o -> o.operator().equals("+")));
    }

    // ==================== 3. 通配符泛型推导增强 ====================

    @Test
    public void wildcardExtendsNumber_addBindsCorrectly() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "List<? extends Number> l = new ArrayList<>();\n" +
                        "l.add(1);");

        ASTNode lastNode = last(nodes);
        assertTrue("应为 MethodCallNode", lastNode instanceof MethodCallNode);

        MethodCallNode call = (MethodCallNode) lastNode;
        assertNotNull("add 方法应被绑定", call.getBoundMethod());
        assertEquals("add", call.getMethodName());
        assertEquals(1, call.getArguments().size());
    }

    // ==================== 4. for-each auto/var 支持 ====================

    @Test
    public void forEach_withAutoKeyword() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "List<String> list = new ArrayList<>();\n" +
                        "for (auto s : list) {\n" +
                        "    System.out.println(s);\n" +
                        "}");

        assertEquals("应解析为变量声明 + for-each", 2, nodes.size());
        assertTrue("应为 ForEachNode", nodes.get(1) instanceof ForEachNode);

        ForEachNode forEach = (ForEachNode) nodes.get(1);
        assertEquals("s", forEach.getItemName());
    }

    @Test
    public void forEach_withVarKeyword() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "List<Integer> nums = new ArrayList<>();\n" +
                        "for (var n : nums) { n.toString(); }");

        assertEquals(2, nodes.size());
        assertTrue("应为 ForEachNode", nodes.get(1) instanceof ForEachNode);

        ForEachNode forEach = (ForEachNode) nodes.get(1);
        assertEquals("n", forEach.getItemName());
    }

    // ==================== 5. 自定义类字段访问链路 ====================

    @Test
    public void customClassVariable_fieldAccess() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "class Box<T> { T value; }\n" +
                        "Box<String> stringBox = new Box<>();\n" +
                        "String s = stringBox.value;");

        ASTNode declNode = last(nodes);
        assertTrue("应为 VarDeclNode", declNode instanceof VarDeclNode);

        VarDeclNode decl = (VarDeclNode) declNode;
        assertEquals("s", decl.getVarName());
        assertNotNull("initializer 应包含字段访问表达式", decl.getInitializer());
    }

    @Test
    public void customClassGenericFieldAccess() throws Exception {
        List<ASTNode> nodes = parseMultiLine(
                "class Pair<K, V> { K key; V value; }\n" +
                        "Pair<String, Integer> p = new Pair<>();\n" +
                        "String k = p.key;\n" +
                        "Integer v = p.value;");

        assertEquals("应有 4 个节点", 4, nodes.size());

        assertTrue(nodes.get(2) instanceof VarDeclNode);  // String k = p.key
        assertTrue(nodes.get(3) instanceof VarDeclNode);  // Integer v = p.value
    }
}
