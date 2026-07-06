package com.justnothing.engine.builtins;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的内置函数注册表。
 * <p>
 * 解析器（ParseContext）和运行时（Builtins / EvalContext）共享同一个实例，
 * 确保动态注入的 builtin 在解析期和运行期都可见。
 * <p>
 * 使用 ConcurrentHashMap 支持线程安全（Hook 场景可能跨线程调用）。
 */
public class BuiltinRegistry {

    private final Map<String, Builtins.BuiltinFunction> functions = new ConcurrentHashMap<>();
    private final Set<String> nameSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 注册一个 builtin 函数。 */
    public void register(String name, Builtins.BuiltinFunction function) {
        functions.put(name, function);
        nameSet.add(name);
    }

    /** 批量注册（用于初始化）。 */
    public void registerAll(Map<String, Builtins.BuiltinFunction> map) {
        functions.putAll(map);
        nameSet.addAll(map.keySet());
    }

    /** 是否已注册此名字的 builtin。解析器用这个做符号检查。 */
    public boolean isKnown(String name) {
        return nameSet.contains(name);
    }

    /** 获取函数实现。Evaluator 用这个执行调用。 */
    public Builtins.BuiltinFunction get(String name) {
        return functions.get(name);
    }

    /** 获取所有已注册的名字。 */
    public Set<String> getAllNames() {
        return Collections.unmodifiableSet(new HashSet<>(nameSet));
    }

    /** 获取所有函数。 */
    public Map<String, Builtins.BuiltinFunction> getAllFunctions() {
        return Collections.unmodifiableMap(functions);
    }

    /** 已注册的函数数量。 */
    public int size() {
        return functions.size();
    }
}
