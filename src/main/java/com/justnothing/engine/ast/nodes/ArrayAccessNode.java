package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ArrayAccessNode extends ASTNode {
    private final ASTNode array;
    private final ASTNode index;
    
    private ArrayAccessNode(ASTNode array, ASTNode index, SourceLocation location) {
        super(location);
        this.array = array;
        this.index = index;
    }
    
    public ASTNode getArray() {
        return array;
    }
    
    public ASTNode getIndex() {
        return index;
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
        String sb = indent(indent) + "ArrayAccessNode\n" +
                indent(indent + 1) + "array:\n" +
                array.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "index:\n" +
                index.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode array;
        private ASTNode index;

        public Builder array(ASTNode array) {
            this.array = array;
            return this;
        }

        public Builder index(ASTNode index) {
            this.index = index;
            return this;
        }

        @Override
        public ArrayAccessNode build() {
            return new ArrayAccessNode(array, index, location);
        }
    }
}
