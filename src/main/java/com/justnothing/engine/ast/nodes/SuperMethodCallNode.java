package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SuperMethodCallNode extends ASTNode {
    
    private final String methodName;
    private final List<ASTNode> arguments;
    

    private SuperMethodCallNode(String methodName, List<ASTNode> arguments, SourceLocation location) {
        super(location);
        this.methodName = methodName;
        this.arguments = arguments != null ?
            Collections.unmodifiableList(new ArrayList<>(arguments)) :
            Collections.emptyList();
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public List<ASTNode> getArguments() {
        return arguments;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("SuperMethodCallNode\n");
        sb.append(indent(indent + 1)).append("methodName: super.").append(methodName).append("\n");
        sb.append(indent(indent + 1)).append("arguments: ").append(arguments.size()).append("\n");
        for (int i = 0; i < arguments.size(); i++) {
            sb.append(indent(indent + 2)).append("arg[").append(i).append("]:\n");
            sb.append(arguments.get(i).formatString(indent + 3)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String methodName;
        private List<ASTNode> arguments;

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
            return new SuperMethodCallNode(methodName, arguments, location);
        }
    }
}
