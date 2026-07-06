package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.parser.constant.ConstantFolder;
import com.justnothing.engine.util.MethodResolver;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 表达式解析器。
 * <p>
 * 采用递归下降 + 运算符优先级策略，负责解析所有 Cythava 表达式。
 * </p>
 *
 * <h3>运算符优先级层次（从低到高）</h3>
 * 
 * <pre>
 *   parseNextExpression()          ← 入口：async / await / switch 表达式
 *     └─ parseAssignment()    ← = += -= *= /= %= &amp;= |= ^= &lt;&lt;= &gt;&gt;= &gt;&gt;&gt;= ??= ?=
 *          └─ parseTernary()   ← ?: 三元 + |&gt; 管道（左结合循环）
 *               └─ parseNullCoalescing()  ← ??  ?:
 *                    └─ parseLogicalOr()   ← ||
 *                         └─ parseLogicalAnd()  ← &amp;&amp;
 *                              └─ parseBitwiseOr()  ← |
 *                                   └─ parseBitwiseXor()  ← ^
 *                                        └─ parseBitwiseAnd()  ← &amp;
 *                                             └─ parseEquality()  == != + .. ..&lt; (范围)
 *                                                  └─ parseComparison()  &lt; &lt;= &gt; &gt;= &lt;=&gt; instanceof
 *                                                       └─ parseShift()  &lt;&lt; &gt;&gt; &gt;&gt;&gt;
 *                                                            └─ parseAdditive()  + -
 *                                                                 └─ parseMultiplicative()  * / % // ** %%
 *                                                                      └─ parsePower()  ** (右结合)
 *                                                                           └─ parseUnary()  ++ -- !! + - ! ~ (Type)强制转换
 *                                                                                └─ parsePostfix()  ++ -- . ?. () [] :: (链式调用)
 *                                                                                     └─ parsePrimary()  字面量/标识符/new/(expr)/[array]/{map}
 * </pre>
 *
 * @see BaseParser
 * @see ConstantFolder
 */
public class ExprParser extends BaseParser {

    /** 方法重载选择器（解析期绑定）。 */
    private final MethodResolver methodResolver;

    /** 泛型闭合括号缓存：当遇到 >> 时拆分为两个 >，多余的存于此。 */
    private int pendingAngleBrackets = 0;

    /** 嵌套类解析缓存：避免重复 Class.forName + getDeclaredClasses */
    private final Map<String, Class<?>> nestedClassCache = new HashMap<>();

    /**
     * 构造器。
     *
     * @param tokens   完整的 token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public ExprParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
        this.methodResolver = new MethodResolver(context);
    }

    // ==================== 类型标注辅助 ====================

    /** 为 AST 节点设置解析期类型（便捷方法）。 */
    private void annotate(ASTNode node, JType type) {
        context.setType(node, type);
    }

    /** 为 AST 节点设置解析期类型（Class 便捷重载）。 */
    private void annotate(ASTNode node, Class<?> clazz) {
        if (clazz != null) {
            context.setType(node, JType.of(clazz));
        }
    }

    /**
     * 标注可能被常量折叠的结果节点。
     * <p>
     * 如果 {@code node} 是折叠后产生的新 LiteralNode（不在 typeMap 中），
     * 则从其自身携带的类型信息标注；否则使用 {@code fallbackType} 标注。
     *
     * @param node         表达式节点（可能已被常量折叠替换）
     * @param fallbackType 未折叠时的类型，为 {@code null} 时不标注
     */
    private void annotateFoldingResult(ASTNode node, Class<?> fallbackType)
            throws CythavaParseException {
        if (node instanceof LiteralNode literal && context.getType(node) == null) {
            Class<?> literalType = literal.getType();
            if (literalType != null) {
                annotate(node, literalType);
                return;
            }
        }
        if (fallbackType != null) {
            annotate(node, fallbackType);
        }

        // 运算符重载检查：BinaryOpNode / UnaryOpNode 构建后查询 OperatorRegistry
        if (node instanceof BinaryOpNode binaryOp) {
            checkCustomOperator(binaryOp);
        }
        if (node instanceof UnaryOpNode unaryOp) {
            checkCustomUnaryOperator(unaryOp);
        }
    }

    /**
     * 检查二元运算符是否有匹配的重载（内置或用户自定义）。
     * <p>
     * 找到匹配时：
     * <ul>
     *   <li>内置运算符 → 直接将 Java 回调写入 BinaryOpNode，运行期零开销</li>
     *   <li>用户自定义 → 标记类型信息，运行期首次执行时缓存回调</li>
     * </ul>
     * 找不到匹配且处于严格模式 → 解析期报错；宽松模式则静默放行交给运行时。
     */
    private void checkCustomOperator(BinaryOpNode binaryOp)
            throws CythavaParseException {
        OperatorRegistry registry = context.getOperatorRegistry();
        if (registry == null || registry.isEmpty()) return;

        String opSymbol = binaryOp.getOperator().getSymbol();
        JType lhsJType = context.getType(binaryOp.getLeft());
        JType rhsJType = context.getType(binaryOp.getRight());

        Class<?> lhsType = lhsJType != null ? lhsJType.getRuntimeType() : null;
        Class<?> rhsType = rhsJType != null ? rhsJType.getRuntimeType() : null;

        if (lhsType == null || rhsType == null) return; // 类型未知时跳过

        OperatorRegistry.Overload overload =
                registry.findBinaryCompatible(opSymbol, lhsType, rhsType);

        if (overload != null) {
            // 从运算符注册信息推导精确的返回值类型
            Class<?> returnType = overload.returnType();
            // 数组运算：根据操作数的实际数组类型计算 LUB，而非直接用注册的 Object.class
            if (lhsType.isArray() && rhsType.isArray()) {
                returnType = OperatorRegistry.computeArrayReturnType(lhsType, rhsType);
            }
            context.setType(binaryOp, JType.of(returnType));
            return;
        }

        // 严格模式：运算符无匹配 → 解析期报错
        if (context.isStrictMode()) {
            throw error(String.format(
                    "No matching operator '%s' for types '%s' and '%s'",
                    opSymbol, lhsType.getSimpleName(), rhsType.getSimpleName()),
                    ErrorCode.PARSE_INVALID_TYPE);
        }
        // 宽松模式：静默放行，运行期走 Evaluator switch 兜底
    }

    /** 判断类型是否过于模糊（Object），不适合在解析期绑定运算符回调。 */
    private static boolean isAmbiguousType(Class<?> type) {
        return type == Object.class || type == null;
    }

    /**
     * 检查一元运算符是否有匹配的重载。逻辑同 {@link #checkCustomOperator}。
     */
    private void checkCustomUnaryOperator(UnaryOpNode unaryOp)
            throws CythavaParseException {
        OperatorRegistry registry = context.getOperatorRegistry();
        if (registry == null || registry.isEmpty()) return;

        String opSymbol = unaryOp.getOperator().getSymbol();
        JType operandJType = context.getType(unaryOp.getOperand());
        Class<?> operandType = operandJType != null ? operandJType.getRawType() : null;
        if (operandType == null) return;

        OperatorRegistry.Overload overload =
                registry.findUnaryCompatible(opSymbol, operandType);

        if (overload != null) {
            context.setType(unaryOp, JType.of(overload.returnType()));
            return;
        }

        if (context.isStrictMode()) {
            throw error(String.format(
                    "No matching operator '%s' for type '%s'",
                    opSymbol, operandType.getSimpleName()),
                    ErrorCode.PARSE_INVALID_TYPE);
        }
    }

    /**
     * 根据用户显式指定的泛型签名（如 {@code <int>}）精确匹配方法重载。
     * <p>
     * 当用户写 {@code System.out::<int>println} 时，typeArgs 为 {@code [int]}，
     * 此方法会找到 {@code PrintStream.println(int)} 而非其他重载。
     *
     * @param methodRef 方法引用节点
     * @param methodName 方法名
     * @param typeArgs  用户显式指定的参数类型列表
     * @return 精确匹配的 Method，无匹配时返回 null
     */
    private Method resolveByExplicitSignature(MethodReferenceNode methodRef, String methodName,
                                              List<GenericType> typeArgs) {
        ASTNode target = methodRef.getTarget();
        Class<?> targetClass = inferTargetClassFromNode(target);
        if (targetClass == null) return null;

        // 将 GenericType 转为 Class<?> 数组
        Class<?>[] paramTypes = new Class<?>[typeArgs.size()];
        for (int i = 0; i < typeArgs.size(); i++) {
            Class<?> raw = typeArgs.get(i).getRawType();
            if (raw == null) return null;  // 类型解析失败
            paramTypes[i] = raw;
        }

        try {
            for (Method m : targetClass.getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] mParamTypes = m.getParameterTypes();
                if (mParamTypes.length != paramTypes.length) continue;
                boolean exactMatch = true;
                for (int i = 0; i < mParamTypes.length; i++) {
                    if (!isAssignable(paramTypes[i], mParamTypes[i])) {
                        exactMatch = false;
                        break;
                    }
                }
                if (exactMatch) return m;
            }
        } catch (SecurityException ignored) {
            // fall through
        }
        return null;
    }

    /** 从方法引用目标节点推断目标 Class。 */
    private Class<?> inferTargetClassFromNode(ASTNode target) {
        if (target instanceof ClassReferenceNode classRef) {
            return classRef.getResolvedClass();
        }
        JType jtype = context.getType(target);
        if (jtype != null) {
            Class<?> raw = jtype.getRawType();
            if (raw != null) return raw;
        }
        return null;
    }

    /** 检查 from 类型是否可赋值给 to 类型（含装箱/拆箱转换）。 */
    private boolean isAssignable(Class<?> from, Class<?> to) {
        if (from == to) return true;
        if (from.isPrimitive()) {
            return to.isAssignableFrom(boxPrimitive(from));
        }
        if (to.isPrimitive()) {
            return from.isAssignableFrom(boxPrimitive(to));
        }
        return to.isAssignableFrom(from);
    }

    private static Class<?> boxPrimitive(Class<?> primitive) {
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

    /**
     * 解析节点类型：优先从 typeMap 获取，fallback 到节点自身携带的类型信息。
     * <p>
     * 用于 switch LCM 等场景：case body 中的 LiteralNode 可能因解析路径差异
     * 未被 annotate 到 typeMap，但节点自身仍持有正确的类型。
     */
    private Class<?> resolveNodeType(ASTNode node) {
        JType type = context.getType(node);
        if (type != null)
            return type.getRawType();
        // fallback: 从 LiteralNode 自身获取类型
        if (node instanceof LiteralNode literal) {
            return literal.getType();
        }
        return null;
    }

    // ==================== 入口方法 ====================

    /**
     * 解析一个完整表达式（入口方法）。
     * <p>
     * 处理 {@code async}、{@code await} 前缀和 switch 表达式，
     * 然后委托给 {@link #parseAssignment()} 进行核心解析。
     * </p>
     *
     * @return AST 表达式节点
     * @throws CythavaParseException 语法错误
     */
    public ASTNode parseNextExpression() throws CythavaParseException {
        // async { ... } 或 async expr
        if (match(TokenType.KEYWORD_ASYNC)) {
            SourceLocation location = createLocation();
            ASTNode expr;
            if (check(TokenType.DELIMITER_LEFT_BRACE)) {
                savePosition();
                try {
                    expr = parseNextExpression();
                    if (!check(TokenType.DELIMITER_RIGHT_BRACE)) {
                        restorePosition();
                        match(TokenType.DELIMITER_LEFT_BRACE);
                        expr = parseBlockExpression();
                    }
                } catch (CythavaParseException e) {
                    restorePosition();
                    match(TokenType.DELIMITER_LEFT_BRACE);
                    expr = parseBlockExpression();
                }
            } else {
                expr = parseNextExpression();
            }
            AsyncNode asyncNode = new AsyncNode.Builder().expression(expr).location(location).build();
            // async 包装表达式，值类型与内部表达式一致
            JType asyncInnerType = context.getType(expr);
            if (asyncInnerType != null) {
                annotate(asyncNode, asyncInnerType);
            } else {
                annotate(asyncNode, Object.class);
            }
            return asyncNode;
        }

        // await expr
        if (match(TokenType.KEYWORD_AWAIT)) {
            SourceLocation location = createLocation();
            ASTNode expr = parseNextExpression();
            AwaitNode awaitNode = (AwaitNode) new AwaitNode.Builder()
                    .expression(expr)
                    .location(location)
                    .build();
            // await 从 CompletableFuture<T> 解包出 T
            JType awaitInnerType = context.getType(expr);
            if (awaitInnerType != null) {
                annotate(awaitNode, awaitInnerType);
            } else {
                annotate(awaitNode, Object.class);
            }
            return awaitNode;
        }

        // switch 表达式
        if (match(TokenType.KEYWORD_SWITCH)) {
            return parseSwitchExpression();
        }

        return parseAssignment();
    }

    // ==================== L1: 赋值表达式 ====================

    /**
     * 解析赋值与复合赋值表达式。
     * <p>
     * 支持的操作符：
     * <ul>
     * <li>简单赋值: {@code =}</li>
     * <li>复合赋值: {@code += -= *= /= %= &= |= ^= <<= >>= >>>=}</li>
     * <li>Cythava 扩展: {@code ?=} (条件赋值), {@code ??=} (空值合并赋值)</li>
     * </ul>
     * 左值必须是 {@link VariableNode}、{@link FieldAccessNode} 或 {@link ArrayAccessNode}。
     * </p>
     */
    private ASTNode parseAssignment() throws CythavaParseException {
        ASTNode left = parseTernary();

        // 简单赋值 =
        if (match(TokenType.OPERATOR_ASSIGN)) {
            ASTNode right = parseTernary();
            return buildAssignment(left, right);
        }

        // 复合赋值 += -= *= /= %=
        BinaryOpNode.Operator compoundOp = switchCompoundOperator();
        if (compoundOp != null) {
            ASTNode right = parseTernary();
            ASTNode combinedValue = new BinaryOpNode.Builder()
                    .operator(compoundOp)
                    .left(left)
                    .right(right)
                    .location(left.getLocation())
                    .build();
            return buildAssignment(left, combinedValue);
        }

        // 条件赋值 ?=
        if (match(TokenType.OPERATOR_CONDITIONAL_ASSIGN)) {
            ASTNode right = parseTernary();
            if (left instanceof VariableNode v) {
                ConditionalAssignNode node = (ConditionalAssignNode) new ConditionalAssignNode.Builder()
                        .variableName(v.getName())
                        .value(right)
                        .location(left.getLocation())
                        .build();
                annotate(node, context.getRawType(right));
                return node;
            }
        }

        // 空值合并赋值 ??=
        if (match(TokenType.OPERATOR_NULL_COALESCING_ASSIGN)) {
            ASTNode right = parseTernary();
            if (left instanceof VariableNode v) {
                NullCoalescingAssignNode node =
                        (NullCoalescingAssignNode) new NullCoalescingAssignNode.Builder()
                        .variableName(v.getName())
                        .value(right)
                        .location(left.getLocation())
                        .build();
                annotate(node, context.getRawType(right));
                return node;
            }
        }

        return left;
    }

    /**
     * 根据左值类型构建对应的赋值节点。
     */
    private ASTNode buildAssignment(ASTNode left, ASTNode right) throws CythavaParseException {
        JType rightType = context.getType(right);
        if (left instanceof VariableNode v) {
            AssignmentNode node =
                    (AssignmentNode) new AssignmentNode.Builder()
                        .variableName(v.getName())
                        .value(right)
                        .isDeclaration(false)
                        .location(left.getLocation())
                        .build();
            annotate(node, rightType);
            return node;
        }
        if (left instanceof FieldAccessNode fieldAccess) {
            FieldAssignmentNode node = (FieldAssignmentNode) new FieldAssignmentNode.Builder()
                    .target(fieldAccess.getTarget())
                    .fieldName(fieldAccess.getFieldName())
                    .value(right)
                    .location(left.getLocation())
                    .build();
            annotate(node, rightType);
            return node;
        }
        if (left instanceof ArrayAccessNode arrayAccess) {
            ArrayAssignmentNode node = (ArrayAssignmentNode) new ArrayAssignmentNode.Builder()
                    .array(arrayAccess.getArray())
                    .index(arrayAccess.getIndex())
                    .value(right)
                    .location(left.getLocation())
                    .build();
            annotate(node, rightType);
            return node;
        }
            throw error("Invalid assignment target", ErrorCode.PARSE_UNEXPECTED_TOKEN);
    }

    /**
     * 尝试匹配复合赋值操作符，返回对应的 BinaryOpNode.Operator，无则返回 null。
     */
    private BinaryOpNode.Operator switchCompoundOperator() {
        if (match(TokenType.OPERATOR_PLUS_ASSIGN))
            return BinaryOpNode.Operator.ADD;
        if (match(TokenType.OPERATOR_MINUS_ASSIGN))
            return BinaryOpNode.Operator.SUBTRACT;
        if (match(TokenType.OPERATOR_MULTIPLY_ASSIGN))
            return BinaryOpNode.Operator.MULTIPLY;
        if (match(TokenType.OPERATOR_DIVIDE_ASSIGN))
            return BinaryOpNode.Operator.DIVIDE;
        if (match(TokenType.OPERATOR_MODULO_ASSIGN))
            return BinaryOpNode.Operator.MODULO;
        if (match(TokenType.OPERATOR_BITWISE_AND_ASSIGN))
            return BinaryOpNode.Operator.BITWISE_AND;
        if (match(TokenType.OPERATOR_BITWISE_OR_ASSIGN))
            return BinaryOpNode.Operator.BITWISE_OR;
        if (match(TokenType.OPERATOR_BITWISE_XOR_ASSIGN))
            return BinaryOpNode.Operator.BITWISE_XOR;
        if (match(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN))
            return BinaryOpNode.Operator.LEFT_SHIFT;
        if (match(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN))
            return BinaryOpNode.Operator.RIGHT_SHIFT;
        if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN))
            return BinaryOpNode.Operator.UNSIGNED_RIGHT_SHIFT;
        return null;
    }

    // ==================== L2: 三元 + 管道 ====================

    /**
     * 解析三元条件表达式 ({@code cond ? then : else}) 和管道操作符 ({@code |>})。
     * <p>
     * 管道操作符是左结合的，用 while 循环处理链式调用：
     * {@code list |> filter |> map |> collect}
     * </p>
     */
    private ASTNode parseTernary() throws CythavaParseException {
        ASTNode expr = parseNullCoalescing();

        // 三元表达式 cond ? then : else
        if (match(TokenType.OPERATOR_QUESTION)) {
            SourceLocation location = createLocation();
            ASTNode thenExpr = parseNextExpression(); // 允许嵌套三元
            consume(TokenType.OPERATOR_COLON, "Expected ':' in ternary expression");
            ASTNode elseExpr = parseNextExpression();
            ASTNode node = new TernaryNode.Builder()
                    .condition(expr)
                    .thenExpr(thenExpr)
                    .elseExpr(elseExpr)
                    .location(location)
                    .build();
            // 三元结果类型：then/else 的 LCM（最小公共超类型），含数值提升
            annotate(node, computeTernaryLCM(thenExpr, elseExpr));
            return node;
        }

        // 管道操作符 |> （左结合）
        while (match(TokenType.OPERATOR_PIPELINE)) {
            SourceLocation location = createLocation();
            ASTNode function = parseNullCoalescing();
            expr = new PipelineNode.Builder()
                    .input(expr)
                    .function(function)
                    .location(location)
                    .build();
            // 管道结果类型：最后一步的输出类型
            annotate(expr, context.getType(function));
        }

        return expr;
    }

    // ==================== L3: 空值合并 ====================

    /**
     * 解析空值合并 ({@code ??}) 和 Elvis ({@code ?:}) 操作符。
     */
    private ASTNode parseNullCoalescing() throws CythavaParseException {
        ASTNode left = parseLogicalOr();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_NULL_COALESCING)) {
                ASTNode right = parseLogicalOr();
                left = new BinaryOpNode.Builder()
                        .operator(BinaryOpNode.Operator.NULL_COALESCING)
                        .left(left)
                        .right(right)
                        .location(location).build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_ELVIS)) {
                ASTNode right = parseLogicalOr();
                left = new BinaryOpNode.Builder()
                        .operator(BinaryOpNode.Operator.ELVIS)
                        .left(left)
                        .right(right)
                        .location(location).build();
                annotateFoldingResult(left, null);
            } else {
                break;
            }
        }
        return left;
    }

    // ==================== L4-L7: 逻辑/位运算 ====================

    /** 解析逻辑或 {@code ||}。 */
    private ASTNode parseLogicalOr() throws CythavaParseException {
        ASTNode left = parseLogicalAnd();

        while (match(TokenType.OPERATOR_LOGICAL_OR)) {
            SourceLocation location = createLocation();
            ASTNode right = parseLogicalAnd();
            left = new BinaryOpNode.Builder()
                    .operator(BinaryOpNode.Operator.LOGICAL_OR)
                    .left(left).right(right)
                    .location(location).build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    /** 解析逻辑与 {@code &&}。 */
    private ASTNode parseLogicalAnd() throws CythavaParseException {
        ASTNode left = parseBitwiseOr();

        while (match(TokenType.OPERATOR_LOGICAL_AND)) {
            SourceLocation location = createLocation();
            ASTNode right = parseBitwiseOr();
            left = new BinaryOpNode.Builder()
                    .operator(BinaryOpNode.Operator.LOGICAL_AND)
                    .left(left).right(right)
                    .location(location).build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    /** 解析按位或 {@code |}。注意区分 {@code ||}。 */
    private ASTNode parseBitwiseOr() throws CythavaParseException {
        ASTNode left = parseBitwiseXor();

        while (check(TokenType.OPERATOR_BITWISE_OR) && !checkNext(TokenType.OPERATOR_BITWISE_OR)) {
            advance();
            SourceLocation location = createLocation();
            ASTNode right = parseBitwiseXor();
            left = new BinaryOpNode.Builder()
                    .operator(BinaryOpNode.Operator.BITWISE_OR)
                    .left(left).right(right)
                    .location(location).build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    /** 解析按位异或 {@code ^}。 */
    private ASTNode parseBitwiseXor() throws CythavaParseException {
        ASTNode left = parseBitwiseAnd();

        while (match(TokenType.OPERATOR_BITWISE_XOR)) {
            SourceLocation location = createLocation();
            ASTNode right = parseBitwiseAnd();
            left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.BITWISE_XOR)
                    .left(left).right(right)
                    .location(location)
                    .build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    /** 解析按位与 {@code &}。注意区分 {@code &&}。 */
    private ASTNode parseBitwiseAnd() throws CythavaParseException {
        ASTNode left = parseEquality();

        while (check(TokenType.OPERATOR_BITWISE_AND) && !checkNext(TokenType.OPERATOR_BITWISE_AND)) {
            advance();
            SourceLocation location = createLocation();
            ASTNode right = parseEquality();
            left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.BITWISE_AND)
                    .left(left).right(right)
                    .location(location)
                    .build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    // ==================== L8: 相等性 + 范围 ====================

    /**
     * 解析相等性比较 ({@code == !=}) 和范围操作符 ({@code .. ..<})。
     * <p>
     * 范围操作符生成 {@link BinaryOpNode}，Evaluator 层将其展开为数组字面量。
     * </p>
     */
    private ASTNode parseEquality() throws CythavaParseException {
        ASTNode left = parseComparison();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_EQUAL)) {
                ASTNode right = parseComparison();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.EQUAL)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_NOT_EQUAL)) {
                ASTNode right = parseComparison();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.NOT_EQUAL)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_RANGE)) {
                ASTNode right = parseComparison();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.RANGE)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_RANGE_EXCLUSIVE)) {
                ASTNode right = parseComparison();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.RANGE_EXCLUSIVE)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else {
                break;
            }
        }
        return left;
    }

    // ==================== L9: 比较 + instanceof ====================

    /**
     * 解析比较操作符 ({@code < <= > >= <=>}) 和 {@code instanceof}。
     */
    private ASTNode parseComparison() throws CythavaParseException {
        ASTNode left = parseShift();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_LESS_THAN)) {
                ASTNode right = parseShift();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.LESS_THAN)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_LESS_THAN_OR_EQUAL)) {
                ASTNode right = parseShift();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.LESS_THAN_OR_EQUAL)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_GREATER_THAN)) {
                ASTNode right = parseShift();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.GREATER_THAN)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_GREATER_THAN_OR_EQUAL)) {
                ASTNode right = parseShift();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.GREATER_THAN_OR_EQUAL)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_SPACESHIP)) {
                ASTNode right = parseShift();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.SPACESHIP)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.KEYWORD_INSTANCEOF)) {
                String typeName = parseInstanceofType();
                left = new InstanceofNode.Builder()
                        .expression(left)
                        .typeName(typeName)
                        .location(location)
                        .build();
                annotate(left, boolean.class); // instanceof 结果总是 boolean
            } else {
                break;
            }
        }
        return left;
    }

    /**
     * 解析 {@code instanceof} 后面的类型名。
     * 支持简单类型名、全限定类名、带数组的类型。
     */
    private String parseInstanceofType() throws CythavaParseException {
        StringBuilder sb = new StringBuilder();

        // 第一个标识符或基本类型关键字
        if (isTypeStartToken()) {
            sb.append(advance().text());
        } else {
            throw error("Expected type name after 'instanceof'", ErrorCode.PARSE_INVALID_TYPE);
        }

        // 处理包路径 foo.bar.Baz
        while (match(TokenType.OPERATOR_DOT)) {
            if (check(TokenType.IDENTIFIER)) {
                sb.append('.').append(advance().text());
            } else {
                throw error("Expected identifier after '.' in type name", ErrorCode.PARSE_INVALID_TYPE);
            }
        }

        // 处理数组维度 int[]
        while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
            consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after '[' in array type");
            sb.append("[]");
        }

        return sb.toString();
    }

    // ==================== L10: 移位 ====================

    /** 解析位移操作符 {@code << >> >>>}。 */
    private ASTNode parseShift() throws CythavaParseException {
        ASTNode left = parseAdditive();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_LEFT_SHIFT)) {
                ASTNode right = parseAdditive();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.LEFT_SHIFT)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_RIGHT_SHIFT)) {
                ASTNode right = parseAdditive();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.RIGHT_SHIFT)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT)) {
                ASTNode right = parseAdditive();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.UNSIGNED_RIGHT_SHIFT)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else {
                break;
            }
        }
        return left;
    }

    // ==================== L11: 加减法 ====================

    /** 解析加法和减法 {@code + -}。 */
    private ASTNode parseAdditive() throws CythavaParseException {
        ASTNode left = parseMultiplicative();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_PLUS)) {
                ASTNode right = parseMultiplicative();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.ADD)
                        .left(left).right(right)
                        .location(location).build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_MINUS)) {
                ASTNode right = parseMultiplicative();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.SUBTRACT)
                        .left(left).right(right)
                        .location(location).build();
                annotateFoldingResult(left, null);
            } else {
                break;
            }
        }
        return left;
    }

    // ==================== L12: 乘除模 + 幂 ====================

    /**
     * 解析乘除模 ({@code * / % // %%}) 和幂运算 ({@code **})。
     * <p>
     * 幕运算 {@code **} 的优先级高于乘除，但低于一元运算符，且为右结合。
     * </p>
     */
    private ASTNode parseMultiplicative() throws CythavaParseException {
        ASTNode left = parsePower();

        while (true) {
            SourceLocation location = createLocation();
            if (match(TokenType.OPERATOR_MULTIPLY)) {
                ASTNode right = parsePower();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.MULTIPLY)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_DIVIDE)) {
                ASTNode right = parsePower();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.DIVIDE)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_MODULO)) {
                ASTNode right = parsePower();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.MODULO)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_INT_DIVIDE)) {
                ASTNode right = parsePower();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.INT_DIVIDE)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else if (match(TokenType.OPERATOR_MATH_MODULO)) {
                ASTNode right = parsePower();
                left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.MATH_MODULO)
                        .left(left).right(right)
                        .location(location)
                        .build();
                annotateFoldingResult(left, null);
            } else {
                break;
            }
        }
        return left;
    }

    /**
     * 解析幂运算 {@code **}（右结合）。
     * <p>
     * 右结合通过递归调用自身实现：{@code 2 ** 3 ** 2} = {@code 2 ** (3 ** 2)}。
     * </p>
     */
    private ASTNode parsePower() throws CythavaParseException {
        ASTNode left = parseUnary();

        if (match(TokenType.OPERATOR_POWER)) {
            SourceLocation location = createLocation();
            ASTNode right = parsePower(); // 右结合：递归调用自身
            left = new BinaryOpNode.Builder().operator(BinaryOpNode.Operator.POWER)
                    .left(left).right(right)
                    .location(location)
                    .build();
            annotateFoldingResult(left, null);
        }
        return left;
    }

    // ==================== L13: 一元前缀 + 强制转换 ====================

    /**
     * 解析一元前缀运算符和强制类型转换。
     * <p>
     * 支持的一元前缀：
     * <ul>
     * <li>算术: {@code + -}</li>
     * <li>逻辑: {@code !}</li>
     * <li>位运算: {@code ~}</li>
     * <li>Cythava 扩展: {@code !!} (非空断言)</li>
     * <li>自增/自减前缀: {@code ++ --}</li>
     * </ul>
     * 强制转换: {@code (TypeName) expression}
     * </p>
     */
    private ASTNode parseUnary() throws CythavaParseException {
        SourceLocation location = createLocation();

        // 自增/自减前缀 ++x --x
        if (match(TokenType.OPERATOR_INCREMENT)) {
            ASTNode operand = parseUnary();
            ASTNode node = new UnaryOpNode.Builder().operator(UnaryOpNode.Operator.PRE_INCREMENT)
                    .operand(operand)
                    .location(location)
                    .build();
            annotate(node, context.getRawType(operand)); // 同操作数类型
            return node;
        }
        if (match(TokenType.OPERATOR_DECREMENT)) {
            ASTNode operand = parseUnary();
            ASTNode node = new UnaryOpNode.Builder().operator(UnaryOpNode.Operator.PRE_DECREMENT)
                    .operand(operand)
                    .location(location)
                    .build();
            annotate(node, context.getRawType(operand));
            return node;
        }

        // 算术一元 + -
        if (match(TokenType.OPERATOR_PLUS)) {
            ASTNode operand = parseUnary();
            ASTNode node = new UnaryOpNode.Builder()
                    .operator(UnaryOpNode.Operator.POSITIVE)
                    .operand(operand)
                    .location(location).build();
            annotateFoldingResult(node, context.getRawType(operand));
            return node;
        }
        if (match(TokenType.OPERATOR_MINUS)) {
            ASTNode operand = parseUnary();
            ASTNode result = new UnaryOpNode.Builder()
                    .operator(UnaryOpNode.Operator.NEGATIVE)
                    .operand(operand)
                    .location(location).build();
            annotateFoldingResult(result, context.getRawType(operand));
            return result;
        }

        // 逻辑非 !
        if (match(TokenType.OPERATOR_LOGICAL_NOT)) {
            ASTNode operand = parseUnary();
            ASTNode result = new UnaryOpNode.Builder()
                    .operator(UnaryOpNode.Operator.LOGICAL_NOT)
                    .operand(operand)
                    .location(location).build();
            annotateFoldingResult(result, boolean.class);
            return result;
        }

        // 位取反 ~
        if (match(TokenType.OPERATOR_BITWISE_NOT)) {
            ASTNode operand = parseUnary();
            ASTNode result = new UnaryOpNode.Builder()
                    .operator(UnaryOpNode.Operator.BITWISE_NOT)
                    .operand(operand)
                    .location(location).build();
            annotateFoldingResult(result, context.getRawType(operand));
            return result;
        }

        // Cythava 扩展: 非空断言 !!expr
        if (match(TokenType.OPERATOR_NOT_NULL)) {
            ASTNode operand = parseUnary();
            ASTNode node = new UnaryOpNode.Builder()
                    .operator(UnaryOpNode.Operator.NOT_NULL)
                    .operand(operand)
                    .location(location).build();
            annotate(node, context.getRawType(operand)); // !! 不改变类型，只声明非空
            return node;
        }

        // 强制转换 (Type) expression
        if (check(TokenType.DELIMITER_LEFT_PAREN)) {
            savePosition();
            if (match(TokenType.DELIMITER_LEFT_PAREN)) {
                ASTNode castResult = tryParseCast();
                if (castResult != null) {
                    return castResult;
                }
                // 不是强制转换，回退位置，让 parsePostfix 处理括号表达式
                restorePosition();
            } else {
                releasePosition();
            }
        }

        return parsePostfix();
    }

    /**
     * 尝试解析强制类型转换 {@code (Type) expr}。
     * <p>
     * 在 {@code parseUnary()} 中调用，此时 {@code (} 已被消费。
     * 如果括号内是类型名且后面紧跟表达式，则是强制转换；
     * 否则返回 null 表示不是强制转换（调用方需 restorePosition）。
     * </p>
     *
     * @return CastNode 如果成功；null 如果当前位置不是强制转换
     */
    private ASTNode tryParseCast() {
        savePosition();

        try {
            if (!isTypeStartToken()) {
                restorePosition();
                return null;
            }

            TypeParser typeParser = new TypeParser(tokens, context, fileName);
            typeParser.setPosition(position);
            GenericType castType = typeParser.parseType();
            position = typeParser.getPosition();

            if (!match(TokenType.DELIMITER_RIGHT_PAREN)) {
                restorePosition();
                return null;
            }

            Class<?> targetType = castType.getRawType();
            if (targetType == null) targetType = Object.class;

            SourceLocation castLocation = createLocation();
            ASTNode innerExpr = parseUnary();

            releasePosition();
            CastNode castNode = (CastNode) new CastNode.Builder()
                    .targetType(targetType)
                    .expression(innerExpr)
                    .location(castLocation).build();
            annotate(castNode, targetType);
            return castNode;
        } catch (CythavaParseException e) {
            restorePosition();
            return null;
        }
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
    private ASTNode parsePostfix() throws CythavaParseException {
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
                    try {
                        advance(); // 消费 <
                        typeArgs = parseGenericTypeArgumentList();
                        consumeGenericClose();
                    } catch (CythavaParseException e) {
                        position = savedPos;
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
        // 支持泛型构造器 .<TypeArgs>new() 和泛型方法 .<TypeArgs>method()
        List<GenericType> explicitTypeArgs = null;
        int savedPos = position;
        if (check(TokenType.OPERATOR_LESS_THAN)) {
            try {
                advance(); // 消费 <
                explicitTypeArgs = parseGenericTypeArgumentList();
                consumeGenericClose();
                consume(TokenType.OPERATOR_DOT, "Expected '.' after generic type arguments");
            } catch (CythavaParseException e) {
                position = savedPos;
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
            java.lang.reflect.Field field = findFieldInHierarchy(targetType, memberName);
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
                    throw new CythavaParseException(
                            "No such field '" + memberName + "' in class " + targetType.getSimpleName(),
                            nameToken.location());
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
                    throw new CythavaParseException(
                            "No such field '" + memberName + "' in class " + targetClassName,
                            nameToken.location());
                }
            }

            // 回退 2：当前类上下文（方法体内 this.xxx 延迟解析）
            String currentClass = context.getCurrentClassName();
            if (currentClass != null) {
                Class<?> resolvedCurrent = context.resolveClass(currentClass);
                if (resolvedCurrent != null) {
                    java.lang.reflect.Field currentField = findFieldInHierarchy(resolvedCurrent, memberName);
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
                                throw new CythavaParseException(
                                        "No such field '" + memberName + "' in class " + rawTypeName,
                                        nameToken.location());
                            }
                        }
                    } else {
                        // 非严格模式：目标类型未知时标注为 Object
                        if (!context.isStrictMode()) {
                            annotate(node, Object.class);
                        } else {
                            throw new CythavaParseException(
                                    "Cannot resolve field '" + memberName + "' (target type unknown)",
                                    nameToken.location());
                        }
                    }
                } else {
                    // auto 变量可能没有类型信息，宽容处理：标注为 Object，运行时再解析
                    annotate(node, Object.class);
                }
            } else {
                // 非严格模式：目标类型未知时标注为 Object
                if (!context.isStrictMode()) {
                    annotate(node, Object.class);
                } else {
                    throw new CythavaParseException(
                            "Cannot resolve field '" + memberName + "' (target type unknown)",
                            nameToken.location());
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

        // 目标是 VariableNode 且无目标时 → 函数调用（builtin 或脚本定义函数）
        if (target instanceof VariableNode v) {
            FunctionCallNode node = (FunctionCallNode) new FunctionCallNode.Builder()
                    .functionName(v.getName())
                    .arguments(args)
                    .location(location)
                    .build();
            // 解析期类型推断：尝试绑定已知函数或静态方法
            JType returnType = inferFunctionReturnType(v.getName(), args);
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

    private JType inferFunctionReturnType(String funcName, List<ASTNode> args) {
        // 如果是已知变量且值为 Function，通过类型推断获取返回类型
        if (context.isKnownVariable(funcName)) {
            return context.getType(null); // 无法推断时返回 null
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
                        java.lang.reflect.Field classField = findFieldInHierarchy(resolvedClass, name);
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

            // 优先级 5: 全都不匹配
            if (context.isStrictMode()) {
                // 严格模式：不允许不存在的标识符，直接报错
                throw error("Cannot find symbol: '" + name + "'",
                        ErrorCode.SCOPE_VARIABLE_NOT_FOUND);
            } else {
                // 非严格模式：返回未解析 VariableNode（测试/增量解析场景）
                VariableNode unresolved = (VariableNode) new VariableNode.Builder().name(name)
                        .location(token.location()).build();
                GenericType unresolvedType = context.getDeclaredType(name);
                if (unresolvedType != null) {
                    context.setType(unresolved, JType.fromGenericType(unresolvedType));
                } else {
                    context.setType(unresolved, JType.of(Object.class));
                }
                return unresolved;
            }
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

    // ==================== 特殊表达式解析 ====================

    /**
     * 解析 f-string 插值字符串。
     * <p>
     * Token 的 value 是 {@code List&lt;Object&gt;}，元素交替为：
     * {@code String}（字面文本）和 {@code Lexer.InterpolationPart}（需要子解析的表达式）。
     * </p>
     */
    private InterpolatedStringNode parseInterpolatedString(Token token) throws CythavaParseException {
        @SuppressWarnings("unchecked")
        List<Object> parts = (List<Object>) token.value();
        InterpolatedStringNode.Builder builder = new InterpolatedStringNode.Builder();
        builder.location(token.location());

        for (Object part : parts) {
            if (part instanceof String literal) {
                builder.addLiteral(literal);
            } else if (part instanceof Lexer.InterpolationPart ip) {
                // 对插值表达式进行子解析
                Lexer subLexer = new Lexer(ip.getExpression(), fileName);
                List<Token> subTokens = subLexer.tokenize();
                ExprParser subParser = new ExprParser(subTokens, context, fileName);
                ASTNode expr = subParser.parseNextExpression();
                builder.addExpression(expr);
            }
        }

        return (InterpolatedStringNode) builder.build();
    }

    /**
     * 推断数组字面量的元素类型。
     * <p>
     * 所有元素类型相同（含原始类型 widening）→ 返回该类型；
     * 元素类型不一致或无法推断 → 返回 null。
     */
    private Class<?> inferArrayLiteralElementType(List<ASTNode> elements) {
        if (elements.isEmpty())
            return null;
        Class<?> firstType = null;
        for (ASTNode elem : elements) {
            JType elemType = context.getType(elem);
            if (elemType == null)
                return null;
            Class<?> raw = elemType.getRawType();
            if (firstType == null) {
                firstType = raw;
            } else if (!firstType.equals(raw)) {
                // 类型不一致：检查是否可以 widen 到数值类型
                if (isNumericWidening(firstType, raw)) {
                    firstType = raw; // 提升到更宽的类型
                } else {
                    return null; // 不一致且不能提升
                }
            }
        }
        return firstType;
    }

    /** 检查 from 是否可以数值提升到 to（简化版，只处理常见 widening）。 */
    private boolean isNumericWidening(Class<?> from, Class<?> to) {
        Class<?>[] order = { Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE };
        int fromIdx = -1, toIdx = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(from))
                fromIdx = i;
            if (order[i].equals(to))
                toIdx = i;
        }
        return fromIdx >= 0 && toIdx > fromIdx;
    }

    private ArrayLiteralNode parseArrayLiteral() throws CythavaParseException {
        SourceLocation location = createLocation();
        consume(TokenType.DELIMITER_LEFT_BRACKET, "Expected '['");

        List<ASTNode> elements = collectCommaSeparated(
                TokenType.DELIMITER_RIGHT_BRACKET,
                this::parseNextExpression);

        consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after array literal");
        ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(elements).location(location)
                .build();
        // 从元素推导数组类型：所有元素类型相同 → 该类型的数组；否则 → Object[]
        Class<?> elementType = inferArrayLiteralElementType(elements);
        if (elementType != null) {
            // 用 GenericType 正确设置 arrayDepth=1，再包装为 JType
            GenericType gt = new GenericType(elementType, Collections.emptyList(), 1);
            context.setType(node, JType.fromGenericType(gt));
        } else {
            annotate(node, Object[].class);
        }
        return node;
    }

    /**
     * 解析花括号初始化器。
     * <p>
     * 根据首元素后的内容判断是数组初始化器还是 Map 字面量：
     * <ul>
     * <li>{@code {1, 2, 3}} → 数组初始化器</li>
     * <li>{@code {"key": value, ...}} → Map 字面量（首元素后有 {@code :}）</li>
     * </ul>
     * </p>
     */
    private ASTNode parseBraceInitializer() throws CythavaParseException {
        SourceLocation location = createLocation();
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{'");

        // 空的花括号 → 空数组
        if (check(TokenType.DELIMITER_RIGHT_BRACE)) {
            advance();
            ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(new ArrayList<>())
                    .location(location).build();
            annotate(node, Object[].class);
            return node;
        }

        ASTNode firstElement = parseNextExpression();

        // 首元素后有 : → Map 字面量
        if (check(TokenType.OPERATOR_COLON)) {
            return parseMapLiteral(firstElement, location);
        }

        // 否则 → 数组初始化器
        List<ASTNode> elements = new ArrayList<>();
        elements.add(firstElement);
        elements.addAll(collectCommaSeparated(
                TokenType.DELIMITER_RIGHT_BRACE,
                this::parseNextExpression));

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after array initializer");
        ArrayLiteralNode node = (ArrayLiteralNode) new ArrayLiteralNode.Builder().elements(elements).location(location)
                .build();
        // 复用与 [...] 相同的元素类型推断逻辑
        Class<?> braceElementType = inferArrayLiteralElementType(elements);
        if (braceElementType != null) {
            com.justnothing.engine.ast.GenericType gt = new com.justnothing.engine.ast.GenericType(
                    braceElementType, java.util.Collections.emptyList(), 1);
            context.setType(node, JType.fromGenericType(gt));
        } else {
            annotate(node, Object[].class);
        }
        return node;
    }

    /**
     * 解析 Java 风格数组花括号初始化器 {@code {1, 2, 3}} 或 {@code {{1,2}, {3,4}}}。
     * <p>
     * 委托给 parseBraceInitializer，它已正确处理嵌套花括号和 Map 歧义。
     * </p>
     */
    private ArrayLiteralNode parseBraceArrayInitializer() throws CythavaParseException {
        ASTNode result = parseBraceInitializer();
        if (result instanceof ArrayLiteralNode aln) {
            return aln;
        }
        throw error("Expected array initializer", ErrorCode.PARSE_UNEXPECTED_TOKEN);
    }

    /**
     * 解析 Map 字面量 {@code {key: value, key2: value2, ...}}。
     * <p>
     * 调用时第一个 key 已解析完毕，{@code :} 尚未消费。
     * </p>
     */
    private MapLiteralNode parseMapLiteral(ASTNode firstKey, SourceLocation location)
            throws CythavaParseException {
        // 使用 LinkedHashMap 保持插入顺序
        LinkedHashMap<ASTNode, ASTNode> entries = new LinkedHashMap<>();

        consume(TokenType.OPERATOR_COLON, "Expected ':' in map literal");
        ASTNode firstValue = parseNextExpression();
        entries.put(firstKey, firstValue);

        while (match(TokenType.DELIMITER_COMMA)) {
            if (check(TokenType.DELIMITER_RIGHT_BRACE)) {
                break;
            }
            ASTNode key = parseNextExpression();
            consume(TokenType.OPERATOR_COLON, "Expected ':' in map literal");
            ASTNode value = parseNextExpression();
            entries.put(key, value);
        }

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after map literal");
        MapLiteralNode node = (MapLiteralNode) new MapLiteralNode.Builder().entries(entries).location(location).build();
        // Map 类型推断：非空 entries 推断为 Map.class，空 map 保持 Object.class
        if (!entries.isEmpty()) {
            annotate(node, Map.class);
        } else {
            annotate(node, Object.class);
        }
        return node;
    }

    /**
     * 解析括号表达式或 Lambda。
     * <p>
     * 当遇到 {@code (} 时，尝试以下顺序：
     * <ol>
     * <li>Lambda: {@code (params) -> body}</li>
     * <li>普通括号表达式: {@code (expression)}</li>
     * </ol>
     * </p>
     */
    private ASTNode parseParenExpressionOrLambda() throws CythavaParseException {
        savePosition();

        // 先尝试按 lambda 解析
        advance(); // 吃掉 (
        ASTNode lambda = tryParseLambdaInParens();
        if (lambda != null) {
            releasePosition();
            return lambda;
        }
        // tryParseLambdaInParens 返回 null = 确定不是 lambda（无 -> 匹配），不会抛异常
        // 只有确认是 Lambda 后 buildLambdaBody 才可能抛异常，那应该直接传播而非 fallback

        restorePosition();

        // 普通括号表达式
        advance(); // 吃掉 (
        ASTNode expr = parseNextExpression();
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after expression");
        return expr;
    }

    /** 检查下一个 token（不消费）是否是类型起始或标识符，用于 lambda 参数消歧。 */
    private boolean checkNextIsTypeStartOrIdentifier() {
        if (position + 1 >= tokens.size())
            return false;
        return isTypeStartToken(tokens.get(position + 1).type());
    }

    private ASTNode tryParseLambdaInParens() throws CythavaParseException {
        // 空括号 () ->
        if (check(TokenType.DELIMITER_RIGHT_PAREN)) {
            advance(); // 吃掉 )
            if (!match(TokenType.DELIMITER_ARROW)) {
                return null; // () 但后面不是 -> ，不是 lambda
            }
            return buildLambdaBody(new ArrayList<>());
        }

        // 尝试解析参数列表
        List<LambdaNode.Parameter> params = new ArrayList<>();
        boolean isLambda = true;

        do {
            if (!check(TokenType.IDENTIFIER) && !isTypeStartToken()) {
                isLambda = false;
                break;
            }

            // 可选的类型标注：只有当标识符后紧跟另一个标识符/类型时才视为类型名
            // 例如 (int x) → int 是类型, x 是参数名; (x) → x 是参数名（无类型标注）
            Class<?> paramTypeClass = null;
            if (isTypeStartToken()
                    && !check(TokenType.DELIMITER_RIGHT_PAREN)
                    && !check(TokenType.DELIMITER_COMMA)
                    && (checkNextIsTypeStartOrIdentifier())) {
                StringBuilder typeBuilder = new StringBuilder(advance().text());
                // 可能还有包路径 foo.bar.Type
                while (check(TokenType.OPERATOR_DOT) && checkNext(TokenType.IDENTIFIER)) {
                    advance(); // .
                    typeBuilder.append('.').append(advance().text()); // identifier
                }
                paramTypeClass = context.resolveClass(typeBuilder.toString());
                if (paramTypeClass == null) {
                    paramTypeClass = Object.class; // 非基本类型暂用 Object 占位
                }
            }

            // 参数名
            if (!check(TokenType.IDENTIFIER)) {
                isLambda = false;
                break;
            }
            String paramName = advance().text();
            params.add(new LambdaNode.Parameter(paramName, paramTypeClass));

        } while (match(TokenType.DELIMITER_COMMA) && !check(TokenType.DELIMITER_RIGHT_PAREN));

        if (!isLambda) {
            return null;
        }

        // 必须 ) ->
        if (!match(TokenType.DELIMITER_RIGHT_PAREN)) {
            return null;
        }
        if (!match(TokenType.DELIMITER_ARROW)) {
            return null;
        }

        return buildLambdaBody(params);
    }

    /**
     * 尝试解析单参数简写 lambda {@code x -> expr}。
     * <p>
     * 调用时标识符已消费，箭头尚未消费。
     * </p>
     */
    private ASTNode tryParseSingleParamLambda(String paramName, SourceLocation location)
            throws CythavaParseException {
        if (!match(TokenType.DELIMITER_ARROW)) {
            return new VariableNode.Builder().name(paramName).location(location).build(); // 不是
                                                                                                         // lambda，回退为变量
        }

        List<LambdaNode.Parameter> params = List.of(new LambdaNode.Parameter(paramName, null));
        return buildLambdaBody(params);
    }

    /**
     * 构建 Lambda 节点的 body 部分（箭头之后的内容）。
     * <p>
     * 如果箭头后是 {@code {} 则为块体，否则为表达式体。
     * 
    </p>
     */
    private ASTNode buildLambdaBody(List<LambdaNode.Parameter> params) throws CythavaParseException {
        SourceLocation location = createLocation();

        context.enterScope(ParseContext.ScopeKind.LAMBDA);
        for (LambdaNode.Parameter param : params) {
            if (param.type() != null) {
                // 显式类型标注：传递类型信息给符号表
                context.declareVariable(param.name(), param.type());
            } else {
                // 自动推断类型：用 inferred 标记，避免被当成 Object
                context.declareInferredVariable(param.name());
            }
        }

        ASTNode body;
        try {
            if (check(TokenType.DELIMITER_LEFT_BRACE)) {
                // 块体：消费 { 后循环解析，表达式用 ExprParser，声明语句用 StmtParser
                advance();
                List<ASTNode> stmts = new ArrayList<>();
                while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
                    if (match(TokenType.DELIMITER_SEMICOLON)) continue;
                    // 检测关键字开头的语句（auto/var/if/for 等）→ 委托 StmtParser
                    if (isStatementKeywordStart()) {
                        StmtParser stmtParser = new StmtParser(tokens, context, fileName);
                        stmtParser.setPosition(position);
                        stmts.add(stmtParser.parseNextStatement());
                        position = stmtParser.getPosition();
                    } else {
                        stmts.add(parseNextExpression());
                        match(TokenType.DELIMITER_SEMICOLON);
                    }
                }
                consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after lambda body");
                body = new BlockNode.Builder()
                        .statements(stmts)
                        .location(createLocation())
                        .build();
            } else {
                body = parseNextExpression();
            }
        } finally {
            context.exitScope();
        }

        LambdaNode lambda = (LambdaNode) new LambdaNode.Builder()
                .parameters(params)
                .body(body)
                .location(location)
                .build();
        // 标注 Lambda 类型：从 body 推断返回类型（无目标函数接口时用 Object 占位）
        JType bodyType = context.getType(body);
        if (bodyType != null) {
            annotate(lambda, bodyType);
        } else {
            annotate(lambda, Object.class);
        }
        return lambda;
    }

    /** 检查当前 token 是否为需要 StmtParser 处理的语句关键字。 */
    private boolean isStatementKeywordStart() {
        if (check(TokenType.IDENTIFIER) && Keywords.FUNCTION.equals(peek().text())) return true;
        return switch (peek().type()) {
            case KEYWORD_AUTO, KEYWORD_VAR, KEYWORD_IF, KEYWORD_WHILE,
                 KEYWORD_DO, KEYWORD_FOR, KEYWORD_RETURN, KEYWORD_BREAK,
                 KEYWORD_CONTINUE, KEYWORD_THROW, KEYWORD_TRY, KEYWORD_SWITCH,
                 KEYWORD_ASYNC -> true;
            default -> false;
        };
    }

    /**
     * 解析块表达式 {@code { statements }}（用于 lambda 体、async 体等）。
     */
    private BlockNode parseBlockExpression() throws CythavaParseException {
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{'");
        List<ASTNode> statements = new ArrayList<>();

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            statements.add(parseNextExpression());
        }

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after block");
        return (BlockNode) new BlockNode.Builder()
                .statements(statements)
                .location(createLocation())
                .build();
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
    private ASTNode parseNewObject() throws CythavaParseException {
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
            throw error("Anonymous class body '{' requires '()' before it (use 'new " + typeName + "() { ... }')",
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
                consumeGenericClose();
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
    private List<GenericType> parseGenericTypeArgumentList() throws CythavaParseException {
        List<GenericType> args = new ArrayList<>();
        do {
            args.add(parseSingleGenericTypeArg());
        } while (match(TokenType.DELIMITER_COMMA));
        return args;
    }

    /**
     * 消耗泛型闭合括号 {@code >}，支持 {@code >>} 和 {@code >>>} 拆分。
     * <p>
     * Java 语法的遗留问题：词法分析器将 {@code >>} 识别为右移操作符，
     * 但在泛型嵌套场景（如 {@code Map<String, List<Integer>>}）中，
     * 它实际代表两个独立的 {@code >}。此方法处理这种拆分。
     */
    private void consumeGenericClose() throws CythavaParseException {
        if (pendingAngleBrackets > 0) {
            pendingAngleBrackets--;
            return;
        }
        if (match(TokenType.OPERATOR_GREATER_THAN)) {
            return; // 正常的 >
        }
        if (match(TokenType.OPERATOR_RIGHT_SHIFT)) {
            // >> = 两个 >，消费一个，存一个
            pendingAngleBrackets++;
            return;
        }
        if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT)) {
            // >>> = 三个 >，消费一个，存两个
            pendingAngleBrackets += 2;
            return;
        }
        throw error("Expected '>' after generic type arguments", ErrorCode.PARSE_INVALID_TYPE);
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
            consumeGenericClose();
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
     * 解析数组创建 {@code new Type[size]} 或 {@code new Type[]{init}}。
     * <p>
     * 调用时 {@code [} 尚未消费。
     * </p>
     */
    private NewArrayNode parseNewArray(String typeName, SourceLocation location)
            throws CythavaParseException {
        advance(); // 吃掉 [
        if (match(TokenType.DELIMITER_RIGHT_BRACKET)) {
            // new Type[] {init} — 带初始化器（Java 风格花括号）
            ArrayLiteralNode initializer = null;
            if (check(TokenType.DELIMITER_LEFT_BRACE)) {
                initializer = parseBraceArrayInitializer();
            }
            // NewArrayNode 不支持 initializer 参数，暂时用 size=null 表示
            Class<?> elementType1 = context.resolveClass(typeName);
            Class<?> resolvedArrayType1 = elementType1 != null
                    ? Array.newInstance(elementType1, 0).getClass()
                    : Object[].class;
            NewArrayNode node1 = (NewArrayNode) new NewArrayNode.Builder()
                    .elementType(elementType1 != null ? elementType1 : Object.class)
                    .size(initializer)
                    .location(location)
                    .build();
            annotate(node1, resolvedArrayType1);
            return node1;
        }

        // 有尺寸表达式
        ASTNode sizeExpr = parseNextExpression();
        consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' after array dimension");

        // 可选的数组初始化器：new String[2] {"a", "b"}
        ArrayLiteralNode sizeInitializer = null;
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            sizeInitializer = parseBraceArrayInitializer();
        }

        Class<?> elementType2 = context.resolveClass(typeName);
        Class<?> resolvedArrayType2 = elementType2 != null
                ? Array.newInstance(elementType2, 0).getClass()
                : Object[].class;
        NewArrayNode node2 = (NewArrayNode) new NewArrayNode.Builder()
                .elementType(elementType2 != null ? elementType2 : Object.class)
                .size(sizeInitializer != null ? sizeInitializer : sizeExpr)
                .location(location)
                .build();
        annotate(node2, resolvedArrayType2);
        return node2;
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

        // 用最后一个非 null 维度作为 size（或 null 表示有初始化器）
        ASTNode sizeExpr = null;
        for (int i = dimensions.size() - 1; i >= 0; i--) {
            if (dimensions.get(i) != null) {
                sizeExpr = dimensions.get(i);
                break;
            }
        }
        if (sizeExpr == null && initializer != null) {
            sizeExpr = initializer; // 用初始化器表示大小
        }
        if (sizeExpr == null && !dimensions.isEmpty()) {
            sizeExpr = dimensions.get(0); // 可能是 null（全空维度 + 无初始化器）
        }

        NewArrayNode node = (NewArrayNode) new NewArrayNode.Builder()
                .elementType(elementType != null ? elementType : Object.class)
                .size(initializer != null ? initializer : sizeExpr)
                .sizes(dimensions)
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
                    throw error("Expected member declaration (field or method), but found '"
                            + peek().text() + "'",
                        ErrorCode.PARSE_INVALID_SYNTAX);
                }
                // member == null 但 position > posBefore → tryParseAnonymousMember 消费了 token 但决定不生成节点
                // （不应该发生，但安全起见跳过）
            } catch (CythavaParseException e) {
                // 返回值类型不匹配等语义错误应传播出去（不应被匿名类成员循环吞掉）
                if (e.getMessage() != null && (
                        e.getMessage().contains("declares return type")
                        || e.getMessage().contains("declares void"))) {
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

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after anonymous class body");
        return (BlockNode) new BlockNode.Builder().statements(members).location(createLocation()).build();
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

                    body = new com.justnothing.engine.ast.nodes.BlockNode.Builder()
                            .statements(bodyStmts)
                            .location(createLocation())
                            .build();

                    // ★ 返回值类型检查：遍历所有 return 语句，验证返回值与声明返回类型兼容
                    checkReturnTypes(methodName, returnTypeRef, bodyStmts);
                } catch (CythavaParseException e) {
                    // 返回值类型不匹配等语义错误应直接传播
                    String msg = e.getMessage();
                    if (msg != null && (
                            msg.contains("declares return type")
                            || msg.contains("declares void"))) {
                        throw e;
                    }
                    // "Cannot find symbol" 通常意味着参数/字段引用问题 — 这在匿名类方法中
                    // 可能因为字段尚未解析而发生，回退到空体而非中断整个解析
                    int depth = 1;
                    if (msg != null && msg.contains("Cannot find symbol")) {
                        // 回退到括号平衡跳过
                        while (depth > 0 && !isAtEnd()) {
                            Token t = advance();
                            if (t.type() == TokenType.DELIMITER_LEFT_BRACE) depth++;
                            else if (t.type() == TokenType.DELIMITER_RIGHT_BRACE) depth--;
                        }
                    } else {
                        // 其他未知异常：同样回退到括号平衡跳过
                        while (depth > 0 && !isAtEnd()) {
                            Token t = advance();
                            if (t.type() == TokenType.DELIMITER_LEFT_BRACE) depth++;
                            else if (t.type() == TokenType.DELIMITER_RIGHT_BRACE) depth--;
                        }
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
                    body = new com.justnothing.engine.ast.nodes.BlockNode.Builder()
                            .statements(List.of())
                            .location(createLocation())
                            .build();
                }
            } finally {
                context.exitScope();
            }
        }

        return new com.justnothing.engine.ast.nodes.MethodDeclarationNode.Builder()
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

    /** 判断类型是否是内置运算符可能产生的返回类型（用于宽松的返回值检查）。 */
    private static boolean isBuiltinOperatorReturnType(Class<?> type) {
        return type == int.class || type == long.class || type == double.class
                || type == float.class || type == boolean.class
                || type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class;
    }

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
                || type == TokenType.KEYWORD_SYNCHRONIZED;
    }

    /**
     * 将类型名字符串解析为 ClassReferenceNode（含 resolvedClass）。
     * 支持原始类型（int, double 等）和引用类型（String, java.util.List 等）。
     */
    private com.justnothing.engine.ast.nodes.ClassReferenceNode resolveTypeReference(String typeName) {
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
            return new com.justnothing.engine.ast.nodes.ClassReferenceNode.Builder()
                    .originalTypeName(typeName).resolvedClass(primitive)
                    .isPrimitive(true).location(createLocation()).build();
        }

        // 引用类型：通过 context 解析
        Class<?> resolved = context.resolveClass(typeName);
        return new com.justnothing.engine.ast.nodes.ClassReferenceNode.Builder()
                .originalTypeName(typeName).resolvedClass(resolved != null ? resolved : Object.class)
                .location(createLocation()).build();
    }

    /**
     * 解析 switch 表达式。
     * <p>
     * Cythava 的 switch 表达式使用 {@code ->} 作为 case 分隔符（而非 Java 的 {@code :}）：
     * 
     * <pre>
     * switch (x) {
     *     case 1 -> "one";
     *     case 2 -> "two";
     *     default -> "other";
     * }
     * </pre>
     * </p>
     */
    private SwitchNode parseSwitchExpression() throws CythavaParseException {
        SourceLocation location = createLocation();
        consume(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'switch'");
        ASTNode condition = parseNextExpression();
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after switch condition");
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' for switch body");

        List<CaseNode> cases = new ArrayList<>();
        ASTNode defaultCase = null;

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            if (match(TokenType.KEYWORD_DEFAULT)) {
                // 支持 ->（Cythava）和 :（Java 兼容）两种分隔符
                if (match(TokenType.DELIMITER_ARROW)) {
                    ASTNode defaultBody = parseNextExpression();
                    consumeOptionalSemicolon();
                    defaultCase = defaultBody;
                } else if (match(TokenType.OPERATOR_COLON)) {
                    // Java 风格：收集到下一个 case/default/} 为止
                    List<ASTNode> stmts = new ArrayList<>();
                    while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()
                            && !check(TokenType.KEYWORD_CASE) && !check(TokenType.KEYWORD_DEFAULT)) {
                        stmts.add(parseNextExpression());
                        consumeOptionalSemicolon();
                    }
                    defaultCase = stmts.size() == 1 ? stmts.get(0)
                            : new BlockNode.Builder().statements(stmts).location(createLocation()).build();
                } else {
                    throw error("Expected '->' or ':' after default", ErrorCode.PARSE_INVALID_SYNTAX);
                }
            } else if (match(TokenType.KEYWORD_CASE)) {
                List<ASTNode> caseValues = new ArrayList<>();
                caseValues.add(parseNextExpression());
                while (match(TokenType.OPERATOR_COLON)) {
                    // case 1: 2: 3: — 多值 case（Java 14+ 风格）
                    // 歧义消除：如果下一个 token 看起来像常量值则继续收集，否则视为 : 分隔符
                    if (!isCaseValueStart()) {
                        break; // : 是 body 分隔符，不是多值分隔符（当前 token 已是 :）
                    }
                    caseValues.add(parseNextExpression());
                }
                // 支持 ->（Cythava）和 :（Java 兼容）两种 case body 分隔符
                ASTNode caseBody;
                if (match(TokenType.DELIMITER_ARROW)) {
                    // Cythava 箭头风格
                    caseBody = parseNextExpression();
                    consumeOptionalSemicolon();
                } else if (match(TokenType.OPERATOR_COLON) || isOnColon()) {
                    // Java 冒号风格：上面的 break 停在 : 处，或显式匹配到 :
                    List<ASTNode> stmts = new ArrayList<>();
                    while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()
                            && !check(TokenType.KEYWORD_CASE) && !check(TokenType.KEYWORD_DEFAULT)) {
                        stmts.add(parseNextExpression());
                        consumeOptionalSemicolon();
                    }
                    caseBody = stmts.size() == 1 ? stmts.get(0)
                            : new BlockNode.Builder().statements(stmts).location(createLocation()).build();
                } else {
                    throw error("Expected '->' or ':' after case values", ErrorCode.PARSE_INVALID_SYNTAX);
                }
                // 多值 case：为每个值创建独立的 CaseNode
                for (ASTNode caseValue : caseValues) {
                    cases.add((CaseNode) new CaseNode.Builder().value(caseValue).statements(List.of(caseBody))
                            .location(createLocation()).build());
                }
            } else {
                // 跳过未知 token
                advance();
            }
        }

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after switch body");
        SwitchNode node = (SwitchNode) new SwitchNode.Builder().expression(condition).cases(cases)
                .defaultCase(defaultCase).location(location).build();
        // 计算 switch 表达式类型：各 case 分支和 default 的 LCM（最小公共超类型）
        Class<?> lcm = computeSwitchLCM(cases, defaultCase);
        annotate(node, lcm);
        return node;
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

        Method[] allMethods = targetClass.getMethods();
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
     * 计算三元表达式的 LCM（最小公共超类型）。
     * <p>
     * 规则与 {@link #computeSwitchLCM} 一致：
     * <ul>
     * <li>两分支类型相同 → 该类型</li>
     * <li>数值类型提升：byte/short → int → long → float → double</li>
     * <li>混合类型 → Object.class</li>
     * </ul>
     */
    private Class<?> computeTernaryLCM(ASTNode thenExpr, ASTNode elseExpr) {
        Class<?> thenType = resolveNodeType(thenExpr);
        Class<?> elseType = resolveNodeType(elseExpr);
        if (thenType == null && elseType == null)
            return Object.class;
        if (thenType == null || elseType == null)
            return thenType != null ? thenType : elseType;
        if (thenType.equals(elseType))
            return thenType;

        // 数值类型提升链
        Class<?>[] NUMERIC_CHAIN = { Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE };
        java.util.function.Function<Class<?>, Integer> rank = t -> {
            for (int i = 0; i < NUMERIC_CHAIN.length; i++) {
                if (NUMERIC_CHAIN[i].equals(t))
                    return i;
            }
            return -1;
        };
        int rThen = rank.apply(thenType);
        int rElse = rank.apply(elseType);
        if (rThen >= 0 && rElse >= 0) {
            return rThen >= rElse ? thenType : elseType;
        }

        return Object.class;
    }

    /**
     * 计算 switch 表达式的 LCM（最小公共超类型）。
     * <p>
     * 规则：
     * <ul>
     * <li>所有分支类型相同 → 该类型</li>
     * <li>数值类型提升：byte/short → int → long → float → double</li>
     * <li>混合类型 → Object.class</li>
     * </ul>
     */
    private Class<?> computeSwitchLCM(List<CaseNode> cases, ASTNode defaultCase) {
        List<Class<?>> types = new ArrayList<>();
        for (CaseNode c : cases) {
            ASTNode caseBody = c.getStatements().get(0);
            Class<?> rawType = resolveNodeType(caseBody);
            if (rawType != null)
                types.add(rawType);
        }
        if (defaultCase != null) {
            Class<?> dt = resolveNodeType(defaultCase);
            if (dt != null)
                types.add(dt);
        }
        if (types.isEmpty())
            return void.class;

        // 所有类型相同 → 直接返回
        Class<?> first = types.get(0);
        boolean allSame = true;
        for (Class<?> t : types) {
            if (!t.equals(first)) {
                allSame = false;
                break;
            }
        }
        if (allSame)
            return first;

        // 数值类型提升链（索引越大 = 类型越宽）
        Class<?>[] NUMERIC_PROMOTION_CHAIN = {
                Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE
        };
        // 辅助：获取类型在提升链中的位置（不在链中返回 -1）
        Function<Class<?>, Integer> numericRank = t -> {
            for (int i = 0; i < NUMERIC_PROMOTION_CHAIN.length; i++) {
                if (NUMERIC_PROMOTION_CHAIN[i].equals(t))
                    return i;
            }
            return -1;
        };

        boolean allNumeric = true;
        for (Class<?> t : types) {
            if (numericRank.apply(t) < 0) {
                allNumeric = false;
                break;
            }
        }
        if (allNumeric) {
            // 找最大 rank → 最宽类型
            int maxRank = -1;
            Class<?> widest = Integer.TYPE;
            for (Class<?> t : types) {
                int r = numericRank.apply(t);
                if (r > maxRank) {
                    maxRank = r;
                    widest = t;
                }
            }
            return widest;
        } else {
            // 混合类型 → Object
            return Object.class;
        }

    }

    /**
     * 收集逗号分隔的元素列表。
     *
     * @param endToken      结束标记（不消费）
     * @param elementParser 元素解析函数
     * @return 元素列表
     */
    private List<ASTNode> collectCommaSeparated(TokenType endToken, ElementParser elementParser)
            throws CythavaParseException {
        List<ASTNode> elements = new ArrayList<>();

        if (check(endToken)) {
            return elements;
        }

        // 跳过可能的起始逗号
        if (check(TokenType.DELIMITER_COMMA)) {
            advance();
        }

        do {
            elements.add(elementParser.parse());
        } while (match(TokenType.DELIMITER_COMMA) && !check(endToken));

        return elements;
    }

    /**
     * 可选地消费分号（用于 switch case 等场景）。
     */
    private void consumeOptionalSemicolon() {
        match(TokenType.DELIMITER_SEMICOLON);
    }

    /**
     * 判断当前 token 是否可能是 case 值（常量/字面量）。
     * 用于 switch 多值 case 的歧义消除：{@code case 1:} 中 : 后面是 body 分隔符，
     * 而 {@code case 1: 2: 3:} 中 : 是多值分隔符。
     */
    private boolean isCaseValueStart() {
        if (isAtEnd())
            return false;
        TokenType t = peek().type();
        // 字面量、标识符（枚举常量）、一元运算符（-1, !flag）都可能是值
        return t == TokenType.LITERAL_INTEGER || t == TokenType.LITERAL_STRING
                || t == TokenType.LITERAL_MULTI_LINE_STRING
                || t == TokenType.LITERAL_CHAR || t == TokenType.LITERAL_DECIMAL
                || t == TokenType.KEYWORD_TRUE || t == TokenType.KEYWORD_FALSE
                || t == TokenType.KEYWORD_NULL
                || t == TokenType.IDENTIFIER
                || t == TokenType.OPERATOR_MINUS || t == TokenType.OPERATOR_BITWISE_NOT
                || t == TokenType.OPERATOR_LOGICAL_NOT;
    }

    /**
     * 判断当前是否停在未消费的 {@code :} token 上（用于 Java 风格 switch body）。
     */
    private boolean isOnColon() {
        return !isAtEnd() && peek().type() == TokenType.OPERATOR_COLON;
    }

    /**
     * 从父类/接口层次结构中查找字段声明。
     *
     * @param startClass 起始类
     * @param fieldName  字段名
     * @return 找到的 Field，未找到返回 null
     */
    private java.lang.reflect.Field findFieldInHierarchy(Class<?> startClass, String fieldName) {
        // 1. 先查 startClass 自身（含 public/protected/package/private + 静态字段）
        try {
            return startClass.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
        }

        // 2. 向上遍历继承链（使用 getField 查找公共字段，含静态）
        Class<?> current = startClass;
        while (current != null && current != Object.class) {
            try {
                return current.getField(fieldName); // 含继承的公共字段
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }

        // 3. 遍历接口
        for (Class<?> _interface : startClass.getInterfaces()) {
            try {
                return _interface.getField(fieldName);
            } catch (NoSuchFieldException ignored) {
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

    /**
     * 元素解析函数式接口（用于 collectCommaSeparated）。
     */
    @FunctionalInterface
    private interface ElementParser {
        ASTNode parse() throws CythavaParseException;
    }
}
