package com.justnothing.engine.parser;

/**
 * Lambda 参数自动推断类型的占位标记。
 * <p>
 * 当 Lambda 参数没有显式类型标注时（如 {@code x -> x * 2}），
 * 用此标记替代 {@code Object.class} 和 {@code null}，使运算符查表等
 * 解析期检查能够区分"类型待推断"和"类型为 Object"。
 * </p>
 *
 * @see com.justnothing.engine.parser.ExprParser#checkCustomOperator
 * @see com.justnothing.engine.parser.ParseContext#declareInferredVariable
 */
public final class InferredType {

    private InferredType() {}

    /** 单例实例，用于在类型系统中标记"自动推断类型"。 */
    public static final InferredType INSTANCE = new InferredType();

    @Override
    public String toString() {
        return "inferred";
    }
}
