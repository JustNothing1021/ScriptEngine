package com.justnothing.engine.ast.nodes;


import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ReturnNode extends ASTNode {
    private final ASTNode value;
    
    /**
     * @deprecated 使用 {@link Builder} 替代。
     */
    @Deprecated
    private ReturnNode(ASTNode value, SourceLocation location) {
        super(location);
        this.value = value;
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
        String ind = indent(indent);
        if (value != null) {
            return ind + "ReturnNode{\n" + value.formatString(indent + 1) + "\n" + ind + "}";
        }
        return ind + "ReturnNode{null}";
    }
    
    @Override
    public String toString() {
        return "ReturnNode{value=" + value + "}";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode value;

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ReturnNode(value, location);
        }
    }
}
