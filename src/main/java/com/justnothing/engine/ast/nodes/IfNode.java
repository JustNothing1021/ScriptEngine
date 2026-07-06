package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * if语句节点
 * <p>
 * 表示if-else条件语句。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class IfNode extends ASTNode {
    
    private final ASTNode condition;
    private final ASTNode thenBlock;
    private final ASTNode elseBlock;
    

    private IfNode(ASTNode condition, ASTNode thenBlock,
                ASTNode elseBlock, SourceLocation location) {
        super(location);
        this.condition = condition;
        this.thenBlock = thenBlock;
        this.elseBlock = elseBlock;
    }
    
    public ASTNode getCondition() {
        return condition;
    }
    
    public ASTNode getThenBlock() {
        return thenBlock;
    }
    
    public ASTNode getElseBlock() {
        return elseBlock;
    }
    
    public boolean hasElse() {
        return elseBlock != null;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("IfNode\n");
        sb.append(indent(indent + 1)).append("condition:\n");
        sb.append(condition.formatString(indent + 2)).append("\n");
        sb.append(indent(indent + 1)).append("then:\n");
        sb.append(thenBlock.formatString(indent + 2)).append("\n");
        if (elseBlock != null) {
            sb.append(indent(indent + 1)).append("else:\n");
            sb.append(elseBlock.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode condition;
        private ASTNode thenBlock;
        private ASTNode elseBlock;
        
        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }
        
        public Builder thenBlock(ASTNode thenBlock) {
            this.thenBlock = thenBlock;
            return this;
        }
        
        public Builder elseBlock(ASTNode elseBlock) {
            this.elseBlock = elseBlock;
            return this;
        }
        
        @Override
        public ASTNode build() {
            return new IfNode(condition, thenBlock, elseBlock, location);
        }
    }
}
