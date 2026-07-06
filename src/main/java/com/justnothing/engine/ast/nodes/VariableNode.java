package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 变量节点。
 * <p>
 * 表示变量的声明或引用。支持存储声明时类型、final 修饰符和注解信息。
 * </p>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public class VariableNode extends ASTNode {

    private final String name;
    private boolean isFieldAccess = false;
    private ClassReferenceNode declaredType;
    private boolean isFinal;
    private List<AnnotationNode> annotations = Collections.emptyList();


    private VariableNode(String name, SourceLocation location) {
        super(location);
        this.name = name;
    }

    // ==================== 基础属性 ====================

    public String getName() {
        return name;
    }

    public boolean isFieldAccess() {
        return isFieldAccess;
    }

    public void setFieldAccess(boolean fieldAccess) {
        isFieldAccess = fieldAccess;
    }

    // ==================== 声明元数据 ====================

    /**
     * 获取变量声明的类型（可能为 null 表示未推断/未声明）。
     */
    public ClassReferenceNode getDeclaredType() {
        return declaredType;
    }

    public void setDeclaredType(ClassReferenceNode declaredType) {
        this.declaredType = declaredType;
    }

    /**
     * 是否为 final 变量。
     */
    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean aFinal) {
        isFinal = aFinal;
    }

    /**
     * 获取变量上的注解列表（声明时携带的注解）。
     */
    public List<AnnotationNode> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<AnnotationNode> annotations) {
        this.annotations = annotations != null
                ? Collections.unmodifiableList(new ArrayList<>(annotations))
                : Collections.emptyList();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("VariableNode: ").append(name);
        if (isFieldAccess) sb.append(" (field)");
        if (declaredType != null) {
            sb.append("\n").append(indent(indent + 1)).append("declaredType: ")
              .append(declaredType.getTypeName());
        }
        if (isFinal) {
            sb.append("\n").append(indent(indent + 1)).append("modifier: final");
        }
        if (!annotations.isEmpty()) {
            sb.append("\n").append(indent(indent + 1)).append("annotations: ").append(annotations.size());
        }
        return sb.toString();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String name;
        private boolean isFieldAccess;
        private ClassReferenceNode declaredType;
        private boolean isFinal;
        private List<AnnotationNode> annotations;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder fieldAccess(boolean isFieldAccess) {
            this.isFieldAccess = isFieldAccess;
            return this;
        }

        public Builder declaredType(ClassReferenceNode declaredType) {
            this.declaredType = declaredType;
            return this;
        }

        public Builder isFinal(boolean aFinal) {
            isFinal = aFinal;
            return this;
        }

        public Builder annotations(List<AnnotationNode> annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public ASTNode build() {
            VariableNode node = new VariableNode(name, location);
            node.setFieldAccess(isFieldAccess);
            if (declaredType != null) node.setDeclaredType(declaredType);
            node.setFinal(isFinal);
            if (annotations != null) node.setAnnotations(annotations);
            return node;
        }
    }
}
