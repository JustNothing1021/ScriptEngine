package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ArrayAssignmentNode extends ASTNode {
    
    private final ASTNode array;
    private final ASTNode index;
    private final ASTNode value;
    

    private ArrayAssignmentNode(ASTNode array, ASTNode index, ASTNode value, SourceLocation location) {
        super(location);
        this.array = array;
        this.index = index;
        this.value = value;
    }
    
    public ASTNode getArray() {
        return array;
    }
    
    public ASTNode getIndex() {
        return index;
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
        String sb = indent(indent) + "ArrayAssignmentNode\n" +
                indent(indent + 1) + "array:\n" +
                array.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "index:\n" +
                index.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "value:\n" +
                value.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode array;
        private ASTNode index;
        private ASTNode value;

        public Builder array(ASTNode array) {
            this.array = array;
            return this;
        }

        public Builder index(ASTNode index) {
            this.index = index;
            return this;
        }

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ArrayAssignmentNode(array, index, value, location);
        }
    }
}
