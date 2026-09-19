package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.LiteralNode;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.lang.reflect.Array;
import java.util.List;

/**
 * 解析器基类。
 * <p>
 * 所有子解析器（ExprParser、StmtParser、TypeParser、DeclParser 等）的公共父类，
 * 提供 token 操作、位置管理、回溯支持等基础能力。
 * </p>
 * <p>
 * 设计约定：
 * <ul>
 *   <li>所有子解析器共享同一个 {@code List<Token>}，但各自维护独立的 position 指针</li>
 *   <li>跨解析器调用时必须通过 setPosition/getPosition 同步位置</li>
 *   <li>savePosition/restorePosition/releasePosition 必须配对使用</li>
 * </ul>
 * </p>
 *
 * @see CythavaParseException
 * @see ParseContext
 */
public abstract class BaseParser {

    protected final List<Token> tokens;
    protected int position = 0;
    protected final ParseContext context;
    protected final String fileName;

    /** 位置回溯栈，固定大小避免 GC。 */
    private static final int MAX_SAVE_DEPTH = 64;
    private final int[] savedPositions = new int[MAX_SAVE_DEPTH];
    private int stackTop = 0;

    /**
     * 构造器。
     *
     * @param tokens   完整的 token 流（不可变）
     * @param context  解析上下文（符号表 + 类型解析）
     * @param fileName 源文件名（用于错误消息）
     */
    protected BaseParser(List<Token> tokens, ParseContext context, String fileName) {
        this.tokens = tokens;
        this.context = context;
        this.fileName = fileName;
    }

    // ==================== Token 基本操作 ====================

    /** 是否已到达输入末尾。 */
    protected boolean isAtEnd() {
        return position >= tokens.size();
    }

    /** 查看当前 token（不消费）。 */
    protected Token peek() {
        if (isAtEnd()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(position);
    }

    /** 查看相对偏移位置的 token（不消费）。 */
    protected Token peek(int offset) {
        if (position + offset >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(position + offset);
    }

    /** 查看下一个 token 的类型（不消费）。 */
    protected TokenType peekType() {
        return peek().type();
    }

    /** 查看下一个 next 位置的 token 类型。 */
    protected TokenType peekNextType() {
        return peek(1).type();
    }

    /** 消费当前 token 并返回。 */
    protected Token advance() {
        if (isAtEnd()) {
            return tokens.get(tokens.size() - 1);
        }
        Token token = tokens.get(position++);
        // 记录当前位置，供无 token 上下文的语义检查（如 ParseContext 的重复声明）报错定位
        context.setCurrentLocation(token.location());
        return token;
    }

    /** 如果当前 token 匹配指定类型则消费并返回 true。 */
    protected boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    /** 检查当前 token 是否为指定类型（不消费）。 */
    protected boolean check(TokenType type) {
        if (isAtEnd()) {
            return false;
        }
        return peek().type() == type;
    }

    /** 检查下一个 token 是否为指定类型（不消费）。 */
    protected boolean checkNext(TokenType type) {
        if (position + 1 >= tokens.size()) {
            return false;
        }
        return tokens.get(position + 1).type() == type;
    }

    /** 检查当前 token 文本是否为 "in"（用于 for-each 分隔符判断）。 */
    protected boolean checkInKeyword() {
        return check(TokenType.IDENTIFIER) && "in".equals(peek().text());
    }

    /**
     * 消费指定类型的 token，不匹配时抛出异常。
     *
     * @param type    期望的 token 类型
     * @param message 错误提示消息
     * @return 被消费的 token
     * @throws CythavaParseException 当前 token 不匹配
     */
    protected Token consume(TokenType type, String message) throws CythavaParseException {
        if (check(type)) {
            return advance();
        }
        throw error(message);
    }

    protected Token consume(TokenType type, String message, ErrorCode errorCode) throws CythavaParseException {
        if (check(type)) {
            return advance();
        }
        throw error(message, errorCode);
    }


    /**
     * 消费指定类型的 token，不匹配时抛出于语义错误异常。
     *
     * @param type    期望的 token 类型
     * @param message 错误提示消息
     * @return 被消费的 token
     * @throws CythavaParseException 当前 token 不匹配
     */
    protected Token consumeOrSemanticError(TokenType type, String message) throws CythavaParseException {
        if (check(type)) {
            return advance();
        }
        throw semanticError(message, ErrorCode.PARSE_INVALID_SYNTAX);
    }

    protected Token consumeOrSemanticError(TokenType type, String message, ErrorCode errorCode) throws CythavaParseException {
        if (check(type)) {
            return advance();
        }
        throw semanticError(message, errorCode);
    }

    /**
     * 消费一个泛型闭合的 {@code >}，兼容 {@code >>} / {@code >>>}。
     * <p>
     * Java 允许嵌套泛型用右移符代替连续的 {@code >}（如 {@code List<List<String>>}），但 Lexer 只会
     * 给出一个右移 token。多出来的 {@code >} 记在 {@link ParseContext} 上还给外层 —— 必须跨 parser
     * 实例共享，因为一个类型声明可能由多个实例接力解析（{@code class G<T extends Comparable<T>>}：
     * 类型参数表由 ClassBodyParser 解析，上界交给新的 TypeParser）。
     * </p>
     *
     * @param message 缺闭合符时的错误信息
     */
    protected void consumeGenericClose(String message) throws CythavaParseException {
        // 外层欠的 '>'：token 流里已经没有它了
        if (context.consumePendingAngleBracket()) {
            return;
        }
        if (match(TokenType.OPERATOR_GREATER_THAN)) {
            return;
        }
        if (match(TokenType.OPERATOR_RIGHT_SHIFT)) {
            // >> = 两个 >，消费一个，还一个
            context.addPendingAngleBrackets(1);
            return;
        }
        if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT)) {
            // >>> = 三个 >，消费一个，还两个
            context.addPendingAngleBrackets(2);
            return;
        }
        throw error(message, ErrorCode.PARSE_INVALID_TYPE);
    }

    // ==================== 编译期断言 ====================

    /** {@code @StaticAssert} 语法糖的注解名。 */
    protected static final String STATIC_ASSERT_ANNOTATION = "StaticAssert";

    /**
     * 校验 {@code static_assert} / {@code @StaticAssert} 的条件与消息。
     * <p>
     * 这是编译期语法糖，不产生任何 AST 节点：条件必须能折叠成布尔字面量
     * （{@code BinaryOp/UnaryOp/Ternary} 的纯字面量组合在构建 AST 时已被
     * {@code ConstantFolder} 折叠），为 {@code true} 时静默通过，为 {@code false}
     * 时报编译期错误（运行期零开销）。
     * </p>
     *
     * @param condition 条件表达式
     * @param message   可选的消息表达式；只支持字面量，用于拼进错误信息
     * @param location  报错位置
     * @throws CythavaParseException 条件不是布尔常量、或条件不成立
     */
    protected void verifyStaticAssert(ASTNode condition, ASTNode message, SourceLocation location)
            throws CythavaParseException {
        if (!(condition instanceof LiteralNode literal)) {
            throw semanticError("static_assert 的条件必须是编译期常量表达式",
                    ErrorCode.PARSE_INVALID_TYPE, location);
        }
        if (!(literal.getValue() instanceof Boolean held)) {
            throw semanticError("static_assert 的条件必须是布尔常量表达式",
                    ErrorCode.PARSE_INVALID_TYPE, location);
        }
        if (held) {
            return;
        }

        StringBuilder detail = new StringBuilder("static_assert 条件不成立");
        if (message instanceof LiteralNode literalMessage) {
            detail.append(": ").append(literalMessage.getValue());
        } else if (message != null) {
            detail.append("（消息必须是字面量才能写进编译期错误信息）");
        }
        throw semanticError(detail.toString(), ErrorCode.PARSE_INVALID_TYPE, location);
    }

    // ==================== 位置管理 ====================

    /** 获取当前位置。 */
    public int getPosition() {
        return position;
    }

    /** 设置当前位置（用于跨解析器同步）。 */
    public void setPosition(int pos) {
        this.position = pos;
    }

    // ==================== 回溯支持 ====================

    /** 将当前位置压入回溯栈。 */
    protected void savePosition() {
        if (stackTop >= MAX_SAVE_DEPTH) {
            throw new IllegalStateException("Position save stack overflow (depth=" + MAX_SAVE_DEPTH + ")");
        }
        savedPositions[stackTop++] = position;
    }

    /** 弹出回溯栈顶部位置并恢复到该位置。 */
    protected void restorePosition() {
        if (stackTop > 0) {
            position = savedPositions[--stackTop];
        } else {
            throw new IllegalStateException("Position save stack underflow");
        }
    }

    /** 弹出回溯栈顶部位置但不恢复（解析成功时使用）。 */
    protected void releasePosition() {
        if (stackTop > 0) {
            stackTop--;
        } else {
            throw new IllegalStateException("Position save stack underflow");
        }
    }

    /** 清空回溯栈（一般不需要手动调用）。 */
    protected void clearSavedPositions() {
        stackTop = 0;
    }

    // ==================== 错误处理 ====================

    /**
     * 在当前位置创建一个解析异常。
     *
     * @param message 错误描述
     * @return 不会返回，总是抛出异常（方便 throw 使用）
     */
    protected CythavaParseException error(String message) {
        return new CythavaParseException(message, createLocation());
    }

    /**
     * 创建带错误码的解析异常。
     *
     * @param message   错误描述
     * @param errorCode 错误码分类
     * @return 不会返回，总是抛出异常
     */
    protected CythavaParseException error(String message, ErrorCode errorCode) {
        // 类型/语义相关的错误码自动标记为语义异常，防止被 fallback 逻辑吞掉
        boolean isSemantic = errorCode == ErrorCode.PARSE_INVALID_TYPE;
        return new CythavaParseException(message, createLocation(), errorCode, isSemantic);
    }


    /**
     * 创建带错误码的，已经明确了语义错误的解析异常。
     *
     * @param message   错误描述
     * @param errorCode 错误码分类
     * @return 不会返回，总是抛出异常
     */
    protected CythavaParseException semanticError(String message, ErrorCode errorCode) {
        return new CythavaParseException(message, createLocation(), errorCode, true);
    }

    /**
     * 创建指定位置的语义错误异常。
     * <p>
     * 调用方持有比当前位置更精确的 token（如字段名 token）时使用。
     * </p>
     *
     * @param message   错误描述
     * @param errorCode 错误码分类
     * @param location  精确位置（null 时回退到当前位置）
     * @return 不会返回，总是抛出异常
     */
    protected CythavaParseException semanticError(String message, ErrorCode errorCode, SourceLocation location) {
        return new CythavaParseException(message,
                location != null ? location : createLocation(), errorCode, true);
    }

    /** 创建当前位置的 SourceLocation。 */
    protected SourceLocation createLocation() {
        if (isAtEnd()) {
            return tokens.get(tokens.size() - 1).location();
        }
        return peek().location();
    }

    // ==================== 错误恢复 ====================

    /**
     * 语句级错误恢复：跳到下一个语句同步点。
     * <p>
     * 同步点为当前嵌套层级上的 {@code ;} 之后；遇到本层块的 {@code }} 时停止（交回上层），
     * 不消费该 token，使外层循环能够正常结束。
     * </p>
     */
    protected void synchronizeToStatementBoundary() {
        int depth = 0;
        while (!isAtEnd()) {
            TokenType type = peek().type();
            if (type == TokenType.EOF) {
                return;
            }
            if (type == TokenType.DELIMITER_RIGHT_BRACE) {
                if (depth == 0) {
                    return; // 本层块的结束，保留给上层
                }
                depth--;
                advance();
                continue;
            }
            if (type == TokenType.DELIMITER_LEFT_BRACE) {
                depth++;
                advance();
                continue;
            }
            if (type == TokenType.DELIMITER_SEMICOLON && depth == 0) {
                advance();
                return;
            }
            advance();
        }
    }

    // ==================== 辅助判断方法 ====================

    /** 判断 token 类型是否为基本类型关键字。 */
    protected boolean isPrimitiveTypeKeyword(TokenType type) {
        return type == TokenType.KEYWORD_INT || type == TokenType.KEYWORD_LONG ||
               type == TokenType.KEYWORD_FLOAT || type == TokenType.KEYWORD_DOUBLE ||
               type == TokenType.KEYWORD_BOOLEAN || type == TokenType.KEYWORD_CHAR ||
               type == TokenType.KEYWORD_BYTE || type == TokenType.KEYWORD_SHORT ||
               type == TokenType.KEYWORD_VOID;
    }

    /** 判断当前 token 是否可能是类型的开头（标识符或基本类型关键字）。 */
    protected boolean isTypeStartToken() {
        return isTypeStartToken(peek().type());
    }

    /** 判断给定 token 类型是否可能是一个类型的开头。 */
    protected boolean isTypeStartToken(TokenType type) {
        return type == TokenType.IDENTIFIER || isPrimitiveTypeKeyword(type);
    }

    /**
     * 把源码中写的类型名解析成 {@link Class}（支持 {@code int[]} 这类数组维度）。
     *
     * @param typeName 源码中的类型原文
     * @return 解析结果；无法解析时返回 null
     */
    protected Class<?> resolveTypeName(String typeName) {
        if (typeName == null) {
            return null;
        }
        if (typeName.endsWith("[]")) {
            Class<?> component = resolveTypeName(typeName.substring(0, typeName.length() - 2));
            return component == null ? null : Array.newInstance(component, 0).getClass();
        }
        return context.resolveClass(typeName);
    }
}
