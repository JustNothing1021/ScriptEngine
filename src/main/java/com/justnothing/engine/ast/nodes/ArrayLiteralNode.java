package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.List;

public class ArrayLiteralNode extends ASTNode {
    private final List<ASTNode> elements;
    private final Class<?> expectedElementType;
    private final ASTNode arrayLength;
    

    private ArrayLiteralNode(List<ASTNode> elements, Class<?> expectedElementType, ASTNode arrayLength, SourceLocation location) {
        super(location);
        this.elements = elements;
        this.expectedElementType = expectedElementType;
        this.arrayLength = arrayLength;
    }
    
    public List<ASTNode> getElements() {
        return elements;
    }
    
    public Class<?> getExpectedElementType() {
        return expectedElementType;
    }
    
    public ASTNode getArrayLength() {
        return arrayLength;
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
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ArrayLiteralNode\n");
        if (expectedElementType != null) {
            sb.append(indent(indent + 1)).append("expectedType: ").append(expectedElementType.getSimpleName()).append("\n");
        }
        if (arrayLength != null) {
            sb.append(indent(indent + 1)).append("arrayLength: ").append(arrayLength.formatString(indent + 2)).append("\n");
        }
        sb.append(indent(indent + 1)).append("elements: ").append(elements.size()).append("\n");
        for (int i = 0; i < elements.size(); i++) {
            sb.append(indent(indent + 1)).append("element[").append(i).append("]:\n");
            sb.append(elements.get(i).formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private List<ASTNode> elements;
        private Class<?> expectedElementType;
        private ASTNode arrayLength;

        public Builder elements(List<ASTNode> elements) {
            this.elements = elements;
            return this;
        }

        public Builder expectedElementType(Class<?> expectedElementType) {
            this.expectedElementType = expectedElementType;
            return this;
        }

        public Builder arrayLength(ASTNode arrayLength) {
            this.arrayLength = arrayLength;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ArrayLiteralNode(elements, expectedElementType, arrayLength, location);
        }
    }
}
