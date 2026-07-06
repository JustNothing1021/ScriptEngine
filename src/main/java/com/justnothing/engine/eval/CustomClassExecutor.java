package com.justnothing.engine.eval;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ReturnException;
import com.justnothing.engine.parser.ParseContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomClassExecutor {

    private static final Map<String, MethodDeclarationNode> methodRegistry = new ConcurrentHashMap<>();
    private static final ThreadLocal<ExecutorContext> currentContext = new ThreadLocal<>();

    public static void setContext(EvalContext evalCtx, ParseContext parseCtx) {
        currentContext.set(new ExecutorContext(evalCtx, parseCtx));
    }

    public static void clearContext() {
        currentContext.remove();
    }

    private static ExecutorContext requireContext() {
        ExecutorContext ctx = currentContext.get();
        if (ctx == null) throw new IllegalStateException("CustomClassExecutor not initialized");
        return ctx;
    }

    public static void registerMethod(String className, String methodName, String descriptor,
                                       MethodDeclarationNode decl) {
        methodRegistry.put(key(className, methodName, descriptor), decl);
    }

    public static void unregisterClass(String className) {
        methodRegistry.entrySet().removeIf(e -> e.getKey().startsWith(className + "#"));
    }

    private static String key(String className, String methodName, String descriptor) {
        return className + "#" + methodName + "#" + descriptor;
    }

    /**
     * 由生成的字节码调用。参数和返回类型固定为 Object 以兼容所有签名。
     */
    public static Object execute(String className, String methodName, String descriptor,
                                  Object instance, Object[] args) {
        String k = key(className, methodName, descriptor);
        MethodDeclarationNode method = methodRegistry.get(k);
        if (method == null) {
            // 用参数数量 fallback 查找（部分调用场景没有精确描述符）
            method = findMethodByParamCount(className, methodName, args != null ? args.length : 0);
        }
        if (method == null) {
            throw new RuntimeException("Method not found: " + className + "#" + methodName);
        }

        ExecutorContext ctx = requireContext();
        ASTNode body = method.getBody();
        if (body == null) {
            return defaultReturn(method.getReturnType());
        }

        // 创建方法级 EvalContext
        EvalContext methodCtx = ctx.evalContext.createChild();

        // this
        if (instance != null) {
            methodCtx.declareVariable("this", Value.of(instance));
        }

        // 参数
        List<ParameterNode> params = method.getParameters();
        if (params != null && args != null) {
            for (int i = 0; i < params.size() && i < args.length; i++) {
                methodCtx.declareVariable(params.get(i).getParameterName(), Value.of(args[i]));
            }
        }

        // 读取实例字段 → 设置变量
        Map<String, Field> fields = collectFields(instance != null ? instance.getClass() : null);
        for (Field f : fields.values()) {
            try {
                methodCtx.declareVariable(f.getName(), Value.of(f.get(instance)));
            } catch (Exception ignored) {
            }
        }

        // 执行方法体
        Evaluator methodEval = new Evaluator(methodCtx, ctx.parseContext);
        Value result;
        try {
            result = methodEval.evaluate(body);
        } catch (ReturnException e) {
            // 字段写回
            for (Field f : fields.values()) {
                try {
                    if (methodCtx.hasVariable(f.getName())) {
                        Value v = methodCtx.getVariable(f.getName());
                        f.set(instance, v.asJavaObject());
                    }
                } catch (Exception ignored) {
                }
            }
            return ((Value) e.getValue()).asJavaObject();
        }

        // 字段写回
        for (Field f : fields.values()) {
            try {
                if (methodCtx.hasVariable(f.getName())) {
                    Value v = methodCtx.getVariable(f.getName());
                    f.set(instance, v.asJavaObject());
                }
            } catch (Exception ignored) {
            }
        }

        return result != null ? result.asJavaObject() : null;
    }

    private static MethodDeclarationNode findMethodByParamCount(String className, String methodName, int paramCount) {
        String prefix = className + "#" + methodName + "#";
        for (Map.Entry<String, MethodDeclarationNode> e : methodRegistry.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                List<ParameterNode> params = e.getValue().getParameters();
                int count = params != null ? params.size() : 0;
                if (count == paramCount) return e.getValue();
            }
        }
        return null;
    }

    private static Map<String, Field> collectFields(Class<?> clazz) {
        Map<String, Field> result = new LinkedHashMap<>();
        if (clazz == null) return result;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    result.putIfAbsent(f.getName(), f);
                }
            }
            c = c.getSuperclass();
        }
        return result;
    }

    private static Object defaultReturn(ClassReferenceNode returnType) {
        if (returnType == null) return null;
        Class<?> type = returnType.getResolvedClass();
        if (type == null || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class || type == short.class || type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }

    private record ExecutorContext(EvalContext evalContext, ParseContext parseContext) {
    }
}
