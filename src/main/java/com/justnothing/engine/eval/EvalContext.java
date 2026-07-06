package com.justnothing.engine.eval;

import com.justnothing.engine.api.IOutputHandler;
import com.justnothing.engine.builtins.BuiltinRegistry;
import com.justnothing.engine.builtins.Builtins;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.security.SecurityGate;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvalContext implements AutoCloseable {
    private final Map<String, Value> variables = new HashMap<>();
    private final EvalContext parent;
    private IOutputHandler output;
    /** 共享的 BuiltinRegistry，与 ParseContext 使用同一实例。 */
    private final BuiltinRegistry builtinRegistry;
    private final Builtins builtins;

    /** 安全门卫，null 表示无安全限制（默认）。 */
    private SecurityGate securityGate;

    /** 自包含模式（测试/REPL）：创建私有 Registry，不与其他组件共享。 */
    public EvalContext() {
        this((EvalContext) null, null);
    }

    public EvalContext(IOutputHandler output) {
        this((EvalContext) null, output);
    }

    /** 子上下文：继承父级的 Registry（Lambda 执行等场景）。 */
    public EvalContext(EvalContext parent) {
        this(parent, parent.output);
    }

    /** 内部构造：基于父级或自包含。 */
    private EvalContext(EvalContext parent, IOutputHandler output) {
        this.parent = parent;
        this.output = output;
        this.builtinRegistry = (parent != null) ? parent.builtinRegistry : new BuiltinRegistry();
        this.builtins = new Builtins(builtinRegistry, output);
    }

    /** 共享 Registry 模式（ScriptRunner 主路径推荐）。 */
    public EvalContext(BuiltinRegistry registry, IOutputHandler output) {
        this.parent = null;
        this.output = output;
        this.builtinRegistry = registry;
        this.builtins = new Builtins(registry, output);
    }

    public EvalContext createChild() {
        EvalContext child = new EvalContext(this, output);
        child.securityGate = this.securityGate;
        return child;
    }


    public void assignVariable(String name, Value value) {
        EvalContext ctx = this;
        // 只查当前层的 variables map（不含父链），找到真正声明该变量的作用域
        while (ctx != null && !ctx.variables.containsKey(name)) ctx = ctx.getParent();
        if (ctx == null) throw new EvalException("Variable not defined: " + name, ErrorCode.SCOPE_VARIABLE_NOT_FOUND);
        ctx.variables.put(name, value);
    }


    /** 从当前作用域向上查找变量的定义位置并赋值。如果不存在则创建。 */
    public void declareVariable(String name, Value value) {
//        if (variables.containsKey(name)) throw new EvalException("Variable already declared: " + name, ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED);
        variables.put(name, value);
    }


    public Value getVariable(String name) {
        if (variables.containsKey(name)) {
            return variables.get(name);
        }
        if (parent != null) {
            return parent.getVariable(name);
        }
        throw new EvalException("Variable not defined: " + name, ErrorCode.SCOPE_VARIABLE_NOT_FOUND);
    }

    public EvalContext getParent() {
        return parent;
    }

    public Map<String, Value> getVariables() {
        return variables;
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name) || (parent != null && parent.hasVariable(name));
    }

    public void print(String text) {
        if (output != null) {
            output.print(text);
        } else {
            System.out.print(text);
        }
    }

    public void println(String text) {
        if (output != null) {
            output.println(text);
        } else {
            System.out.println(text);
        }
    }

    public IOutputHandler getOutput() {
        return output;
    }

    public void setOutput(IOutputHandler output) {
        this.output = output;
        // 同步更新 Builtins 的 outputHandler，确保 println() 等内置函数的输出走新 handler
        if (builtins != null) {
            builtins.setOutputHandler(output);
        }
    }

    public void close() {
        variables.clear();
    }

    // ==================== Builtins ====================

    public Builtins getBuiltins() {
        return builtins;
    }

    /** 获取共享的 BuiltinRegistry（与 ParseContext 同一实例）。 */
    public BuiltinRegistry getBuiltinRegistry() {
        return builtinRegistry;
    }

    public boolean hasBuiltin(String name) {
        return builtins != null && builtins.hasFunction(name);
    }

    public Builtins.BuiltinFunction getBuiltin(String name) {
        return builtins != null ? builtins.getFunction(name) : null;
    }

    public Value callBuiltin(String name, List<Value> args) {
        Builtins.BuiltinFunction func = builtins != null ? builtins.getFunction(name) : null;
        if (func == null) {
            throw new EvalException("Unknown builtin function: " + name, ErrorCode.EVAL_UNDEFINED_VARIABLE);
        }
        return func.call(args);
    }

    public void addBuiltIn(String name, Builtins.BuiltinFunction function) {
        if (builtins != null) {
            builtins.registerFunction(name, function);
        }
    }

    // ==================== SecurityGate ====================

    public SecurityGate getSecurityGate() {
        return securityGate;
    }

    /**
     * 设置安全门卫。设为 null 表示无限制模式（默认）。
     *
     * <p>子上下文通过 createChild() 继承父上下文的 securityGate。
     */
    public void setSecurityGate(SecurityGate securityGate) {
        this.securityGate = securityGate;
    }
}
