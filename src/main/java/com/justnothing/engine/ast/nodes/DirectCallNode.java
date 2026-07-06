package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 直接调用节点（语法糖）
 * <p>
 * 表示对任意可调用表达式的直接 () 调用，如：
 * <ul>
 *   <li>{@code lambdaExpr(args)} — Lambda 表达式直接调用</li>
 *   <li>{@code methodRef(args)} — 方法引用直接调用</li>
 *   <li>{@code functionalVar(args)} — 存储了 FI 的变量直接调用</li>
 * </ul>
 */
public class DirectCallNode extends ASTNode {

    private final ASTNode target;       // 被调用的表达式
    private final List<ASTNode> arguments;

    private DirectCallNode(ASTNode target, List<ASTNode> arguments, SourceLocation location) {
        super(location);
        this.target = target;
        this.arguments = arguments != null ?
                Collections.unmodifiableList(new ArrayList<>(arguments)) :
                Collections.emptyList();
    }

    public ASTNode getTarget() {
        return target;
    }

    public List<ASTNode> getArguments() {
        return arguments;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("DirectCallNode\n");
        sb.append(indent(indent + 1)).append("target:\n");
        sb.append(target.formatString(indent + 2)).append("\n");
        sb.append(indent(indent + 1)).append("arguments: ").append(arguments.size()).append("\n");
        for (int i = 0; i < arguments.size(); i++) {
            sb.append(indent(indent + 2)).append("arg[").append(i).append("]:\n");
            sb.append(arguments.get(i).formatString(indent + 3)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode target;
        private List<ASTNode> arguments;

        public Builder target(ASTNode target) {
            this.target = target;
            return this;
        }

        public Builder arguments(List<ASTNode> arguments) {
            this.arguments = arguments;
            return this;
        }

        @Override
        public ASTNode build() {
            return new DirectCallNode(target, arguments, location);
        }
    }
}
