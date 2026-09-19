package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字面量解析层。
 * <p>
 * 负责各类字面量、数组/映射字面量与字符串插值。
 * </p>
 */
abstract class LiteralParser extends LambdaParser {

    protected LiteralParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 特殊表达式解析 ====================

    /**
     * 解析 f-string 插值字符串。
     * <p>
     * Token 的 value 是 {@code List&lt;Object&gt;}，元素交替为：
     * {@code String}（字面文本）和 {@code Lexer.InterpolationPart}（需要子解析的表达式）。
     * </p>
     */
    protected InterpolatedStringNode parseInterpolatedString(Token token) throws CythavaParseException {
        @SuppressWarnings("unchecked")
        List<Object> parts = (List<Object>) token.value();
        InterpolatedStringNode.Builder builder = new InterpolatedStringNode.Builder();
        builder.location(token.location());

        for (Object part : parts) {
            if (part instanceof String literal) {
                builder.addLiteral(literal);
            } else if (part instanceof Lexer.InterpolationPart ip) {
                // 对插值表达式进行子解析
                Lexer subLexer = new Lexer(ip.getExpression(), fileName);
                List<Token> subTokens = subLexer.tokenize();
                ExprParser subParser = new ExprParser(subTokens, context, fileName);
                ASTNode expr = subParser.parseNextExpression();
                builder.addExpression(expr);
            }
        }

        return (InterpolatedStringNode) builder.build();
    }

    /**
     * 推导数组字面量的元素类型。
     * <p>
     * 返回的元素类型保留自身的数组维度，例如 {@code {{1}, {2}}} 的元素是 {@code int[]}
     * （rawType=int、arrayDepth=1），调用方据此把外层标注为 {@code int[][]}。
     * 元素类型不一致（且无法数值提升）或维度不一致时返回 null，调用方退化为 {@code Object[]}。
     * </p>
     */
    private GenericType inferArrayLiteralElementType(List<ASTNode> elements) {
        if (elements.isEmpty())
            return null;
        Class<?> raw = null;
        int depth = 0;
        for (ASTNode elem : elements) {
            JType elemType = context.getType(elem);
            if (elemType == null)
                return null;
            Class<?> elemRaw = elemType.getRawType();
            int elemDepth = elemType.getArrayDepth();
            if (raw == null) {
                raw = elemRaw;
                depth = elemDepth;
            } else if (elemDepth != depth) {
                // 维度不一致（如 {1} 与 {{1}} 混用）→ 无法统一
                return null;
            } else if (!raw.equals(elemRaw)) {
                // 类型不一致：仅非数组元素允许数值提升；数组元素间提升后元素本身仍不匹配
                if (depth == 0 && isNumericWidening(raw, elemRaw)) {
                    raw = elemRaw; // 提升到更宽的类型
                } else {
                    return null; // 不一致且不能提升
                }
            }
        }
        return new GenericType(raw, Collections.emptyList(), depth);
    }

    /** 检查 from 是否可以数值提升到 to（简化版，只处理常见 widening）。 */
    private boolean isNumericWidening(Class<?> from, Class<?> to) {
        Class<?>[] order = { Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE };
        int fromIdx = -1, toIdx = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(from))
                fromIdx = i;
            if (order[i].equals(to))
                toIdx = i;
        }
        return fromIdx >= 0 && toIdx > fromIdx;
    }

    protected ArrayLiteralNode parseArrayLiteral() throws CythavaParseException {
        SourceLocation location = createLocation();
        consume(TokenType.DELIMITER_LEFT_BRACKET, "Expected '['");

        List<ASTNode> elements = collectCommaSeparated(
                TokenType.DELIMITER_RIGHT_BRACKET,
                this::parseNextExpression);

        consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after array literal");
        ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(elements).location(location)
                .build();
        // 从元素推导数组类型：所有元素类型相同 → 该类型的数组；否则 → Object[]
        GenericType elementType = inferArrayLiteralElementType(elements);
        if (elementType != null) {
            // 元素自身可能已是数组（{{1}, {2}} 的元素是 int[]），外层维度在元素维度上加 1
            GenericType gt = new GenericType(elementType.getRawType(), Collections.emptyList(),
                    elementType.getArrayDepth() + 1);
            context.setType(node, JType.fromGenericType(gt));
        } else {
            annotate(node, Object[].class);
        }
        return node;
    }

    /**
     * 解析花括号初始化器。
     * <p>
     * 根据首元素后的内容判断是数组初始化器还是 Map 字面量：
     * <ul>
     * <li>{@code {1, 2, 3}} → 数组初始化器</li>
     * <li>{@code {"key": value, ...}} → Map 字面量（首元素后有 {@code :}）</li>
     * </ul>
     * </p>
     */
    protected ASTNode parseBraceInitializer() throws CythavaParseException {
        SourceLocation location = createLocation();
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{'");

        // 空的花括号 → 空数组
        if (check(TokenType.DELIMITER_RIGHT_BRACE)) {
            advance();
            ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(new ArrayList<>())
                    .location(location).build();
            annotate(node, Object[].class);
            return node;
        }

        ASTNode firstElement = parseNextExpression();

        // 首元素后有 : → Map 字面量
        if (check(TokenType.OPERATOR_COLON)) {
            return parseMapLiteral(firstElement, location);
        }

        // 否则 → 数组初始化器
        List<ASTNode> elements = new ArrayList<>();
        elements.add(firstElement);
        elements.addAll(collectCommaSeparated(
                TokenType.DELIMITER_RIGHT_BRACE,
                this::parseNextExpression));

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after array initializer");
        ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(elements).location(location)
                .build();
        // 复用与 [...] 相同的元素类型推断逻辑
        GenericType braceElementType = inferArrayLiteralElementType(elements);
        if (braceElementType != null) {
            GenericType gt = new GenericType(braceElementType.getRawType(), Collections.emptyList(),
                    braceElementType.getArrayDepth() + 1);
            context.setType(node, JType.fromGenericType(gt));
        } else {
            annotate(node, Object[].class);
        }
        return node;
    }

    /**
     * 解析 Java 风格数组花括号初始化器 {@code {1, 2, 3}} 或 {@code {{1,2}, {3,4}}}。
     * <p>
     * 委托给 parseBraceInitializer，它已正确处理嵌套花括号和 Map 歧义。
     * </p>
     */
    @Override
    protected ArrayLiteralNode parseBraceArrayInitializer() throws CythavaParseException {
        ASTNode result = parseBraceInitializer();
        if (result instanceof ArrayLiteralNode aln) {
            return aln;
        }
        throw error("Expected array initializer", ErrorCode.PARSE_UNEXPECTED_TOKEN);
    }

    /**
     * 解析 Map 字面量 {@code {key: value, key2: value2, ...}}。
     * <p>
     * 调用时第一个 key 已解析完毕，{@code :} 尚未消费。
     * </p>
     */
    private MapLiteralNode parseMapLiteral(ASTNode firstKey, SourceLocation location)
            throws CythavaParseException {
        // 使用 LinkedHashMap 保持插入顺序
        LinkedHashMap<ASTNode, ASTNode> entries = new LinkedHashMap<>();

        consume(TokenType.OPERATOR_COLON, "Expected ':' in map literal");
        ASTNode firstValue = parseNextExpression();
        entries.put(firstKey, firstValue);

        while (match(TokenType.DELIMITER_COMMA)) {
            if (check(TokenType.DELIMITER_RIGHT_BRACE)) {
                break;
            }
            ASTNode key = parseNextExpression();
            consume(TokenType.OPERATOR_COLON, "Expected ':' in map literal");
            ASTNode value = parseNextExpression();
            entries.put(key, value);
        }

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after map literal");
        MapLiteralNode node = (MapLiteralNode) new MapLiteralNode.Builder().entries(entries).location(location).build();
        // Map 类型推断：非空 entries 推断为 Map.class，空 map 保持 Object.class
        if (!entries.isEmpty()) {
            annotate(node, Map.class);
        } else {
            annotate(node, Object.class);
        }
        return node;
    }

    /**
     * 收集逗号分隔的元素列表。
     *
     * @param endToken      结束标记（不消费）
     * @param elementParser 元素解析函数
     * @return 元素列表
     */
    private List<ASTNode> collectCommaSeparated(TokenType endToken, ElementParser elementParser)
            throws CythavaParseException {
        List<ASTNode> elements = new ArrayList<>();

        if (check(endToken)) {
            return elements;
        }

        // 跳过可能的起始逗号
        if (check(TokenType.DELIMITER_COMMA)) {
            advance();
        }

        do {
            elements.add(elementParser.parse());
        } while (match(TokenType.DELIMITER_COMMA) && !check(endToken));

        return elements;
    }

    /**
     * 元素解析函数式接口（用于 collectCommaSeparated）。
     */
    @FunctionalInterface
    private interface ElementParser {
        ASTNode parse() throws CythavaParseException;
    }
}
