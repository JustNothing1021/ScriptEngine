package com.justnothing.engine.ast;

import java.util.Objects;

/**
 * 值位置上的一个名字引用，以及它在 link 层被判定成的身份。
 * <p>
 * 解析期遇到一个裸名字（如 {@code Foo}、{@code x}、{@code println}）时，往往无从判断它
 * 指代什么：可能是个局部变量、当前类字段、某个类、内置函数，甚至是个还没解析到的类。
 * 解析器不该为了回答这个问题去查类 —— 那是 link 层的活。所以解析期一律产出
 * {@link com.justnothing.engine.ast.nodes.NameRefNode}，把身份判定结果留在这个
 * {@code NameRef} 里。
 * </p>
 * <p>
 * 与 {@link TypeRef} 的分工：{@code TypeRef} 描述"类型位置"上的名字（声明、参数、返回值），
 * {@code NameRef} 描述"值位置"上的名字（表达式里出现的东西）。
 * </p>
 */
public final class NameRef {

    /**
     * 名字指代什么。
     * <p>
     * {@link #UNRESOLVED} 是解析期的初始状态；其余取值由 link 层（或运行期兜底）填入。
     * </p>
     */
    public enum Kind {
        /** 尚未判定 —— 解析期产出的都是这个。 */
        UNRESOLVED,
        /** 局部变量或形参。 */
        VARIABLE,
        /** 当前类的字段（隐式 {@code this.}）。 */
        FIELD,
        /** 类名。 */
        CLASS,
        /** 内置函数。 */
        BUILTIN
    }

    private final String name;
    private Kind kind;
    private Class<?> resolvedClass;

    private NameRef(String name, Kind kind, Class<?> resolvedClass) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("NameRef 的名字不能为空");
        }
        this.name = name;
        this.kind = kind;
        this.resolvedClass = resolvedClass;
    }

    /** 一个尚未判定身份的名字引用。 */
    public static NameRef unresolved(String name) {
        return new NameRef(name, Kind.UNRESOLVED, null);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** 判定为类名后指向的类；其余身份为 null。 */
    public Class<?> resolvedClass() {
        return resolvedClass;
    }

    public boolean isResolved() {
        return kind != Kind.UNRESOLVED;
    }

    /** 记为普通名字身份（非类）。只应由 link 层调用。 */
    public void resolveAs(Kind kind) {
        if (kind == Kind.CLASS) {
            throw new IllegalArgumentException("判定为类名请用 resolveAsClass");
        }
        this.kind = kind;
        this.resolvedClass = null;
    }

    /** 记为类名。只应由 link 层调用。 */
    public void resolveAsClass(Class<?> type) {
        this.kind = Kind.CLASS;
        this.resolvedClass = type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NameRef other)) return false;
        return name.equals(other.name) && kind == other.kind && resolvedClass == other.resolvedClass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, kind, resolvedClass);
    }

    @Override
    public String toString() {
        return kind == Kind.UNRESOLVED ? name : name + " (" + kind + ")";
    }
}
