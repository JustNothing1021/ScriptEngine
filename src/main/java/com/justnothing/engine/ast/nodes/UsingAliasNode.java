package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class UsingAliasNode extends ASTNode {

    private final String aliasName;
    private final String fullClassName;


    private UsingAliasNode(String aliasName, String fullClassName, SourceLocation location) {
        super(location);
        this.aliasName = aliasName;
        this.fullClassName = fullClassName;
    }

    public String getAliasName() {
        return aliasName;
    }

    public String getFullClassName() {
        return fullClassName;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        return indent(indent) + "UsingAliasNode\n" +
                indent(indent + 1) + "alias: " + aliasName + "\n" +
                indent(indent + 1) + "target: " + fullClassName + "\n";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String aliasName;
        private String fullClassName;

        public Builder aliasName(String aliasName) {
            this.aliasName = aliasName;
            return this;
        }

        public Builder fullClassName(String fullClassName) {
            this.fullClassName = fullClassName;
            return this;
        }

        @Override
        public ASTNode build() {
            return new UsingAliasNode(aliasName, fullClassName, location);
        }
    }
}
