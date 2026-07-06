package com.justnothing.engine.parser.constant;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.BinaryOpNode;
import com.justnothing.engine.ast.nodes.LiteralNode;
import com.justnothing.engine.ast.nodes.TernaryNode;
import com.justnothing.engine.ast.nodes.UnaryOpNode;

/**
 * 常量折叠入口。
 * <p>
 * 在解析阶段对纯字面量表达式进行求值，将编译期可确定的计算结果
 * 直接替换为 LiteralNode，实现运行时零开销。
 * </p>
 * <p>
 * 具体折叠逻辑按运算符类别委托给各子类：
 * <ul>
 *   <li>{@link ArithmeticFolder} — 算术运算</li>
 *   <li>{@link BitwiseFolder} — 位运算</li>
 *   <li>{@link LogicalFolder} — 逻辑运算</li>
 *   <li>{@link ComparisonFolder} — 比较运算</li>
 *   <li>{@link ShiftFolder} — 移位运算</li>
 *   <li>{@link UnaryFolder} — 一元运算</li>
 * </ul>
 * </p>
 */
public final class ConstantFolder {

    private ConstantFolder() {
    }

    /**
     * 递归常量折叠入口。
     * <p>
     * 先递归折叠子节点，再尝试折叠当前节点。支持：
     * <ul>
     *   <li>BinaryOpNode — 算术/位/逻辑/比较/移位运算</li>
     *   <li>UnaryOpNode — 一元运算</li>
     *   <li>TernaryNode — 条件表达式（条件为常量时短路）</li>
     * </ul>
     *
     * @param node 待折叠的 AST 节点
     * @return 折叠后的节点（可能被替换为 LiteralNode）
     */
    public static ASTNode fold(ASTNode node) {
        if (node == null) return null;

        // 按节点类型分派
        if (node instanceof BinaryOpNode binary) {
            return fold(binary);
        }
        if (node instanceof UnaryOpNode unary) {
            return fold(unary);
        }
        if (node instanceof TernaryNode ternary) {
            return foldTernary(ternary);
        }

        return node;
    }

    /**
     * 尝试折叠二元运算节点。
     *
     * @param node 二元运算节点
     * @return 折叠后的 LiteralNode，或原始 node（无法折叠时）
     */
    public static ASTNode fold(BinaryOpNode node) {
        // 先递归折叠子节点（确保 1+1==3 中 1+1 先被折叠为 LiteralNode(2)）
        ASTNode left = fold(node.getLeft());
        ASTNode right = fold(node.getRight());

        // 子节点已被折叠，如果无变化则用原始节点
        if (!(left instanceof LiteralNode literalLeft) ||
            !(right instanceof LiteralNode literalRight)) {
            // 子节点有变化但未全部变为字面量，返回折叠后的节点（替换已折叠的子节点）
            if (left != node.getLeft() || right != node.getRight()) {
                return new BinaryOpNode.Builder()
                        .operator(node.getOperator()).left(left).right(right)
                        .location(node.getLocation()).build();
            }
            return node;
        }

        Object lv = literalLeft.getValue();
        Object rv = literalRight.getValue();

        // null 相关特殊处理
        if (lv == null && rv == null) {
            return foldNullBinary(node);
        }

        try {
            Object result = dispatchBinary(node.getOperator(), lv, rv);
            if (result != null) {
                return new LiteralNode.Builder()
                        .value(result).type(TypeInferrer.infer(result))
                        .location(node.getLocation()).build();
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // 除零、溢出等 — 不折叠，保留原表达式让运行时报错
        }

        return node;
    }

    /**
     * 尝试折叠一元运算节点。
     *
     * @param node 一元运算节点
     * @return 折叠后的 LiteralNode，或原始 node
     */
    public static ASTNode fold(UnaryOpNode node) {
        // 先递归折叠操作数
        ASTNode operand = fold(node.getOperand());

        if (!(operand instanceof LiteralNode literalOperand)) {
            // 操作数被折叠了但不是字面量（防御性处理）
            if (operand != node.getOperand()) {
                return new UnaryOpNode.Builder()
                        .operator(node.getOperator()).operand(operand)
                        .location(node.getLocation()).build();
            }
            return node;
        }

        Object value = literalOperand.getValue();
        if (value == null) {
            return node;
        }

        try {
            Object result = UnaryFolder.fold(node.getOperator(), value);
            if (result != null) {
                return new LiteralNode.Builder()
                        .value(result).type(TypeInferrer.infer(result))
                        .location(node.getLocation()).build();
            }
        } catch (Exception e) {
            // 一元运算折叠失败（如溢出），保留原始节点让运行时处理
        }

        return node;
    }

    /**
     * 折叠三元条件表达式。
     * <p>
     * 当条件为常量布尔值时，直接短路返回对应分支：
     * <ul>
     *   <li>{@code true ? a : b} → 折叠为 {@code a}</li>
     *   <li>{@code false ? a : b} → 折叠为 {@code b}</li>
     * </ul>
     * 如果条件不是字面量布尔值，则保留原始 TernaryNode。
     * </p>
     */
    private static ASTNode foldTernary(TernaryNode ternary) {
        // 先递归折叠条件（如 2*3==6 → true），再判断是否可短路
        ASTNode cond = fold(ternary.getCondition());
        if (cond instanceof LiteralNode literal && literal.getValue() instanceof Boolean) {
            boolean value = (Boolean) literal.getValue();
            return value ? ternary.getThenExpr() : ternary.getElseExpr();
        }
        return ternary;
    }

    private static Object dispatchBinary(BinaryOpNode.Operator op, Object left, Object right) {
        return switch (op) {
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, INT_DIVIDE ->
                    ArithmeticFolder.fold(op, left, right);
            case BITWISE_AND, BITWISE_OR, BITWISE_XOR ->
                    BitwiseFolder.fold(op, left, right);
            case LOGICAL_AND, LOGICAL_OR ->
                    LogicalFolder.fold(op, left, right);
            case EQUAL, NOT_EQUAL, LESS_THAN, GREATER_THAN,
                 LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL ->
                    ComparisonFolder.fold(op, left, right);
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT ->
                    ShiftFolder.fold(op, left, right);
            default -> null;
        };
    }

    private static ASTNode foldNullBinary(BinaryOpNode node) {
        return switch (node.getOperator()) {
            case EQUAL -> new LiteralNode.Builder()
                    .value(true).type(boolean.class)
                    .location(node.getLocation()).build();
            case NOT_EQUAL -> new LiteralNode.Builder()
                    .value(false).type(boolean.class)
                    .location(node.getLocation()).build();
            default -> node;
        };
    }
}
