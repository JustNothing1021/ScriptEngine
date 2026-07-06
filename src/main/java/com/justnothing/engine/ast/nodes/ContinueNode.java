package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ContinueNode extends ASTNode {

    private ContinueNode(SourceLocation location) {
        super(location);
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        return indent(indent) + "ContinueNode";
    }

    @Override
    public String toString() {
        return "ContinueNode";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        @Override
        public ASTNode build() {
            return new ContinueNode(location);
        }
    }
}
