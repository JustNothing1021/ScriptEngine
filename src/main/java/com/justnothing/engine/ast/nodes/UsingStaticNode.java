package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class UsingStaticNode extends ASTNode {
    
    private final String className;
    
    private UsingStaticNode(String className, SourceLocation location) {
        super(location);
        this.className = className;
    }
    
    public String getClassName() {
        return className;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        return indent(indent) + "UsingStaticNode\n" +
                indent(indent + 1) + "className: " + className + "\n";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String className;

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        @Override
        public ASTNode build() {
            return new UsingStaticNode(className, location);
        }
    }
}
