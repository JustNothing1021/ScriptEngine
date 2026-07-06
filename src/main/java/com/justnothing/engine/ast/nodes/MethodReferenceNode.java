package com.justnothing.engine.ast.nodes;



import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class MethodReferenceNode extends ASTNode {
    private final ASTNode target;
    private final String methodName;
    private final List<GenericType> typeArguments;
    private Class<?> functionalInterfaceType;
    private Method boundMethod;  // 绑定的重载方法（解析期确定）

    private MethodReferenceNode(ASTNode target, String methodName, List<GenericType> typeArguments, SourceLocation location) {
        super(location);
        this.target = target;
        this.methodName = methodName;
        this.typeArguments = typeArguments != null ?
            Collections.unmodifiableList(typeArguments) :
            Collections.emptyList();
    }
    
    public ASTNode getTarget() {
        return target;
    }
    
    public String getMethodName() {
        return methodName;
    }

    public List<GenericType> getTypeArguments() {
        return typeArguments;
    }

    public Class<?> getFunctionalInterfaceType() {
        return functionalInterfaceType;
    }

    public void setFunctionalInterfaceType(Class<?> functionalInterfaceType) {
        this.functionalInterfaceType = functionalInterfaceType;
    }

    public Method getBoundMethod() {
        return boundMethod;
    }

    public void setBoundMethod(Method boundMethod) {
        this.boundMethod = boundMethod;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString() {
        return formatString(0);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("MethodReferenceNode\n");
        sb.append(indent(indent + 1)).append("methodName: ").append(methodName).append("\n");
        if (functionalInterfaceType != null) {
            sb.append(indent(indent + 1)).append("targetInterface: ").append(functionalInterfaceType.getName()).append("\n");
        }
        sb.append(indent(indent + 1)).append("target:\n");
        sb.append(target.formatString(indent + 2)).append("\n");
        return sb.toString().stripTrailing();
    }


    @Override
    public String toString() {
        return "MethodReferenceNode[" +
                "target=" + target +
                ", methodName='" + methodName + '\'' +
                "]";
           }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode target;
        private String methodName;
        private List<GenericType> typeArguments;

        public Builder target(ASTNode target) {
            this.target = target;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder typeArguments(List<GenericType> typeArguments) {
            this.typeArguments = typeArguments;
            return this;
        }

        @Override
        public ASTNode build() {
            return new MethodReferenceNode(target, methodName, typeArguments, location);
        }
    }
}
