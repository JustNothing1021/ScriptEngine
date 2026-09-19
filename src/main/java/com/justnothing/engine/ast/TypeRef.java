package com.justnothing.engine.ast;

import java.util.Objects;

/**
 * 可解析的类型引用：源码里的书写形式 + 解析结果。
 * <p>
 * 这是解析/链接（link）拆分的基石。Parser 只填 {@link #name()}、不查类；Linker 的 Resolve 遍
 * 才回填 {@link #resolved()}。"未解析"因此是一个显式状态，不再像以前那样用
 * {@code Object.class} 冒充 —— 那个谎话在解析期真的不查类时，会从"无害的占位"变成
 * "假的类型信息"（{@code int}、{@code String} 都会被当成 {@code Object}）。
 * </p>
 * <p>
 * 约定：{@code name} 与 {@code resolved} 至少一个非 null。
 * 从 {@link #byName} 创建即"未解析"，由 link 层用 {@link #setResolved} 回填；
 * 从 {@link #resolved} 创建即"已解析"，名字取全限定名。
 * </p>
 */
public final class TypeRef {

    private final String name;
    private Class<?> resolved;

    private TypeRef(String name, Class<?> resolved) {
        if (name == null && resolved == null) {
            throw new IllegalArgumentException("name 与 resolved 不能同时为 null");
        }
        this.name = name;
        this.resolved = resolved;
    }

    // ==================== 工厂方法 ====================

    /**
     * 从书写形式创建（未解析状态）。
     *
     * @param name 源码里的写法，如 {@code String}、{@code java.util.List}
     */
    public static TypeRef byName(String name) {
        return new TypeRef(Objects.requireNonNull(name, "name"), null);
    }

    /**
     * 从已解析的 Class 创建（名字取全限定名）。
     */
    public static TypeRef resolved(Class<?> type) {
        return new TypeRef(Objects.requireNonNull(type, "type").getName(), type);
    }

    /**
     * 同时给出书写形式与解析结果。
     * <p>简单名/别名场景用这个保留源码写法，比 {@link #resolved} 的全限定名更贴近原始代码。</p>
     */
    public static TypeRef of(String name, Class<?> type) {
        return new TypeRef(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
    }

    // ==================== 访问 ====================

    /** 书写形式（未解析时这是唯一可用的信息）。 */
    public String name() {
        return name != null ? name : resolved.getName();
    }

    /** 已解析的 Class；{@code null} 表示尚未解析。 */
    public Class<?> resolved() {
        return resolved;
    }

    /** 是否已完成类型解析。 */
    public boolean isResolved() {
        return resolved != null;
    }

    /**
     * 取已解析的 Class，未解析时抛异常。
     * <p>运行期路径用这个：走到这里还没解析，说明 link 层没跑或该引用漏填，属于程序错误，
     * 不该静默降级。</p>
     */
    public Class<?> require() {
        if (resolved == null) {
            throw new IllegalStateException("类型 '" + name + "' 尚未解析：link 层未执行，或该引用漏填");
        }
        return resolved;
    }

    /**
     * 回填解析结果。
     * <p><b>只应由 link 层调用</b>：Parser 与 Evaluator 都不该碰它，否则"未解析"这个状态
     * 就失去意义了。</p>
     */
    public void setResolved(Class<?> type) {
        this.resolved = Objects.requireNonNull(type, "type");
    }

    // ==================== 基础方法 ====================

    @Override
    public String toString() {
        return resolved != null ? name() + " -> " + resolved.getName() : name() + ": ?";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TypeRef other)) return false;
        // 已解析比 Class，未解析比名字（同名未解析引用视为同一个）
        if (resolved != null || other.resolved != null) {
            return resolved != null && resolved.equals(other.resolved);
        }
        return name().equals(other.name());
    }

    @Override
    public int hashCode() {
        return resolved != null ? resolved.hashCode() : name().hashCode();
    }
}
