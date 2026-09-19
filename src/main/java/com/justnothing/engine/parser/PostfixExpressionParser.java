package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.util.ReflectCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后缀与主表达式解析层。
 * <p>
 * 负责 postfix、成员访问、方法调用、参数列表与 {@code parsePrimary} 原子表达式。
 * </p>
 *
 * <pre>
 *                                                                                └─ parsePostfix()  ++ -- . ?. () [] :: (链式调用)
 *                                                                                     └─ parsePrimary()  字面量/标识符/new/(expr)/[array]/{map}
 * </pre>
 */
abstract class PostfixExpressionParser extends LiteralParser {

    /** 嵌套类解析缓存：避免重复 Class.forName + getDeclaredClasses */
    private final Map<String, Class<?>> nestedClassCache = new HashMap<>();

    protected PostfixExpressionParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== L14: 后缀 + 链式调用 ====================

    /**
     * 解析后缀操作符和链式成员访问。
     * <p>
     * 用 while 循环处理连续的后缀操作：
     * <ul>
     * <li>{@code ++ --} 后置自增/自减</li>
     * <li>{@code .} 成员访问</li>
     * <li>{@code ?.} 安全成员访问</li>
     * <li>{@code (...)} 方法调用</li>
     * <li>{@code [...]} 数组/集合索引访问</li>
     * <li>{@code ::} 方法引用</li>
     * </ul>
     * </p>
     */
    protected ASTNode parsePostfix() throws CythavaParseException {
        ASTNode expr = parsePrimary();

        while (true) {
            boolean matched = false;

            // 后置 ++ --
            if (match(TokenType.OPERATOR_INCREMENT)) {
                Class<?> operandType = context.getRawType(expr);
                expr = new UnaryOpNode.Builder()
                        .operator(UnaryOpNode.Operator.POST_INCREMENT)
                        .operand(expr)
                        .location(createLocation()).build();
                annotate(expr, operandType); // 后置++/-- 不改变类型
                matched = true;
            } else if (match(TokenType.OPERATOR_DECREMENT)) {
                Class<?> operandType = context.getRawType(expr);
                expr = new UnaryOpNode.Builder()
                        .operator(UnaryOpNode.Operator.POST_DECREMENT)
                        .operand(expr)
                        .location(createLocation())
                        .build();
                annotate(expr, operandType);
                matched = true;
            }
            // 成员访问 .
            else if (match(TokenType.OPERATOR_DOT)) {
                expr = parseMemberAccess(expr);
                matched = true;
            }
            // 安全访问 ?.
            else if (match(TokenType.OPERATOR_SAFE_DOT)) {
                expr = parseSafeMemberAccess(expr);
                matched = true;
            }
            // 方法调用 (...) — 允许在任意表达式后调用（包括方法引用）
            else if (match(TokenType.DELIMITER_LEFT_PAREN)) {
                expr = parseMethodCall(expr);
                matched = true;
            }
            // 数组/索引访问 [...] — 但排除类型方法引用的 []:: 模式
            else if (match(TokenType.DELIMITER_LEFT_BRACKET)) {
                // String[]::length 或 String[][][]::length → [] 是类型维度后缀，不是索引访问
                if (check(TokenType.DELIMITER_RIGHT_BRACKET)
                        && expr instanceof ClassReferenceNode classRef) {
                    // 消费所有连续的 [] 后缀（ClassReferenceNode 后的 [] 都是数组维度）
                    do {
                        consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in array type");
                        classRef = new ClassReferenceNode.Builder()
                                .originalTypeName(classRef.getOriginalTypeName())
                                .resolvedClass(classRef.getResolvedClass())
                                .isPrimitive(classRef.isPrimitive())
                                .arrayDepth(classRef.getArrayDepth() + 1)
                                .typeArguments(List.of())
                                .location(createLocation())
                                .build();
                        annotate(classRef, context.getType(classRef));
                        expr = classRef;
                    } while (match(TokenType.DELIMITER_LEFT_BRACKET)
                            && check(TokenType.DELIMITER_RIGHT_BRACKET));
                } else {
                    expr = parseArrayIndexAccess(expr);
                }
                matched = true;
            }
            // 方法引用 :: （在 postfix 循环内，允许后续 .invoke() 等链式调用）
            else if (match(TokenType.OPERATOR_DOUBLE_COLON)) {
                SourceLocation location = createLocation();
                // 支持泛型方法引用 Class::<TypeArgs>methodName
                List<GenericType> typeArgs = List.of();
                if (check(TokenType.OPERATOR_LESS_THAN)) {
                    int savedPos = position;
                    int savedAngles = context.getPendingAngleBrackets();
                    try {
                        advance(); // 消费 <
                        typeArgs = parseGenericTypeArgumentList();
                        consumeGenericClose("Expected '>' after generic type arguments");
                    } catch (CythavaParseException e) {
                        position = savedPos;
                        context.setPendingAngleBrackets(savedAngles);
                        typeArgs = List.of();
                    }
                }
                String refName;
                if (check(TokenType.IDENTIFIER)) {
                    refName = advance().text();
                } else if (check(TokenType.KEYWORD_NEW)) {
                    refName = advance().text();
                } else {
                    throw error("Expected method name after '::'", ErrorCode.PARSE_INVALID_SYNTAX);
                }
                expr = new MethodReferenceNode.Builder()
                        .target(expr)
                        .methodName(refName)
                        .typeArguments(typeArgs)
                        .location(location)
                        .build();
                // 方法引用类型推断：尝试通过反射绑定方法，获取返回类型
                Method boundMethod = null;
                MethodReferenceNode methodRef = (MethodReferenceNode) expr;
                if (!typeArgs.isEmpty()) {
                    // ★ 签名强制指定：用户显式写了 <TypeArgs>，按参数类型精确匹配
                    boundMethod = resolveByExplicitSignature(methodRef, refName, typeArgs);
                } else {
                    // 无签名标注：用空参数列表做宽松匹配（可能返回任意重载）
                    boundMethod = methodResolver.resolve(expr, refName, List.of());
                }
                if (boundMethod != null) {
                    methodRef.setBoundMethod(boundMethod);  // ★ 绑定到节点，运行期直接使用
                    annotate(expr, boundMethod.getReturnType());
                } else {
                    annotate(expr, Object.class); // 无法绑定时保持 Object 占位
                }
                matched = true;
            }

            if (!matched) {
                break;
            }
        }

        return expr;
    }

    /**
     * 解析普通成员访问 {@code target.memberName} 或 {@code target.methodName(args)}。
     */
    private ASTNode parseMemberAccess(ASTNode target) throws CythavaParseException {
        // 显式泛型实参：obj.<String>m()（泛型方法调用）与 obj.<T>.new()（Cythava 的构造器形式）
        // 两种写法只差一个点，所以闭合符后的点是可选的。
        // '.' 后面跟 '<' 只可能是这个语法（Cythava 的区间运算符 ..< 是单个 token），无需前瞻。
        List<GenericType> explicitTypeArgs = null;
        if (check(TokenType.OPERATOR_LESS_THAN)) {
            int savedPos = position;
            int savedAngles = context.getPendingAngleBrackets();
            try {
                advance(); // 消费 <
                explicitTypeArgs = parseGenericTypeArgumentList();
                consumeGenericClose("Expected '>' after generic type arguments");
                match(TokenType.OPERATOR_DOT); // .new() 形式才有点；obj.<T>m() 直接跟方法名
            } catch (CythavaParseException e) {
                position = savedPos;
                context.setPendingAngleBrackets(savedAngles);
                explicitTypeArgs = null;
            }
        }

        // 支持 .class 字面量：class 是关键字而非标识符，需特殊处理
        Token nameToken;
        if (check(TokenType.KEYWORD_CLASS)) {
            nameToken = advance(); // .class
        } else if (check(TokenType.KEYWORD_NEW)) {
            // Type.new(args) → 构造器调用
            advance(); // 吃掉 new
            JType parsedType = context.getType(target);
            if (parsedType == null) {
                // 非严格模式：类型无法解析时回退到 Object
                if (!context.isStrictMode()) {
                    parsedType = JType.of(Object.class);
                } else {
                    throw error("Cannot resolve type for constructor call", ErrorCode.PARSE_CLASS_NOT_FOUND);
                }
            }
            consume(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'new'");
            List<ASTNode> args = parseArgumentList();
            consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after constructor arguments");
            GenericType genericType;
            if (explicitTypeArgs != null && !explicitTypeArgs.isEmpty()) {
                genericType = new GenericType(parsedType.getRawType(), explicitTypeArgs, 0, parsedType.getDisplayName());
            } else {
                genericType = new GenericType(parsedType.getRawType());
            }
            ConstructorCallNode node = (ConstructorCallNode) new ConstructorCallNode.Builder()
                    .type(genericType).arguments(args)
                    .location(createLocation()).build();
            annotate(node, JType.fromGenericType(genericType));
            return node;
        } else {
            nameToken = consume(TokenType.IDENTIFIER, "Expected member name after '.'");
        }
        String memberName = nameToken.text();

        // .class 字面量：TypeName.class → 返回该类型的 Class<?> 对象
        if ("class".equals(memberName)) {
            JType targetType = context.getType(target);
            Class<?> resultClass = (targetType != null) ? targetType.getRawType() : Object.class;
            // 返回 Class<?> 引用（Class 对象本身用 Class.class 表示）
            ClassReferenceNode classLiteral = new ClassReferenceNode.Builder()
                    .originalTypeName(resultClass.getName() + ".class")
                    .resolvedClass(Class.class)
                    .location(nameToken.location())
                    .build();
            annotate(classLiteral, Class.class);
            return classLiteral;
        }

        // 如果后面紧跟 ( 则是方法调用
        if (check(TokenType.DELIMITER_LEFT_PAREN)) {
            advance(); // 吃掉 (
            List<ASTNode> args = parseArgumentList();
            consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after method arguments");
            MethodCallNode node = (MethodCallNode) new MethodCallNode.Builder().target(target).methodName(memberName)
                    .arguments(args).location(nameToken.location()).build();
            // 解析期方法重载选择绑定
            Method bound = methodResolver.resolve(target, memberName, args);
            if (bound != null) {
                node.setBoundMethod(bound);

                // 标注 lambda/方法引用参数的目标函数式接口类型
                GenericType targetGenericType = methodResolver.inferTargetGenericType(target);
                methodResolver.annotateFunctionalInterfaceArgs(bound, args, targetGenericType);

                // 泛型返回类型替换：当目标有声明泛型类型时，替换方法返回类型
                if (targetGenericType != null && targetGenericType.isGeneric()) {
                    Class<?> substitutedReturn = methodResolver.substituteReturnType(bound, targetGenericType);
                    annotate(node, substitutedReturn != null ? substitutedReturn : bound.getReturnType());
                } else {
                    annotate(node, bound.getReturnType());
                }

                // 泛型参数校验：当目标有声明泛型类型时，验证参数是否匹配替换后的类型
                if (targetGenericType != null && targetGenericType.isGeneric()) {
                    if (!methodResolver.isGenericApplicable(bound, targetGenericType, args)) {
                        // 非严格模式：泛型参数不匹配静默放行，继续使用该方法
                        if (context.isStrictMode()) {
                            Class<?>[] expectedParams = methodResolver.substituteGenericParameters(bound, targetGenericType);
                            StringBuilder msg = new StringBuilder();
                            msg.append("Generic type mismatch in call to '").append(memberName).append("': \n");
                            for (int i = 0; i < args.size() && i < expectedParams.length; i++) {
                                if (i > 0) msg.append(";\n");
                                JType actualType = context.getType(args.get(i));
                                String actualName = actualType != null ? actualType.getRawType().getSimpleName() : "?";
                                msg.append("  parameter ").append(i + 1)
                                        .append(": expected ").append(expectedParams[i].getSimpleName())
                                        .append(", got ").append(actualName);
                            }
                            throw semanticError(msg.toString(), ErrorCode.EVAL_TYPE_MISMATCH);
                        }
                        // 非严格模式：静默跳过校验，继续使用已绑定的方法
                    }
                }
            } else {
                // 方法未严格匹配：按名称回退推断返回类型
                annotate(node, inferMethodReturnTypeFallback(target, memberName));
            }
            return node;
        }

        // ★ 优先级 1: 嵌套类引用（如 Map.Entry → java.util.Map$Entry）
        if (target instanceof ClassReferenceNode classRef) {
            Class<?> outerClass = classRef.getResolvedClass();
            if (outerClass != null) {
                Class<?> innerClass = resolveNestedClass(outerClass, memberName);
                if (innerClass != null) {
                    ClassReferenceNode innerClassRef = new ClassReferenceNode.Builder()
                            .originalTypeName(classRef.getOriginalTypeName() + "." + memberName)
                            .resolvedClass(innerClass)
                            .location(nameToken.location())
                            .build();
                    annotate(innerClassRef, innerClass);
                    return innerClassRef;
                }
                // 不是嵌套类，继续走字段访问路径
            }
        }

        // 字段访问 — 验证目标类型是否有该字段
        FieldAccessNode node = new FieldAccessNode.Builder().target(target).fieldName(memberName)
                .location(nameToken.location()).build();
        Class<?> targetType = context.getRawType(target);
        if (targetType != null && !targetType.equals(Object.class)) {
            // ★ 特判：数组的 .length 不是 Java 反射 Field，需要特殊处理
            if (targetType.isArray() && "length".equals(memberName)) {
                annotate(node, int.class);
                return node;
            }
            Field field = findFieldInHierarchy(targetType, memberName);
            if (field != null) {
                node.setBoundField(field);
                annotate(node, field.getType());
            } else {
                // 回退：检查目标变量是否关联了匿名类（如 Object obj = new Object() { int x; }）
                FieldDeclarationNode anonField = findFieldInAnonymousClass(target,
                        memberName);
                if (anonField != null) {
                    ClassReferenceNode fieldType = anonField.getType();
                    annotate(node, fieldType != null ? fieldType.getResolvedClass() : Object.class);
                } else {
                    throw semanticError(
                            "No such field '" + memberName + "' in class " + targetType.getSimpleName(),
                            ErrorCode.PARSE_UNKNOWN_MEMBER, nameToken.location());
                }
            }
        } else {
            // 目标类型未知（Object.class / 自定义类无 Java 反射）时，尝试多种回退路径

            // 回退 1：目标是一个自定义类名（REPL 中声明的 class X { ... }）
            if (target instanceof ClassReferenceNode cr) {
                String targetClassName = cr.getOriginalTypeName();
                ClassDeclarationNode customClass =
                        context.getClassDeclaration(targetClassName);
                if (customClass != null) {
                    // 在自定义类的字段列表中查找
                    for (FieldDeclarationNode field : customClass.getFields()) {
                        if (field.getFieldName().equals(memberName)) {
                            ClassReferenceNode fieldType = field.getType();
                            annotate(node, fieldType != null && fieldType.getResolvedClass() != null
                                    ? fieldType.getResolvedClass() : Object.class);
                            return node;
                        }
                    }
                    throw semanticError(
                            "No such field '" + memberName + "' in class " + targetClassName,
                            ErrorCode.PARSE_UNKNOWN_MEMBER, nameToken.location());
                }
            }

            // 回退 2：当前类上下文（方法体内 this.xxx 延迟解析）
            String currentClass = context.getCurrentClassName();
            if (currentClass != null) {
                Class<?> resolvedCurrent = context.resolveClass(currentClass);
                if (resolvedCurrent != null) {
                    Field currentField = findFieldInHierarchy(resolvedCurrent, memberName);
                    if (currentField != null) {
                        node.setBoundField(currentField);
                        annotate(node, currentField.getType());
                        return node;
                    }
                }
            }
            // ★ 回退：Object 类型 + 匿名类字段查找
            FieldDeclarationNode anonField2 = findFieldInAnonymousClass(target,
                    memberName);
            if (anonField2 != null) {
                ClassReferenceNode fieldType2 = anonField2.getType();
                annotate(node, fieldType2 != null ? fieldType2.getResolvedClass() : Object.class);
            } else if (target instanceof VariableNode varNode) {
                // ★ 回退 3：目标变量是自定义类实例（如 Box<String> stringBox），通过 ClassDeclarationNode 查找字段
                GenericType declaredGT = context.getDeclaredType(varNode.getName());
                if (declaredGT != null && declaredGT.getRawType() != null) {
                    String rawTypeName = declaredGT.getRawType().getSimpleName();
                    // 也尝试 originalTypeName（可能包含泛型信息，如 "Box"）
                    if (rawTypeName.equals("Object") && declaredGT.getOriginalTypeName() != null) {
                        rawTypeName = declaredGT.getOriginalTypeName().replaceAll("<.*$", "");
                    }
                    ClassDeclarationNode customClass = context.getClassDeclaration(rawTypeName);
                    if (customClass != null) {
                        boolean found = false;
                        for (FieldDeclarationNode field : customClass.getFields()) {
                            if (field.getFieldName().equals(memberName)) {
                                ClassReferenceNode fieldType = field.getType();
                                annotate(node, fieldType != null && fieldType.getResolvedClass() != null
                                        ? fieldType.getResolvedClass() : Object.class);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            // 非严格模式：字段不存在时标注为 Object，运行期再解析
                            if (!context.isStrictMode()) {
                                annotate(node, Object.class);
                            } else {
                                throw semanticError(
                                        "No such field '" + memberName + "' in class " + rawTypeName,
                                        ErrorCode.PARSE_UNKNOWN_MEMBER, nameToken.location());
                            }
                        }
                    } else {
                        // 非严格模式：目标类型未知时标注为 Object
                        if (!context.isStrictMode()) {
                            annotate(node, Object.class);
                        } else {
                            throw semanticError(
                                    "Cannot resolve field '" + memberName + "' (target type unknown)",
                                    ErrorCode.PARSE_UNKNOWN_MEMBER, nameToken.location());
                        }
                    }
                } else {
                    // auto 变量可能没有类型信息，宽容处理：标注为 Object，运行时再解析
                    annotate(node, Object.class);
                }
            } else if (target instanceof NameRefNode) {
                // 目标是解析期还没定身份的名字（可能是声明在本段之后的类名）：
                // 现在无从判断它有没有这个字段，标注为 Object 交给运行期反射解析
                annotate(node, Object.class);
            } else {
                // 非严格模式：目标类型未知时标注为 Object
                if (!context.isStrictMode()) {
                    annotate(node, Object.class);
                } else {
                    throw semanticError(
                            "Cannot resolve field '" + memberName + "' (target type unknown)",
                            ErrorCode.PARSE_UNKNOWN_MEMBER, nameToken.location());
                }
            }
        }
        return node;
    }

    /**
     * 尝试从外部类解析嵌套/内部类。
     *
     * @param outerClass 外部类
     * @param simpleName 内部类的简单名称
     * @return 内部类的 Class 对象，找不到则返回 null
     */
    private Class<?> resolveNestedClass(Class<?> outerClass, String simpleName) {
        // 1. 尝试直接用 $ 分隔的完全限定名
        String fqn = outerClass.getName() + "$" + simpleName;
        Class<?> cached = nestedClassCache.get(fqn);
        if (cached != null) return cached;

        try {
            cached = Class.forName(fqn);
            nestedClassCache.put(fqn, cached);
            return cached;
        } catch (ClassNotFoundException ignored) {
        }

        // 2. 遍历外部类的声明内部类（含 public/protected/package/private）
        for (Class<?> declared : outerClass.getDeclaredClasses()) {
            if (declared.getSimpleName().equals(simpleName)) {
                nestedClassCache.put(fqn, declared);
                return declared;
            }
        }

        nestedClassCache.put(fqn, null); // 缓存"找不到"，避免重复查找
        return null;
    }

    /**
     * 解析安全成员访问 {@code target?.memberName} 或 {@code target?.methodName(args)}。
     */
    private ASTNode parseSafeMemberAccess(ASTNode target) throws CythavaParseException {
        // 支持 ?.class 字面量
        Token nameToken;
        if (check(TokenType.KEYWORD_CLASS)) {
            nameToken = advance(); // ?.class
        } else {
            nameToken = consume(TokenType.IDENTIFIER, "Expected member name after '?.'");
        }
        String memberName = nameToken.text();

        // .class 字面量
        if ("class".equals(memberName)) {
            JType targetType = context.getType(target);
            Class<?> resultClass = (targetType != null) ? targetType.getRawType() : Object.class;
            ClassReferenceNode classLiteral = new ClassReferenceNode.Builder()
                    .originalTypeName(resultClass.getName() + ".class")
                    .resolvedClass(Class.class)
                    .location(nameToken.location())
                    .build();
            annotate(classLiteral, Class.class);
            return classLiteral;
        }

        if (check(TokenType.DELIMITER_LEFT_PAREN)) {
            advance();
            List<ASTNode> args = parseArgumentList();
            consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after safe method arguments");
            SafeMethodCallNode node = new SafeMethodCallNode.Builder()
                    .target(target)
                    .methodName(memberName)
                    .arguments(args)
                    .location(nameToken.location()).build();
            // 安全调用：尝试从目标类型绑定方法返回类型，结果可能为 null（nullable 语义）
            Class<?> safeTargetType = context.getRawType(target);
            if (safeTargetType != null && !safeTargetType.equals(Object.class)) {
                Method safeBound = methodResolver.resolve(target, memberName, args);
                if (safeBound != null) {
                    annotate(node, safeBound.getReturnType()); // 已绑定方法返回类型（nullable）
                } else {
                    annotate(node, Object.class); // 方法未解析，标记为 Object（nullable）
                }
            } else {
                annotate(node, Object.class); // 目标类型未知，标记为 Object（nullable）
            }
            return node;
        }

        SafeFieldAccessNode node = (SafeFieldAccessNode) new SafeFieldAccessNode.Builder()
                .target(target)
                .fieldName(memberName)
                .location(nameToken.location()).build();
        // 安全字段访问：从目标对象类型解析字段声明类型，结果可能为 null（nullable 语义）
        Class<?> safeFieldTargetType = context.getRawType(target);
        if (safeFieldTargetType != null && !safeFieldTargetType.equals(Object.class)) {
            try {
                Field safeField = safeFieldTargetType.getDeclaredField(memberName);
                annotate(node, safeField.getType()); // 字段声明类型（nullable）
            } catch (NoSuchFieldException e) {
                // 尝试公共字段（含继承）
                try {
                    Field safeField2 = target.getClass()
                            .getClassLoader()
                            .loadClass(safeFieldTargetType.getName()).getField(memberName);
                    annotate(node, safeField2.getType()); // 继承字段声明类型（nullable）
                } catch (Exception ignored) {
                    annotate(node, Object.class); // 字段未解析，标记为 Object（nullable）
                }
            }
        } else {
            annotate(node, Object.class); // 目标类型未知，标记为 Object（nullable）
        }
        return node;
    }

    /**
     * 解析方法调用 {@code target(args)}。
     * <p>
     * 调用时 {@code (} 已被消费。
     * </p>
     */
    private ASTNode parseMethodCall(ASTNode target) throws CythavaParseException {
        SourceLocation location = createLocation();
        List<ASTNode> args = parseArgumentList();
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after arguments");

        // 目标是裸名字且无目标时 → 函数调用（builtin 或脚本定义函数）
        // NameRefNode 与 VariableNode 在这里等价：都是"一个还没绑定到任何对象的名字"，
        // 前者只是解析期还没判定它到底是变量还是函数。
        String calleeName = null;
        if (target instanceof VariableNode v) {
            calleeName = v.getName();
        } else if (target instanceof NameRefNode n) {
            calleeName = n.getName();
        }
        if (calleeName != null) {
            FunctionCallNode node = (FunctionCallNode) new FunctionCallNode.Builder()
                    .functionName(calleeName)
                    .arguments(args)
                    .location(location)
                    .build();
            // 解析期类型推断：尝试绑定已知函数或静态方法
            JType returnType = inferFunctionReturnType(calleeName, args);
            if (returnType != null) {
                annotate(node, returnType);
            } else {
                annotate(node, Object.class);
            }
            return node;
        }

        // 其他情况 → 直接调用节点（语法糖：lambdaExpr(args), methodRef(args) 等）
        DirectCallNode node = (DirectCallNode) new DirectCallNode.Builder()
                .target(target)
                .arguments(args)
                .location(location)
                .build();
        annotate(node, Object.class);
        return node;
    }

    /**
     * 解析类内未限定的方法调用 {@code f(args)}，构造为 {@code this.f(args)}。
     * <p>
     * 调用时 {@code (} 尚未消费。
     * </p>
     */
    private ASTNode parseSelfMethodCall(String methodName, SourceLocation nameLocation)
            throws CythavaParseException {
        consume(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after method name");
        List<ASTNode> args = parseArgumentList();
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after arguments");

        ASTNode self = new VariableNode.Builder().name("this").location(nameLocation).build();
        MethodCallNode node = (MethodCallNode) new MethodCallNode.Builder()
                .target(self)
                .methodName(methodName)
                .arguments(args)
                .location(createLocation())
                .build();
        annotate(node, Object.class);
        return node;
    }

    private JType inferFunctionReturnType(String funcName, List<ASTNode> args) {
        // 已知变量（持有的是函数值）：其返回值类型无法从变量声明类型推出，交给运行期
        if (context.isKnownVariable(funcName)) {
            return null;
        }
        // 静态方法绑定：尝试解析为静态方法调用
        Method bound = methodResolver.resolve(null, funcName, args);
        if (bound != null) {
            return JType.fromGenericType(new GenericType(bound.getReturnType()));
        }
        return null;
    }

    /**
     * 解析数组/集合索引访问 {@code expr[index]}。
     * <p>
     * 调用时 {@code [} 已被消费。
     * </p>
     */
    private ASTNode parseArrayIndexAccess(ASTNode arrayExpr) throws CythavaParseException {
        SourceLocation location = createLocation();
        ASTNode index = parseNextExpression();
        consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after index");
        ArrayAccessNode node = new ArrayAccessNode.Builder()
                .array(arrayExpr)
                .index(index)
                .location(location)
                .build();
        // 数组元素类型：从数组表达式的 JType 提取组件类型
        JType arrayType = context.getType(arrayExpr);
        if (arrayType != null && (arrayType.getArrayDepth() > 0 || arrayType.getRawType().isArray())) {
            Class<?> rawType = arrayType.getRawType();
            // rawType 可能是 int[]、Object[] 等数组类 → 取组件类型
            Class<?> componentType = rawType.isArray() ? rawType.getComponentType() : rawType;
            annotate(node, componentType);
        } else if (arrayType != null && !arrayType.getRawType().equals(Object.class)) {
            // TODO: 已知非数组类型上做 [] 访问 — 标记为待推断（可能是自定义 operator[]）
            annotate(node, Object.class);
        } else {
            annotate(node, Object.class); // 未知数组类型
        }
        return node;
    }

    /**
     * 解析逗号分隔的参数列表。
     * <p>
     * 调用时 {@code (} 已被消费，此方法不消费闭合的 {@code )}。
     * </p>
     *
     * @return 参数节点列表（可能为空）
     */
    @Override
    public List<ASTNode> parseArgumentList() throws CythavaParseException {
        List<ASTNode> args = new ArrayList<>();

        if (check(TokenType.DELIMITER_RIGHT_PAREN)) {
            return args;
        }

        // 跳过可能的起始逗号
        if (check(TokenType.DELIMITER_COMMA)) {
            advance();
        }

        do {
            args.add(parseNextExpression());
        } while (match(TokenType.DELIMITER_COMMA) && !check(TokenType.DELIMITER_RIGHT_PAREN));

        return args;
    }

    // ==================== L15: 主表达式（原子） ====================

    /**
     * 解析原子表达式——表达式的最底层构成单元。
     * <p>
     * 支持的语法：
     * <ul>
     * <li>字面量: 整数、长整数、浮点数、字符串、字符、布尔值、null</li>
     * <li>f-string 插值字符串</li>
     * <li>标识符: 变量、类名（后续由后缀处理区分）</li>
     * <li>this / super 关键字</li>
     * <li>基本类型名作为 Class 引用</li>
     * <li>数组字面量: {@code [1, 2, 3]}</li>
     * <li>花括号初始化器: {@code {1, 2, 3}} 或 Map 字面量 {@code {"k": v}}</li>
     * <li>括号表达式 / Lambda: {@code (expr)} 或 {@code (params) -> body}</li>
     * <li>new 对象创建</li>
     * </ul>
     * </p>
     */
    private ASTNode parsePrimary() throws CythavaParseException {
        // === 字面量 ===
        // 整数字面量
        if (match(TokenType.LITERAL_INTEGER)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(int.class)
                    .location(token.location()).build();
            annotate(node, int.class);
            return node;
        }
        // 长整数字面量
        if (match(TokenType.LITERAL_LONG)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(long.class)
                    .location(token.location()).build();
            annotate(node, long.class);
            return node;
        }
        // 浮点数字面量
        if (match(TokenType.LITERAL_DECIMAL)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(double.class)
                    .location(token.location()).build();
            annotate(node, double.class);
            return node;
        }
        // 字符串字面量
        if (match(TokenType.LITERAL_STRING)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(String.class)
                    .location(token.location()).build();
            annotate(node, String.class);
            return node;
        }
        // f-string 插值字符串
        if (match(TokenType.LITERAL_INTERPOLATED_STRING)) {
            Token token = tokens.get(position - 1);
            ASTNode node = parseInterpolatedString(token);
            annotate(node, String.class);
            return node;
        }
        // 多行原始字符串 ("""...""")
        if (match(TokenType.LITERAL_MULTI_LINE_STRING)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(String.class)
                    .location(token.location()).build();
            annotate(node, String.class);
            return node;
        }
        // 多行插值字符串 (f"""...""")
        if (match(TokenType.LITERAL_MULTI_LINE_INTERPOLATED_STRING)) {
            Token token = tokens.get(position - 1);
            ASTNode node = parseInterpolatedString(token);
            annotate(node, String.class);
            return node;
        }
        // 字符字面量
        if (match(TokenType.LITERAL_CHAR)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(char.class)
                    .location(token.location()).build();
            annotate(node, char.class);
            return node;
        }
        // 布尔字面量
        if (match(TokenType.LITERAL_BOOLEAN)) {
            Token token = tokens.get(position - 1);
            LiteralNode node = (LiteralNode) new LiteralNode.Builder()
                    .value(token.value())
                    .type(boolean.class)
                    .location(token.location()).build();
            annotate(node, boolean.class);
            return node;
        }
        // null 字面量
        if (match(TokenType.LITERAL_NULL)) {
            Token token = tokens.get(position - 1);
            // null 无具体类型，不标注（或标注为 nullable Object）
            return new LiteralNode.Builder()
                    .value(null)
                    .type(null)
                    .location(token.location()).build();
        }

        // === this / super ===
        if (match(TokenType.KEYWORD_THIS)) {
            VariableNode node = (VariableNode) new VariableNode.Builder()
                    .name("this")
                    .location(createLocation())
                    .build();
            String currentClass = context.getCurrentClassName();
            if (currentClass != null) {
                Class<?> resolved = context.resolveClass(currentClass);
                annotate(node, resolved != null ? resolved : Object.class);
            } else {
                annotate(node, Object.class); // 非类上下文中的 this（REPL顶层等）
            }
            return node;
        }
        if (match(TokenType.KEYWORD_SUPER)) {
            VariableNode node = (VariableNode) new VariableNode.Builder()
                    .name("super")
                    .location(createLocation())
                    .build();
            String currentClass = context.getCurrentClassName();
            if (currentClass != null) {
                Class<?> resolved = context.resolveClass(currentClass);
                if (resolved != null && resolved.getSuperclass() != null) {
                    annotate(node, resolved.getSuperclass());
                } else {
                    annotate(node, Object.class);
                }
            } else {
                annotate(node, Object.class); // 非类上下文中的 super
            }
            return node;
        }

        // === 基本类型名作为 Class 引用 ===
        if (isPrimitiveTypeKeyword(peekType())) {
            Token token = advance();
            Class<?> primitiveClass = context.resolveClass(token.text());
            ClassReferenceNode classRef = ClassReferenceNode.of(token.text(), primitiveClass, true, token.location());
            annotate(classRef, JType.fromGenericType(new GenericType(primitiveClass)));
            return classRef;
        }

        // === 数组字面量 [1, 2, 3] ===
        if (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            return parseArrayLiteral();
        }

        // === 花括号初始化器 {1, 2, 3} 或 Map {"k": v} ===
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            return parseBraceInitializer();
        }

        // === 标识符（变量 / 类名 / 字段 / lambda 参数）— 4 级优先级消歧 ===
        if (check(TokenType.IDENTIFIER)) {
            Token token = advance();
            String name = token.text();

            // 检查是否是 lambda: x -> ...
            if (check(TokenType.DELIMITER_ARROW)) {
                savePosition();
                try {
                    position--;
                } finally {
                    restorePosition();
                }
                if (check(TokenType.DELIMITER_ARROW)) {
                    return tryParseSingleParamLambda(name, token.location());
                }
            }

            // ★ 优先级 0: 合格类名消歧（如 java.lang.Math）— 从旧版 Parser 移植 ★
            // 当标识符后跟 . 时，尝试收集完整的带点类名，保留最后一个可解析的类名。
            if (check(TokenType.OPERATOR_DOT) && !context.isKnownVariable(name)) {
                int savedPos = position;
                String lastValidClass = context.resolveClass(name) != null ? name : null;
                int lastValidPos = savedPos;
                StringBuilder qualifiedName = new StringBuilder(name);
                while (check(TokenType.OPERATOR_DOT) && !isAtEnd()) {
                    advance();
                    if (check(TokenType.IDENTIFIER)) {
                        qualifiedName.append('.').append(advance().text());
                        String currentName = qualifiedName.toString();
                        if (context.resolveClass(currentName) != null) {
                            lastValidClass = currentName;
                            lastValidPos = position;
                        }
                    } else {
                        break;
                    }
                }
                if (lastValidClass != null) {
                    position = lastValidPos;
                    Class<?> resolved = context.resolveClass(lastValidClass);
                    ClassReferenceNode classRef = ClassReferenceNode.of(
                            lastValidClass, resolved, false, token.location());
                    context.setType(classRef, JType.fromGenericType(new GenericType(resolved)));
                    return classRef;
                }
                position = savedPos;
                token = tokens.get(position - 1);
                name = token.text();
            }

            // ★ 标识符消歧算法（文档 PARSER_DESIGN_V2.md §5.3）★
    // 优先级 1: 局部变量/参数 → VariableNode（从符号表取声明类型）
    if (context.isKnownVariable(name)) {
        VariableNode varNode = (VariableNode) new VariableNode.Builder().name(name).location(token.location())
                .build();
        // Lambda 自动推断类型参数：不标注类型（保留 null），让 checkCustomOperator 跳过
        if (context.isInferredVariable(name)) {
            // 不设置类型 → getType 返回 null → checkCustomOperator 的 null 检查生效
        } else {
            GenericType declaredType = context.getDeclaredType(name);
            if (declaredType != null) {
                context.setType(varNode, JType.fromGenericType(declaredType));
            } else {
                // auto 推断变量：尝试从初始化表达式推断（或保留 Object 占位）
                context.setType(varNode, JType.of(Object.class));
            }
        }
        return varNode;
    }

            // 优先级 2: 当前类字段 → FieldAccessNode(this, fieldName)
            if (context.shouldResolveAsField(name)) {
                FieldAccessNode fieldAccess = new FieldAccessNode.Builder()
                        .target(new VariableNode.Builder().name("this").location(token.location()).build())
                        .fieldName(name)
                        .location(token.location())
                        .build();
                // 从当前类声明反射解析字段类型
                String currentClass = context.getCurrentClassName();
                if (currentClass != null) {
                    Class<?> resolvedClass = context.resolveClass(currentClass);
                    if (resolvedClass != null) {
                        Field classField = findFieldInHierarchy(resolvedClass, name);
                        if (classField != null) {
                            annotate(fieldAccess, classField.getType());
                        } else {
                            annotate(fieldAccess, Object.class);
                        }
                    } else {
                        annotate(fieldAccess, Object.class);
                    }
                } else {
                    annotate(fieldAccess, Object.class);
                }
                return fieldAccess;
            }

            // 优先级 2.5: 已注册的当前类方法 → 同类方法互调（f(args) 解析为 this.f(args)）
            boolean knownClassMethod = !context.getCurrentClassMethodSignatures(name).isEmpty();
            if (knownClassMethod) {
                if (check(TokenType.DELIMITER_LEFT_PAREN)) {
                    return parseSelfMethodCall(name, token.location());
                }
                // 非调用形态（如作为方法引用传递）：放行为普通标识符
                VariableNode methodRef = (VariableNode) new VariableNode.Builder().name(name)
                        .location(token.location()).build();
                context.setType(methodRef, JType.of(Object.class));
                return methodRef;
            }

            // 优先级 3: 已知类名 → ClassReferenceNode（后续 . 可链式访问成员）
            if (context.isKnownClass(name)) {
                Class<?> resolvedClass = context.resolveClass(name);
                ClassReferenceNode classRef = ClassReferenceNode.of(
                        name, resolvedClass, false, token.location());
                context.setType(classRef, JType.fromGenericType(new GenericType(resolvedClass)));
                return classRef;
            }

            // 优先级 4: 内置函数名 → 通过 VariableNode 放行，运行期由 Builtins 处理
            if (context.isBuiltinFunction(name)) {
                VariableNode unresolved = (VariableNode) new VariableNode.Builder().name(name)
                        .location(token.location()).build();
                context.setType(unresolved, JType.of(Object.class));
                return unresolved;
            }

            // 优先级 4.5: 类体内未注册名字后跟 '(' → 可能是后声明的方法
            // （类成员按书写顺序解析：构造器/方法的调用目标可能声明在调用点之后，
            //   此处先按同类方法调用放行，名字不存在时由运行期报 "No applicable method"）
            if (context.getCurrentClassName() != null && check(TokenType.DELIMITER_LEFT_PAREN)) {
                return parseSelfMethodCall(name, token.location());
            }

            // 优先级 5: 全都不匹配 → 登记为"未定名引用"，身份留给 link 层判定。
            // 这里不再查一次类去猜它是类还是变量，也不在解析期断言它不存在 ——
            // 类名可能声明在本次输入的后半段，解析到此处时还没登记。
            NameRefNode nameRef = NameRefNode.unresolved(name, token.location());
            context.addNameRef(nameRef);
            GenericType declaredType = context.getDeclaredType(name);
            if (declaredType != null) {
                context.setType(nameRef, JType.fromGenericType(declaredType));
            } else {
                context.setType(nameRef, JType.of(Object.class));
            }
            return nameRef;
        }

        // === 括号表达式 (expr) 或 Lambda (params) -> body ===
        if (check(TokenType.DELIMITER_LEFT_PAREN)) {
            return parseParenExpressionOrLambda();
        }

        // === new 对象创建 ===
        if (match(TokenType.KEYWORD_NEW)) {
            return parseNewObject();
        }

        throw error("Expected expression", ErrorCode.PARSE_UNEXPECTED_TOKEN);
    }

    // ==================== 辅助方法 ====================

    /**
     * 方法调用未绑定时的返回类型回退推断。
     * <p>
     * 当 {@link MethodResolver#resolve} 无法找到严格匹配的重载时，
     * 从目标类按方法名查找所有候选方法的返回类型作为最佳猜测。
     */
    private Class<?> inferMethodReturnTypeFallback(ASTNode target, String methodName) {
        Class<?> targetClass = methodResolver.inferTargetClass(target);
        if (targetClass == null)
            return Object.class;

        Method[] allMethods = ReflectCache.methods(targetClass);
        Method found = null;
        for (Method m : allMethods) {
            if (m.getName().equals(methodName)) {
                if (found == null) {
                    found = m;
                } else {
                    // 多个同名方法：优先非 void 返回类型
                    if (found.getReturnType() == void.class && m.getReturnType() != void.class) {
                        found = m;
                    }
                }
            }
        }
        return found != null ? found.getReturnType() : Object.class;
    }

    /**
     * 从父类/接口层次结构中查找字段声明。
     *
     * @param startClass 起始类
     * @param fieldName  字段名
     * @return 找到的 Field，未找到返回 null
     */
    private Field findFieldInHierarchy(Class<?> startClass, String fieldName) {
        // 1. 先查 startClass 自身（含 public/protected/package/private + 静态字段）
        for (Field f : ReflectCache.declaredFields(startClass)) {
            if (f.getName().equals(fieldName)) {
                return f;
            }
        }

        // 2. 向上遍历继承链上的 public 字段（等价于逐层 getField，但不靠抛异常探测）
        for (Class<?> current = startClass.getSuperclass();
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field f : ReflectCache.declaredFields(current)) {
                if (f.getName().equals(fieldName) && Modifier.isPublic(f.getModifiers())) {
                    return f;
                }
            }
        }

        // 3. 遍历接口（含父接口）的 public 字段
        return findPublicFieldInInterfaces(startClass, fieldName);
    }

    /** 在接口（含父接口）中查找 public 字段。 */
    private Field findPublicFieldInInterfaces(Class<?> clazz, String fieldName) {
        for (Class<?> itf : clazz.getInterfaces()) {
            for (Field f : ReflectCache.declaredFields(itf)) {
                if (f.getName().equals(fieldName) && Modifier.isPublic(f.getModifiers())) {
                    return f;
                }
            }
            Field inherited = findPublicFieldInInterfaces(itf, fieldName);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    /**
     * 从目标变量的匿名类初始化器中查找字段声明。
     * <p>
     * 用于处理 {@code Object obj = new Object() { int x; }; obj.x} 场景：
     * obj 的运行时类型是 Object（匿名类父类），但 x 定义在匿名类体中，
     * 反射无法找到，需要从解析期关联的 ClassDeclarationNode 中查找。
     *
     * @param target    字段访问的目标表达式（通常是 VariableNode）
     * @param fieldName 要查找的字段名
     * @return 找到的字段声明节点，未找到返回 null
     */
    private FieldDeclarationNode findFieldInAnonymousClass(ASTNode target,
            String fieldName) {
        // 路径 1: 变量引用（var.x）
        if (target instanceof VariableNode varNode) {
            ClassDeclarationNode anonClass = context
                    .getVariableAnonymousClass(varNode.getName());
            if (anonClass != null) {
                for (FieldDeclarationNode field : anonClass.getFields()) {
                    if (field.getFieldName().equals(fieldName)) {
                        return field;
                    }
                }
            }
        }

        // 路径 2: 匿名类实例化表达式 ((new Object() { int x; }).x)
        if (target instanceof ConstructorCallNode newNode) {
            ClassDeclarationNode anonBody = newNode.getAnonymousClass();
            if (anonBody != null) {
                for (FieldDeclarationNode field : anonBody.getFields()) {
                    if (field.getFieldName().equals(fieldName)) {
                        return field;
                    }
                }
            }
        }

        return null;
    }
}
