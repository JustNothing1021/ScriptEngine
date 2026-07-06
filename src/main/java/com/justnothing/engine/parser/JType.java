package com.justnothing.engine.parser;

import java.util.List;
import java.lang.reflect.Array;
import java.util.stream.Collectors;

import com.justnothing.engine.ast.GenericType;

/**
 * Cythava 类型系统接口。
 * <p>
 * 在解析期间为每个 AST 表达式节点附加类型信息。
 * 复用旧版 {@link GenericType} 作为默认实现。
 * </p>
 * <p>
 * 设计目标：
 * <ul>
 *   <li>Parser 解析完成后，每个表达式节点都有对应的 JType</li>
 *   <li>Evaluator 无需再做类型推断，直接使用即可</li>
 *   <li>为未来字节码生成提供完整的类型信息</li>
 * </ul>
 * </p>
 */
public interface JType {

    /** 获取原始 Class 对象（如 int.class, String.class）。 */
    Class<?> getRawType();

    /** 获取泛型参数列表（空列表表示非泛型）。 */
    List<JType> getTypeArguments();

    /** 获取数组维度（0 表示非数组）。 */
    int getArrayDepth();

    /** 是否为基本类型（int, long, boolean 等）。 */
    boolean isPrimitive();

    /** 是否可空（用于 ?? 安全相关操作符的语义分析）。 */
    boolean isNullable();

    /**
     * 获取运行时 Java Class 对象。
     * <p>与 {@link #getRawType()} 的区别：对数组类型会合成数组 Class（如 int[] 而非 int）。</p>
     *
     * @return 运行时类型，数组类型返回对应的数组 Class
     */
    default Class<?> getRuntimeType() {
        Class<?> raw = getRawType();
        for (int i = 0; i < getArrayDepth(); i++) {
            raw = Array.newInstance(raw, 0).getClass();
        }
        return raw;
    }

    /**
     * 获取类型的显示名称。
     *
     * @return 如 "List&lt;String&gt;[]", "int", "java.lang.String"
     */
    String getDisplayName();

    // ==================== 工厂方法 ====================

    /**
     * 从 GenericType 创建 JType（复用旧版类型体系）。
     */
    static JType fromGenericType(GenericType gt) {
        return new GenericTypeWrapper(gt);
    }

    /**
     * 从 Class 创建简单 JType。
     */
    static JType of(Class<?> clazz) {
        return new GenericTypeWrapper(new GenericType(clazz));
    }

    // ==================== 默认实现 ====================

    /**
     * 基于 {@link GenericType} 的 JType 默认实现。
     * 将旧版类型系统包装为统一接口。
     */
    class GenericTypeWrapper implements JType {
        private final GenericType delegate;

        GenericTypeWrapper(GenericType delegate) {
            this.delegate = delegate;
        }

        @Override
        public Class<?> getRawType() {
            return delegate.getRawType();
        }

        @Override
        public List<JType> getTypeArguments() {
            return delegate.getTypeArguments().stream()
                    .map(GenericTypeWrapper::new)
                    .collect(Collectors.toList());
        }

        @Override
        public int getArrayDepth() {
            return delegate.getArrayDepth();
        }

        @Override
        public boolean isPrimitive() {
            Class<?> raw = delegate.getRawType();
            return raw.isPrimitive() || raw == Void.TYPE;
        }

        @Override
        public boolean isNullable() {
            // 基本类型不可空，引用类型可空
            return !isPrimitive();
        }

        @Override
        public String getDisplayName() {
            return delegate.getTypeName();
        }
    }
}
