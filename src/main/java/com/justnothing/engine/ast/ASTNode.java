package com.justnothing.engine.ast;

import com.justnothing.engine.ast.visitor.ASTVisitor;
import com.justnothing.engine.util.CodeFormatter;

/**
 * AST节点基类
 * <p>
 * 所有AST节点的基类，提供通用的接口和功能。
 * 使用Builder模式创建，所有字段都是final的，确保不可变性。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public abstract class ASTNode {
    
    private final SourceLocation location;
    
    protected ASTNode(SourceLocation location) {
        this.location = location;
    }
    
    public SourceLocation getLocation() {
        return location;
    }
    
    /**
     * 接受访问者
     * 
     * @param visitor 访问者
     * @return 访问结果
     */
    public abstract <T> T accept(ASTVisitor<T> visitor);
    
    /**
     * 格式化输出
     * 
     * @return 格式化后的字符串
     */
    public String formatString() {
        return formatString(0);
    }
    
    /**
     * 格式化输出（带缩进）
     * 
     * @param indent 缩进级别
     * @return 格式化后的字符串
     */
    public abstract String formatString(int indent);

    /**
     * 重建为源代码
     *
     * @return 源代码字符串
     */
    public String formatCode() {
        return CodeFormatter.format(this);
    }

    /**
     * 重建为源代码（带缩进）
     *
     * @param indent 缩进级别
     * @return 源代码字符串
     */
    public String formatCode(int indent) {
        return CodeFormatter.format(this, indent);
    }

    /**
     * 生成缩进字符串
     * 
     * @param level 缩进级别
     * @return 缩进字符串
     */
    protected String indent(int level) {
        return "  ".repeat(level);
    }
    
    /**
     * Builder基类
     * 
     * @param <B> Builder子类类型
     */
    protected abstract static class Builder<B extends Builder<B>> {
        protected SourceLocation location;
        
        protected Builder() {
        }
        
        @SuppressWarnings("unchecked")
        public B location(SourceLocation location) {
            this.location = location;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B location(int line, int column, String source) {
            this.location = new SourceLocation(line, column, source);
            return (B) this;
        }
        
        protected abstract ASTNode build();
    }
}
