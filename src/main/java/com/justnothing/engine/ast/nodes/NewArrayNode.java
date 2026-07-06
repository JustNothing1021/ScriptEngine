package com.justnothing.engine.ast.nodes;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class NewArrayNode extends ASTNode {
    private final Class<?> elementType;
    private final ASTNode size;
    private final List<ASTNode> sizes;
    
    private NewArrayNode(Class<?> elementType, ASTNode size, List<ASTNode> sizes, SourceLocation location) {
        super(location);
        this.elementType = elementType;
        this.size = size;
        this.sizes = sizes;
    }
    
    public Class<?> getElementType() {
        return elementType;
    }
    
    public ASTNode getSize() {
        return size;
    }
    
    public List<ASTNode> getSizes() {
        return sizes;
    }
    
    public int getDimensionCount() {
        return sizes.size();
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
        String sb = indent(indent) + "NewArrayNode\n" +
                indent(indent + 1) + "elementType: " + elementType.getSimpleName() + "\n" +
                indent(indent + 1) + "sizes:\n";
        for (ASTNode s : sizes) {
            sb += s.formatString(indent + 2) + "\n";
        }
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private Class<?> elementType;
        private ASTNode size;
        private List<ASTNode> sizes = Collections.emptyList();

        public Builder elementType(Class<?> elementType) {
            this.elementType = elementType;
            return this;
        }

        public Builder size(ASTNode size) {
            this.size = size;
            return this;
        }

        public Builder sizes(List<ASTNode> sizes) {
            this.sizes = sizes;
            return this;
        }

        @Override
        public ASTNode build() {
            return new NewArrayNode(elementType, size, sizes, location);
        }
    }
}
