package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ImportNode extends ASTNode {
    
    private final String packageName;
    

    private ImportNode(String packageName, SourceLocation location) {
        super(location);
        this.packageName = packageName;
    }
    
    public String getPackageName() {
        return packageName;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "ImportNode\n" +
                indent(indent + 1) + "packageName: " + packageName + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String packageName;

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ImportNode(packageName, location);
        }
    }
}
