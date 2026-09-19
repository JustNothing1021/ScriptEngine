package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * assert 语句。
 * <p>
 * 两种形式：
 * <ul>
 *   <li>{@code assert condition;}</li>
 *   <li>{@code assert condition : message;}</li>
 * </ul>
 * 条件求值为假（或非 Boolean 值）时抛出断言失败，message 用于描述失败原因。
 * </p>
 */
public class AssertNode extends ASTNode {

    private final ASTNode condition;
    private final ASTNode message;

    private AssertNode(ASTNode condition, ASTNode message, SourceLocation location) {
        super(location);
        this.condition = condition;
        this.message = message;
    }

    public ASTNode getCondition() {
        return condition;
    }

    /** 断言消息表达式，未提供时为 null。 */
    public ASTNode getMessage() {
        return message;
    }

    public boolean hasMessage() {
        return message != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        String ind = indent(indent);
        StringBuilder sb = new StringBuilder(ind).append("AssertNode{\n");
        sb.append(indent(indent + 1)).append("condition:\n")
                .append(condition.formatString(indent + 2)).append('\n');
        if (message != null) {
            sb.append(indent(indent + 1)).append("message:\n")
                    .append(message.formatString(indent + 2)).append('\n');
        }
        return sb.append(ind).append('}').toString();
    }

    @Override
    public String toString() {
        return "AssertNode{condition=" + condition + ", message=" + message + "}";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode condition;
        private ASTNode message;

        public Builder condition(ASTNode condition) {
            this.condition = condition;
            return this;
        }

        public Builder message(ASTNode message) {
            this.message = message;
            return this;
        }

        @Override
        public AssertNode build() {
            return new AssertNode(condition, message, location);
        }
    }
}
