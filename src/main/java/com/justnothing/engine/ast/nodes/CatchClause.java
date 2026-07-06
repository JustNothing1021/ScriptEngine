package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;

import java.util.List;

public class CatchClause {
    private final List<Class<?>> exceptionTypes;
    private final String variableName;
    private final ASTNode body;
    private final SourceLocation location;
    
    private CatchClause(List<Class<?>> exceptionTypes, String variableName, ASTNode body, SourceLocation location) {
        this.exceptionTypes = exceptionTypes;
        this.variableName = variableName;
        this.body = body;
        this.location = location;
    }
    
    public List<Class<?>> getExceptionTypes() {
        return exceptionTypes;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    public ASTNode getBody() {
        return body;
    }
    
    public SourceLocation getLocation() {
        return location;
    }
    
    private String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }
    
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("CatchClause\n");
        
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < exceptionTypes.size(); i++) {
            if (i > 0) types.append(" | ");
            types.append(exceptionTypes.get(i).getSimpleName());
        }
        sb.append(indent(indent + 1)).append("exceptionTypes: ").append(types).append("\n");
        sb.append(indent(indent + 1)).append("variableName: ").append(variableName).append("\n");
        sb.append(indent(indent + 1)).append("body:\n");
        sb.append(body.formatString(indent + 2)).append("\n");
        return sb.toString().stripTrailing();
    }

    public static class Builder {
        private List<Class<?>> exceptionTypes;
        private String variableName;
        private ASTNode body;
        private SourceLocation location;

        public Builder exceptionTypes(List<Class<?>> exceptionTypes) {
            this.exceptionTypes = exceptionTypes;
            return this;
        }

        public Builder variableName(String variableName) {
            this.variableName = variableName;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        public Builder location(SourceLocation location) {
            this.location = location;
            return this;
        }

        public CatchClause build() {
            return new CatchClause(exceptionTypes, variableName, body, location);
        }
    }
}
