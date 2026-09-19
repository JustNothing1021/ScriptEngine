package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Cythava 声明解析器。
 * <p>
 * 解析顶层声明，包括：
 * <ul>
 *   <li>import 语句</li>
 *   <li>package 声明</li>
 *   <li>函数定义（顶层）</li>
 * </ul>
 * </p>
 */
public class DeclParser extends ClassBodyParser {

    /**
     * 构造器。
     *
     * @param tokens   token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public DeclParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 入口 ====================

    /**
     * 解析完整源文件的声明列表。
     *
     * @return 声明节点列表
     * @throws CythavaParseException 语法错误
     */
    public ASTNode parseNextCompilationUnit() throws CythavaParseException {

        // 可选的 package 声明
        if (check(TokenType.KEYWORD_PACKAGE)) {
            ASTNode result;
            result = parsePackageDeclaration();
            consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after package declaration");
            return result;
        }

        // import 列表
        if (!isAtEnd() && check(TokenType.KEYWORD_IMPORT)) {
            return parseImportDeclaration();
        }

        // 类型/函数声明
        if (!isAtEnd() && peek().type() != TokenType.EOF) {
            return parseTopLevelDeclaration();
        }

        return null;

    }

    // ==================== 顶层声明分发 ====================

    /** 解析单个顶层声明。 */
    private ASTNode parseTopLevelDeclaration() throws CythavaParseException {
        // using 声明（不需要注解/修饰符）
        if (check(TokenType.KEYWORD_USING)) {
            savePosition();
            advance();
            try {
                ASTNode result = parseUsingDeclaration();
                releasePosition();
                return result;
            } catch (CythavaParseException e) {
                restorePosition();
                if (e.getErrorCode() == ErrorCode.PARSE_CLASS_NOT_FOUND) {
                    throw error("Expected expression", ErrorCode.PARSE_UNEXPECTED_TOKEN);
                }
                throw e;
            }
        }

        // ★ var/auto 是语句级关键字（类型推断变量声明），不是顶层声明
        //   直接返回 null 让 Parser fallback 到 StmtParser
        if (check(TokenType.KEYWORD_VAR) || check(TokenType.KEYWORD_AUTO)) {
            throw error("var/auto are statement-level keywords, not declarations",
                    ErrorCode.PARSE_INVALID_SYNTAX);
        }

        // 注解
        List<AnnotationNode> annotations = tryParseAnnotations();

        // 修饰符 — 先保存位置，若最终不是顶层声明则恢复（如 final int x = 1 实际是语句）
        int beforeModifiers = position;
        ClassModifiers modifiers = parseModifiers();

        if (match(TokenType.KEYWORD_CLASS)) {
            return parseClassDeclaration(annotations, modifiers);
        }
        if (match(TokenType.KEYWORD_INTERFACE)) {
            return parseInterfaceDeclaration(annotations, modifiers);
        }
        if (check(TokenType.KEYWORD_ENUM)) {
            return parseEnumDeclaration();
        }
        if (isRecordDeclarationStart()) {
            advance(); // record
            return parseRecordDeclaration(annotations, modifiers);
        }
        if (check(TokenType.IDENTIFIER) || isPrimitiveTypeKeyword(peek().type())) {
            //   二次安全网：var/auto 不应在此处理（已在 parseTopLevelDeclaration 入口拦截）
            //   防止因注解/修饰符消费后位置变化导致漏过入口检查
            if (check(TokenType.KEYWORD_VAR) || check(TokenType.KEYWORD_AUTO)) {
                throw error("var/auto are statement-level keywords, not declarations",
                        ErrorCode.PARSE_INVALID_SYNTAX);
            }
            // function 关键字交给 StmtParser 处理（支持单语句体等特性）
            if (check(TokenType.IDENTIFIER) && Keywords.FUNCTION.equals(peek().text())) {
                throw error("function is a statement-level keyword, not a declaration",
                        ErrorCode.PARSE_INVALID_SYNTAX);
            }
            // ★ 便宜的前置判断：语句首标识符若已是已知变量或内置函数，则不可能是声明的类型名。
            //   直接交给语句解析器，避免把每个语句首标识符都当作类名去逐 import 前缀探测
            //   （实测 java.util.function.enemies / android.os.attacker 这类无意义探测都源自这里）。
            //   注意：判错也不会改变行为 —— 走 parseFunctionOrVariable 时最终同样会回退到
            //   parseStatement()（未知类型名时 parseTypeReference 得到 Object.class，不会构成声明）。
            if (check(TokenType.IDENTIFIER)
                    && (context.isKnownVariable(peek().text()) || context.isBuiltinFunction(peek().text()))) {
                throw error("statement starts with a known variable, not a declaration",
                        ErrorCode.PARSE_INVALID_SYNTAX);
            }
            return parseFunctionOrVariable(beforeModifiers);
        }

        // 独立注解声明（注解后无其他声明跟随）
        if (!annotations.isEmpty()) {
            return annotations.get(annotations.size() - 1);
        }

        // 消费过修饰符却没构成声明（如 synchronized (lock) { … } 其实是语句）：
        // 恢复到修饰符之前，使 Parser 的 fallback 判定看到"位置未前进"，
        // 从而把这段输入交给语句解析器，而不是当成声明报错。
        position = beforeModifiers;
        throw error("Expected declaration", ErrorCode.PARSE_UNEXPECTED_TOKEN);
    }

    // ==================== Package / Import ====================

    /** package name; */
    private ImportNode parsePackageDeclaration() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.KEYWORD_PACKAGE, "Expected 'package'");
        String name = parseQualifiedName();
        return (ImportNode) new ImportNode.Builder().packageName("package " + name).location(location).build();
    }

    /** import [static] name[.*]; */
    private ImportNode parseImportDeclaration() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.KEYWORD_IMPORT, "Expected 'import'");

        boolean isStatic = match(TokenType.KEYWORD_STATIC);

        StringBuilder name = new StringBuilder(consumeOrSemanticError(TokenType.IDENTIFIER,
                "Expected identifier after import").text());
        while (match(TokenType.OPERATOR_DOT)) {
            name.append('.');
            if (match(TokenType.OPERATOR_MULTIPLY)) {
                name.append('*');
                break;
            }
            name.append(consumeOrSemanticError(TokenType.IDENTIFIER,
                    "Expected identifier in import").text());
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after import");

        String importStr = "import " + (isStatic ? "static " : "") + name;
        return (ImportNode) new ImportNode.Builder().packageName(importStr).location(location).build();
    }

    // ==================== Using 声明 ====================

    /** 解析 using 声明：using static X.Y.Z; 或 using Alias = X.Y.Z; */
    private ASTNode parseUsingDeclaration() throws CythavaParseException {
        if (match(TokenType.KEYWORD_STATIC)) {
            return parseUsingStatic();
        }
        return parseUsingAlias();
    }

    /** using static Full.Qualified.ClassName ; */
    private UsingStaticNode parseUsingStatic() throws CythavaParseException {
        SourceLocation location = createLocation();

        StringBuilder className = new StringBuilder();
        while (check(TokenType.IDENTIFIER)) {
            className.append(advance().text());
            if (match(TokenType.OPERATOR_DOT)) {
                className.append('.');
            } else {
                break;
            }
        }

        if (className.length() == 0) {
            throw semanticError("Expected class name after 'using static'", ErrorCode.PARSE_INVALID_SYNTAX);
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after using static declaration");

        String classNameStr = className.toString();
        try {
            Class<?> clazz = context.resolveClass(classNameStr);
            if (clazz != null) {
                for (Class<?> nested : clazz.getClasses()) {
                    String simpleName = nested.getSimpleName();
                    if (!simpleName.isEmpty()) {
                        context.addImport(nested.getName().replace('$', '.'));
                    }
                }
            }
        } catch (Exception ignored) {
            // 类解析失败不影响 AST 构建
        }

        return (UsingStaticNode) new UsingStaticNode.Builder().className(classNameStr).location(location).build();
    }

    /** using Alias = Full.Qualified.TypeName ; */
    private UsingAliasNode parseUsingAlias() throws CythavaParseException {
        SourceLocation location = createLocation();

        String aliasName = consumeOrSemanticError(TokenType.IDENTIFIER,
                "Expected alias name after 'using'").text();
        consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "Expected '=' after alias name");

        StringBuilder fullTypeName = new StringBuilder();
        while (check(TokenType.IDENTIFIER)) {
            fullTypeName.append(advance().text());
            if (match(TokenType.OPERATOR_DOT)) {
                fullTypeName.append('.');
            } else {
                break;
            }
        }

        if (fullTypeName.length() == 0) {
            throw semanticError("Expected type name after '=' in using alias",
                    ErrorCode.PARSE_INVALID_SYNTAX);
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after using alias declaration");

        // 校验目标类是否存在
        String targetName = fullTypeName.toString();
        if (!context.isKnownClass(targetName)) {
            throw semanticError("Cannot resolve type '" + targetName + "' in using alias",
                    ErrorCode.PARSE_CLASS_NOT_FOUND);
        }

        // 注册别名到符号表（让后续解析能识别 HMap 等别名）
        context.addTypeAlias(aliasName, targetName);
        context.addImport(targetName);

        return (UsingAliasNode) new UsingAliasNode.Builder().aliasName(aliasName).fullClassName(targetName).location(location).build();
    }

    // ==================== 函数定义 ====================

    /** 解析顶层函数或变量声明。 */
    private ASTNode parseFunctionOrVariable(int beforeModifiers) throws CythavaParseException {
        SourceLocation location = createLocation();

        savePosition();
        ClassReferenceNode type;
        try {
            type = parseTypeReference();
        } catch (CythavaParseException e) {
            type = null;
        }

        if (type != null && (check(TokenType.IDENTIFIER) || isKeyword(Keywords.OPERATOR))) {
            Token funcToken = advance();
            String funcName = funcToken.text();
            if (Keywords.OPERATOR.equals(funcToken.text()) && !check(TokenType.DELIMITER_LEFT_PAREN)) {
                StringBuilder opBuilder = new StringBuilder("operator");
                while (!check(TokenType.DELIMITER_LEFT_PAREN)
                        && !check(TokenType.DELIMITER_COMMA)
                        && peek() != null
                        && isOperatorToken(peek().type())) {
                    opBuilder.append(advance().text());
                }
                funcName = opBuilder.toString();
            }

            if (check(TokenType.DELIMITER_LEFT_PAREN)) {
                consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after function name");
                List<LambdaNode.Parameter> params = parseLambdaParameters();
                consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after function parameters");

                ASTNode body = null;
                if (match(TokenType.DELIMITER_LEFT_BRACE)) {
                    body = parseBlock();
                } else {
                    consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' or '{' after function signature");
                }

                ASTNode funcNode = new FunctionDefNode.Builder().functionName(funcName).returnType(type).parameters(params).body(body).location(location).build();

                // ★ 运算符重载注册：顶层函数定义的 operator 方法也注册到 OperatorRegistry
                if (funcName.startsWith(Keywords.OPERATOR) && funcName.length() > Keywords.OPERATOR.length()) {
                    String opSymbol = funcName.substring(Keywords.OPERATOR.length());
                    if (OperatorRegistry.BINARY_OPERATORS.contains(opSymbol)) {
                        Class<?> retClass = type.getResolvedClass() != null
                                ? type.getResolvedClass() : Object.class;
                        List<Class<?>> paramTypes = new ArrayList<>();
                        for (var p : params) {
                            paramTypes.add(p.type() != null ? p.type() : Object.class);
                        }
                        if (paramTypes.size() == 2) {
                            context.getOperatorRegistry().registerBinary(
                                    opSymbol, paramTypes.get(0), paramTypes.get(1),
                                    retClass, funcNode);
                        } else if (paramTypes.size() == 1) {
                            context.getOperatorRegistry().registerUnary(
                                    opSymbol, paramTypes.get(0),
                                    retClass, funcNode);
                        }
                    }
                }

                releasePosition();
                return funcNode;
            }
        }

        restorePosition();  // 恍复到 parseTypeReference 之前的位置
        if (beforeModifiers >= 0) {
            position = beforeModifiers;  // 还要恍复到修饰符之前（修饰符在顶层被 parseModifiers 消耗了）
        }
        return parseStatement();
    }

    // ==================== 参数列表 ====================

    /** 解析 Lambda 风格参数列表（用于 FunctionDefNode）。 */
    private List<LambdaNode.Parameter> parseLambdaParameters() throws CythavaParseException {
        List<LambdaNode.Parameter> params = new ArrayList<>();
        if (check(TokenType.DELIMITER_RIGHT_PAREN)) {
            return params;
        }
        do {
            LambdaNode.Parameter param = parseLambdaParameter();
            for (LambdaNode.Parameter existing : params) {
                if (existing.name().equals(param.name())) {
                    throw semanticError("Duplicate parameter name '" + param.name() + "' in lambda",
                            ErrorCode.PARSE_DUPLICATE_PARAMETER);
                }
            }
            params.add(param);
        } while (match(TokenType.DELIMITER_COMMA));
        return params;
    }

    /** 单个 lambda 参数。 */
    private LambdaNode.Parameter parseLambdaParameter() throws CythavaParseException {
        savePosition();
        try {
            ClassReferenceNode type = parseTypeReference();
            if (check(TokenType.IDENTIFIER)) {
                String name = advance().text();
                releasePosition();
                return new LambdaNode.Parameter(name, refToClass(type));
            }
        } catch (CythavaParseException ignored) {
            // 不是类型+名称格式的参数，回退到简单标识符解析
        }
        restorePosition();

        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected parameter name").text();
        return new LambdaNode.Parameter(name, Object.class);
    }

    // ==================== 类型引用辅助 ====================

    /** 将基本类型名字符串映射为 Class 对象。 */

    private static Class<?> refToClass(ClassReferenceNode ref) {
        return ref.getResolvedClass();
    }
}
