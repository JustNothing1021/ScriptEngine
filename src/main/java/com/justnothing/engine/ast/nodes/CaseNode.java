package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.List;

public class CaseNode extends ASTNode {
    private final ASTNode value;
    private final List<ASTNode> statements;
    
    private CaseNode(ASTNode value, List<ASTNode> statements, SourceLocation location) {
        super(location);
        this.value = value;
        this.statements = statements;
    }
    
    public ASTNode getValue() {
        return value;
    }
    
    public List<ASTNode> getStatements() {
        return statements;
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
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("CaseNode\n");
        sb.append(indent(indent + 1)).append("value:\n");
        sb.append(value.formatString(indent + 2)).append("\n");
        sb.append(indent(indent + 1)).append("statements: ").append(statements.size()).append("\n");
        for (int i = 0; i < statements.size(); i++) {
            sb.append(indent(indent + 1)).append("stmt[").append(i).append("]:\n");
            sb.append(statements.get(i).formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode value;
        private List<ASTNode> statements;

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        public Builder statements(List<ASTNode> statements) {
            this.statements = statements;
            return this;
        }

        @Override
        public ASTNode build() {
            return new CaseNode(value, statements, location);
        }
    }
}
