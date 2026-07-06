package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConstructorCallNode extends ASTNode {
    
    private final GenericType type;
    private final List<ASTNode> arguments;
    private final ASTNode arrayInitializer;
    private final ClassDeclarationNode anonymousClass;

    private ConstructorCallNode(GenericType type, List<ASTNode> arguments,
                             ASTNode arrayInitializer, SourceLocation location) {
        super(location);
        this.type = type;
        this.arguments = arguments != null ?
            List.copyOf(arguments) :
            Collections.emptyList();
        this.arrayInitializer = arrayInitializer;
        this.anonymousClass = null;
    }
    
    private ConstructorCallNode(GenericType type, List<ASTNode> arguments,
                             ClassDeclarationNode anonymousClass, SourceLocation location) {
        super(location);
        this.type = type;
        this.arguments = arguments != null ? 
            Collections.unmodifiableList(new ArrayList<>(arguments)) : 
            Collections.emptyList();
        this.arrayInitializer = null;
        this.anonymousClass = anonymousClass;
    }
    
    public GenericType getType() {
        return type;
    }
    
    public String getClassName() {
        String orig = type.getOriginalTypeName();
        if (orig != null) return orig;
        Class<?> raw = type.getRawType();
        return raw != null ? raw.getTypeName() : type.getTypeName();
    }
    
    public List<ASTNode> getArguments() {
        return arguments;
    }
    
    public ASTNode getArrayInitializer() {
        return arrayInitializer;
    }
    
    public ClassDeclarationNode getAnonymousClass() {
        return anonymousClass;
    }
    
    public boolean isAnonymousClass() {
        return anonymousClass != null;
    }
    
    public boolean isArrayConstructor() {
        return type.isArray() || arrayInitializer != null;
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ConstructorCallNode\n");
        sb.append(indent(indent + 1)).append("className: ").append(type.getTypeName()).append("\n");
        if (type.getOriginalTypeName() != null) {
            sb.append(indent(indent + 1)).append("originalTypeName: ").append(type.getOriginalTypeName()).append("\n");
        }
        sb.append(indent(indent + 1)).append("arguments: ").append(arguments.size()).append("\n");
        if (arrayInitializer != null) {
            sb.append(indent(indent + 1)).append("arrayInitializer:\n");
            sb.append(arrayInitializer.formatString(indent + 2));
        }
        if (anonymousClass != null) {
            sb.append(indent(indent + 1)).append("anonymousClass:\n");
            sb.append(anonymousClass.formatString(indent + 2));
        }
        return sb.toString().stripTrailing();
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private GenericType type;
        private List<ASTNode> arguments;
        private ASTNode arrayInitializer;
        private ClassDeclarationNode anonymousClass;

        public Builder type(GenericType type) {
            this.type = type;
            return this;
        }

        public Builder arguments(List<ASTNode> arguments) {
            this.arguments = arguments;
            return this;
        }

        public Builder arrayInitializer(ASTNode arrayInitializer) {
            this.arrayInitializer = arrayInitializer;
            return this;
        }

        public Builder anonymousClass(ClassDeclarationNode anonymousClass) {
            this.anonymousClass = anonymousClass;
            return this;
        }

        @Override
        public ASTNode build() {
            if (anonymousClass != null) {
                return new ConstructorCallNode(type, arguments, anonymousClass, location);
            } else {
                return new ConstructorCallNode(type, arguments, arrayInitializer, location);
            }
        }
    }
}
