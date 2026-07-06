package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 方法调用节点
 * <p>
 * 表示方法调用，包括目标对象、方法名和参数列表。
 * 支持解析期方法重载选择绑定（boundMethod）。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class MethodCallNode extends ASTNode {
    
    private final ASTNode target;
    private final String methodName;
    private final List<ASTNode> arguments;
    
    /**
     * 解析期绑定的具体方法（重载选择结果）。
     * <p>
     * 如果为 null，表示未能在解析期确定重载（Evaluator 阶段再做延迟绑定）。
     * </p>
     */
    private Method boundMethod;
    

    private MethodCallNode(ASTNode target, String methodName,
                       List<ASTNode> arguments, SourceLocation location) {
        super(location);
        this.target = target;
        this.methodName = methodName;
        this.arguments = arguments != null ?
            List.copyOf(arguments) :
            Collections.emptyList();
    }
    
    public ASTNode getTarget() {
        return target;
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public List<ASTNode> getArguments() {
        return arguments;
    }
    
    /** 获取解析期绑定的具体方法（可能为 null）。 */
    public Method getBoundMethod() {
        return boundMethod;
    }
    
    /** 设置解析期绑定的具体方法（由 Parser 的重载选择逻辑调用）。 */
    public void setBoundMethod(Method method) {
        this.boundMethod = method;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("MethodCallNode");
        if (boundMethod != null) {
            sb.append(" [bound: ");
            sb.append(boundMethod.getDeclaringClass().getSimpleName()).append(" ");

            sb.append(boundMethod.getName()).append("(");
            Class<?>[] params = boundMethod.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(")");
            sb.append("]");
        }
        sb.append("\n");
        sb.append(indent(indent + 1)).append("target: ");
        if (target == null) {
            sb.append("null (static call)\n");
        } else {
            sb.append("\n");
            sb.append(target.formatString(indent + 2)).append("\n");
        }
        sb.append(indent(indent + 1)).append("methodName: ").append(methodName).append("\n");
        sb.append(indent(indent + 1)).append("arguments: ").append(arguments.size()).append("\n");
        for (int i = 0; i < arguments.size(); i++) {
            sb.append(indent(indent + 2)).append("arg[").append(i).append("]:\n");
            sb.append(arguments.get(i).formatString(indent + 3)).append("\n");
        }
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode target;
        private String methodName;
        private List<ASTNode> arguments;
        
        public Builder target(ASTNode target) {
            this.target = target;
            return this;
        }
        
        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }
        
        public Builder arguments(List<ASTNode> arguments) {
            this.arguments = arguments;
            return this;
        }
        
        @Override
        public ASTNode build() {
            return new MethodCallNode(target, methodName, arguments, location);
        }
    }
}
