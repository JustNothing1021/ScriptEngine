package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;
import com.justnothing.engine.parser.constant.ConstantFolder;

/**
 * 一元操作节点
 * <p>
 * 表示一元操作，如负号、逻辑非、自增自减等。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class UnaryOpNode extends ASTNode {
    
    /**
     * 一元操作符
     */
    public enum Operator {
        NEGATIVE("-"),
        POSITIVE("+"),
        LOGICAL_NOT("!"),
        NOT_NULL("!!"),
        BITWISE_NOT("~"),
        PRE_INCREMENT("++"),
        PRE_DECREMENT("--"),
        POST_INCREMENT("++"),
        POST_DECREMENT("--");
        
        private final String symbol;
        
        Operator(String symbol) {
            this.symbol = symbol;
        }
        
        public String getSymbol() {
            return symbol;
        }
        
        public boolean isPrefix() {
            return this == PRE_INCREMENT || this == PRE_DECREMENT ||
                   this == NEGATIVE || this == POSITIVE ||
                   this == LOGICAL_NOT || this == BITWISE_NOT;
        }
        
        public boolean isPostfix() {
            return this == POST_INCREMENT || this == POST_DECREMENT;
        }
    }
    
    private final Operator operator;
    private final ASTNode operand;
    

    private UnaryOpNode(Operator operator, ASTNode operand, SourceLocation location) {
        super(location);
        this.operator = operator;
        this.operand = operand;
    }
    
    public Operator getOperator() {
        return operator;
    }
    
    public ASTNode getOperand() {
        return operand;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String opDisplay = switch (operator) {
            case PRE_INCREMENT -> "++ (prefix)";
            case PRE_DECREMENT -> "-- (prefix)";
            case POST_INCREMENT -> "++ (postfix)";
            case POST_DECREMENT -> "-- (postfix)";
            default -> operator.getSymbol();
        };
        String sb = indent(indent) + "UnaryOpNode\n" +
                indent(indent + 1) + "operator: " + opDisplay + "\n" +
                indent(indent + 1) + "operand:\n" +
                operand.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private Operator operator;
        private ASTNode operand;
        
        public Builder operator(Operator operator) {
            this.operator = operator;
            return this;
        }
        
        public Builder operand(ASTNode operand) {
            this.operand = operand;
            return this;
        }
        
        @Override
        public ASTNode build() {
            UnaryOpNode node = new UnaryOpNode(operator, operand, location);
            return ConstantFolder.fold(node);
        }
    }
}
