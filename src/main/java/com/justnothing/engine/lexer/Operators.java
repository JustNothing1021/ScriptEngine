package com.justnothing.engine.lexer;

import java.util.Set;

public final class Operators {
    private Operators() {}

    public static final String ADD = "+";
    public static final String SUBTRACT = "-";
    public static final String MULTIPLY = "*";
    public static final String DIVIDE = "/";
    public static final String MODULO = "%";
    public static final String POWER = "**";
    public static final String INT_DIVIDE = "//";
    public static final String MATH_MODULO = "%%";

    public static final String RANGE = "..";
    public static final String RANGE_EXCLUSIVE = "..<";

    public static final String EQUAL = "==";
    public static final String NOT_EQUAL = "!=";
    public static final String LESS_THAN = "<";
    public static final String LESS_THAN_OR_EQUAL = "<=";
    public static final String GREATER_THAN = ">";
    public static final String GREATER_THAN_OR_EQUAL = ">=";
    public static final String SPACESHIP = "<=>";

    public static final String LOGICAL_AND = "&&";
    public static final String LOGICAL_OR = "||";

    public static final String BITWISE_AND = "&";
    public static final String BITWISE_OR = "|";
    public static final String BITWISE_XOR = "^";
    public static final String LEFT_SHIFT = "<<";
    public static final String RIGHT_SHIFT = ">>";
    public static final String UNSIGNED_RIGHT_SHIFT = ">>>";

    public static final String NULL_COALESCING = "??";
    public static final String ELVIS = "?:";

    public static final String NOT = "!";
    public static final String BITWISE_NOT = "~";
    public static final String INCREMENT = "++";
    public static final String DECREMENT = "--";

    public static final Set<String> BINARY_OPERATORS = Set.of(
            ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO,
            POWER, INT_DIVIDE, MATH_MODULO,
            RANGE, RANGE_EXCLUSIVE,
            EQUAL, NOT_EQUAL, LESS_THAN, GREATER_THAN,
            LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL, SPACESHIP,
            BITWISE_AND, BITWISE_OR, BITWISE_XOR,
            LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT,
            LOGICAL_AND, LOGICAL_OR
    );

    public static final Set<String> UNARY_OPERATORS = Set.of(
            ADD, SUBTRACT, NOT, BITWISE_NOT, INCREMENT, DECREMENT
    );
}
