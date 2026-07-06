package com.justnothing.engine.util;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.nodes.*;

import java.util.List;
import java.util.Map;

public class CodeFormatter {

    private static final String INDENT = "    ";

    public static String format(ASTNode node) {
        return format(node, 0);
    }

    public static String format(ASTNode node, int indent) {
        if (node == null) return "null";

        if (node instanceof AnnotationNode n) return formatAnnotation(n);
        if (node instanceof ArrayAccessNode n) return formatArrayAccess(n, indent);
        if (node instanceof ArrayAssignmentNode n) return formatArrayAssignment(n, indent);
        if (node instanceof ArrayLiteralNode n) return formatArrayLiteral(n, indent);
        if (node instanceof AssignmentNode n) return formatAssignment(n, indent);
        if (node instanceof AsyncNode n) return formatAsync(n, indent);
        if (node instanceof AwaitNode n) return formatAwait(n, indent);
        if (node instanceof BinaryOpNode n) return formatBinaryOp(n, indent);
        if (node instanceof BlockNode n) return formatBlock(n, indent);
        if (node instanceof BreakNode n) return formatBreak(n);
        if (node instanceof CastNode n) return formatCast(n, indent);
        if (node instanceof ClassDeclarationNode n) return formatClassDeclaration(n, indent);
        if (node instanceof ClassReferenceNode n) return formatClassRef(n);
        if (node instanceof ConditionalAssignNode n) return formatConditionalAssign(n, indent);
        if (node instanceof ConstructorCallNode n) return formatConstructorCall(n, indent);
        if (node instanceof ConstructorDeclarationNode n) return formatConstructorDeclaration(n, indent);
        if (node instanceof ContinueNode n) return "continue;";
        if (node instanceof DeleteNode n) return formatDelete(n);
        if (node instanceof DoWhileNode n) return formatDoWhile(n, indent);
        if (node instanceof FieldAccessNode n) return formatFieldAccess(n, indent);
        if (node instanceof FieldAssignmentNode n) return formatFieldAssignment(n, indent);
        if (node instanceof FieldDeclarationNode n) return formatFieldDeclaration(n, indent);
        if (node instanceof ForEachNode n) return formatForEach(n, indent);
        if (node instanceof ForNode n) return formatFor(n, indent);
        if (node instanceof FunctionCallNode n) return formatFunctionCall(n, indent);
        if (node instanceof FunctionDefNode n) return formatFunctionDef(n, indent);
        if (node instanceof IfNode n) return formatIf(n, indent);
        if (node instanceof ImportNode n) return "import " + n.getPackageName() + ";";
        if (node instanceof InstanceofNode n) return formatInstanceof(n, indent);
        if (node instanceof InterpolatedStringNode n) return formatInterpolatedString(n, indent);
        if (node instanceof LabeledStatementNode n) return formatLabeledStatement(n, indent);
        if (node instanceof LambdaNode n) return formatLambda(n, indent);
        if (node instanceof LiteralNode n) return formatLiteral(n);
        if (node instanceof MapLiteralNode n) return formatMapLiteral(n, indent);
        if (node instanceof MethodCallNode n) return formatMethodCall(n, indent);
        if (node instanceof MethodDeclarationNode n) return formatMethodDeclaration(n, indent);
        if (node instanceof MethodReferenceNode n) return formatMethodReference(n, indent);
        if (node instanceof NewArrayNode n) return formatNewArray(n, indent);
        if (node instanceof NullCoalescingAssignNode n) return formatNullCoalescingAssign(n, indent);
        if (node instanceof ParameterNode n) return formatParam(n);
        if (node instanceof PipelineNode n) return formatPipeline(n, indent);
        if (node instanceof ReturnNode n) return formatReturn(n, indent);
        if (node instanceof SafeFieldAccessNode n) return formatSafeFieldAccess(n, indent);
        if (node instanceof SafeMethodCallNode n) return formatSafeMethodCall(n, indent);
        if (node instanceof SuperMethodCallNode n) return formatSuperMethodCall(n, indent);
        if (node instanceof SwitchNode n) return formatSwitch(n, indent);
        if (node instanceof TernaryNode n) return formatTernary(n, indent);
        if (node instanceof ThrowNode n) return formatThrow(n, indent);
        if (node instanceof TryNode n) return formatTry(n, indent);
        if (node instanceof UnaryOpNode n) return formatUnaryOp(n, indent);
        if (node instanceof UsingAliasNode n) return formatUsingAlias(n);
        if (node instanceof UsingStaticNode n) return "using static " + n.getClassName() + ";";
        if (node instanceof VarDeclNode n) return formatVarDecl(n, indent);
        if (node instanceof VariableNode n) return n.getName();
        if (node instanceof WhileNode n) return formatWhile(n, indent);

        return node.toString();
    }

    private static String indent(int level) {
        return INDENT.repeat(level);
    }

    private static String line(int indent, String content) {
        return indent(indent) + content;
    }

    // ==================== Literals / Values ====================

    private static String formatLiteral(LiteralNode n) {
        Object value = n.getValue();
        if (value == null) return "null";
        Class<?> type = n.getType();
        if (type == String.class || type == Character.class || type == char.class) {
            String s = value.toString();
            if (type == char.class || type == Character.class) {
                return "'" + escapeChar(s) + "'";
            }
            return "\"" + escapeString(s) + "\"";
        }
        if (type == Boolean.class || type == boolean.class) {
            return value.toString();
        }
        if (type == Long.class || type == long.class) {
            return value + "L";
        }
        if (type == Float.class || type == float.class) {
            return value + "f";
        }
        if (type == Double.class || type == double.class) {
            String s = value.toString();
            if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                s += ".0";
            }
            return s;
        }
        return value.toString();
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    private static String escapeChar(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatInterpolatedString(InterpolatedStringNode n, int indent) {
        StringBuilder sb = new StringBuilder("\"");
        for (InterpolatedStringNode.Part part : n.getParts()) {
            if (part.isExpression()) {
                sb.append("${").append(format(part.getExpression(), indent)).append("}");
            } else {
                sb.append(escapeString(part.getLiteralText()));
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ==================== Variables / Assignments ====================

    private static String formatAssignment(AssignmentNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        if (n.isDeclaration()) {
            if (n.isFinal()) sb.append("final ");
            if (n.getDeclaredType() != null) {
                sb.append(formatGenericType(n.getDeclaredType())).append(" ");
            }
        }
        sb.append(n.getVariableName());
        sb.append(" = ").append(format(n.getValue(), indent));
        if (!isExpression(n)) sb.append(";");
        return sb.toString();
    }

    private static String formatVarDecl(VarDeclNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : n.getAnnotations()) {
            sb.append(formatAnnotation(a)).append(" ");
        }
        if (n.isFinal()) sb.append("final ");
        if (n.getDeclaredType() != null) {
            sb.append(formatGenericType(n.getDeclaredType())).append(" ");
        }
        sb.append(n.getVarName());
        if (n.getInitializer() != null) {
            sb.append(" = ").append(format(n.getInitializer(), indent));
        }
        if (!isExpression(n)) sb.append(";");
        return sb.toString();
    }

    private static String formatConditionalAssign(ConditionalAssignNode n, int indent) {
        return n.getVariableName() + " ?= " + format(n.getValue(), indent);
    }

    private static String formatNullCoalescingAssign(NullCoalescingAssignNode n, int indent) {
        return n.getVariableName() + " ??= " + format(n.getValue(), indent);
    }

    private static String formatDelete(DeleteNode n) {
        if (n.isDeleteAll()) return "delete *;";
        return "delete " + n.getVariableName() + ";";
    }

    // ==================== Operators ====================

    private static String formatBinaryOp(BinaryOpNode n, int indent) {
        String left = format(n.getLeft(), indent);
        String right = format(n.getRight(), indent);
        String op = n.getOperator().getSymbol();
        return "(" + left + " " + op + " " + right + ")";
    }

    private static String formatUnaryOp(UnaryOpNode n, int indent) {
        String operand = format(n.getOperand(), indent);
        UnaryOpNode.Operator op = n.getOperator();
        if (op.isPrefix()) {
            return op.getSymbol() + operand;
        }
        return operand + op.getSymbol();
    }

    // ==================== Control Flow ====================

    private static String formatBlock(BlockNode n, int indent) {
        List<ASTNode> stmts = n.getStatements();
        if (stmts.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{\n");
        for (ASTNode stmt : stmts) {
            sb.append(line(indent + 1, formatStatement(stmt, indent + 1))).append("\n");
        }
        sb.append(indent(indent)).append("}");
        return sb.toString();
    }

    private static String formatIf(IfNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("if (").append(format(n.getCondition(), indent)).append(") ");
        sb.append(formatBlockOrStmt(n.getThenBlock(), indent));
        if (n.getElseBlock() != null) {
            sb.append(" else ");
            sb.append(formatBlockOrStmt(n.getElseBlock(), indent));
        }
        return sb.toString();
    }

    private static String formatWhile(WhileNode n, int indent) {
        return "while (" + format(n.getCondition(), indent) + ") " + formatBlockOrStmt(n.getBody(), indent);
    }

    private static String formatDoWhile(DoWhileNode n, int indent) {
        return "do " + formatBlockOrStmt(n.getBody(), indent) + " while (" + format(n.getCondition(), indent) + ");";
    }

    private static String formatFor(ForNode n, int indent) {
        String sb = "for (" + (n.getInitialization() != null ? format(n.getInitialization(), indent) : "") +
                "; " +
                (n.getCondition() != null ? format(n.getCondition(), indent) : "") +
                "; " +
                (n.getUpdate() != null ? format(n.getUpdate(), indent) : "") +
                ") " +
                formatBlockOrStmt(n.getBody(), indent);
        return sb;
    }

    private static String formatForEach(ForEachNode n, int indent) {
        StringBuilder sb = new StringBuilder("for (");
        if (n.getItemType() != null) {
            sb.append(n.getItemType().getSimpleName()).append(" ");
        }
        sb.append(n.getItemName()).append(" : ");
        sb.append(format(n.getCollection(), indent)).append(") ");
        sb.append(formatBlockOrStmt(n.getBody(), indent));
        return sb.toString();
    }

    private static String formatSwitch(SwitchNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("switch (").append(format(n.getExpression(), indent)).append(") {\n");
        for (CaseNode c : n.getCases()) {
            sb.append(line(indent + 1, "case " + format(c.getValue(), indent) + ":"));
            for (ASTNode stmt : c.getStatements()) {
                sb.append("\n").append(line(indent + 2, formatStatement(stmt, indent + 2)));
            }
            sb.append("\n");
        }
        if (n.getDefaultCase() != null) {
            sb.append(line(indent + 1, "default:"));
            ASTNode defaultBody = n.getDefaultCase();
            if (defaultBody instanceof BlockNode block) {
                for (ASTNode stmt : block.getStatements()) {
                    sb.append("\n").append(line(indent + 2, formatStatement(stmt, indent + 2)));
                }
            } else {
                sb.append("\n").append(line(indent + 2, formatStatement(defaultBody, indent + 2)));
            }
            sb.append("\n");
        }
        sb.append(indent(indent)).append("}");
        return sb.toString();
    }

    private static String formatTry(TryNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        if (!n.getResources().isEmpty()) {
            sb.append("try (");
            for (int i = 0; i < n.getResources().size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(formatResourceDecl(n.getResources().get(i)));
            }
            sb.append(") ");
        } else {
            sb.append("try ");
        }
        sb.append(format(n.getTryBlock(), indent));
        for (CatchClause cc : n.getCatchClauses()) {
            sb.append(" ").append(formatCatch(cc, indent));
        }
        if (n.getFinallyBlock() != null) {
            sb.append(" finally ").append(format(n.getFinallyBlock(), indent));
        }
        return sb.toString();
    }

    private static String formatCatch(CatchClause cc, int indent) {
        StringBuilder types = new StringBuilder();
        List<Class<?>> exTypes = cc.getExceptionTypes();
        for (int i = 0; i < exTypes.size(); i++) {
            if (i > 0) types.append(" | ");
            types.append(exTypes.get(i).getSimpleName());
        }
        return "catch (" + types + " " + cc.getVariableName() + ") " + format(cc.getBody(), indent);
    }

    private static String formatResourceDecl(ResourceDeclaration rd) {
        if (rd.isReference()) return rd.getVariableName();
        return rd.getType().getSimpleName() + " " + rd.getVariableName() + " = " + format(rd.getInitializer(), 0);
    }

    private static String formatThrow(ThrowNode n, int indent) {
        return "throw " + format(n.getExpression(), indent) + ";";
    }

    private static String formatReturn(ReturnNode n, int indent) {
        if (n.getValue() != null) {
            return "return " + format(n.getValue(), indent) + ";";
        }
        return "return;";
    }

    private static String formatBreak(BreakNode n) {
        if (n.getLabel() != null) return "break " + n.getLabel() + ";";
        return "break;";
    }

    // ==================== Expressions ====================

    private static String formatTernary(TernaryNode n, int indent) {
        return "(" + format(n.getCondition(), indent) + " ? " + format(n.getThenExpr(), indent) + " : " + format(n.getElseExpr(), indent) + ")";
    }

    private static String formatCast(CastNode n, int indent) {
        return "(" + n.getTargetType().getSimpleName() + ") " + format(n.getExpression(), indent);
    }

    private static String formatInstanceof(InstanceofNode n, int indent) {
        return "(" + format(n.getExpression(), indent) + " instanceof " + n.getTypeName() + ")";
    }

    private static String formatArrayAccess(ArrayAccessNode n, int indent) {
        return format(n.getArray(), indent) + "[" + format(n.getIndex(), indent) + "]";
    }

    private static String formatArrayAssignment(ArrayAssignmentNode n, int indent) {
        return format(n.getArray(), indent) + "[" + format(n.getIndex(), indent) + "] = " + format(n.getValue(), indent);
    }

    private static String formatFieldAccess(FieldAccessNode n, int indent) {
        return format(n.getTarget(), indent) + "." + n.getFieldName();
    }

    private static String formatSafeFieldAccess(SafeFieldAccessNode n, int indent) {
        return format(n.getTarget(), indent) + "?." + n.getFieldName();
    }

    private static String formatFieldAssignment(FieldAssignmentNode n, int indent) {
        return format(n.getTarget(), indent) + "." + n.getFieldName() + " = " + format(n.getValue(), indent);
    }

    private static String formatMethodCall(MethodCallNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        if (n.getTarget() != null) {
            sb.append(format(n.getTarget(), indent)).append(".");
        }
        sb.append(n.getMethodName()).append("(");
        sb.append(formatArgs(n.getArguments(), indent));
        sb.append(")");
        return sb.toString();
    }

    private static String formatSafeMethodCall(SafeMethodCallNode n, int indent) {
        return format(n.getTarget(), indent) + "?." + n.getMethodName() + "(" + formatArgs(n.getArguments(), indent) + ")";
    }

    private static String formatSuperMethodCall(SuperMethodCallNode n, int indent) {
        return "super." + n.getMethodName() + "(" + formatArgs(n.getArguments(), indent) + ")";
    }

    private static String formatFunctionCall(FunctionCallNode n, int indent) {
        return n.getFunctionName() + "(" + formatArgs(n.getArguments(), indent) + ")";
    }

    private static String formatConstructorCall(ConstructorCallNode n, int indent) {
        StringBuilder sb = new StringBuilder("new ");
        sb.append(n.getType().getTypeName()).append("(");
        sb.append(formatArgs(n.getArguments(), indent));
        sb.append(")");
        if (n.getArrayInitializer() != null) {
            sb.append(" ").append(format(n.getArrayInitializer(), indent));
        }
        if (n.getAnonymousClass() != null) {
            sb.append(" ").append(formatClassDeclaration(n.getAnonymousClass(), indent));
        }
        return sb.toString();
    }

    private static String formatNewArray(NewArrayNode n, int indent) {
        StringBuilder sb = new StringBuilder("new ");
        sb.append(n.getElementType().getSimpleName());
        if (!n.getSizes().isEmpty()) {
            for (ASTNode size : n.getSizes()) {
                sb.append("[").append(format(size, indent)).append("]");
            }
        } else if (n.getSize() != null) {
            sb.append("[").append(format(n.getSize(), indent)).append("]");
        }
        return sb.toString();
    }

    private static String formatLambda(LambdaNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        List<LambdaNode.Parameter> params = n.getParameters();
        if (params.size() == 1) {
            LambdaNode.Parameter p = params.get(0);
            sb.append(p.name());
        } else {
            sb.append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                LambdaNode.Parameter p = params.get(i);
                if (p.type() != null && !p.type().equals(Object.class)) {
                    sb.append(p.type().getSimpleName()).append(" ");
                }
                sb.append(p.name());
            }
            sb.append(")");
        }
        sb.append(" -> ");
        if (n.getBody() instanceof BlockNode) {
            sb.append(format(n.getBody(), indent));
        } else {
            sb.append(format(n.getBody(), indent));
        }
        return sb.toString();
    }

    private static String formatMethodReference(MethodReferenceNode n, int indent) {
        return format(n.getTarget(), indent) + "::" + n.getMethodName();
    }

    private static String formatPipeline(PipelineNode n, int indent) {
        return format(n.getInput(), indent) + " |> " + format(n.getFunction(), indent);
    }

    private static String formatAsync(AsyncNode n, int indent) {
        return "async " + format(n.getExpression(), indent);
    }

    private static String formatAwait(AwaitNode n, int indent) {
        return "await " + format(n.getExpression(), indent);
    }

    private static String formatArrayLiteral(ArrayLiteralNode n, int indent) {
        if (!n.getElements().isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n.getElements().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(format(n.getElements().get(i), indent));
            }
            sb.append("]");
            return sb.toString();
        }
        if (n.getArrayLength() != null) {
            return "[" + format(n.getArrayLength(), indent) + "]";
        }
        return "[]";
    }

    private static String formatMapLiteral(MapLiteralNode n, int indent) {
        if (n.getEntries().isEmpty()) return "[:]";
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Map.Entry<ASTNode, ASTNode> entry : n.getEntries().entrySet()) {
            if (i > 0) sb.append(", ");
            sb.append(format(entry.getKey(), indent)).append(": ").append(format(entry.getValue(), indent));
            i++;
        }
        sb.append("]");
        return sb.toString();
    }

    // ==================== Declarations ====================

    private static String formatClassDeclaration(ClassDeclarationNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : n.getAnnotations()) {
            sb.append(line(indent, formatAnnotation(a))).append("\n");
        }
        if (n.getModifiers() != null) {
            String mods = n.getModifiers().toModifierString();
            if (!mods.isEmpty()) {
                sb.append(mods).append(" ");
            }
        }
        if (n.isInterface()) {
            sb.append("interface ");
        } else {
            sb.append("class ");
        }
        sb.append(n.getClassName());
        if (!n.getTypeParameters().isEmpty()) {
            sb.append("<");
            for (int i = 0; i < n.getTypeParameters().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(n.getTypeParameters().get(i));
                ClassReferenceNode bound = n.getTypeParameterBounds().get(n.getTypeParameters().get(i));
                if (bound != null) {
                    sb.append(" extends ").append(formatClassRef(bound));
                }
            }
            sb.append(">");
        }
        if (n.getSuperClass() != null) {
            sb.append(" extends ").append(n.getSuperClass().getOriginalTypeName());
        }
        if (!n.getInterfaces().isEmpty()) {
            sb.append(" implements ");
            for (int i = 0; i < n.getInterfaces().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(n.getInterfaces().get(i).getOriginalTypeName());
            }
        }
        sb.append(" {\n");
        for (FieldDeclarationNode field : n.getFields()) {
            sb.append(line(indent + 1, formatFieldDeclaration(field, indent + 1))).append("\n");
        }
        for (ConstructorDeclarationNode ctor : n.getConstructors()) {
            sb.append("\n").append(formatConstructorDeclaration(ctor, indent + 1)).append("\n");
        }
        for (MethodDeclarationNode method : n.getMethods()) {
            sb.append("\n").append(formatMethodDeclaration(method, indent + 1)).append("\n");
        }
        sb.append(indent(indent)).append("}");
        return sb.toString();
    }

    private static String formatFunctionDef(FunctionDefNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        if (n.getReturnType() != null) {
            sb.append(formatClassRef(n.getReturnType())).append(" ");
        }
        sb.append("function ").append(n.getFunctionName());
        sb.append("(");
        for (int i = 0; i < n.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            LambdaNode.Parameter p = n.getParameters().get(i);
            if (p.type() != null && !p.type().equals(Object.class)) {
                sb.append(p.type().getSimpleName()).append(" ");
            }
            sb.append(p.name());
        }
        sb.append(")");
        if (n.getBody() != null) {
            sb.append(" ").append(formatBlockOrStmt(n.getBody(), indent));
        }
        return sb.toString();
    }

    private static String formatMethodDeclaration(MethodDeclarationNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : n.getAnnotations()) {
            sb.append(line(indent, formatAnnotation(a))).append("\n");
        }
        if (n.getModifiers() != null) {
            String mods = n.getModifiers().toModifierString();
            if (!mods.isEmpty()) {
                sb.append(mods).append(" ");
            }
        }
        if (n.getReturnType() != null) {
            sb.append(formatClassRef(n.getReturnType())).append(" ");
        }
        sb.append(n.getMethodName()).append("(");
        sb.append(formatParams(n.getParameters()));
        sb.append(")");
        if (n.getBody() != null) {
            sb.append(" ").append(format(n.getBody(), indent));
        } else {
            sb.append(";");
        }
        return sb.toString();
    }

    private static String formatConstructorDeclaration(ConstructorDeclarationNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : n.getAnnotations()) {
            sb.append(line(indent, formatAnnotation(a))).append("\n");
        }
        if (n.getModifiers() != null) {
            String mods = n.getModifiers().toModifierString();
            if (!mods.isEmpty()) {
                sb.append(mods).append(" ");
            }
        }
        sb.append(n.getClassName()).append("(");
        sb.append(formatParams(n.getParameters()));
        sb.append(")");
        if (n.getBody() != null) {
            sb.append(" ").append(format(n.getBody(), indent));
        } else {
            sb.append("{}");
        }
        return sb.toString();
    }

    private static String formatFieldDeclaration(FieldDeclarationNode n, int indent) {
        StringBuilder sb = new StringBuilder();
        for (AnnotationNode a : n.getAnnotations()) {
            sb.append(formatAnnotation(a)).append(" ");
        }
        if (n.getModifiers() != null) {
            String mods = n.getModifiers().toModifierString();
            if (!mods.isEmpty()) {
                sb.append(mods).append(" ");
            }
        }
        sb.append(formatClassRef(n.getType())).append(" ").append(n.getFieldName());
        if (n.getInitialValue() != null) {
            sb.append(" = ").append(format(n.getInitialValue(), indent));
        }
        sb.append(";");
        return sb.toString();
    }

    // ==================== Labeled ====================

    private static String formatLabeledStatement(LabeledStatementNode n, int indent) {
        return n.getLabel() + ": " + formatStatement(n.getStatement(), indent);
    }

    // ==================== Imports / Using ====================

    private static String formatUsingAlias(UsingAliasNode n) {
        return "using " + n.getAliasName() + " = " + n.getFullClassName() + ";";
    }

    // ==================== Annotations ====================

    private static String formatAnnotation(AnnotationNode n) {
        StringBuilder sb = new StringBuilder("@").append(n.getAnnotationName());
        if (!n.getValues().isEmpty()) {
            sb.append("(");
            boolean first = true;
            for (Map.Entry<String, Object> entry : n.getValues().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(entry.getKey()).append(" = ").append(formatAnnotationValue(entry.getValue()));
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private static String formatAnnotationValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeString(s) + "\"";
        if (value instanceof Class<?> c) return c.getSimpleName() + ".class";
        if (value.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("{");
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatAnnotationValue(arr[i]));
            }
            sb.append("}");
            return sb.toString();
        }
        return value.toString();
    }

    // ==================== Types ====================

    private static String formatClassRef(ClassReferenceNode n) {
        StringBuilder sb = new StringBuilder();
        if (n.getOriginalTypeName() != null) {
            sb.append(n.getOriginalTypeName());
        } else if (n.getResolvedClass() != null) {
            sb.append(n.getResolvedClass().getSimpleName());
        } else {
            sb.append("?");
        }
        if (!n.getTypeArguments().isEmpty()) {
            sb.append("<");
            for (int i = 0; i < n.getTypeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatClassRef(n.getTypeArguments().get(i)));
            }
            sb.append(">");
        }
        sb.append("[]".repeat(Math.max(0, n.getArrayDepth())));
        return sb.toString();
    }

    private static String formatGenericType(GenericType gt) {
        StringBuilder sb = new StringBuilder();
        if (gt.getOriginalTypeName() != null) {
            sb.append(gt.getOriginalTypeName());
        } else if (gt.getRawType() != null) {
            sb.append(gt.getRawType().getSimpleName());
        }
        if (!gt.getTypeArguments().isEmpty()) {
            sb.append("<");
            for (int i = 0; i < gt.getTypeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatGenericType(gt.getTypeArguments().get(i)));
            }
            sb.append(">");
        }
        sb.append("[]".repeat(Math.max(0, gt.getArrayDepth())));
        return sb.toString();
    }

    private static String formatParam(ParameterNode n) {
        if (n.getType() != null) {
            return formatClassRef(n.getType()) + " " + n.getParameterName();
        }
        return n.getParameterName();
    }

    private static String formatParams(List<ParameterNode> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatParam(params.get(i)));
        }
        return sb.toString();
    }

    // ==================== Helpers ====================

    private static String formatArgs(List<ASTNode> args, int indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(format(args.get(i), indent));
        }
        return sb.toString();
    }

    private static String formatBlockOrStmt(ASTNode node, int indent) {
        if (node instanceof BlockNode) {
            return format(node, indent);
        }
        return formatStatement(node, indent);
    }

    private static String formatStatement(ASTNode node, int indent) {
        String code = format(node, indent);
        if (code.endsWith("}") || code.endsWith(";") || code.endsWith(":")) {
            return code;
        }
        return code + ";";
    }

    // Check if a node is used as an expression (not requiring semicolon)
    private static boolean isExpression(ASTNode node) {
        return node instanceof LiteralNode
                || node instanceof VariableNode
                || node instanceof BinaryOpNode
                || node instanceof UnaryOpNode
                || node instanceof TernaryNode
                || node instanceof CastNode
                || node instanceof ArrayAccessNode
                || node instanceof FieldAccessNode
                || node instanceof MethodCallNode
                || node instanceof ConstructorCallNode
                || node instanceof FunctionCallNode
                || node instanceof LambdaNode
                || node instanceof MethodReferenceNode
                || node instanceof InstanceofNode
                || node instanceof InterpolatedStringNode
                || node instanceof PipelineNode
                || node instanceof SafeFieldAccessNode
                || node instanceof SafeMethodCallNode
                || node instanceof SuperMethodCallNode
                || node instanceof ArrayLiteralNode
                || node instanceof MapLiteralNode
                || node instanceof AsyncNode
                || node instanceof AwaitNode
                || node instanceof NewArrayNode
                || node instanceof ConditionalAssignNode
                || node instanceof NullCoalescingAssignNode;
    }
}
