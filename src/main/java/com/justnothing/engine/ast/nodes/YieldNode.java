package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * yield 语句：{@code yield expr;}。
 * <p>
 * 产生所在 switch 表达式的值。与 return 类似，求值时会向上抛出控制流异常，
 * 由最近的 switch 捕获，因此可以出现在 switch 分支里的嵌套块或循环中。
 * </p>
 */
public class YieldNode extends ASTNode {

    private final ASTNode value;

    private YieldNode(ASTNode value, SourceLocation location) {
        super(location);
        this.value = value;
    }

    /** yield 的值表达式。 */
    public ASTNode getValue() {
        return value;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        String ind = indent(indent);
        if (value == null) {
            return ind + "YieldNode{null}";
        }
        return ind + "YieldNode{\n" + value.formatString(indent + 1) + "\n" + ind + "}";
    }

    @Override
    public String toString() {
        return "YieldNode{value=" + value + "}";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode value;

        public Builder value(ASTNode value) {
            this.value = value;
            return this;
        }

        @Override
        public YieldNode build() {
            return new YieldNode(value, location);
        }
    }
}
