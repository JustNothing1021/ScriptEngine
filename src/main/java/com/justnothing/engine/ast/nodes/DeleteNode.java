package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class DeleteNode extends ASTNode {
    
    private final String variableName;
    private final boolean deleteAll;
    
    private DeleteNode(String variableName, SourceLocation location) {
        super(location);
        this.variableName = variableName;
        this.deleteAll = false;
    }
    
    private DeleteNode(boolean deleteAll, SourceLocation location) {
        super(location);
        this.variableName = null;
        this.deleteAll = deleteAll;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    public boolean isDeleteAll() {
        return deleteAll;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("DeleteNode\n");
        if (deleteAll) {
            sb.append(indent(indent + 1)).append("deleteAll: true\n");
        } else {
            sb.append(indent(indent + 1)).append("variableName: ").append(variableName).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String variableName;
        private boolean deleteAll;

        public Builder variableName(String variableName) {
            this.variableName = variableName;
            return this;
        }

        public Builder deleteAll(boolean deleteAll) {
            this.deleteAll = deleteAll;
            return this;
        }

        @Override
        public ASTNode build() {
            if (deleteAll) {
                return new DeleteNode(deleteAll, location);
            } else {
                return new DeleteNode(variableName, location);
            }
        }
    }
}
