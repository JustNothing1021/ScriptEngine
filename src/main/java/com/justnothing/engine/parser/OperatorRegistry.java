package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.OperatorCallback;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.eval.Value;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Operators;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * 统一运算符注册表——内置运算符和用户自定义运算符的唯一真相源。
 * <p>
 * 两种实现模式：
 * <ul>
 *   <li><b>内置运算符</b>（Java 回调）：{@code registerBuiltinBinary("+", Number.class, Number.class, (l,r) -> ...)}
 *   <li><b>用户自定义</b>（AST 节点）：{@code registerBinary("+", Vec2.class, Vec2.class, ..., astNode)}</li>
 * </ul>
 * <p>
 * 解析期通过此表做符号校验 + 预写 callback 到 BinaryOpNode；
 * 运行期直接调用已缓存的 callback，零查找开销。
 */
public final class OperatorRegistry {

    /** 支持的二元运算符集合。 */
    public static final Set<String> BINARY_OPERATORS = Operators.BINARY_OPERATORS;

    /** 支持的一元运算符集合。 */
    public static final Set<String> UNARY_OPERATORS = Operators.UNARY_OPERATORS;

    /**
     * 运算符重载条目。支持两种实现模式：
     * <ul>
     *   <li><b>AST 实现</b>（用户定义的 operator+ 函数）：{@code implementation != null}</li>
     *   <li><b>Java 回调实现</b>（内置运算符）：{@code javaCallback != null}</li>
     * </ul>
     */
    public record Overload(
            String operator,
            List<Class<?>> parameterTypes,
            ASTNode implementation,
            Class<?> returnType,
            BiFunction<Value, Value, Value> javaCallback
    ) {
        /** 是否为内置 Java 回调实现。 */
        public boolean isBuiltin() {
            return javaCallback != null;
        }

        /** 是否为用户 AST 实现。 */
        public boolean isUserDefined() {
            return implementation != null;
        }

        /** 转换为 OperatorCallback（供 BinaryOpNode 缓存使用）。 */
        public OperatorCallback toOperatorCallback(EvaluatorBridge bridge) {
            if (javaCallback != null) {
                // 内置运算符：直接用 Java 回调
                return javaCallback::apply;
            }
            if (implementation != null) {
                // 用户自定义：通过 Evaluator 执行 AST 节点
                return (left, right) -> bridge.invokeUserOverload(this, left, right);
            }
            throw new IllegalStateException("Overload has no implementation: " + operator);
        }
    }

    /** 桥接接口：用户自定义重载需要 Evaluator 来执行 AST 节点。 */
    @FunctionalInterface
    public interface EvaluatorBridge {
        Value invokeUserOverload(Overload overload, Value left, Value right);
    }

    // ==================== 存储 ====================

    /** 二元运算符映射：(operator, lhsType, rhsType) → Overload */
    private final Map<BinaryOpKey, Overload> binaryOps = new ConcurrentHashMap<>();

    /** 一元运算符映射：(operator, operandType) → Overload */
    private final Map<UnaryOpKey, Overload> unaryOps = new ConcurrentHashMap<>();

    /** 所有已注册的重载（按注册顺序） */
    private final List<Overload> allOverloads = Collections.synchronizedList(new ArrayList<>());

    // ==================== 用户自定义注册（AST 节点）====================

    /**
     * 注册一个用户定义的二元运算符重载（AST 实现）。
     *
     * @param operator       运算符符号
     * @param lhsType        左操作数类型
     * @param rhsType        右操作数类型
     * @param returnType     返回值类型
     * @param implementation 实现节点（MethodDeclarationNode 或 FunctionDefNode）
     */
    public void registerBinary(String operator, Class<?> lhsType, Class<?> rhsType,
                               Class<?> returnType, ASTNode implementation) {
        validateOperator(operator, false);
        Overload overload = new Overload(operator,
                List.of(lhsType, rhsType), implementation, returnType, null);
        doRegisterBinary(operator, lhsType, rhsType, overload);
    }

    /**
     * 注册一个用户定义的一元运算符重载（AST 实现）。
     */
    public void registerUnary(String operator, Class<?> operandType,
                              Class<?> returnType, ASTNode implementation) {
        validateOperator(operator, true);
        Overload overload = new Overload(operator,
                List.of(operandType), implementation, returnType, null);
        doRegisterUnary(operator, operandType, overload);
    }

    // ==================== 内置运算符注册（Java 回调）====================

    /**
     * 注册一个内置二元运算符（Java 回调实现）。
     * <p>用于预注册 int+int、String+String 等原生语义。
     *
     * @param operator       运算符符号
     * @param lhsType        左操作数声明类型
     * @param rhsType        右操作数声明类型
     * @param returnType     返回值类型
     * @param callback       运算逻辑回调
     */
    public void registerBuiltinBinary(String operator, Class<?> lhsType, Class<?> rhsType,
                                      Class<?> returnType, BinaryOperator<Value> callback) {
        validateOperator(operator, false);
        Overload overload = new Overload(operator,
                List.of(lhsType, rhsType), null, returnType, callback);
        doRegisterBinary(operator, lhsType, rhsType, overload);
    }

    /**
     * 注册一个内置一元运算符（Java 回调实现）。
     *
     * @param operator       运算符符号
     * @param operandType    操作数声明类型
     * @param returnType     返回值类型
     * @param callback       运算逻辑回调
     */
    public void registerBuiltinUnary(String operator, Class<?> operandType,
                                     Class<?> returnType, UnaryOperator<Value> callback) {
        validateOperator(operator, true);
        BinaryOperator<Value> bifunc = (v, ignore) -> callback.apply(v);
        Overload overload = new Overload(operator,
                List.of(operandType), null, returnType, bifunc);
        doRegisterUnary(operator, operandType, overload);
    }

    private void doRegisterBinary(String op, Class<?> lhs, Class<?> rhs, Overload overload) {
        binaryOps.put(new BinaryOpKey(op, lhs, rhs), overload);
        allOverloads.add(overload);
    }

    private void doRegisterUnary(String op, Class<?> operand, Overload overload) {
        unaryOps.put(new UnaryOpKey(op, operand), overload);
        allOverloads.add(overload);
    }

    // ==================== 查找 ====================

    public Overload findBinary(String operator, Class<?> lhsType, Class<?> rhsType) {
        return binaryOps.get(new BinaryOpKey(operator, lhsType, rhsType));
    }

    public Overload findUnary(String operator, Class<?> operandType) {
        return unaryOps.get(new UnaryOpKey(operator, operandType));
    }

    public Overload findBinaryCompatible(String operator, Class<?> lhsType, Class<?> rhsType) {
        Overload exact = findBinary(operator, lhsType, rhsType);
        if (exact != null) return exact;

        Overload best = null;
        double bestScore = -1;
        for (Map.Entry<BinaryOpKey, Overload> entry : binaryOps.entrySet()) {
            BinaryOpKey key = entry.getKey();
            if (!key.operator().equals(operator)) continue;

            double score = compatibilityScore(key.lhsType(), lhsType)
                    + compatibilityScore(key.rhsType(), rhsType);
            // 数值运算符优先级加权：避免 (String,Object) 抢先于 (Number,Number)
            if (Number.class.isAssignableFrom(key.lhsType())
                    && Number.class.isAssignableFrom(key.rhsType())) {
                score += 0.5;  // 数值运算符微优先
            }
            if (score > bestScore && score > 0) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        return best;
    }

    public Overload findUnaryCompatible(String operator, Class<?> operandType) {
        Overload exact = findUnary(operator, operandType);
        if (exact != null) return exact;

        Overload best = null;
        int bestScore = -1;
        for (Map.Entry<UnaryOpKey, Overload> entry : unaryOps.entrySet()) {
            UnaryOpKey key = entry.getKey();
            if (!key.operator().equals(operator)) continue;

            int score = compatibilityScore(key.operandType(), operandType);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        return best;
    }

    // ==================== 查询 ====================

    public boolean isEmpty() {
        return binaryOps.isEmpty() && unaryOps.isEmpty();
    }

    public List<Overload> getAllOverloads() {
        return Collections.unmodifiableList(new ArrayList<>(allOverloads));
    }

    /** 是否包含任何内置运算符注册（用于判断是否启用解析期严格校验）。 */
    public boolean hasBuiltins() {
        return allOverloads.stream().anyMatch(Overload::isBuiltin);
    }

    public void clear() {
        binaryOps.clear();
        unaryOps.clear();
        allOverloads.clear();
    }

    /**
     * 预注册所有内置运算符。
     * <p>由 ScriptRunner 在构造时调用一次，使 Registry 成为运算符的唯一真相源。
     *
     * <h3>设计原则</h3>
     * <ul>
     *   <li>只注册包装类型/父类型（如 {@code Number}, {@code Integer}, {@code Object}）</li>
     *   <li>基本类型（{@code int}, {@code long} 等）通过 {@link #boxPrimitive(Class)} ()} 自动拆箱匹配</li>
     *   <li>所有算术回调使用"智能类型提升"（Double > Long > Int），与 Evaluator 行为一致</li>
     * </ul>
     */
    public void registerAllBuiltins() {
        // ===== 算术运算符 =====
        // + : 字符串拼接优先，否则数值加法（智能提升）
        registerBuiltinBinary(Operators.ADD, String.class, Object.class, String.class,
                (l, r) -> new Value.StringValue(str(l) + str(r)));
        registerBuiltinBinary(Operators.ADD, String.class, String.class, Object.class,
                (l, r) -> new Value.StringValue(str(l) + str(r)));
        registerBuiltinBinary(Operators.ADD, Number.class, Number.class, Object.class, OperatorRegistry::numericAdd);

        // -, *, /, % : 统一用智能数值运算（Double > Long > Int）
        registerBuiltinBinary(Operators.SUBTRACT, Number.class, Number.class, Object.class,
                (l, r) -> smartNumericOp(l, r,
                        (a, b) -> new Value.DoubleValue(a - b),
                        (a, b) -> new Value.LongValue(a - b),
                        (a, b) -> new Value.IntValue(a - b)));
        registerBuiltinBinary(Operators.MULTIPLY, Number.class, Number.class, Object.class,
                (l, r) -> smartNumericOp(l, r,
                        (a, b) -> new Value.DoubleValue(a * b),
                        (a, b) -> new Value.LongValue(a * b),
                        (a, b) -> new Value.IntValue(a * b)));
        registerBuiltinBinary(Operators.DIVIDE, Number.class, Number.class, Object.class,
                (l, r) -> smartNumericOp(l, r,
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.DoubleValue(a / b); },
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.LongValue(a / b); },
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.IntValue(a / b); }));
        registerBuiltinBinary(Operators.MODULO, Number.class, Number.class, Object.class,
                (l, r) -> smartNumericOp(l, r,
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.DoubleValue(a % b); },
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.LongValue(a % b); },
                        (a, b) -> { if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.IntValue(a % b); }));

        // ** (幂运算): 始终返回 double（Math.pow 的自然行为）
        registerBuiltinBinary(Operators.POWER, Number.class, Number.class, Double.class,
                (l, r) -> new Value.DoubleValue(Math.pow(l.asDouble(), r.asDouble())));

        // 整数专用运算符
        registerBuiltinBinary(Operators.INT_DIVIDE, Integer.class, Integer.class, Long.class,
                (l, r) -> { int b = r.asInt(); if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.LongValue((long) l.asInt() / b); });
        registerBuiltinBinary(Operators.MATH_MODULO, Integer.class, Integer.class, Long.class,
                (l, r) -> { int b = r.asInt(); if (b == 0) throw new EvalException("Division by zero", ErrorCode.EVAL_DIVISION_BY_ZERO); return new Value.LongValue(Math.floorMod(l.asInt(), b)); });

        // ===== 数组集合运算（注册为 Object[] 通配，compatibilityScore 自动匹配任意数组）=====
        // + : 数组拼接 (concat)
        registerBuiltinBinary(Operators.ADD, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arrayConcat);
        // - : 数组差集 (difference)
        registerBuiltinBinary(Operators.SUBTRACT, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arrayDifference);
        // & : 数组交集 (intersection)
        registerBuiltinBinary(Operators.BITWISE_AND, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arrayIntersection);
        // | : 数组并集 (union)
        registerBuiltinBinary(Operators.BITWISE_OR, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arrayUnion);
        // ^ : 数组对称差集 (symmetric difference)
        registerBuiltinBinary(Operators.BITWISE_XOR, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arraySymmetricDifference);
        // ** : 数组笛卡尔积 (cartesian product)
        registerBuiltinBinary(Operators.POWER, Object[].class, Object[].class, Object.class,
                OperatorRegistry::arrayCartesianProduct);

        // ===== 比较运算符 =====
        registerBuiltinBinary(Operators.EQUAL, Object.class, Object.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(l.equals(r)));
        registerBuiltinBinary(Operators.NOT_EQUAL, Object.class, Object.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(!l.equals(r)));
        registerBuiltinBinary(Operators.LESS_THAN, Number.class, Number.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(compareValues(l, r) < 0));
        registerBuiltinBinary(Operators.LESS_THAN_OR_EQUAL, Number.class, Number.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(compareValues(l, r) <= 0));
        registerBuiltinBinary(Operators.GREATER_THAN, Number.class, Number.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(compareValues(l, r) > 0));
        registerBuiltinBinary(Operators.GREATER_THAN_OR_EQUAL, Number.class, Number.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(compareValues(l, r) >= 0));
        registerBuiltinBinary(Operators.SPACESHIP, Object.class, Object.class, Integer.class,
                (l, r) -> new Value.IntValue(compareValues(l, r)));

        // ===== 逻辑运算符 =====
        registerBuiltinBinary(Operators.LOGICAL_AND, Object.class, Object.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(l.isTruthy() && r.isTruthy()));
        registerBuiltinBinary(Operators.LOGICAL_OR, Object.class, Object.class, Boolean.class,
                (l, r) -> new Value.BooleanValue(l.isTruthy() || r.isTruthy()));

        // ===== 位运算符 (int, int) → int =====
        // 注意：注册 int.class 而非 Integer.class，这样基本类型可以直接精确匹配
        registerBuiltinBinary(Operators.BITWISE_AND, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() & r.asInt()));
        registerBuiltinBinary(Operators.BITWISE_OR, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() | r.asInt()));
        registerBuiltinBinary(Operators.BITWISE_XOR, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() ^ r.asInt()));
        registerBuiltinBinary(Operators.LEFT_SHIFT, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() << r.asInt()));
        registerBuiltinBinary(Operators.RIGHT_SHIFT, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() >> r.asInt()));
        registerBuiltinBinary(Operators.UNSIGNED_RIGHT_SHIFT, int.class, int.class, int.class,
                (l, r) -> new Value.IntValue(l.asInt() >>> r.asInt()));

        // ===== 范围运算符 =====
        registerBuiltinBinary(Operators.RANGE, int.class, int.class, Object.class,
                (l, r) -> makeRange(l, r, true));
        registerBuiltinBinary(Operators.RANGE_EXCLUSIVE, int.class, int.class, Object.class,
                (l, r) -> makeRange(l, r, false));

        // ===== 一元运算符 =====
        // - (负号): 智能类型保持
        registerBuiltinUnary(Operators.SUBTRACT, Number.class, Object.class,
                v -> {
                    if (v instanceof Value.IntValue) return new Value.IntValue(-v.asInt());
                    if (v instanceof Value.LongValue) return new Value.LongValue(-v.asLong());
                    return new Value.DoubleValue(-v.asDouble());
                });
        registerBuiltinUnary(Operators.NOT, Object.class, Boolean.class,
                v -> new Value.BooleanValue(!v.isTruthy()));
        registerBuiltinUnary(Operators.BITWISE_NOT, int.class, int.class,
                v -> new Value.IntValue(~v.asInt()));
    }

    // ==================== 智能运算辅助方法 ====================

    /**
     * 智能数值二元运算：根据操作数实际类型选择 Double > Long > Int 分支。
     * <p>与 Evaluator.binaryNumericOp() 行为完全一致。 */
    @FunctionalInterface
    private interface DoubleBiOp { Value apply(double a, double b); }
    @FunctionalInterface
    private interface LongBiOp { Value apply(long a, long b); }
    @FunctionalInterface
    private interface IntBiOp { Value apply(int a, int b); }

    /** null-safe 的 Value → String 转换 */
    private static String str(Value v) {
        Object o = v.asJavaObject();
        return o != null ? o.toString() : "null";
    }

    private static Value smartNumericOp(Value l, Value r,
                                       DoubleBiOp doubleOp, LongBiOp longOp, IntBiOp intOp) {
        if (l instanceof Value.DoubleValue || r instanceof Value.DoubleValue)
            return doubleOp.apply(l.asDouble(), r.asDouble());
        if (l instanceof Value.LongValue || r instanceof Value.LongValue)
            return longOp.apply(l.asLong(), r.asLong());
        return intOp.apply(l.asInt(), r.asInt());
    }

    // ==================== 内置运算辅助方法 ====================

    private static Value numericAdd(Value l, Value r) {
        if (l instanceof Value.StringValue || r instanceof Value.StringValue) {
            return new Value.StringValue(l.asString() + r.asString());
        }
        if (l instanceof Value.DoubleValue || r instanceof Value.DoubleValue) {
            return new Value.DoubleValue(l.asDouble() + r.asDouble());
        }
        if (l instanceof Value.LongValue || r instanceof Value.LongValue) {
            return new Value.LongValue(l.asLong() + r.asLong());
        }
        return new Value.IntValue(l.asInt() + r.asInt());
    }

    private static int compareValues(Value a, Value b) {
        if (a instanceof Value.DoubleValue || b instanceof Value.DoubleValue) {
            return Double.compare(a.asDouble(), b.asDouble());
        }
        if (a instanceof Value.IntValue && b instanceof Value.IntValue) {
            return Integer.compare(a.asInt(), b.asInt());
        }
        return Long.compare(a.asLong(), b.asLong());
    }

    private static Value makeRange(Value left, Value right, boolean inclusive) {
        int start = left.asInt();
        int end = right.asInt();
        java.util.List<Integer> list = new java.util.ArrayList<>();
        int limit = inclusive ? end + 1 : end;
        for (int i = start; i < limit; i++) list.add(i);
        return Value.of(list);
    }

    // ==================== 内部工具 ====================

    private void validateOperator(String operator, boolean isUnary) {
        Set<String> allowed = isUnary ? UNARY_OPERATORS : BINARY_OPERATORS;
        if (!allowed.contains(operator)) {
            throw new IllegalArgumentException(
                    "Unsupported operator '" + operator + "' for "
                            + (isUnary ? "unary" : "binary") + " operator overload");
        }
    }

    /**
     * 将基本类型映射为其对应的包装类型（用于运算符匹配时的自动拆箱）。
     * <p>
     * Java 的 {@code isAssignableFrom()} 不支持基本类型到包装类型的隐式转换，
     * 所以需要手动处理：{@code int.class} → {@code Integer.class}，以此类推。
     *
     * @param type 可能是基本类型或包装类型
     * @return 如果输入是基本类型则返回对应包装类型，否则原样返回
     */
    static Class<?> boxPrimitive(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == boolean.class) return Boolean.class;
        return type; // void 等其他基本类型原样返回
    }

    /**
     * 寻找两个类的最小公共父类（Least Upper Bound）。
     * <p>沿父类链向上爬，找到第一个同时是 {@code b} 的父类的类。</p>
     */
    static Class<?> leastCommonSupertype(Class<?> a, Class<?> b) {
        if (a == null || b == null) return Object.class;
        if (a == b || a.isAssignableFrom(b)) return a;
        if (b.isAssignableFrom(a)) return b;
        if (a.isInterface() || b.isInterface()) return Object.class;
        Set<Class<?>> ancestors = new HashSet<>();
        Class<?> cursor = a;
        while (cursor != null) {
            ancestors.add(cursor);
            cursor = cursor.getSuperclass();
        }
        cursor = b;
        while (cursor != null) {
            if (ancestors.contains(cursor)) return cursor;
            cursor = cursor.getSuperclass();
        }
        return Object.class;
    }

    /**
     * 兼容性评分：精确匹配=2，包装类型匹配=1.5，父类匹配=1，不兼容=0。
     * <p>
     * 相比原始版本，增加了基本类型→包装类型的自动拆箱支持：
     * {@code int} 可以匹配到注册的 {@code Integer} 或 {@code Number}。
     * 数组类型：{@code Object[]} 匹配任意数组（包括基本类型数组）分数 1。
     */
    static int compatibilityScore(Class<?> declared, Class<?> actual) {
        if (declared == actual) return 2;
        // 基本类型 → 包装类型匹配（如 int → Integer, Number）
        Class<?> boxedActual = boxPrimitive(actual);
        if (declared == boxedActual) return 2;  // 精确匹配（含拆箱）
        // 数组通配匹配：declared 是 Object[] 则匹配任意数组
        if (declared == Object[].class && actual.isArray()) return 1;
        // 数组间赋性匹配（String[] → Object[]）
        if (declared.isArray() && actual.isArray()) {
            Class<?> declaredComp = declared.getComponentType();
            Class<?> actualComp = actual.getComponentType();
            if (actualComp.isPrimitive()) actualComp = boxPrimitive(actualComp);
            if (declaredComp == actualComp) return 1;
            if (declaredComp.isAssignableFrom(actualComp)) return 1;
        }
        if (declared.isAssignableFrom(boxedActual)) return 1;  // 父类匹配（含拆箱）
        return 0;
    }

    /**
     * 将任意数组（含基本类型数组）转为 Object[]。
     * 非数组对象以单元素数组返回。
     */
    static Object[] toObjectArray(Object array) {
        if (array instanceof Object[] oa) return oa;
        int len = Array.getLength(array);
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = Array.get(array, i);
        }
        return result;
    }

    static Value arrayConcat(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Class<?> comp = resolveComponentType(left.asJavaObject(), right.asJavaObject());
        Object[] result = new Object[lArr.length + rArr.length];
        System.arraycopy(lArr, 0, result, 0, lArr.length);
        System.arraycopy(rArr, 0, result, lArr.length, rArr.length);
        return Value.of(toTypedArray(result, comp));
    }

    static Value arrayDifference(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Class<?> comp = resolveComponentType(left.asJavaObject(), right.asJavaObject());
        Set<Object> rightSet = new HashSet<>(Arrays.asList(rArr));
        List<Object> result = new ArrayList<>();
        for (Object obj : lArr) {
            if (!rightSet.contains(obj)) {
                result.add(obj);
            }
        }
        return Value.of(toTypedArray(result.toArray(), comp));
    }

    static Value arrayIntersection(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Class<?> comp = resolveComponentType(left.asJavaObject(), right.asJavaObject());
        Set<Object> rightSet = new HashSet<>(Arrays.asList(rArr));
        List<Object> result = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object obj : lArr) {
            if (rightSet.contains(obj) && !seen.contains(obj)) {
                result.add(obj);
                seen.add(obj);
            }
        }
        return Value.of(toTypedArray(result.toArray(), comp));
    }

    static Value arrayUnion(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Class<?> comp = resolveComponentType(left.asJavaObject(), right.asJavaObject());
        Set<Object> resultSet = new LinkedHashSet<>();
        resultSet.addAll(Arrays.asList(lArr));
        resultSet.addAll(Arrays.asList(rArr));
        return Value.of(toTypedArray(resultSet.toArray(), comp));
    }

    static Value arraySymmetricDifference(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Class<?> comp = resolveComponentType(left.asJavaObject(), right.asJavaObject());
        Set<Object> lSet = new HashSet<>(Arrays.asList(lArr));
        Set<Object> rSet = new HashSet<>(Arrays.asList(rArr));
        List<Object> result = new ArrayList<>();
        for (Object obj : lArr) {
            if (!rSet.contains(obj)) result.add(obj);
        }
        for (Object obj : rArr) {
            if (!lSet.contains(obj)) result.add(obj);
        }
        return Value.of(toTypedArray(result.toArray(), comp));
    }

    static Value arrayCartesianProduct(Value left, Value right) {
        Object[] lArr = toObjectArray(left.asJavaObject());
        Object[] rArr = toObjectArray(right.asJavaObject());
        Object[] result = new Object[lArr.length * rArr.length];
        int idx = 0;
        for (Object l : lArr) {
            for (Object r : rArr) {
                result[idx++] = new Object[]{l, r};
            }
        }
        return Value.of(result);
    }

    /** 推导两个数组的 LUB 组件类型。 */
    private static Class<?> resolveComponentType(Object leftArr, Object rightArr) {
        if (leftArr == null || rightArr == null) return Object.class;
        Class<?> lc = leftArr.getClass().getComponentType();
        Class<?> rc = rightArr.getClass().getComponentType();
        if (lc == null || rc == null) return Object.class;
        return unboxIfPossible(leastCommonSupertype(boxPrimitive(lc), boxPrimitive(rc)));
    }

    /**
     * 从两个数组类型的运行时 Class 推导运算结果的最精确数组类型。
     * <p>例如 int[] + int[] → int[]；int[] + double[] → Number[]；String[] + int[] → Object[]。
     */
    static Class<?> computeArrayReturnType(Class<?> lhsArrayType, Class<?> rhsArrayType) {
        if (!lhsArrayType.isArray() || !rhsArrayType.isArray()) return Object.class;
        Class<?> lc = lhsArrayType.getComponentType();
        Class<?> rc = rhsArrayType.getComponentType();
        Class<?> comp = unboxIfPossible(leastCommonSupertype(boxPrimitive(lc), boxPrimitive(rc)));
        return Array.newInstance(comp, 0).getClass();
    }

    /** 将 Object[] 转为指定组件类型的数组。 */
    private static Object toTypedArray(Object[] arr, Class<?> componentType) {
        Object result = Array.newInstance(componentType, arr.length);
        for (int i = 0; i < arr.length; i++) {
            Array.set(result, i, arr[i]);
        }
        return result;
    }

    /** 包装类型 → 基本类型的反向映射。 */
    static Class<?> unboxIfPossible(Class<?> type) {
        if (type == Integer.class) return int.class;
        if (type == Long.class) return long.class;
        if (type == Double.class) return double.class;
        if (type == Float.class) return float.class;
        if (type == Short.class) return short.class;
        if (type == Byte.class) return byte.class;
        if (type == Character.class) return char.class;
        if (type == Boolean.class) return boolean.class;
        return type;
    }

    // ==================== Key 类型 ====================

    record BinaryOpKey(String operator, Class<?> lhsType, Class<?> rhsType) {}

    record UnaryOpKey(String operator, Class<?> operandType) {}
}
