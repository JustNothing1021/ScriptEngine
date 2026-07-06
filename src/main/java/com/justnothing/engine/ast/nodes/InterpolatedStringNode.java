package com.justnothing.engine.ast.nodes;

import java.util.ArrayList;
import java.util.List;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class InterpolatedStringNode extends ASTNode {
    
    public static class Part {
        private final boolean isExpression;
        private final String literalText;
        private final ASTNode expression;
        
        public Part(String literalText) {
            this.isExpression = false;
            this.literalText = literalText;
            this.expression = null;
        }
        
        public Part(ASTNode expression) {
            this.isExpression = true;
            this.literalText = null;
            this.expression = expression;
        }
        
        public boolean isExpression() {
            return isExpression;
        }
        
        public String getLiteralText() {
            return literalText;
        }
        
        public ASTNode getExpression() {
            return expression;
        }
    }
    
    private final List<Part> parts;
    

    private InterpolatedStringNode(List<Part> parts, SourceLocation location) {
        super(location);
        this.parts = new ArrayList<>(parts);
    }
    
    public List<Part> getParts() {
        return parts;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("InterpolatedStringNode\n");
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            sb.append(indent(indent + 1)).append("part[").append(i).append("]: ");
            if (part.isExpression()) {
                sb.append("\n");
                sb.append(part.getExpression().formatString(indent + 2));
            } else {
                sb.append("\"").append(escapeString(part.getLiteralText())).append("\"\n");
            }
        }
        return sb.toString().stripTrailing();
    }
    
    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\"", "\\\"");
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private final List<Part> parts = new ArrayList<>();
        
        public Builder addLiteral(String text) {
            parts.add(new Part(text));
            return this;
        }
        
        public Builder addExpression(ASTNode expression) {
            parts.add(new Part(expression));
            return this;
        }
        
        @Override
        public ASTNode build() {
            return new InterpolatedStringNode(parts, location);
        }
    }
}
