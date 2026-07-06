package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cythava 声明解析器。
 * <p>
 * 解析顶层声明和类成员声明，包括：
 * <ul>
 *   <li>import 语句</li>
 *   <li>package 声明</li>
 *   <li>class / interface 定义（含字段、方法、构造器）</li>
 *   <li>函数定义（顶层或类成员）</li>
 *   <li>注解</li>
 * </ul>
 * </p>
 */
public class DeclParser extends BaseParser {

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
                if (e.getMessage() != null && e.getMessage().contains("Cannot resolve type")) {
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
            return parseFunctionOrVariable(beforeModifiers);
        }

        // 独立注解声明（注解后无其他声明跟随）
        if (!annotations.isEmpty()) {
            return annotations.get(annotations.size() - 1);
        }

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
                    ErrorCode.PARSE_INVALID_SYNTAX);
        }

        // 注册别名到符号表（让后续解析能识别 HMap 等别名）
        context.addTypeAlias(aliasName, targetName);
        context.addImport(targetName);

        return (UsingAliasNode) new UsingAliasNode.Builder().aliasName(aliasName).fullClassName(targetName).location(location).build();
    }

    // ==================== 泛型类型参数 ====================

    /** 解析结果：类型参数列表 + 上界映射。 */
    private record TypeParameterResult(
            List<String> typeParams,
            Map<String, ClassReferenceNode> bounds
    ) {}

    /**
     * 解析类/接口声明中的泛型类型参数列表。
     * <p>
     * 语法: {@code <T>}, {@code <T extends Number>}, {@code <K, V>}, {@code <E extends Comparable<E>>}
     * <p>
     * 调用前已消费了开头的 {@code <}，调用后消费到匹配的 {@code >}。
     *
     * @return TypeParameterResult 包含参数名列表和上界映射
     */
    private TypeParameterResult parseTypeParameterList() throws CythavaParseException {
        List<String> typeParams = new ArrayList<>();
        Map<String, ClassReferenceNode> bounds = new HashMap<>();

        do {
            // 参数名: T, E, K, V 等（单个大写标识符）
            String paramName = consumeOrSemanticError(TokenType.IDENTIFIER,
                    "Expected type parameter name (e.g., T, E)").text();
            typeParams.add(paramName);

            // 可选上界: extends SomeType / super SomeType
            if (match(TokenType.KEYWORD_EXTENDS)) {
                ClassReferenceNode boundType = parseClassReference();
                bounds.put(paramName, boundType);
            } else if (match(TokenType.KEYWORD_SUPER)) {
                // Java 泛型声明中通常只用 extends，但 super 也合法
                ClassReferenceNode boundType = parseClassReference();
                bounds.put(paramName, boundType);
            }
            // 无上界 → 默认 extends Object（bounds 中不记录 = null 表示无界）
        } while (match(TokenType.DELIMITER_COMMA));

        consumeOrSemanticError(TokenType.OPERATOR_GREATER_THAN, "Expected '>' after type parameter list");

        return new TypeParameterResult(typeParams, bounds);
    }

    // ==================== 类定义 ====================

    /** class Name [extends Super] [implements I1, I2] { body } */
    private ClassDeclarationNode parseClassDeclaration(List<AnnotationNode> annotations,
                                                        ClassModifiers modifiers) throws CythavaParseException {
        SourceLocation location = createLocation();

        String className = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected class name").text();

        // ★ 泛型类型参数: <T>, <T extends Number>, <K, V>
        List<String> typeParameters = null;
        Map<String, ClassReferenceNode> typeParamBounds = null;
        if (match(TokenType.OPERATOR_LESS_THAN)) {
            var parsed = parseTypeParameterList();
            typeParameters = parsed.typeParams();
            typeParamBounds = parsed.bounds();
        }

        // extends
        ClassReferenceNode superClass = null;
        if (match(TokenType.KEYWORD_EXTENDS)) {
            superClass = parseClassReference();
        }

        // implements
        List<ClassReferenceNode> interfaces = new ArrayList<>();
        if (match(TokenType.KEYWORD_IMPLEMENTS)) {
            do {
                interfaces.add(parseClassReference());
            } while (match(TokenType.DELIMITER_COMMA));
        }

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before class body");

        ClassDeclarationNode classNode = (ClassDeclarationNode) new ClassDeclarationNode.Builder()
                .className(className).superClass(superClass).interfaces(interfaces)
                .typeParameters(typeParameters).typeParameterBounds(typeParamBounds)
                .location(location).build();
        classNode.getAnnotations().addAll(annotations);
        copyModifiers(classNode.getModifiers(), modifiers);

        // 进入类上下文（使方法签名预注册和字段消歧可用）
        context.enterClass(className);

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            parseClassMember(classNode);
        }

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after class body");

        // 退出类上下文
        context.exitClass();

        context.declareClass(classNode);
        return classNode;
    }

    /** interface Name { body } */
    private ClassDeclarationNode parseInterfaceDeclaration(List<AnnotationNode> annotations,
                                                           ClassModifiers modifiers) throws CythavaParseException {
        SourceLocation location = createLocation();
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected interface name").text();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before interface body");

        ClassDeclarationNode node = (ClassDeclarationNode) new ClassDeclarationNode.Builder().className(name).superClass(null).interfaces(List.of()).location(location).build();
        node.setInterface(true);
        node.getAnnotations().addAll(annotations);
        copyModifiers(node.getModifiers(), modifiers);

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            parseClassMember(node);
        }

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after interface body");
        return node;
    }

    /** enum Name { constants [,] [; members] } */
    private ClassDeclarationNode parseEnumDeclaration() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.KEYWORD_ENUM, "Expected 'enum'");
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected enum name").text();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before enum body");

        ClassDeclarationNode node = (ClassDeclarationNode) new ClassDeclarationNode.Builder().className(name).superClass(null).interfaces(List.of()).location(location).build();

        // 枚举常量作为 public static final 字段注册
        ClassReferenceNode enumType = ClassReferenceNode.of(name, null, false, location);

        if (!check(TokenType.DELIMITER_RIGHT_BRACE)) {
            do {
                Token constToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected enum constant name");
                FieldDeclarationNode constField = (FieldDeclarationNode) new FieldDeclarationNode.Builder()
                        .fieldName(constToken.text())
                        .type(enumType)
                        .modifiers(new ClassModifiers())
                        .location(location).build();
                constField.getModifiers().setPublic(true);
                constField.getModifiers().setStatic(true);
                constField.getModifiers().setFinal(true);
                node.getFields().add(constField);

                if (match(TokenType.DELIMITER_LEFT_PAREN)) {
                    while (!check(TokenType.DELIMITER_RIGHT_PAREN) && !isAtEnd()) {
                        advance();
                    }
                    consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after enum args");
                }
            } while (match(TokenType.DELIMITER_COMMA));

            if (match(TokenType.DELIMITER_SEMICOLON)) {
                while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
                    parseClassMember(node);
                }
            }
        }

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after enum body");
        return node;
    }

    // ==================== 类成员解析 ====================

    /** 解析类体中的单个成员（字段、方法、构造器）。 */
    private void parseClassMember(ClassDeclarationNode classNode) throws CythavaParseException {
        List<AnnotationNode> annotations = tryParseAnnotations();
        ClassModifiers modifiers = parseModifiers();

        // 构造器: ClassName(...)
        if (checkTypeNameIsClassName(classNode.getClassName()) && checkNext(TokenType.DELIMITER_LEFT_PAREN)) {
            ConstructorDeclarationNode constructorDecl = parseConstructor(
                    classNode.getClassName(), modifiers, annotations);
            classNode.getConstructors().add(constructorDecl);
            return;
        }

        // 方法: Type methodName(...) 或 void methodName(...)
        if (isMethodStart()) {
            MethodDeclarationNode method = parseMethodDeclaration(modifiers, annotations);
            classNode.getMethods().add(method);
            return;
        }

        // 字段: Type fieldName [= value] [, fieldName2 [= value2]] ;
        // 支持逗号分隔多字段声明：int a, b, c;
        FieldDeclarationNode firstField = parseFieldDeclaration(modifiers, annotations);
        classNode.getFields().add(firstField);
        context.addField(firstField.getFieldName());

        // 逗号分隔的后续字段（共享第一个字段的类型）
        ClassReferenceNode fieldType = firstField.getType();
        while (match(TokenType.DELIMITER_COMMA)) {
            SourceLocation loc = createLocation();
            String nextName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected field name").text();
            ASTNode nextInit = null;
            if (match(TokenType.OPERATOR_ASSIGN)) {
                nextInit = parseExpression();
            }
            FieldDeclarationNode nextField = (FieldDeclarationNode) new FieldDeclarationNode.Builder()
                    .fieldName(nextName).type(fieldType).initialValue(nextInit).modifiers(new ClassModifiers()).location(loc).build();
            classNode.getFields().add(nextField);
            context.addField(nextName);
        }
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after field declaration");
    }

    /** 解析方法声明。 */
    private MethodDeclarationNode parseMethodDeclaration(ClassModifiers modifiers,
                                                            List<AnnotationNode> annotations) throws CythavaParseException {
        SourceLocation location = createLocation();

        ClassReferenceNode returnType = parseTypeReference();
        String methodName;
        if (isKeyword(Keywords.OPERATOR)) {
            methodName = advance().text();
        } else {
            methodName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected method name").text();
        }

        if (Keywords.OPERATOR.equals(methodName) && !check(TokenType.DELIMITER_LEFT_PAREN)) {
            StringBuilder opBuilder = new StringBuilder(Keywords.OPERATOR);
            // 拼接运算符符号（支持单字符和多字符运算符）
            while (!check(TokenType.DELIMITER_LEFT_PAREN)
                    && !check(TokenType.DELIMITER_COMMA)
                    && peek() != null
                    && isOperatorToken(peek().type())) {
                opBuilder.append(advance().text());
            }
            methodName = opBuilder.toString();
        }

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after method name");
        List<ParameterNode> parameters = parseParameterList();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after parameters");


        // ★ 先注册方法签名到上下文（使同类方法互调可见），再解析方法体
        List<Class<?>> paramTypeList = new ArrayList<>();
        for (var p : parameters) {
            ClassReferenceNode pt = p.getType();
            paramTypeList.add(pt != null && pt.getResolvedClass() != null ? pt.getResolvedClass() : Object.class);
        }
        context.registerMethodSignature(methodName, paramTypeList);

        // 解析方法体（此时同类其他方法的签名已可见）
        ASTNode body = null;
        if (match(TokenType.DELIMITER_LEFT_BRACE)) {
            // ★ 进入方法作用域并注册参数，使方法体内的参数引用可被解析
            context.enterScope(ParseContext.ScopeKind.METHOD);
            for (var p : parameters) {
                context.declareVariable(p.getParameterName(),
                        p.getType() != null && p.getType().getResolvedClass() != null
                                ? p.getType().getResolvedClass() : Object.class);
            }
            body = parseBlock();
            context.exitScope();
        } else {
            consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' or '{' after method signature");
        }

        MethodDeclarationNode method = (MethodDeclarationNode) new MethodDeclarationNode.Builder()
                .methodName(methodName).returnType(returnType)
                .parameters(parameters).body(body).modifiers(modifiers).location(location).build();
        method.getAnnotations().addAll(annotations);

        // ★ 运算符重载注册：方法名以 "operator" 开头时自动注册到 OperatorRegistry
        if (methodName.startsWith(Keywords.OPERATOR) && methodName.length() > Keywords.OPERATOR.length()) {
            String opSymbol = methodName.substring(Keywords.OPERATOR.length());
            if (OperatorRegistry.BINARY_OPERATORS.contains(opSymbol)) {
                Class<?> retClass = returnType != null && returnType.getResolvedClass() != null
                        ? returnType.getResolvedClass() : Object.class;
                if (paramTypeList.size() == 2) {
                    context.getOperatorRegistry().registerBinary(
                            opSymbol, paramTypeList.get(0), paramTypeList.get(1),
                            retClass, method);
                } else if (paramTypeList.size() == 1) {
                    context.getOperatorRegistry().registerUnary(
                            opSymbol, paramTypeList.get(0),
                            retClass, method);
                }
                // 参数数量不匹配时静默忽略（由后续使用时报错更合理）
            }
        }

        return method;
    }

    /** 解析构造器。 */
    private ConstructorDeclarationNode parseConstructor(String className, ClassModifiers modifiers,
                                                         List<AnnotationNode> annotations) throws CythavaParseException {
        SourceLocation location = createLocation();
        advance(); // 消费类名标识符

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after constructor name");
        List<ParameterNode> parameters = parseParameterList();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after constructor parameters");

        ASTNode body = null;
        if (match(TokenType.DELIMITER_LEFT_BRACE)) {
            body = parseBlock();
        }

        ConstructorDeclarationNode constructorDecl =
                (ConstructorDeclarationNode) new ConstructorDeclarationNode.Builder()
                .className(className).parameters(parameters).body(body).modifiers(modifiers).location(location).build();
        constructorDecl.getAnnotations().addAll(annotations);
        return constructorDecl;
    }

    /** 解析字段声明（不含末尾分号，由调用方处理 , 或 ; 分隔）。 */
    private FieldDeclarationNode parseFieldDeclaration(ClassModifiers modifiers,
                                                        List<AnnotationNode> annotations) throws CythavaParseException {
        SourceLocation location = createLocation();
        ClassReferenceNode type = parseTypeReference();
        String fieldName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected field name").text();

        ASTNode initialValue = null;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            initialValue = parseExpression();
        }

        FieldDeclarationNode field = (FieldDeclarationNode) new FieldDeclarationNode.Builder().fieldName(fieldName).type(type).initialValue(initialValue).modifiers(modifiers).location(location).build();
        field.getAnnotations().addAll(annotations);
        return field;
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

    // ==================== 注解 ====================

    /** 解析零或多个注解。 */
    private List<AnnotationNode> tryParseAnnotations() throws CythavaParseException {
        List<AnnotationNode> annotations = new ArrayList<>();
        while (check(TokenType.DELIMITER_AT)) {
            annotations.add(parseSingleAnnotation());
        }
        return annotations;
    }

    /** "@Name" 或 "@Name(value)" 或 "@Name(k1=v1, k2=v2)" */
    private AnnotationNode parseSingleAnnotation() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.DELIMITER_AT, "Expected '@'");
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected annotation name").text();

        if (!match(TokenType.DELIMITER_LEFT_PAREN)) {
            return new AnnotationNode.Builder().annotationName(name).location(location).build();
        }

        if (match(TokenType.DELIMITER_RIGHT_PAREN)) {
            return new AnnotationNode.Builder().annotationName(name).location(location).build();
        }

        // 键值对模式: key=value, key2=value2
        if (check(TokenType.IDENTIFIER) && checkNext(TokenType.OPERATOR_ASSIGN)) {
            Map<String, Object> values = new LinkedHashMap<>();
            do {
                String key = advance().text();
                advance(); // =
                Object value = parseAnnotationValue();
                values.put(key, value);
            } while (match(TokenType.DELIMITER_COMMA));
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after annotation values");
            return new AnnotationNode.Builder().annotationName(name).values(values).location(location).build();
        }

        // 单个位置值 @Name(value)
        Object singleValue = parseAnnotationValue();
        if (!match(TokenType.DELIMITER_COMMA)) {
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after annotation value");
            return new AnnotationNode.Builder().annotationName(name).values(Map.of("value", singleValue)).location(location).build();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", singleValue);
        do {
            String key = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected annotation key").text();
            consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "Expected '=' in annotation");
            values.put(key, parseAnnotationValue());
        } while (match(TokenType.DELIMITER_COMMA));
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after annotation values");
        return new AnnotationNode.Builder().annotationName(name).values(values).location(location).build();
    }

    /** 注解值（字面量、枚举引用、注解、数组）。 */
    private Object parseAnnotationValue() throws CythavaParseException {
        if (match(TokenType.DELIMITER_LEFT_BRACE)) {
            List<Object> elements = new ArrayList<>();
            if (!check(TokenType.DELIMITER_RIGHT_BRACE)) {
                do {
                    elements.add(parseAnnotationValue());
                } while (match(TokenType.DELIMITER_COMMA));
            }
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after annotation array");
            return elements.toArray();
        }
        if (check(TokenType.DELIMITER_AT)) {
            return parseSingleAnnotation();
        }
        Token valueToken = advance();
        TokenType tt = valueToken.type();
        if (tt == TokenType.LITERAL_STRING) return valueText(valueToken);
        if (tt == TokenType.LITERAL_CHAR) return valueText(valueToken).charAt(0);
        if (tt == TokenType.LITERAL_INTEGER) return Integer.parseInt(valueText(valueToken));
        if (tt == TokenType.LITERAL_LONG) return Long.parseLong(valueText(valueToken));
        if (tt == TokenType.KEYWORD_TRUE) return Boolean.TRUE;
        if (tt == TokenType.KEYWORD_FALSE) return Boolean.FALSE;
        // 数字字面量（double/float 可能被 Lexer 归为同一类型）
        String text = valueText(valueToken);
        try { return Double.parseDouble(text); }
        catch (NumberFormatException e1) {
            try { return Float.parseFloat(text); }
            catch (NumberFormatException e2) { return text; }
        }
    }

    // ==================== 修饰符 ====================

    /** 解析修饰符序列。 */
    private ClassModifiers parseModifiers() {
        ClassModifiers modifiers = new ClassModifiers();
        while (isModifierKeyword(peek().type())) {
            TokenType mod = advance().type();
            applyModifier(modifiers, mod);
        }
        return modifiers;
    }

    private static boolean isModifierKeyword(TokenType type) {
        return type == TokenType.KEYWORD_PUBLIC
                || type == TokenType.KEYWORD_PRIVATE
                || type == TokenType.KEYWORD_PROTECTED
                || type == TokenType.KEYWORD_STATIC
                || type == TokenType.KEYWORD_FINAL
                || type == TokenType.KEYWORD_ABSTRACT
                || type == TokenType.KEYWORD_NATIVE
                || type == TokenType.KEYWORD_SYNCHRONIZED;
    }

    private static void applyModifier(ClassModifiers modifiers, TokenType type) {
        if (type == TokenType.KEYWORD_PUBLIC) modifiers.setPublic(true);
        else if (type == TokenType.KEYWORD_PRIVATE) modifiers.setPrivate(true);
        else if (type == TokenType.KEYWORD_PROTECTED) modifiers.setProtected(true);
        else if (type == TokenType.KEYWORD_STATIC) modifiers.setStatic(true);
        else if (type == TokenType.KEYWORD_FINAL) modifiers.setFinal(true);
        else if (type == TokenType.KEYWORD_ABSTRACT) modifiers.setAbstract(true);
        else if (type == TokenType.KEYWORD_NATIVE) modifiers.setNative(true);
        else if (type == TokenType.KEYWORD_SYNCHRONIZED) modifiers.setSynchronized(true);
    }

    private static void copyModifiers(ClassModifiers target, ClassModifiers source) {
        target.setPublic(source.isPublic());
        target.setPrivate(source.isPrivate());
        target.setProtected(source.isProtected());
        target.setStatic(source.isStatic());
        target.setFinal(source.isFinal());
        target.setAbstract(source.isAbstract());
        target.setNative(source.isNative());
        target.setSynchronized(source.isSynchronized());
    }

    // ==================== 参数列表 ====================

    /** 解析方法/构造器的形式参数列表。 */
    private List<ParameterNode> parseParameterList() throws CythavaParseException {
        List<ParameterNode> params = new ArrayList<>();
        if (check(TokenType.DELIMITER_RIGHT_PAREN)) {
            return params;
        }
        do {
            params.add(parseFormalParameter());
        } while (match(TokenType.DELIMITER_COMMA));
        return params;
    }

    /** 单个形式参数: Type paramName */
    private ParameterNode parseFormalParameter() throws CythavaParseException {
        SourceLocation location = createLocation();
        ClassReferenceNode type = parseTypeReference();
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected parameter name").text();
        return (ParameterNode) new ParameterNode.Builder().parameterName(name).type(type).location(location).build();
    }

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

    /** 解析类型引用为 ClassReferenceNode。 */
    private ClassReferenceNode parseTypeReference() throws CythavaParseException {
        Token first = advance();
        StringBuilder typeName = new StringBuilder(first.text());

        int dimensions = 0;
        while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in type");
            dimensions++;
            typeName.append("[]");
        }

        String name = typeName.toString();
        Class<?> resolved = context.resolveClass(name);
        boolean isPrim = resolved != null && resolved.isPrimitive();

        if (dimensions > 0) {
            return new ClassReferenceNode.Builder().originalTypeName(name)
                    .resolvedClass(resolved != null ? resolved : Object.class).isPrimitive(isPrim)
                    .arrayDepth(dimensions).typeArguments(null).location(createLocation()).build();
        }
        return ClassReferenceNode.of(name, resolved != null ? resolved : Object.class,
                isPrim, createLocation());
    }


    /** 解析类引用（用于 extends/implements）。 */
    private ClassReferenceNode parseClassReference() throws CythavaParseException {
        StringBuilder name = new StringBuilder(advance().text());
        while (match(TokenType.OPERATOR_DOT)) {
            name.append('.').append(
                    consumeOrSemanticError(TokenType.IDENTIFIER, "Expected identifier in class reference").text());
        }
        String className = name.toString();
        Class<?> resolved = context.resolveClass(className);
        return ClassReferenceNode.of(className, resolved != null ? resolved : Object.class,
                false, createLocation());
    }

    // ==================== 表达式/语句辅助 ====================

    /** 使用 ExprParser 解析表达式（创建新实例避免状态污染）。 */
    private ASTNode parseExpression() throws CythavaParseException {
        ExprParser parser = new ExprParser(tokens, context, fileName);
        parser.setPosition(position);
        ASTNode result = parser.parseNextExpression();
        position = parser.getPosition();
        return result;
    }

    /** 使用 StmtParser 解析语句。 */
    private ASTNode parseStatement() throws CythavaParseException {
        StmtParser parser = new StmtParser(tokens, context, fileName);
        parser.setPosition(position);
        ASTNode result = parser.parseNextStatement();
        position = parser.getPosition();
        return result;
    }

    /** 解析块语句 { statements } */
    private BlockNode parseBlock() throws CythavaParseException {
        StmtParser parser = new StmtParser(tokens, context, fileName);
        parser.setPosition(position);
        BlockNode block = parser.parseBlock();
        position = parser.getPosition();
        return block;
    }

    /** 解析限定名称（点分隔的标识符序列）。 */
    private String parseQualifiedName() throws CythavaParseException {
        StringBuilder sb = new StringBuilder(advance().text());
        while (match(TokenType.OPERATOR_DOT)) {
            sb.append('.').append(
                    consumeOrSemanticError(TokenType.IDENTIFIER, "Expected identifier in qualified name").text());
        }
        return sb.toString();
    }

    // ==================== 判断辅助 ====================

    /** 当前是否看起来像方法声明的开头。 */
    private boolean isMethodStart() {
        if (isAtEnd()) return false;
        if (isTypeStartToken() && position + 1 < tokens.size()) {
            // ★ 普通: Type methodName(...) → position+1=IDENT, position+2=(
            if (position + 2 < tokens.size()
                    && tokens.get(position + 1).type() == TokenType.IDENTIFIER
                    && tokens.get(position + 2).type() == TokenType.DELIMITER_LEFT_PAREN) {
                return true;
            }
            // ★ 运算符重载: Type operator+(...) → position+1=IDENTIFIER("operator"), 后跟运算符token, 最后(
            if (tokens.get(position + 1).type() == TokenType.IDENTIFIER
                    && Keywords.OPERATOR.equals(tokens.get(position + 1).text())) {
                int lookAhead = position + 2;
                while (lookAhead < tokens.size() && isOperatorToken(tokens.get(lookAhead).type())) {
                    lookAhead++;
                }
                return lookAhead < tokens.size()
                        && tokens.get(lookAhead).type() == TokenType.DELIMITER_LEFT_PAREN;
            }
        }
        return false;
    }

    /** 当前 token 是否与给定的类名匹配（用于识别构造器）。 */
    private boolean checkTypeNameIsClassName(String className) {
        return check(TokenType.IDENTIFIER) && peek().text().equals(className);
    }

    /** 检查当前 token 是否为指定的关键字文本（用于非 TokenType 枚举的关键字）。 */
    private boolean isKeyword(String keyword) {
        return check(TokenType.IDENTIFIER) && keyword.equals(peek().text());
    }

    /** 消费指定文本的关键字标识符。 */
    private void consumeIdentifier(String keyword) throws CythavaParseException {
        Token token = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected '" + keyword + "'");
        if (!keyword.equals(token.text())) {
            throw error("Expected '" + keyword + "' but got '" + token.text() + "'",
                    ErrorCode.PARSE_UNEXPECTED_TOKEN);
        }
    }

    /** 判断 token 类型是否为运算符符号（用于 operator+ 等方法名拼接）。 */
    private boolean isOperatorToken(TokenType type) {
        return switch (type) {
            case OPERATOR_PLUS, OPERATOR_MINUS, OPERATOR_MULTIPLY, OPERATOR_DIVIDE,
                OPERATOR_MODULO, OPERATOR_POWER, OPERATOR_INT_DIVIDE,
                OPERATOR_ASSIGN, OPERATOR_PLUS_ASSIGN, OPERATOR_MINUS_ASSIGN,
                OPERATOR_MULTIPLY_ASSIGN, OPERATOR_DIVIDE_ASSIGN, OPERATOR_MODULO_ASSIGN,
                OPERATOR_EQUAL, OPERATOR_NOT_EQUAL, OPERATOR_LESS_THAN,
                OPERATOR_GREATER_THAN, OPERATOR_LESS_THAN_OR_EQUAL,
                OPERATOR_GREATER_THAN_OR_EQUAL, OPERATOR_SPACESHIP,
                OPERATOR_LOGICAL_AND, OPERATOR_LOGICAL_OR, OPERATOR_LOGICAL_NOT,
                OPERATOR_BITWISE_AND, OPERATOR_BITWISE_OR, OPERATOR_BITWISE_XOR,
                OPERATOR_BITWISE_NOT, OPERATOR_LEFT_SHIFT, OPERATOR_RIGHT_SHIFT,
                OPERATOR_UNSIGNED_RIGHT_SHIFT,
                OPERATOR_INCREMENT, OPERATOR_DECREMENT,
                OPERATOR_NOT_NULL, OPERATOR_PIPELINE -> true;
            default -> false;
        };
    }

    /** 将基本类型名字符串映射为 Class 对象。 */

    private static Class<?> refToClass(ClassReferenceNode ref) {
        return ref.getResolvedClass();
    }

    /** 获取 token 的文本内容。 */
    private String valueText(Token token) {
        return token.text();
    }
}
