package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 代码块节点
 * <p>
 * 表示一个代码块，包含多个语句。
 * </p>
 * <p>
 * {@code scoped} 区分两类语义：
 * <ul>
 *   <li>{@code true}（默认）：真正的词法块 {@code { … }}，求值时创建子作用域，
 *       块内声明的变量在块结束后不可见</li>
 *   <li>{@code false}：仅为承载"语句序列"的语法容器，例如多变量声明
 *       {@code int a = 1, b = 2;} 或 for 的逗号更新子句 {@code i++, j--}。
 *       这类序列不引入作用域，声明的变量归属于外层</li>
 * </ul>
 */
public class BlockNode extends ASTNode {

    private final List<ASTNode> statements;
    private final boolean scoped;

    private BlockNode(List<ASTNode> statements, boolean scoped, SourceLocation location) {
        super(location);
        this.statements = statements != null ?
                List.copyOf(statements) :
            Collections.emptyList();
        this.scoped = scoped;
    }

    public List<ASTNode> getStatements() {
        return statements;
    }

    /** 是否引入新的词法作用域（见类注释）。 */
    public boolean isScoped() {
        return scoped;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("BlockNode");
        if (!scoped) {
            sb.append(" (unscoped)");
        }
        sb.append("\n");
        sb.append(indent(indent + 1)).append("statements: ").append(statements.size()).append("\n");
        for (int i = 0; i < statements.size(); i++) {
            sb.append(indent(indent + 1)).append("stmt[").append(i).append("]:\n");
            sb.append(statements.get(i).formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private List<ASTNode> statements;
        private boolean scoped = true;

        public Builder statements(List<ASTNode> statements) {
            this.statements = statements;
            return this;
        }

        /** 标记为非词法块：求值时不创建子作用域。 */
        public Builder unscoped() {
            this.scoped = false;
            return this;
        }

        public Builder addStatement(ASTNode statement) {
            if (this.statements == null) {
                this.statements = new ArrayList<>();
            }
            this.statements.add(statement);
            return this;
        }

        @Override
        public ASTNode build() {
            return new BlockNode(statements, scoped, location);
        }
    }
}
