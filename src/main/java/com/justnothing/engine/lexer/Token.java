package com.justnothing.engine.lexer;

import com.justnothing.engine.ast.SourceLocation;

import java.util.Locale;
import java.util.Objects;

/**
 * Token类
 * <p>
 * 表示词法分析产生的token，包含类型、值和位置信息。
 * </p>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public record Token(TokenType type, String text, Object value, SourceLocation location) {

    public Token(TokenType type, String text, SourceLocation location) {
        this(type, text, text, location);
    }

    public Token(TokenType type, Object value, SourceLocation location) {
        this(type, value != null ? value.toString() : inferOperatorText(type), value, location);
    }

    public Token(TokenType type, SourceLocation location) {
        this(type, inferOperatorText(type), null, location);
    }

    /**
     * 根据 TokenType 推断操作符/分隔符的默认文字表示。
     * 用于 {@code Token(type, location)} 简写构造函数，确保所有 token 的 text 非 null。
     */
    private static String inferOperatorText(TokenType type) {
        return switch (type) {
            // 算术运算符
            case OPERATOR_PLUS -> "+";
            case OPERATOR_MINUS -> "-";
            case OPERATOR_MULTIPLY -> "*";
            case OPERATOR_DIVIDE -> "/";
            case OPERATOR_MODULO -> "%";
            // 比较运算符
            case OPERATOR_LESS_THAN -> "<";
            case OPERATOR_GREATER_THAN -> ">";
            case OPERATOR_LESS_THAN_OR_EQUAL -> "<=";
            case OPERATOR_GREATER_THAN_OR_EQUAL -> ">=";
            case OPERATOR_EQUAL -> "==";
            case OPERATOR_NOT_EQUAL -> "!=";
            case OPERATOR_SPACESHIP -> "<=>";
            // 逻辑运算符
            case OPERATOR_LOGICAL_NOT -> "!";
            case OPERATOR_LOGICAL_AND -> "&&";
            case OPERATOR_LOGICAL_OR -> "||";
            case OPERATOR_NOT_NULL -> "!!";
            // 位运算符
            case OPERATOR_BITWISE_AND -> "&";
            case OPERATOR_BITWISE_OR -> "|";
            case OPERATOR_BITWISE_XOR -> "^";
            case OPERATOR_BITWISE_NOT -> "~";
            case OPERATOR_LEFT_SHIFT -> "<<";
            case OPERATOR_RIGHT_SHIFT -> ">>";
            case OPERATOR_UNSIGNED_RIGHT_SHIFT -> ">>>";
            // 赋值运算符
            case OPERATOR_ASSIGN -> "=";
            case OPERATOR_PLUS_ASSIGN -> "+=";
            case OPERATOR_MINUS_ASSIGN -> "-=";
            case OPERATOR_MULTIPLY_ASSIGN -> "*=";
            case OPERATOR_DIVIDE_ASSIGN -> "/=";
            case OPERATOR_MODULO_ASSIGN -> "%=";
            case OPERATOR_BITWISE_AND_ASSIGN -> "&=";
            case OPERATOR_BITWISE_OR_ASSIGN -> "|=";
            case OPERATOR_BITWISE_XOR_ASSIGN -> "^=";
            case OPERATOR_LEFT_SHIFT_ASSIGN -> "<<=";
            case OPERATOR_RIGHT_SHIFT_ASSIGN -> ">>=";
            case OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN -> ">>>=";
            // 特殊操作符
            case OPERATOR_PIPELINE -> "|>";
            case OPERATOR_DOT -> ".";
            // 注意：OPERATOR_ARROW(=>), OPERATOR_DOUBLE_DOT(..), OPERATOR_ELVIS(?:),
            // OPERATOR_SAFE_ACCESS(?.), OPERATOR_SPREAD(...), DELIMITER_COLON(:),
            // AT(@) 与 Java 语法冲突，无法作为 switch case 标签；通过 default 分支的兜底映射处理
            // 分隔符
            case DELIMITER_LEFT_PAREN -> "(";
            case DELIMITER_RIGHT_PAREN -> ")";
            case DELIMITER_LEFT_BRACKET -> "[";
            case DELIMITER_RIGHT_BRACKET -> "]";
            case DELIMITER_LEFT_BRACE -> "{";
            case DELIMITER_RIGHT_BRACE -> "}";
            case DELIMITER_COMMA -> ",";
            case DELIMITER_SEMICOLON -> ";";
            // 默认返回 null（关键字、字面量等不会走此路径）
            default -> null;
        };
    }

    public boolean is(TokenType type) {
        return this.type == type;
    }

    public boolean isKeyword() {
        return type.isKeyword();
    }

    public boolean isOperator() {
        return type.isOperator();
    }

    public boolean isDelimiter() {
        return type.isDelimiter();
    }

    public boolean isLiteral() {
        return type.isLiteral();
    }

    public boolean isEOF() {
        return type == TokenType.EOF;
    }

    @Override
    public String toString() {
        if (value != null && !value.equals(text)) {
            return String.format(
                    Locale.getDefault(),
                    "Token[%s, %s, value=%s, line=%d, col=%d]",
                    type, text, value, location.getLine(), location.getColumn());
        }
        return String.format(
                Locale.getDefault(),
                "Token[%s, %s, line=%d, col=%d]",
                type, text, location.getLine(), location.getColumn());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Token token = (Token) obj;
        return type == token.type &&
                (Objects.equals(text, token.text)) &&
                (Objects.equals(value, token.value));
    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (text != null ? text.hashCode() : 0);
        result = 31 * result + (value != null ? value.hashCode() : 0);
        return result;
    }
}
