package com.justnothing.engine.exception;


/**
 * 错误代码枚举
 * <p>
 * 定义所有可能的错误类型，便于错误分类和处理。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public enum ErrorCode {
    
    // 词法错误 (LEXICAL_xxx)
    LEXICAL_ERROR("LEXICAL_001", "Lexical error"),
    LEXICAL_UNEXPECTED_CHARACTER("LEXICAL_002", "Unexpected character"),
    LEXICAL_UNTERMINATED_STRING("LEXICAL_003", "Unterminated string literal"),
    LEXICAL_INVALID_NUMBER("LEXICAL_004", "Invalid number format"),
    LEXICAL_INVALID_ESCAPE_SEQUENCE("LEXICAL_005", "Invalid escape sequence"),
    
    // 解析错误 (PARSE_xxx)
    PARSE_UNEXPECTED_TOKEN("PARSE_001", "Unexpected token"),
    PARSE_INVALID_SYNTAX("PARSE_002", "Invalid syntax"),
    PARSE_UNTERMINATED_STRING("PARSE_003", "Unterminated string literal"),
    PARSE_INVALID_NUMBER("PARSE_004", "Invalid number format"),
    PARSE_INVALID_ESCAPE_SEQUENCE("PARSE_005", "Invalid escape sequence"),
    PARSE_DUPLICATE_PARAMETER("PARSE_006", "Duplicate parameter name in lambda"),
    PARSE_INVALID_IDENTIFIER("PARSE_007", "Invalid identifier"),
    PARSE_MISSING_CLOSING_PARENTHESIS("PARSE_008", "Missing closing parenthesis"),
    PARSE_MISSING_CLOSING_BRACE("PARSE_009", "Missing closing brace"),
    PARSE_MISSING_CLOSING_BRACKET("PARSE_010", "Missing closing bracket"),
    PARSE_INVALID_TYPE("PARSE_011", "Invalid type"),
    PARSE_INVALID_CAST("PARSE_012", "Invalid cast"),
    PARSE_INVALID_METHOD_REFERENCE("PARSE_013", "Invalid method reference"),
    PARSE_CLASS_NOT_FOUND("PARSE_014", "Class not found"),
    
    // 求值错误 (EVAL_xxx)
    EVAL_ERROR("EVAL_000", "Evaluation error"),
    EVAL_UNDEFINED_VARIABLE("EVAL_001", "Undefined variable"),
    EVAL_TYPE_MISMATCH("EVAL_002", "Type mismatch"),
    EVAL_DIVISION_BY_ZERO("EVAL_003", "Division by zero"),
    EVAL_NULL_POINTER("EVAL_004", "Null pointer exception"),
    EVAL_INDEX_OUT_OF_BOUNDS("EVAL_005", "Index out of bounds"),
    EVAL_INVALID_OPERATION("EVAL_006", "Invalid operation"),
    EVAL_CONSTRUCTOR_INVOCATION_FAILED("EVAL_007", "Constructor invocation failed"),
    EVAL_FIELD_ACCESS_FAILED("EVAL_008", "Field access failed"),
    EVAL_ARRAY_ACCESS_FAILED("EVAL_009", "Array access failed"),
    EVAL_ASSIGNMENT_FAILED("EVAL_010", "Assignment failed"),
    EVAL_LOOP_LIMIT_EXCEEDED("EVAL_011", "Loop limit exceeded"),
    EVAL_STACK_OVERFLOW("EVAL_012", "Stack overflow"),
    EVAL_CLASS_NOT_FOUND("EVAL_013", "Class not found"),
    EVAL_EXCEPTION_THROWN("EVAL_014", "Exception thrown"),
    EVAL_PERMISSION_DENIED("EVAL_015", "Permission denied"),
    
    // 作用域错误 (SCOPE_xxx)
    SCOPE_VARIABLE_ALREADY_DECLARED("SCOPE_001", "Variable already declared in current scope"),
    SCOPE_CANNOT_ASSIGN_TO_FINAL("SCOPE_002", "Cannot assign to final variable"),
    SCOPE_VARIABLE_NOT_FOUND("SCOPE_003", "Variable not found"),
    
    // 方法解析错误 (METHOD_xxx)
    METHOD_NOT_FOUND("METHOD_001", "Method not found"),
    METHOD_AMBIGUOUS_CALL("METHOD_002", "Ambiguous method call"),
    METHOD_NO_APPLICABLE_METHOD("METHOD_003", "No applicable method found"),
    METHOD_INVALID_ARGUMENTS("METHOD_004", "Invalid arguments for method"),
    METHOD_INVOCATION_TARGET_NULL("METHOD_005", "Method invocation target is null"),
    METHOD_INVOCATION_FAILED("METHOD_006", "Method invocation failed");
    
    private final String code;
    private final String description;
    
    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return code + ": " + description;
    }
}
