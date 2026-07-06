package com.justnothing.engine.parser;

import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

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
        return tokens.get(position++);
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

    /** 创建当前位置的 SourceLocation。 */
    protected SourceLocation createLocation() {
        if (isAtEnd()) {
            return tokens.get(tokens.size() - 1).location();
        }
        return peek().location();
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
}
