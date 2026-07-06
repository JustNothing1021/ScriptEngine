package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ClassDeclarationNode extends ASTNode {

    private final String className;
    private final ClassReferenceNode superClass;
    private final List<ClassReferenceNode> interfaces;
    private final List<FieldDeclarationNode> fields;
    private final List<MethodDeclarationNode> methods;
    private final List<ConstructorDeclarationNode> constructors;
    private final ClassModifiers modifiers;
    private final List<AnnotationNode> annotations;
    private boolean interfaceFlag;

    /** 泛型类型参数名列表（如 ["T", "E"] 表示 class Foo<T, E>）。空列表表示非泛型类。 */
    private final List<String> typeParameters;

    /** 泛型类型参数上界映射（如 {"T" → Number.class} 表示 T extends Number）。无界时对应值为 null。 */
    private final java.util.Map<String, ClassReferenceNode> typeParameterBounds;
    
    private ClassDeclarationNode(String className, ClassReferenceNode superClass,
                                 List<ClassReferenceNode> interfaces,
                                 List<String> typeParameters,
                                 Map<String, ClassReferenceNode> typeParameterBounds,
                                 SourceLocation location) {
        super(location);
        this.className = className;
        this.superClass = superClass;
        this.interfaces = interfaces != null ? interfaces : new ArrayList<>();
        this.fields = new ArrayList<>();
        this.methods = new ArrayList<>();
        this.constructors = new ArrayList<>();
        this.modifiers = new ClassModifiers();
        this.annotations = new ArrayList<>();
        this.interfaceFlag = false;
        this.typeParameters = typeParameters != null ? Collections.unmodifiableList(new ArrayList<>(typeParameters)) : Collections.emptyList();
        this.typeParameterBounds = typeParameterBounds != null ? Collections.unmodifiableMap(new java.util.HashMap<>(typeParameterBounds)) : Collections.emptyMap();
    }
    
    public void setInterface(boolean isInterface) {
        this.interfaceFlag = isInterface;
    }
    
    public boolean isInterface() {
        return interfaceFlag;
    }
    
    public String getClassName() {
        return className;
    }
    
    public ClassReferenceNode getSuperClass() {
        return superClass;
    }
    
    public List<ClassReferenceNode> getInterfaces() {
        return interfaces;
    }
    
    public List<FieldDeclarationNode> getFields() {
        return fields;
    }
    
    public List<MethodDeclarationNode> getMethods() {
        return methods;
    }
    
    public List<ConstructorDeclarationNode> getConstructors() {
        return constructors;
    }
    
    public ClassModifiers getModifiers() {
        return modifiers;
    }
    
    public List<AnnotationNode> getAnnotations() {
        return annotations;
    }

    /** 获取泛型类型参数名列表（如 ["T", "E"]）。空列表表示非泛型类。 */
    public List<String> getTypeParameters() {
        return typeParameters;
    }

    /** 是否为泛型类（有类型参数声明）。 */
    public boolean isGeneric() {
        return !typeParameters.isEmpty();
    }

    /** 获取泛型参数上界映射。key=参数名, value=上界类型（null 表示无界，即 extends Object）。 */
    public Map<String, ClassReferenceNode> getTypeParameterBounds() {
        return typeParameterBounds;
    }

    /** 获取指定泛型参数的上界类型。无上界时返回 null。 */
    public ClassReferenceNode getTypeParameterBound(String paramName) {
        return typeParameterBounds.get(paramName);
    }
    
    public void addField(FieldDeclarationNode field) {
        fields.add(field);
    }
    
    public void addMethod(MethodDeclarationNode method) {
        methods.add(method);
    }
    
    public void addConstructor(ConstructorDeclarationNode constructor) {
        constructors.add(constructor);
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
        sb.append(indent(indent)).append("ClassDeclarationNode\n");

        if (!annotations.isEmpty()) {
            sb.append(indent(indent + 1)).append("annotations:\n");
            for (AnnotationNode annotation : annotations) {
                sb.append(annotation.formatString(indent + 2)).append("\n");
            }
        }

        sb.append(indent(indent + 1)).append("className: ").append(className).append("\n");
        if (!typeParameters.isEmpty()) {
            sb.append(indent(indent + 1)).append("typeParameters: <");
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) sb.append(", ");
                String tp = typeParameters.get(i);
                sb.append(tp);
                ClassReferenceNode bound = typeParameterBounds.get(tp);
                if (bound != null) {
                    sb.append(" extends ").append(bound.getTypeName());
                }
            }
            sb.append(">\n");
        }
        if (superClass != null) {
            sb.append(indent(indent + 1)).append("superClass: ").append(superClass.getTypeName()).append("\n");
        }
        if (!interfaces.isEmpty()) {
            sb.append(indent(indent + 1)).append("interfaces: ");
            for (int i = 0; i < interfaces.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces.get(i).getTypeName());
            }
            sb.append("\n");
        }
        sb.append(indent(indent + 1)).append("modifiers: ").append(modifiers.toModifierString()).append("\n");

        sb.append(indent(indent + 1)).append("fields: ").append(fields.size()).append("\n");
        for (int i = 0; i < fields.size(); i++) {
            sb.append(indent(indent + 2)).append("field[").append(i).append("]:\n");
            sb.append(fields.get(i).formatString(indent + 3)).append("\n");
        }

        sb.append(indent(indent + 1)).append("constructors: ").append(constructors.size()).append("\n");
        for (int i = 0; i < constructors.size(); i++) {
            sb.append(indent(indent + 2)).append("constructor[").append(i).append("]:\n");
            sb.append(constructors.get(i).formatString(indent + 3)).append("\n");
        }

        sb.append(indent(indent + 1)).append("methods: ").append(methods.size()).append("\n");
        for (int i = 0; i < methods.size(); i++) {
            sb.append(indent(indent + 2)).append("method[").append(i).append("]:\n");
            sb.append(methods.get(i).formatString(indent + 3)).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String className;
        private ClassReferenceNode superClass;
        private List<ClassReferenceNode> interfaces;
        private List<FieldDeclarationNode> fields;
        private List<MethodDeclarationNode> methods;
        private List<ConstructorDeclarationNode> constructors;
        private ClassModifiers modifiers;
        private List<AnnotationNode> annotations;
        private boolean interfaceFlag;
        /** 泛型类型参数名列表（如 ["T", "E"]）。null 或空表示非泛型类。 */
        private List<String> typeParameters;
        /** 泛型参数上界映射。key=参数名, value=上界类型引用。 */
        private Map<String, ClassReferenceNode> typeParameterBounds;

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder superClass(ClassReferenceNode superClass) {
            this.superClass = superClass;
            return this;
        }

        public Builder interfaces(List<ClassReferenceNode> interfaces) {
            this.interfaces = interfaces;
            return this;
        }

        public Builder fields(List<FieldDeclarationNode> fields) {
            this.fields = fields;
            return this;
        }

        public Builder methods(List<MethodDeclarationNode> methods) {
            this.methods = methods;
            return this;
        }

        public Builder constructors(List<ConstructorDeclarationNode> constructors) {
            this.constructors = constructors;
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

        public Builder interfaceFlag(boolean interfaceFlag) {
            this.interfaceFlag = interfaceFlag;
            return this;
        }

        /** 设置泛型类型参数名列表。 */
        public Builder typeParameters(List<String> typeParameters) {
            this.typeParameters = typeParameters;
            return this;
        }

        /** 设置泛型参数上界映射。 */
        public Builder typeParameterBounds(Map<String, ClassReferenceNode> bounds) {
            this.typeParameterBounds = bounds;
            return this;
        }

        @Override
        public ASTNode build() {
            ClassDeclarationNode node = new ClassDeclarationNode(className, superClass, interfaces,
                    typeParameters, typeParameterBounds, location);
            if (fields != null) {
                for (FieldDeclarationNode f : fields) node.addField(f);
            }
            if (methods != null) {
                for (MethodDeclarationNode m : methods) node.addMethod(m);
            }
            if (constructors != null) {
                for (ConstructorDeclarationNode c : constructors) node.addConstructor(c);
            }
            if (annotations != null) {
                for (AnnotationNode a : annotations) node.addAnnotation(a);
            }
            if (modifiers != null) {
                if (modifiers.isPublic()) node.getModifiers().setPublic(true);
                if (modifiers.isPrivate()) node.getModifiers().setPrivate(true);
                if (modifiers.isProtected()) node.getModifiers().setProtected(true);
                if (modifiers.isStatic()) node.getModifiers().setStatic(true);
                if (modifiers.isFinal()) node.getModifiers().setFinal(true);
                if (modifiers.isAbstract()) node.getModifiers().setAbstract(true);
                if (modifiers.isNative()) node.getModifiers().setNative(true);
                if (modifiers.isSynchronized()) node.getModifiers().setSynchronized(true);
            }
            node.setInterface(interfaceFlag);
            return node;
        }
    }
}