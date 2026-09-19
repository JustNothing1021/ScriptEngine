package com.justnothing.engine.eval;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.NameRef;
import com.justnothing.engine.ast.OperatorCallback;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.ast.visitor.ASTVisitor;
import com.justnothing.engine.builtins.Lambda;
import com.justnothing.engine.builtins.MethodReference;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.exception.BreakException;
import com.justnothing.engine.exception.ContinueException;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.exception.LabeledBreakException;
import com.justnothing.engine.exception.ReturnException;
import com.justnothing.engine.exception.YieldException;
import com.justnothing.engine.util.MethodResolver;
import com.justnothing.engine.util.ReflectCache;
import com.justnothing.engine.parser.JType;
import com.justnothing.engine.parser.OperatorRegistry;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.security.SecurityGate;
import com.justnothing.engine.util.CastUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class Evaluator implements ASTVisitor<Value> {

    private final EvalContext evalContext;
    private final ParseContext parseContext;
    private static int anonSeq = 0;


    public EvalContext getEvalContext() {
        return evalContext;
    }

    public Evaluator(EvalContext evalContext, ParseContext parseContext) {
        this.evalContext = evalContext;
        this.parseContext = parseContext;
    }

    public Value evaluate(ASTNode node) {
        return node.accept(this);
    }

    public List<Value> evaluateAll(List<ASTNode> nodes) {
        List<Value> results = new ArrayList<>();
        for (ASTNode node : nodes) {
            try {
                results.add(evaluate(node));
            } catch (ReturnException e) {
                results.add((Value) e.getValue());
                break;
            }
        }
        return results;
    }

    public Value visit(ASTNode node) {
        if (node instanceof LiteralNode n) return visitLiteral(n);
        if (node instanceof VariableNode n) return visitVariable(n);
        if (node instanceof NameRefNode n) return visitNameRef(n);
        if (node instanceof BinaryOpNode n) return visitBinaryOp(n);
        if (node instanceof UnaryOpNode n) return visitUnaryOp(n);
        if (node instanceof AssignmentNode n) return visitAssignment(n);
        if (node instanceof VarDeclNode n) return visitVarDecl(n);
        if (node instanceof MethodCallNode n) return visitMethodCall(n);
        if (node instanceof FieldAccessNode n) return visitFieldAccess(n);
        if (node instanceof ConstructorCallNode n) return visitConstructorCall(n);
        if (node instanceof TernaryNode n) return visitTernary(n);
        if (node instanceof ArrayAccessNode n) return visitArrayAccess(n);
        if (node instanceof ArrayAssignmentNode n) return visitArrayAssignment(n);
        if (node instanceof ArrayLiteralNode n) return visitArrayLiteral(n);
        if (node instanceof NewArrayNode n) return visitNewArray(n);
        if (node instanceof CastNode n) return visitCast(n);
        if (node instanceof InstanceofNode n) return visitInstanceof(n);
        if (node instanceof PipelineNode n) return visitPipeline(n);
        if (node instanceof BlockNode n) return visitBlock(n);
        if (node instanceof IfNode n) return visitIf(n);
        if (node instanceof WhileNode n) return visitWhile(n);
        if (node instanceof ForNode n) return visitFor(n);
        if (node instanceof ForEachNode n) return visitForEach(n);
        if (node instanceof SwitchNode n) return visitSwitch(n);
        if (node instanceof ReturnNode n) return visitReturn(n);
        if (node instanceof YieldNode n) return visitYield(n);
        if (node instanceof BreakNode n) return visitBreak(n);
        if (node instanceof ContinueNode n) return visitContinue(n);
        if (node instanceof LambdaNode n) return visitLambda(n);
        if (node instanceof FunctionCallNode n) return visitFunctionCall(n);
        if (node instanceof DirectCallNode n) return visitDirectCall(n);
        if (node instanceof AsyncNode n) return visitAsync(n);
        if (node instanceof AwaitNode n) return visitAwait(n);
        if (node instanceof MapLiteralNode n) return visitMapLiteral(n);
        if (node instanceof InterpolatedStringNode n) return visitInterpolatedString(n);
        if (node instanceof FieldAssignmentNode n) return visitFieldAssignment(n);
        if (node instanceof ConditionalAssignNode n) return visitConditionalAssign(n);
        if (node instanceof NullCoalescingAssignNode n) return visitNullCoalescingAssign(n);
        if (node instanceof MethodReferenceNode n) return visitMethodReference(n);
        if (node instanceof SafeFieldAccessNode n) return visitSafeFieldAccess(n);
        if (node instanceof SafeMethodCallNode n) return visitSafeMethodCall(n);
        if (node instanceof ThrowNode n) return visitThrow(n);
        if (node instanceof AssertNode n) return visitAssert(n);
        if (node instanceof SynchronizedNode n) return visitSynchronized(n);
        if (node instanceof DeleteNode n) return visitDelete(n);
        if (node instanceof LabeledStatementNode n) return visitLabeledStatement(n);
        if (node instanceof ImportNode || node instanceof UsingAliasNode
                || node instanceof UsingStaticNode || node instanceof ClassDeclarationNode) {
            return Value.VoidValue.INSTANCE;
        }
        if (node instanceof FunctionDefNode n) return visitFunctionDef(n);
        if (node instanceof DoWhileNode n) return visitDoWhile(n);
        if (node instanceof TryNode n) return visitTry(n);
        if (node instanceof ClassReferenceNode n) return visitClassReference(n);
        throw new EvalException("Unsupported node: " + node.getClass().getSimpleName(), ErrorCode.EVAL_INVALID_OPERATION);
    }

    private Value visitLiteral(LiteralNode node) {
        Object value = node.getValue();
        Class<?> type = node.getType();
        if (value == null) return Value.NullValue.INSTANCE;
        if (type == void.class) return Value.VoidValue.INSTANCE;
        if (value instanceof String s) return new Value.StringValue(s);
        if (value instanceof Integer i) return new Value.IntValue(i);
        if (value instanceof Long l) return new Value.LongValue(l);
        if (value instanceof Double d) return new Value.DoubleValue(d);
        if (value instanceof Boolean b) return new Value.BooleanValue(b);
        if (value instanceof Character c) return new Value.CharValue(c);
        return Value.of(value);
    }

    private Value visitVariable(VariableNode node) {
        String name = node.getName();
        if (evalContext.hasVariable(name)) {
            return evalContext.getVariable(name);
        }
        // 方法体内的裸字段引用（如 v += 1 的读取端）解析为普通变量节点，
        // 但字段被镜像成 $field$ 前缀的变量；名字不是局部变量/形参时回退到镜像
        String mirror = CustomClassExecutor.mirrorName(name);
        if (evalContext.hasVariable(mirror)) {
            return evalContext.getVariable(mirror);
        }
        if (node.isFieldAccess() && node.getDeclaredType() != null) {
            return Value.NullValue.INSTANCE;
        }
        // 回退：尝试将变量名解析为类引用（如 import 的类名在运行时使用）
        if (parseContext != null && parseContext.isKnownClass(name)) {
            Class<?> resolved = parseContext.resolveClass(name);
            if (resolved != null) {
                return Value.of(resolved);
            }
        }
        throw new EvalException("Undefined variable: " + name, ErrorCode.EVAL_UNDEFINED_VARIABLE);
    }

    /**
     * 求值一个解析期未定身份的名字引用。
     * <p>
     * 解析期只登记"这里有个名字"，link 阶段判定过一次身份（{@link NameRef.Kind}）；
     * 但那是解析当时的快照，运行期环境可能已经变了（REPL 里前后两次输入共用运行环境），
     * 所以这里以运行期符号表为准重新判定一次，判定不出来再退回解析期的结论。
     * </p>
     */
    private Value visitNameRef(NameRefNode node) {
        String name = node.getName();
        if (evalContext.hasVariable(name)) {
            return evalContext.getVariable(name);
        }
        // 脚本类方法体内的裸字段引用会被镜像成 $field$ 前缀的变量（同 visitVariable）
        String mirror = CustomClassExecutor.mirrorName(name);
        if (evalContext.hasVariable(mirror)) {
            return evalContext.getVariable(mirror);
        }

        Class<?> resolved = node.getRef().resolvedClass();
        if (resolved == null && parseContext != null) {
            resolved = parseContext.resolveClass(name);
        }
        if (resolved != null) {
            checkClass(resolved); // ★ 安全检查
            return Value.of(resolved);
        }
        throw new EvalException("Undefined variable: " + name, ErrorCode.EVAL_UNDEFINED_VARIABLE);
    }

    private Value visitBinaryOp(BinaryOpNode node) {
        Value left = evaluate(node.getLeft());
        if (node.getOperator() == BinaryOpNode.Operator.NULL_COALESCING) {
            if (!(left instanceof Value.NullValue)) return left;
            return evaluate(node.getRight());
        }
        if (node.getOperator() == BinaryOpNode.Operator.ELVIS) {
            return left.isTruthy() ? left : evaluate(node.getRight());
        }
        // 逻辑短路：右侧仅在需要时求值
        if (node.getOperator() == BinaryOpNode.Operator.LOGICAL_AND) {
            if (!left.isTruthy()) return new Value.BooleanValue(false);
            return new Value.BooleanValue(evaluate(node.getRight()).isTruthy());
        }
        if (node.getOperator() == BinaryOpNode.Operator.LOGICAL_OR) {
            if (left.isTruthy()) return new Value.BooleanValue(true);
            return new Value.BooleanValue(evaluate(node.getRight()).isTruthy());
        }
        Value right = evaluate(node.getRight());
        OperatorRegistry registry = parseContext.getOperatorRegistry();

        // 优先级 1: 解析期缓存的 callback（解析期已绑定，运行期零查找）
        OperatorCallback cached = node.getOperatorCallback();
        if (cached != null) {
            return cached.call(left, right);
        }

        // 优先级 2: 自定义运算符重载（用户定义的运算符）
        Value customResult = tryCustomBinaryOp(node.getOperator(), left, right);
        if (customResult != null) {
            cacheOperatorCallback(node, left, right);
            return customResult;
        }

        // 优先级 3: 从 OperatorRegistry 查找（统一运算符分发，替代旧版 switch-case fallback）
        String opStr = operatorToRegistryString(node.getOperator());
        Class<?> lhsType = getRuntimeType(left);
        Class<?> rhsType = getRuntimeType(right);

        OperatorRegistry.Overload overload = registry.findBinaryCompatible(opStr, lhsType, rhsType);
        if (overload != null) {
            // 将 BiFunction 包装为 OperatorCallback（方法签名不同：apply vs call）
            OperatorCallback opCb = (l, r) -> overload.javaCallback().apply(l, r);
            cacheOperatorCallback(node, opCb);  // 缓存供下次使用
            return opCb.call(left, right);
        }

        throw new EvalException(
                "No matching operator '" + opStr + "' for types '"
                        + lhsType.getSimpleName() + "' and '" + rhsType.getSimpleName() + "'",
                ErrorCode.EVAL_INVALID_OPERATION);
    }


    private Value visitUnaryOp(UnaryOpNode node) {
        Value operand = evaluate(node.getOperand());
        OperatorRegistry registry = parseContext.getOperatorRegistry();
        return switch (node.getOperator()) {
            case POSITIVE -> operand;
            case NOT_NULL -> operand.requiresNonNull();
            case NEGATIVE -> {
                String opStr = operatorToRegistryString(UnaryOpNode.Operator.NEGATIVE);
                Class<?> opType = getRuntimeType(operand);
                OperatorRegistry.Overload overload = registry.findUnaryCompatible(opStr, opType);
                if (overload != null) yield overload.javaCallback().apply(operand, Value.NullValue.INSTANCE);
                throw new EvalException("No matching unary operator '" + opStr + "' for type '" + opType.getSimpleName() + "'", ErrorCode.EVAL_INVALID_OPERATION);
            }
            case LOGICAL_NOT -> {
                String opStr = operatorToRegistryString(UnaryOpNode.Operator.LOGICAL_NOT);
                Class<?> opType = getRuntimeType(operand);
                OperatorRegistry.Overload overload = registry.findUnaryCompatible(opStr, opType);
                if (overload != null) yield overload.javaCallback().apply(operand, Value.NullValue.INSTANCE);
                throw new EvalException("No matching unary operator '" + opStr + "' for type '" + opType.getSimpleName() + "'", ErrorCode.EVAL_INVALID_OPERATION);
            }
            case BITWISE_NOT -> {
                String opStr = operatorToRegistryString(UnaryOpNode.Operator.BITWISE_NOT);
                Class<?> opType = getRuntimeType(operand);
                OperatorRegistry.Overload overload = registry.findUnaryCompatible(opStr, opType);
                if (overload != null) yield overload.javaCallback().apply(operand, Value.NullValue.INSTANCE);
                throw new EvalException("No matching unary operator '" + opStr + "' for type '" + opType.getSimpleName() + "'", ErrorCode.EVAL_INVALID_OPERATION);
            }
            case PRE_INCREMENT -> {
                Value inc = increment(evaluate(node.getOperand()));
                storeLValue(node.getOperand(), inc, "increment");
                yield inc;
            }
            case POST_INCREMENT -> {
                Value val = evaluate(node.getOperand());
                storeLValue(node.getOperand(), increment(val), "increment");
                yield val;
            }
            case PRE_DECREMENT -> {
                Value dec = decrement(evaluate(node.getOperand()));
                storeLValue(node.getOperand(), dec, "decrement");
                yield dec;
            }
            case POST_DECREMENT -> {
                Value val = evaluate(node.getOperand());
                storeLValue(node.getOperand(), decrement(val), "decrement");
                yield val;
            }
        };
    }

    /**
     * 将自增/自减的结果写回左值。
     * <p>支持变量、字段访问（{@code obj.field++}）与数组元素（{@code arr[i]++}）；
     * 基本类型数组元素通过反射写回底层原生数组，避免写入 {@code asArray()} 产生的装箱副本。</p>
     */
    private void storeLValue(ASTNode operand, Value value, String verb) {
        if (operand instanceof VariableNode v) {
            evalContext.assignVariable(v.getName(), value);
            return;
        }
        if (operand instanceof NameRefNode n) {
            // 解析期未定身份的名字：自增/自减写回时按变量处理（同 visitVariable 的兜底口径）
            evalContext.assignVariable(n.getName(), value);
            return;
        }
        if (operand instanceof FieldAccessNode fa) {
            Object obj = evaluate(fa.getTarget()).asJavaObject();
            if (obj == null) throw new EvalException("Cannot access field on null", ErrorCode.EVAL_NULL_POINTER);
            Class<?> resolvedClass = (obj instanceof Class<?> c) ? c : obj.getClass();
            try {
                Field f = resolvedClass.getField(fa.getFieldName());
                checkFieldWrite(f);
                Object receiver = (obj instanceof Class<?>) ? null : obj;
                Object coerced = MethodResolver.coerceArg(f.getType(), value.asJavaObject());
                f.set(receiver, coerced);
                // 同步镜像变量：本方法内后续读取字段走的是镜像，不同步会读到自增前的旧值
                if (isCurrentInstance(obj)) {
                    storeFieldMirror(fa.getFieldName(), Value.of(coerced));
                }
            } catch (EvalException e) {
                throw e;
            } catch (Exception e) {
                throw new EvalException("Field not found: " + fa.getFieldName() + " on " + resolvedClass.getSimpleName(), ErrorCode.EVAL_FIELD_ACCESS_FAILED);
            }
            return;
        }
        if (operand instanceof ArrayAccessNode aa) {
            Object raw = evaluate(aa.getArray()).asJavaObject();
            int i = evaluate(aa.getIndex()).asInt();
            if (raw == null || !raw.getClass().isArray()) {
                throw new EvalException("Not an array: " + (raw == null ? "null" : raw.getClass().getSimpleName()), ErrorCode.EVAL_TYPE_MISMATCH);
            }
            if (i < 0 || i >= Array.getLength(raw)) {
                throw new EvalException("Array index out of bounds: " + i, ErrorCode.EVAL_INDEX_OUT_OF_BOUNDS);
            }
            Array.set(raw, i, MethodResolver.coerceArg(raw.getClass().getComponentType(), value.asJavaObject()));
            return;
        }
        throw new EvalException("Cannot " + verb + " non-variable", ErrorCode.EVAL_INVALID_OPERATION);
    }

    private Value increment(Value v) {
        if (v instanceof Value.IntValue i) return new Value.IntValue(i.getValue() + 1);
        if (v instanceof Value.LongValue l) return new Value.LongValue(l.getValue() + 1);
        if (v instanceof Value.DoubleValue d) return new Value.DoubleValue(d.getValue() + 1.0);
        if (v instanceof Value.CharValue c) return new Value.CharValue((char) (c.getValue() + 1));
        throw new EvalException("Cannot increment type: " + v.getClass().getSimpleName(), ErrorCode.EVAL_INVALID_OPERATION);
    }

    private Value decrement(Value v) {
        if (v instanceof Value.IntValue i) return new Value.IntValue(i.getValue() - 1);
        if (v instanceof Value.LongValue l) return new Value.LongValue(l.getValue() - 1);
        if (v instanceof Value.DoubleValue d) return new Value.DoubleValue(d.getValue() - 1.0);
        if (v instanceof Value.CharValue c) return new Value.CharValue((char) (c.getValue() - 1));
        throw new EvalException("Cannot decrement type: " + v.getClass().getSimpleName(), ErrorCode.EVAL_INVALID_OPERATION);
    }

    private Value visitAssignment(AssignmentNode node) {
        Value value = evaluate(node.getValue());
        String name = node.getVariableName();
        if (node.isDeclaration()) {
            evalContext.declareVariable(name, value);
            return value;
        }
        // 语句层对裸标识符赋值生成的是普通赋值节点，但该名字可能被解析为当前实例的字段。
        // 名字不是局部变量/形参时按字段处理：除了更新镜像，还要写穿到实例字段，
        // 否则字段不会更新，本方法内随后的嵌套调用也看不到这次赋值
        if (!evalContext.hasVariable(name)) {
            Field instanceField = currentInstanceField(name);
            if (instanceField == null) {
                throw new EvalException("Variable not declared: " + name, ErrorCode.SCOPE_VARIABLE_NOT_FOUND);
            }
            storeFieldMirror(name, value);
            writeInstanceField(instanceField, currentInstance(), value);
            return value;
        }
        if (node.isFinal()) {
            throw new EvalException("Cannot assign to final variable: " + name, ErrorCode.SCOPE_CANNOT_ASSIGN_TO_FINAL);
        }
        evalContext.assignVariable(name, value);
        return value;
    }

    /**
     * {@code x ?= v}：等价于 {@code x = x ?: v}（x 为真时保持原值，否则赋 v）。
     */
    private Value visitConditionalAssign(ConditionalAssignNode node) {
        String name = node.getVariableName();
        Value current = evalContext.hasVariable(name) ? evalContext.getVariable(name) : Value.NullValue.INSTANCE;
        if (current.isTruthy()) return current;
        Value value = evaluate(node.getValue());
        if (evalContext.hasVariable(name)) {
            evalContext.assignVariable(name, value);
        } else {
            evalContext.declareVariable(name, value);
        }
        return value;
    }

    /**
     * {@code x ??= v}：等价于 {@code x = x ?? v}（x 为 null 时赋 v）。
     */
    private Value visitNullCoalescingAssign(NullCoalescingAssignNode node) {
        String name = node.getVariableName();
        Value current = evalContext.hasVariable(name) ? evalContext.getVariable(name) : Value.NullValue.INSTANCE;
        if (!(current instanceof Value.NullValue)) return current;
        Value value = evaluate(node.getValue());
        if (evalContext.hasVariable(name)) {
            evalContext.assignVariable(name, value);
        } else {
            evalContext.declareVariable(name, value);
        }
        return value;
    }

    private Value visitVarDecl(VarDeclNode node) {
        Value value;
        if (node.getInitializer() != null) {
            value = evaluate(node.getInitializer());
        } else {
            Class<?> type = node.getDeclaredType() != null ? node.getDeclaredType().getRawType() : null;
            value = defaultForType(type);
        }
        evalContext.declareVariable(node.getVarName(), value);
        return value;
    }

    private Value defaultForType(Class<?> type) {
        if (type == int.class || type == byte.class || type == short.class) return new Value.IntValue(0);
        if (type == long.class) return new Value.LongValue(0L);
        if (type == double.class || type == float.class) return new Value.DoubleValue(0.0);
        if (type == boolean.class) return new Value.BooleanValue(false);
        if (type == char.class) return new Value.CharValue('\0');
        return Value.NullValue.INSTANCE;
    }

    private Value visitMethodCall(MethodCallNode node) {
        List<Value> args = evaluateAll(node.getArguments());
        Object target = resolveTarget(node.getTarget());
        String methodName = node.getMethodName();


        // forEach on Iterable: snapshot to avoid ConcurrentModificationException
        if ("forEach".equals(methodName) && target instanceof Iterable<?> iterable
                && args.size() == 1) {
            Object argObj = args.get(0).asJavaObject();
            if (argObj instanceof Lambda lambda && Lambda.isFunctionalInterface(Consumer.class)) {
                argObj = lambda.asInterface(Consumer.class);
            }
            if (argObj instanceof Consumer) {
                @SuppressWarnings("unchecked")
                Consumer<Object> consumer = (Consumer<Object>) argObj;
                Object[] snapshot;
                if (target instanceof Collection<?> coll) {
                    snapshot = coll.toArray();
                } else {
                    List<Object> tmp = new ArrayList<>();
                    for (Object item : iterable) { tmp.add(item); }
                    snapshot = tmp.toArray();
                }
                for (Object item : snapshot) {
                    consumer.accept(item);
                }
                return Value.VoidValue.INSTANCE;
            }
        }

        // .invoke() on Function<Value[], Value> or Lambda stored in HashMap, etc.
        if ("invoke".equals(methodName)) {
            if (target instanceof Lambda lambda) {
                return lambda.invoke(args.toArray(new Value[0]));
            }
            if (target instanceof Function) {
                @SuppressWarnings("unchecked")
                Function<Value[], Value> func =
                        (Function<Value[], Value>) target;
                return func.apply(args.toArray(new Value[0]));
            }
            if (target instanceof MethodReference mr) {
                return Value.of(mr.invoke(args.stream().map(Value::asJavaObject).toArray()));
            }
        }

        // 无目标方法调用 → 函数调用(builtin 或脚本定义函数)
        if (node.getTarget() == null && evalContext.hasVariable(methodName)) {
            Value funcVal = evalContext.getVariable(methodName);
            if (funcVal instanceof Value.ObjectValue ov) {
                Object raw = ov.getValue();
                if (raw instanceof Lambda lambda) {
                    return lambda.invoke(args.toArray(new Value[0]));
                }
                if (raw instanceof Function) {
                    @SuppressWarnings("unchecked")
                    Function<Value[], Value> func =
                            (Function<Value[], Value>) raw;
                    return func.apply(args.toArray(new Value[0]));
                }
            }
        }
        if (node.getTarget() == null && evalContext.hasBuiltin(methodName)) {
            return evalContext.callBuiltin(methodName, args);
        }

        if (node.getBoundMethod() != null) {
            Method method = node.getBoundMethod();
            // 多态分发：对实例方法，在目标运行时类上查找实际覆写
            if (target != null && !Modifier.isStatic(method.getModifiers())) {
                try {
                    Method override = MethodResolver.accessible(
                            target.getClass().getMethod(method.getName(), method.getParameterTypes()));
                    if (override.getDeclaringClass() != method.getDeclaringClass()) {
                        method = override;
                    }
                } catch (NoSuchMethodException ignored) { }
            }
            try {
                checkMethod(method); // ★ 安全检查
                Object[] javaArgs = prepareInvokeArgs(method, args);
                Object result = method.invoke(target, javaArgs);
                return Value.of(result);
            } catch (InvocationTargetException e) {
                throw new EvalException("Exception in " + methodName + ": " + e.getCause().getMessage(), e.getCause(), ErrorCode.EVAL_EXCEPTION_THROWN);
            } catch (Exception e) {
                throw new EvalException("Method call failed: " + methodName + " (" + e.getMessage() + ")", e, ErrorCode.METHOD_INVOCATION_FAILED);
            }
        }

        Class<?> clazz;
        if (target instanceof Class<?> c) {
            // ★ Class 对象的二义性：
            //   1. Class.getName() / getDeclaredFields() 等 → 在 Class.class 上查找实例方法
            //   2. SomeClass.staticMethod() → 在 c 上查找静态方法
            //   策略：先尝试 Class.class 实例方法，找不到再回退到 c
            boolean foundOnClassClass = false;
            for (Method m : Class.class.getMethods()) {
                if (m.getName().equals(methodName)) {
                    foundOnClassClass = true;
                    break;
                }
            }
            clazz = foundOnClassClass ? Class.class : c;
        } else if (target != null) {
            clazz = target.getClass();
        } else {
            clazz = findClassForMethod(methodName);
        }
        if (clazz == null) {
            throw new EvalException("Cannot resolve method: " + methodName, ErrorCode.METHOD_NOT_FOUND);
        }
        // 遍历所有重载，尝试匹配（支持 varargs）
        Method bestMatch = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : ReflectCache.methods(clazz)) {
            if (!m.getName().equals(methodName)) continue;
            // 构造参数类型数组用于 isApplicable 判断
            Class<?>[] argTypes = new Class<?>[args.size()];
            for (int i = 0; i < args.size(); i++) {
                Object argObj = args.get(i).asJavaObject();
                argTypes[i] = argObj != null ? argObj.getClass() : Object.class;
            }
            if (!MethodResolver.isApplicable(m, argTypes)) continue;
            // 计算匹配分数（优先非 varargs 精确匹配）
            int score = computeMethodMatchScore(m, argTypes);
            if (score < bestScore) {
                bestScore = score;
                bestMatch = m;
            }
        }
        if (bestMatch != null) {
            try {
                checkMethod(bestMatch);
                Object[] javaArgs = prepareInvokeArgs(bestMatch, args);
                Object result = bestMatch.invoke(target, javaArgs);
                return Value.of(result);
            } catch (InvocationTargetException e) {
                throw new EvalException("Exception in " + methodName + ": " + e.getCause().getMessage(), e.getCause(), ErrorCode.EVAL_EXCEPTION_THROWN);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                // 类型不匹配，不应该发生（isApplicable 已通过），但防万一
            }
        }
        // 构建友好的错误信息
        String typeList = args.isEmpty() ? "()"
                : "(" + String.join(", ",
                    args.stream().map(a -> {
                        Object o = a.asJavaObject();
                        return o != null ? o.getClass().getName() : "null";
                    }).toArray(String[]::new)) + ")";
        assert target != null;
        throw new EvalException("No applicable method found: " +
                target.getClass().getName() + "." +  methodName + typeList, ErrorCode.METHOD_NO_APPLICABLE_METHOD);
    }

    Object resolveTarget(ASTNode targetNode) {
        if (targetNode == null) return null;
        Value targetVal = evaluate(targetNode);
        return targetVal.asJavaObject();
    }

    Class<?> findClassForMethod(String methodName) {
        try {
            checkClassByName(methodName); // ★ 安全检查
            return Class.forName(methodName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * 计算方法匹配分数（越低越好）。
     * 非varargs精确匹配优先于varargs匹配。
     */
    private static int computeMethodMatchScore(Method method, Class<?>[] argTypes) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (!method.isVarArgs()) {
            // 精确匹配：每个参数的转换代价
            int score = 0;
            for (int i = 0; i < paramTypes.length; i++) {
                score += paramTypeScore(paramTypes[i], argTypes[i]);
            }
            return score;
        }
        // varargs 匹配：加分（优先选择非 varargs 重载）
        int fixedCount = paramTypes.length - 1;
        int score = 1000;  // varargs 基础惩罚分
        for (int i = 0; i < fixedCount && i < argTypes.length; i++) {
            score += paramTypeScore(paramTypes[i], argTypes[i]);
        }
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < argTypes.length; i++) {
            score += paramTypeScore(componentType, argTypes[i]);
        }
        return score;
    }

    private static int paramTypeScore(Class<?> paramType, Class<?> argType) {
        if (paramType == argType) return 0;
        // char 实参：优先精确匹配 char 形参（避免误选 int/Character 重载，如 println(char) vs println(int)）
        if (argType == Character.class && (paramType == char.class || paramType == Character.class)) return 0;
        if (paramType == char.class && argType == char.class) return 0;
        if (paramType.isAssignableFrom(argType)) {
            if (paramType == Object.class) return 10;
            return 1;
        }
        return 100; // 需要装箱/拆箱
    }

    private static boolean hasMethodNamed(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    /**
     * 准备方法调用的参数数组，处理 varargs 打包。
     * 对于非 varargs 方法：直接逐个 coerce。
     * 对于 varargs 方法：固定参数逐个 coerce，可变参数打包成数组。
     */
    private static Object[] prepareInvokeArgs(Method method, List<Value> args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (!method.isVarArgs()) {
            // 非 varargs：直接 1 对 1
            Object[] result = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                result[i] = MethodResolver.coerceArg(paramTypes[i], args.get(i).asJavaObject());
            }
            return result;
        }
        // varargs：固定部分 + 打包的可变部分
        int fixedCount = paramTypes.length - 1;
        Object[] result = new Object[fixedCount + 1];
        for (int i = 0; i < fixedCount; i++) {
            result[i] = MethodResolver.coerceArg(paramTypes[i],
                    i < args.size() ? args.get(i).asJavaObject() : null);
        }
        // 打包剩余参数为 varargs 数组
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        int varargsCount = args.size() - fixedCount;
        Object varargsArray = java.lang.reflect.Array.newInstance(componentType, Math.max(0, varargsCount));
        for (int i = 0; i < varargsCount; i++) {
            Object coerced = MethodResolver.coerceArg(componentType, args.get(fixedCount + i).asJavaObject());
            java.lang.reflect.Array.set(varargsArray, i, coerced);
        }
        result[fixedCount] = varargsArray;
        return result;
    }

    private Value visitClassReference(ClassReferenceNode node) {
        Class<?> resolvedClass = node.getResolvedClass();
        if (resolvedClass != null) {
            checkClass(resolvedClass); // ★ 安全检查
            return Value.of(resolvedClass);
        }
        try {
            checkClassByName(node.getOriginalTypeName()); // ★ 安全检查
            return Value.of(Class.forName(node.getOriginalTypeName()));
        } catch (ClassNotFoundException e) {
            throw new EvalException("Unknown class: " + node.getOriginalTypeName(), ErrorCode.EVAL_CLASS_NOT_FOUND);
        }
    }

    private Value visitFieldAccess(FieldAccessNode node) {
        Value target = evaluate(node.getTarget());
        Object obj = target.asJavaObject();
        if (obj == null) throw new EvalException("Cannot access field on null", ErrorCode.EVAL_NULL_POINTER);

        // 脚本类方法体内的 this.field：字段被 CustomClassExecutor 镜像成局部变量，
        // 读取必须走镜像变量，否则读到的是尚未写回的旧字段值
        if (isCurrentInstance(obj)) {
            String mirror = CustomClassExecutor.mirrorName(node.getFieldName());
            if (evalContext.hasVariable(mirror)) {
                return evalContext.getVariable(mirror);
            }
        }

        // 静态字段访问：target 已经是 Class<?>
        Class<?> resolvedClass = (obj instanceof Class<?> c) ? c : obj.getClass();

        if (node.getBoundField() != null) {
            try {
                checkFieldRead(node.getBoundField()); // ★ 安全检查
                return Value.of(node.getBoundField().get(obj));
            } catch (Exception e) {
                    throw new EvalException("Field access failed: " + node.getFieldName() + " (" + e.getMessage() + ")", e, ErrorCode.EVAL_FIELD_ACCESS_FAILED);
            }
        }

        try {
            // 特判：数组的 .length 不是普通 Field，用 Array.getLength 获取
            if (resolvedClass.isArray() && "length".equals(node.getFieldName())) {
                return Value.of(Array.getLength(obj));
            }
            Field f = resolvedClass.getField(node.getFieldName());
            checkFieldRead(f); // ★ 安全检查
            return Value.of(f.get(obj));
        } catch (Exception e) {
            throw new EvalException("Field not found: " + node.getFieldName() + " on " + resolvedClass.getSimpleName(), ErrorCode.EVAL_FIELD_ACCESS_FAILED);
        }
    }

    private Value visitConstructorCall(ConstructorCallNode node) {
        List<Value> args = evaluateAll(node.getArguments());

        // 匿名类：动态生成类 + 实例化
        ClassDeclarationNode anonClass = node.getAnonymousClass();
        if (anonClass != null && parseContext != null) {
            DynamicClassGenerator dcg = parseContext.getCodeGenerator();
            if (dcg != null) {
                String anonName = anonClassName(node);

                // 解析父类构造器签名：根据实参匹配父类构造器，获取参数类型列表
                List<Class<?>> superArgTypes = resolveSuperConstructorArgTypes(anonClass, args);

                Class<?> generated = dcg.generateAnonymous(anonName, anonClass, superArgTypes);
                if (generated != null) {
                    try {
                        Constructor<?> anonCtor = generated.getDeclaredConstructors()[0];
                        checkConstructor(anonCtor); // ★ 安全检查
                        Object result = anonCtor.newInstance(
                                args.stream().map(Value::asJavaObject).toArray());
                        return Value.of(result);
                    } catch (Exception e) {
                        throw new EvalException("Anonymous class construction failed: " + anonName, e, ErrorCode.EVAL_CONSTRUCTOR_INVOCATION_FAILED);
                    }
                }
            }
        }

        String className = node.getClassName();

        Class<?> clazz = findClass(className);
        if (clazz == null) {
            throw new EvalException("Class not found: " + className, ErrorCode.EVAL_CLASS_NOT_FOUND);
        }

        try {
            for (Constructor<?> ctor : clazz.getConstructors()) {
                if (ctor.getParameterCount() != args.size() && !ctor.isVarArgs()) continue;
                if (args.size() < ctor.getParameterCount() - (ctor.isVarArgs() ? 1 : 0)) continue;
                try {
                    checkConstructor(ctor); // 安全检查
                    Object[] javaArgs = convertArgsForParameters(args, ctor.getParameterTypes(), ctor.isVarArgs());
                    Object result = ctor.newInstance(javaArgs);
                    return Value.of(result);
                } catch (InvocationTargetException e) {
                    throw new EvalException("Exception in " + className + " constructor: " + e.getCause().getMessage(), e.getCause(), ErrorCode.EVAL_EXCEPTION_THROWN);
                } catch (IllegalArgumentException | InstantiationException | IllegalAccessException e) {
                    // argument mismatch or can't instantiate, try next constructor
                }
            }
            throw new EvalException("No matching constructor for " + className, ErrorCode.EVAL_CONSTRUCTOR_INVOCATION_FAILED);
        } catch (Exception e) {
            throw new EvalException("Construction failed: " + className, e, ErrorCode.EVAL_CONSTRUCTOR_INVOCATION_FAILED);
        }
    }


    private static String anonClassName(ConstructorCallNode node) {
        String base = node.getClassName().replace('.', '_');
        return "anon$" + base + "$" + (++anonSeq);
    }

    /**
     * 根据实参列表匹配匿名类父类的构造器，返回参数类型列表。
     * <p>匹配逻辑与普通构造器调用一致：遍历所有 public 构造器，
     * 找到参数数量兼容的第一个（支持 varargs）。
     *
     * @param anonClass 匿名类声明（superClass 已设置）
     * @param args      实际参数值列表（已求值）
     * @return 父类构造器的参数类型列表；无参时返回空列表（非 null）；匹配失败返回 null
     */
    private List<Class<?>> resolveSuperConstructorArgTypes(ClassDeclarationNode anonClass, List<Value> args) {
        ClassReferenceNode superRef = anonClass.getSuperClass();
        if (superRef == null) return List.of();

        Class<?> superClass = superRef.getResolvedClass();
        if (superClass == null) return List.of();

        try {
            for (Constructor<?> ctor : superClass.getConstructors()) {
                if (ctor.getParameterCount() != args.size() && !ctor.isVarArgs()) continue;
                if (args.size() < ctor.getParameterCount() - (ctor.isVarArgs() ? 1 : 0)) continue;
                // 找到兼容的构造器
                return Arrays.asList(ctor.getParameterTypes());
            }
        } catch (Exception ignored) {
            // 安全限制等异常 → 返回 null 让 DCG fallback 到无参
        }

        // 无匹配构造器 → 返回空列表（无参 fallback）
        return args.isEmpty() ? List.of() : null;
    }

    private Class<?> findClass(String className) {
        if (parseContext != null) {
            Class<?> resolved = parseContext.resolveClass(className);
            if (resolved != null) {
                checkClass(resolved); // ★ 安全检查
                return resolved;
            }
        }
        // 回退
        try {
            checkClassByName(className); // ★ 安全检查
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Value visitTernary(TernaryNode node) {
        Value cond = evaluate(node.getCondition());
        return cond.isTruthy() ? evaluate(node.getThenExpr()) : evaluate(node.getElseExpr());
    }

    private Value visitArrayAccess(ArrayAccessNode node) {
        Value array = evaluate(node.getArray());
        Value index = evaluate(node.getIndex());
        Object[] arr = array.asArray();
        int i = index.asInt();
        if (i < 0 || i >= arr.length) throw new EvalException("Array index out of bounds: " + i, ErrorCode.EVAL_INDEX_OUT_OF_BOUNDS);
        return Value.of(arr[i]);
    }

    private Value visitArrayAssignment(ArrayAssignmentNode node) {
        Value array = evaluate(node.getArray());
        Value index = evaluate(node.getIndex());
        Value value = evaluate(node.getValue());
        Object raw = array.asJavaObject();
        int i = index.asInt();
        if (raw == null || !raw.getClass().isArray()) {
            throw new EvalException("Not an array: " + (raw == null ? "null" : raw.getClass().getSimpleName()), ErrorCode.EVAL_TYPE_MISMATCH);
        }
        if (i < 0 || i >= Array.getLength(raw)) throw new EvalException("Array index out of bounds: " + i, ErrorCode.EVAL_INDEX_OUT_OF_BOUNDS);
        // 直接写回底层原生数组：asArray() 对基本类型数组会返回装箱副本，写入副本会丢失修改
        Array.set(raw, i, MethodResolver.coerceArg(raw.getClass().getComponentType(), value.asJavaObject()));
        return value;
    }

    private Value visitArrayLiteral(ArrayLiteralNode node) {
        List<Value> elements = evaluateAll(node.getElements());

        // 嵌套数组字面量（{{1}, {2}} / {{"a"}, {"b"}}）：元素本身是数组，
        // 按元素的运行期类型确定组件类型，构建真正的多维数组；
        // 否则仅按解析期注释创建 int[2] 之类的低维数组，写入数组元素时会类型不匹配。
        if (allSameArrayType(elements)) {
            Class<?> componentType = elements.get(0).asJavaObject().getClass();
            Object nested = Array.newInstance(componentType, elements.size());
            for (int i = 0; i < elements.size(); i++) {
                Array.set(nested, i, elements.get(i).asJavaObject());
            }
            return new Value.ArrayValue(nested);
        }

        // 根据解析期类型注释创建对应类型的数组（int[] / double[] / Object[] 等）
        JType type = parseContext != null ? parseContext.getType(node) : null;
        if (type != null && type.getArrayDepth() > 0) {
            Class<?> baseType = type.getRawType();
            if (baseType.isPrimitive()) {
                Object arr = Array.newInstance(baseType, elements.size());
                for (int i = 0; i < elements.size(); i++) {
                    Array.set(arr, i, elements.get(i).asJavaObject());
                }
                return new Value.ArrayValue(arr);
            }
        }
        return new Value.ArrayValue(elements.stream().map(Value::asJavaObject).toArray());
    }

    /** 元素全部为非空数组且运行期类型一致时返回 true（用于识别嵌套数组字面量）。 */
    private static boolean allSameArrayType(List<Value> elements) {
        if (elements.isEmpty()) {
            return false;
        }
        Class<?> first = null;
        for (Value v : elements) {
            Object raw = v.asJavaObject();
            if (raw == null || !raw.getClass().isArray()) {
                return false;
            }
            if (first == null) {
                first = raw.getClass();
            } else if (first != raw.getClass()) {
                return false;
            }
        }
        return true;
    }

    private Value visitNewArray(NewArrayNode node) {
        // new Type[] {…}：直接用初始化器构建类型化数组
        if (node.getInitializer() != null) {
            return buildArrayFromInitializer(node);
        }

        List<ASTNode> sizes = node.getSizes();
        int dimCount = sizes.size();
        // 找到最后一个非 null 维度来确定实际创建深度
        int nonNullCount = 0;
        int trailingNulls;
        for (int i = dimCount - 1; i >= 0; i--) {
            if (sizes.get(i) != null) {
                nonNullCount = i + 1;
                break;
            }
        }
        trailingNulls = dimCount - nonNullCount;

        Class<?> baseType = node.getElementType() != null ? node.getElementType() : Object.class;
        if (dimCount <= 1 || nonNullCount == 0) {
            // 单维或无具体维度：用 size
            Value size = evaluate(node.getSize());
            int len = size.asInt();
            Object javaArray = Array.newInstance(baseType, len);
            Object[] boxed = new Object[len];
            for (int i = 0; i < len; i++) {
                boxed[i] = Array.get(javaArray, i);
            }
            return new Value.ArrayValue(boxed);
        }

        // 多维度：只取非 null 的维度创建
        int[] lens = new int[nonNullCount];
        for (int i = 0; i < nonNullCount; i++) {
            lens[i] = evaluate(sizes.get(i)).asInt();
        }
        // 组件类型要加上 trailing nulls 的数组维度
        Class<?> componentType = baseType;
        for (int i = 0; i < trailingNulls; i++) {
            componentType = Array.newInstance(componentType, 0).getClass();
        }
        Object javaArray = Array.newInstance(componentType, lens);
        return arrayValueFromJavaArray(javaArray, nonNullCount);
    }

    /**
     * 由花括号初始化器构建类型化数组。
     * <p>
     * 支持 {@code new String[] {"a", "b"}} 以及多维嵌套
     * {@code new int[][] {{1, 2}, {3, 4}}}；元素类型取自声明的类型，
     * 因此 {@code new String[]{…}} 得到的是 {@code String[]} 而非 {@code Object[]}。
     * </p>
     */
    private Value buildArrayFromInitializer(NewArrayNode node) {
        Class<?> componentType = node.getElementType() != null ? node.getElementType() : Object.class;
        // 按声明的维度逐层降维：int[][] 的组件类型是 int[]
        for (int depth = 1; depth < node.getSizes().size(); depth++) {
            componentType = Array.newInstance(componentType, 0).getClass();
        }
        return new Value.ArrayValue(fillArrayFromInitializer(componentType, node.getInitializer()));
    }

    private Object fillArrayFromInitializer(Class<?> componentType, ArrayLiteralNode literal) {
        List<ASTNode> elements = literal.getElements();
        Object array = Array.newInstance(componentType, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            ASTNode element = elements.get(i);
            if (element instanceof ArrayLiteralNode nested) {
                Class<?> nestedComponent = componentType.isArray()
                        ? componentType.getComponentType()
                        : componentType;
                Array.set(array, i, fillArrayFromInitializer(nestedComponent, nested));
            } else {
                Array.set(array, i, evaluate(element).asJavaObject());
            }
        }
        return array;
    }

    private Value arrayValueFromJavaArray(Object javaArray, int depth) {
        int len = Array.getLength(javaArray);
        if (depth <= 1) {
            Object[] boxed = new Object[len];
            for (int i = 0; i < len; i++) {
                boxed[i] = Array.get(javaArray, i);
            }
            return new Value.ArrayValue(boxed);
        }
        Object[] boxed = new Object[len];
        for (int i = 0; i < len; i++) {
            Object elem = Array.get(javaArray, i);
            if (elem != null && elem.getClass().isArray()) {
                boxed[i] = arrayValueFromJavaArray(elem, depth - 1);
            } else {
                boxed[i] = elem;
            }
        }
        return new Value.ArrayValue(boxed);
    }

    private Value visitCast(CastNode node) {
        Value value = evaluate(node.getExpression());
        return CastUtils.castValue(value, node.getTargetType());
    }

    private Value visitInstanceof(InstanceofNode node) {
        Value value = evaluate(node.getExpression());
        String typeName = node.getTypeName();
        checkClassByName(typeName); // ★ 安全检查
        Class<?> clazz = resolveTypeName(typeName);
        if (clazz == null) {
            throw new EvalException("Unknown type in instanceof: " + typeName, ErrorCode.EVAL_CLASS_NOT_FOUND);
        }
        Object raw = value.asJavaObject();
        boolean matched = raw != null && clazz.isInstance(raw);
        // 模式变量绑定：o instanceof String s 匹配成功时把值绑到 s（供 then 分支使用）
        if (matched && node.getPatternVariable() != null) {
            evalContext.declareVariable(node.getPatternVariable(), value);
        }
        return new Value.BooleanValue(matched);
    }

    /**
     * 解析 instanceof / 类型名：支持简单名（含 import）、全限定名以及数组类型（{@code int[]}）。
     * <p>解析器存入的是源码原文，因此必须经过 {@link ParseContext#resolveClass} 而非直接 Class.forName。</p>
     */
    private Class<?> resolveTypeName(String typeName) {
        if (typeName == null) return null;
        if (typeName.endsWith("[]")) {
            Class<?> component = resolveTypeName(typeName.substring(0, typeName.length() - 2));
            return component == null ? null : Array.newInstance(component, 0).getClass();
        }
        Class<?> primitive = primitiveClass(typeName);
        if (primitive != null) return primitive;
        Class<?> resolved = parseContext != null ? parseContext.resolveClass(typeName) : null;
        if (resolved != null) return resolved;
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<?> primitiveClass(String name) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> null;
        };
    }

    private final PipelineDispatcher pipelineDispatcher = new PipelineDispatcher(this);

    private Value visitPipeline(PipelineNode node) {
        try {
            return pipelineDispatcher.dispatch(node);
        } catch (Exception e) {

            throw new EvalException("Failed to dispatch pipeline: " + e.getMessage(), e, ErrorCode.EVAL_ERROR);
        }
    }

    private Value visitBlock(BlockNode node) {
        // 非词法块（多变量声明、for 的逗号更新子句等）：在当前作用域内顺序执行，
        // 声明的变量必须留在外层，不能被块结束时的子上下文一起丢弃
        if (!node.isScoped()) {
            Value result = Value.VoidValue.INSTANCE;
            for (ASTNode stmt : node.getStatements()) {
                result = evaluate(stmt);
            }
            return result;
        }
        EvalContext childCtx = evalContext.createChild();
        Evaluator childEval = new Evaluator(childCtx, parseContext);
        Value result = Value.VoidValue.INSTANCE;
        for (ASTNode stmt : node.getStatements()) {
            result = childEval.evaluate(stmt);
        }
        return result;
    }

    private Value visitIf(IfNode node) {
        Value cond = evaluate(node.getCondition());
        if (cond.isTruthy()) {
            return evaluate(node.getThenBlock());
        } else if (node.getElseBlock() != null) {
            return evaluate(node.getElseBlock());
        }
        return Value.VoidValue.INSTANCE;
    }

    private Value visitWhile(WhileNode node) {
        Value result = Value.VoidValue.INSTANCE;
        while (true) {
            Value cond = evaluate(node.getCondition());
            if (!cond.isTruthy()) break;
            try {
                result = evaluate(node.getBody());
            } catch (BreakException e) {
                break;
            } catch (ContinueException ignoredAsNaturalCtrlFlow) {
            }
        }
        return result;
    }

    private Value visitDoWhile(DoWhileNode node) {
        Value result = Value.VoidValue.INSTANCE;
        do {
            try {
                result = evaluate(node.getBody());
            } catch (BreakException e) {
                break;
            } catch (ContinueException ignoredAsNaturalCtrlFlow) {
            }
        } while (evaluate(node.getCondition()).isTruthy());
        return result;
    }

    private Value visitFor(ForNode node) {
        EvalContext loopCtx = evalContext.createChild();
        Evaluator loopEval = new Evaluator(loopCtx, parseContext);
        Value result = Value.VoidValue.INSTANCE;

        if (node.getInitialization() != null) loopEval.evaluate(node.getInitialization());
        while (node.getCondition() == null || loopEval.evaluate(node.getCondition()).isTruthy()) {
            try {
                result = loopEval.evaluate(node.getBody());
            } catch (BreakException e) {
                break;
            } catch (ContinueException ignoredAsNaturalCtrlFlow) {
            }
            if (node.getUpdate() != null) loopEval.evaluate(node.getUpdate());
        }
        return result;
    }

    private Value visitForEach(ForEachNode node) {
        Value collection = evaluate(node.getCollection());
        Object[] items;
        if (collection.asJavaObject() instanceof Iterable<?> iter) {
            List<Object> list = new ArrayList<>();
            for (Object item : iter) list.add(item);
            items = list.toArray();
        } else {
            items = collection.asArray();
        }
        EvalContext loopCtx = evalContext.createChild();
        Evaluator loopEval = new Evaluator(loopCtx, parseContext);
        Value result = Value.VoidValue.INSTANCE;
        loopCtx.declareVariable(node.getItemName(), Value.of(null)); // 先在这一层声明
        for (Object item : items) {
            loopCtx.assignVariable(node.getItemName(), Value.of(item));
            try {
                result = loopEval.evaluate(node.getBody());
            } catch (BreakException e) {
                break;
            } catch (ContinueException e) {
                // continue to next iteration
            }
        }
        return result;
    }

    private Value visitSwitch(SwitchNode node) {
        // switch 表达式的值可以来自分支体里的 yield expr;（Java 14+ 冒号风格），
        // yield 会穿过嵌套块/循环抛到这里
        try {
            return evaluateSwitch(node);
        } catch (YieldException e) {
            return (Value) e.getValue();
        }
    }

    private Value evaluateSwitch(SwitchNode node) {
        Value expr = evaluate(node.getExpression());
        List<CaseNode> cases = node.getCases();

        int start = -1;
        for (int i = 0; i < cases.size(); i++) {
            if (matchesCase(expr, cases.get(i))) { start = i; break; }
        }
        if (start < 0) {
            return evaluateDefaultCase(node);
        }

        Value result = Value.VoidValue.INSTANCE;
        for (int i = start; i < cases.size(); i++) {
            CaseNode caseNode = cases.get(i);
            EvalContext childCtx = evalContext.createChild();
            Evaluator childEval = new Evaluator(childCtx, parseContext);
            boolean broke = false;
            for (ASTNode stmt : caseNode.getStatements()) {
                try {
                    result = childEval.evaluate(stmt);
                } catch (BreakException e) {
                    broke = true;
                    break;
                }
            }
            // 命中 break 或箭头式 case：停止，不再贯穿
            if (broke || caseNode.isArrowStyle()) return result;
        }
        // 冒号式 case 贯穿到末尾且未 break → 继续执行 default（Java 中 default 位于末尾时的语义）
        if (node.getDefaultCase() != null) {
            result = evaluateDefaultCase(node);
        }
        return result;
    }

    /** 判断 case 声明值中是否有一个匹配 switch 表达式（数值类型之间按数值比较，如 long 与 int 常量）。 */
    private boolean matchesCase(Value expr, CaseNode caseNode) {
        for (ASTNode valueNode : caseNode.getValues()) {
            if (switchValuesEqual(expr, evaluate(valueNode))) return true;
        }
        return false;
    }

    private static boolean switchValuesEqual(Value a, Value b) {
        if (isIntegral(a) && isIntegral(b)) return integralAsLong(a) == integralAsLong(b);
        if (isNumeric(a) && isNumeric(b)) return Double.compare(a.asDouble(), b.asDouble()) == 0;
        return a.equals(b);
    }

    private static boolean isIntegral(Value v) {
        return v instanceof Value.IntValue || v instanceof Value.LongValue || v instanceof Value.CharValue;
    }

    private static boolean isNumeric(Value v) {
        return isIntegral(v) || v instanceof Value.DoubleValue;
    }

    private static long integralAsLong(Value v) {
        if (v instanceof Value.IntValue i) return i.getValue();
        if (v instanceof Value.LongValue l) return l.getValue();
        return ((Value.CharValue) v).getValue();
    }

    private Value evaluateDefaultCase(SwitchNode node) {
        ASTNode defaultCase = node.getDefaultCase();
        if (defaultCase == null) return Value.VoidValue.INSTANCE;
        if (defaultCase instanceof BlockNode block) {
            EvalContext childCtx = evalContext.createChild();
            Evaluator childEval = new Evaluator(childCtx, parseContext);
            Value result = Value.VoidValue.INSTANCE;
            for (ASTNode stmt : block.getStatements()) {
                try {
                    result = childEval.evaluate(stmt);
                } catch (BreakException e) {
                    return result;
                }
            }
            return result;
        }
        return evaluate(defaultCase);
    }

    private Value visitReturn(ReturnNode node) {
        if (node.getValue() != null) {
            Value val = evaluate(node.getValue());
            throw new ReturnException(val);
        }
        throw new ReturnException(Value.VoidValue.INSTANCE);
    }

    /**
     * yield 语句：携带值向上抛出，由最近的 switch 捕获（见 {@link #visitSwitch}）。
     */
    private Value visitYield(YieldNode node) {
        throw new YieldException(evaluate(node.getValue()));
    }

    private Value visitBreak(BreakNode node) {
        if (node.isLabeled()) {
            throw new LabeledBreakException(node.getLabel());
        }
        throw new BreakException();
    }

    private Value visitContinue(ContinueNode node) {
        throw new ContinueException();
    }

    private Value visitLambda(LambdaNode node) {
        Function<Value[], Value> lambdaFunc = args -> {
            EvalContext lambdaCtx = evalContext.createChild();
            List<LambdaNode.Parameter> params = node.getParameters();
            for (int i = 0; i < params.size() && i < args.length; i++) {
                lambdaCtx.declareVariable(params.get(i).name(), args[i]);
            }
            Evaluator lambdaEval = new Evaluator(lambdaCtx, parseContext);
            try {
                return lambdaEval.evaluate(node.getBody());
            } catch (ReturnException e) {
                return (Value) e.getValue();
            }
        };

        List<String> paramNames = new ArrayList<>();
        for (LambdaNode.Parameter p : node.getParameters()) {
            paramNames.add(p.name());
        }
        Lambda lambda = new Lambda(lambdaFunc, paramNames, evalContext);

        Class<?> fiType = node.getFunctionalInterfaceType();
        if (fiType != null) {
            return Value.of(lambda.asInterface(fiType));
        }

        return Value.of(lambda);
    }

    private Value visitFunctionDef(FunctionDefNode node) {
        Function<Value[], Value> func = args -> {
            EvalContext funcCtx = evalContext.createChild();
            List<LambdaNode.Parameter> params = node.getParameters();
            for (int i = 0; i < params.size() && i < args.length; i++) {
                funcCtx.declareVariable(params.get(i).name(), args[i]);
            }
            Evaluator funcEval = new Evaluator(funcCtx, parseContext);
            try {
                funcEval.evaluate(node.getBody());
            } catch (ReturnException e) {
                return (Value) e.getValue();
            }
            return Value.VoidValue.INSTANCE;
        };
        evalContext.declareVariable(node.getFunctionName(), Value.of(func));
        return Value.VoidValue.INSTANCE;
    }

    private Value visitFunctionCall(FunctionCallNode node) {
        List<Value> args = evaluateAll(node.getArguments());
        String funcName = node.getFunctionName();
        if (evalContext.hasVariable(funcName)) {
            Value funcVal = evalContext.getVariable(funcName);
            Value result = tryInvokeCallable(funcVal, args);
            if (result != null) return result;
        }
        if (evalContext.hasBuiltin(funcName)) {
            return evalContext.callBuiltin(funcName, args);
        }
        throw new EvalException("Function not defined: " + funcName, ErrorCode.EVAL_UNDEFINED_VARIABLE);
    }

    /** DirectCallNode: 对任意可调用表达式的直接 () 调用 */
    private Value visitDirectCall(DirectCallNode node) {
        Value targetVal = evaluate(node.getTarget());
        List<Value> args = evaluateAll(node.getArguments());
        Value result = tryInvokeCallable(targetVal, args);
        if (result != null) return result;
        throw new EvalException("Value is not callable: " + targetVal, ErrorCode.EVAL_INVALID_OPERATION);
    }

    /**
     * 尝试将 Value 作为可调用对象调用。支持 Lambda / Function / MethodReference / FI proxy。
     * @return 调用结果，不可调用时返回 null
     */
    private Value tryInvokeCallable(Value val, List<Value> args) {
        Object raw = val.asJavaObject();
        if (raw instanceof Lambda lambda) {
            return lambda.invoke(args.toArray(new Value[0]));
        }
        if (raw instanceof Function) {
            @SuppressWarnings("unchecked")
            Function<Value[], Value> func = (Function<Value[], Value>) raw;
            return func.apply(args.toArray(new Value[0]));
        }
        if (raw instanceof MethodReference mr) {
            return Value.of(mr.invoke(args.stream().map(Value::asJavaObject).toArray()));
        }
        // FI proxy: 通过 Lambda.getSAM 检测并 invoke
        Class<?> rawClass = raw.getClass();
        if (rawClass.isInterface() || Proxy.isProxyClass(rawClass)) {
            Method sam = Lambda.getSAM(rawClass);
            if (sam != null) {
                try {
                    Object[] javaArgs = args.stream().map(Value::asJavaObject).toArray();
                    return Value.of(sam.invoke(raw, javaArgs));
                } catch (Exception e) {
                    throw new EvalException("Functional interface invocation failed", e,
                            ErrorCode.METHOD_INVOCATION_FAILED);
                }
            }
        }
        return null;  // 不是可调用对象
    }

    private Value visitAsync(AsyncNode node) {
        return evaluate(node.getExpression());
    }

    private Value visitAwait(AwaitNode node) {
        return evaluate(node.getExpression());
    }

    private Value visitMapLiteral(MapLiteralNode node) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<ASTNode, ASTNode> entry : node.getEntries().entrySet()) {
            Object key = evaluate(entry.getKey()).asJavaObject();
            Object value = evaluate(entry.getValue()).asJavaObject();
            result.put(key, value);
        }
        return Value.of(result);
    }

    private Value visitInterpolatedString(InterpolatedStringNode node) {
        StringBuilder sb = new StringBuilder();
        for (InterpolatedStringNode.Part part : node.getParts()) {
            if (part.isExpression()) {
                Value val = evaluate(part.getExpression());
                sb.append(val.asString());
            } else {
                sb.append(part.getLiteralText());
            }
        }
        String result = sb.toString();
        return new Value.StringValue(result);
    }

    private Value visitFieldAssignment(FieldAssignmentNode node) {
        Value target = evaluate(node.getTarget());
        Value value = evaluate(node.getValue());
        Object obj = target.asJavaObject();

        // ★ 静态字段赋值：目标是 Class 对象（如 Test1.value0 = 1）
        if (obj instanceof Class<?> clazz) {
            try {
                Field f = clazz.getField(node.getFieldName());
                checkFieldWrite(f);
                f.set(null, value.asJavaObject());
                return value;
            } catch (Exception e) {
                throw new EvalException("Static field assignment failed: " + node.getFieldName(), e, ErrorCode.EVAL_FIELD_ACCESS_FAILED);
            }
        }

        if (obj == null) throw new EvalException("Cannot set field on null", ErrorCode.EVAL_NULL_POINTER);

        // 脚本类方法体内的 this.field = v：既写镜像变量（本方法后续读取走镜像），
        // 也立即写穿到实例字段 —— 否则嵌套调用的同类方法读到的还是字段旧值
        if (isCurrentInstance(obj) && evalContext.hasVariable(CustomClassExecutor.mirrorName(node.getFieldName()))) {
            storeFieldMirror(node.getFieldName(), value);
            writeFieldThroughMirror(obj, node.getFieldName(), value);
            return value;
        }

        try {
            Field f = obj.getClass().getField(node.getFieldName());
            checkFieldWrite(f); // ★ 安全检查
            f.set(obj, value.asJavaObject());
        } catch (Exception e) {
            throw new EvalException("Field assignment failed: " + node.getFieldName(), e, ErrorCode.EVAL_FIELD_ACCESS_FAILED);
        }
        return value;
    }

    /**
     * 判断对象是否为当前脚本类实例。
     * <p>
     * {@link CustomClassExecutor} 执行脚本类方法体时，会把实例绑定为 {@code this}，
     * 并把实例字段镜像成同名局部变量。此判定用于让 {@code this.field} 的读写走镜像变量。
     * </p>
     */
    private boolean isCurrentInstance(Object obj) {
        if (obj == null || !evalContext.hasVariable("this")) return false;
        return evalContext.getVariable("this").asJavaObject() == obj;
    }

    /**
     * 把脚本类方法体内赋给字段镜像的值立即写进实例字段。
     * <p>
     * 字段镜像是 {@link CustomClassExecutor} 为每个方法帧准备的副本，本方法帧的读写都走副本。
     * 若赋值只停留在副本里，同一方法内后续发起的嵌套调用（{@code n() { s = "a"; all(); }}）
     * 会从实例上读到字段的旧值。因此这里直接反射写穿，让赋值立即对所有人可见。
     * </p>
     */
    private void writeFieldThroughMirror(Object instance, String fieldName, Value value) {
        writeInstanceField(findInstanceField(instance.getClass(), fieldName), instance, value);
    }

    /** 更新字段镜像变量（不存在时创建）。 */
    private void storeFieldMirror(String fieldName, Value value) {
        String mirror = CustomClassExecutor.mirrorName(fieldName);
        if (evalContext.hasVariable(mirror)) {
            evalContext.assignVariable(mirror, value);
        } else {
            evalContext.declareVariable(mirror, value);
        }
    }

    /** 当前脚本类方法体的接收者（{@code this} 绑定的实例）；不在脚本类方法体内时返回 null。 */
    private Object currentInstance() {
        if (!evalContext.hasVariable("this")) return null;
        Object self = evalContext.getVariable("this").asJavaObject();
        if (self == null || self instanceof Class<?>) return null; // 静态方法里 this 绑的是类对象
        return self;
    }

    /** 若名字是当前实例的字段，返回对应 Field，否则返回 null。 */
    private Field currentInstanceField(String fieldName) {
        Object self = currentInstance();
        return self == null ? null : findInstanceField(self.getClass(), fieldName);
    }

    /** 沿类层次查找实例字段（含非 public 字段与父类字段）。 */
    private static Field findInstanceField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 把值写进实例字段；写不进去（如 final 字段）时静默忽略，退回「只写镜像」的旧行为。 */
    private static void writeInstanceField(Field field, Object instance, Value value) {
        if (field == null || instance == null) return;
        try {
            field.setAccessible(true);
            field.set(instance, value.asJavaObject());
        } catch (Exception ignored) {
        }
    }

    private Value visitMethodReference(MethodReferenceNode node) {
        String methodName = node.getMethodName();
        ASTNode targetNode = node.getTarget();

        // ★ 提前解析目标信息用于 toString 显示
        String targetClassName;
        Object boundTarget;
        Class<?> staticTargetClass;  // 静态引用时的目标 Class

        if (targetNode != null) {
            Value targetVal = evaluate(targetNode);
            Object targetObj = targetVal.asJavaObject();
            if (targetObj instanceof Class<?> clazz) {
                targetClassName = clazz.getName();
                staticTargetClass = clazz;
                boundTarget = null;
            } else if (targetObj != null) {
                boundTarget = targetObj;
                targetClassName = targetObj.getClass().getName();
                staticTargetClass = null;
            } else {
                targetClassName = null;
                staticTargetClass = null;
                boundTarget = null;
            }
        } else {
            targetClassName = null;
            staticTargetClass = null;
            boundTarget = null;
        }

        // ★ 验证方法名在目标类上存在
        if (staticTargetClass != null && !hasMethodNamed(staticTargetClass, methodName)) {
            throw new EvalException("Method '" + methodName + "' not found in class "
                    + staticTargetClass.getName(), ErrorCode.METHOD_NOT_FOUND);
        } else if (boundTarget != null && !hasMethodNamed(boundTarget.getClass(), methodName)) {
            throw new EvalException("Method '" + methodName + "' not found in class "
                    + boundTarget.getClass().getName(), ErrorCode.METHOD_NOT_FOUND);
        }

        // ★ 确保所有被 lambda 引用的变量都是 effectively final
        final String fnMethodName = methodName;
        final ASTNode fnTargetNode = targetNode;
        final Class<?> fnStaticTargetClass = staticTargetClass;

        // ★ 旧版风格的 refFunc：动态方法重载选择 + 自动装箱/拆箱
        Function<Object[], Object> refFunc = args -> {
            Object target = fnTargetNode != null ? evaluate(fnTargetNode).asJavaObject() : null;
            Class<?> clazz;
            if (target instanceof Class<?> c) {
                clazz = c;  // 静态方法引用：target 本身就是 Class 对象（如 Runtime.class）
            } else if (target != null) {
                clazz = target.getClass();
            } else {
                clazz = fnStaticTargetClass != null ? fnStaticTargetClass : findClassForMethod(fnMethodName);
            }
            if (clazz == null)
                throw new EvalException("Cannot resolve method: " + fnMethodName, ErrorCode.METHOD_NOT_FOUND);

            Method method;
            Method explicitMethod = node.getBoundMethod();  // 解析期通过 <Sig> 强制指定的方法
            if (explicitMethod != null) {
                method = explicitMethod;  // ★ 强制使用指定签名，不做运行时重载选择
            } else {
                method = MethodResolver.resolveRuntime(clazz, fnMethodName, args);  // 动态决策
            }
            try {
                checkMethod(method);
                return method.invoke(target, MethodResolver.coerceArgsForInvoke(method, args));
            } catch (EvalException e) {
                throw e;
            } catch (Exception e) {
                throw new EvalException("Method reference failed: " + fnMethodName, e,
                        ErrorCode.METHOD_INVOCATION_FAILED);
            }
        };

        // ★ 提取泛型参数名列表（如 <int> → ["int"]）
        List<String> typeArgNames = null;
        if (!node.getTypeArguments().isEmpty()) {
            typeArgNames = new ArrayList<>();
            for (GenericType ga : node.getTypeArguments()) {
                typeArgNames.add(ga.getRawType() != null ? ga.getRawType().getSimpleName()
                        : ga.getTypeName());
            }
        }

        MethodReference ref = new MethodReference(methodName, refFunc,
                targetClassName, boundTarget, node.getBoundMethod(), typeArgNames);

        Class<?> fiType = node.getFunctionalInterfaceType();
        if (fiType != null) {
            return Value.of(ref.asInterface(fiType));
        }

        return Value.of(ref);
    }

    // ==================== 自定义运算符重载 ====================

    private Value tryCustomBinaryOp(BinaryOpNode.Operator op, Value left, Value right) {
        OperatorRegistry registry = parseContext.getOperatorRegistry();
        if (registry == null || registry.isEmpty()) return null;

        String opSymbol = op.getSymbol();
        if (!OperatorRegistry.BINARY_OPERATORS.contains(opSymbol)) return null;

        Object lhsObj = left.asJavaObject();
        Object rhsObj = right.asJavaObject();
        if (lhsObj == null || rhsObj == null) return null;

        OperatorRegistry.Overload overload = registry.findBinaryCompatible(
                opSymbol, lhsObj.getClass(), rhsObj.getClass());
        if (overload == null) return null;

        return invokeOperatorOverload(overload, List.of(left, right));
    }

    private Value invokeOperatorOverload(OperatorRegistry.Overload overload, List<Value> argValues) {
        ASTNode impl = overload.implementation();
        EvalContext childCtx = evalContext.createChild();
        Evaluator childEval = new Evaluator(childCtx, parseContext);

        if (impl instanceof FunctionDefNode fn) {
            List<LambdaNode.Parameter> params = fn.getParameters();
            for (int i = 0; i < params.size() && i < argValues.size(); i++) {
                childCtx.declareVariable(params.get(i).name(), argValues.get(i));
            }
            try {
                return childEval.evaluate(fn.getBody());
            } catch (ReturnException e) {
                return (Value) e.getValue();
            }
        }

        if (impl instanceof MethodDeclarationNode md) {
            List<ParameterNode> params = md.getParameters();
            for (int i = 0; i < params.size() && i < argValues.size(); i++) {
                childCtx.declareVariable(params.get(i).getParameterName(), argValues.get(i));
            }
            ASTNode body = md.getBody();
            if (body != null) {
                try {
                    return childEval.evaluate(body);
                } catch (ReturnException e) {
                    return (Value) e.getValue();
                }
            }
            return Value.VoidValue.INSTANCE;
        }

        return null;
    }

    private void cacheOperatorCallback(BinaryOpNode node, Value left, Value right) {
        OperatorRegistry registry = parseContext.getOperatorRegistry();
        if (registry == null || registry.isEmpty()) return;

        String opSymbol = node.getOperator().getSymbol();
        Object lhsObj = left.asJavaObject();
        Object rhsObj = right.asJavaObject();
        if (lhsObj == null || rhsObj == null) return;

        OperatorRegistry.Overload overload = registry.findBinaryCompatible(
                opSymbol, lhsObj.getClass(), rhsObj.getClass());
        if (overload == null) return;

        node.setOperatorCallback((l, r) -> invokeOperatorOverload(overload, List.of(l, r)));
    }

    /** 缓存已找到的 Overload callback（避免下次重复查找） */
    private void cacheOperatorCallback(BinaryOpNode node, OperatorCallback callback) {
        node.setOperatorCallback(callback);
    }

    /**
     * 将 BinaryOpNode.Operator 枚举转换为 Registry 使用的字符串表示。
     * <p>例如：{@code ADD} → {@code "+"}, {@code SUBTRACT} → {@code "-"} */
    private static String operatorToRegistryString(BinaryOpNode.Operator op) {
        return op.getSymbol();
    }

    /**
     * 将 UnaryOpNode.Operator 枚举转换为 Registry 使用的字符串表示。
     * <p>例如：{@code NEGATIVE} → {@code "-"}, {@code LOGICAL_NOT} → {@code "!"} */
    private static String operatorToRegistryString(UnaryOpNode.Operator op) {
        return op.getSymbol();
    }

    /**
     * 获取 Value 的运行时 Java 类型（用于 Registry 查找）。
     * <p>基本类型返回其原始类型；Number 包装类返回对应基本类型或 Number；
     * 其他对象返回其实际 Class。 */
    private static Class<?> getRuntimeType(Value v) {
        if (v instanceof Value.IntValue) return int.class;
        if (v instanceof Value.LongValue) return long.class;
        if (v instanceof Value.DoubleValue) return double.class;
        if (v instanceof Value.BooleanValue) return boolean.class;
        if (v instanceof Value.StringValue) return String.class;
        // 对于 ObjectValue（如从 Java 集合取出的元素），尝试获取更精确的类型
        Object obj = v.asJavaObject();
        if (obj != null) {
            Class<?> clazz = obj.getClass();
            // Number 包装类 → 优先返回具体类型，便于匹配数值运算符
            if (clazz == Integer.class) return int.class;
            if (clazz == Long.class) return long.class;
            if (clazz == Double.class) return double.class;
            if (clazz == Float.class) return float.class;
            if (clazz == Short.class) return short.class;
            if (clazz == Byte.class) return byte.class;
            if (Number.class.isAssignableFrom(clazz)) return Number.class;
            return clazz;
        }
        return Object.class;
    }

    private Value visitSafeFieldAccess(SafeFieldAccessNode node) {
        Value target = evaluate(node.getTarget());
        if (target instanceof Value.NullValue) return Value.NullValue.INSTANCE;
        Object obj = target.asJavaObject();
        if (obj == null) return Value.NullValue.INSTANCE;
        Class<?> resolvedClass = (obj instanceof Class<?> c) ? c : obj.getClass();
        // 接收者非 null：字段不存在应报错，而不是静默返回 null
        try {
            if (resolvedClass.isArray() && "length".equals(node.getFieldName())) {
                return Value.of(Array.getLength(obj));
            }
            Field f = resolvedClass.getField(node.getFieldName());
            checkFieldRead(f); // ★ 安全检查
            return Value.of(f.get(obj));
        } catch (EvalException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("Field not found: " + node.getFieldName() + " on " + resolvedClass.getSimpleName(), ErrorCode.EVAL_FIELD_ACCESS_FAILED);
        }
    }

    private Value visitSafeMethodCall(SafeMethodCallNode node) {
        Value target = evaluate(node.getTarget());
        if (target instanceof Value.NullValue) return Value.NullValue.INSTANCE;
        Object obj = target.asJavaObject();
        if (obj == null) return Value.NullValue.INSTANCE;
        // 接收者非 null：复用普通方法调用的重载解析与参数转换，方法内部异常正常向外抛
        List<Value> args = evaluateAll(node.getArguments());
        return invokeResolvedMethod(obj, node.getMethodName(), args);
    }

    /**
     * 在给定目标上按重载规则解析并调用方法（与 {@code visitMethodCall} 的动态分发一致）。
     */
    private Value invokeResolvedMethod(Object target, String methodName, List<Value> args) {
        Class<?> clazz;
        if (target instanceof Class<?> c) {
            boolean foundOnClassClass = false;
            for (Method m : Class.class.getMethods()) {
                if (m.getName().equals(methodName)) { foundOnClassClass = true; break; }
            }
            clazz = foundOnClassClass ? Class.class : c;
        } else {
            clazz = target.getClass();
        }
        Method bestMatch = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] argTypes = new Class<?>[args.size()];
            for (int i = 0; i < args.size(); i++) {
                Object argObj = args.get(i).asJavaObject();
                argTypes[i] = argObj != null ? argObj.getClass() : Object.class;
            }
            if (!MethodResolver.isApplicable(m, argTypes)) continue;
            int score = computeMethodMatchScore(m, argTypes);
            if (score < bestScore) {
                bestScore = score;
                bestMatch = m;
            }
        }
        if (bestMatch == null) {
            String typeList = args.isEmpty() ? "()"
                    : "(" + String.join(", ", args.stream().map(a -> {
                        Object o = a.asJavaObject();
                        return o != null ? o.getClass().getName() : "null";
                    }).toArray(String[]::new)) + ")";
            throw new EvalException("No applicable method found: " + clazz.getName() + "." + methodName + typeList,
                    ErrorCode.METHOD_NO_APPLICABLE_METHOD);
        }
        try {
            checkMethod(bestMatch); // ★ 安全检查
            Object[] javaArgs = prepareInvokeArgs(bestMatch, args);
            Object result = bestMatch.invoke(target, javaArgs);
            return Value.of(result);
        } catch (InvocationTargetException e) {
            throw new EvalException("Exception in " + methodName + ": " + e.getCause().getMessage(), e.getCause(), ErrorCode.EVAL_EXCEPTION_THROWN);
        } catch (EvalException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("Method call failed: " + methodName + " (" + e.getMessage() + ")", e, ErrorCode.METHOD_INVOCATION_FAILED);
        }
    }

    private Value visitThrow(ThrowNode node) {
        Value val = evaluate(node.getExpression());
        throw new EvalException("Uncaught throw: " + val, ErrorCode.EVAL_EXCEPTION_THROWN);
    }

    /**
     * assert 语句：条件为假时抛出断言失败。
     * <p>
     * 失败以 {@link EvalException} 抛出，并把 {@link AssertionError} 作为 cause，
     * 因此 {@code catch (AssertionError e)} 与 {@code catch (Exception e)} 都能捕获。
     * </p>
     */
    private Value visitAssert(AssertNode node) {
        if (evaluate(node.getCondition()).isTruthy()) {
            return Value.VoidValue.INSTANCE;
        }

        String detail = null;
        if (node.hasMessage()) {
            Object message = evaluate(node.getMessage()).asJavaObject();
            detail = String.valueOf(message);
        }
        String message = detail != null ? "Assertion failed: " + detail : "Assertion failed";
        throw new EvalException(message, new AssertionError(message), ErrorCode.EVAL_ASSERTION_FAILED);
    }

    /**
     * synchronized 语句：以 lock 对象的监视器执行临界区。
     * <p>
     * 控制流异常（return/break/continue）会自然穿过 synchronized 块并释放监视器，
     * 因此这里不需要额外的 finally 处理。
     * </p>
     */
    private Value visitSynchronized(SynchronizedNode node) {
        Object monitor = evaluate(node.getLock()).asJavaObject();
        if (monitor == null) {
            throw new EvalException("Cannot synchronize on null", ErrorCode.EVAL_NULL_POINTER);
        }
        synchronized (monitor) {
            return evaluate(node.getBody());
        }
    }

    private Value visitDelete(DeleteNode node) {
        if (node.isDeleteAll()) {
            evalContext.getVariables().clear();
            parseContext.clearAllVariables();
        } else {
            String name = node.getVariableName();
            if (evalContext.getVariables().containsKey(name)) {
                evalContext.getVariables().remove(name);
            } else if (evalContext.getParent() != null) {
                EvalContext ctx = evalContext;
                while (ctx != null) {
                    if (ctx.getVariables().containsKey(name)) {
                        ctx.getVariables().remove(name);
                        break;
                    }
                    ctx = ctx.getParent();
                }
            }
            parseContext.undeclareVariable(name);
        }
        return Value.VoidValue.INSTANCE;
    }

    private Value visitLabeledStatement(LabeledStatementNode node) {
        try {
            return evaluate(node.getStatement());
        } catch (LabeledBreakException e) {
            if (e.getLabel().equals(node.getLabel())) {
                return Value.VoidValue.INSTANCE;
            }
            throw e;
        }
    }

    private Value visitTry(TryNode node) {
        ASTNode finallyBlock = node.getFinallyBlock();
        Value result;
        try {
            result = evaluate(node.getTryBlock());
        } catch (BreakException | ContinueException | ReturnException | YieldException
                 | LabeledBreakException e) {
            // 控制流异常：finally 仍须执行（其自身的 return/throw 会覆盖当前控制流）
            if (finallyBlock != null) evaluate(finallyBlock);
            throw e;
        } catch (Exception e) {
            CatchClause matched = null;
            for (CatchClause catchClause : node.getCatchClauses()) {
                if (matchesCatch(catchClause, e)) { matched = catchClause; break; }
            }
            if (matched == null) {
                if (finallyBlock != null) evaluate(finallyBlock);
                throw e;
            }
            Value catchResult;
            try {
                EvalContext catchCtx = evalContext.createChild();
                catchCtx.declareVariable(matched.getVariableName(), Value.of(e));
                Evaluator catchEval = new Evaluator(catchCtx, parseContext);
                catchResult = catchEval.evaluate(matched.getBody());
            } catch (BreakException | ContinueException | ReturnException | YieldException
                     | LabeledBreakException ctl) {
                if (finallyBlock != null) evaluate(finallyBlock);
                throw ctl;
            } catch (Exception inner) {
                if (finallyBlock != null) evaluate(finallyBlock);
                throw inner;
            }
            if (finallyBlock != null) evaluate(finallyBlock);
            return catchResult;
        }
        if (finallyBlock != null) evaluate(finallyBlock);
        return result;
    }

    /**
     * 判断 catch 子句是否匹配给定异常。
     * <p>局限于：{@code throw} 目前统一抛出 {@link EvalException}，因此按声明类型匹配时同时考虑
     * 异常自身与其 {@code cause}（方法调用内部抛出的 Java 异常被包装为 EvalException 的 cause）。
     * 未声明类型（解析失败得到 null）时按兼容策略视为可捕获一切。</p>
     */
    private static boolean matchesCatch(CatchClause catchClause, Throwable thrown) {
        List<Class<?>> types = catchClause.getExceptionTypes();
        if (types == null || types.isEmpty()) return true;
        boolean hasUsableType = false;
        for (Class<?> type : types) {
            if (type == null) continue;
            hasUsableType = true;
            if (type.isInstance(thrown)) return true;
            if (thrown.getCause() != null && type.isInstance(thrown.getCause())) return true;
        }
        // 所有声明类型都无法解析 → 保持旧的“捕获一切”行为
        return !hasUsableType;
    }


    /** 将 Value 参数列表转换为 Java 对象数组，对 Lambda 类型的 Value 做 FI proxy 兜底。 */
    private Object[] convertArgsForParameters(List<Value> args, Class<?>[] paramTypes, boolean isVarArgs) {
        Object[] javaArgs = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Object raw = args.get(i).asJavaObject();
            Class<?> expectedType = paramTypes[Math.min(i, paramTypes.length - 1)];
            if (isVarArgs && i >= paramTypes.length - 1) {
                expectedType = expectedType.getComponentType();
            }
            if (raw instanceof Lambda lambdaObj && expectedType.isInterface()
                    && Lambda.isFunctionalInterface(expectedType)) {
                javaArgs[i] = lambdaObj.asInterface(expectedType);
                continue;
            }
            javaArgs[i] = raw;
        }
        return javaArgs;
    }

    // ==================== 安全检查辅助 ====================

    /** 获取当前的安全门卫（可能为 null）。 */
    private SecurityGate sg() {
        return evalContext.getSecurityGate();
    }

    /** 快捷方法：在方法调用前检查。 */
    void checkMethod(Method method) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeMethodCall(method);
    }

    /** 快捷方法：在字段读取前检查。 */
    private void checkFieldRead(Field field) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeFieldRead(field);
    }

    /** 快捷方法：在字段写入前检查。 */
    private void checkFieldWrite(Field field) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeFieldWrite(field);
    }

    /** 快捷方法：在构造器调用前检查。 */
    private void checkConstructor(Constructor<?> ctor) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeConstructorCall(ctor);
    }

    /** 快捷方法：在类访问前检查（通过 Class 对象）。 */
    private void checkClass(Class<?> clazz) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeClassAccess(clazz);
    }

    /** 快捷方法：在类访问前检查（通过类名字符串）。 */
    private void checkClassByName(String className) throws SecurityException {
        SecurityGate gate = sg();
        if (gate != null) gate.beforeClassAccessByName(className);
    }
}
