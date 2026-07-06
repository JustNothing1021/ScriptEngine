package com.justnothing.engine.ast.nodes;

import java.lang.reflect.Field;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class FieldAccessNode extends ASTNode {
    private final ASTNode target;
    private final String fieldName;
    private Field boundField;  // 解析期绑定的反射字段对象
    
    private FieldAccessNode(ASTNode target, String fieldName, SourceLocation location) {
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

    public java.lang.reflect.Field getBoundField() {
        return boundField;
    }

    public void setBoundField(java.lang.reflect.Field field) {
        this.boundField = field;
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
        String sb = indent(indent) + "FieldAccessNode\n" +
                indent(indent + 1) + "fieldName: " + fieldName + "\n" +
                indent(indent + 1) + "target:\n" +
                target.formatString(indent + 2) + "\n";
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
        public FieldAccessNode build() {
            return new FieldAccessNode(target, fieldName, location);
        }
    }
}
