package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.OperatorCallback;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;
import com.justnothing.engine.lexer.Operators;
import com.justnothing.engine.parser.constant.ConstantFolder;

/**
 * 二元操作节点
 * <p>
 * 表示二元操作，如加减乘除、比较、逻辑运算等。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class BinaryOpNode extends ASTNode {
    
    /**
     * 二元操作符
     */
    public enum Operator {
        ADD(Operators.ADD),
        SUBTRACT(Operators.SUBTRACT),
        MULTIPLY(Operators.MULTIPLY),
        DIVIDE(Operators.DIVIDE),
        MODULO(Operators.MODULO),
        POWER(Operators.POWER),
        INT_DIVIDE(Operators.INT_DIVIDE),
        MATH_MODULO(Operators.MATH_MODULO),
        RANGE(Operators.RANGE),
        RANGE_EXCLUSIVE(Operators.RANGE_EXCLUSIVE),
        
        EQUAL(Operators.EQUAL),
        NOT_EQUAL(Operators.NOT_EQUAL),
        LESS_THAN(Operators.LESS_THAN),
        LESS_THAN_OR_EQUAL(Operators.LESS_THAN_OR_EQUAL),
        GREATER_THAN(Operators.GREATER_THAN),
        GREATER_THAN_OR_EQUAL(Operators.GREATER_THAN_OR_EQUAL),
        SPACESHIP(Operators.SPACESHIP),
        
        LOGICAL_AND(Operators.LOGICAL_AND),
        LOGICAL_OR(Operators.LOGICAL_OR),
        
        BITWISE_AND(Operators.BITWISE_AND),
        BITWISE_OR(Operators.BITWISE_OR),
        BITWISE_XOR(Operators.BITWISE_XOR),
        LEFT_SHIFT(Operators.LEFT_SHIFT),
        RIGHT_SHIFT(Operators.RIGHT_SHIFT),
        UNSIGNED_RIGHT_SHIFT(Operators.UNSIGNED_RIGHT_SHIFT),
        
        NULL_COALESCING(Operators.NULL_COALESCING),
        ELVIS(Operators.ELVIS);
        
        private final String symbol;
        
        Operator(String symbol) {
            this.symbol = symbol;
        }
        
        public String getSymbol() {
            return symbol;
        }
    }
    
    private final Operator operator;
    private final ASTNode left;
    private final ASTNode right;
    private OperatorCallback operatorCallback;

    private BinaryOpNode(Operator operator, ASTNode left, ASTNode right, SourceLocation location) {
        super(location);
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    public void setOperatorCallback(OperatorCallback callback) {
        this.operatorCallback = callback;
    }

    public OperatorCallback getOperatorCallback() {
        return operatorCallback;
    }
    
    public Operator getOperator() {
        return operator;
    }
    
    public ASTNode getLeft() {
        return left;
    }
    
    public ASTNode getRight() {
        return right;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "BinaryOpNode\n" +
                indent(indent + 1) + "operator: " + operator.getSymbol() + "\n" +
                indent(indent + 1) + "left:\n" +
                left.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "right:\n" +
                right.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }
    
    public static class Builder extends ASTNode.Builder<Builder> {
        private Operator operator;
        private ASTNode left;
        private ASTNode right;
        
        public Builder operator(Operator operator) {
            this.operator = operator;
            return this;
        }
        
        public Builder left(ASTNode left) {
            this.left = left;
            return this;
        }
        
        public Builder right(ASTNode right) {
            this.right = right;
            return this;
        }
        
        @Override
        public ASTNode build() {
            BinaryOpNode node = new BinaryOpNode(operator, left, right, location);
            return ConstantFolder.fold(node);
        }
    }
}
