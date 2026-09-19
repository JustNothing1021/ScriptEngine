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
    /** 花括号初始化器 {@code new Type[] {…}}；无初始化器时为 null */
    private final ArrayLiteralNode initializer;

    private NewArrayNode(Class<?> elementType, ASTNode size, List<ASTNode> sizes,
                         ArrayLiteralNode initializer, SourceLocation location) {
        super(location);
        this.elementType = elementType;
        this.size = size;
        this.sizes = sizes;
        this.initializer = initializer;
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

    public ArrayLiteralNode getInitializer() {
        return initializer;
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
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("NewArrayNode\n");
        sb.append(indent(indent + 1)).append("elementType: ").append(elementType.getSimpleName()).append("\n");
        sb.append(indent(indent + 1)).append("sizes:\n");
        for (ASTNode s : sizes) {
            // 空维度（new Type[] {…} 里的 []）为 null
            sb.append(s == null
                    ? indent(indent + 2) + "<empty>\n"
                    : s.formatString(indent + 2) + "\n");
        }
        if (initializer != null) {
            sb.append(indent(indent + 1)).append("initializer:\n");
            sb.append(initializer.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private Class<?> elementType;
        private ASTNode size;
        private List<ASTNode> sizes = Collections.emptyList();
        private ArrayLiteralNode initializer;

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

        public Builder initializer(ArrayLiteralNode initializer) {
            this.initializer = initializer;
            return this;
        }

        @Override
        public ASTNode build() {
            return new NewArrayNode(elementType, size, sizes, initializer, location);
        }
    }
}
