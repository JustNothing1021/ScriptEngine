package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class InstanceofNode extends ASTNode {
    
    private final ASTNode expression;
    private final String typeName;

    private InstanceofNode(ASTNode expression, String typeName, SourceLocation location) {
        super(location);
        this.expression = expression;
        this.typeName = typeName;
    }
    
    public ASTNode getExpression() {
        return expression;
    }
    
    public String getTypeName() {
        return typeName;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "InstanceofNode\n" +
                indent(indent + 1) + "typeName: " + typeName + "\n" +
                indent(indent + 1) + "expression:\n" +
                expression.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode expression;
        private String typeName;

        public Builder expression(ASTNode expression) {
            this.expression = expression;
            return this;
        }

        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        @Override
        public ASTNode build() {
            return new InstanceofNode(expression, typeName, location);
        }
    }
}
