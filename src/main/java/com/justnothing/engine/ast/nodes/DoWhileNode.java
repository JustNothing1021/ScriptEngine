package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class DoWhileNode extends ASTNode {
    private final ASTNode body;
    private final ASTNode condition;
    
    private DoWhileNode(ASTNode body, ASTNode condition, SourceLocation location) {
        super(location);
        this.body = body;
        this.condition = condition;
    }
    
    public ASTNode getBody() {
        return body;
    }
    
    public ASTNode getCondition() {
        return condition;
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
        String sb = indent(indent) + "DoWhileNode\n" +
                indent(indent + 1) + "body:\n" +
                body.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "condition:\n" +
                condition.formatString(indent + 2) + "\n";
        return sb.stripTrailing();

    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode body;
        private ASTNode condition;

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }

        @Override
        public ASTNode build() {
            return new DoWhileNode(body, condition, location);
        }
    }
}
