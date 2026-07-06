package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class SafeFieldAccessNode extends ASTNode {
    
    private final ASTNode target;
    private final String fieldName;
    

    private SafeFieldAccessNode(ASTNode target, String fieldName, SourceLocation location) {
        super(location);
        this.target = target;
        this.fieldName = fieldName;
    }
    
    public ASTNode getTarget() {
        return target;
    }
    
    public String getFieldName() {
        return fieldName;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "SafeFieldAccessNode\n" +
                indent(indent + 1) + "target:\n" +
                target.formatString(indent + 2) +
                "\n" + indent(indent + 1) + "field: " + fieldName;
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode target;
        private String fieldName;

        public Builder target(ASTNode target) {
            this.target = target;
            return this;
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        @Override
        public ASTNode build() {
            return new SafeFieldAccessNode(target, fieldName, location);
        }
    }
}
