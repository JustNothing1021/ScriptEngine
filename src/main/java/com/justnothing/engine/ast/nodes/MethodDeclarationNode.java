package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.List;

public class MethodDeclarationNode extends ASTNode {
    
    private final String methodName;
    private final ClassReferenceNode returnType;
    private final List<ParameterNode> parameters;
    private final ASTNode body;
    private final ClassModifiers modifiers;
    private final List<AnnotationNode> annotations;
    

    private MethodDeclarationNode(String methodName, ClassReferenceNode returnType,
                                  List<ParameterNode> parameters, ASTNode body,
                                  ClassModifiers modifiers, SourceLocation location) {
        super(location);
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameters = parameters != null ? parameters : new ArrayList<>();
        this.body = body;
        this.modifiers = modifiers != null ? modifiers : new ClassModifiers();
        this.annotations = new ArrayList<>();
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public ClassReferenceNode getReturnType() {
        return returnType;
    }
    
    public List<ParameterNode> getParameters() {
        return parameters;
    }
    
    public ASTNode getBody() {
        return body;
    }
    
    public ClassModifiers getModifiers() {
        return modifiers;
    }
    
    public List<AnnotationNode> getAnnotations() {
        return annotations;
    }
    
    public void addAnnotation(AnnotationNode annotation) {
        annotations.add(annotation);
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("MethodDeclarationNode\n");
        
        if (!annotations.isEmpty()) {
            sb.append(indent(indent + 1)).append("annotations:\n");
            for (AnnotationNode annotation : annotations) {
                sb.append(annotation.formatString(indent + 2)).append("\n");
            }
        }
        
        sb.append(indent(indent + 1)).append("methodName: ").append(methodName).append("\n");
        sb.append(indent(indent + 1)).append("returnType: ").append(returnType != null ? returnType.getTypeName() : "void").append("\n");
        sb.append(indent(indent + 1)).append("modifiers: ").append(modifiers.toModifierString()).append("\n");
        sb.append(indent(indent + 1)).append("parameters: ").append(parameters.size()).append("\n");
        for (int i = 0; i < parameters.size(); i++) {
            sb.append(indent(indent + 2)).append("param[").append(i).append("]:\n");
            sb.append(parameters.get(i).formatString(indent + 3)).append("\n");
        }
        if (body != null) {
            sb.append(indent(indent + 1)).append("body:\n");
            sb.append(body.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String methodName;
        private ClassReferenceNode returnType;
        private List<ParameterNode> parameters;
        private ASTNode body;
        private ClassModifiers modifiers;

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder returnType(ClassReferenceNode returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder parameters(List<ParameterNode> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        public Builder modifiers(ClassModifiers modifiers) {
            this.modifiers = modifiers;
            return this;
        }

        @Override
        public ASTNode build() {
            return new MethodDeclarationNode(methodName, returnType, parameters, body, modifiers, location);
        }
    }
}