package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FunctionDefNode extends ASTNode {

    private final String functionName;
    private final ClassReferenceNode returnType;
    private final List<LambdaNode.Parameter> parameters;
    private final ASTNode body;

    /**
     * @deprecated 使用 {@link Builder} 替代。
     */
    @Deprecated
    private FunctionDefNode(String functionName, ClassReferenceNode returnType,
                           List<LambdaNode.Parameter> parameters, ASTNode body,
                           SourceLocation location) {
        super(location);
        this.functionName = functionName;
        this.returnType = returnType;
        this.parameters = parameters != null ?
            Collections.unmodifiableList(new ArrayList<>(parameters)) :
            Collections.emptyList();
        this.body = body;
    }

    public String getFunctionName() {
        return functionName;
    }

    public ClassReferenceNode getReturnType() {
        return returnType;
    }

    public List<LambdaNode.Parameter> getParameters() {
        return parameters;
    }

    public ASTNode getBody() {
        return body;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("FunctionDefNode\n");
        sb.append(indent(indent + 1)).append("name: ").append(functionName).append("\n");
        if (returnType != null) {
            sb.append(indent(indent + 1)).append("returnType: ").append(returnType.getTypeName()).append("\n");
        }
        sb.append(indent(indent + 1)).append("parameters: ").append(parameters.size()).append("\n");
        for (int i = 0; i < parameters.size(); i++) {
            LambdaNode.Parameter param = parameters.get(i);
            sb.append(indent(indent + 2)).append("param[").append(i).append("]: ");
            sb.append(param.type().getSimpleName()).append(" ").append(param.name()).append("\n");
        }
        if (body != null) {
            sb.append(indent(indent + 1)).append("body:\n");
            sb.append(body.formatString(indent + 2)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String functionName;
        private ClassReferenceNode returnType;
        private List<LambdaNode.Parameter> parameters;
        private ASTNode body;

        public Builder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }

        public Builder returnType(ClassReferenceNode returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder parameters(List<LambdaNode.Parameter> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        @Override
        public ASTNode build() {
            return new FunctionDefNode(functionName, returnType, parameters, body, location);
        }
    }
}
