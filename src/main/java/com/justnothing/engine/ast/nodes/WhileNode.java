package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class WhileNode extends ASTNode {
    private final ASTNode condition;
    private final ASTNode body;
    

    private WhileNode(ASTNode condition, ASTNode body, SourceLocation location) {
        super(location);
        this.condition = condition;
        this.body = body;
    }
    
    public ASTNode getCondition() {
        return condition;
    }
    
    public ASTNode getBody() {
        return body;
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
        String sb = indent(indent) + "WhileNode\n" +
                indent(indent + 1) + "condition:\n" +
                condition.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "body:\n" +
                body.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode condition;
        private ASTNode body;

        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        @Override
        public ASTNode build() {
            return new WhileNode(condition, body, location);
        }
    }
}
