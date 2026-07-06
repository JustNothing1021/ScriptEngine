package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class AsyncNode extends ASTNode {
    
    private final ASTNode expression;
    

    private AsyncNode(ASTNode expression, SourceLocation location) {
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
        String sb = indent(indent) + "AsyncNode\n" +
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
        public AsyncNode build() {
            return new AsyncNode(expression, location);
        }
    }
}
