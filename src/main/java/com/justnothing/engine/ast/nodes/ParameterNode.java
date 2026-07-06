package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ParameterNode extends ASTNode {
    
    private final String parameterName;
    private final ClassReferenceNode type;
    
    /**
     * @deprecated 使用 {@link Builder} 替代。
     */
    @Deprecated
    private ParameterNode(String parameterName, ClassReferenceNode type, SourceLocation location) {
        super(location);
        this.parameterName = parameterName;
        this.type = type;
    }
    
    public String getParameterName() {
        return parameterName;
    }
    
    public ClassReferenceNode getType() {
        return type;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ParameterNode\n");
        sb.append(indent(indent + 1)).append("parameterName: ").append(parameterName).append("\n");
        sb.append(indent(indent + 1)).append("type: ").append(type.getTypeName()).append("\n");
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String parameterName;
        private ClassReferenceNode type;

        public Builder parameterName(String parameterName) {
            this.parameterName = parameterName;
            return this;
        }

        public Builder type(ClassReferenceNode type) {
            this.type = type;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ParameterNode(parameterName, type, location);
        }
    }
}