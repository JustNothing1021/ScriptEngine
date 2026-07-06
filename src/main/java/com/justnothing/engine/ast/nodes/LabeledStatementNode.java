package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class LabeledStatementNode extends ASTNode {

    private final String label;
    private final ASTNode statement;


    private LabeledStatementNode(String label, ASTNode statement, SourceLocation location) {
        super(location);
        this.label = label;
        this.statement = statement;
    }

    public String getLabel() {
        return label;
    }

    public ASTNode getStatement() {
        return statement;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("LabeledStatementNode[label=").append(label).append("]\n");
        if (statement != null) {
            sb.append(statement.formatString(indent + 1));
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String toString() {
        return "LabeledStatementNode[label=" + label + "]";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String label;
        private ASTNode statement;

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder statement(ASTNode statement) {
            this.statement = statement;
            return this;
        }

        @Override
        public ASTNode build() {
            return new LabeledStatementNode(label, statement, location);
        }
    }
}
