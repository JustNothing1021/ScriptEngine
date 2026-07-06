package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * 字面量节点
 * <p>
 * 表示各种字面量值，如数字、字符串、布尔值、null等。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class LiteralNode extends ASTNode {
    
    private final Object value;
    private final Class<?> type;
    

    private LiteralNode(Object value, Class<?> type, SourceLocation location) {
        super(location);
        this.value = value;
        this.type = type;
    }
    
    public Object getValue() {
        return value;
    }
    
    public Class<?> getType() {
        return type;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent));
        sb.append("LiteralNode: ");
        
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Character) {
            sb.append("'").append(value).append("'");
        } else if (value instanceof String) {
            sb.append("\"").append(value).append("\"");
        } else {
            sb.append(value);
        }
        
        sb.append(" (").append(type != null ? type.getSimpleName() : "void").append(")");
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private Object value;
        private Class<?> type;
        
        public Builder value(Object value) {
            this.value = value;
            return this;
        }
        
        public Builder type(Class<?> type) {
            this.type = type;
            return this;
        }
        
        @Override
        public ASTNode build() {
            return new LiteralNode(value, type, location);
        }
    }
}
