package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.parser.constant.ConstantFolder;

import java.util.ArrayList;
import java.util.List;
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
 * </pre>
 *
 * @see BaseParser
 * @see ConstantFolder
 */
public class ExprParser extends PostfixExpressionParser {

    /**
     * 构造器。
     *
     * @param tokens   完整的 token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public ExprParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
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
    @Override
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
            String name = variableRefName(left);
            if (name != null) {
                ConditionalAssignNode node = (ConditionalAssignNode) new ConditionalAssignNode.Builder()
                        .variableName(name)
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
            String name = variableRefName(left);
            if (name != null) {
                NullCoalescingAssignNode node =
                        (NullCoalescingAssignNode) new NullCoalescingAssignNode.Builder()
                        .variableName(name)
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
     * 左值上的变量名。
     * <p>
     * {@link VariableNode} 与 {@link NameRefNode} 都要算：前者是解析期已确认的变量，
     * 后者是解析期判不出身份、留到 link 层再判的名字。两者都能出现在 {@code =} 左边。
     * </p>
     *
     * @return 变量名；该节点不是变量引用时返回 null
     */
    private static String variableRefName(ASTNode node) {
        if (node instanceof VariableNode v) return v.getName();
        if (node instanceof NameRefNode n) return n.getName();
        return null;
    }

    /**
     * 根据左值类型构建对应的赋值节点。
     */
    private ASTNode buildAssignment(ASTNode left, ASTNode right) throws CythavaParseException {
        JType rightType = context.getType(right);
        String variableName = variableRefName(left);
        if (variableName != null) {
            AssignmentNode node =
                    (AssignmentNode) new AssignmentNode.Builder()
                        .variableName(variableName)
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
                String patternVariable = parsePatternVariableName();
                if (patternVariable != null) {
                    // ★ 立刻在当前位置声明，使同一条件下的后续表达式（如 && 右侧）也能解析到它；
                    //   语句解析器负责在 if/while 语句结束后撤销该声明
                    Class<?> patternType = resolveTypeName(typeName);
                    context.declareVariable(patternVariable,
                            patternType != null ? patternType : Object.class);
                }
                left = new InstanceofNode.Builder()
                        .expression(left)
                        .typeName(typeName)
                        .patternVariable(patternVariable)
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

    /**
     * 解析 {@code instanceof} 之后的模式变量名（{@code o instanceof String s} 中的 {@code s}）。
     * <p>
     * 为避免误吞语句边界后的标识符（如 {@code var q = x instanceof Foo} 换行后紧跟下一条语句），
     * 仅当模式变量后面的 token 是 {@code )}、{@code &&} 或 {@code ||} 时才认定为模式绑定——
     * 这三种正是 Java 模式变量唯一有意义的出现位置。
     * </p>
     *
     * @return 模式变量名；不存在模式绑定时返回 null
     */
    private String parsePatternVariableName() {
        if (!check(TokenType.IDENTIFIER)) {
            return null;
        }
        TokenType after = peekNextType();
        if (after != TokenType.DELIMITER_RIGHT_PAREN
                && after != TokenType.OPERATOR_LOGICAL_AND
                && after != TokenType.OPERATOR_LOGICAL_OR) {
            return null;
        }
        return advance().text();
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

            // 便宜的前置判断（避免把变量名当类名去逐 import 前缀探测）：
            // 1) 括号内以已知变量开头 → 这不可能是类型名（是括号表达式）；
            // 2) 匹配 ')' 之后紧跟 '->' → 这是 lambda 参数列表，不是强制转换。
            // 两条判断都只是提前退出，判错也不会改变语义（后续 TypeParser 本来也会失败回退）。
            if (check(TokenType.IDENTIFIER) && context.isKnownVariable(peek().text())) {
                restorePosition();
                return null;
            }
            if (isLambdaParamListAhead()) {
                restorePosition();
                return null;
            }
            // 类型名后不可能紧跟 ','（括号内不可能出现裸逗号）
            if (check(TokenType.IDENTIFIER) && checkNext(TokenType.DELIMITER_COMMA)) {
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

            // 强制转换表达式后面不可能是 '->'（那是 lambda 的箭头）
            if (check(TokenType.DELIMITER_ARROW)) {
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

    /**
     * 判断当前位置（已消费 {@code (}）到匹配的 {@code )} 之后是否紧跟 {@code ->}，
     * 即是否为 lambda 参数列表。用于强制转换的便宜前置判断。
     */
    private boolean isLambdaParamListAhead() {
        int depth = 1;
        int limit = Math.min(tokens.size(), position + 256);
        for (int i = position; i < limit; i++) {
            TokenType t = tokens.get(i).type();
            if (t == TokenType.DELIMITER_LEFT_PAREN) {
                depth++;
            } else if (t == TokenType.DELIMITER_RIGHT_PAREN) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == TokenType.DELIMITER_ARROW;
                }
            } else if (t == TokenType.DELIMITER_SEMICOLON || t == TokenType.DELIMITER_LEFT_BRACE) {
                // 语句/块边界：括号内不是 lambda 参数列表
                return false;
            }
        }
        return false;
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
                        stmts.add(parseSwitchBodyStatement());
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
                // 多值 case（Java 14+ 逗号分隔）: case 1, 2, 3 -> ...
                // 与 StmtParser.parseCaseClause 保持一致
                while (match(TokenType.DELIMITER_COMMA)) {
                    caseValues.add(parseNextExpression());
                }
                while (check(TokenType.OPERATOR_COLON)) {
                    // case 1: 2: 3: — 多值 case（Java 14+ 风格）
                    // 歧义消除：只有 : 之后看起来像 case 值才是多值分隔符，否则 : 是 body 分隔符。
                    // 这里必须"先判断后消费"，否则 case 1: yield x; 的 : 会被提前吃掉。
                    if (!isCaseValueStartAt(position + 1)) {
                        break; // 停在 : 上，交给下面按 body 分隔符处理
                    }
                    advance(); // 消费多值分隔符 :
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
                        stmts.add(parseSwitchBodyStatement());
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
        Function<Class<?>, Integer> rank = t -> {
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
            if (c.getStatements().isEmpty()) continue; // 空 case 体无类型可推断
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
     * 判断 {@code pos} 处的 token 是否可能是 case 值（常量/字面量）。
     * 用于 switch 多值 case 的歧义消除：{@code case 1:} 中 : 后面是 body 分隔符，
     * 而 {@code case 1: 2: 3:} 中 : 是多值分隔符。
     */
    private boolean isCaseValueStartAt(int pos) {
        if (pos >= tokens.size())
            return false;
        // yield 是分支体的开始（yield expr;），不是 case 值：否则 case 1: yield x; 会被当成多值 case
        if (StmtParser.isYieldStatementStart(tokens, pos)) {
            return false;
        }
        TokenType t = tokens.get(pos).type();
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
     * switch 体（冒号风格）里的一条语句：{@code yield expr} 或普通表达式。
     * <p>
     * switch 表达式的 value 由 {@code yield} 显式给出；这里让冒号风格的分支体也能解析 yield，
     * 而不是把它当成名为 yield 的标识符表达式。
     * </p>
     */
    private ASTNode parseSwitchBodyStatement() throws CythavaParseException {
        if (StmtParser.isYieldStatementStart(tokens, position)) {
            SourceLocation location = createLocation();
            advance(); // yield
            ASTNode value = parseNextExpression();
            return new YieldNode.Builder().value(value).location(location).build();
        }
        return parseNextExpression();
    }
}
