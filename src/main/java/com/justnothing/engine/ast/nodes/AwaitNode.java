package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class AwaitNode extends ASTNode {
    
    private final ASTNode expression;
    
    private AwaitNode(ASTNode expression, SourceLocation location) {
        super(location);
        this.expression = expression;
    }
    
    public ASTNode getExpression() {
        return expression;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "AwaitNode\n" +
                indent(indent + 1) + "expression:\n" +
                expression.formatString(indent + 2);
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode expression;

        public Builder expression(ASTNode expression) {
            this.expression = expression;
            return this;
        }

        @Override
        public ASTNode build() {
            return new AwaitNode(expression, location);
        }
    }
}
