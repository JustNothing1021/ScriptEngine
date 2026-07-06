package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 函数调用节点
 * <p>
 * 表示独立函数调用（无目标对象），如内置函数或用户定义函数。
 * 例如：print("hello")、range(0, 10)、myFunc(x)
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class FunctionCallNode extends ASTNode {
    
    private final String functionName;
    private final List<ASTNode> arguments;
    

    private FunctionCallNode(String functionName,
                       List<ASTNode> arguments, SourceLocation location) {
        super(location);
        this.functionName = functionName;
        this.arguments = arguments != null ? 
            Collections.unmodifiableList(new ArrayList<>(arguments)) : 
            Collections.emptyList();
    }
    
    public String getFunctionName() {
        return functionName;
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
        sb.append(indent(indent)).append("FunctionCallNode\n");
        sb.append(indent(indent + 1)).append("functionName: ").append(functionName).append("\n");
        sb.append(indent(indent + 1)).append("arguments: ").append(arguments.size()).append("\n");
        for (int i = 0; i < arguments.size(); i++) {
            sb.append(indent(indent + 2)).append("arg[").append(i).append("]:\n");
            sb.append(arguments.get(i).formatString(indent + 3)).append("\n");
        }
        return sb.toString().stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private String functionName;
        private List<ASTNode> arguments;
        
        public Builder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }
        
        public Builder arguments(List<ASTNode> arguments) {
            this.arguments = arguments;
            return this;
        }
        
        @Override
        public ASTNode build() {
            return new FunctionCallNode(functionName, arguments, location);
        }
    }
}
