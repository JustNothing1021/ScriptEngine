package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cythava 声明解析层（类体声明）。
 * <p>
 * 解析类成员声明，包括：
 * <ul>
 *   <li>class / interface 定义（含字段、方法、构造器）</li>
 *   <li>注解</li>
 * </ul>
 * </p>
 *
 * @see BaseParser
 */
abstract class ClassBodyParser extends BaseParser {

    /**
     * 记录隐式成员（equals / hashCode）里用到的工具类。
     * <p>
     * 用简单名即可：{@code java.util.*} 是引擎的默认 import，脚本无需自己声明。
     * </p>
     */
    private static final String OBJECTS = "Objects";

    /**
     * 构造器。
     *
     * @param tokens   token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    protected ClassBodyParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
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
                ClassReferenceNode boundType = parseTypeReference();
                bounds.put(paramName, boundType);
            } else if (match(TokenType.KEYWORD_SUPER)) {
                // Java 泛型声明中通常只用 extends，但 super 也合法
                ClassReferenceNode boundType = parseTypeReference();
                bounds.put(paramName, boundType);
            }
            // 无上界 → 默认 extends Object（bounds 中不记录 = null 表示无界）
        } while (match(TokenType.DELIMITER_COMMA));

        consumeGenericClose("Expected '>' after type parameter list");

        return new TypeParameterResult(typeParams, bounds);
    }

    // ==================== 类定义 ====================

    /** class Name [extends Super] [implements I1, I2] { body } */
    protected ClassDeclarationNode parseClassDeclaration(List<AnnotationNode> annotations,
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
            superClass = parseTypeReference();
        }

        // implements
        List<ClassReferenceNode> interfaces = new ArrayList<>();
        if (match(TokenType.KEYWORD_IMPLEMENTS)) {
            do {
                interfaces.add(parseTypeReference());
            } while (match(TokenType.DELIMITER_COMMA));
        }

        // permits A, B — sealed 类型的许可子类型
        List<ClassReferenceNode> permittedSubclasses = parsePermittedSubclasses();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before class body");

        ClassDeclarationNode classNode = (ClassDeclarationNode) new ClassDeclarationNode.Builder()
                .className(className).superClass(superClass).interfaces(interfaces)
                .permittedSubclasses(permittedSubclasses)
                .typeParameters(typeParameters).typeParameterBounds(typeParamBounds)
                .location(location).build();
        classNode.getAnnotations().addAll(annotations);
        copyModifiers(classNode.getModifiers(), modifiers);

        // 进入类上下文（使方法签名预注册和字段消歧可用）
        context.enterClass(className);

        // ★ 先扫一遍类体，登记所有字段名/方法名：类成员是按书写顺序解析的，
        //   否则方法体内引用声明在后面的字段会报 "Cannot find symbol"
        preRegisterClassMembers();

        try {
            while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
                parseClassMember(classNode);
            }
        } finally {
            // 成员解析失败时也要退出类上下文，否则后续解析会误以为仍在类体内
            context.exitClass();
        }

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after class body");

        context.declareClass(classNode);
        return classNode;
    }

    /**
     * 预扫描类体，登记成员名（字段 → {@code addField}，方法 → {@code registerMethodSignature}）。
     * <p>
     * 只做轻量的 token 级识别：在类体的花括号层级 0 上，找出形如
     * {@code <类型结尾> <名字> <声明分隔符>} 的三元组。括号/方括号内（形参列表、
     * 构造实参、数组下标）以及嵌套块内一律跳过，避免把形参、局部变量误认成字段。
     * </p>
     */
    private void preRegisterClassMembers() {
        int braceDepth = 0;
        int parenDepth = 0;
        for (int i = position; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.DELIMITER_LEFT_BRACE) {
                braceDepth++;
                continue;
            }
            if (type == TokenType.DELIMITER_RIGHT_BRACE) {
                if (braceDepth == 0) {
                    return; // 类体结束
                }
                braceDepth--;
                continue;
            }
            if (type == TokenType.DELIMITER_LEFT_PAREN || type == TokenType.DELIMITER_LEFT_BRACKET) {
                parenDepth++;
                continue;
            }
            if (type == TokenType.DELIMITER_RIGHT_PAREN || type == TokenType.DELIMITER_RIGHT_BRACKET) {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (braceDepth != 0 || parenDepth != 0) {
                continue;
            }
            if (!isMemberNameCandidate(i)) {
                continue;
            }
            String name = tokens.get(i).text();
            if (tokens.get(i + 1).type() == TokenType.DELIMITER_LEFT_PAREN) {
                context.registerMethodSignature(name, List.of());
            } else {
                context.addField(name);
            }
        }
    }

    /** 判断 tokens[i] 是否像类成员声明中的“名字”（前面像类型结尾，后面像声明分隔符）。 */
    private boolean isMemberNameCandidate(int i) {
        if (i == 0 || i + 1 >= tokens.size()) {
            return false;
        }
        if (tokens.get(i).type() != TokenType.IDENTIFIER) {
            return false;
        }
        TokenType prev = tokens.get(i - 1).type();
        boolean prevEndsType = prev == TokenType.IDENTIFIER
                || isPrimitiveTypeKeyword(prev)
                || prev == TokenType.OPERATOR_GREATER_THAN      // Map<K, V> m
                || prev == TokenType.DELIMITER_RIGHT_BRACKET    // int[] a
                || prev == TokenType.DELIMITER_COMMA;           // int a, b
        if (!prevEndsType) {
            return false;
        }
        TokenType next = tokens.get(i + 1).type();
        return next == TokenType.DELIMITER_LEFT_PAREN
                || next == TokenType.OPERATOR_ASSIGN
                || next == TokenType.DELIMITER_SEMICOLON
                || next == TokenType.DELIMITER_COMMA
                || next == TokenType.DELIMITER_LEFT_BRACKET;
    }

    /** interface Name [permits A, B] { body } */
    protected ClassDeclarationNode parseInterfaceDeclaration(List<AnnotationNode> annotations,
                                                           ClassModifiers modifiers) throws CythavaParseException {
        SourceLocation location = createLocation();
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected interface name").text();

        List<ClassReferenceNode> permittedSubclasses = parsePermittedSubclasses();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before interface body");

        ClassDeclarationNode node = (ClassDeclarationNode) new ClassDeclarationNode.Builder().className(name).superClass(null)
                .interfaces(List.of()).permittedSubclasses(permittedSubclasses).location(location).build();
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
    protected ClassDeclarationNode parseEnumDeclaration() throws CythavaParseException {
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

    // ==================== 记录 / sealed ====================

    /**
     * 判断当前 token 是否是 {@code record} 声明的开头。
     * <p>
     * {@code record} 是上下文关键字：只有后面紧跟「记录名 + 泛型参数或组件列表」时才算声明，
     * 否则 {@code record} 仍是普通标识符（如 {@code record x = 1;} 是变量声明）。
     * </p>
     */
    protected boolean isRecordDeclarationStart() {
        if (!check(TokenType.IDENTIFIER) || !Keywords.RECORD.equals(peek().text())) {
            return false;
        }
        if (peek(1).type() != TokenType.IDENTIFIER) {
            return false;
        }
        TokenType next = peek(2).type();
        return next == TokenType.DELIMITER_LEFT_PAREN || next == TokenType.OPERATOR_LESS_THAN;
    }

    /** 解析 {@code permits A, B} 子句。{@code permits} 是上下文关键字。 */
    private List<ClassReferenceNode> parsePermittedSubclasses() throws CythavaParseException {
        List<ClassReferenceNode> permitted = new ArrayList<>();
        if (!check(TokenType.IDENTIFIER) || !Keywords.PERMITS.equals(peek().text())) {
            return permitted;
        }
        advance(); // permits
        do {
            permitted.add(parseTypeReference());
        } while (match(TokenType.DELIMITER_COMMA));
        return permitted;
    }

    /**
     * {@code record Name<T>(Type x, ...) [implements I] [permits A] { body }}
     * <p>
     * 记录在解析期就脱糖成普通类：每个组件生成一个 {@code private final} 同名字段，
     * 再加一个规范构造器和一组访问器。用户显式声明的同签名成员不会被重复生成。
     * </p>
     */
    protected ClassDeclarationNode parseRecordDeclaration(List<AnnotationNode> annotations,
                                                        ClassModifiers modifiers) throws CythavaParseException {
        SourceLocation location = createLocation();
        String name = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected record name").text();

        List<String> typeParameters = null;
        Map<String, ClassReferenceNode> typeParamBounds = null;
        if (match(TokenType.OPERATOR_LESS_THAN)) {
            var parsed = parseTypeParameterList();
            typeParameters = parsed.typeParams();
            typeParamBounds = parsed.bounds();
        }

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' before record components");
        List<ParameterNode> components = parseParameterList();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after record components");

        List<ClassReferenceNode> interfaces = new ArrayList<>();
        if (match(TokenType.KEYWORD_IMPLEMENTS)) {
            do {
                interfaces.add(parseTypeReference());
            } while (match(TokenType.DELIMITER_COMMA));
        }
        List<ClassReferenceNode> permittedSubclasses = parsePermittedSubclasses();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before record body");

        ClassDeclarationNode node = (ClassDeclarationNode) new ClassDeclarationNode.Builder()
                .className(name).superClass(null).interfaces(interfaces)
                .permittedSubclasses(permittedSubclasses)
                .typeParameters(typeParameters).typeParameterBounds(typeParamBounds)
                .recordFlag(true).recordComponents(components)
                .location(location).build();
        node.getAnnotations().addAll(annotations);
        copyModifiers(node.getModifiers(), modifiers);
        node.getModifiers().setFinal(true); // 记录隐含 final

        context.enterClass(name);
        try {
            // 组件即字段：先登记，类体里的方法才能引用它们
            for (ParameterNode component : components) {
                node.addField(createRecordComponentField(component, location));
                context.addField(component.getParameterName());
            }
            preRegisterClassMembers();
            while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
                parseClassMember(node);
            }
            // 隐式成员必须在类上下文里生成：合成出来的源码要用到 this.x、
            // instanceof 记录名、强制转换，这些都依赖 enterClass 建立的上下文
            desugarRecordMembers(node, location);
        } finally {
            context.exitClass();
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after record body");

        context.declareClass(node);
        return node;
    }

    /**
     * 记录组件对应的字段。
     * <p>
     * 只标 {@code final}：字节码生成时字段会被无条件加上 {@code ACC_PUBLIC}，
     * 标 {@code private} 会得到非法修饰符组合。
     * </p>
     */
    private static FieldDeclarationNode createRecordComponentField(ParameterNode component,
                                                                  SourceLocation location) {
        ClassModifiers fieldModifiers = new ClassModifiers();
        fieldModifiers.setFinal(true);
        return (FieldDeclarationNode) new FieldDeclarationNode.Builder()
                .fieldName(component.getParameterName())
                .type(component.getType())
                .modifiers(fieldModifiers)
                .location(location).build();
    }

    /**
     * 生成记录的隐式成员：规范构造器（{@code this.x = x;}）、访问器（{@code return this.x;}），
     * 以及记录协议要求的 {@code equals} / {@code hashCode} / {@code toString}。
     * <p>用户显式写了同名成员时不重复生成，与 Java 的规则一致。</p>
     */
    private void desugarRecordMembers(ClassDeclarationNode node, SourceLocation location)
            throws CythavaParseException {
        List<ParameterNode> components = node.getRecordComponents();

        boolean hasCanonicalConstructor = node.getConstructors().stream()
                .anyMatch(ctor -> hasSameParameterTypes(ctor.getParameters(), components));
        if (!hasCanonicalConstructor && !components.isEmpty()) {
            List<ASTNode> assignments = new ArrayList<>();
            for (ParameterNode component : components) {
                assignments.add(new FieldAssignmentNode.Builder()
                        .target(selfReference(location))
                        .fieldName(component.getParameterName())
                        .value(new VariableNode.Builder()
                                .name(component.getParameterName()).location(location).build())
                        .location(location).build());
            }
            node.addConstructor((ConstructorDeclarationNode) new ConstructorDeclarationNode.Builder()
                    .className(node.getClassName())
                    .parameters(components)
                    .body(new BlockNode.Builder().statements(assignments).location(location).build())
                    .location(location).build());
        }

        for (ParameterNode component : components) {
            String componentName = component.getParameterName();
            boolean alreadyDeclared = node.getMethods().stream()
                    .anyMatch(method -> componentName.equals(method.getMethodName()));
            if (alreadyDeclared) {
                continue;
            }
            ClassModifiers methodModifiers = new ClassModifiers();
            methodModifiers.setPublic(true);
            node.addMethod((MethodDeclarationNode) new MethodDeclarationNode.Builder()
                    .methodName(componentName)
                    .returnType(component.getType())
                    .parameters(List.of())
                    .modifiers(methodModifiers)
                    .body(new ReturnNode.Builder()
                            .value(new FieldAccessNode.Builder()
                                    .target(selfReference(location))
                                    .fieldName(componentName)
                                    .location(location).build())
                            .location(location).build())
                    .location(location).build());
        }

        parseRecordProtocolMembers(node);
    }

    /**
     * 合成 {@code equals} / {@code hashCode} / {@code toString} 的源码，再交给真实解析器解析出成员。
     * <p>
     * 不走手搓 AST：静态调用、方法调用这些节点的形状与类型标注都由解析器自己决定，
     * 手搓容易和解析器的实际产物不一致。此时类上下文已经建立、组件字段也已登记，
     * 所以合成源码里的 {@code this.x} 能与用户手写时一样解析成字段访问。
     * </p>
     * <p>
     * 注意合成源码里刻意<b>不出现记录自己的类名</b>：解析期该类的字节码还没生成，
     * 严格模式下引用它就是「前向引用」错误。所以 {@code equals} 用
     * {@code getClass()} 判类型、用访问器读对方组件，而不是 {@code instanceof} + 强制转换。
     * </p>
     */
    private void parseRecordProtocolMembers(ClassDeclarationNode node) throws CythavaParseException {
        StringBuilder source = new StringBuilder();
        if (!hasMethod(node, "equals")) {
            source.append(buildEqualsSource(node));
        }
        if (!hasMethod(node, "hashCode")) {
            source.append(buildHashCodeSource(node));
        }
        if (!hasMethod(node, "toString")) {
            source.append(buildToStringSource(node));
        }
        if (source.length() == 0) {
            return;
        }

        // 静态类型写成 ClassBodyParser：parseClassMember 是本类的私有方法，
        // 通过子类类型的引用看不到它（私有成员不参与继承）
        ClassBodyParser parser = new DeclParser(
                new Lexer(source.toString(), fileName).tokenize(), context, fileName);
        parser.setPosition(0);
        // 合成体里会出现局部量（equals 里的 that）：开一层作用域，别污染类作用域
        context.enterScope(ParseContext.ScopeKind.METHOD);
        try {
            while (parser.peek().type() != TokenType.EOF) {
                parser.parseClassMember(node);
            }
        } finally {
            context.exitScope();
        }
    }

    /**
     * {@code equals}：先判空、再比类型，最后逐组件比较。
     * <p>
     * 用 {@code Objects.equals} 而不是 {@code ==}，这样基本类型（装箱后）与引用类型都按值比较。
     * 类型判定用 {@code getClass()} 比较：记录隐含 final，没有子类，所以它等价于
     * {@code other instanceof 记录名}，又不必写出自己的类名。
     * 读对方组件走访问器（{@code other.x()}），同样是为了避开类名；若用户覆盖了某个访问器，
     * 比较的就是覆盖后的值，而不是原始字段值。
     * </p>
     * <p>
     * <b>这里一个 {@code ==} / {@code !=} 都不能用</b>：引擎对脚本对象重载了相等运算
     * （{@code ==} 会走 {@code Value.ObjectValue.equals}，进而反射调用脚本类自己的
     * {@code equals}），在 {@code equals} 内部再用 {@code ==} 判身份/判空就是无限递归。
     * 所以身份快路径直接省掉（逐组件比较本来就覆盖了自反的情形），判空改用
     * {@code Objects.isNull}，类型比较改用 {@code Objects.equals}。
     * </p>
     */
    private static String buildEqualsSource(ClassDeclarationNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("public boolean equals(Object other) {\n");
        sb.append("if (").append(OBJECTS).append(".isNull(other)) { return false; }\n");
        sb.append("if (!").append(OBJECTS)
                .append(".equals(getClass(), other.getClass())) { return false; }\n");
        sb.append("return ");
        List<ParameterNode> components = node.getRecordComponents();
        if (components.isEmpty()) {
            sb.append("true");
        } else {
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) {
                    sb.append(" && ");
                }
                String componentName = components.get(i).getParameterName();
                sb.append(OBJECTS).append(".equals(this.").append(componentName)
                        .append(", other.").append(componentName).append("())");
            }
        }
        sb.append(";\n}\n");
        return sb.toString();
    }

    /** {@code hashCode}：{@code Objects.hash(组件…)}，逐组件取哈希后按 31 递推。 */
    private static String buildHashCodeSource(ClassDeclarationNode node) {
        StringBuilder sb = new StringBuilder("public int hashCode() {\nreturn ");
        sb.append(OBJECTS).append(".hash(");
        List<ParameterNode> components = node.getRecordComponents();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("this.").append(components.get(i).getParameterName());
        }
        sb.append(");\n}\n");
        return sb.toString();
    }

    /** {@code toString}：{@code "Point[x=" + x + ", y=" + y + "]"}。 */
    private static String buildToStringSource(ClassDeclarationNode node) {
        String className = node.getClassName();
        List<ParameterNode> components = node.getRecordComponents();
        StringBuilder sb = new StringBuilder("public String toString() {\nreturn \"");
        sb.append(className).append('[');
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(components.get(i).getParameterName()).append('=');
            // 组件值拼在字符串字面量之外
            sb.append("\" + this.").append(components.get(i).getParameterName()).append(" + \"");
        }
        sb.append("]\";\n}\n");
        return sb.toString();
    }

    private static boolean hasMethod(ClassDeclarationNode node, String methodName) {
        return node.getMethods().stream()
                .anyMatch(method -> methodName.equals(method.getMethodName()));
    }

    private static ASTNode selfReference(SourceLocation location) {
        return new VariableNode.Builder().name("this").location(location).build();
    }

    /** 比较两组形参的类型是否一一对应（用于识别用户手写的规范构造器）。 */
    private static boolean hasSameParameterTypes(List<ParameterNode> left, List<ParameterNode> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).getType().getTypeName().equals(right.get(i).getType().getTypeName())) {
                return false;
            }
        }
        return true;
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
            try {
                for (var p : parameters) {
                    context.declareVariable(p.getParameterName(),
                            p.getType() != null && p.getType().getResolvedClass() != null
                                    ? p.getType().getResolvedClass() : Object.class);
                }
                body = parseBlock();
            } finally {
                // 解析失败时也要退出作用域，否则作用域栈不平衡会污染后续解析
                context.exitScope();
            }
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
            // ★ 进入构造器作用域并注册参数，使构造器体内的参数引用可被解析
            //   （与 parseMethodDeclaration 一致；否则 this.x = x 中的 x 会报 Cannot find symbol）
            context.enterScope(ParseContext.ScopeKind.METHOD);
            try {
                for (var p : parameters) {
                    context.declareVariable(p.getParameterName(),
                            p.getType() != null && p.getType().getResolvedClass() != null
                                    ? p.getType().getResolvedClass() : Object.class);
                }
                body = parseBlock();
            } finally {
                context.exitScope();
            }
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

    // ==================== 注解 ====================

    /** 解析零或多个注解。 */
    protected List<AnnotationNode> tryParseAnnotations() throws CythavaParseException {
        List<AnnotationNode> annotations = new ArrayList<>();
        while (check(TokenType.DELIMITER_AT)) {
            if (tryParseStaticAssertAnnotation()) {
                continue; // 编译期语法糖：就地判定并消费，不产生 AnnotationNode
            }
            annotations.add(parseSingleAnnotation());
        }
        return annotations;
    }

    /**
     * 若当前位置是 {@code @StaticAssert(condition [, message])}，就地求值并消费，返回 true。
     * <p>
     * 与语句形式的 {@code static_assert(...)} 同一套判定逻辑，只是允许出现在声明位置
     * （类体成员、类声明之前）。
     * </p>
     */
    private boolean tryParseStaticAssertAnnotation() throws CythavaParseException {
        if (peekType() != TokenType.DELIMITER_AT
                || peek(1).type() != TokenType.IDENTIFIER
                || !STATIC_ASSERT_ANNOTATION.equals(peek(1).text())) {
            return false;
        }
        SourceLocation location = createLocation();
        advance(); // @
        advance(); // StaticAssert

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after '@StaticAssert'");
        ASTNode condition = parseExpression();
        ASTNode message = null;
        if (match(TokenType.DELIMITER_COMMA)) {
            message = parseExpression();
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after '@StaticAssert' arguments");

        verifyStaticAssert(condition, message, location);
        return true;
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
    protected ClassModifiers parseModifiers() {
        ClassModifiers modifiers = new ClassModifiers();
        while (true) {
            if (isModifierKeyword(peek().type())) {
                TokenType mod = advance().type();
                applyModifier(modifiers, mod);
            } else if (isSealedModifier()) {
                advance();
                modifiers.setSealed(true);
            } else if (isNonSealedModifier()) {
                advance(); // non
                advance(); // -
                advance(); // sealed
                modifiers.setNonSealed(true);
            } else {
                return modifiers;
            }
        }
    }

    /** {@code sealed} 是上下文关键字：只有后面还跟着修饰符/声明开启符时才算修饰符。 */
    private boolean isSealedModifier() {
        if (!check(TokenType.IDENTIFIER) || !Keywords.SEALED.equals(peek().text())) {
            return false;
        }
        return isModifierFollowedByDeclaration(1);
    }

    /** {@code non-sealed} 在词法上是三个 token：{@code non} {@code -} {@code sealed}。 */
    private boolean isNonSealedModifier() {
        if (!check(TokenType.IDENTIFIER) || !Keywords.NON.equals(peek().text())) {
            return false;
        }
        if (peek(1).type() != TokenType.OPERATOR_MINUS) {
            return false;
        }
        if (peek(2).type() != TokenType.IDENTIFIER || !Keywords.SEALED.equals(peek(2).text())) {
            return false;
        }
        return isModifierFollowedByDeclaration(3);
    }

    /**
     * 判断偏移 {@code offset} 处是否紧接着一个声明开启符。
     * <p>
     * 用于区分 {@code sealed class Foo}（修饰符）与 {@code sealed = 1;}（标识符）：
     * 只有后面还有修饰符或 class/interface/enum/record 时，前面那个词才是修饰符。
     * </p>
     */
    private boolean isModifierFollowedByDeclaration(int offset) {
        int i = position + offset;
        while (i < tokens.size()) {
            TokenType type = tokens.get(i).type();
            if (isModifierKeyword(type)) {
                i++;
                continue;
            }
            if (type == TokenType.IDENTIFIER) {
                String text = tokens.get(i).text();
                if (Keywords.SEALED.equals(text)) {
                    i++;
                    continue;
                }
                if (Keywords.NON.equals(text) && i + 2 < tokens.size()
                        && tokens.get(i + 1).type() == TokenType.OPERATOR_MINUS
                        && tokens.get(i + 2).type() == TokenType.IDENTIFIER
                        && Keywords.SEALED.equals(tokens.get(i + 2).text())) {
                    i += 3;
                    continue;
                }
                return Keywords.RECORD.equals(text) && i + 1 < tokens.size()
                        && tokens.get(i + 1).type() == TokenType.IDENTIFIER;
            }
            return type == TokenType.KEYWORD_CLASS || type == TokenType.KEYWORD_INTERFACE
                    || type == TokenType.KEYWORD_ENUM;
        }
        return false;
    }

    private static boolean isModifierKeyword(TokenType type) {
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

    private static void applyModifier(ClassModifiers modifiers, TokenType type) {
        if (type == TokenType.KEYWORD_PUBLIC) modifiers.setPublic(true);
        else if (type == TokenType.KEYWORD_PRIVATE) modifiers.setPrivate(true);
        else if (type == TokenType.KEYWORD_PROTECTED) modifiers.setProtected(true);
        else if (type == TokenType.KEYWORD_STATIC) modifiers.setStatic(true);
        else if (type == TokenType.KEYWORD_FINAL) modifiers.setFinal(true);
        else if (type == TokenType.KEYWORD_ABSTRACT) modifiers.setAbstract(true);
        else if (type == TokenType.KEYWORD_NATIVE) modifiers.setNative(true);
        else if (type == TokenType.KEYWORD_SYNCHRONIZED) modifiers.setSynchronized(true);
        else if (type == TokenType.KEYWORD_VOLATILE) modifiers.setVolatile(true);
        else if (type == TokenType.KEYWORD_TRANSIENT) modifiers.setTransient(true);
        else if (type == TokenType.KEYWORD_STRICTFP) modifiers.setStrictfp(true);
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
        target.setVolatile(source.isVolatile());
        target.setTransient(source.isTransient());
        target.setStrictfp(source.isStrictfp());
        target.setSealed(source.isSealed());
        target.setNonSealed(source.isNonSealed());
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

    // ==================== 类型引用辅助 ====================

    /**
     * 解析类型引用为 ClassReferenceNode。
     * <p>
     * 委托 {@link TypeParser}，因而支持限定名（{@code a.b.C}）、泛型实参（含嵌套 {@code >>} 拆分）、
     * 通配符与数组维度。类体里允许出现类的泛型类型参数（如 {@code T value;}），
     * 因此关闭「未知类型报错」。
     * </p>
     */
    protected ClassReferenceNode parseTypeReference() throws CythavaParseException {
        TypeParser typeParser = new TypeParser(tokens, context, fileName);
        typeParser.setPosition(position);
        typeParser.setAllowUnresolvedTypes(true);
        GenericType parsedType;
        try {
            parsedType = typeParser.parseType();
        } finally {
            position = typeParser.getPosition();
        }
        return toClassReferenceNode(parsedType);
    }

    /** 把 GenericType 转成等价的 ClassReferenceNode（递归转换泛型实参）。 */
    private ClassReferenceNode toClassReferenceNode(GenericType type) {
        List<ClassReferenceNode> typeArguments = new ArrayList<>();
        for (GenericType argument : type.getTypeArguments()) {
            typeArguments.add(toClassReferenceNode(argument));
        }
        Class<?> resolved = type.getRawType();
        // 数组维度由 arrayDepth 表达，类型名里不再重复拼接 "[]"
        String name = type.getOriginalTypeName() != null
                ? type.getOriginalTypeName()
                : (resolved != null ? resolved.getName() : "java.lang.Object");
        return new ClassReferenceNode.Builder()
                .originalTypeName(name)
                .resolvedClass(resolved != null ? resolved : Object.class)
                .isPrimitive(resolved != null && resolved.isPrimitive())
                .arrayDepth(type.getArrayDepth())
                .typeArguments(typeArguments)
                .location(createLocation())
                .build();
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
    protected ASTNode parseStatement() throws CythavaParseException {
        StmtParser parser = new StmtParser(tokens, context, fileName);
        parser.setPosition(position);
        try {
            return parser.parseNextStatement();
        } finally {
            // 失败时也要同步位置：否则外层会以为本语句一个 token 都没消费，
            // 从而重复解析同一段输入（报出"重复声明"之类的派生错误）
            position = parser.getPosition();
        }
    }

    /** 解析块语句 { statements } */
    protected BlockNode parseBlock() throws CythavaParseException {
        StmtParser parser = new StmtParser(tokens, context, fileName);
        parser.setPosition(position);
        try {
            return parser.parseBlock();
        } finally {
            position = parser.getPosition();
        }
    }

    /** 解析限定名称（点分隔的标识符序列）。 */
    protected String parseQualifiedName() throws CythavaParseException {
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
        if (!isTypeStartToken()) return false;

        // ★ 运算符重载: Type operator+(...) → position+1=IDENTIFIER("operator"), 后跟运算符token, 最后(
        if (position + 1 < tokens.size()
                && tokens.get(position + 1).type() == TokenType.IDENTIFIER
                && Keywords.OPERATOR.equals(tokens.get(position + 1).text())) {
            int lookAhead = position + 2;
            while (lookAhead < tokens.size() && isOperatorToken(tokens.get(lookAhead).type())) {
                lookAhead++;
            }
            return lookAhead < tokens.size()
                    && tokens.get(lookAhead).type() == TokenType.DELIMITER_LEFT_PAREN;
        }

        // ★ 普通: Type methodName(...)，类型可能带限定名/泛型实参/数组维度
        int nameIndex = skipTypeSuffix(position + 1);
        if (nameIndex >= tokens.size() || tokens.get(nameIndex).type() != TokenType.IDENTIFIER) {
            return false;
        }
        return nameIndex + 1 < tokens.size()
                && tokens.get(nameIndex + 1).type() == TokenType.DELIMITER_LEFT_PAREN;
    }

    /**
     * 从类型的第一个 token 之后开始，跳过限定名、泛型实参和数组维度，
     * 返回「类型之后」的下标（通常是成员名所在位置）。
     */
    private int skipTypeSuffix(int index) {
        int i = index;
        while (i < tokens.size()) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.OPERATOR_DOT && i + 1 < tokens.size()
                    && tokens.get(i + 1).type() == TokenType.IDENTIFIER) {
                i += 2; // 限定名 a.b.C
                continue;
            }
            if (type == TokenType.OPERATOR_LESS_THAN) {
                int depth = 1;
                i++;
                while (i < tokens.size() && depth > 0) {
                    TokenType inner = tokens.get(i).type();
                    if (inner == TokenType.OPERATOR_LESS_THAN) {
                        depth++;
                    } else if (inner == TokenType.OPERATOR_GREATER_THAN) {
                        depth--;
                    } else if (inner == TokenType.OPERATOR_RIGHT_SHIFT) {
                        depth -= 2; // >> = 两个 >
                    } else if (inner == TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT) {
                        depth -= 3; // >>> = 三个 >
                    }
                    i++;
                }
                continue;
            }
            if (type == TokenType.DELIMITER_LEFT_BRACKET && i + 1 < tokens.size()
                    && tokens.get(i + 1).type() == TokenType.DELIMITER_RIGHT_BRACKET) {
                i += 2; // 数组维度 []
                continue;
            }
            break;
        }
        return i;
    }

    /** 当前 token 是否与给定的类名匹配（用于识别构造器）。 */
    private boolean checkTypeNameIsClassName(String className) {
        return check(TokenType.IDENTIFIER) && peek().text().equals(className);
    }

    /** 检查当前 token 是否为指定的关键字文本（用于非 TokenType 枚举的关键字）。 */
    protected boolean isKeyword(String keyword) {
        return check(TokenType.IDENTIFIER) && keyword.equals(peek().text());
    }

    /** 判断 token 类型是否为运算符符号（用于 operator+ 等方法名拼接）。 */
    protected boolean isOperatorToken(TokenType type) {
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

    /** 获取 token 的文本内容。 */
    private String valueText(Token token) {
        return token.text();
    }
}
