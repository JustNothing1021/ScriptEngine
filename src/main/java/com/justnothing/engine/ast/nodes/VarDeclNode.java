package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.List;

/**
 * 变量声明节点。
 * <p>
 * 表示 {@code Type varName [= initializer]} 形式的变量声明，
 * 与 {@link AssignmentNode}（纯赋值）语义分离。
 * </p>
 *
 * <h3>与 AssignmentNode 的区别</h3>
 * <ul>
 *   <li>VarDeclNode — 声明新变量，携带类型信息和注解，只出现一次</li>
 *   <li>AssignmentNode — 对已有变量的赋值操作，无类型信息（从符号表获取）</li>
 * </ul>
 *
 * @author JustNothing1021
 * @since 1.0.0
 */
public class VarDeclNode extends ASTNode {

    private final String varName;
    private final GenericType declaredType;
    private final ASTNode initializer;
    private final boolean isFinal;
    private final List<AnnotationNode> annotations;


    private VarDeclNode(String varName, GenericType declaredType, ASTNode initializer,
                       boolean isFinal, List<AnnotationNode> annotations, SourceLocation location) {
        super(location);
        this.varName = varName;
        this.declaredType = declaredType;
        this.initializer = initializer;
        this.isFinal = isFinal;
        this.annotations = annotations != null ? annotations : List.of();
    }

    /** 变量名。 */
    public String getVarName() {
        return varName;
    }

    /** 声明的类型（含泛型参数和数组维度）。 */
    public GenericType getDeclaredType() {
        return declaredType;
    }

    /** 初始化表达式（可能为 null 表示使用类型默认值）。 */
    public ASTNode getInitializer() {
        return initializer;
    }

    /** 是否为 final（不可重新赋值）。 */
    public boolean isFinal() {
        return isFinal;
    }

    /** 声明上的注解列表（可能为空）。 */
    public List<AnnotationNode> getAnnotations() {
        return annotations;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("VarDeclNode\n");
        sb.append(indent(indent + 1)).append("varName: ").append(varName).append("\n");
        sb.append(indent(indent + 1)).append("type: ")
                .append(declaredType != null ? declaredType.getTypeName() : "null").append("\n");
        sb.append(indent(indent + 1)).append("isFinal: ").append(isFinal).append("\n");
        if (!annotations.isEmpty()) {
            sb.append(indent(indent + 1)).append("annotations: ").append(annotations.size()).append("\n");
        }
        if (initializer == null) {
            sb.append(indent(indent + 1)).append("initializer: null\n");
        } else {
            sb.append(indent(indent + 1)).append("initializer:\n");
            sb.append(initializer.formatString(indent + 2));
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String varName;
        private GenericType declaredType;
        private ASTNode initializer;
        private boolean isFinal;
        private List<AnnotationNode> annotations;

        public Builder varName(String varName) {
            this.varName = varName;
            return this;
        }

        public Builder declaredType(GenericType declaredType) {
            this.declaredType = declaredType;
            return this;
        }

        public Builder initializer(ASTNode initializer) {
            this.initializer = initializer;
            return this;
        }

        public Builder isFinal(boolean isFinal) {
            this.isFinal = isFinal;
            return this;
        }

        public Builder annotations(List<AnnotationNode> annotations) {
            this.annotations = annotations;
            return this;
        }

        @Override
        public ASTNode build() {
            return new VarDeclNode(varName, declaredType, initializer, isFinal, annotations, location);
        }
    }
}
