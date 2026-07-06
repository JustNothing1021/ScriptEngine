package com.justnothing.engine.eval;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.MethodCallNode;
import com.justnothing.engine.ast.nodes.MethodReferenceNode;
import com.justnothing.engine.ast.nodes.PipelineNode;
import com.justnothing.engine.builtins.Lambda;
import com.justnothing.engine.builtins.MethodReference;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.util.MethodResolver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pipeline 管道操作符（{@code |>）的专用分发器。
 * <p>
 * 将 {@code visitPipeline} 的 ~250 行逻辑从 Evaluator 中抽离，负责：
 * <ul>
 *   <li>AST MethodRef 节点分发（0 参实例方法 / N 参静态方法 / 绑定引用）</li>
 *   <li>运行时存储的 MethodRef 对象分发（绕过 invoke() 的 0 参问题）</li>
 *   <li>Lambda / Function 管道调用</li>
 * </ul>
 *
 * @see com.justnothing.engine.eval.Evaluator#visitPipeline(PipelineNode)
 */
public final class PipelineDispatcher {

    private final Evaluator evaluator;

    public PipelineDispatcher(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * 分发 Pipeline 节点的求值。
     *
     * @param node Pipeline AST 节点
     * @return 管道执行结果
     */
    public Value dispatch(PipelineNode node) throws Exception {
        Value input = evaluator.evaluate(node.getInput());
        Object inputValue = input.asJavaObject();

        if (node.getFunction() instanceof MethodReferenceNode ref) {
            return dispatchAstMethodRef(ref, inputValue);
        }
        if (node.getFunction() instanceof MethodCallNode call) {
            return dispatchMethodCall(call, input);
        }

        // 运行时可调用对象（Lambda / 存储的 MethodRef / Function）
        Value funcVal = evaluator.evaluate(node.getFunction());
        Object raw = funcVal.asJavaObject();

        if (raw instanceof Lambda lambda) {
            return lambda.invoke(input);
        }
        if (raw instanceof MethodReference methodRef) {
            return dispatchStoredMethodRef(methodRef, inputValue);
        }
        if (raw instanceof Function<?, ?> fn) {
            @SuppressWarnings("unchecked")
            Function<Object, Object> typedFn = (Function<Object, Object>) fn;
            return Value.of(typedFn.apply(inputValue));
        }
        throw new EvalException("Pipeline: unsupported function type", ErrorCode.EVAL_INVALID_OPERATION);
    }

    // ==================== AST MethodRef 分发 ====================

    /**
     * 分发 AST 级别 MethodReference 节点的管道调用。
     * <p>
     * 非绑定引用（target is Class）：0 参实例方法 → input 作为 receiver；N 参 → resolveRuntime 智能匹配。
     * 绑定引用（target is instance）：target 作为 receiver。
     */
    private Value dispatchAstMethodRef(MethodReferenceNode ref, Object inputValue) throws EvalException {
        String methodName = ref.getMethodName();
        ASTNode targetNode = ref.getTarget();
        Object target = targetNode != null ? evaluator.evaluate(targetNode).asJavaObject() : null;

        if (target instanceof Class<?> targetClass) {
            // 非绑定引用：String::trim, String::toUpperCase 等
            return tryZeroArgInstance(targetClass, methodName, inputValue)
                    .orElseGet(() -> tryResolveRuntime(targetClass, methodName, inputValue, null));
        } else {
            // 绑定引用：instance::method
            Class<?> clazz = target != null ? target.getClass()
                    : evaluator.findClassForMethod(methodName);
            if (clazz == null) throw new EvalException("Cannot resolve method: " + methodName,
                    ErrorCode.METHOD_NOT_FOUND);

            return tryZeroArgOnTarget(clazz, methodName, target)
                    .orElseGet(() -> tryResolveRuntime(clazz, methodName, inputValue, target));
        }
    }

    // ==================== 存储 MethodRef 分发 ====================

    /**
     * 分发运行时存储的 MethodReference 对象的管道调用。
     * <p>
     * 核心问题：{@code MethodReference.invoke(input)} 内部用 resolveRuntime 找 1 参方法，
     * 但 {@code String::toUpperCase()} 是 0 参方法。此方法绕过 invoke() 直接做正确分发。
     */
    private Value dispatchStoredMethodRef(MethodReference methodRef, Object inputValue) throws Exception {
        String methodName = methodRef.getMethodName();
        Object boundTarget = getFieldValue(methodRef, "boundTarget");
        String targetClassName = (String) getFieldValue(methodRef, "targetClassName");
        Method explicitMethod = (Method) getFieldValue(methodRef, "boundMethod");

        Class<?> targetClass = resolveTargetClass(boundTarget, targetClassName, methodName);

        // 优先使用显式绑定的方法
        if (explicitMethod != null) {
            return invokeExplicitMethod(explicitMethod, boundTarget, inputValue);
        }

        // 无显式绑定：0 参 → resolveRuntime → 手动 coercion 三级回退
        if (targetClass != null) {
            // 0 参实例方法 → input 作为 receiver
            Value zeroArgResult = tryZeroArgInstance(targetClass, methodName, inputValue).orElse(null);
            if (zeroArgResult != null) return zeroArgResult;

            // N 参方法 → resolveRuntime 智能匹配
            try {
                Method method = MethodResolver.resolveRuntime(targetClass, methodName,
                        new Object[]{inputValue});
                evaluator.checkMethod(method);
                return invokeByModifier(method, inputValue, null);
            } catch (EvalException e) {
                throw e;
            } catch (Exception ignored) {
                // fall through
            }

            // 最终回退：手动遍历 + 拆箱兼容
            Method matched = findMethodWithCoercion(targetClass, methodName, inputValue);
            if (matched != null) {
                evaluator.checkMethod(matched);
                return invokeByModifier(matched, inputValue, null);
            }
        }

        throw new EvalException("No applicable method: " + methodName
                + " (tried 0-param, runtime-resolve, and coercion-fallback)",
                ErrorCode.METHOD_NO_APPLICABLE_METHOD);
    }

    // ==================== MethodCall 分发 ====================

    private Value dispatchMethodCall(MethodCallNode call, Value input) throws EvalException {
        List<Value> args = new ArrayList<>();
        args.add(input);
        for (ASTNode arg : call.getArguments()) {
            args.add(evaluator.evaluate(arg));
        }
        Object target = evaluator.resolveTarget(call.getTarget());
        try {
            if (call.getBoundMethod() != null) {
                evaluator.checkMethod(call.getBoundMethod());
                Object result = call.getBoundMethod().invoke(target,
                        args.stream().map(Value::asJavaObject).toArray());
                return Value.of(result);
            }
        } catch (Exception e) {
            throw new EvalException("Pipeline method call failed", e, ErrorCode.METHOD_INVOCATION_FAILED);
        }
        throw new EvalException("Pipeline method call: no bound method",
                ErrorCode.METHOD_NO_APPLICABLE_METHOD);
    }

    // ==================== 方法查找策略 ====================

    /** 尝试 0 参实例方法调用（input 作为 receiver）。 */
    private ValueOrError tryZeroArgInstance(Class<?> targetClass, String methodName, Object receiver) {
        Method m = findMethod(targetClass, methodName, 0);
        if (m == null || Modifier.isStatic(m.getModifiers())) return ValueOrError.empty();
        try {
            evaluator.checkMethod(m);
            return ValueOrError.of(Value.of(m.invoke(receiver)));
        } catch (Exception e) {
            return ValueOrError.error(e);
        }
    }

    /** 尝试在指定 target 上调 0 参方法。 */
    private ValueOrError tryZeroArgOnTarget(Class<?> clazz, String methodName, Object target) {
        Method m = findMethod(clazz, methodName, 0);
        if (m == null) return ValueOrError.empty();
        try {
            evaluator.checkMethod(m);
            return ValueOrError.of(Value.of(m.invoke(target)));
        } catch (Exception e) {
            return ValueOrError.error(e);
        }
    }

    /** 用 resolveRuntime 做 N 参智能匹配。 */
    private Value tryResolveRuntime(Class<?> targetClass, String methodName,
                                     Object inputValue, Object receiver) throws EvalException {
        try {
            Method method = MethodResolver.resolveRuntime(targetClass, methodName,
                    new Object[]{inputValue});
            evaluator.checkMethod(method);
            return invokeByModifier(method, inputValue, receiver);
        } catch (EvalException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new EvalException("Method not found: " + methodName + " in pipeline",
                    ErrorCode.METHOD_NO_APPLICABLE_METHOD);
        } catch (Exception e) {
            throw new EvalException("Pipeline method resolution failed", e,
                    ErrorCode.METHOD_INVOCATION_FAILED);
        }
    }

    /** 根据方法的 static/instance 特性决定调用方式。 */
    private Value invokeByModifier(Method method, Object inputValue, Object explicitReceiver)
            throws Exception {
        if (Modifier.isStatic(method.getModifiers())) {
            return Value.of(method.invoke(null, inputValue));
        } else {
            Object receiver = explicitReceiver != null ? explicitReceiver : inputValue;
            if (method.getParameterCount() == 0) {
                return Value.of(method.invoke(receiver));
            } else {
                return Value.of(method.invoke(receiver, inputValue));
            }
        }
    }

    /** 调用显式绑定的方法。 */
    private Value invokeExplicitMethod(Method explicitMethod, Object boundTarget, Object inputValue)
            throws Exception {
        evaluator.checkMethod(explicitMethod);
        int paramCount = explicitMethod.getParameterCount();
        if (paramCount == 0) {
            Object receiver = boundTarget != null ? boundTarget : inputValue;
            return Value.of(explicitMethod.invoke(receiver));
        } else {
            Object receiver = Modifier.isStatic(explicitMethod.getModifiers())
                    ? null : (boundTarget != null ? boundTarget : inputValue);
            return Value.of(explicitMethod.invoke(receiver, coerceToArgs(explicitMethod.getParameterTypes(), inputValue)));
        }
    }

    // ==================== 工具方法 ====================

    private Method findMethod(Class<?> clazz, String name, int paramCount) {
        try {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    /**
     * 手动查找方法（含拆箱兼容性）。用于 resolveRuntime 失败时的最终回退。
     */
    private Method findMethodWithCoercion(Class<?> clazz, String methodName, Object argValue) {
        Class<?> argType = argValue != null ? argValue.getClass() : Object.class;
        Method bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length != 1) continue;

            int score = scoreCoercion(paramTypes[0], argType);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                bestMatch = m;
            }
        }
        return bestMatch;
    }

    /** 单参数类型转换分数。 */
    private static int scoreCoercion(Class<?> targetType, Class<?> sourceType) {
        if (targetType.isAssignableFrom(sourceType)) {
            if (targetType == sourceType) return 0;
            if (targetType == Object.class) return 100;
            return 10;
        }
        if (targetType.isPrimitive()) {
            if (boxPrimitive(targetType) == sourceType) return 1;  // 精确拆箱
        }
        if (!targetType.isPrimitive() && sourceType.isPrimitive()) {
            if (targetType.isAssignableFrom(boxPrimitive(sourceType))) return 2;  // 装箱+赋值
        }
        return -1;
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

    private Class<?> resolveTargetClass(Object boundTarget, String targetClassName, String fallbackName) {
        if (boundTarget instanceof Class<?> clazz) return clazz;
        if (boundTarget != null) return boundTarget.getClass();
        if (targetClassName != null) {
            try { return Class.forName(targetClassName); } catch (ClassNotFoundException ignored) {}
        }
        return evaluator.findClassForMethod(fallbackName);
    }

    private Object[] coerceToArgs(Class<?>[] paramTypes, Object inputValue) {
        Object[] args = new Object[paramTypes.length];
        if (paramTypes.length >= 1) {
            args[0] = MethodResolver.coerceArg(paramTypes[0], inputValue);
        }
        return args;
    }

    private static Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部工具类 ====================

    /** 可能为空的值容器，用于 try-then-fallback 链式调用模式。 */
    private static final class ValueOrError {
        private final Value value;
        private final Throwable error;
        private final boolean present;

        private ValueOrError(Value value, Throwable error, boolean present) {
            this.value = value;
            this.error = error;
            this.present = present;
        }

        static ValueOrError of(Value value) { return new ValueOrError(value, null, true); }
        static ValueOrError empty() { return new ValueOrError(null, null, false); }
        static ValueOrError error(Throwable error) { return new ValueOrError(null, error, false); }

        boolean isEmpty() { return !present; }
        Value get() { return value; }
        Value orElse(Value fallback) { return present ? value : fallback; }
        Value orElseGet(Supplier<Value> fallback) { return present ? value : fallback.get(); }
    }
}
