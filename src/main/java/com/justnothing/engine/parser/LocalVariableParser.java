package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.util.MethodResolver;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cythava 语句解析层（局部变量声明）。
 * <p>
 * 解析以下类型的 Java/Cythava 语句：
 * <ul>
 *   <li>变量/字段声明</li>
 * </ul>
 * </p>
 *
 * @see BaseParser
 */
abstract class LocalVariableParser extends BaseParser {

    protected LocalVariableParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    protected abstract ASTNode parseStatementInternal() throws CythavaParseException;

    // ==================== 底层工具 ====================

    /**
     * 解析表达式（从当前位置创建新的 ExprParser 避免状态污染）。
     */
    protected ASTNode parseExpr() throws CythavaParseException {
        ExprParser freshParser = new ExprParser(tokens, context, fileName);
        freshParser.setPosition(position);
        try {
            return freshParser.parseNextExpression();
        } finally {
            // 失败时也要同步位置，否则外层会重复解析同一段输入
            position = freshParser.getPosition();
        }
    }

    /**
     * 解析语句列表（直到遇到 } 或 EOF）。
     *
     * @return 语句节点列表
     * @throws CythavaParseException 语法错误
     */
    public List<ASTNode> parseBlockBody() throws CythavaParseException {
        List<ASTNode> statements = new ArrayList<>();
        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            // 跳过可能的空语句（连续分号）
            if (match(TokenType.DELIMITER_SEMICOLON)) {
                continue;
            }
            int startPos = position;
            try {
                statements.add(parseStatementInternal());
            } catch (CythavaParseException e) {
                // 语句级错误恢复：记录错误后跳到下一个语句同步点继续解析，
                // 以便一次性报告块内所有错误（而不是遇到第一个错误就中止）
                if (!context.isErrorRecoveryEnabled()) {
                    throw e;
                }
                context.reportError(e);
                synchronizeToStatementBoundary();
                if (position == startPos) {
                    advance(); // 保证前进，避免死循环
                }
            }
        }
        return statements;
    }

    /** 解析块 { statements } */
    public BlockNode parseBlock() throws CythavaParseException {
        SourceLocation location = createLocation();
        context.enterScope(ParseContext.ScopeKind.BLOCK);
        try {
            List<ASTNode> statements = parseBlockBody();
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after block");
            return (BlockNode) new BlockNode.Builder().statements(statements).location(location).build();
        } finally {
            // 解析失败时也要退出作用域，否则作用域栈不平衡会污染后续解析
            context.exitScope();
        }
    }

    /**
     * ASI (Automatic Semicolon Insertion): 自动分号插入
     * 如果下一个 token 是分号就吃掉，如果是 EOF 或流结束也允许省略。
     */
    protected void consumeSemicolon() throws CythavaParseException {
        if (match(TokenType.DELIMITER_SEMICOLON)) {
            return;
        }
        if (!check(TokenType.EOF)) {
            throw error("Expected ';' after statement", ErrorCode.PARSE_UNEXPECTED_TOKEN);
        }
    }

    /** 解析类型名称（用于 catch 子句）。 */
    protected Class<?> parseTypeName() throws CythavaParseException {
        StringBuilder sb = new StringBuilder(advance().text()); // 第一个标识符或基本类型

        while (match(TokenType.OPERATOR_DOT)) {
            sb.append('.').append(advance().text());
        }

        // 数组后缀
        int dimensions = 0;
        while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in array type");
            sb.append("[]");
            dimensions++;
        }

        Class<?> baseType = context.resolveClass(sb.toString());
        // 按照数组维度创建对应的数组类型
        if (dimensions > 0 && baseType != null) {
            return Array.newInstance(baseType, new int[dimensions]).getClass();
        }
        return baseType;
    }

    /** 判断 token 是否可能作为类型声明的起始（基本类型关键字或标识符/类名） */
    protected boolean isTypeStart(Token token) {
        return token != null && isTypeStartToken(token.type());
    }

    /** 判断 token 类型是否为关键字（排除类型关键字，只判断结构关键字）。 */
    private boolean isKeywordToken(TokenType type) {
        return type.name().startsWith("KEYWORD_");
    }

    /**
     * 贪心判断当前位置之后是否构成 "类型名 变量名" 的声明模式。
     * <p>
     * 参考老版 engine Parser.isQualifiedTypeVariableDeclaration() 的设计：
     * 从当前 position（已消费第一个标识符后）向前扫描，不消费任何 token，
     * 检查是否匹配以下模式之一：
     * <ul>
     *   <li>{@code TypePart (. TypePart)* [< gen >] [[]]* ID} — 限定名/泛型/数组 + 变量名</li>
     *   <li>{@code < gen >} — 泛型参数开头（如 {@code List<String> x} 中消费了 List 后的 &lt;）</li>
     *   <li>基本类型 / {@code ?} 通配符 / {@code [} 数组开头</li>
     * </ul>
     * 关键排除：方法调用（如 {@code m.put(} 中 put 后是 {@code (}）不会匹配。
     *
     * @return true 如果后续 token 更像是类型声明而非表达式语句
     */
    protected boolean looksLikeTypeDeclaration() {
        // 快速路径：下一个 token 是标识符 → "类型 变量名"（如 String s;）
        if (isTypeStartToken(peek().type())) {
            return true;
        }

        // 泛型实参开头 <：必须括号配对、内容形状合法，且后面紧跟声明符才算声明。
        // 不能一看到 < 就当声明 —— 那样 x < y; 会被当成类型 x<y> 而解析失败。
        if (check(TokenType.OPERATOR_LESS_THAN)) {
            int after = skipGenericTypeArguments(position);
            return after > 0 && startsDeclarator(after);
        }

        if (check(TokenType.OPERATOR_QUESTION)) {
            return true;
        }

        // 数组维度 [ 起始：需要进一步检查不是数组方法引用（String[]::method）
        if (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            return looksLikeArrayTypeNotMethodRef();
        }

        // 慢速路径：限定名类型（Map.Entry e, java.util.List<String> arr 等）
        // 贪心扫描: (DOT IDENTIFIER)* (< generic >)? ([][])* 然后看下一个是否是变量名
        if (check(TokenType.OPERATOR_DOT)) {
            int pos = position;
            boolean hadDot = false;

            while (pos < tokens.size()) {
                TokenType t = tokens.get(pos).type();

                if (t == TokenType.OPERATOR_DOT) {
                    if (pos + 1 >= tokens.size()) return false;
                    TokenType next = tokens.get(pos + 1).type();
                    if (next != TokenType.IDENTIFIER && !isKeywordToken(next)) {
                        return false;
                    }
                    hadDot = true;
                    pos += 2;
                    continue;
                }

                if (t == TokenType.OPERATOR_LESS_THAN) {
                    // 泛型参数 < ... >，跳过（处理嵌套 >> >>>）
                    int after = skipGenericTypeArguments(pos);
                    if (after < 0) return false;
                    pos = after;
                    continue;
                }

                if (t == TokenType.DELIMITER_LEFT_BRACKET) {
                    if (pos + 1 >= tokens.size()
                            || tokens.get(pos + 1).type() != TokenType.DELIMITER_RIGHT_BRACKET) {
                        return false;
                    }
                    pos += 2;
                    continue;
                }

                return hadDot && t == TokenType.IDENTIFIER;
            }
        }

        return false;
    }

    /**
     * 从 {@code pos} 处的 {@code <} 开始，按纯语法形状跳过一段泛型类型实参。
     * <p>
     * 只做括号配对与 token 形状检查，不解析类型、不查类：这一步的用途是在
     * "这里开始的是变量声明还是比较表达式"的岔路口给出判据，而不是替代
     * {@link TypeParser}。例如 {@code a < b > c} 与 {@code List<String> xs}
     * 的形状相同，判据只能是"括号配对 + 后面跟声明符"；而
     * {@code a < b && c > d} 中间的 {@code &&} 不可能是类型实参的一部分，
     * 据此判定它不是声明。
     * </p>
     *
     * @param pos {@code <} 所在下标
     * @return 匹配的 {@code >} 之后的下标；形状非法或未闭合时返回 -1
     */
    private int skipGenericTypeArguments(int pos) {
        int depth = 0;
        while (pos < tokens.size()) {
            TokenType t = tokens.get(pos).type();

            if (t == TokenType.OPERATOR_LESS_THAN) {
                depth++;
            } else if (t == TokenType.OPERATOR_GREATER_THAN) {
                depth--;
                if (depth == 0) return pos + 1;
            } else if (t == TokenType.OPERATOR_RIGHT_SHIFT) {
                depth -= 2;
                if (depth <= 0) return pos + 1;
            } else if (t == TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT) {
                depth -= 3;
                if (depth <= 0) return pos + 1;
            } else if (depth == 0) {
                return -1;
            } else if (!isGenericArgumentToken(t)) {
                return -1;
            }
            pos++;
        }
        return -1;
    }

    /** 泛型实参内部允许出现的 token（类型名、分隔符、通配符、数组维度）。 */
    private boolean isGenericArgumentToken(TokenType type) {
        if (isTypeStartToken(type)) return true;
        return switch (type) {
            case OPERATOR_DOT, DELIMITER_COMMA, OPERATOR_QUESTION,
                 KEYWORD_EXTENDS, KEYWORD_SUPER,
                 DELIMITER_LEFT_BRACKET, DELIMITER_RIGHT_BRACKET -> true;
            default -> false;
        };
    }

    /** {@code pos} 处是否是一个声明符（变量名），允许前置的 {@code []} 数组维度。 */
    private boolean startsDeclarator(int pos) {
        while (pos + 1 < tokens.size()
                && tokens.get(pos).type() == TokenType.DELIMITER_LEFT_BRACKET
                && tokens.get(pos + 1).type() == TokenType.DELIMITER_RIGHT_BRACKET) {
            pos += 2;
        }
        return pos < tokens.size() && tokens.get(pos).type() == TokenType.IDENTIFIER;
    }

    /**
     * 检查当前 token 是否为数组维度 {@code [} 且不是数组方法引用。
     * <p>
     * 用于消歧 {@code String[] arr}（变量声明） vs {@code String[]::method}（方法引用）。
     * 扫描 {@code [...]} 对并检查后续 token 是否为 {@code ::}。
     * </p>
     */
    private boolean looksLikeArrayTypeNotMethodRef() {
        int pos = position;
        while (pos < tokens.size()) {
            TokenType t = tokens.get(pos).type();
            if (t == TokenType.DELIMITER_LEFT_BRACKET) {
                if (pos + 1 >= tokens.size()
                        || tokens.get(pos + 1).type() != TokenType.DELIMITER_RIGHT_BRACKET) {
                    return false;
                }
                pos += 2;
                continue;
            }
            // [][] 后跟 :: → 方法引用，不是类型声明
            if (t == TokenType.OPERATOR_DOUBLE_COLON) {
                return false;
            }
            // [][] 后跟标识符 → 变量声明
            return t == TokenType.IDENTIFIER;
        }
        return false;
    }

    /** 当前 token 是否是复合赋值操作符。 */
    protected boolean isCompoundAssignmentOperator() {
        return check(TokenType.OPERATOR_PLUS_ASSIGN)
                || check(TokenType.OPERATOR_MINUS_ASSIGN)
                || check(TokenType.OPERATOR_MULTIPLY_ASSIGN)
                || check(TokenType.OPERATOR_DIVIDE_ASSIGN)
                || check(TokenType.OPERATOR_MODULO_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_AND_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_OR_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_XOR_ASSIGN)
                || check(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_NULL_COALESCING_ASSIGN);
    }

    /**
     * 检查下一个下一个 token 是否是 lambda 箭头 ->。
     * 用于区分 label: 和 lambda 参数中的冒号。
     */
    protected boolean checkNextIsLambdaArrow() {
        // peek(1) 是 :, 再看后面有没有 ->
        if (position + 2 >= tokens.size()) {
            return false;
        }
        return tokens.get(position + 2).type() == TokenType.DELIMITER_ARROW;
    }

    /**
     * 判断 for-each 的迭代变量前是否为类型推断标记 {@code var} / {@code auto}。
     * <p>
     * {@code auto} 在词法器关键字表内；而 {@code var} 不在，会以普通标识符形式出现，
     * 因此必须按文本判断，否则会被 TypeParser 当成名为 "var" 的类型。
     * </p>
     */
    protected static boolean isInferMarker(Token token) {
        if (token.type() == TokenType.KEYWORD_AUTO || token.type() == TokenType.KEYWORD_VAR) {
            return true;
        }
        return token.type() == TokenType.IDENTIFIER
                && (Keywords.VAR.equals(token.text()) || Keywords.AUTO.equals(token.text()));
    }

    // ==================== 局部变量声明 ====================

    /**
     * 局部变量声明: [@Annotation...] [final] Type varName [= initializer] [, varName2 ...] ;
     *
     * @param annotations 前置注解（可能为 null）
     * @param hasFinal   是否有 final 修饰符
     */
    protected ASTNode parseLocalVariableDeclaration(List<AnnotationNode> annotations, boolean hasFinal)
            throws CythavaParseException {
        SourceLocation location = createLocation();

        // 消费 auto/var 关键字（类型推断，无需显式类型）
        boolean isAuto = match(TokenType.KEYWORD_AUTO) || match(TokenType.KEYWORD_VAR);
        if (!isAuto && isInferMarker(peek())) {
            // var 未列入词法器关键字表，会以普通标识符形式出现；此处等同 auto 处理，
            // 否则会被下面的 TypeParser 当成名为 "var" 的类型，报 "Unknown type 'var'"
            advance();
            isAuto = true;
        }

        // 使用 TypeParser 解析完整类型（支持泛型、数组、通配符等）
        // auto/var 已消费，不需要显式类型，跳过类型解析
        GenericType declaredType = null;
        if (!isAuto && isTypeStart(peek())) {
            int savedPos = position;
            try {
                TypeParser typeParser = new TypeParser(tokens, context, fileName);
                typeParser.setPosition(position);
                declaredType = typeParser.parseType();
                position = typeParser.getPosition();

                // 防御性检查：如果类型名是 "var" 或 "auto"，说明这些关键字被误当类型名解析了
                //   （可能因 DeclParser 安全网未拦截到），应降级为 auto 类型推断
                String origName = declaredType.getOriginalTypeName();
                if ((Keywords.VAR.equals(origName) || Keywords.AUTO.equals(origName))) {
                    declaredType = null; // 降级为 auto：类型从初始化器推断
                }
            } catch (CythavaParseException e) {
                // 类型解析错误应直接抛出，不应静默回退
                if (e.getErrorCode() == ErrorCode.PARSE_CLASS_NOT_FOUND
                        || e.getErrorCode() == ErrorCode.PARSE_INVALID_TYPE) {
                    throw e;
                }
                position = savedPos;
            }
        }

        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name");
        String varName = nameToken.text();

        // 提前注册变量到符号表（在解析初始化器之前），确保即使初始化器解析失败
        //   （如匿名类体语法复杂导致 parseExpr 异常），变量名仍然可用
        if (context.isVariableDeclared(varName)) {
            if (context.isStrictMode()) {
                throw new CythavaParseException(
                        "Duplicate variable declaration: '" + varName + "'",
                        nameToken.location(),
                        ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED);
            }
            // 非严格模式：静默允许重新声明（REPL 兼容）
        } else {
            context.declareVariable(varName, hasFinal, declaredType);
        }

        // 可选初始化器
        ASTNode initializer = null;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            initializer = parseExpr();
            // 声明时检查初始化器类型兼容性
            if (declaredType != null && initializer != null) {
                checkTypeCompatibility(varName, declaredType, initializer);
            }
        }

        // 原始类型无显式初始化器时，自动赋予 Java 默认值（与 Java 语义一致）
        if (initializer == null && isPrimitiveType(declaredType)) {
            initializer = defaultLiteralFor(declaredType.getRawType(), location);
        }

        // 逗号分隔的多变量声明：int a, b = 1, c;
        List<ASTNode> declarations = new ArrayList<>();
        VarDeclNode firstDecl = (VarDeclNode) new VarDeclNode.Builder().varName(varName).declaredType(declaredType).initializer(initializer).isFinal(hasFinal).annotations(annotations).location(location).build();
        declarations.add(firstDecl);

        while (match(TokenType.DELIMITER_COMMA)) {
            SourceLocation nextLocation = createLocation();
            String nextName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name").text();

            // 后续变量可带独立数组维度: int[] a, b[], c[][]
            GenericType nextType = declaredType;
            int nextDims = 0;
            while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
                if (!match(TokenType.DELIMITER_RIGHT_BRACKET)) { position -= 2; break; }
                nextDims++;
            }
            if (nextDims > 0 && nextType != null) {
                Class<?> base = nextType.getRawType();
                if (base != null) {
                    nextType = GenericType.of(Array.newInstance(base, new int[nextDims]).getClass());
                }
            }

            ASTNode nextInit = null;
            if (match(TokenType.OPERATOR_ASSIGN)) {
                nextInit = parseExpr();
                if (nextType != null && nextInit != null) {
                    checkTypeCompatibility(nextName, nextType, nextInit);
                }
            }

            // 后续变量同样自动赋予原始类型默认值
            if (nextInit == null && isPrimitiveType(nextType)) {
                nextInit = defaultLiteralFor(nextType.getRawType(), nextLocation);
            }

            declarations.add(new VarDeclNode.Builder().varName(nextName).declaredType(nextType).initializer(nextInit).isFinal(hasFinal).annotations(null).location(nextLocation).build());
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after local variable declaration");

        // 将声明的变量注册到符号表（后续引用和严格模式消歧需要）
        for (ASTNode decl : declarations) {
            if (decl instanceof VarDeclNode vd) {
                if (!context.isVariableDeclared(vd.getVarName())) {
                    context.declareVariable(vd.getVarName(), vd.isFinal(), vd.getDeclaredType());
                }
                // ★ 匿名类初始化器：将匿名类关联到变量符号（供后续字段访问解析）
                if (vd.getInitializer() instanceof ConstructorCallNode cc
                        && cc.getAnonymousClass() != null) {
                    context.setVariableAnonymousClass(vd.getVarName(), cc.getAnonymousClass());
                }
            }
        }

        return declarations.size() == 1 ? declarations.get(0)
                : new BlockNode.Builder().statements(declarations).unscoped().location(location).build();
    }

    /** final / static 等修饰符开头的局部变量声明 */
    protected ASTNode parseModifierLocalVariableDeclaration() throws CythavaParseException {
        boolean hasFinal = false;
        while (isModifierKeyword(peek().text())) {
            if (Keywords.FINAL.equals(peek().text())) {
                hasFinal = true;
            }
            advance(); // 消费修饰符
        }
        return parseLocalVariableDeclaration(null, hasFinal);
    }

    /**
     * auto 变量声明: auto varName = expr [, varName2 = expr2] ;
     * <p>
     * 类型从初始化表达式的解析期类型标注自动推断。
     * 要求必须有初始化器（auto 不能没有初始值）。
     * </p>
     */
    protected ASTNode parseAutoVariableDeclaration() throws CythavaParseException {
        SourceLocation location = createLocation();

        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name after 'auto'");
        String varName = nameToken.text();

        consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "auto variable '" + varName + "' requires an initializer (= expression)");
        ASTNode initializer = parseExpr();

        // 从 typeMap 推断类型
        GenericType inferredType = context.getInferredType(initializer);

        List<ASTNode> declarations = new ArrayList<>();
        declarations.add(new AssignmentNode.Builder().variableName(varName).value(initializer).isDeclaration(true).declaredType(inferredType).isFinal(false).location(location).build());

        // 逗号分隔的后续 auto 变量（每个都必须有初始化器）
        while (match(TokenType.DELIMITER_COMMA)) {
            SourceLocation nextLoc = createLocation();
            String nextName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name").text();
            consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "auto variable '" + nextName + "' requires an initializer");
            ASTNode nextInit = parseExpr();
            GenericType nextType = context.getInferredType(nextInit);
            declarations.add(new AssignmentNode.Builder().variableName(nextName).value(nextInit).isDeclaration(true).declaredType(nextType).isFinal(false).location(nextLoc).build());
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after auto variable declaration");

        // 注册到符号表（含类型信息和匿名类关联）
        for (ASTNode decl : declarations) {
            if (decl instanceof AssignmentNode assign) {
                context.declareVariable(assign.getVariableName(), assign.getDeclaredType());
                // ★ 匿名类初始化器：将匿名类关联到变量符号（供后续字段访问解析）
                if (assign.getValue() instanceof ConstructorCallNode cc
                        && cc.getAnonymousClass() != null) {
                    context.setVariableAnonymousClass(assign.getVariableName(), cc.getAnonymousClass());
                }
            }
        }

        return declarations.size() == 1 ? declarations.get(0)
                : new BlockNode.Builder().statements(declarations).unscoped().location(location).build();
    }

    /** "@Annotation [final] Type varName ..." 注解开头的局部变量声明 */
    protected ASTNode parseAnnotatedLocalVariableDeclaration() throws CythavaParseException {
        List<AnnotationNode> annotations = new ArrayList<>();
        while (match(TokenType.DELIMITER_AT)) {
            SourceLocation annotationLocation = createLocation();
            String annoName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected annotation name").text();
            if (STATIC_ASSERT_ANNOTATION.equals(annoName)) {
                // 编译期语法糖：就地判定并消费，不产生 AnnotationNode
                parseStaticAssertAnnotationArguments(annotationLocation);
                continue;
            }
            if (match(TokenType.DELIMITER_LEFT_PAREN)) {
                Map<String, Object> args = parseAnnotationArguments();
                consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after annotation arguments");
                annotations.add(new AnnotationNode.Builder().annotationName(annoName).values(args).location(createLocation()).build());
            } else {
                annotations.add(new AnnotationNode.Builder().annotationName(annoName).location(createLocation()).build());
            }
        }

        // 可选的 final 修饰符（final 可能是 KEYWORD_FINAL 或 IDENTIFIER）
        boolean hasFinal = false;
        if (isModifierKeyword(peek().text())) {
            if (Keywords.FINAL.equals(peek().text())) {
                hasFinal = true;
            }
            advance();
        }

        return parseLocalVariableDeclaration(annotations, hasFinal);
    }

    /**
     * 解析 {@code @StaticAssert(condition [, message])} 的参数表并就地判定。
     * <p>
     * 调用时 {@code @} 与注解名已被消费。与语句形式的 {@code static_assert(...)}
     * 共用 {@link #verifyStaticAssert}，判定通过后不产生任何节点。
     * </p>
     */
    private void parseStaticAssertAnnotationArguments(SourceLocation location) throws CythavaParseException {
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after '@StaticAssert'");
        ASTNode condition = parseExpr();
        ASTNode message = null;
        if (match(TokenType.DELIMITER_COMMA)) {
            message = parseExpr();
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after '@StaticAssert' arguments");

        verifyStaticAssert(condition, message, location);
    }

    /** 解析注解参数列表: value 或 k1=v1, k2=v2 */
    private Map<String, Object> parseAnnotationArguments() throws CythavaParseException {
        Map<String, Object> args = new LinkedHashMap<>();
        do {
            SourceLocation loc = createLocation();
            String keyOrValue = advance().text();
            if (match(TokenType.OPERATOR_ASSIGN)) {
                ASTNode valExpr = parseExpr();
                args.put(keyOrValue, valExpr);
            } else {
                args.put("value", new LiteralNode.Builder().value(keyOrValue).type(String.class).location(loc).build());
            }
        } while (match(TokenType.DELIMITER_COMMA));
        return args;
    }

    /** 判断标识符文本是否为 Java 修饰符关键字 */
    protected static boolean isModifierKeyword(String text) {
        return (
                Keywords.FINAL.equals(text)
                        || Keywords.STATIC.equals(text)
                        || Keywords.PUBLIC.equals(text)
                        || Keywords.PRIVATE.equals(text)
                        || Keywords.PROTECTED.equals(text)
                        || Keywords.ABSTRACT.equals(text)
                        || Keywords.NATIVE.equals(text)
                        || Keywords.SYNCHRONIZED.equals(text));
    }

    // ==================== 类型检查与转换 ====================

    /**
     * 检查赋值/初始化值的类型与声明类型是否兼容。
     * <p>
     * 基本规则（简化版，后续可扩展为完整 JLS 赋值转换）：
     * <ul>
     *   <li>相同类型 → 兼容</li>
     *   <li>int → long/double/float（ widening 基本类型提升）→ 兼容</li>
     *   <li>子类 → 父类（引用类型向上转型）→ 兼容</li>
     *   <li>null → 任何引用类型 → 兼容</li>
     *   <li>其他情况 → 报错</li>
     * </ul>
     *
     * @param varName       变量名（用于错误信息）
     * @param declaredType  声明类型
     * @param valueNode     赋值表达式节点
     */
    protected void checkTypeCompatibility(String varName, GenericType declaredType, ASTNode valueNode)
            throws CythavaParseException {
        if (declaredType == null || declaredType.getRawType() == null) return;  // auto 类型无法检查

        Class<?> targetType = declaredType.getRuntimeType();
        JType valueJType = context.getType(valueNode);
        Class<?> valueType = valueJType != null ? resolveRuntimeType(valueJType) : context.getRawType(valueNode);

        // null 兼容所有引用类型
        if (valueType == null) return;

        // 完全匹配
        if (targetType.isAssignableFrom(valueType)) return;

        // 基本类型 widening: int→long, int→double, float→double 等
        if (isWideningConversion(valueType, targetType)) return;

        // 常量窄化: byte b = 1; / short s = 4; / char c = 65;
        // Java 只对"值能放进目标类型的 int 常量表达式"放开，运行时变量（int i = 1; byte b = i;）仍然禁止
        if (isConstantNarrowing(valueNode, valueType, targetType)) return;

        // 自动装箱: int → Integer, long → Long 等
        if (valueType.isPrimitive() && !targetType.isPrimitive()) {
            Class<?> boxed = box(valueType);
            if (boxed != null && targetType.isAssignableFrom(boxed)) return;
        }
        // 自动拆箱: Integer → int, Long → long 等
        if (!valueType.isPrimitive() && targetType.isPrimitive()) {
            Class<?> unboxed = unbox(valueType);
            if (unboxed != null && targetType.isAssignableFrom(unboxed)) return;
        }

        // Lambda/方法引用隐式转换为函数式接口（必须在 Object 放行之前处理）
        if (MethodResolver.isLambdaOrMethodRefNode(valueNode)
                && MethodResolver.isFunctionalInterface(targetType)) {
            if (valueNode instanceof LambdaNode lambda) {
                lambda.setFunctionalInterfaceType(targetType);
            } else if (valueNode instanceof MethodReferenceNode methodRef) {
                methodRef.setFunctionalInterfaceType(targetType);
            }
            return;
        }

        // 类型推断返回 Object（泛型擦除/无法精确推断）时允许赋给任意类型
        // 因为此时没有足够信息做静态检查，信任运行时行为
        // （钻石操作符 new HashMap<>() 等因类未导入被标注为 Object 的情况也走这里）
        if (valueType == Object.class) {
            return;
        }

        // 非严格模式：所有类型不匹配静默放行（兼容旧版 raw class 代码）
        if (!context.isStrictMode()) {
            return;
        }

        throw semanticError("Type mismatch: cannot assign " + valueType.getSimpleName()
                + " to variable '" + varName + "' of type " + targetType.getSimpleName(),
                ErrorCode.EVAL_TYPE_MISMATCH);
    }

    /** 将 JType 解析为完整的运行时 Class（对数组类型合成 int[]、String[][] 等）。 */
    private static Class<?> resolveRuntimeType(JType type) {
        Class<?> raw = type.getRawType();
        if (raw == null) return null;
        int depth = type.getArrayDepth();
        if (depth > 0) {
            return Array.newInstance(raw, new int[depth]).getClass();
        }
        return raw;
    }

    /** 判断是否为基本类型的 widening 转换。 */
    private static boolean isWideningConversion(Class<?> from, Class<?> to) {
        if (!from.isPrimitive() || !to.isPrimitive()) return false;
        // JLS §5.1.2 Widening Primitive Conversion
        if (from == byte.class)   return to == short.class || to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == short.class)  return to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == char.class)   return to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == int.class)    return to == long.class || to == float.class || to == double.class;
        if (from == long.class)   return to == float.class || to == double.class;
        if (from == float.class)  return to == double.class;
        return false;
    }

    /**
     * 常量窄化赋值：目标为 byte / short / char，右侧是值在目标范围内的 int 常量表达式。
     * <p>
     * 对应 JLS §5.2 的 assignment conversion —— 只对常量放开（{@code byte b = 1;} 合法），
     * 运行时变量仍然禁止（{@code int i = 1; byte b = i;} 非法）。常量折叠在解析期就把
     * {@code 1 + 1} 这类表达式折成了字面量，所以这里按 LiteralNode 判断即可。
     * </p>
     */
    private static boolean isConstantNarrowing(ASTNode valueNode, Class<?> valueType, Class<?> targetType) {
        if (valueType != int.class) return false;
        if (targetType != byte.class && targetType != short.class && targetType != char.class) return false;
        if (!(valueNode instanceof LiteralNode lit) || !(lit.getValue() instanceof Integer value)) return false;
        if (targetType == byte.class) return value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
        if (targetType == short.class) return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
        return value >= Character.MIN_VALUE && value <= Character.MAX_VALUE;
    }

    /** 判断 GenericType 是否为 Java 原始类型（int/long/double/float/boolean/char/byte/short）。 */
    private static boolean isPrimitiveType(GenericType type) {
        if (type == null || type.getRawType() == null) {
            return false;
        }
        return type.getRawType().isPrimitive();
    }

    /**
     * 为原始类型生成默认值字面量（与 Java 默认值语义一致）。
     * <ul>
     *   <li>int, short, byte → 0</li>
     *   <li>long → 0L</li>
     *   <li>double → 0.0</li>
     *   <li>float → 0.0f</li>
     *   <li>boolean → false</li>
     *   <li>char → '\0'</li>
     * </ul>
     */
    private static LiteralNode defaultLiteralFor(Class<?> primitiveType, SourceLocation location) {
        if (primitiveType == int.class || primitiveType == short.class || primitiveType == byte.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0).type(int.class).location(location).build();
        }
        if (primitiveType == long.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0L).type(long.class).location(location).build();
        }
        if (primitiveType == double.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0.0).type(double.class).location(location).build();
        }
        if (primitiveType == float.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0.0f).type(float.class).location(location).build();
        }
        if (primitiveType == boolean.class) {
            return (LiteralNode) new LiteralNode.Builder().value(false).type(boolean.class).location(location).build();
        }
        if (primitiveType == char.class) {
            return (LiteralNode) new LiteralNode.Builder().value('\0').type(char.class).location(location).build();
        }
        // 不应到达这里
        return (LiteralNode) new LiteralNode.Builder().value(null).type(void.class).location(location).build();
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == char.class) return Character.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == void.class) return Void.class;
        return primitive;
    }

    private static Class<?> unbox(Class<?> wrapper) {
        if (wrapper == Integer.class) return int.class;
        if (wrapper == Long.class) return long.class;
        if (wrapper == Double.class) return double.class;
        if (wrapper == Float.class) return float.class;
        if (wrapper == Boolean.class) return boolean.class;
        if (wrapper == Character.class) return char.class;
        if (wrapper == Byte.class) return byte.class;
        if (wrapper == Short.class) return short.class;
        return null;
    }

    // ==================== 复合赋值 ====================

    /** x += expr ; 等复合赋值 */
    protected ASTNode parseCompoundAssignment(String varName) throws CythavaParseException {
        SourceLocation location = createLocation();

        // 读取并消费复合赋值操作符
        BinaryOpNode.Operator op = consumeCompoundOperator();
        if (op == null) {
            throw error("Expected compound assignment operator", ErrorCode.PARSE_UNEXPECTED_TOKEN);
        }

        ASTNode value = parseExpr();
        consumeSemicolon();

        // 创建变量引用节点（带类型标注），使 read-modify-write 的 BinaryOpNode 能正确读取当前值
        VariableNode varRef = (VariableNode) new VariableNode.Builder().name(varName).location(location).build();
        GenericType declaredType = context.getDeclaredType(varName);
        if (declaredType != null) {
            context.setType(varRef, JType.fromGenericType(declaredType));
        }

        // 包装为 BinaryOpNode：Evaluator 在 visitAssignment 中 evaluate 此节点时，
        // 会递归 evaluate(varRef) 读取当前值，再与 RHS 做运算
        ASTNode combinedValue = new BinaryOpNode.Builder()
                .operator(op)
                .left(varRef)
                .right(value)
                .location(location)
                .build();

        // 复合赋值的结果类型与 LHS 变量类型一致（如 int += int → int）
        JType assignType = declaredType != null ? JType.fromGenericType(declaredType) : null;

        AssignmentNode assignNode = (AssignmentNode) new AssignmentNode.Builder()
                                .variableName(varName)
                                .value(combinedValue)
                                .isDeclaration(false)
                                .declaredType(declaredType)
                                .location(location)
                                .build();
        if (assignType != null) {
            context.setType(assignNode, assignType);
        }
        return assignNode;
    }

    /** 消费复合赋值操作符并返回对应的 BinaryOpNode.Operator，无匹配则返回 null。 */
    private BinaryOpNode.Operator consumeCompoundOperator() {
        if (match(TokenType.OPERATOR_PLUS_ASSIGN)) return BinaryOpNode.Operator.ADD;
        if (match(TokenType.OPERATOR_MINUS_ASSIGN)) return BinaryOpNode.Operator.SUBTRACT;
        if (match(TokenType.OPERATOR_MULTIPLY_ASSIGN)) return BinaryOpNode.Operator.MULTIPLY;
        if (match(TokenType.OPERATOR_DIVIDE_ASSIGN)) return BinaryOpNode.Operator.DIVIDE;
        if (match(TokenType.OPERATOR_MODULO_ASSIGN)) return BinaryOpNode.Operator.MODULO;
        if (match(TokenType.OPERATOR_BITWISE_AND_ASSIGN)) return BinaryOpNode.Operator.BITWISE_AND;
        if (match(TokenType.OPERATOR_BITWISE_OR_ASSIGN)) return BinaryOpNode.Operator.BITWISE_OR;
        if (match(TokenType.OPERATOR_BITWISE_XOR_ASSIGN)) return BinaryOpNode.Operator.BITWISE_XOR;
        if (match(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.LEFT_SHIFT;
        if (match(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.RIGHT_SHIFT;
        if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.UNSIGNED_RIGHT_SHIFT;
        if (match(TokenType.OPERATOR_NULL_COALESCING_ASSIGN)) return BinaryOpNode.Operator.NULL_COALESCING;
        return null;
    }
}
