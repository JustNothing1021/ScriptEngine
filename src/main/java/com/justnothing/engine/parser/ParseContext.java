package com.justnothing.engine.parser;

import com.justnothing.engine.api.IClassFinder;
import com.justnothing.engine.builtins.BuiltinRegistry;
import com.justnothing.engine.util.DefaultClassFinder;
import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.ast.nodes.NameRefNode;
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

    /**
     * 本上下文内已确认"解析不到/不是类名"的名字集合。
     * <p>避免同一个名字（尤其是变量名被当作类名探测）被反复逐 import 前缀重试 —— 每次重试
     * 都要对 7 个默认 import 前缀各做一次 Class.forName。import / 类型别名 / 自定义类声明
     * 发生变化时清空（这些变化可能让原本解析不到的名字变得可解析）。
     * 生命周期与 ParseContext 相同，不会跨上下文增长。
     */
    private final Set<String> unresolvedClassNames = new HashSet<>();

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

    // ==================== 拆分探针（默认全关，仅用于测量） ====================

    /**
     * 解析阶段标记：探针开关只作用于解析期调用者。
     * <p>
     * 为什么需要它：{@link #resolveClass} 同时被解析器和运行期 {@code Evaluator} 复用
     * （见 {@code Evaluator#findClass}）。若不区分阶段，"关掉类解析"会把运行期一起打死，
     * 测出来的失败里就混进一堆与解析期无关的噪音，而我们需要的是"解析期到底扛了多少语义"
     * 这一个干净的读数。进出由 {@link Parser#parse()} 维护，用 ThreadLocal 避免跨线程污染。
     * </p>
     */
    private static final ThreadLocal<int[]> parsePhase = ThreadLocal.withInitial(() -> new int[1]);

    public static void enterParsePhase() {
        parsePhase.get()[0]++;
    }

    public static void exitParsePhase() {
        int[] depth = parsePhase.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** 当前是否处于解析阶段调用栈内。 */
    public static boolean isInParsePhase() {
        return parsePhase.get()[0] > 0;
    }

    /** 探针：跳过类解析（{@link #resolveClass} 直接返回 null，不触发 codegen）。 */
    private static final boolean PROBE_SKIP_CLASS_RESOLUTION =
            Boolean.getBoolean("engine.parser.noClassResolution");

    /** 探针：跳过类型标注（{@link #setType} 不写入类型标注表，推断随之整体缺席）。 */
    private static final boolean PROBE_SKIP_TYPE_ANNOTATION =
            Boolean.getBoolean("engine.parser.noTypeAnnotation");

    /** 探针：跳过语义校验（{@link #isStrictMode} 恒为 false，未知类型/未知符号静默放行）。 */
    private static final boolean PROBE_SKIP_SEMANTIC_CHECKS =
            Boolean.getBoolean("engine.parser.noSemanticChecks");

    /**
     * 探针作用范围：缺省 {@code parse} 只作用解析期；{@code all} 连运行期一起退化（对照组）。
     */
    private static final boolean PROBE_ALL_SCOPE =
            "all".equalsIgnoreCase(System.getProperty("engine.parser.probeScope", "parse"));

    private static boolean probeActive() {
        return PROBE_ALL_SCOPE || isInParsePhase();
    }

    /** 探针：本次调用是否跳过类解析。 */
    public static boolean probeSkipsClassResolution() {
        return PROBE_SKIP_CLASS_RESOLUTION && probeActive();
    }

    /** 探针：本次调用是否跳过类型标注。 */
    public static boolean probeSkipsTypeAnnotation() {
        return PROBE_SKIP_TYPE_ANNOTATION && probeActive();
    }

    /** 探针：本次调用是否跳过语义校验。 */
    public static boolean probeSkipsSemanticChecks() {
        return PROBE_SKIP_SEMANTIC_CHECKS && probeActive();
    }

    // ==================== 泛型闭合符拆分（>> / >>>） ====================

    /** 嵌套泛型闭合时从 {@code >>} / {@code >>>} 里预取、尚未被外层消费的 {@code >} 数量。 */
    private int pendingAngleBrackets;

    /**
     * 是否还有从 {@code >>} / {@code >>>} 里预取的 {@code >} 可以消费；有则消费掉一个。
     * <p>
     * Lexer 会把 {@code >>} 识别成右移操作符，但 Java 允许用它代替嵌套泛型的 {@code > >}
     * （如 {@code List<List<String>>}），所以类型解析侧要把多出来的 {@code >} 记下来还外层。
     * </p>
     * <p>
     * <b>为什么这个状态在 ParseContext 而不是某个解析器实例</b>：一个类型声明可能跨多个 parser
     * 实例解析 —— 例如 {@code class G<T extends Comparable<T>>}，类声明的类型参数表由
     * ClassBodyParser 解析，上界 {@code Comparable<T>} 又交给一个新的 TypeParser。内层从
     * {@code >>} 里多吃掉的那个 {@code >} 必须让外层看得见，否则外层会误报
     * "Expected '>' after type parameter list"。
     * </p>
     */
    public boolean consumePendingAngleBracket() {
        if (pendingAngleBrackets > 0) {
            pendingAngleBrackets--;
            return true;
        }
        return false;
    }

    /**
     * 记录从 {@code >>} / {@code >>>} 中预取到的 {@code >} 数量。
     *
     * @param count {@code >>} 记 1，{@code >>>} 记 2
     */
    public void addPendingAngleBrackets(int count) {
        pendingAngleBrackets += count;
    }

    /**
     * 清空预存的 {@code >}。
     * <p>解析入口与错误恢复时调用：被放弃的那段代码里若正好有个嵌套泛型，剩下的
     * {@code >} 不该留给后面的语句。</p>
     */
    public void clearPendingAngleBrackets() {
        pendingAngleBrackets = 0;
    }

    /** 当前预存的 {@code >} 数量（供回溯时快照/还原）。 */
    public int getPendingAngleBrackets() {
        return pendingAngleBrackets;
    }

    /** 还原预存的 {@code >} 数量（供回溯时快照/还原）。 */
    public void setPendingAngleBrackets(int count) {
        pendingAngleBrackets = count;
    }

    public void setCodeGenerator(DynamicClassGenerator codegen) {
        this.codegen = codegen;
        unresolvedClassNames.clear();
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
        // 探针：语义校验全部静默放行
        return !probeSkipsSemanticChecks() && strictMode;
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
            // 新增 import 后，之前找不到的类可能通过新 import 找到了。
            // 这里不再调用 classFinder.clearBlacklist()：ClassResolver 的 import 查找缓存以
            // "类名 + import 集合签名" 为 key，新 import 会自动落入新的缓存分区，旧分区的
            // "未找到" 结论不会被复用；而全量清空黑名单会让每个 import 都触发一次全量重查，
            // 使 Class.forName 次数按 import 条数成倍放大（实测 20 条 import 放大 20 倍）。
            unresolvedClassNames.clear();
        }
    }

    public void addImports(List<String> importStmts) {
        for (String stmt : importStmts) {
            addImport(stmt);
        }
    }

    public void clearImports() {
        imports.clear();
        unresolvedClassNames.clear();
    }

    // ==================== 类型别名 ====================

    public Map<String, String> getTypeAliases() {
        return Collections.unmodifiableMap(typeAliases);
    }

    public void addTypeAlias(String aliasName, String fullClassName) {
        typeAliases.put(aliasName, fullClassName);
        unresolvedClassNames.clear();
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
        unresolvedClassNames.clear();
    }

    /**
     * 注册一个完整的自定义类声明（含字段和方法信息）。
     * 用于 REPL 环境中后续解析该类的静态字段访问、方法调用等。
     */
    public void declareClass(ClassDeclarationNode classDecl) {
        declaredClassNames.add(classDecl.getClassName());
        classDeclarations.put(classDecl.getClassName(), classDecl);
        unresolvedClassNames.clear();
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
        unresolvedClassNames.clear();
    }

    public IClassFinder getClassFinder() {
        return classFinder;
    }

    public void setClassFinder(IClassFinder classFinder) {
        this.classFinder = classFinder;
        unresolvedClassNames.clear();
    }

    /**
     * 解析类名（含 import 和别名展开）。
     *
     * @param className 类名（可能含包路径）
     * @return 找到的 Class 对象，找不到返回 null
     */
    public Class<?> resolveClass(String className) {
        // 探针：不查类，让调用方拿到 null 走各自的 fallback
        if (probeSkipsClassResolution()) return null;

        if (unresolvedClassNames.contains(className)) return null;

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

        if (result == null) {
            unresolvedClassNames.add(className);
        }
        return result;
    }


    // ==================== 当前位置 ====================

    /**
     * 最近消费的 token 位置。
     * <p>
     * 由 {@link BaseParser#advance()} 维护，用于没有 token 上下文的语义检查
     * （如重复声明检测）生成带位置的错误信息。
     * </p>
     */
    private SourceLocation currentLocation;

    /** 记录最近消费的 token 位置。 */
    public void setCurrentLocation(SourceLocation location) {
        this.currentLocation = location;
    }

    /** 最近消费的 token 位置（尚未消费任何 token 时为 null）。 */
    public SourceLocation getCurrentLocation() {
        return currentLocation;
    }

    // ==================== 错误恢复 ====================

    /**
     * 语句级错误恢复期间收集到的错误。
     * <p>
     * 遇到错误时跳到下一个语句同步点继续解析，把错误累积到这里，
     * 使一次解析能够报告多处问题（而不是首个错误就中止）。
     * </p>
     */
    private final List<CythavaParseException> parseErrors = new ArrayList<>();

    /**
     * 是否启用语句级错误恢复。
     * <p>
     * 宽容解析路径（如匿名类成员/方法体）依赖"出错即抛异常并回退"的行为，
     * 需要临时关闭恢复。
     * </p>
     */
    private boolean errorRecoveryEnabled = true;

    /** 记录一个已恢复的语句级错误。 */
    public void reportError(CythavaParseException error) {
        parseErrors.add(error);
    }

    /** 本次解析收集到的全部语句级错误（只读）。 */
    public List<CythavaParseException> getParseErrors() {
        return Collections.unmodifiableList(parseErrors);
    }

    /** 清空错误列表（每次顶层解析开始时调用）。 */
    public void clearParseErrors() {
        parseErrors.clear();
    }

    // ==================== 值位置的未定名引用（link 前的待判定名单） ====================

    /**
     * 解析期产出、身份尚未判定的名字引用。
     * <p>
     * 解析器遇到裸名字时不该去查类（那是 link 层的事），只登记在这里；等整棵 AST 建好、
     * 类声明与符号表都齐了，再由 link 阶段统一判定。顺带的好处是"后声明的类"也能被引用到：
     * 解析到 {@code Foo.bar} 时 {@code class Foo} 可能还没被登记。
     * </p>
     */
    private final List<NameRefNode> pendingNameRefs = new ArrayList<>();

    /** 登记一个待判定的名字引用。 */
    public void addNameRef(NameRefNode node) {
        pendingNameRefs.add(node);
    }

    /** 本次解析登记的待判定名字引用（只读）。 */
    public List<NameRefNode> getNameRefs() {
        return Collections.unmodifiableList(pendingNameRefs);
    }

    /** 清空待判定名字引用（每次顶层解析开始时调用）。 */
    public void clearNameRefs() {
        pendingNameRefs.clear();
    }

    public boolean isErrorRecoveryEnabled() {
        return errorRecoveryEnabled;
    }

    public void setErrorRecoveryEnabled(boolean enabled) {
        this.errorRecoveryEnabled = enabled;
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
                    "Variable '" + name + "' is already declared in the current scope", getCurrentLocation(), ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
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
                    "Variable '" + name + "' is already declared in the current scope", getCurrentLocation(), ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED, true);
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
        // 内置函数名不是类名：避免 println/print 等被逐 import 前缀当作类名探测
        if (isBuiltinFunction(name)) {
            return false;
        }
        if (unresolvedClassNames.contains(name)) {
            return false;
        }
        // 尝试通过 import/classfinder 解析
        String resolved = resolveTypeAlias(name);
        String actualName = (resolved != null) ? resolved : name;
        if (classFinder.findClassWithImports(actualName, classLoader, imports) != null) {
            return true;
        }
        unresolvedClassNames.add(name);
        return false;
    }

    // ==================== 类型标注 ====================

    /**
     * 为 AST 节点设置解析期类型。
     *
     * @param node 表达式 AST 节点
     * @param type 该节点的 JType 类型
     */
    public void setType(ASTNode node, JType type) {
        // 探针：类型标注整体缺席
        if (probeSkipsTypeAnnotation()) return;
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
