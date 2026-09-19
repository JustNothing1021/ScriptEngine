package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class InstanceofNode extends ASTNode {
    
    private final ASTNode expression;
    private final String typeName;
    /** 模式变量名（{@code o instanceof String s} 中的 {@code s}），无模式绑定时为 null。 */
    private final String patternVariable;

    private InstanceofNode(ASTNode expression, String typeName, String patternVariable,
                           SourceLocation location) {
        super(location);
        this.expression = expression;
        this.typeName = typeName;
        this.patternVariable = patternVariable;
    }
    
    public ASTNode getExpression() {
        return expression;
    }
    
    public String getTypeName() {
        return typeName;
    }

    /** 模式变量名，未使用模式绑定语法时返回 null。 */
    public String getPatternVariable() {
        return patternVariable;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "InstanceofNode\n" +
                indent(indent + 1) + "typeName: " + typeName + "\n" +
                (patternVariable != null
                        ? indent(indent + 1) + "patternVariable: " + patternVariable + "\n" : "") +
                indent(indent + 1) + "expression:\n" +
                expression.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private ASTNode expression;
        private String typeName;
        private String patternVariable;

        public Builder expression(ASTNode expression) {
            this.expression = expression;
            return this;
        }

        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        /** 设置模式变量名（{@code instanceof Type name} 语法）。 */
        public Builder patternVariable(String patternVariable) {
            this.patternVariable = patternVariable;
            return this;
        }

        @Override
        public ASTNode build() {
            return new InstanceofNode(expression, typeName, patternVariable, location);
        }
    }
}
