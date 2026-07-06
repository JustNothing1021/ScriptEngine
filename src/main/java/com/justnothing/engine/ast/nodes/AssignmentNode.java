package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * 赋值节点
 * <p>
 * 表示变量赋值操作。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class AssignmentNode extends ASTNode {
    
    private final String variableName;
    private final ASTNode value;
    private final boolean isDeclaration;
    private final GenericType declaredType;
    private final boolean isFinal;
    
    public AssignmentNode(String variableName, ASTNode value, boolean isDeclaration, 
                        GenericType declaredType, SourceLocation location) {
        this(variableName, value, isDeclaration, declaredType, false, location);
    }
    
    private AssignmentNode(String variableName, ASTNode value, boolean isDeclaration, 
                        GenericType declaredType, boolean isFinal, SourceLocation location) {
        super(location);
        this.variableName = variableName;
        this.value = value;
        this.isDeclaration = isDeclaration;
        this.declaredType = declaredType;
        this.isFinal = isFinal;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    public ASTNode getValue() {
        return value;
    }
    
    public boolean isDeclaration() {
        return isDeclaration;
    }
    
    public GenericType getDeclaredType() {
        return declaredType;
    }
    
    public Class<?> getDeclaredClass() {
        return declaredType != null ? declaredType.getRuntimeType() : null;
    }
    
    public boolean isFinal() {
        return isFinal;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("AssignmentNode\n");
        sb.append(indent(indent + 1)).append("variable: ").append(variableName).append("\n");
        sb.append(indent(indent + 1)).append("type: ").append(declaredType != null ? declaredType.getTypeName() : "null").append("\n");
        sb.append(indent(indent + 1)).append("isDeclaration: ").append(isDeclaration).append("\n");
        sb.append(indent(indent + 1)).append("isFinal: ").append(isFinal).append("\n");
        if (value == null) {
            sb.append(indent(indent + 1)).append("value: null\n");
        } else {
            sb.append(indent(indent + 1)).append("value:\n");
            sb.append(value.formatString(indent + 2));
        }
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private String variableName;
        private ASTNode value;
        private boolean isDeclaration;
        private GenericType declaredType;
        private boolean isFinal;

        public Builder variableName(String variableName) {
            this.variableName = variableName;
            return this;
        }

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }
        
        public Builder isFinal(boolean isFinal) {
            this.isFinal = isFinal;
            return this;
        }

        public Builder isDeclaration(boolean isDeclaration) {
            this.isDeclaration = isDeclaration;
            return this;
        }

        public Builder declaredType(GenericType declaredType) {
            this.declaredType = declaredType;
            return this;
        }

        @Override
        public ASTNode build() {
            return new AssignmentNode(variableName, value, isDeclaration, declaredType, isFinal, location);
        }
    }
}
