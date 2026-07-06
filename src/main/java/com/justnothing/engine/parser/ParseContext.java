package com.justnothing.engine.parser;

import com.justnothing.engine.api.IClassFinder;
import com.justnothing.engine.builtins.BuiltinRegistry;
import com.justnothing.engine.util.DefaultClassFinder;
import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.exception.ErrorCode;

import java.util.*;

/**
 * 解析上下文 — 符号表 + 类型解析 + 语义状态。
 * <p>
 * 这是 Cythava Parser 的核心基础设施，在解析阶段承担以下职责：
 * <ul>
 *   <li><b>符号表管理</b>：变量/类/函数的声明与查找（作用域嵌套）</li>
 *   <li><b>类型解析</b>：import 管理、类型别名、Class 查找、泛型构建</li>
 *   <li><b>语义状态</b>：当前类/方法上下文、闭包捕获分析、循环标签栈</li>
 * </ul>
 * </p>
 *
 * @see BaseParser
 */
public class ParseContext {

    // ==================== Import 与类型别名 ====================

    private final List<String> imports = new ArrayList<>();
    private final Map<String, String> typeAliases = new HashMap<>();
    private final Set<String> declaredClassNames = new HashSet<>();
    /** 共享的内置函数注册表。解析期通过此表做符号检查，运行期通过同一实例执行调用。 */
    private BuiltinRegistry builtinRegistry;

    // ==================== 类加载器与类查找 ====================

    private ClassLoader classLoader;
    private IClassFinder classFinder;

    /** 数组类型缓存：避免 getRawType() 每次都 Array.newInstance 反射创建 */
    private final Map<String, Class<?>> arrayTypeCache = new HashMap<>();

    // ==================== 作用域 / 符号表 ====================

    /** 作用域栈。每个 Scope 包含该层级的变量声明。 */
    private final Deque<Scope> scopeStack = new ArrayDeque<>();

    /** 当前类名（进入类体时设置）。 */
    private String currentClassName;

    /** 当前类的字段集合（用于 this.xxx vs 局部变量消歧）。 */
    private Set<String> currentClassFields;

    /** 当前类的方法签名集合（方法名 → 参数类型列表的列表）。两遍扫描时第一遍先注册签名。 */
    private Map<String, List<List<Class<?>>>> currentClassMethodSignatures;

    /** 当前方法的参数名集合（用于判断是否为局部变量）。 */
    private Set<String> currentMethodParams;

    /** 循环标签栈（用于 break/continue 标签验证）。 */
    private final Deque<String> labelStack = new ArrayDeque<>();

    // ==================== 类型标注表 ====================

    /**
     * AST 节点 → 解析期类型映射。
     * <p>
     * 在解析阶段为每个表达式节点附加 JType 类型信息，
     * Evaluator 无需再做类型推断，直接使用即可。
     * </p>
     */
    private final Map<ASTNode, JType> typeMap = new IdentityHashMap<>();

    /** 统一严格模式开关（控制标识符解析、类型解析、运算符校验的严格程度）。
     * <p>true = 严格模式：未识别的标识符报错、未知类型不 fallback、运算符无匹配时解析期报错。
     * <br>false = 宽松模式：静默放行，交给运行时处理。
     * <p>默认 true（生产环境）；测试中可设为 false 以兼容不规范的测试脚本。
     */
    private boolean strictMode = true;
    private DynamicClassGenerator codegen;

    public void setCodeGenerator(DynamicClassGenerator codegen) {
        this.codegen = codegen;
    }

    public DynamicClassGenerator getCodeGenerator() {
        return codegen;
    }

    public ParseContext() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ParseContext(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        this.classFinder = new DefaultClassFinder(classLoader);
        addDefaultImports();
        // 初始作用域：全局作用域
        enterScope(ScopeKind.GLOBAL);
    }

    public ParseContext(IClassFinder classFinder) {
        this.classFinder = classFinder;
        addDefaultImports();
        enterScope(ScopeKind.GLOBAL);
    }

    // ==================== 严格模式 ====================

    public boolean isStrictMode() {
        return strictMode;
    }

    /** 设置严格模式（必须在解析前调用）。 */
    public void setStrictMode(boolean strict) {
        this.strictMode = strict;
    }

    // ==================== Import 管理 ====================

    private void addDefaultImports() {
        imports.add("java.lang.*");
        imports.add("java.util.*");
        imports.add("java.lang.reflect.*");
        imports.add("java.util.function.*");
        imports.add("com.justnothing.engine.builtins.*");
        imports.add("android.os.*");
        imports.add("android.util.*");
    }

    public List<String> getImports() {
        return Collections.unmodifiableList(imports);
    }

    public void addImport(String importStmt) {
        if (!imports.contains(importStmt)) {
            imports.add(importStmt);
            // 新增 import 后，之前找不到的类可能通过新 import 找到了，清空黑名单
            if (classFinder != null) {
                classFinder.clearBlacklist();
            }
        }
    }

    public void addImports(List<String> importStmts) {
        for (String stmt : importStmts) {
            addImport(stmt);
        }
    }

    public void clearImports() {
        imports.clear();
    }

    // ==================== 类型别名 ====================

    public Map<String, String> getTypeAliases() {
        return Collections.unmodifiableMap(typeAliases);
    }

    public void addTypeAlias(String aliasName, String fullClassName) {
        typeAliases.put(aliasName, fullClassName);
    }

    /**
     * 解析类型别名（支持链式别名）。
     *
     * @param aliasName 别名
     * @return 解析后的完整类名，如果无别名则返回 null
     */
    public String resolveTypeAlias(String aliasName) {
        String resolved = typeAliases.get(aliasName);
        if (resolved != null && typeAliases.containsKey(resolved)) {
            return resolveTypeAlias(resolved);  // 链式解析
        }
        return resolved;
    }

    public boolean hasTypeAlias(String aliasName) {
        return typeAliases.containsKey(aliasName);
    }

    // ==================== 类声明追踪 ====================

    /** 已声明的自定义类（REPL 中 class X { ... } 声明的类）。key=类名, value=AST 节点。 */
    private final Map<String, ClassDeclarationNode> classDeclarations = new HashMap<>();

    public void declareClass(String className) {
        declaredClassNames.add(className);
    }

    /**
     * 注册一个完整的自定义类声明（含字段和方法信息）。
     * 用于 REPL 环境中后续解析该类的静态字段访问、方法调用等。
     */
    public void declareClass(ClassDeclarationNode classDecl) {
        declaredClassNames.add(classDecl.getClassName());
        classDeclarations.put(classDecl.getClassName(), classDecl);
    }

    public boolean isClassDeclared(String className) {
        return declaredClassNames.contains(className);
    }

    /**
     * 获取已声明的自定义类节点（仅限 REPL 动态声明，非 Java 反射类）。
     *
     * @return 类声明节点，未找到则返回 null
     */
    public ClassDeclarationNode getClassDeclaration(String className) {
        return classDeclarations.get(className);
    }

    public Map<String, ClassDeclarationNode> getClassDeclarations() {
        return classDeclarations;
    }

    // ==================== 内置函数注册 ====================

    /** 设置共享的 BuiltinRegistry（必须在解析前调用）。 */
    public void setBuiltinRegistry(BuiltinRegistry registry) {
        this.builtinRegistry = registry;
    }

    public BuiltinRegistry getBuiltinRegistry() {
        return builtinRegistry;
    }

    /** 检查名字是否为已注册的 builtin（解析期符号检查用）。 */
    public boolean isBuiltinFunction(String name) {
        return builtinRegistry != null && builtinRegistry.isKnown(name);
    }

    // ==================== 运算符重载注册表 ====================

    /** 共享的运算符重载注册表（由 ScriptRunner 注入，与 Evaluator 共用）。 */
    private OperatorRegistry operatorRegistry;
    private boolean builtinsRegistered = false;
    private boolean externallyManaged = false;  // 用户通过 setOperatorRegistry() 显式设置

    /** 设置共享的 OperatorRegistry（必须在解析前调用）。标记为外部管理，不自动注册 builtins。 */
    public void setOperatorRegistry(OperatorRegistry registry) {
        this.operatorRegistry = registry;
        this.externallyManaged = true;  // 外部管理，跳过自动注册
    }

    /** 获取运算符注册表（用于注册和查询）。未显式设置时懒创建并自动注册内置运算符。 */
    public OperatorRegistry getOperatorRegistry() {
        if (operatorRegistry == null) {
            operatorRegistry = new OperatorRegistry();
        }
        // 自动注册内置运算符（仅非外部管理时，且只执行一次）
        if (!externallyManaged && !builtinsRegistered) {
            operatorRegistry.registerAllBuiltins();
            builtinsRegistered = true;
        }
        return operatorRegistry;
    }

    // ==================== 类加载器与查找 ====================

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public IClassFinder getClassFinder() {
        return classFinder;
    }

    public void setClassFinder(IClassFinder classFinder) {
        this.classFinder = classFinder;
    }

    /**
     * 解析类名（含 import 和别名展开）。
     *
     * @param className 类名（可能含包路径）
     * @return 找到的 Class 对象，找不到返回 null
     */
    public Class<?> resolveClass(String className) {
        String resolved = resolveTypeAlias(className);
        String actualName = (resolved != null) ? resolved : className;

        // 1) 直接通过类加载器查找（支持自定义生成类）
        Class<?> result = classFinder.findClass(actualName, classLoader);
        if (result != null) return result;

        // 2) 通过 import 列表解析
        result = classFinder.findClassWithImports(actualName, classLoader, imports);
        if (result != null) return result;

        // 3) REPL 中声明的自定义类：按需生成字节码，使类加载器可见
        if (classDeclarations.containsKey(actualName)) {
            ClassDeclarationNode classDecl = classDeclarations.get(actualName);
            if (codegen != null) {
                result = codegen.generate(classDecl);
                if (result == null) {
                    throw new RuntimeException(
                            "Cannot generate bytecode for user-defined class " + classDecl.getClassName()
                                    + " at position "
                                    + classDecl.getLocation());
                }
            }
            // codegen == null 时不报错，返回 null 让调用方处理（解析器会 fallback）
        }

        return result;
    }


    // ==================== 作用域管理 ====================

    /**
     * 进入新作用域。
     *
     * @param kind 作用域种类
     */
    public void enterScope(ScopeKind kind) {
        scopeStack.push(new Scope(kind));
    }

    /** 退出当前作用域。 */
    public void exitScope() {
        if (!scopeStack.isEmpty()) {
            scopeStack.pop();
        }
    }

    /** 获取当前作用域深度。 */
    public int getScopeDepth() {
        return scopeStack.size();
    }

    /**
     * 在当前作用域声明变量。
     *
     * @param name    变量名
     * @param isFinal 是否为 final
     * @throws CythavaParseException 如果同名变量已在当前作用域声明
     */
    public void declareVariable(String name, boolean isFinal) throws CythavaParseException {
        if (scopeStack.isEmpty()) {
            throw new CythavaParseException("No active scope to declare variable: " + name, null, ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
        }
        Scope currentScope = scopeStack.peek();
        if (currentScope.variables.containsKey(name)) {
            throw new CythavaParseException(
                    "Variable '" + name + "' is already declared in the current scope", null, ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
        }
        currentScope.variables.put(name, new VariableSymbol(name, isFinal));
    }

    /** 便利方法：声明非 final 变量。 */
    public void declareVariable(String name) throws CythavaParseException {
        declareVariable(name, false);
    }

    /**
     * 在当前作用域声明带类型信息的变量。
     *
     * @param name    变量名
     * @param type    声明类型（从 Class<?> 自动包装为 GenericType）
     * @throws CythavaParseException 如果同名变量已在当前作用域声明
     */
    public void declareVariable(String name, Class<?> type) throws CythavaParseException {
        declareVariable(name, false, type != null ? new GenericType(type) : null);
    }

    /**
     * 在当前作用域声明带泛型类型信息的变量（非 final）。
     *
     * @param name         变量名
     * @param declaredType 声明时的泛型类型（null 表示 auto 推断）
     * @throws CythavaParseException 如果同名变量已在当前作用域声明
     */
    public void declareVariable(String name, GenericType declaredType) throws CythavaParseException {
        declareVariable(name, false, declaredType);
    }

    /**
     * 在当前作用域声明带类型信息的变量。
     *
     * @param name         变量名
     * @param isFinal      是否为 final
     * @param declaredType 声明时的泛型类型（null 表示 auto 推断）
     * @throws IllegalStateException 如果同名变量已在当前作用域声明
     */
    public void declareVariable(String name, boolean isFinal, GenericType declaredType)
            throws CythavaParseException {
        if (scopeStack.isEmpty()) {
            throw new CythavaParseException("No active scope to declare variable: " + name, null, ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
        }
        Scope currentScope = scopeStack.peek();
        if (currentScope.variables.containsKey(name)) {
            throw new CythavaParseException(
                    "Variable '" + name + "' is already declared in the current scope", null, ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
        }
        currentScope.variables.put(name, new VariableSymbol(name, isFinal, declaredType));
    }

    /** 从当前作用域移除变量声明（用于 lambda 参数退出作用域）。 */
    public void undeclareVariable(String name) {
        if (!scopeStack.isEmpty()) {
            scopeStack.peek().variables.remove(name);
        }
    }

    /** 清除所有作用域的变量声明（用于 delete * 命令）。 */
    public void clearAllVariables() {
        for (Scope scope : scopeStack) {
            scope.variables.clear();
        }
    }

    /**
     * 声明一个 Lambda 自动推断类型参数（无显式类型标注）。
     * <p>
     * 与普通 {@code declareVariable(name)} 的区别：此方法标记变量为"inferred"，
     * 使解析期运算符查表等检查跳过该变量（类型将在运行时确定）。
     * </p>
     *
     * @param name 参数名
     */
    public void declareInferredVariable(String name) throws CythavaParseException {
        declareVariable(name, false, (GenericType) null);
        VariableSymbol sym = resolveVariable(name);
        if (sym != null) {
            sym.markInferred();
        }
    }

    /**
     * 检查变量是否为 Lambda 自动推断类型参数。
     *
     * @param name 变量名
     * @return true 如果该变量标记为 inferred
     */
    public boolean isInferredVariable(String name) {
        VariableSymbol sym = resolveVariable(name);
        return sym != null && sym.isInferred();
    }

    /**
     * 从内向外解析变量名。
     *
     * @param name 变量名
     * @return 找到返回 VariableSymbol，未找到返回 null
     */
    public VariableSymbol resolveVariable(String name) {
        // 1. 从内向外遍历作用域栈
        for (Scope scope : scopeStack) {
            VariableSymbol sym = scope.variables.get(name);
            if (sym != null) {
                return sym;
            }
        }
        return null;
    }

    /** 判断名字是否为已知变量（在任意可见作用域中）。 */
    public boolean isKnownVariable(String name) {
        return resolveVariable(name) != null;
    }

    /** 判断变量是否在当前作用域已声明（用于重复声明检测）。 */
    public boolean isVariableDeclared(String name) {
        if (scopeStack.isEmpty()) return false;
        return scopeStack.peek().variables.containsKey(name);
    }

    /** 获取已声明变量的声明类型（用于赋值类型检查）。 */
    public GenericType getDeclaredType(String name) {
        VariableSymbol sym = resolveVariable(name);
        return sym != null ? sym.getDeclaredType() : null;
    }

    /** 获取已声明变量的 final 标志。 */
    public boolean isFinal(String name) {
        VariableSymbol sym = resolveVariable(name);
        return sym != null && sym.isFinal();
    }

    /** 获取变量关联的匿名类声明（如有）。 */
    public com.justnothing.engine.ast.nodes.ClassDeclarationNode getVariableAnonymousClass(String name) {
        VariableSymbol sym = resolveVariable(name);
        return sym != null ? sym.getAnonymousClass() : null;
    }

    /** 设置变量关联的匿名类声明。 */
    public void setVariableAnonymousClass(String name, com.justnothing.engine.ast.nodes.ClassDeclarationNode anonClass) {
        VariableSymbol sym = resolveVariable(name);
        if (sym != null) {
            sym.setAnonymousClass(anonClass);
        }
    }

    /** 判断名字是否为已声明的类（含导入的类）。 */
    public boolean isKnownClass(String name) {
        if (declaredClassNames.contains(name)) {
            return true;
        }
        // 尝试通过 import/classfinder 解析
        String resolved = resolveTypeAlias(name);
        String actualName = (resolved != null) ? resolved : name;
        return classFinder.findClassWithImports(actualName, classLoader, imports) != null;
    }

    // ==================== 类型标注 ====================

    /**
     * 为 AST 节点设置解析期类型。
     *
     * @param node 表达式 AST 节点
     * @param type 该节点的 JType 类型
     */
    public void setType(ASTNode node, JType type) {
        if (node != null && type != null) {
            typeMap.put(node, type);
        }
    }

    /**
     * 获取 AST 节点的解析期类型。
     *
     * @param node 表达式 AST 节点
     * @return JType 类型，未标注时返回 null
     */
    public JType getType(ASTNode node) {
        return typeMap.get(node);
    }

    /**
     * 获取 AST 节点的原始 Class 类型（便捷方法）。
     *
     * @param node 表达式 AST 节点
     * @return 原始 Class，未标注或类型为 null 时返回 null
     */
    public Class<?> getRawType(ASTNode node) {
        JType jtype = typeMap.get(node);
        if (jtype == null) return null;
        // 如果是数组类型（arrayDepth > 0），返回实际的数组 Class
        // 例如 int[] 类型应该返回 int[].class 而不是 int.class
        int arrayDepth = jtype.getArrayDepth();
        if (arrayDepth > 0) {
            Class<?> componentType = jtype.getRawType();
            String cacheKey = componentType.getName() + "[".repeat(arrayDepth);
            return arrayTypeCache.computeIfAbsent(cacheKey, k ->
                    java.lang.reflect.Array.newInstance(componentType, new int[arrayDepth]).getClass());
        }
        return jtype.getRawType();
    }

    /**
     * 获取 AST 节点的推断类型（以 GenericType 形式返回）。
     * <p>
     * 用于 auto 类型推断：从初始化表达式的类型标注推导变量声明类型。
     *
     * @param node 表达式 AST 节点
     * @return GenericType，未标注时返回 null
     */
    public GenericType getInferredType(ASTNode node) {
        JType jtype = typeMap.get(node);
        if (jtype == null) {
            return null;
        }
        Class<?> rawType = jtype.getRawType();
        if (rawType == null) return null;
        return toGenericType(jtype);
    }

    private static GenericType toGenericType(JType jtype) {
        Class<?> rawType = jtype.getRawType();
        if (rawType == null) return null;
        List<JType> jArgs = jtype.getTypeArguments();
        int arrayDepth = jtype.getArrayDepth();
        if (jArgs == null || jArgs.isEmpty()) {
            return new GenericType(rawType, Collections.emptyList(), arrayDepth);
        }
        List<GenericType> genericArgs = new ArrayList<>(jArgs.size());
        for (JType ja : jArgs) {
            genericArgs.add(toGenericType(ja));
        }
        return new GenericType(rawType, genericArgs, arrayDepth);
    }

    /**
     * 获取完整的类型标注表（只读）。
     */
    public Map<ASTNode, JType> getTypeMap() {
        return Collections.unmodifiableMap(typeMap);
    }

    // ==================== 类上下文 ====================

    public String getCurrentClassName() {
        return currentClassName;
    }

    /** 进入类体（开始解析类成员时调用）。 */
    public void enterClass(String className) {
        this.currentClassName = className;
        this.currentClassFields = new HashSet<>();
        this.currentClassMethodSignatures = new HashMap<>();
    }

    /** 退出类体。 */
    public void exitClass() {
        this.currentClassName = null;
        this.currentClassFields = null;
        this.currentClassMethodSignatures = null;
    }

    /**
     * 注册一个方法签名（解析方法体之前调用，使同类方法互调可见）。
     *
     * @param methodName 方法名
     * @param paramTypes 参数类型列表
     */
    public void registerMethodSignature(String methodName, List<Class<?>> paramTypes) {
        if (currentClassMethodSignatures == null) return;
        currentClassMethodSignatures.computeIfAbsent(methodName, k -> new ArrayList<>()).add(paramTypes);
    }

    /**
     * 查找当前类的所有方法重载的参数类型。
     *
     * @param methodName 方法名
     * @return 参数类型列表的列表（未找到返回空列表）
     */
    public List<List<Class<?>>> getCurrentClassMethodSignatures(String methodName) {
        if (currentClassMethodSignatures == null) return List.of();
        List<List<Class<?>>> overloads = currentClassMethodSignatures.get(methodName);
        return overloads != null ? overloads : List.of();
    }

    public void addField(String fieldName) {
        if (currentClassFields != null) {
            currentClassFields.add(fieldName);
        }
    }

    public boolean isFieldOfCurrentClass(String name) {
        return currentClassFields != null && currentClassFields.contains(name);
    }

    /**
     * 判断一个名字应该被解析为字段访问还是局部变量。
     * 规则：是当前类的字段 且 不是当前方法的参数/局部变量 → 应作为字段访问
     */
    public boolean shouldResolveAsField(String name) {
        return isFieldOfCurrentClass(name) && !isKnownVariable(name);
    }

    // ==================== 方法上下文 ====================

    /** 进入方法体（开始解析方法时调用）。 */
    public void enterMethod(Set<String> paramNames) {
        this.currentMethodParams = paramNames != null ? new HashSet<>(paramNames) : new HashSet<>();
    }

    /** 退出方法体。 */
    public void exitMethod() {
        this.currentMethodParams = null;
    }

    public boolean isLocalVariable(String name) {
        return currentMethodParams != null && currentMethodParams.contains(name);
    }

    // ==================== 循环标签 ====================

    public void pushLabel(String label) {
        labelStack.push(label);
    }

    public String popLabel() {
        return labelStack.isEmpty() ? null : labelStack.pop();
    }

    /** 检查标签是否在当前循环栈中。 */
    public boolean hasLabel(String label) {
        return labelStack.contains(label);
    }

    // ==================== 内部数据结构 ====================

    /**
     * 作用域种类。
     */
    public enum ScopeKind {
        /** 全局/顶层作用域。 */
        GLOBAL,
        /** 类体作用域（this 可用）。 */
        CLASS,
        /** 方法体作用域。 */
        METHOD,
        /** 块级作用域（if/for/while/do/lambda 体等）。 */
        BLOCK,
        /** Lambda 表达式体作用域。 */
        LAMBDA
    }

    /**
     * 作用域实例，持有该层级声明的所有变量。
     */
    public static class Scope {
        final ScopeKind kind;
        final Map<String, VariableSymbol> variables = new LinkedHashMap<>();

        Scope(ScopeKind kind) {
            this.kind = kind;
        }
    }

    /**
     * 变量符号记录。
     */
    public static class VariableSymbol {
        private final String name;
        private final boolean isFinal;
        private GenericType declaredType;  // 声明时的类型（null = auto 推断）
        private ClassDeclarationNode anonymousClass;  // 匿名类初始化器（如有）
        /** 是否为 Lambda 自动推断类型参数（无显式类型标注）。 */
        private boolean inferred;

        VariableSymbol(String name, boolean isFinal) {
            this(name, isFinal, null);
        }

        VariableSymbol(String name, boolean isFinal, GenericType declaredType) {
            this.name = name;
            this.isFinal = isFinal;
            this.declaredType = declaredType;
            this.inferred = false;
        }

        /** 是否为 Lambda 自动推断类型参数。 */
        public boolean isInferred() {
            return inferred;
        }

        /** 标记为自动推断类型（由 Lambda 参数声明时调用）。 */
        public void markInferred() {
            this.inferred = true;
        }

        public String getName() {
            return name;
        }

        public boolean isFinal() {
            return isFinal;
        }

        /** 获取声明的类型（可能为 null 表示 auto 推断）。 */
        public GenericType getDeclaredType() {
            return declaredType;
        }

        /** 设置声明类型（由 StmtParser 在声明时调用）。 */
        public void setDeclaredType(GenericType type) {
            this.declaredType = type;
        }

        /** 获取匿名类声明（变量初始化器为匿名类时非 null）。 */
        public ClassDeclarationNode getAnonymousClass() {
            return anonymousClass;
        }

        /** 设置匿名类声明（由 StmtParser 在匿名类初始化时调用）。 */
        public void setAnonymousClass(ClassDeclarationNode anonClass) {
            this.anonymousClass = anonClass;
        }
    }
}
