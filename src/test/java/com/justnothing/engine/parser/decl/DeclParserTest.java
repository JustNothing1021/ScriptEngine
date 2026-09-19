package com.justnothing.engine.parser.decl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.DeclParser;
import com.justnothing.engine.parser.ParseContext;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * DeclParser 声明解析测试。
 * <p>
 * 覆盖 import、class、interface、enum、函数定义、注解等所有声明类型。
 * </p>
 */
public class DeclParserTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        context.setStrictMode(false);  // 测试允许未声明变量
    }

    // ==================== 辅助方法 ====================

    private DeclParser createParser(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        List<Token> tokens = lexer.tokenize();
        return new DeclParser(tokens, context, "<test>");
    }

    private List<ASTNode> parseUnit(String source) throws CythavaParseException {
        List<ASTNode> decls = new ArrayList<>();
        DeclParser parser = createParser(source);
        ASTNode next;
        while ((next = parser.parseNextCompilationUnit()) != null) {
            decls.add(next);
        }
        return decls;
    }

    private void assertParseError(String source) {
        try {
            parseUnit(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException expected) {
            // ok
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends ASTNode> T assertDecl(Class<T> type, String source) {
        try {
            List<ASTNode> decls = parseUnit(source);
            assertFalse("Expected at least one declaration for: " + source, decls.isEmpty());
            if (!type.isInstance(decls.get(0))) {
                fail("Expected " + type.getSimpleName() + " but got "
                        + decls.get(0).getClass().getSimpleName() + " for: " + source);
            }
            return (T) decls.get(0);
        } catch (CythavaParseException e) {
            fail("Unexpected parse error for '" + source + "': " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    // ==================== Import 声明 ====================

    @Test
    public void simpleImport() throws CythavaParseException {
        ImportNode node = assertDecl(ImportNode.class, "import java.util.List;");
        assertNotNull(node);
        assertTrue(node.getPackageName().contains("java.util.List"));
    }

    @Test
    public void wildcardImport() throws CythavaParseException {
        ImportNode node = assertDecl(ImportNode.class, "import java.util.*;");
        assertNotNull(node);
        assertTrue(node.getPackageName().contains("*"));
    }

    @Test
    public void multipleImports() throws CythavaParseException {
        List<ASTNode> decls = parseUnit("import java.util.List; import java.io.File;");
        assertEquals(2, decls.size());
    }

    // ==================== Class 声明 ====================

    @Test
    public void emptyClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class, "class MyClass {}");
        assertEquals("MyClass", node.getClassName());
        assertFalse(node.isInterface());
        assertTrue(node.getFields().isEmpty());
        assertTrue(node.getMethods().isEmpty());
    }

    @Test
    public void classWithFields() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Person { String name; int age; }");
        assertEquals("Person", node.getClassName());
        assertFalse(node.getFields().isEmpty());
    }

    @Test
    public void classWithMethod() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Calculator { int add(int a, int b) { return a + b; } }");
        assertEquals("Calculator", node.getClassName());
        assertFalse(node.getMethods().isEmpty());
        assertEquals("add", node.getMethods().get(0).getMethodName());
    }

    @Test
    public void classExtends() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Dog extends Animal {}");
        assertNotNull(node.getSuperClass());
    }

    @Test
    public void classImplements() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class ArrayList implements List, Serializable {}");
        assertFalse(node.getInterfaces().isEmpty());
    }

    @Test
    public void classWithConstructor() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Point { Point(int x, int y) { ; } }");
        assertFalse(node.getConstructors().isEmpty());
        assertEquals("Point", node.getConstructors().get(0).getClassName());
    }

    @Test
    public void classWithFieldInitializer() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Config { int maxConnections = 100; }");
        FieldDeclarationNode field = node.getFields().get(0);
        assertNotNull(field.getInitialValue());
    }

    // ==================== Interface 声明 ====================

    @Test
    public void simpleInterface() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "interface Runnable { void run(); }");
        assertTrue(node.isInterface());
        assertEquals("Runnable", node.getClassName());
    }

    // ==================== Enum 声明 ====================

    @Test
    public void simpleEnum() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "enum Color { RED, GREEN, BLUE }");
        assertNotNull(node);
    }

    @Test
    public void enumWithArgs() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "enum Planet { EARTH(5.97), MARS(6.39) }");
        assertNotNull(node);
    }

    // ==================== 函数定义 ====================

    @Test
    public void topLevelFunction() throws CythavaParseException {
        FunctionDefNode node = assertDecl(FunctionDefNode.class,
                "int factorial(int n) { if (n <= 1) { return 1; } return n * factorial(n - 1); }");
        assertEquals("factorial", node.getFunctionName());
        assertNotNull(node.getBody());
    }

    @Test
    public void voidFunction() throws CythavaParseException {
        FunctionDefNode node = assertDecl(FunctionDefNode.class,
                "void greet(String name) { ; }");
        assertEquals("greet", node.getFunctionName());
    }

    @Test
    public void functionNoParams() throws CythavaParseException {
        FunctionDefNode node = assertDecl(FunctionDefNode.class,
                "String now() { ; }");
        assertEquals("now", node.getFunctionName());
        assertTrue(node.getParameters().isEmpty());
    }

    // ==================== 注解 ====================

    @Test
    public void annotationOnly() throws CythavaParseException {
        AnnotationNode node = assertDecl(AnnotationNode.class, "@Override");
        assertEquals("Override", node.getAnnotationName());
    }

    @Test
    public void annotationWithValue() throws CythavaParseException {
        AnnotationNode node = assertDecl(AnnotationNode.class,
                "@Deprecated(\"use newApi instead\")");
        assertEquals("Deprecated", node.getAnnotationName());
        assertFalse(node.getValues().isEmpty());
    }

    @Test
    public void annotatedClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "@Entity class User {}");
        assertFalse(node.getAnnotations().isEmpty());
        assertEquals("Entity", node.getAnnotations().get(0).getAnnotationName());
    }

    // ==================== 修饰符 ====================

    @Test
    public void publicClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "public class PublicClass {}");
        assertTrue(node.getModifiers().isPublic());
    }

    @Test
    public void finalClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "final class Immutable {}");
        assertTrue(node.getModifiers().isFinal());
    }

    @Test
    public void abstractClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "abstract class Shape {}");
        assertTrue(node.getModifiers().isAbstract());
    }

    @Test
    public void strictfpClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "strictfp class Precise {}");
        assertTrue(node.getModifiers().isStrictfp());
    }

    @Test
    public void volatileField() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Shared { volatile int counter; }");
        assertTrue(node.getFields().get(0).getModifiers().isVolatile());
    }

    @Test
    public void transientField() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Cached { transient String blob; }");
        assertTrue(node.getFields().get(0).getModifiers().isTransient());
    }

    @Test
    public void strictfpMethod() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Precise { strictfp double half(double x) { return x; } }");
        assertTrue(node.getMethods().get(0).getModifiers().isStrictfp());
    }

    @Test
    public void combinedNewModifiers() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Mixed { public static volatile transient int state; }");
        ClassModifiers mods = node.getFields().get(0).getModifiers();
        assertTrue(mods.isPublic());
        assertTrue(mods.isStatic());
        assertTrue(mods.isVolatile());
        assertTrue(mods.isTransient());
        assertEquals(0x0001 | 0x0008 | 0x0040 | 0x0080, mods.toAccessFlags());
    }

    // ==================== 记录 / sealed ====================

    @Test
    public void recordDeclarationParsesComponents() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x, int y) {}");
        assertTrue(node.isRecord());
        assertEquals(2, node.getRecordComponents().size());
        assertEquals("x", node.getRecordComponents().get(0).getParameterName());
        assertEquals("y", node.getRecordComponents().get(1).getParameterName());
    }

    @Test
    public void recordIsFinalByDefault() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x) {}");
        assertTrue(node.getModifiers().isFinal());
    }

    @Test
    public void recordComponentBecomesFinalField() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x) {}");
        FieldDeclarationNode field = node.getFields().get(0);
        assertEquals("x", field.getFieldName());
        assertTrue(field.getModifiers().isFinal());
    }

    @Test
    public void recordGeneratesCanonicalConstructorAndAccessors() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x, int y) {}");
        assertEquals(1, node.getConstructors().size());
        assertEquals(2, node.getConstructors().get(0).getParameters().size());
        // 组件访问器（另有隐式三件套 equals / hashCode / toString，故不按方法总数断言）
        assertTrue(node.getMethods().stream().anyMatch(m -> "x".equals(m.getMethodName())));
        assertTrue(node.getMethods().stream().anyMatch(m -> "y".equals(m.getMethodName())));
    }

    @Test
    public void recordKeepsExtraMembers() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x) { int doubled() { return x * 2; } }");
        // 访问器 x + 手写的 doubled 都保留（另有隐式三件套）
        assertTrue(node.getMethods().stream().anyMatch(m -> "x".equals(m.getMethodName())));
        assertTrue(node.getMethods().stream().anyMatch(m -> "doubled".equals(m.getMethodName())));
    }

    @Test
    public void recordWithGenericComponent() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Box<T>(T value) {}");
        assertTrue(node.isRecord());
        assertTrue(node.isGeneric());
        assertEquals("T", node.getRecordComponents().get(0).getType().getTypeName());
    }

    @Test
    public void recordMayImplementInterface() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "record Point(int x) implements Comparable {}");
        assertEquals(1, node.getInterfaces().size());
    }

    @Test
    public void recordNameIsStillUsableAsMemberName() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "class Names { int record = 1; int sealed = 2; int permits = 3; }");
        assertEquals(3, node.getFields().size());
    }

    @Test
    public void sealedClassWithPermits() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "sealed class Shape permits Circle, Square {}");
        assertTrue(node.getModifiers().isSealed());
        assertEquals(2, node.getPermittedSubclasses().size());
        assertEquals("Circle", node.getPermittedSubclasses().get(0).getTypeName());
        assertEquals("Square", node.getPermittedSubclasses().get(1).getTypeName());
    }

    @Test
    public void nonSealedClass() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "non-sealed class Circle extends Shape {}");
        assertTrue(node.getModifiers().isNonSealed());
    }

    @Test
    public void sealedInterfaceWithPermits() throws CythavaParseException {
        ClassDeclarationNode node = assertDecl(ClassDeclarationNode.class,
                "sealed interface Shape permits Circle {}");
        assertTrue(node.isInterface());
        assertTrue(node.getModifiers().isSealed());
        assertEquals(1, node.getPermittedSubclasses().size());
    }

    // ==================== 组合测试 ====================

    @Test
    public void fullCompilationUnit() throws CythavaParseException {
        List<ASTNode> decls = parseUnit(
                "package com.example;" +
                "import java.util.*;" +
                "" +
                "public class App {" +
                "  String name = \"hello\";" +
                "  void run() { ; }" +
                "}");
        assertTrue(decls.size() >= 3); // package, import, class
    }

    @Test
    public void multipleDeclarations() throws CythavaParseException {
        List<ASTNode> decls = parseUnit(
                "class A {}" +
                "interface B {}" +
                "enum C { X }" +
                "int helper(int x) { return x; }");
        assertEquals(4, decls.size());
    }

    // ==================== 泛型闭合符 >> / >>> 的拆分 ====================

    /**
     * 类型参数上界里的 {@code >>}。
     * <p>
     * {@code class G<T extends Comparable<T>>} 的两个 {@code >} 分属"上界泛型"和"类型参数表"两层，
     * 而前者由另一个 TypeParser 实例解析 → 拆分状态必须跨实例共享，否则外层会误报
     * "Expected '>' after type parameter list"。
     * </p>
     */
    @Test
    public void boundedTypeParamClosedByShiftOperator() {
        ClassDeclarationNode decl = assertDecl(ClassDeclarationNode.class,
                "class G<T extends Comparable<T>> { T v; }");
        assertEquals("G", decl.getClassName());
        assertEquals(List.of("T"), decl.getTypeParameters());
        assertEquals("Comparable<T>", decl.getTypeParameterBound("T").getTypeName());
    }

    /** 嵌套泛型用 {@code >>} / {@code >>>} 闭合。 */
    @Test
    public void nestedGenericClosedByShiftOperators() {
        assertDecl(ClassDeclarationNode.class, "class H { Map<String, List<Integer>> m; }");
        assertDecl(ClassDeclarationNode.class, "class I { List<List<List<String>>> xs; }");
    }

    // ==================== 错误处理 ====================

    @Test
    public void emptyInput() {
        // 空输入应该返回空列表或报错 — 取决于实现
        try {
            List<ASTNode> decls = parseUnit("");
            assertTrue(decls.isEmpty()); // 空列表也合理
        } catch (CythavaParseException e) {
            // 报错也可接受
        }
    }

    @Test
    public void invalidSyntax() {
        assertParseError("@@invalid");
    }
}
