package com.justnothing.engine.lexer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Token类型枚举
 * <p>
 * 定义脚本语言中所有可能的token类型。
 * </p>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public enum TokenType {

    IDENTIFIER("identifier"),

    // ========== 关键字 ==========
    KEYWORD_INT(Keywords.INT),
    KEYWORD_LONG(Keywords.LONG),
    KEYWORD_FLOAT(Keywords.FLOAT),
    KEYWORD_DOUBLE(Keywords.DOUBLE),
    KEYWORD_BOOLEAN(Keywords.BOOLEAN),
    KEYWORD_CHAR(Keywords.CHAR),
    KEYWORD_BYTE(Keywords.BYTE),
    KEYWORD_SHORT(Keywords.SHORT),
    KEYWORD_VOID(Keywords.VOID),
    KEYWORD_AUTO(Keywords.AUTO),
    KEYWORD_VAR(Keywords.VAR),
    KEYWORD_IF(Keywords.IF),
    KEYWORD_ELSE(Keywords.ELSE),
    KEYWORD_FOR(Keywords.FOR),
    KEYWORD_WHILE(Keywords.WHILE),
    KEYWORD_DO(Keywords.DO),
    KEYWORD_RETURN(Keywords.RETURN),
    KEYWORD_BREAK(Keywords.BREAK),
    KEYWORD_CONTINUE(Keywords.CONTINUE),
    KEYWORD_TRUE(Keywords.TRUE),
    KEYWORD_FALSE(Keywords.FALSE),
    KEYWORD_NULL(Keywords.NULL),
    KEYWORD_FINAL(Keywords.FINAL),
    KEYWORD_NEW(Keywords.NEW),
    KEYWORD_INSTANCEOF(Keywords.INSTANCEOF),
    KEYWORD_SWITCH(Keywords.SWITCH),
    KEYWORD_CASE(Keywords.CASE),
    KEYWORD_DEFAULT(Keywords.DEFAULT),
    KEYWORD_TRY(Keywords.TRY),
    KEYWORD_CATCH(Keywords.CATCH),
    KEYWORD_FINALLY(Keywords.FINALLY),
    KEYWORD_THROW(Keywords.THROW),
    KEYWORD_THROWS(Keywords.THROWS),
    KEYWORD_IMPORT(Keywords.IMPORT),
    KEYWORD_DELETE(Keywords.DELETE),
    KEYWORD_CLASS(Keywords.CLASS),
    KEYWORD_INTERFACE(Keywords.INTERFACE),
    KEYWORD_EXTENDS(Keywords.EXTENDS),
    KEYWORD_IMPLEMENTS(Keywords.IMPLEMENTS),
    KEYWORD_FUNCTION(Keywords.FUNCTION),
    KEYWORD_OPERATOR(Keywords.OPERATOR),
    KEYWORD_PACKAGE(Keywords.PACKAGE),
    KEYWORD_ENUM(Keywords.ENUM),
    KEYWORD_PUBLIC(Keywords.PUBLIC),
    KEYWORD_PRIVATE(Keywords.PRIVATE),
    KEYWORD_PROTECTED(Keywords.PROTECTED),
    KEYWORD_STATIC(Keywords.STATIC),
    KEYWORD_ABSTRACT(Keywords.ABSTRACT),
    KEYWORD_NATIVE(Keywords.NATIVE),
    KEYWORD_SYNCHRONIZED(Keywords.SYNCHRONIZED),
    KEYWORD_SUPER(Keywords.SUPER),
    KEYWORD_THIS(Keywords.THIS),
    KEYWORD_ASYNC(Keywords.ASYNC),
    KEYWORD_AWAIT(Keywords.AWAIT),
    KEYWORD_USING(Keywords.USING),

    // ========== 运算符 ==========
    OPERATOR_ASSIGN("="),
    OPERATOR_PLUS_ASSIGN("+="),
    OPERATOR_MINUS_ASSIGN("-="),
    OPERATOR_MULTIPLY_ASSIGN("*="),
    OPERATOR_DIVIDE_ASSIGN("/="),
    OPERATOR_MODULO_ASSIGN("%="),
    OPERATOR_BITWISE_AND_ASSIGN("&="),
    OPERATOR_BITWISE_OR_ASSIGN("|="),
    OPERATOR_BITWISE_XOR_ASSIGN("^="),
    OPERATOR_LEFT_SHIFT_ASSIGN("<<="),
    OPERATOR_RIGHT_SHIFT_ASSIGN(">>="),
    OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN(">>>="),
    OPERATOR_EQUAL("=="),
    OPERATOR_NOT_EQUAL("!="),
    OPERATOR_LESS_THAN("<"),
    OPERATOR_LESS_THAN_OR_EQUAL("<="),
    OPERATOR_GREATER_THAN(">"),
    OPERATOR_GREATER_THAN_OR_EQUAL(">="),
    OPERATOR_SPACESHIP("<=>"),
    OPERATOR_LOGICAL_AND("&&"),
    OPERATOR_LOGICAL_OR("||"),
    OPERATOR_LOGICAL_NOT("!"),
    OPERATOR_NOT_NULL("!!"),
    OPERATOR_BITWISE_AND("&"),
    OPERATOR_BITWISE_OR("|"),
    OPERATOR_BITWISE_XOR("^"),
    OPERATOR_BITWISE_NOT("~"),
    OPERATOR_LEFT_SHIFT("<<"),
    OPERATOR_RIGHT_SHIFT(">>"),
    OPERATOR_UNSIGNED_RIGHT_SHIFT(">>>"),
    OPERATOR_PLUS("+"),
    OPERATOR_MINUS("-"),
    OPERATOR_MULTIPLY("*"),
    OPERATOR_DIVIDE("/"),
    OPERATOR_MODULO("%"),
    OPERATOR_POWER("**"),
    OPERATOR_INT_DIVIDE("//"),
    OPERATOR_MATH_MODULO("%%"),
    OPERATOR_RANGE(".."),
    OPERATOR_RANGE_EXCLUSIVE("..<"),
    OPERATOR_INCREMENT("++"),
    OPERATOR_DECREMENT("--"),
    OPERATOR_DOT("."),
    OPERATOR_SAFE_DOT("?."),
    OPERATOR_DOUBLE_COLON("::"),
    OPERATOR_QUESTION("?"),
    OPERATOR_COLON(":"),
    OPERATOR_DECLARE_ASSIGN(":="),
    OPERATOR_CONDITIONAL_ASSIGN("?="),
    OPERATOR_NULL_COALESCING_ASSIGN("??="),
    OPERATOR_NULL_COALESCING("??"),
    OPERATOR_ELVIS("?:"),
    OPERATOR_PIPELINE("|>"),

    // ========== 分隔符 ==========
    DELIMITER_SEMICOLON(";"),
    DELIMITER_COMMA(","),
    DELIMITER_DOT("."),
    DELIMITER_LEFT_PAREN("("),
    DELIMITER_RIGHT_PAREN(")"),
    DELIMITER_LEFT_BRACE("{"),
    DELIMITER_RIGHT_BRACE("}"),
    DELIMITER_LEFT_BRACKET("["),
    DELIMITER_RIGHT_BRACKET("]"),
    DELIMITER_ARROW("->"),
    DELIMITER_AT("@"),

    // ========== 字面量 ==========
    LITERAL_INTEGER("integer literal"),
    LITERAL_LONG("long literal"),
    LITERAL_DECIMAL("decimal literal"),
    LITERAL_STRING("string literal"),
    LITERAL_INTERPOLATED_STRING("interpolated string literal"),
    LITERAL_MULTI_LINE_STRING("multi-line string literal"),
    LITERAL_MULTI_LINE_INTERPOLATED_STRING("multi-line interpolated string literal"),
    LITERAL_CHAR("char literal"),
    LITERAL_BOOLEAN("boolean literal"),
    LITERAL_NULL("null literal"),

    // ========== 特殊 ==========
    COMMENT("comment"),
    EOF("end of file"),
    UNKNOWN("unknown token");

    private final String description;

    TokenType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isKeyword() {
        return this.name().startsWith("KEYWORD_");
    }

    public boolean isOperator() {
        return this.name().startsWith("OPERATOR_");
    }

    public boolean isDelimiter() {
        return this.name().startsWith("DELIMITER_");
    }

    public boolean isLiteral() {
        return this.name().startsWith("LITERAL_");
    }

    private static final Set<String> KEYWORDS = new HashSet<>(
            Arrays.asList(
                    Keywords.INT, Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE,
                    Keywords.BOOLEAN, Keywords.CHAR, Keywords.BYTE, Keywords.SHORT,
                    Keywords.VOID, Keywords.AUTO,
                    Keywords.IF, Keywords.ELSE, Keywords.FOR, Keywords.WHILE, Keywords.DO,
                    Keywords.RETURN, Keywords.BREAK, Keywords.CONTINUE,
                    Keywords.TRUE, Keywords.FALSE, Keywords.NULL,
                    Keywords.FINAL, Keywords.NEW, Keywords.INSTANCEOF,
                    Keywords.SWITCH, Keywords.CASE, Keywords.DEFAULT,
                    Keywords.TRY, Keywords.CATCH, Keywords.FINALLY,
                    Keywords.THROW, Keywords.THROWS,
                    Keywords.IMPORT, Keywords.DELETE,
                    Keywords.CLASS, Keywords.INTERFACE, Keywords.EXTENDS, Keywords.IMPLEMENTS,
                    Keywords.PUBLIC, Keywords.PRIVATE, Keywords.PROTECTED,
                    Keywords.STATIC, Keywords.ABSTRACT, Keywords.NATIVE, Keywords.SYNCHRONIZED,
                    Keywords.SUPER, Keywords.THIS,
                    Keywords.ASYNC, Keywords.AWAIT, Keywords.USING,
                    Keywords.PACKAGE, Keywords.ENUM
            )
    );

    public static boolean isKeyword(String text) {
        return KEYWORDS.contains(text);
    }

    public static TokenType getKeywordType(String text) {
        return switch (text) {
            case Keywords.INT -> KEYWORD_INT;
            case Keywords.LONG -> KEYWORD_LONG;
            case Keywords.FLOAT -> KEYWORD_FLOAT;
            case Keywords.DOUBLE -> KEYWORD_DOUBLE;
            case Keywords.BOOLEAN -> KEYWORD_BOOLEAN;
            case Keywords.CHAR -> KEYWORD_CHAR;
            case Keywords.BYTE -> KEYWORD_BYTE;
            case Keywords.SHORT -> KEYWORD_SHORT;
            case Keywords.VOID -> KEYWORD_VOID;
            case Keywords.AUTO -> KEYWORD_AUTO;
            case Keywords.VAR -> KEYWORD_VAR;
            case Keywords.IF -> KEYWORD_IF;
            case Keywords.ELSE -> KEYWORD_ELSE;
            case Keywords.FOR -> KEYWORD_FOR;
            case Keywords.WHILE -> KEYWORD_WHILE;
            case Keywords.DO -> KEYWORD_DO;
            case Keywords.RETURN -> KEYWORD_RETURN;
            case Keywords.BREAK -> KEYWORD_BREAK;
            case Keywords.CONTINUE -> KEYWORD_CONTINUE;
            case Keywords.TRUE -> KEYWORD_TRUE;
            case Keywords.FALSE -> KEYWORD_FALSE;
            case Keywords.NULL -> KEYWORD_NULL;
            case Keywords.FINAL -> KEYWORD_FINAL;
            case Keywords.NEW -> KEYWORD_NEW;
            case Keywords.INSTANCEOF -> KEYWORD_INSTANCEOF;
            case Keywords.SWITCH -> KEYWORD_SWITCH;
            case Keywords.CASE -> KEYWORD_CASE;
            case Keywords.DEFAULT -> KEYWORD_DEFAULT;
            case Keywords.TRY -> KEYWORD_TRY;
            case Keywords.CATCH -> KEYWORD_CATCH;
            case Keywords.FINALLY -> KEYWORD_FINALLY;
            case Keywords.THROW -> KEYWORD_THROW;
            case Keywords.THROWS -> KEYWORD_THROWS;
            case Keywords.IMPORT -> KEYWORD_IMPORT;
            case Keywords.DELETE -> KEYWORD_DELETE;
            case Keywords.CLASS -> KEYWORD_CLASS;
            case Keywords.INTERFACE -> KEYWORD_INTERFACE;
            case Keywords.EXTENDS -> KEYWORD_EXTENDS;
            case Keywords.IMPLEMENTS -> KEYWORD_IMPLEMENTS;
            case Keywords.PUBLIC -> KEYWORD_PUBLIC;
            case Keywords.PRIVATE -> KEYWORD_PRIVATE;
            case Keywords.PROTECTED -> KEYWORD_PROTECTED;
            case Keywords.STATIC -> KEYWORD_STATIC;
            case Keywords.ABSTRACT -> KEYWORD_ABSTRACT;
            case Keywords.NATIVE -> KEYWORD_NATIVE;
            case Keywords.SYNCHRONIZED -> KEYWORD_SYNCHRONIZED;
            case Keywords.SUPER -> KEYWORD_SUPER;
            case Keywords.THIS -> KEYWORD_THIS;
            case Keywords.ASYNC -> KEYWORD_ASYNC;
            case Keywords.AWAIT -> KEYWORD_AWAIT;
            case Keywords.USING -> KEYWORD_USING;
            case Keywords.FUNCTION -> KEYWORD_FUNCTION;
            case Keywords.OPERATOR -> KEYWORD_OPERATOR;
            case Keywords.PACKAGE -> KEYWORD_PACKAGE;
            case Keywords.ENUM -> KEYWORD_ENUM;
            default -> IDENTIFIER;
        };
    }
}
