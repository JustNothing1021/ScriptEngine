package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.List;

public class TryNode extends ASTNode {
    private final List<ResourceDeclaration> resources;
    private final ASTNode tryBlock;
    private final List<CatchClause> catchClauses;
    private final ASTNode finallyBlock;
    

    private TryNode(List<ResourceDeclaration> resources, ASTNode tryBlock, List<CatchClause> catchClauses, ASTNode finallyBlock, SourceLocation location) {
        super(location);
        this.resources = resources;
        this.tryBlock = tryBlock;
        this.catchClauses = catchClauses;
        this.finallyBlock = finallyBlock;
    }
    
    public List<ResourceDeclaration> getResources() {
        return resources;
    }
    
    public ASTNode getTryBlock() {
        return tryBlock;
    }
    
    public List<CatchClause> getCatchClauses() {
        return catchClauses;
    }
    
    public ASTNode getFinallyBlock() {
        return finallyBlock;
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
        sb.append(indent(indent)).append("TryNode\n");
        if (!resources.isEmpty()) {
            sb.append(indent(indent + 1)).append("resources: ").append(resources.size()).append("\n");
            for (int i = 0; i < resources.size(); i++) {
                sb.append(indent(indent + 1)).append("resource[").append(i).append("]:\n");
                sb.append(resources.get(i).formatString(indent + 2)).append("\n");
            }
        }
        sb.append(indent(indent + 1)).append("tryBlock:\n");
        sb.append(tryBlock.formatString(indent + 2)).append("\n");
        sb.append(indent(indent + 1)).append("catchClauses: ").append(catchClauses.size()).append("\n");
        for (int i = 0; i < catchClauses.size(); i++) {
            sb.append(indent(indent + 1)).append("catch[").append(i).append("]:\n");
            sb.append(catchClauses.get(i).formatString(indent + 2)).append("\n");
        }
        if (finallyBlock != null) {
            sb.append(indent(indent + 1)).append("finallyBlock:\n");
            sb.append(finallyBlock.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private List<ResourceDeclaration> resources;
        private ASTNode tryBlock;
        private List<CatchClause> catchClauses;
        private ASTNode finallyBlock;

        public Builder resources(List<ResourceDeclaration> resources) {
            this.resources = resources;
            return this;
        }

        public Builder tryBlock(ASTNode tryBlock) {
            this.tryBlock = tryBlock;
            return this;
        }

        public Builder catchClauses(List<CatchClause> catchClauses) {
            this.catchClauses = catchClauses;
            return this;
        }

        public Builder finallyBlock(ASTNode finallyBlock) {
            this.finallyBlock = finallyBlock;
            return this;
        }

        @Override
        public ASTNode build() {
            return new TryNode(resources, tryBlock, catchClauses, finallyBlock, location);
        }
    }
}
