package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.NameRef;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

/**
 * 值位置上的未定名引用。
 * <p>
 * 表达式里出现一个裸名字、而解析期无法（也不该）判定它是什么时，产出这个节点。
 * 例如 {@code x + 1} 里 {@code x} 若是未声明的名字、{@code Foo.bar} 里 {@code Foo}
 * 若是同文件后面才声明的类 —— 解析器只登记"这里有个名字叫 x/Foo"，身份留给 link 层。
 * </p>
 * <p>
 * 与 {@link VariableNode} 的区别：{@code VariableNode} 是"已经知道它是变量"的引用
 * （解析期查过符号表），{@code NameRefNode} 是"还不知道是什么"。前者带声明类型等元数据，
 * 后者只有一个名字和（可能为空的）判定结果。
 * </p>
 */
public class NameRefNode extends ASTNode {

    private final NameRef ref;

    private NameRefNode(NameRef ref, SourceLocation location) {
        super(location);
        this.ref = ref;
    }

    /** 创建一个尚未判定身份的名字引用。 */
    public static NameRefNode unresolved(String name, SourceLocation location) {
        return new NameRefNode(NameRef.unresolved(name), location);
    }

    public NameRef getRef() {
        return ref;
    }

    public String getName() {
        return ref.name();
    }

    /** 是否已被判定身份。 */
    public boolean isResolved() {
        return ref.isResolved();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indentLevel) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indentLevel)).append("NameRefNode: ").append(ref.name());
        if (ref.isResolved()) {
            sb.append(" -> ").append(ref.kind());
            if (ref.resolvedClass() != null) {
                sb.append(' ').append(ref.resolvedClass().getName());
            }
        }
        return sb.toString();
    }
}
