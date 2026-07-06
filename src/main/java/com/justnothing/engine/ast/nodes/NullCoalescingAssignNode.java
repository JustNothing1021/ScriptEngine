package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class NullCoalescingAssignNode extends ASTNode {
    
    private final String variableName;
    private final ASTNode value;
    
    private NullCoalescingAssignNode(String variableName, ASTNode value, SourceLocation location) {
        super(location);
        this.variableName = variableName;
        this.value = value;
    }
    
    public String getVariableName() {
        return variableName;
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
        String sb = indent(indent) + "NullCoalescingAssignNode\n" +
                indent(indent + 1) + "variable: " + variableName + "\n" +
                indent(indent + 1) + "value:\n" +
                value.formatString(indent + 2);
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String variableName;
        private ASTNode value;

        public Builder variableName(String variableName) {
            this.variableName = variableName;
            return this;
        }

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        @Override
        public ASTNode build() {
            return new NullCoalescingAssignNode(variableName, value, location);
        }
    }
}
