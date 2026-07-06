package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;
import com.justnothing.engine.parser.constant.ConstantFolder;

/**
 * 三元条件表达式节点。
 * <p>
 * 表示 {@code condition ? thenExpr : elseExpr}。
 * 通过 Builder 创建时自动执行常量折叠（条件为常量布尔值时短路）。
 * </p>
 */
public class TernaryNode extends ASTNode {
    private final ASTNode condition;
    private final ASTNode thenExpr;
    private final ASTNode elseExpr;

    private TernaryNode(ASTNode condition, ASTNode thenExpr, ASTNode elseExpr, SourceLocation location) {
        super(location);
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    public ASTNode getCondition() {
        return condition;
    }

    public ASTNode getThenExpr() {
        return thenExpr;
    }

    public ASTNode getElseExpr() {
        return elseExpr;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString() {
        return formatString(0);
    }

    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "TernaryNode\n" +
                indent(indent + 1) + "condition:\n" +
                condition.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "then:\n" +
                thenExpr.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "else:\n" +
                elseExpr.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    // ==================== Builder（build() 自动常量折叠） ====================

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode condition;
        private ASTNode thenExpr;
        private ASTNode elseExpr;

        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }

        public Builder thenExpr(ASTNode thenExpr) {
            this.thenExpr = thenExpr;
            return this;
        }

        public Builder elseExpr(ASTNode elseExpr) {
            this.elseExpr = elseExpr;
            return this;
        }

        @Override
        public ASTNode build() {
            TernaryNode node = new TernaryNode(condition, thenExpr, elseExpr, location);
            return ConstantFolder.fold(node);
        }
    }
}
