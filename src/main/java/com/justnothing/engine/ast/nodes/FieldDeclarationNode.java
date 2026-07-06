package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.List;

public class FieldDeclarationNode extends ASTNode {
    
    private final String fieldName;
    private final ClassReferenceNode type;
    private final ASTNode initialValue;
    private final ClassModifiers modifiers;
    private final List<AnnotationNode> annotations;
    
    private FieldDeclarationNode(String fieldName, ClassReferenceNode type, ASTNode initialValue,
                                 ClassModifiers modifiers, SourceLocation location) {
        super(location);
        this.fieldName = fieldName;
        this.type = type;
        this.initialValue = initialValue;
        this.modifiers = modifiers != null ? modifiers : new ClassModifiers();
        this.annotations = new ArrayList<>();
    }
    
    public String getFieldName() {
        return fieldName;
    }
    
    public ClassReferenceNode getType() {
        return type;
    }
    
    public ASTNode getInitialValue() {
        return initialValue;
    }
    
    public ClassModifiers getModifiers() {
        return modifiers;
    }
    
    public List<AnnotationNode> getAnnotations() {
        return annotations;
    }
    
    public void addAnnotation(AnnotationNode annotation) {
        annotations.add(annotation);
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("FieldDeclarationNode\n");
        
        if (!annotations.isEmpty()) {
            sb.append(indent(indent + 1)).append("annotations:\n");
            for (AnnotationNode annotation : annotations) {
                sb.append(annotation.formatString(indent + 2)).append("\n");
            }
        }
        
        sb.append(indent(indent + 1)).append("fieldName: ").append(fieldName).append("\n");
        sb.append(indent(indent + 1)).append("type: ").append(type.getTypeName()).append("\n");
        sb.append(indent(indent + 1)).append("modifiers: ").append(modifiers.toModifierString()).append("\n");
        if (initialValue != null) {
            sb.append(indent(indent + 1)).append("initialValue:\n");
            sb.append(initialValue.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String fieldName;
        private ClassReferenceNode type;
        private ASTNode initialValue;
        private ClassModifiers modifiers;
        private List<AnnotationNode> annotations;

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder type(ClassReferenceNode type) {
            this.type = type;
            return this;
        }

        public Builder initialValue(ASTNode initialValue) {
            this.initialValue = initialValue;
            return this;
        }

        public Builder modifiers(ClassModifiers modifiers) {
            this.modifiers = modifiers;
            return this;
        }

        public Builder annotations(List<AnnotationNode> annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public ASTNode build() {
            FieldDeclarationNode node = new FieldDeclarationNode(fieldName, type, initialValue, modifiers, location);
            if (annotations != null) {
                for (AnnotationNode a : annotations) node.addAnnotation(a);
            }
            return node;
        }
    }
}