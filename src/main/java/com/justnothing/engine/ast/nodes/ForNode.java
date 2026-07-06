package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * for循环节点
 * <p>
 * 表示for循环，支持传统for循环和增强for循环。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class ForNode extends ASTNode {
    
    private final ASTNode initialization;
    private final ASTNode condition;
    private final ASTNode update;
    private final ASTNode body;

    

    private ForNode(ASTNode initialization, ASTNode condition, ASTNode update,
                 ASTNode body, SourceLocation location) {
        super(location);
        this.initialization = initialization;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }


    public ASTNode getInitialization() {
        return initialization;
    }
    
    public ASTNode getCondition() {
        return condition;
    }
    
    public ASTNode getUpdate() {
        return update;
    }
    
    public ASTNode getBody() {
        return body;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ForNode\n");

        sb.append(indent(indent + 1)).append("initialization:\n");
        if (initialization != null) {
            sb.append(initialization.formatString(indent + 2)).append("\n");
        } else {
            sb.append(indent(indent + 2)).append("null\n");
        }
        sb.append(indent(indent + 1)).append("condition:\n");
        if (condition != null) {
            sb.append(condition.formatString(indent + 2)).append("\n");
        } else {
            sb.append(indent(indent + 2)).append("null\n");
        }
        sb.append(indent(indent + 1)).append("update:\n");
        if (update != null) {
            sb.append(update.formatString(indent + 2)).append("\n");
        } else {
            sb.append(indent(indent + 2)).append("null\n");
        }
        sb.append(indent(indent + 1)).append("body:\n");
        sb.append(body.formatString(indent + 2)).append("\n");
        
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode initialization;
        private ASTNode condition;
        private ASTNode update;
        private ASTNode body;
        
        public Builder initialization(ASTNode initialization) {
            this.initialization = initialization;
            return this;
        }
        
        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }
        
        public Builder update(ASTNode update) {
            this.update = update;
            return this;
        }
        
        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ForNode(initialization, condition, update, body, location);
        }
    }
}
