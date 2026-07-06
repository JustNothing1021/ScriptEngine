package com.justnothing.engine.builtins;

import com.justnothing.engine.eval.EvalContext;
import com.justnothing.engine.eval.Value;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class Lambda {

    private final Function<Value[], Value> func;
    private final List<String> parameterNames;
    private final EvalContext closureContext;
    private final Map<String, Value> capturedVariables;

    public Lambda(Function<Value[], Value> func, List<String> parameterNames, EvalContext closureContext) {
        this.func = func;
        this.parameterNames = parameterNames;
        this.closureContext = closureContext;
        this.capturedVariables = captureVariables();
    }

    private Map<String, Value> captureVariables() {
        Map<String, Value> captured = new HashMap<>();
        if (closureContext == null) return captured;
        EvalContext ctx = closureContext;
        while (ctx != null) {
            for (Map.Entry<String, Value> entry : ctx.getVariables().entrySet()) {
                captured.putIfAbsent(entry.getKey(), entry.getValue());
            }
            ctx = ctx.getParent();
        }
        return captured;
    }

    public Function<Value[], Value> asFunction() {
        return func;
    }

    public Value invoke(Value... args) {
        return func.apply(args);
    }

    public List<String> getParameterNames() {
        return parameterNames;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Lambda[params=(");
        for (int i = 0; i < parameterNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameterNames.get(i));
        }
        sb.append(")]");
        return sb.toString();
    }


    public Map<String, Value> getCapturedVariables() {
        return capturedVariables;
    }

    @SuppressWarnings("unchecked")
    public <T> T asInterface(Class<T> fiType) {
        Method sam = getSAM(fiType);
        if (sam == null) return (T) func;
        return (T) Proxy.newProxyInstance(
                fiType.getClassLoader(),
                new Class<?>[]{fiType},
                (proxy, method, javaArgs) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, javaArgs);
                    }
                    if (!method.equals(sam)) {
                        if (method.isDefault()) {
                            return InvocationHandler.invokeDefault(proxy, method, javaArgs);
                        }
                        throw new UnsupportedOperationException("Not a SAM: " + method);
                    }
                    int paramCount = sam.getParameterCount();
                    Value[] valueArgs = new Value[paramCount];
                    for (int i = 0; i < paramCount; i++) {
                        valueArgs[i] = javaArgs != null && i < javaArgs.length
                                ? Value.of(javaArgs[i])
                                : Value.NullValue.INSTANCE;
                    }
                    Value result = func.apply(valueArgs);
                    return convertToJavaReturnType(result, sam.getReturnType());
                });
    }

    static Object convertToJavaReturnType(Value value, Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        if (value == null || value instanceof Value.NullValue) return null;
        return value.asJavaObject();
    }

    public static Method getSAM(Class<?> fiType) {
        if (fiType == null || !fiType.isInterface()) return null;
        for (Method m : fiType.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (Modifier.isAbstract(m.getModifiers()) && !m.isDefault()) {
                return m;
            }
        }
        return null;
    }

    public static boolean isFunctionalInterface(Class<?> type) {
        return type != null && type.isInterface() && getSAM(type) != null;
    }

    static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        return switch (name) {
            case "toString" -> "Lambda[proxy=" + System.identityHashCode(proxy) + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && args[0] == proxy;
            default -> throw new UnsupportedOperationException("Unknown Object method: " + name);
        };
    }
}
