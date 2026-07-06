package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;

public class ResourceDeclaration {
    private final Class<?> type;
    private final String variableName;
    private final ASTNode initializer;
    private final SourceLocation location;
    private final boolean isReference;
    
    public ResourceDeclaration(Class<?> type, String variableName, ASTNode initializer, SourceLocation location) {
        this.type = type;
        this.variableName = variableName;
        this.initializer = initializer;
        this.location = location;
        this.isReference = false;
    }
    
    private ResourceDeclaration(String variableName, SourceLocation location) {
        this.type = null;
        this.variableName = variableName;
        this.initializer = null;
        this.location = location;
        this.isReference = true;
    }
    
    public Class<?> getType() {
        return type;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    public ASTNode getInitializer() {
        return initializer;
    }
    
    public SourceLocation getLocation() {
        return location;
    }
    
    public boolean isReference() {
        return isReference;
    }
    
    private String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }
    
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ResourceDeclaration\n");
        if (isReference) {
            sb.append(indent(indent + 1)).append("isReference: true\n");
            sb.append(indent(indent + 1)).append("variableName: ").append(variableName).append("\n");
        } else {
            sb.append(indent(indent + 1)).append("type: ").append(type != null ? type.getSimpleName() : "auto").append("\n");
            sb.append(indent(indent + 1)).append("variableName: ").append(variableName).append("\n");
            if (initializer != null) {
                sb.append(indent(indent + 1)).append("initializer:\n");
                sb.append(initializer.formatString(indent + 2)).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
