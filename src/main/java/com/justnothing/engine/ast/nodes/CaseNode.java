package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.List;

public class CaseNode extends ASTNode {
    private final List<ASTNode> values;
    private final List<ASTNode> statements;
    /** 箭头风格 {@code ->} 的 case 不会 fall-through；冒号风格 {@code :} 会。 */
    private final boolean arrowStyle;

    private CaseNode(List<ASTNode> values, List<ASTNode> statements, boolean arrowStyle, SourceLocation location) {
        super(location);
        this.values = values;
        this.statements = statements;
        this.arrowStyle = arrowStyle;
    }

    /** 第一个匹配值（兼容单值调用方；多值 case 请使用 {@link #getValues()}）。 */
    public ASTNode getValue() {
        return values.isEmpty() ? null : values.get(0);
    }

    /** 该 case 声明的全部匹配值（{@code case 1, 2, 3 ->}）。 */
    public List<ASTNode> getValues() {
        return values;
    }

    public List<ASTNode> getStatements() {
        return statements;
    }

    public boolean isArrowStyle() {
        return arrowStyle;
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
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("CaseNode\n");
        sb.append(indent(indent + 1)).append("values: ").append(values.size()).append("\n");
        for (int i = 0; i < values.size(); i++) {
            sb.append(indent(indent + 1)).append("value[").append(i).append("]:\n");
            sb.append(values.get(i).formatString(indent + 2)).append("\n");
        }
        sb.append(indent(indent + 1)).append("statements: ").append(statements.size()).append("\n");
        for (int i = 0; i < statements.size(); i++) {
            sb.append(indent(indent + 1)).append("stmt[").append(i).append("]:\n");
            sb.append(statements.get(i).formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private final List<ASTNode> values = new ArrayList<>();
        private List<ASTNode> statements;
        private boolean arrowStyle = true;

        public Builder value(ASTNode value) {
            this.values.add(value);
            return this;
        }

        public Builder values(List<ASTNode> values) {
            this.values.clear();
            if (values != null) this.values.addAll(values);
            return this;
        }

        public Builder statements(List<ASTNode> statements) {
            this.statements = statements;
            return this;
        }

        public Builder arrowStyle(boolean arrowStyle) {
            this.arrowStyle = arrowStyle;
            return this;
        }

        @Override
        public ASTNode build() {
            return new CaseNode(new ArrayList<>(values), statements, arrowStyle, location);
        }
    }
}
