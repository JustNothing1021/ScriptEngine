package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class PipelineNode extends ASTNode {
    
    private final ASTNode input;
    private final ASTNode function;
    
    /**
     * @deprecated 使用 {@link Builder} 替代。
     */
    @Deprecated
    private PipelineNode(ASTNode input, ASTNode function, SourceLocation location) {
        super(location);
        this.input = input;
        this.function = function;
    }
    
    public ASTNode getInput() {
        return input;
    }
    
    public ASTNode getFunction() {
        return function;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "PipelineNode\n" +
                indent(indent + 1) + "input:\n" +
                input.formatString(indent + 2) +
                "\n" + indent(indent + 1) + "function:\n" +
                function.formatString(indent + 2);
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode input;
        private ASTNode function;

        public Builder input(ASTNode input) {
            this.input = input;
            return this;
        }

        public Builder function(ASTNode function) {
            this.function = function;
            return this;
        }

        @Override
        public ASTNode build() {
            return new PipelineNode(input, function, location);
        }
    }
}
