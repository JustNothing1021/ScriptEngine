package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code new} 表达式解析层。
 * <p>
 * 负责对象创建、泛型类型参数、数组创建与匿名内部类。
 * </p>
 */
abstract class ObjectCreationParser extends ExpressionSupport {

    protected ObjectCreationParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    /**
     * 解析 {@code new} 对象创建。
     * <p>
     * 支持形式：
     * <ul>
     * <li>{@code new ClassName(args)} — 普通构造</li>
     * <li>{@code new int[size]} — 数组创建</li>
     * <li>{@code new int[]{elements} — 数组初始化器</li>
     *   
    <li>{@code new ClassName() { ... } } — 匿名内部类</li>
     * 
    </ul>
     * 
    </p>
     */
    protected ASTNode parseNewObject() throws CythavaParseException {
        SourceLocation location = createLocation();

        // 解析类型名（含泛型参数）→ 返回结构化 GenericType
        GenericType parsedType = parseNewObjectType();
        String typeName = parsedType.getOriginalTypeName();

        // 数组创建: new Type[size] 或 new Type[] with initializer（支持多维）
        if (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            return parseNewArrayMultiDim(typeName, location);
        }

        // 普通构造: new Type(args) 或匿名内部类
        if (match(TokenType.DELIMITER_LEFT_PAREN)) {
            List<ASTNode> args = parseArgumentList();
            consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after constructor arguments");

            // 匿名内部类: new Type(...) with class body
            if (check(TokenType.DELIMITER_LEFT_BRACE)) {
                BlockNode body = parseAnonymousClassBody();
                ClassReferenceNode superRef = ClassReferenceNode.of(
                        typeName, parsedType.getRawType(), false, createLocation());
                ClassDeclarationNode anonClass = (ClassDeclarationNode) new ClassDeclarationNode.Builder()
                        .className("")
                        .superClass(superRef)
                        .interfaces(List.of()).location(createLocation()).build();
                for (ASTNode member : body.getStatements()) {
                    if (member instanceof FieldDeclarationNode field) {
                        anonClass.addField(field);
                    } else if (member instanceof MethodDeclarationNode method) {
                        anonClass.addMethod(method);
                    }
                }
                Class<?> anonRawType = context.resolveClass(typeName);
                if (anonRawType != null) {
                    methodResolver.annotateConstructorFunctionalInterfaceArgs(anonRawType, args);
                }
                ConstructorCallNode node = (ConstructorCallNode) new ConstructorCallNode.Builder()
                        .type(parsedType).arguments(args)
                        .anonymousClass(anonClass).location(location).build();
                annotate(node, anonRawType != null ? anonRawType : Object.class);
                return node;
            }

            // 标注 lambda/方法引用参数的目标函数式接口类型
            Class<?> rawType = parsedType.getRawType();
            if (rawType != null) {
                methodResolver.annotateConstructorFunctionalInterfaceArgs(rawType, args);
            }

            ConstructorCallNode node2 = (ConstructorCallNode) new ConstructorCallNode.Builder()
                    .type(parsedType).arguments(args)
                    .location(location).build();
            annotate(node2, rawType);
            return node2;
        }

        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            throw semanticError("Anonymous class body '{' requires '()' before it (use 'new " + typeName + "() { ... }')",
                    ErrorCode.PARSE_INVALID_SYNTAX);
        }

        throw error("Expected '(' or '[' after type in new expression",
                ErrorCode.PARSE_INVALID_SYNTAX);
    }

    /**
     * 解析 {@code new} 后面的类型名（含包路径和泛型参数）。
     * <p>
     * 返回结构化的 {@link GenericType}，其中 {@code typeArguments} 包含每个泛型参数的解析结果。
     * 支持钻石操作符 {@code <>}（typeArguments 为空列表）、嵌套泛型、通配符等。
     *
     * @return 完整的类型信息（rawType + typeArguments + originalTypeName）
     */
    private GenericType parseNewObjectType() throws CythavaParseException {
        if (!isTypeStartToken()) {
            throw error("Expected type name after 'new'", ErrorCode.PARSE_INVALID_TYPE);
        }

        // 1. 解析原始类名（标识符 [. 标识符]*）
        StringBuilder typeNameBuilder = new StringBuilder(advance().text());
        while (check(TokenType.OPERATOR_DOT) && checkNext(TokenType.IDENTIFIER)) {
            advance(); // .
            typeNameBuilder.append('.').append(advance().text());
        }
        String rawTypeName = typeNameBuilder.toString();

        // 2. 解析类本身
        Class<?> resolvedClass = context.resolveClass(rawTypeName);
        if (resolvedClass == null) resolvedClass = Object.class;

        // 3. 泛型参数 <...>
        List<GenericType> typeArgs;
        if (check(TokenType.OPERATOR_LESS_THAN)) {
            if (position + 1 < tokens.size()
                    && tokens.get(position + 1).type() == TokenType.OPERATOR_GREATER_THAN) {
                // 钻石操作符 <>
                advance(); // <
                advance(); // >
                typeArgs = List.of();
            } else {
                // 正常泛型参数: 逐个解析为 GenericType
                advance(); // 消费 <
                typeArgs = parseGenericTypeArgumentList();
                consumeGenericClose("Expected '>' after generic type arguments");
            }
        } else {
            typeArgs = List.of();
        }

        return new GenericType(resolvedClass, typeArgs, 0, rawTypeName);
    }

    /**
     * 解析泛型参数列表的内容（不含外围的 {@code < >}）。
     * <p>
     * 例如输入位置在 {@code String, Integer>} 之后，解析出 [String, Integer]。
     * 支持通配符 ({@code ?}, {@code ? extends T}, {@code ? super T}) 和嵌套泛型。
     */
    protected List<GenericType> parseGenericTypeArgumentList() throws CythavaParseException {
        List<GenericType> args = new ArrayList<>();
        do {
            args.add(parseSingleGenericTypeArg());
        } while (match(TokenType.DELIMITER_COMMA));
        return args;
    }

    /**
     * 解析单个泛型类型参数。
     * <p>
     * 处理：基本类型（int/long/String 等）、引用类型（含嵌套泛型）、通配符。
     * 对无法解析的类型名抛出语义错误。
     */
    private GenericType parseSingleGenericTypeArg() throws CythavaParseException {
        // 通配符 ?
        if (check(TokenType.OPERATOR_QUESTION)) {
            advance(); // 消费 ?
            if (match(TokenType.KEYWORD_EXTENDS)) {
                GenericType bound = parseGenericTypeName();
                return new GenericType(Object.class, List.of(bound), 0, "? extends " + bound.getOriginalTypeName());
            }
            if (match(TokenType.KEYWORD_SUPER)) {
                GenericType bound = parseGenericTypeName();
                return new GenericType(Object.class, List.of(bound), 0, "? super " + bound.getOriginalTypeName());
            }
            return new GenericType(Object.class, List.of(), 0, "?");
        }

        // 普通类型：解析名称 + 可选嵌套泛型
        return parseGenericTypeName();
    }

    /**
     * 解析一个完整类型名（可能带嵌套泛型），用于泛型参数内部。
     * <p>
     * 例如: {@code String}, {@code Map<String, Integer>}, {@code List<?>}
     */
    private GenericType parseGenericTypeName() throws CythavaParseException {
        if (!isTypeStartToken() && !check(TokenType.IDENTIFIER)) {
            throw error("Expected type name in generic argument", ErrorCode.PARSE_INVALID_TYPE);
        }

        StringBuilder nameBuilder = new StringBuilder(advance().text());

        // 限定名
        while (check(TokenType.OPERATOR_DOT) && checkNext(TokenType.IDENTIFIER)) {
            advance(); // .
            nameBuilder.append('.').append(advance().text());
        }
        String typeName = nameBuilder.toString();

        // 解析为 Class<?>
        Class<?> resolved = context.resolveClass(typeName);
        if (resolved == null) {
            // 非严格模式：回退到 Object.class，让解析继续
            if (!context.isStrictMode()) {
                resolved = Object.class;
            } else {
                throw error("Cannot resolve type '" + typeName + "' in generic argument"
                        + " (ensure the class is imported or use fully qualified name)",
                        ErrorCode.PARSE_INVALID_TYPE);
            }
        }

        // 嵌套泛型: Map<String, Integer>
        List<GenericType> nestedArgs = List.of();
        if (check(TokenType.OPERATOR_LESS_THAN)) {
            advance(); // <
            nestedArgs = parseGenericTypeArgumentList();
            consumeGenericClose("Expected '>' after generic type arguments");
        }

        // 数组后缀 []
        int arrayDepth = 0;
        while (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            advance(); // [
            consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in array type");
            arrayDepth++;
        }

        return new GenericType(resolved, nestedArgs, arrayDepth, typeName);
    }

    /**
     * 解析多维数组创建：{@code new int[][] {{1,2},{3,4}}} 或 {@code new String[2][3]}。
     * <p>
     * 支持任意维度，每个维度可以是尺寸表达式或空（配合初始化器）。
     * </p>
     */
    private NewArrayNode parseNewArrayMultiDim(String typeName, SourceLocation location)
            throws CythavaParseException {
        // 收集所有维度: 每个 [] 里可能是 size 表达式或空
        List<ASTNode> dimensions = new ArrayList<>();
        while (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            advance(); // 吃掉 [
            if (match(TokenType.DELIMITER_RIGHT_BRACKET)) {
                // 空 [] → 维度由初始化器决定
                dimensions.add(null);
            } else {
                // 有尺寸表达式
                dimensions.add(parseNextExpression());
                consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after array dimension");
            }
        }

        // 检查是否有数组初始化器 { ... }（Java 风格花括号初始化器）
        ArrayLiteralNode initializer = null;
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            initializer = parseBraceArrayInitializer();
        }

        Class<?> elementType = context.resolveClass(typeName);
        int dimCount = dimensions.size();
        // 构建实际数组类型（如 int[][]）
        Class<?> arrayType;
        if (elementType != null && dimCount > 0) {
            arrayType = Array.newInstance(elementType, new int[dimCount]).getClass();
        } else {
            // 无法推断具体类型时使用通用数组类型
            arrayType = dimCount == 1 ? Object[].class : Object[][].class;
        }

        // 用最后一个非 null 维度作为 size（表示 new Type[n] 里的 n）
        // 带初始化器（new Type[] {…}）时没有尺寸表达式，size 保持 null
        ASTNode sizeExpr = null;
        for (int i = dimensions.size() - 1; i >= 0; i--) {
            if (dimensions.get(i) != null) {
                sizeExpr = dimensions.get(i);
                break;
            }
        }

        NewArrayNode node = (NewArrayNode) new NewArrayNode.Builder()
                .elementType(elementType != null ? elementType : Object.class)
                .size(sizeExpr)
                .sizes(dimensions)
                .initializer(initializer)
                .location(location)
                .build();
        annotate(node, arrayType);
        return node;
    }

    /**
     * 解析匿名内部类体 {@code { members... }}。
     * <p>
     * 简化实现：使用括号匹配收集原始内容，不做完整成员解析。
     * 完整的匿名类体解析将来由 DeclParser 负责。
     * </p>
     */
    private BlockNode parseAnonymousClassBody() throws CythavaParseException {
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' for anonymous class body");
        List<ASTNode> members = new ArrayList<>();
        // ★ 跟踪已解析的字段（用于传递给后续方法解析，使方法体内可按名访问字段）
        List<FieldDeclarationNode> priorFields = new ArrayList<>();
        // 匿名类体是宽容解析路径：成员可能引用尚未解析的字段而失败，需要"出错即回退"，
        // 因此暂时关闭语句级错误恢复（否则失败会被记录下来导致整个解析失败）
        boolean recovery = context.isErrorRecoveryEnabled();
        context.setErrorRecoveryEnabled(false);
        try {
            parseAnonymousClassMembers(members, priorFields);
        } finally {
            context.setErrorRecoveryEnabled(recovery);
        }
        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after anonymous class body");
        return (BlockNode) new BlockNode.Builder().statements(members).location(createLocation()).build();
    }

    private void parseAnonymousClassMembers(List<ASTNode> members, List<FieldDeclarationNode> priorFields)
            throws CythavaParseException {
        // 逐个解析匿名类体成员（字段声明、方法声明等）
        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            int posBefore = position; // 记录位置用于检测原地踏步
            // 尝试解析为字段声明：[modifiers] type name [= init];
            try {
                ASTNode member = tryParseAnonymousMember(priorFields);
                if (member != null) {
                    members.add(member);
                    // ★ 跟踪字段声明
                    if (member instanceof FieldDeclarationNode fd) {
                        priorFields.add(fd);
                    }
                } else if (position == posBefore) {
                    // 解析器无法识别当前成员且未消费任何 token → 语法错误，不应被 catch 吞掉
                    throw semanticError("Expected member declaration (field or method), but found '"
                            + peek().text() + "'",
                        ErrorCode.PARSE_INVALID_SYNTAX);
                }
                // member == null 但 position > posBefore → tryParseAnonymousMember 消费了 token 但决定不生成节点
                // （不应该发生，但安全起见跳过）
            } catch (CythavaParseException e) {
                // 返回值类型不匹配等错误应传播出去（不应被匿名类成员循环吞掉）
                if (e.getErrorCode() == ErrorCode.PARSE_INVALID_TYPE) {
                    throw e;
                }
                // 成员解析失败但可能已消费部分 token
                if (position > posBefore) {
                    // 已有进展，跳过到下一个分号或继续尝试
                    consumeOptionalSemicolon();
                    continue;
                }
                // 完全没有进展 → 重新抛出
                throw e;
            } catch (IllegalStateException e) {
                if (position > posBefore) {
                    consumeOptionalSemicolon();
                    continue;
                }
                throw e;
            }
            consumeOptionalSemicolon();
        }
    }

    /**
     * 尝试将当前 token 序列解析为匿名类成员（支持字段声明和方法声明）。
     *
     * @return 解析成功的成员节点，无法识别返回 null（调用方应报错）
     */
    private ASTNode tryParseAnonymousMember(List<FieldDeclarationNode> priorFields) throws CythavaParseException {
        // 跳过修饰符（public/private/protected/static/final 等）
        while (!isAtEnd() && isModifierToken(peek())) {
            advance();
        }

        // 成员必须以类型起始
        if (!isTypeStartToken())
            return null;

        // ★ 前瞻检查：类型后面是否有标识符（成员名）
        int savedPos = position;
        int typeLen = 1; // 至少消费 1 个 type token
        // 跳过包路径 qualified name
        while (savedPos + typeLen < tokens.size()) {
            Token next = tokens.get(savedPos + typeLen);
            if (next.type() == TokenType.DELIMITER_DOT
                    && savedPos + typeLen + 1 < tokens.size()
                    && tokens.get(savedPos + typeLen + 1).type() == TokenType.IDENTIFIER) {
                typeLen += 2; // . + 标识符
            } else {
                break;
            }
        }
        // 类型序列之后必须是 IDENTIFIER（字段名或方法名）
        if (savedPos + typeLen >= tokens.size()
                || tokens.get(savedPos + typeLen).type() != TokenType.IDENTIFIER) {
            return null; // 不是成员声明，不消费任何 token
        }

        StringBuilder typeBuilder = new StringBuilder(advance().text());
        // 处理包路径 qualified name（如 java.lang.String）
        while (check(TokenType.DELIMITER_DOT) && position + 1 < tokens.size()
                && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
            typeBuilder.append(advance().text()); // .
            typeBuilder.append(advance().text()); // 标识符
        }

        String memberName = advance().text();

        // ★ 区分字段声明和方法声明：看成员名后的下一个 token
        if (check(TokenType.DELIMITER_LEFT_PAREN)) {
            // 方法声明：name(params) [throws] { body } 或 ;
            return parseAnonymousMethod(typeBuilder.toString(), memberName, priorFields);
        }

        // 字段声明：name [= init];
        ASTNode initialValue = null;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            initialValue = parseNextExpression();
        }
        return new FieldDeclarationNode.Builder()
                .fieldName(memberName)
                .type(resolveTypeReference(typeBuilder.toString()))
                .initialValue(initialValue)
                .location(createLocation())
                .build();
    }

    /**
     * 解析匿名类体内的方法声明。
     * <p>
     * 格式：methodName(params) [throws ExceptionList] { body }  或  methodName(params);
     *
     * @param returnTypeStr 返回类型的字符串表示
     * @param methodName    方法名
     * @param priorFields   此方法之前已解析的字段（用于注册到作用域，使方法体内可直接按名访问字段）
     * @return MethodDeclarationNode
     */
    private ASTNode parseAnonymousMethod(String returnTypeStr, String methodName,
            List<FieldDeclarationNode> priorFields) throws CythavaParseException {
        consume(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after method name in anonymous class");

        // 解析参数列表：type name [, type name ...]
        List<ParameterNode> params = new ArrayList<>();
        while (!check(TokenType.DELIMITER_RIGHT_PAREN) && !isAtEnd()) {
            // 参数类型
            if (!isTypeStartToken()) {
                throw error("Expected parameter type in method '" + methodName + "'", ErrorCode.PARSE_INVALID_SYNTAX);
            }
            StringBuilder paramType = new StringBuilder(advance().text());
            while (check(TokenType.DELIMITER_DOT) && position + 1 < tokens.size()
                    && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
                paramType.append(advance().text()); // .
                paramType.append(advance().text()); // 标识符
            }
            // 参数名
            if (!check(TokenType.IDENTIFIER)) {
                throw error("Expected parameter name in method '" + methodName + "'", ErrorCode.PARSE_INVALID_SYNTAX);
            }
            String paramName = advance().text();
            ClassReferenceNode paramTypeRef =
                    resolveTypeReference(paramType.toString());

            params.add((ParameterNode) new ParameterNode.Builder()
                    .parameterName(paramName)
                    .type(paramTypeRef)
                    .location(createLocation())
                    .build());

            //逗号分隔 → 继续下一个参数；右括号 → 参数列表结束
            if (!match(TokenType.DELIMITER_COMMA)) {
                break;
            }
        }
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after parameter list of method '" + methodName + "'");

        // 可选的 throws 子句 — 跳过直到 { 或 ;
        while (!check(TokenType.DELIMITER_LEFT_BRACE) && !check(TokenType.DELIMITER_SEMICOLON) && !isAtEnd()) {
            advance(); // 跳过 'throws' 和异常列表
        }

        // 方法体或抽象分号
        ASTNode body = null;
        ClassReferenceNode returnTypeRef = resolveTypeReference(returnTypeStr);
        if (match(TokenType.DELIMITER_SEMICOLON)) {
            // 抽象方法（无 body），body 保持 null
        } else {
            // { body } — 使用 StmtParser 完整解析方法体语句
            consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' for method body of '" + methodName + "'");

            // 进入方法作用域，声明参数和已解析的字段为局部变量
            context.enterScope(ParseContext.ScopeKind.METHOD);
            try {
                // ★ 注册参数（带类型信息）
                for (ParameterNode param : params) {
                    Class<?> paramType = param.getType() != null && param.getType().getResolvedClass() != null
                            ? param.getType().getResolvedClass() : Object.class;
                    context.declareVariable(param.getParameterName(), paramType);
                }

                // ★ 将此方法之前已解析的匿名类字段注册到作用域（使方法体内可直接按名访问字段）
                if (priorFields != null) {
                    for (FieldDeclarationNode field : priorFields) {
                        if (!context.isVariableDeclared(field.getFieldName())) {
                            Class<?> fieldType = field.getType() != null && field.getType().getResolvedClass() != null
                                    ? field.getType().getResolvedClass() : Object.class;
                            context.declareVariable(field.getFieldName(), fieldType);
                        }
                    }
                }

                // 用 StmtParser 解析方法体中的所有语句
                List<ASTNode> bodyStmts;
                try {
                    StmtParser stmtParser = new StmtParser(tokens, context, fileName);
                    // 同步 position（StmtParser 和 ExprParser 共享同一 token 流）
                    stmtParser.setPosition(position);
                    bodyStmts = stmtParser.parseBlockBody();
                    position = stmtParser.getPosition(); // 同步回位置

                    consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after method body of '" + methodName + "'");

                    body = new BlockNode.Builder()
                            .statements(bodyStmts)
                            .location(createLocation())
                            .build();

                    // ★ 返回值类型检查：遍历所有 return 语句，验证返回值与声明返回类型兼容
                    checkReturnTypes(methodName, returnTypeRef, bodyStmts);
                } catch (CythavaParseException e) {
                    // 返回值类型不匹配等错误应直接传播
                    if (e.getErrorCode() == ErrorCode.PARSE_INVALID_TYPE) {
                        throw e;
                    }
                    // 其余错误（如 Cannot find symbol / 字段尚未解析）回退为空方法体，
                    // 不中断整个解析：按括号平衡跳过方法体
                    int depth = 1;
                    while (depth > 0 && !isAtEnd()) {
                        Token t = advance();
                        if (t.type() == TokenType.DELIMITER_LEFT_BRACE) depth++;
                        else if (t.type() == TokenType.DELIMITER_RIGHT_BRACE) depth--;
                    }
                    body = new BlockNode.Builder()
                            .statements(List.of())
                            .location(createLocation())
                            .build();
                } catch (IllegalStateException e) {
                    // 状态异常（如 scope 栈不平衡）→ 回退到括号平衡跳过
                    int depth = 1;
                    while (depth > 0 && !isAtEnd()) {
                        Token t = advance();
                        if (t.type() == TokenType.DELIMITER_LEFT_BRACE) depth++;
                        else if (t.type() == TokenType.DELIMITER_RIGHT_BRACE) depth--;
                    }
                    body = new BlockNode.Builder()
                            .statements(List.of())
                            .location(createLocation())
                            .build();
                }
            } finally {
                context.exitScope();
            }
        }

        return new MethodDeclarationNode.Builder()
                .methodName(methodName)
                .returnType(returnTypeRef)
                .parameters(params)
                .body(body)
                .location(createLocation())
                .build();
    }

    /**
     * 检查方法体中所有 return 语句的返回值类型是否与声明的返回类型兼容。
     *
     * @param methodName   方法名（用于错误信息）
     * @param returnTypeRef 声明的返回类型
     * @param statements   方法体语句列表
     * @throws CythavaParseException 如果返回值类型不兼容
     */
    private void checkReturnTypes(String methodName, ClassReferenceNode returnTypeRef,
                                   List<ASTNode> statements) throws CythavaParseException {
        Class<?> declaredRaw = returnTypeRef.getResolvedClass();
        if (declaredRaw == null) return; // 无法解析的类型跳过检查

        // 非严格模式：跳过所有返回值类型检查（兼容旧版无类型检查代码）
        if (!context.isStrictMode()) {
            return;
        }

        boolean isVoid = declaredRaw == void.class;
        for (ASTNode stmt : statements) {
            if (!(stmt instanceof ReturnNode ret)) continue;

            ASTNode returnValue = ret.getValue();
            if (isVoid) {
                if (returnValue != null) {
                    throw error("Method '" + methodName + "' declares void return type but returns a value",
                            ErrorCode.PARSE_INVALID_TYPE);
                }
                continue; // void return; — OK
            }
            if (returnValue == null) {
                throw error("Method '" + methodName + "' declares non-void return type '"
                                + returnTypeRef.getTypeName() + "' but has no return value",
                        ErrorCode.PARSE_INVALID_TYPE);
            }

            // 获取返回值的推断类型
            JType actualType = context.getType(returnValue);
            if (actualType == null) {
                // 无法推断类型的情况：
                // - null 字面量：只能赋给非基本类型（引用类型）
                if (returnValue instanceof LiteralNode && ((LiteralNode) returnValue).getValue() == null) {
                    if (declaredRaw.isPrimitive()) {
                        throw error("Method '" + methodName + "' declares return type '"
                                        + declaredRaw.getSimpleName() + "' but returns 'null'",
                                ErrorCode.PARSE_INVALID_TYPE);
                    }
                }
                continue; // 其他无法推断的情况跳过
            }

            Class<?> actualRaw = actualType.getRawType();
            // 未解析的变量在非严格模式下类型为 Object，无法确定实际类型 → 跳过检查
            // （只有字面量/已知类型的表达式才做严格的返回值兼容性检查）
            if (actualRaw == Object.class && !context.isStrictMode()) {
                continue; // 非严格模式下无法精确推断类型时跳过
            }
            // 内置运算符的动态返回类型：Registry 中数值/字符串运算符注册为 Object 返回，
            // 但运行时会根据操作数实际类型返回 int/long/double/String 等精确类型。
            // 因此当声明类型是常见基本/包装类型且实际推断为 Object 时，放行（信任运行期）。
            if (actualRaw == Object.class && isBuiltinOperatorReturnType(declaredRaw)) {
                continue;
            }
            if (!isReturnTypeCompatible(actualRaw, declaredRaw)) {
                throw error("Method '" + methodName + "' declares return type '"
                                + declaredRaw.getSimpleName() + "' but returns '"
                                + actualRaw.getSimpleName() + "'",
                        ErrorCode.PARSE_INVALID_TYPE);
            }
        }
        // 递归检查嵌套块（if/while/try 等控制流中的 return）
        checkReturnTypesInNestedBlocks(methodName, returnTypeRef, statements);
    }

    /** 在嵌套块中递归检查 return 类型。 */
    private void checkReturnTypesInNestedBlocks(String methodName, ClassReferenceNode returnTypeRef,
                                                  List<ASTNode> statements) throws CythavaParseException {
        for (ASTNode stmt : statements) {
            List<ASTNode> nested = getNestedStatements(stmt);
            if (!nested.isEmpty()) {
                checkReturnTypes(methodName, returnTypeRef, nested);
            }
        }
    }

    /** 从一个语句节点中提取可能包含 return 的子语句列表。 */
    private List<ASTNode> getNestedStatements(ASTNode stmt) {
        // if/else 分支
        if (stmt instanceof IfNode ifNode) {
            List<ASTNode> result = new ArrayList<>();
            if (ifNode.getThenBlock() != null) result.add(ifNode.getThenBlock());
            if (ifNode.getElseBlock() != null) result.add(ifNode.getElseBlock());
            return result;
        }
        // while/for 循环体
        if (stmt instanceof WhileNode wn && wn.getBody() != null) return List.of(wn.getBody());
        // try 块
        if (stmt instanceof TryNode tn) {
            List<ASTNode> result = new ArrayList<>();
            result.add(tn.getTryBlock());
            for (var cc : tn.getCatchClauses()) {
                if (cc.getBody() != null) result.add(cc.getBody());
            }
            if (tn.getFinallyBlock() != null) result.add(tn.getFinallyBlock());
            return result;
        }
        // switch case 体
        if (stmt instanceof SwitchNode sn) {
            List<ASTNode> result = new ArrayList<>();
            for (var c : sn.getCases()) {
                result.addAll(c.getStatements());
            }
            if (sn.getDefaultCase() != null) result.add(sn.getDefaultCase());
            return result;
        }
        // 普通代码块
        if (stmt instanceof BlockNode bn) return bn.getStatements();
        return List.of();
    }

    /** 判断类型是否是内置运算符可能产生的返回类型（用于宽松的返回值检查）。 */
    private static boolean isBuiltinOperatorReturnType(Class<?> type) {
        return type == int.class || type == long.class || type == double.class
                || type == float.class || type == boolean.class
                || type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class;
    }

    /**
     * 判断实际返回类型是否与声明返回类型兼容。
     * <p>
     * 规则：
     * <ul>
     *   <li>完全匹配 → 兼容</li>
     *   <li>null 可以赋给任何非基本类型</li>
     *   <li>数值提升：byte→short→int→long→float→double</li>
     *   <li>引用类型：isAssignableFrom</li>
     * </ul>
     */
    private boolean isReturnTypeCompatible(Class<?> actual, Class<?> declared) {
        if (actual == declared) return true;
        if (actual == null) return !declared.isPrimitive(); // null 可赋给任何引用类型

        // 数值提升链
        if (declared.isPrimitive() && actual.isPrimitive()) {
            Class<?>[] rank = {Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE};
            int actualRank = -1, declaredRank = -1;
            for (int i = 0; i < rank.length; i++) {
                if (rank[i] == actual) actualRank = i;
                if (rank[i] == declared) declaredRank = i;
            }
            if (actualRank >= 0 && declaredRank >= 0 && actualRank <= declaredRank) return true;
        }

        // 引用类型：声明类型是实际类型的父类/接口
        if (!declared.isPrimitive() && !actual.isPrimitive() && declared.isAssignableFrom(actual)) {
            return true;
        }

        // 自动装箱：int → Integer 等
        if (declared.isPrimitive() && !actual.isPrimitive()) {
            Class<?> unboxed = unbox(actual);
            if (unboxed != null) return isReturnTypeCompatible(unboxed, declared);
        }
        if (!declared.isPrimitive() && actual.isPrimitive()) {
            Class<?> boxed = box(actual);
            if (boxed != null) return isReturnTypeCompatible(boxed, declared);
        }

        return false;
    }

    /** 将包装类拆箱为基本类型。 */
    private static Class<?> unbox(Class<?> wrapper) {
        if (wrapper == Integer.class) return Integer.TYPE;
        if (wrapper == Long.class) return Long.TYPE;
        if (wrapper == Double.class) return Double.TYPE;
        if (wrapper == Float.class) return Float.TYPE;
        if (wrapper == Short.class) return Short.TYPE;
        if (wrapper == Byte.class) return Byte.TYPE;
        if (wrapper == Character.class) return Character.TYPE;
        if (wrapper == Boolean.class) return Boolean.TYPE;
        return null;
    }

    /** 将基本类型装箱为包装类。 */
    private static Class<?> box(Class<?> primitive) {
        if (primitive == Integer.TYPE) return Integer.class;
        if (primitive == Long.TYPE) return Long.class;
        if (primitive == Double.TYPE) return Double.class;
        if (primitive == Float.TYPE) return Float.class;
        if (primitive == Short.TYPE) return Short.class;
        if (primitive == Byte.TYPE) return Byte.class;
        if (primitive == Character.TYPE) return Character.class;
        if (primitive == Boolean.TYPE) return Boolean.class;
        return null;
    }

    /** 判断 token 是否为 Java 访问/修饰符关键字。 */
    private boolean isModifierToken(Token token) {
        TokenType type = token.type();
        return type == TokenType.KEYWORD_PUBLIC
                || type == TokenType.KEYWORD_PRIVATE
                || type == TokenType.KEYWORD_PROTECTED
                || type == TokenType.KEYWORD_STATIC
                || type == TokenType.KEYWORD_FINAL
                || type == TokenType.KEYWORD_ABSTRACT
                || type == TokenType.KEYWORD_NATIVE
                || type == TokenType.KEYWORD_SYNCHRONIZED
                || type == TokenType.KEYWORD_VOLATILE
                || type == TokenType.KEYWORD_TRANSIENT
                || type == TokenType.KEYWORD_STRICTFP;
    }

    /**
     * 将类型名字符串解析为 ClassReferenceNode（含 resolvedClass）。
     * 支持原始类型（int, double 等）和引用类型（String, java.util.List 等）。
     */
    private ClassReferenceNode resolveTypeReference(String typeName) {
        // 原始类型映射
        Class<?> primitive = switch (typeName) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "boolean" -> boolean.class;
            case "char" -> char.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "void" -> void.class;
            default -> null;
        };

        if (primitive != null) {
            return new ClassReferenceNode.Builder()
                    .originalTypeName(typeName).resolvedClass(primitive)
                    .isPrimitive(true).location(createLocation()).build();
        }

        // 引用类型：通过 context 解析
        Class<?> resolved = context.resolveClass(typeName);
        return new ClassReferenceNode.Builder()
                .originalTypeName(typeName).resolvedClass(resolved != null ? resolved : Object.class)
                .location(createLocation()).build();
    }

    /**
     * 可选地消费分号（用于 switch case 等场景）。
     */
    protected void consumeOptionalSemicolon() {
        match(TokenType.DELIMITER_SEMICOLON);
    }
}
