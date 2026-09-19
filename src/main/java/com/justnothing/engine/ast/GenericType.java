package com.justnothing.engine.ast;


import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class GenericType {
    
    /**
     * 原始类型引用。
     * <p>用 {@link TypeRef} 而不是裸 {@code Class} 承载，是为了让"尚未解析"成为一个合法状态：
     * Parser 只填名字、Linker 才回填 Class。已解析时行为与以前的裸 Class 完全一致。</p>
     */
    private final TypeRef rawRef;
    private final List<GenericType> typeArguments;
    private final int arrayDepth;
    private final String originalTypeName;
    
    public GenericType(Class<?> rawType) {
        this(rawType, Collections.emptyList(), 0, null);
    }
    
    public GenericType(Class<?> rawType, List<GenericType> typeArguments) {
        this(rawType, typeArguments, 0, null);
    }
    
    public GenericType(Class<?> rawType, List<GenericType> typeArguments, int arrayDepth) {
        this(rawType, typeArguments, arrayDepth, null);
    }
    
    public GenericType(Class<?> rawType, List<GenericType> typeArguments, int arrayDepth, String originalTypeName) {
        this(toRawRef(rawType, originalTypeName), typeArguments, arrayDepth, originalTypeName);
    }

    /**
     * 未解析且连书写形式都没有时用的占位名（只影响显示与调试，不代表真实类型）。
     */
    private static final String UNRESOLVED_NAME = "<未解析>";

    /**
     * 把可空的 Class 归一成类型引用。
     * <p>{@code null} 一律当作"尚未解析"：历史上这个类允许 {@code rawType} 为 null（自定义类
     * 尚未生成时就会这样），这里不能变成构造期异常，否则改变既有行为。</p>
     */
    private static TypeRef toRawRef(Class<?> rawType, String originalTypeName) {
        if (rawType != null) return TypeRef.resolved(rawType);
        return TypeRef.byName(originalTypeName != null ? originalTypeName : UNRESOLVED_NAME);
    }

    public GenericType(TypeRef rawRef, List<GenericType> typeArguments, int arrayDepth) {
        this(rawRef, typeArguments, arrayDepth, null);
    }

    /**
     * 用类型引用构造，允许"尚未解析"（{@code rawRef.isResolved() == false}）。
     * <p>该状态只应出现在 link 之前的 AST 上；link 跑完后所有引用都该被回填。</p>
     */
    public GenericType(TypeRef rawRef, List<GenericType> typeArguments, int arrayDepth, String originalTypeName) {
        this.rawRef = Objects.requireNonNull(rawRef, "rawRef");
        this.typeArguments = typeArguments != null ? 
            Collections.unmodifiableList(new ArrayList<>(typeArguments)) : 
            Collections.emptyList();
        this.arrayDepth = arrayDepth;
        this.originalTypeName = originalTypeName;
    }
    
    /** 获取原始 Class；未解析时返回 {@code null}（见 {@link #isResolved()}）。 */
    public Class<?> getRawType() {
        return rawRef.resolved();
    }

    /** 获取原始类型引用（带名字与解析状态）。 */
    public TypeRef getRawRef() {
        return rawRef;
    }

    /** 原始类型是否已解析出 Class。 */
    public boolean isResolved() {
        return rawRef.isResolved();
    }
    
    public List<GenericType> getTypeArguments() {
        return typeArguments;
    }
    
    public int getArrayDepth() {
        return arrayDepth;
    }
    
    public String getOriginalTypeName() {
        return originalTypeName;
    }
    
    public boolean isGeneric() {
        return !typeArguments.isEmpty();
    }
    
    public boolean isArray() {
        return arrayDepth > 0;
    }
    
    public Class<?> getRuntimeType() {
        // 未解析时 require() 会抛出带名字的明确异常，而不是 NPE
        Class<?> type = rawRef.require();
        for (int i = 0; i < arrayDepth; i++) {
            type = Array.newInstance(type, 0).getClass();
        }
        return type;
    }
    
    public String getTypeName() {
        if (originalTypeName != null) {
            StringBuilder sb = new StringBuilder(originalTypeName);
            
            if (!typeArguments.isEmpty()) {
                sb.append("<");
                for (int i = 0; i < typeArguments.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeArguments.get(i).getTypeName());
                }
                sb.append(">");
            }

            sb.append("[]".repeat(Math.max(0, arrayDepth)));
            
            return sb.toString();
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(simpleNameOf(rawRef));
        
        if (!typeArguments.isEmpty()) {
            sb.append("<");
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments.get(i).getTypeName());
            }
            sb.append(">");
        }

        sb.append("[]".repeat(Math.max(0, arrayDepth)));
        
        return sb.toString();
    }
    
    /** 取简单名：已解析用 Class 的简单名，未解析从书写形式里截最后一段。 */
    private static String simpleNameOf(TypeRef ref) {
        Class<?> resolved = ref.resolved();
        if (resolved != null) return resolved.getSimpleName();
        String name = ref.name();
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }
    
    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        Class<?> rawType = rawRef.resolved();
        sb.append(rawType != null ? rawType.getName() : rawRef.name());
        
        if (!typeArguments.isEmpty()) {
            sb.append("<");
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments.get(i).getFullName());
            }
            sb.append(">");
        }

        sb.append("[]".repeat(Math.max(0, arrayDepth)));
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return "GenericType[" + getTypeName() + "]";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        GenericType other = (GenericType) obj;
        if (!rawRef.equals(other.rawRef)) return false;
        if (arrayDepth != other.arrayDepth) return false;
        return typeArguments.equals(other.typeArguments);
    }
    
    @Override
    public int hashCode() {
        int result = rawRef.hashCode();
        result = 31 * result + typeArguments.hashCode();
        result = 31 * result + arrayDepth;
        return result;
    }
    
    public static GenericType of(Class<?> rawType) {
        return new GenericType(rawType);
    }
    
    public static GenericType of(Class<?> rawType, GenericType... typeArguments) {
        List<GenericType> args = new ArrayList<>();
        Collections.addAll(args, typeArguments);
        return new GenericType(rawType, args);
    }
    
    public static GenericType arrayOf(GenericType elementType, int dimensions) {
        return new GenericType(elementType.rawRef, elementType.typeArguments, dimensions);
    }
}
