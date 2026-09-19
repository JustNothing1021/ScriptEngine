package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.parser.ParseContext.VariableSymbol;

import java.util.ArrayList;
import java.util.List;

/**
 * Cythava 语句解析器。
 * <p>
 * 解析以下类型的 Java/Cythava 语句：
 * <ul>
 *   <li>表达式语句（赋值、方法调用等）</li>
 *   <li>块语句、空语句、标签语句</li>
 *   <li>Cythava 扩展: async/await</li>
 * </ul>
 * </p>
 *
 * @see ExprParser
 */
public class StmtParser extends ControlFlowStatementParser {

    /**
     * 构造器。
     *
     * @param tokens   token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public StmtParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 入口 ====================

    /**
     * 解析单条语句。
     *
     * @return AST 节点表示的语句
     * @throws CythavaParseException 语法错误
     */
    public ASTNode parseNextStatement() throws CythavaParseException {
        return parseStatementInternal();
    }

    // ==================== 内部分发 ====================

    @Override
    protected ASTNode parseStatementInternal() throws CythavaParseException {
        // --- 关键字开头 ---
        if (match(TokenType.KEYWORD_IF)) {
            return parseIfStatement();
        }
        if (match(TokenType.KEYWORD_WHILE)) {
            return parseWhileStatement();
        }
        if (match(TokenType.KEYWORD_DO)) {
            return parseDoWhileStatement();
        }
        if (match(TokenType.KEYWORD_FOR)) {
            return parseForStatement();
        }
        if (match(TokenType.KEYWORD_RETURN)) {
            return parseReturnStatement();
        }
        if (match(TokenType.KEYWORD_BREAK)) {
            return parseBreakStatement();
        }
        if (match(TokenType.KEYWORD_CONTINUE)) {
            return parseContinueStatement();
        }
        if (match(TokenType.KEYWORD_THROW)) {
            return parseThrowStatement();
        }
        if (match(TokenType.KEYWORD_ASSERT)) {
            return parseAssertStatement();
        }
        if (match(TokenType.KEYWORD_STATIC_ASSERT)) {
            return parseStaticAssertStatement();
        }
        if (match(TokenType.KEYWORD_TRY)) {
            return parseTryStatement();
        }
        if (match(TokenType.KEYWORD_SWITCH)) {
            return parseSwitchStatement();
        }
        // synchronized 只在后面跟 '(' 时是语句，否则是修饰符（交给下面的 isModifierKeyword 分支）
        if (check(TokenType.KEYWORD_SYNCHRONIZED) && checkNext(TokenType.DELIMITER_LEFT_PAREN)) {
            advance();
            return parseSynchronizedStatement();
        }
        if (match(TokenType.KEYWORD_ASYNC)) {
            return parseAsyncStatement();
        }
        if (match(TokenType.KEYWORD_DELETE)) {
            return parseDeleteStatement();
        }
        if (match(TokenType.KEYWORD_AUTO)) {
            return parseAutoVariableDeclaration();
        }
        if (match(TokenType.KEYWORD_VAR)) {
            // var 等同于 auto：类型推断
            return parseAutoVariableDeclaration();
        }
        if (check(TokenType.IDENTIFIER) && Keywords.FUNCTION.equals(peek().text())) {
            advance();
            return parseFunctionDefinition();
        }
        // yield 是受限标识符：只有后面跟表达式（而不是 = ; . 等标识符用法）时才是 yield 语句
        if (isYieldStatementStart()) {
            SourceLocation location = createLocation();
            advance();
            return parseYieldStatement(location);
        }

        // --- 分隔符开头 ---
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            // 先尝试解析为块语句，若失败则回退让表达式解析器处理（map/array 字面量）
            // 这是试探性解析：必须关闭语句级错误恢复，否则块内错误会被恢复而不是抛出，
            // 调用方无法得知失败并回退到表达式解析
            savePosition();
            advance();
            boolean recovery = context.isErrorRecoveryEnabled();
            context.setErrorRecoveryEnabled(false);
            try {
                ASTNode block = parseBlockStatement();
                releasePosition();
                return block;
            } catch (CythavaParseException e) {
                if (e.isSemanticError()) throw e;
                restorePosition();
            } finally {
                context.setErrorRecoveryEnabled(recovery);
            }
        }

        // 空语句 '';'
        if (match(TokenType.DELIMITER_SEMICOLON)) {
            return new LiteralNode.Builder().value(null).type(void.class).location(createLocation()).build();
        }

        // --- 修饰符关键字开头: final int x 等 ---
        if (isModifierKeyword(peek().text())) {
            return parseModifierLocalVariableDeclaration();
        }

        // --- 注解开头: @Annotation [final] Type varName ... ---
        if (check(TokenType.DELIMITER_AT)) {
            return parseAnnotatedLocalVariableDeclaration();
        }

        // --- 标识符开头: 可能是标签、局部变量声明或表达式 ---
        if (check(TokenType.IDENTIFIER)) {
            return parseIdentifierStartStatement();
        }

        // --- 类型关键字开头: 可能是变量声明 ---
        if (isPrimitiveTypeKeyword(peek().type())) {
            return parseLocalVariableDeclaration(null, false);
        }

        // --- 其他: 当作表达式语句 ---
        return parseExpressionStatement();
    }

    // ==================== Async/Await ====================

    /** async 语句 */
    private ASTNode parseAsyncStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        ASTNode expression = parseStatementOrBlock();
        AsyncNode node = (AsyncNode) new AsyncNode.Builder().expression(expression).location(location).build();
        JType innerType = context.getType(expression);
        if (innerType != null) {
            context.setType(node, innerType);
        } else {
            annotate(node, Object.class);
        }
        return node;
    }

    private ASTNode parseDeleteStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        if (match(TokenType.OPERATOR_MULTIPLY)) {
            consume(TokenType.DELIMITER_SEMICOLON, "Expected ';' after delete *");
            return new DeleteNode.Builder().deleteAll(true).location(location).build();
        }
        Token name = consume(TokenType.IDENTIFIER, "Expected variable name after 'delete'");
        consume(TokenType.DELIMITER_SEMICOLON, "Expected ';' after delete statement");
        return new DeleteNode.Builder().variableName(name.text()).location(location).build();
    }


    // ==================== yield ====================

    /**
     * 当前位置是否是 {@code yield expr} 语句的开头。
     * <p>
     * {@code yield} 在 Java 里是受限标识符（只在语句位置且后面跟表达式时才是关键字），
     * 所以 {@code yield = 1;}、{@code yield;}、{@code yield.f()}、{@code yield + 1} 仍按普通标识符处理。
     * 判据取保守方向：只有后一个 token 明确开启一个表达式时才算 yield 语句。
     * </p>
     */
    static boolean isYieldStatementStart(List<Token> tokens, int pos) {
        if (pos + 1 >= tokens.size()) return false;
        if (tokens.get(pos).type() != TokenType.IDENTIFIER
                || !Keywords.YIELD.equals(tokens.get(pos).text())) {
            return false;
        }
        TokenType next = tokens.get(pos + 1).type();
        if (next == TokenType.DELIMITER_SEMICOLON || next == TokenType.DELIMITER_COMMA
                || next == TokenType.OPERATOR_ASSIGN || next == TokenType.OPERATOR_DOT) {
            return false;
        }
        // 除一元 !/~ 外，运算符意味着 yield 是它的左操作数（yield + 1 / yield <= x）
        return !next.isOperator()
                || next == TokenType.OPERATOR_LOGICAL_NOT
                || next == TokenType.OPERATOR_BITWISE_NOT;
    }

    private boolean isYieldStatementStart() {
        return isYieldStatementStart(tokens, position);
    }

    /** yield expr ; */
    private ASTNode parseYieldStatement(SourceLocation location) throws CythavaParseException {
        ASTNode value = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after yield value");
        return new YieldNode.Builder().value(value).location(location).build();
    }

    // ==================== 块与表达式语句 ====================

    /** { ... } 块语句 */
    private BlockNode parseBlockStatement() throws CythavaParseException {
        return parseBlock();
    }

    /** expression ; */
    private ASTNode parseExpressionStatement() throws CythavaParseException {
        ASTNode expression = parseExpr();
        consumeSemicolon();
        return expression; // 表达式语句直接返回表达式节点
    }

    // ==================== 标识符开头的语句 ====================

    /**
     * 标识符开头的情况:
     * - 标签: label:
     * - 局部变量声明: varName = expr 或 Type varName [= expr]
     * - 表达式语句: methodCall(), obj.field 等
     */
    private ASTNode parseIdentifierStartStatement() throws CythavaParseException {
        // 检查是否是标签: identifier :
        if (checkNext(TokenType.OPERATOR_COLON) && !checkNextIsLambdaArrow()) {
            return parseLabeledStatement();
        }

        // 可能是变量声明或表达式语句
        savePosition();
        String firstName = advance().text(); // 消费标识符

        // 检查是否是修饰符开头的变量声明: final / static 等
        if (isModifierKeyword(firstName)) {
            restorePosition();
            return parseModifierLocalVariableDeclaration();
        }

        // 检查是否是带类型标注的变量声明: Type varName ...
        // 贪心策略（参考老版 Parser.isQualifiedTypeVariableDeclaration）：
        //   从当前 position 向前扫描，不消费 token，判断是否符合
        //   "TypePart[.TypePart]* [<gen>] [[]]* varName" 模式
        boolean ltd = looksLikeTypeDeclaration();
        if (ltd && !check(TokenType.OPERATOR_ASSIGN)) {
            restorePosition();
            return parseLocalVariableDeclaration(null, false);
        }

        // 检查是否是简单变量声明或赋值: varName = expr ;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            releasePosition();
            SourceLocation location = createLocation();
            ASTNode value = parseExpr();

            if (context.isKnownVariable(firstName) || context.isFieldOfCurrentClass(firstName)) {
                // 已声明的变量 / 当前类的字段 → 赋值（非新声明）
                GenericType declaredType = context.getDeclaredType(firstName);
                if (declaredType != null) {
                    checkTypeCompatibility(firstName, declaredType, value);
                }
                consumeSemicolon();
                AssignmentNode assignNode = (AssignmentNode) new AssignmentNode.Builder().variableName(firstName).value(value).isDeclaration(false).declaredType(declaredType).isFinal(context.isFinal(firstName)).location(location).build();
                JType valueType = context.getType(value);
                if (valueType != null) {
                    context.setType(assignNode, valueType);
                }
                return assignNode;
            } else {
                // 新变量 → 声明（auto 类型推断）
                consumeSemicolon();
                context.declareVariable(firstName);
                // auto 变量：从初始化器推断类型并注册到符号表
                GenericType inferredType = context.getInferredType(value);
                if (inferredType != null) {
                    VariableSymbol sym = context.resolveVariable(firstName);
                    if (sym != null) sym.setDeclaredType(inferredType);
                }
                return new VarDeclNode.Builder().varName(firstName).declaredType(null).initializer(value).isFinal(false).annotations(null).location(location).build();
            }
        }

        // 检查复合赋值: varName += expr 等
        if (isCompoundAssignmentOperator()) {
            releasePosition();
            return parseCompoundAssignment(firstName);
        }

        // 不是特殊形式，回退让表达式解析器处理
        restorePosition();
        return parseExpressionStatement();
    }

    /** label: statement */
    private LabeledStatementNode parseLabeledStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        String label = advance().text(); // 标签名
        consumeOrSemanticError(TokenType.OPERATOR_COLON, "Expected ':' after label");
        ASTNode statement = parseStatementInternal();
        return (LabeledStatementNode) new LabeledStatementNode.Builder().label(label).statement(statement).location(location).build();
    }

    // ==================== 函数定义 ====================

    /**
     * 解析函数定义语句。
     * <p>
     * 支持两种语法：
     * <ol>
     *   <li>{@code function name(params) { body }} — 无返回类型声明</li>
     *   <li>{@code function returnType name(params) { body }} — 带返回类型声明</li>
     * </ol>
     *
     * @return FunctionDefNode
     */
    private FunctionDefNode parseFunctionDefinition() throws CythavaParseException {
        SourceLocation location = createLocation();

        // 可选的返回类型：尝试用 TypeParser 解析，成功则下一个 token 是函数名
        // 省略返回类型的形态 `function name(...)`：标识符紧跟 '(' → 直接进入函数名解析
        boolean looksLikeReturnType = !(check(TokenType.IDENTIFIER)
                && position + 1 < tokens.size()
                && tokens.get(position + 1).type() == TokenType.DELIMITER_LEFT_PAREN);
        ClassReferenceNode returnType = null;
        if (looksLikeReturnType && isTypeStart(peek()) && !check(TokenType.DELIMITER_LEFT_PAREN)) {
            // 保存位置：如果 TypeParser 解析失败或后面不是函数名，回退
            int savedPos = position;
            try {
                TypeParser typeParser = new TypeParser(tokens, context, fileName);
                typeParser.setPosition(position);
                GenericType gt = typeParser.parseType();
                // 解析成功后，下一个 token 必须是标识符（函数名）
                if (typeParser.getPosition() < tokens.size()
                        && tokens.get(typeParser.getPosition()).type() == TokenType.IDENTIFIER) {
                    position = typeParser.getPosition();
                    // GenericType.of(原始类型) 不设置 originalTypeName，需从 rawType 推导
                    String typeName = gt.getOriginalTypeName();
                    if (typeName == null && gt.getRawType() != null) {
                        typeName = gt.getRawType().getName();
                    }
                    returnType = new ClassReferenceNode.Builder()
                            .originalTypeName(typeName)
                            .resolvedClass(gt.getRawType())
                            .location(createLocation())
                            .build();
                } else {
                    // 类型解析成功但后面不是函数名 → 不是返回类型声明
                    position = savedPos;
                }
            } catch (CythavaParseException e) {
                // TypeParser 完全失败 → 回退到简单名称解析（兼容非泛型场景）
                position = savedPos;
                StringBuilder typeSb = new StringBuilder(advance().text());
                while (check(TokenType.OPERATOR_DOT) && position + 1 < tokens.size()
                        && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
                    typeSb.append(advance().text()); // .
                    typeSb.append(advance().text()); // 标识符
                }
                // 数组后缀
                while (check(TokenType.DELIMITER_LEFT_BRACKET)) {
                    consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in return type");
                    typeSb.append("[]");
                }
                returnType = new ClassReferenceNode.Builder()
                        .originalTypeName(typeSb.toString())
                        .resolvedClass(context.resolveClass(typeSb.toString()))
                        .location(createLocation())
                        .build();
            }
        }

        // 函数名
        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected function name after 'function'");
        String functionName = nameToken.text();

        // 参数列表
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after function name '" + functionName + "'");
        List<LambdaNode.Parameter> parameters = new ArrayList<>();
        while (!check(TokenType.DELIMITER_RIGHT_PAREN) && !isAtEnd()) {
            // 参数类型（可选）
            ClassReferenceNode paramType = null;
            if (isTypeStart(peek()) && !check(TokenType.IDENTIFIER)
                    || (isTypeStart(peek()) && check(TokenType.IDENTIFIER)
                    && position + 1 < tokens.size() && tokens.get(position + 1).type() == TokenType.IDENTIFIER)) {
                StringBuilder paramTypeSb = new StringBuilder(advance().text());
                while (check(TokenType.OPERATOR_DOT) && position + 1 < tokens.size()
                        && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
                    paramTypeSb.append(advance().text());
                    paramTypeSb.append(advance().text());
                }
                paramType = new ClassReferenceNode.Builder()
                        .originalTypeName(paramTypeSb.toString())
                        .resolvedClass(context.resolveClass(paramTypeSb.toString()))
                        .location(createLocation())
                        .build();
            }

            // 参数名
            String paramName = advance().text();
            Class<?> paramClazz = paramType != null ? paramType.getResolvedClass() : Object.class;
            parameters.add(new LambdaNode.Parameter(paramName, paramClazz));

            if (!match(TokenType.DELIMITER_COMMA)) break;
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after function parameter list");

        // 函数体：进入新作用域，注册参数和函数名（支持递归）
        // 同时在当前作用域注册函数名，使后续语句可引用
        context.declareVariable(functionName);
        context.enterScope(ParseContext.ScopeKind.METHOD);
        ASTNode body;
        try {
            context.declareVariable(functionName);
            for (LambdaNode.Parameter param : parameters) {
                context.declareVariable(param.name());
            }
            body = parseStatementOrBlock();
        } finally {
            // 解析失败时也要退出作用域，否则作用域栈不平衡会污染后续解析
            context.exitScope();
        }

        return (FunctionDefNode) new FunctionDefNode.Builder()
                .functionName(functionName)
                .returnType(returnType)
                .parameters(parameters)
                .body(body)
                .location(location)
                .build();
    }
}
