package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.parser.constant.ConstantFolder;
import com.justnothing.engine.util.MethodResolver;
import com.justnothing.engine.util.ReflectCache;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 表达式解析基础层。
 * <p>
 * 提供类型标注、类型推断与运算符重载检查等通用辅助能力，供上层各表达式解析层共用。
 * </p>
 *
 * @see BaseParser
 * @see ConstantFolder
 */
abstract class ExpressionSupport extends BaseParser {

    /** 方法重载选择器（解析期绑定）。 */
    protected final MethodResolver methodResolver;

    protected ExpressionSupport(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
        this.methodResolver = new MethodResolver(context);
    }

    protected abstract ASTNode parseNextExpression() throws CythavaParseException;

    protected abstract List<ASTNode> parseArgumentList() throws CythavaParseException;

    protected abstract ArrayLiteralNode parseBraceArrayInitializer() throws CythavaParseException;

    /** 为 AST 节点设置解析期类型（便捷方法）。 */
    protected void annotate(ASTNode node, JType type) {
        context.setType(node, type);
    }

    /** 为 AST 节点设置解析期类型（Class 便捷重载）。 */
    protected void annotate(ASTNode node, Class<?> clazz) {
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
    protected void annotateFoldingResult(ASTNode node, Class<?> fallbackType)
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
        // ?? 与 ?: 是语言级空值合并运算符：语义由 Evaluator 直接实现，与操作数类型无关，
        // 因此不参与运算符重载检查（否则 String ?? String 会被误判为"无匹配运算符"）
        if (binaryOp.getOperator() == BinaryOpNode.Operator.NULL_COALESCING
                || binaryOp.getOperator() == BinaryOpNode.Operator.ELVIS) {
            return;
        }
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
    protected Method resolveByExplicitSignature(MethodReferenceNode methodRef, String methodName,
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
            for (Method m : ReflectCache.methods(targetClass)) {
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
    protected Class<?> resolveNodeType(ASTNode node) {
        JType type = context.getType(node);
        if (type != null)
            return type.getRawType();
        // fallback: 从 LiteralNode 自身获取类型
        if (node instanceof LiteralNode literal) {
            return literal.getType();
        }
        // switch 冒号风格分支体是 yield expr; —— 类型取自 yield 的值
        if (node instanceof YieldNode yield) {
            return yield.getValue() != null ? resolveNodeType(yield.getValue()) : null;
        }
        return null;
    }
}
