package com.justnothing.engine.parser.type;

import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.TypeParser;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * TypeParser 类型解析测试。
 * <p>
 * 覆盖基本类型、引用类型、泛型、数组、通配符等所有形式。
 * </p>
 */
public class TypeParserTest {

    private ParseContext context;

    @Before
    public void setUp() {
        context = new ParseContext();
        context.setStrictMode(false);  // 测试允许未声明变量
        context.setStrictMode(false);        // 测试允许未知类型
    }

    // ==================== 辅助方法 ====================

    private TypeParser createParser(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        List<Token> tokens = lexer.tokenize();
        return new TypeParser(tokens, context, "<test>");
    }

    private GenericType parseType(String source) throws CythavaParseException {
        return createParser(source).parseType();
    }

    private void assertParseError(String source) {
        try {
            parseType(source);
            fail("Expected parse error for: " + source);
        } catch (CythavaParseException expected) {
            // ok
        }
    }

    // ==================== 基本类型 ====================

    @Test
    public void intType() throws CythavaParseException {
        GenericType type = parseType("int");
        assertEquals(int.class, type.getRawType());
        assertFalse(type.isGeneric());
        assertFalse(type.isArray());
    }

    @Test
    public void longType() throws CythavaParseException {
        GenericType type = parseType("long");
        assertEquals(long.class, type.getRawType());
    }

    @Test
    public void doubleType() throws CythavaParseException {
        GenericType type = parseType("double");
        assertEquals(double.class, type.getRawType());
    }

    @Test
    public void booleanType() throws CythavaParseException {
        GenericType type = parseType("boolean");
        assertEquals(boolean.class, type.getRawType());
    }

    @Test
    public void charType() throws CythavaParseException {
        GenericType type = parseType("char");
        assertEquals(char.class, type.getRawType());
    }

    @Test
    public void byteType() throws CythavaParseException {
        GenericType type = parseType("byte");
        assertEquals(byte.class, type.getRawType());
    }

    @Test
    public void shortType() throws CythavaParseException {
        GenericType type = parseType("short");
        assertEquals(short.class, type.getRawType());
    }

    @Test
    public void floatType() throws CythavaParseException {
        GenericType type = parseType("float");
        assertEquals(float.class, type.getRawType());
    }

    @Test
    public void voidType() throws CythavaParseException {
        GenericType type = parseType("void");
        assertEquals(void.class, type.getRawType());
    }

    // ==================== 引用类型 ====================

    @Test
    public void simpleReference() throws CythavaParseException {
        GenericType type = parseType("String");
        assertEquals("String", type.getOriginalTypeName());
        assertEquals(String.class, type.getRawType());
    }

    @Test
    public void qualifiedName() throws CythavaParseException {
        GenericType type = parseType("java.util.List");
        assertEquals("java.util.List", type.getOriginalTypeName());
    }

    @Test
    public void deeplyQualifiedName() throws CythavaParseException {
        GenericType type = parseType("com.justnothing.engine.ASTNode");
        assertEquals("com.justnothing.engine.ASTNode", type.getOriginalTypeName());
    }

    // ==================== 数组类型 ====================

    @Test
    public void singleDimArray() throws CythavaParseException {
        GenericType type = parseType("int[]");
        assertEquals(int.class, type.getRawType());
        assertTrue(type.isArray());
        assertEquals(1, type.getArrayDepth());
    }

    @Test
    public void multiDimArray() throws CythavaParseException {
        GenericType type = parseType("String[][]");
        assertTrue(type.isArray());
        assertEquals(2, type.getArrayDepth());
    }

    @Test
    public void referenceArray() throws CythavaParseException {
        GenericType type = parseType("Object[]");
        assertTrue(type.isArray());
        assertEquals(1, type.getArrayDepth());
    }

    // ==================== 泛型类型 ====================

    @Test
    public void singleGenericType() throws CythavaParseException {
        GenericType type = parseType("List<String>");
        assertTrue(type.isGeneric());
        assertEquals(1, type.getTypeArguments().size());
        assertEquals("String", type.getTypeArguments().get(0).getOriginalTypeName());
    }

    @Test
    public void multiGenericType() throws CythavaParseException {
        GenericType type = parseType("Map<String, Integer>");
        assertTrue(type.isGeneric());
        assertEquals(2, type.getTypeArguments().size());
    }

    @Test
    public void nestedGeneric() throws CythavaParseException {
        GenericType type = parseType("Map<String, List<Integer>>");
        assertTrue(type.isGeneric());
        assertEquals(2, type.getTypeArguments().size());
        // 第二个参数本身也是泛型
        GenericType inner = type.getTypeArguments().get(1);
        assertTrue(inner.isGeneric());
    }

    @Test
    public void genericWithPrimitive() throws CythavaParseException {
        GenericType type = parseType("Optional<int>");
        assertTrue(type.isGeneric());
        assertEquals(int.class, type.getTypeArguments().get(0).getRawType());
    }

    // ==================== 泛型 + 数组组合 ====================

    @Test
    public void genericArrayOfGeneric() throws CythavaParseException {
        GenericType type = parseType("List<String>[]");
        assertTrue(type.isGeneric());
        assertTrue(type.isArray());
        assertEquals(1, type.getArrayDepth());
    }

    // ==================== 通配符 ====================

    @Test
    public void unboundedWildcard() throws CythavaParseException {
        GenericType type = parseType("?");
        assertEquals("?", type.getOriginalTypeName());
    }

    @Test
    public void extendsWildcard() throws CythavaParseException {
        GenericType type = parseType("? extends Number");
        assertNotNull(type.getOriginalTypeName());
        assertTrue(type.getOriginalTypeName().startsWith("? extends"));
    }

    @Test
    public void superWildcard() throws CythavaParseException {
        GenericType type = parseType("? super String");
        assertNotNull(type.getOriginalTypeName());
        assertTrue(type.getOriginalTypeName().startsWith("? super"));
    }

    @Test
    public void wildcardInGeneric() throws CythavaParseException {
        GenericType type = parseType("List<?>");
        assertTrue(type.isGeneric());
        assertEquals("?", type.getTypeArguments().get(0).getOriginalTypeName());
    }

    // ==================== 类型列表解析 ====================

    @Test
    public void singleTypeList() throws CythavaParseException {
        TypeParser parser = createParser("int");
        List<GenericType> types = parser.parseTypeList();
        assertEquals(1, types.size());
        assertEquals(int.class, types.get(0).getRawType());
    }

    @Test
    public void multiTypeList() throws CythavaParseException {
        TypeParser parser = createParser("String, int, boolean");
        List<GenericType> types = parser.parseTypeList();
        assertEquals(3, types.size());
    }

    // ==================== 错误处理 ====================

    @Test
    public void emptyInput() {
        assertParseError("");
    }

    @Test
    public void invalidToken() {
        assertParseError("+");
    }

    // ==================== isAtTypeStart ====================

    @Test
    public void isAtTypeStartPrimitive() {
        TypeParser parser = createParser("int x");
        assertTrue(parser.isAtTypeStart());
    }

    @Test
    public void isAtTypeStartIdentifier() {
        TypeParser parser = createParser("String name");
        assertTrue(parser.isAtTypeStart());
    }

    @Test
    public void isAtTypeStartWildcard() {
        TypeParser parser = createParser("? extends Foo");
        assertTrue(parser.isAtTypeStart());
    }

    @Test
    public void isNotAtTypeStartKeyword() {
        TypeParser parser = createParser("if (x > 0)");
        assertFalse(parser.isAtTypeStart());
    }
}
