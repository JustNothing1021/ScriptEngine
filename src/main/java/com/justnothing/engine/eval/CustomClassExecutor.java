package com.justnothing.engine.eval;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.exception.ReturnException;
import com.justnothing.engine.parser.ParseContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomClassExecutor {

    private static final Map<String, MethodDeclarationNode> methodRegistry = new ConcurrentHashMap<>();
    private static final ThreadLocal<ExecutorContext> currentContext = new ThreadLocal<>();

    /** 正在执行的脚本类方法帧，用于嵌套调用返回后刷新外层的字段镜像。 */
    private static final ThreadLocal<Deque<ActiveFrame>> activeFrames =
            ThreadLocal.withInitial(ArrayDeque::new);

    public static void setContext(EvalContext evalCtx, ParseContext parseCtx) {
        currentContext.set(new ExecutorContext(evalCtx, parseCtx));
    }

    public static void clearContext() {
        currentContext.remove();
        activeFrames.remove();
    }

    private static ExecutorContext requireContext() {
        ExecutorContext ctx = currentContext.get();
        if (ctx == null) throw new IllegalStateException("CustomClassExecutor not initialized");
        return ctx;
    }

    /**
     * 字段镜像变量在方法帧里的名字。
     * <p>
     * 必须与字段名本身区分开：方法内可能有同名形参或局部变量遮蔽字段
     * （{@code void set(int v) { this.v = v; }} 就是最常见的一种），若镜像直接用字段名，
     * 两者会互相覆盖，导致 {@code v} 读到字段旧值、局部变量赋值又误写进字段。
     * </p>
     */
    public static String mirrorName(String fieldName) {
        return "$field$" + fieldName;
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
        } else {
            // 静态方法：脚本内未限定的同类调用 f(...) 会被解析为 this.f(...)，
            // 此处把 this 绑定到类对象，使静态方法能以类为接收者正确分发
            Class<?> self = resolveGeneratedClass(ctx.parseContext, className);
            if (self != null) {
                methodCtx.declareVariable("this", Value.of(self));
            }
        }

        // 参数
        List<ParameterNode> params = method.getParameters();
        if (params != null && args != null) {
            for (int i = 0; i < params.size() && i < args.length; i++) {
                methodCtx.declareVariable(params.get(i).getParameterName(), Value.of(args[i]));
            }
        }

        // 读取实例字段 → 设置同名镜像变量：方法体内的字段读写都走镜像，
        // 赋值由 Evaluator 立即写穿回实例字段（见 Evaluator#writeFieldThroughMirror），
        // 因此这里不再做方法结束时的统一写回 —— 那种「延迟写回」会让本方法内随后
        // 发起的嵌套调用读到字段旧值，也会用陈旧镜像覆盖嵌套调用对同一字段的修改
        Map<String, Field> fields = collectFields(instance != null ? instance.getClass() : null);
        for (Field f : fields.values()) {
            try {
                methodCtx.declareVariable(mirrorName(f.getName()), Value.of(f.get(instance)));
            } catch (Exception ignored) {
            }
        }

        // 执行方法体
        Evaluator methodEval = new Evaluator(methodCtx, ctx.parseContext);
        activeFrames.get().push(new ActiveFrame(instance, fields, methodCtx));
        Value result;
        try {
            result = methodEval.evaluate(body);
        } catch (ReturnException e) {
            return ((Value) e.getValue()).asJavaObject();
        } finally {
            activeFrames.get().pop();
            // 嵌套调用的方法体可能改过同一实例的字段，需要把外层帧的镜像刷新为最新值
            refreshEnclosingMirrors(instance);
        }

        return result != null ? result.asJavaObject() : null;
    }

    /**
     * 把外层方法帧的字段镜像重新读成实例上的最新值。
     * <p>
     * 镜像只是读取用的缓存，实例字段才是唯一真相：本方法（被嵌套调用的那层）可能刚改过
     * 字段，而外层帧手里的还是进入时的旧副本，不刷新的话外层后续读取会拿到过期值。
     * </p>
     */
    private static void refreshEnclosingMirrors(Object instance) {
        if (instance == null) {
            return;
        }
        for (ActiveFrame frame : activeFrames.get()) {
            if (frame.instance() != instance) continue;
            for (Field f : frame.fields().values()) {
                try {
                    frame.methodCtx().declareVariable(mirrorName(f.getName()), Value.of(f.get(instance)));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 一个正在执行的脚本类方法帧：绑定实例、实例字段表与该帧的字段镜像上下文。 */
    private record ActiveFrame(Object instance, Map<String, Field> fields, EvalContext methodCtx) {
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

    /** 查找脚本引擎生成的类（用于静态方法中把 this 绑定为类对象）。 */
    private static Class<?> resolveGeneratedClass(ParseContext parseContext, String className) {
        if (parseContext == null) return null;
        DynamicClassGenerator codegen = parseContext.getCodeGenerator();
        return codegen != null ? codegen.getGenerated(className) : null;
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
