package com.justnothing.engine.ast.visitor;

import com.justnothing.engine.ast.ASTNode;

/**
 * AST访问者接口
 * <p>
 * 使用访问者模式遍历AST节点，支持不同的操作（如求值、类型检查、代码生成等）。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 * 
 * @param <T> 访问结果的类型
 */
public interface ASTVisitor<T> {
    
    /**
     * 访问AST节点
     * 
     * @param node AST节点
     * @return 访问结果
     */
    T visit(ASTNode node);
}
