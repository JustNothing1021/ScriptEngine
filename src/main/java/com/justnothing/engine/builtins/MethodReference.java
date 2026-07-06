package com.justnothing.engine.builtins;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Function;

public class MethodReference {

    private final String methodName;
    private final Function<Object[], Object> refFunc;
    private final String targetClassName;       // 目标类全限定名（如 java.lang.System）
    private final Object boundTarget;            // 绑定的实例（bound 方法引用时非 null）
    private final Method boundMethod;            // 解析期绑定的具体重载（可能为 null）
    private final List<String> typeArguments;   // 显式泛型参数（如 <int>）

    public MethodReference(String methodName, Function<Object[], Object> refFunc) {
        this(methodName, refFunc, null, null, null, null);
    }

    public MethodReference(String methodName, Function<Object[], Object> refFunc,
                           String targetClassName, Object boundTarget,
                           Method boundMethod, List<String> typeArguments) {
        this.methodName = methodName;
        this.refFunc = refFunc;
        this.targetClassName = targetClassName;
        this.boundTarget = boundTarget;
        this.boundMethod = boundMethod;
        this.typeArguments = typeArguments != null ? typeArguments : List.of();
    }

    public String getMethodName() {
        return methodName;
    }

    public Object invoke(Object... args) {
        return refFunc.apply(args);
    }

    public Function<Object[], Object> asFunction() {
        return refFunc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MethodReference[");

        String className = targetClassName != null ? targetClassName : "?";
        if (boundTarget != null) {
            sb.append("bound=").append(boundTarget).append(" ");
        } else if (targetNodeIsClass()) {
            if (isStaticRef()) {
                sb.append("static ");
            } else {
                sb.append("unbound ");
            }
        } else {
            sb.append("unbound ");
        }

        sb.append(className).append("::");
        // 泛型参数（如 <int>）
        if (!typeArguments.isEmpty()) {
            sb.append("<").append(String.join(", ", typeArguments)).append(">");
        }
        sb.append(methodName);

        if (boundMethod != null) {
            sb.append("(");
            Class<?>[] paramTypes = boundMethod.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(paramTypes[i].getSimpleName());
            }
            sb.append(")");
        }

        sb.append("]");
        return sb.toString();
    }

    /** 判断目标是否是 Class 对象（静态引用） */
    private boolean targetNodeIsClass() {
        return boundTarget == null && targetClassName != null;
    }

    /** 通过方法修饰符判断实际是 static 还是实例方法引用 */
    private boolean isStaticRef() {
        if (boundMethod != null) {
            return Modifier.isStatic(boundMethod.getModifiers());
        }
        if (targetClassName == null) return true;
        try {
            Class<?> clazz = Class.forName(targetClassName);
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName)) {
                    return Modifier.isStatic(m.getModifiers());
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    @SuppressWarnings("unchecked")
    public <T> T asInterface(Class<T> fiType) {
        Method sam = Lambda.getSAM(fiType);
        if (sam == null) return (T) refFunc;
        return (T) Proxy.newProxyInstance(
                fiType.getClassLoader(),
                new Class<?>[]{fiType},
                (proxy, method, javaArgs) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return Lambda.handleObjectMethod(proxy, method, javaArgs);
                    }
                    // 支持 .invoke() 调用：代理默认只暴露 SAM 接口方法，
                    // 但用户可能直接在方法引用上调用 invoke(args)
                    if ("invoke".equals(method.getName())) {
                        return refFunc.apply(javaArgs != null ? javaArgs : new Object[0]);
                    }
                    if (!method.equals(sam)) {
                        if (method.isDefault()) {
                            return InvocationHandler.invokeDefault(proxy, method, javaArgs);
                        }
                        throw new UnsupportedOperationException("Not a SAM: " + method);
                    }
                    return refFunc.apply(javaArgs != null ? javaArgs : new Object[0]);
                });
    }
}
