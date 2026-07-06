package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 类引用节点。
 **/
public class ClassReferenceNode extends ASTNode {

    // 基本类型名称常量（消除 switch 中的重复字面量）
    private static final String T_INT = "int";
    private static final String T_LONG = "long";
    private static final String T_FLOAT = "float";
    private static final String T_DOUBLE = "double";
    private static final String T_BOOLEAN = "boolean";
    private static final String T_CHAR = "char";
    private static final String T_BYTE = "byte";
    private static final String T_SHORT = "short";
    private static final String T_VOID = "void";

    private final String originalTypeName;
    private final Class<?> resolvedClass;
    private final boolean isPrimitive;
    private final int arrayDepth;
    private final List<ClassReferenceNode> typeArguments;
    
    private ClassReferenceNode(String originalTypeName, Class<?> resolvedClass,
                               boolean isPrimitive, int arrayDepth,
                               List<ClassReferenceNode> typeArguments, SourceLocation location) {
        super(location);
        this.originalTypeName = originalTypeName;
        this.resolvedClass = resolvedClass;
        this.isPrimitive = isPrimitive;
        this.arrayDepth = arrayDepth;
        this.typeArguments = typeArguments != null ? 
            Collections.unmodifiableList(new ArrayList<>(typeArguments)) : 
            Collections.emptyList();
    }
    
    public static ClassReferenceNode of(String originalTypeName, Class<?> resolvedClass, 
                                         boolean isPrimitive, SourceLocation location) {
        return new ClassReferenceNode(originalTypeName, resolvedClass, isPrimitive, 0, null, location);
    }
    
    public static ClassReferenceNode arrayOf(ClassReferenceNode elementType, int dimensions) {
        return new ClassReferenceNode(
            elementType.originalTypeName,
            elementType.resolvedClass,
            elementType.isPrimitive,
            dimensions,
            elementType.typeArguments,
            elementType.getLocation()
        );
    }
    
    public static ClassReferenceNode generic(String originalTypeName, Class<?> resolvedClass,
                                              boolean isPrimitive, List<ClassReferenceNode> typeArguments,
                                              SourceLocation location) {
        return new ClassReferenceNode(originalTypeName, resolvedClass, isPrimitive, 0, typeArguments, location);
    }
    
    public String getOriginalTypeName() {
        return originalTypeName;
    }
    
    public Class<?> getResolvedClass() {
        return resolvedClass;
    }
    
    public boolean isPrimitive() {
        return isPrimitive;
    }
    
    public int getArrayDepth() {
        return arrayDepth;
    }
    
    public boolean isArray() {
        return arrayDepth > 0;
    }
    
    public boolean isGeneric() {
        return !typeArguments.isEmpty();
    }
    
    public List<ClassReferenceNode> getTypeArguments() {
        return typeArguments;
    }
    
    /**
     * 生成对应的 GenericType 对象
     * @return GenericType 对象
     */
    public GenericType toGenericType() {
        List<GenericType> genericTypeArguments = new ArrayList<>();
        for (ClassReferenceNode arg : typeArguments) {
            genericTypeArguments.add(arg.toGenericType());
        }
        return new GenericType(resolvedClass, genericTypeArguments, arrayDepth, originalTypeName);
    }
    
    public String getSimpleName() {
        if (resolvedClass == null) {
            return originalTypeName;
        }
        String name = resolvedClass.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }
    
    public String getTypeName() {
        StringBuilder sb = new StringBuilder();
        sb.append(originalTypeName);
        
        if (!typeArguments.isEmpty()) {
            sb.append("<");
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments.get(i).getTypeName());
            }
            sb.append(">");
        }
        
        for (int i = 0; i < arrayDepth; i++) {
            sb.append("[]");
        }
        
        return sb.toString();
    }
    
    public String getInternalName() {
        if (isPrimitive) {
            return getPrimitiveInternalName(resolvedClass.getName());
        }
        return resolvedClass.getName().replace('.', '/');
    }
    
    public String getDescriptor() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arrayDepth; i++) {
            sb.append("[");
        }
        
        if (isPrimitive) {
            sb.append(getPrimitiveDescriptor(resolvedClass.getName()));
        } else {
            sb.append("L").append(resolvedClass.getName().replace('.', '/')).append(";");
        }
        
        return sb.toString();
    }
    
    public int getLoadOpcode() {
        if (isPrimitive) {
            return switch (resolvedClass.getName()) {
                case T_INT, T_BYTE, T_SHORT, T_CHAR, T_BOOLEAN -> Opcodes.ILOAD;
                case T_LONG -> Opcodes.LLOAD;
                case T_FLOAT -> Opcodes.FLOAD;
                case T_DOUBLE -> Opcodes.DLOAD;
                default -> Opcodes.ALOAD;
            };
        }
        return Opcodes.ALOAD;
    }
    
    public int getReturnOpcode() {
        if (isPrimitive) {
            return switch (resolvedClass.getName()) {
                case T_INT, T_BYTE, T_SHORT, T_CHAR, T_BOOLEAN -> Opcodes.IRETURN;
                case T_LONG -> Opcodes.LRETURN;
                case T_FLOAT -> Opcodes.FRETURN;
                case T_DOUBLE -> Opcodes.DRETURN;
                case T_VOID -> Opcodes.RETURN;
                default -> Opcodes.ARETURN;
            };
        }
        return Opcodes.ARETURN;
    }
    
    private static String getPrimitiveInternalName(String primitiveName) {
        return switch (primitiveName) {
            case T_INT -> "I";
            case T_LONG -> "J";
            case T_FLOAT -> "F";
            case T_DOUBLE -> "D";
            case T_BOOLEAN -> "Z";
            case T_CHAR -> "C";
            case T_BYTE -> "B";
            case T_SHORT -> "S";
            case T_VOID -> "V";
            default -> primitiveName;
        };
    }

    private static String getPrimitiveDescriptor(String primitiveName) {
        return switch (primitiveName) {
            case T_INT -> "I";
            case T_LONG -> "J";
            case T_FLOAT -> "F";
            case T_DOUBLE -> "D";
            case T_BOOLEAN -> "Z";
            case T_CHAR -> "C";
            case T_BYTE -> "B";
            case T_SHORT -> "S";
            case T_VOID -> "V";
            default -> primitiveName;
        };
    }
    
    private static class Opcodes {
        static final int ILOAD = 21;
        static final int LLOAD = 22;
        static final int FLOAD = 23;
        static final int DLOAD = 24;
        static final int ALOAD = 25;
        static final int IRETURN = 172;
        static final int LRETURN = 173;
        static final int FRETURN = 174;
        static final int DRETURN = 175;
        static final int ARETURN = 176;
        static final int RETURN = 177;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("ClassReferenceNode\n");
        sb.append(indent(indent + 1)).append("originalTypeName: ").append(originalTypeName).append("\n");
        sb.append(indent(indent + 1)).append("resolvedClass: ").append(resolvedClass != null ? resolvedClass.getName() : "null").append("\n");
        sb.append(indent(indent + 1)).append("isPrimitive: ").append(isPrimitive).append("\n");
        sb.append(indent(indent + 1)).append("arrayDepth: ").append(arrayDepth).append("\n");
        if (!typeArguments.isEmpty()) {
            sb.append(indent(indent + 1)).append("typeArguments:\n");
            for (ClassReferenceNode arg : typeArguments) {
                sb.append(arg.formatString(indent + 2)).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
    
    @Override
    public String toString() {
        return "ClassReferenceNode(" + getTypeName() + " -> " + (resolvedClass != null ? resolvedClass.getName() : "null") + ")";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String originalTypeName;
        private Class<?> resolvedClass;
        private boolean isPrimitive;
        private int arrayDepth;
        private List<ClassReferenceNode> typeArguments;

        public Builder originalTypeName(String originalTypeName) {
            this.originalTypeName = originalTypeName;
            return this;
        }

        public Builder resolvedClass(Class<?> resolvedClass) {
            this.resolvedClass = resolvedClass;
            return this;
        }

        public Builder isPrimitive(boolean isPrimitive) {
            this.isPrimitive = isPrimitive;
            return this;
        }

        public Builder arrayDepth(int arrayDepth) {
            this.arrayDepth = arrayDepth;
            return this;
        }

        public Builder typeArguments(List<ClassReferenceNode> typeArguments) {
            this.typeArguments = typeArguments;
            return this;
        }

        @Override
        public ClassReferenceNode build() {
            return new ClassReferenceNode(originalTypeName, resolvedClass, isPrimitive, arrayDepth, typeArguments, location);
        }
    }
}