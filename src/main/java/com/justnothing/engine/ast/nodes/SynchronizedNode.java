package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * synchronized 语句：{@code synchronized (lock) { ... }}。
 * <p>
 * 与 synchronized 修饰符不同：这里是对块级临界区的支持，求值时以 lock 对象为监视器。
 * </p>
 */
public class SynchronizedNode extends ASTNode {

    private final ASTNode lock;
    private final ASTNode body;

    private SynchronizedNode(ASTNode lock, ASTNode body, SourceLocation location) {
        super(location);
        this.lock = lock;
        this.body = body;
    }

    /** 监视器表达式。 */
    public ASTNode getLock() {
        return lock;
    }

    /** 临界区语句体（通常是 BlockNode）。 */
    public ASTNode getBody() {
        return body;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        String ind = indent(indent);
        return ind + "SynchronizedNode{\n"
                + indent(indent + 1) + "lock:\n" + lock.formatString(indent + 2) + "\n"
                + indent(indent + 1) + "body:\n" + body.formatString(indent + 2) + "\n"
                + ind + "}";
    }

    @Override
    public String toString() {
        return "SynchronizedNode{lock=" + lock + ", body=" + body + "}";
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode lock;
        private ASTNode body;

        public Builder lock(ASTNode lock) {
            this.lock = lock;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        @Override
        public SynchronizedNode build() {
            return new SynchronizedNode(lock, body, location);
        }
    }
}
