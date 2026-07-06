package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class FieldAssignmentNode extends ASTNode {
    
    private final ASTNode target;
    private final String fieldName;
    private final ASTNode value;
    
    private FieldAssignmentNode(ASTNode target, String fieldName, ASTNode value, SourceLocation location) {
        super(location);
        this.target = target;
        this.fieldName = fieldName;
        this.value = value;
    }
    
    public ASTNode getTarget() {
        return target;
    }
    
    public String getFieldName() {
        return fieldName;
    }
    
    public ASTNode getValue() {
        return value;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "FieldAssignmentNode\n" +
                indent(indent + 1) + "fieldName: " + fieldName + "\n" +
                indent(indent + 1) + "target:\n" +
                target.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "value:\n" +
                value.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode target;
        private String fieldName;
        private ASTNode value;

        public Builder target(ASTNode target) {
            this.target = target;
            return this;
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        @Override
        public ASTNode build() {
            return new FieldAssignmentNode(target, fieldName, value, location);
        }
    }
}
