package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class BreakNode extends ASTNode {

    private final String label;


    private BreakNode(String label, SourceLocation location) {
        super(location);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLabeled() {
        return label != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        if (label != null) {
            return indent(indent) + "BreakNode[label=" + label + "]";
        }
        return indent(indent) + "BreakNode";
    }

    @Override
    public String toString() {
        if (label != null) {
            return "BreakNode[label=" + label + "]";
        }
        return "BreakNode";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String label;

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        @Override
        public ASTNode build() {
            return new BreakNode(label, location);
        }
    }
}
