package com.justnothing.engine.parser;

import com.justnothing.engine.api.ClassResolver;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cythava 类型解析器。
 * <p>
 * 解析所有 Java/Cythava 类型声明，包括：
 * <ul>
 *   <li>基本类型: int, long, float, double, boolean, char, byte, short, void</li>
 *   <li>引用类型: String, java.util.List（限定名）</li>
 *   <li>泛型类型: List&lt;String&gt;, Map&lt;String, Integer&gt;</li>
 *   <li>数组类型: int[], String[][], List&lt;?&gt;[]</li>
 *   <li>通配符: ?, ? extends T, ? super T</li>
 * </ul>
 * </p>
 */
public class TypeParser extends BaseParser {

    /**
     * 是否允许未解析的类型名。
     * <p>
     * 默认 false：严格模式下未知类型直接报错。
     * 置为 true 时，无法解析的类型名回退为 {@code Object.class} 而不报错 ——
     * 类体成员声明需要它，因为字段/参数/返回值可以是类的泛型类型参数（如 {@code T value;}），
     * 这些名字不是真实的类。
     * </p>
     */
    private boolean allowUnresolvedTypes = false;

    /** 设置是否允许未解析的类型名（见 {@link #allowUnresolvedTypes}）。 */
    public void setAllowUnresolvedTypes(boolean allowUnresolvedTypes) {
        this.allowUnresolvedTypes = allowUnresolvedTypes;
    }

    /**
     * 构造器。
     *
     * @param tokens   token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public TypeParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 入口 ====================

    /**
     * 解析完整类型声明（含数组后缀）。
     * <p>
     * 例如: {@code int}, {@code String[]}, {@code List<Map<String, Integer>>}
     *
     * @return GenericType 表示的类型信息
     * @throws CythavaParseException 语法错误
     */
    public GenericType parseType() throws CythavaParseException {
        GenericType baseType = parseBaseType();

        // 解析数组维度后缀 []
        int dimensions = 0;
        while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
            consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in array type");
            dimensions++;
        }

        if (dimensions > 0) {
            return new GenericType(baseType.getRawType(),
                    baseType.getTypeArguments(), dimensions,
                    baseType.getOriginalTypeName());
        }

        return baseType;
    }

    /**
     * 解析类型列表（用于泛型参数、方法参数等）。
     * <p>
     * 例如: {@code String, int, List<?>}
     *
     * @return 类型列表
     * @throws CythavaParseException 语法错误
     */
    public List<GenericType> parseTypeList() throws CythavaParseException {
        List<GenericType> types = new ArrayList<>();
        do {
            types.add(parseType());
        } while (match(TokenType.DELIMITER_COMMA));
        return types;
    }

    // ==================== 基础类型解析 ====================

    /**
     * 解析基础类型（不含数组后缀）。
     * <p>
     * 处理基本类型、引用类型、泛型参数化、通配符。
     */
    private GenericType parseBaseType() throws CythavaParseException {
        // 通配符 ?（由 parseWildcard 内部消费）
        if (check(TokenType.OPERATOR_QUESTION)) {
            return parseWildcard();
        }

        // 基本类型关键字
        if (isPrimitiveTypeKeyword(peek().type())) {
            String name = advance().text();
            // 基本类型是语法层就该知道的知识（ClassResolver 里有本地表），不属于"类解析"：
            // 探针模式下仍照常解析，否则 int/long/... 会退化成 Object，污染测量结果。
            Class<?> primitive = ParseContext.probeSkipsClassResolution()
                    ? ClassResolver.findClass(name)
                    : context.resolveClass(name);
            return GenericType.of(primitive != null ? primitive : Object.class);
        }

        // 引用类型: 标识符 [. 标识符]* [< 泛型参数 >]
        if (check(TokenType.IDENTIFIER)) {
            return parseReferenceType();
        }

        throw error("Expected type", ErrorCode.PARSE_INVALID_TYPE);
    }

    /** 引用类型: 可能是简单名称或限定名称，可能带泛型参数。 */
    private GenericType parseReferenceType() throws CythavaParseException {
        StringBuilder typeName = new StringBuilder(advance().text()); // 第一个标识符

        // 限定名: com.example.MyClass
        while (match(TokenType.OPERATOR_DOT)) {
            typeName.append('.').append(
                    consume(TokenType.IDENTIFIER,
                            "Expected identifier after '.' in type name").text());
        }

        // 泛型参数: <T1, T2, ...>
        List<GenericType> typeArguments = new ArrayList<>();
        if (match(TokenType.OPERATOR_LESS_THAN)) {
            typeArguments = parseTypeArguments();
            consumeGenericClose("Expected '>' after type arguments");
        }

        String fullTypeName = typeName.toString();
        Class<?> resolvedClass = context.resolveClass(fullTypeName);

        // 严格模式：未知类型必须报错（不允许前向引用）
        if (resolvedClass == null && context.isStrictMode() && !allowUnresolvedTypes) {
            throw semanticError("Unknown type '" + fullTypeName + "'; "
                    + "cannot resolve class (forward references are not allowed)",
                    ErrorCode.PARSE_CLASS_NOT_FOUND);
        }

        // ★ 泛型参数数量校验：对于已解析的 Java 类，检查泛型参数数量是否匹配声明
        if (resolvedClass != null && !typeArguments.isEmpty()) {
            TypeVariable<?>[] typeParams = resolvedClass.getTypeParameters();
            if (typeParams.length > 0 && typeArguments.size() != typeParams.length) {
                // 非严格模式：泛型参数数量不匹配静默放行
                if (!context.isStrictMode()) {
                    // 继续使用实际提供的参数，运行期由 JVM 处理
                } else {
                    throw error("Type '" + resolvedClass.getName() + "' expects " + typeParams.length
                            + " type parameter(s) but got " + typeArguments.size(), ErrorCode.PARSE_INVALID_TYPE);
                }
            }
        }

        return new GenericType(
                resolvedClass != null ? resolvedClass : Object.class,
                typeArguments, 0, fullTypeName);
    }

    /** 解析泛型参数列表 <T1, T2, ...> 内部的内容。 */
    private List<GenericType> parseTypeArguments() throws CythavaParseException {
        List<GenericType> args = new ArrayList<>();

        do {
            args.add(parseTypeArgument());
        } while (match(TokenType.DELIMITER_COMMA));

        return args;
    }

    /** 单个泛型参数（可以是完整类型或通配符）。 */
    private GenericType parseTypeArgument() throws CythavaParseException {
        // 通配符
        if (check(TokenType.OPERATOR_QUESTION)) {
            return parseWildcard();
        }
        // 普通类型
        return parseType();
    }

    // ==================== 通配符 ====================

    /** 解析通配符: ?, ? extends Type, ? super Type */
    private GenericType parseWildcard() throws CythavaParseException {
        consume(TokenType.OPERATOR_QUESTION, "Expected '?' for wildcard type");

        if (match(TokenType.KEYWORD_EXTENDS)) {
            GenericType bound = parseType();
            // ★ 通配符的上界信息编码在 originalTypeName 中，不放入 typeArguments
            //   否则 getTypeName() 递归输出时会把 bound 的类型参数再包一层 <> 导致 Number<Number>>
            return new GenericType(Object.class, Collections.emptyList(), 0,
                    "? extends " + bound.getTypeName());
        }

        if (match(TokenType.KEYWORD_SUPER)) {
            GenericType bound = parseType();
            return new GenericType(Object.class, Collections.emptyList(), 0,
                    "? super " + bound.getTypeName());
        }

        // 无界通配符 ?
        return new GenericType(Object.class, Collections.emptyList(), 0, "?");
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断当前 token 是否可以作为类型的起始 token。
     * 用于在语句/声明解析中判断接下来是否是类型。
     */
    public boolean isAtTypeStart() {
        return isPrimitiveTypeKeyword(peek().type())
                || check(TokenType.IDENTIFIER)
                || check(TokenType.OPERATOR_QUESTION);
    }
}
