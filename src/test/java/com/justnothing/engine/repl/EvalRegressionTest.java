package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import com.justnothing.engine.api.ClassResolver;
import com.justnothing.engine.api.DefaultOutputHandler;
import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.eval.CustomClassExecutor;
import com.justnothing.engine.eval.EvalContext;
import com.justnothing.engine.eval.Evaluator;
import com.justnothing.engine.eval.Value;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 求值期 Bug 回归测试。
 * <p>
 * 与历史上大量“只断言能解析不报错”的测试不同，这里每个用例都真正执行
 * Lexer → Parser → Evaluator，并断言运行结果。每个测试名对应一个已修复的 Bug，
 * 注释中写明修复前的错误行为。
 */
public class EvalRegressionTest {

    private ParseContext parseContext;
    private EvalContext evalContext;
    private DynamicClassGenerator codegen;

    @Before
    public void setUp() {
        parseContext = new ParseContext();
        evalContext = new EvalContext();
        codegen = new DynamicClassGenerator(parseContext.getClassLoader());
        codegen.setDelegateToExecutor(true);
        parseContext.setClassLoader(codegen.getLoader());
        parseContext.setCodeGenerator(codegen);
        parseContext.addImport("java.util.*");
    }

    @After
    public void tearDown() {
        CustomClassExecutor.clearContext();
    }

    // ==================== 辅助方法 ====================

    /** 完整执行源码并返回最后一个非 void 值（null 表示结果为 null 或无值）。 */
    private Value evalLastValue(String source) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer.tokenize(), parseContext, "<test>");
        List<ASTNode> nodes = parser.parse();
        for (ASTNode node : nodes) {
            if (node instanceof ClassDeclarationNode classDecl) {
                parseContext.declareClass(classDecl);
                try {
                    codegen.generate(classDecl);
                } catch (Exception ignored) {
                    // 与 REPL 一致：生成失败不阻断求值
                }
            }
        }
        CustomClassExecutor.setContext(evalContext, parseContext);
        try {
            Evaluator evaluator = new Evaluator(evalContext, parseContext);
            List<Value> results = evaluator.evaluateAll(nodes);
            for (int i = results.size() - 1; i >= 0; i--) {
                if (!(results.get(i) instanceof Value.VoidValue)) {
                    return results.get(i);
                }
            }
            return null;
        } finally {
            CustomClassExecutor.clearContext();
        }
    }

    /** 执行并返回最后一个非 void 值的底层 Java 对象。 */
    private Object eval(String source) {
        try {
            Value v = evalLastValue(source);
            return v == null ? null : v.asJavaObject();
        } catch (CythavaParseException e) {
            throw new AssertionError("Parse error: " + e.getMessage(), e);
        }
    }

    private void assertEvalException(String source) {
        try {
            evalLastValue(source);
            fail("Expected EvalException, but evaluation succeeded");
        } catch (EvalException expected) {
            // ok
        } catch (CythavaParseException e) {
            throw new AssertionError("Parse error: " + e.getMessage(), e);
        }
    }

    // ==================== #1 finally 总执行 ====================

    @Test
    public void regression_finallyExecutesOnNormalPath() {
        // 修复前得到 "T"（finally 被跳过）
        assertEquals("TF", eval("String w = \"\"; try { w = w + \"T\"; } finally { w = w + \"F\"; } w;"));
    }

    @Test
    public void regression_finallyExecutesAfterCatchBody() {
        // 修复前 catch 命中后直接 return，finally 被跳过 → "C"
        assertEquals("CF", eval("String w = \"\"; try { throw \"x\"; } catch (java.lang.Exception e) { w = \"C\"; } finally { w = w + \"F\"; } w;"));
    }

    @Test
    public void regression_finallyExecutesOnBreakPath() {
        // 修复前 break 控制流直接 rethrow，finally 被跳过 → "T"
        assertEquals("TF", eval("String w = \"\"; for (int k = 0; k < 1; k++) { try { w = w + \"T\"; break; } finally { w = w + \"F\"; } } w;"));
    }

    // ==================== #2 && / || 短路 ====================

    @Test
    public void regression_logicalAndShortCircuits() {
        // 修复前右侧被无条件求值，i 自增为 1
        assertEquals(0, eval("boolean c = false; int i = 0; c && (++i > 0); i;"));
    }

    @Test
    public void regression_logicalAndSkipsDivisionByZero() {
        // 修复前会执行 10/z 并抛除零异常
        assertEquals(Boolean.FALSE, eval("boolean c = false; int z = 0; c && (10/z > 1);"));
    }

    @Test
    public void regression_logicalOrShortCircuits() {
        // 修复前右侧被无条件求值，i 自增为 1
        assertEquals(0, eval("boolean t = true; int i = 0; t || (++i > 0); i;"));
    }

    // ==================== #10 catch 校验声明的异常类型 ====================

    @Test
    public void regression_catchTypeMismatchPropagates() {
        // 修复前 catch 捕获一切 → w = "IO"；正确行为是不匹配并向外抛
        assertEvalException("String w = \"\"; try { throw \"boom\"; } catch (java.io.IOException e) { w = \"IO\"; } w;");
    }

    @Test
    public void regression_catchMatchingDeclaredTypeStillCatches() {
        assertEquals("EX", eval("String w = \"\"; try { throw \"boom\"; } catch (java.lang.Exception e) { w = \"EX\"; } w;"));
    }

    // ==================== #11 / #4 / #8 switch ====================

    @Test
    public void regression_switchColonStyleFallsThrough() {
        // 修复前得到 "one"（命中即返回，无贯穿）
        assertEquals("onetwo", eval("int y = 1; String w = \"\"; switch(y) { case 1: w = w + \"one\"; case 2: w = w + \"two\"; } w;"));
    }

    @Test
    public void regression_switchBreakStopsFallThrough() {
        assertEquals("one", eval("int y = 1; String w = \"\"; switch(y) { case 1: w = w + \"one\"; break; case 2: w = w + \"two\"; } w;"));
    }

    @Test
    public void regression_switchMultiValueArrowCaseMatchesAnyValue() {
        // 修复前只比对第一个值 → "MISS"
        assertEquals("HIT", eval("int x = 2; String s = \"none\"; switch(x) { case 1,2,3 -> s = \"HIT\"; default -> s = \"MISS\"; } s;"));
    }

    @Test
    public void regression_switchLongMatchesIntCaseConstant() {
        // 修复前 LongValue 与 IntValue 永不相等 → "MISS"
        assertEquals("HIT", eval("long L = 2L; String w = \"none\"; switch(L) { case 2 -> w = \"HIT\"; default -> w = \"MISS\"; } w;"));
    }

    // ==================== #3 基本类型数组元素写入 ====================

    @Test
    public void regression_primitiveIntArrayElementAssignmentPersists() {
        // 修复前写入装箱副本，a[0] 仍为 1
        assertEquals(9, eval("int[] a = new int[]{1,2,3}; a[0] = 9; a[0];"));
    }

    @Test
    public void regression_primitiveIntArrayCompoundAssignmentPersists() {
        assertEquals(6, eval("int[] a = new int[]{1,2,3}; a[0] += 5; a[0];"));
    }

    @Test
    public void regression_byteArrayFromGetBytesAssignmentPersists() {
        assertEquals(65, eval("byte[] b = \"abc\".getBytes(); b[0] = 65; b[0];"));
    }

    @Test
    public void regression_charArrayElementAssignmentPersists() {
        assertEquals('z', eval("char[] c = new char[]{'a','b'}; c[0] = 'z'; c[0];"));
    }

    @Test
    public void regression_multidimIntArrayElementAssignmentPersists() {
        assertEquals(7, eval("int[][] m = new int[][]{{1,2},{3,4}}; m[0][1] = 7; m[0][1];"));
    }

    // ==================== #12 / #13 自增自减 ====================

    @Test
    public void regression_arrayElementIncrementWritesBack() {
        // 修复前抛 "Cannot increment non-variable"
        assertEquals(1, eval("int[] a = new int[1]; a[0]++; a[0];"));
    }

    @Test
    public void regression_fieldIncrementWritesBack() {
        // 修复前抛 "Cannot increment non-variable"
        assertEquals(1, eval("class Holder { int v = 0; } Holder h = new Holder(); h.v++; h.v;"));
    }

    @Test
    public void regression_charVariableIncrement() {
        // 修复前抛 "Cannot increment type: CharValue"
        assertEquals('b', eval("char ch = 'a'; ch++; ch;"));
    }

    // ==================== #7 char 实参方法分派 ====================

    @Test
    public void regression_charArgumentToJavaMethod() {
        // 修复前抛 ClassCastException: Character cannot be cast to Number
        assertEquals("a", eval("StringBuilder sb = new StringBuilder(); sb.append('a'); sb.toString();"));
    }

    @Test
    public void regression_charArgumentToPrintlnDoesNotThrow() {
        assertEquals("ok", eval("System.out.println('a'); \"ok\";"));
    }

    // ==================== #5 instanceof 简单类名 ====================

    @Test
    public void regression_instanceofSimpleClassName() {
        // 修复前抛 "Unknown type in instanceof: String"
        assertEquals(Boolean.TRUE, eval("\"abc\" instanceof String;"));
    }

    @Test
    public void regression_instanceofFullyQualifiedNameStillWorks() {
        assertEquals(Boolean.TRUE, eval("\"abc\" instanceof java.lang.String;"));
    }

    @Test
    public void regression_instanceofArrayType() {
        assertEquals(Boolean.TRUE, eval("int[] a = new int[]{1}; a instanceof int[];"));
    }

    // ==================== #6 ?= 条件赋值 ====================

    @Test
    public void regression_conditionalAssignWhenFalsyAssigns() {
        // 修复前抛 "Unsupported node: ConditionalAssignNode"
        assertEquals(10, eval("int q = 0; q ?= 10; q;"));
    }

    @Test
    public void regression_conditionalAssignWhenTruthyKeepsValue() {
        assertEquals(5, eval("int q = 5; q ?= 10; q;"));
    }

    // ==================== #9 / #14 安全调用与安全字段访问 ====================

    @Test
    public void regression_safeCallExecutesOnNonNilReceiver() {
        // 修复前 append 未执行（异常被吞）→ "sb="
        assertEquals("65", eval("StringBuilder sb = new StringBuilder(); sb?.append(65); sb.toString();"));
    }

    @Test
    public void regression_safeCallPropagatesMethodException() {
        // 修复前越界异常被吞，返回 null
        assertEvalException("Object o = \"abc\"; Object r = o?.charAt(99); r;");
    }

    @Test
    public void regression_safeCallOnNullReceiverReturnsNull() {
        assertNull(eval("Object o = null; Object r = o?.charAt(0); r;"));
    }

    @Test
    public void regression_safeFieldAccessMissingFieldThrows() {
        // 修复前静默返回 null
        assertEvalException("Object o = \"abc\"; o?.noSuchField;");
    }

    @Test
    public void regression_safeFieldAccessOnNullReceiverReturnsNull() {
        assertNull(eval("Object o = null; Object r = o?.length; r;"));
    }

    // ==================== P1 / P2 解析期崩溃 ====================

    @Test
    public void regression_emptyCaseBodyDoesNotCrashParser() {
        // 修复前抛 IndexOutOfBoundsException（annotateSwitchType 取空语句列表首个元素）
        assertEquals("x", eval("int z = 9; String w = \"x\"; switch(z) { case 1: } w;"));
    }

    @Test
    public void regression_functionWithoutReturnTypeIsCallable() {
        // 修复前解析错误 "Expected function name after 'function'"
        assertEquals(5, eval("function foo() { return 5; } foo();"));
    }

    // ==================== A3 switch 表达式多值 case ====================

    @Test
    public void regression_switchExpressionSingleValueCase() {
        assertEquals(33, eval("int sy = switch(3) { case 3 -> 33; default -> 0; }; sy;"));
    }

    @Test
    public void regression_switchExpressionSingleValueCaseNoMatchFallsToDefault() {
        // 任务复现用例：单值 case 本就能解析（不报 Duplicate variable declaration），3 不命中 case 1
        assertEquals(0, eval("int sy = switch(3) { case 1 -> 33; default -> 0; }; sy;"));
    }

    @Test
    public void regression_switchExpressionMultiValueCase() {
        // 修复前：多值 case 在 ExprParser 里不支持逗号 → 抛语法错误 → Parser 回退到 StmtParser
        // 重解析同一条语句 → 报 "Duplicate variable declaration: 'sy'"
        assertEquals(33, eval("int sy = switch(3) { case 1,2,3 -> 33; default -> 0; }; sy;"));
    }

    @Test
    public void regression_switchExpressionMultiValueCaseFallsToDefault() {
        assertEquals(0, eval("int sy = switch(9) { case 1,2,3 -> 33; default -> 0; }; sy;"));
    }

    // ==================== A1 ScriptRunner.setPrintAST ====================

    @Test
    public void regression_scriptRunnerSetPrintASTTakesEffect() {
        // 修复前 setPrintAST 是 this.printASTMode = printASTMode（自赋值），isPrintAST 永远 false
        ScriptRunner runner = new ScriptRunner();
        assertFalse(runner.isPrintAST());
        runner.setPrintAST(true);
        assertTrue(runner.isPrintAST());
        runner.setPrintAST(false);
        assertFalse(runner.isPrintAST());
    }

    @Test
    public void regression_scriptRunnerPrintASTEmitsAstOnParse() {
        // 输出重定向到内存，避免污染测试输出
        DefaultOutputHandler handler = new DefaultOutputHandler(
                new PrintStream(new ByteArrayOutputStream()), System.in);
        ScriptRunner runner = new ScriptRunner(handler, handler);
        runner.setPrintAST(true);
        runner.tryParse("int abc = 1;");
        assertTrue("开启 setPrintAST 后解析应输出 AST，实际输出: " + handler.getString(),
                handler.getString().contains("abc"));
    }

    // ==================== B1 import 感知的类查找缓存 ====================

    @Test
    public void regression_classBecomesResolvableAfterAddImport() {
        // 关键正确性：新增 import 后，之前"未找到"的类名必须能重新解析到
        ClassResolver.clearClassCache();
        ParseContext context = new ParseContext();
        assertNull("未 import 时 Pattern 不应可解析", context.resolveClass("Pattern"));
        context.addImport("java.util.regex.*");
        assertNotNull("新增 import 后 Pattern 应可解析", context.resolveClass("Pattern"));
    }

    @Test
    public void regression_importLookupNotPoisonedAcrossContexts() {
        // 旧实现靠 addImport 里 clearBlacklist() 保证正确性；改为 import 签名分区缓存后，
        // 一个上下文里的"未找到"不能污染另一个使用不同 import 集合的上下文
        ClassResolver.clearClassCache();
        ParseContext withoutImport = new ParseContext();
        assertNull(withoutImport.resolveClass("Pattern"));
        ParseContext withImport = new ParseContext();
        withImport.addImport("java.util.regex.*");
        assertNotNull(withImport.resolveClass("Pattern"));
    }

    // ==================== B2 类型探测启发式不改变语义 ====================

    @Test
    public void regression_multiParamLambdaStillParses() {
        // tryParseCast 的便宜前置判断不应把 lambda 参数列表误判
        assertEquals(3, eval("auto add = (a, b) -> { a + b; }; add.invoke(1, 2);"));
    }

    @Test
    public void regression_realCastStillWorks() {
        assertEquals("abc", eval("Object o = \"abc\"; String s = (String) o; s;"));
    }

    @Test
    public void regression_statementStartingWithKnownVariableStillParses() {
        // 语句首是已知变量（DeclParser 的前置判断会直接交给 StmtParser），行为必须不变
        assertEquals("xy", eval("String StringBuilder = \"x\"; StringBuilder = StringBuilder + \"y\"; StringBuilder;"));
    }

    // ==================== 嵌套数组字面量保留元素维度 ====================

    @Test
    public void regression_nestedBraceArrayLiteralIsIntMatrix() {
        // 修复前：元素维度被抹掉，外层被标注为 int[]，求值 Array.set(int[2], 0, int[]) 抛
        // "argument type mismatch"（旧版 REPL 里则静默产出 [0, 0]）
        Object v = eval("{{1}, {2}};");
        assertEquals("int[][]", v.getClass().getTypeName());
        assertEquals("[[1], [2]]", java.util.Arrays.deepToString((Object[]) v));
    }

    @Test
    public void regression_nestedBracketArrayLiteralIsIntMatrix() {
        Object v = eval("[[1, 2], [3, 4]];");
        assertEquals("int[][]", v.getClass().getTypeName());
        assertEquals("[[1, 2], [3, 4]]", java.util.Arrays.deepToString((Object[]) v));
    }

    @Test
    public void regression_raggedNestedArrayLiteral() {
        // 锯齿数组：各行长度可以不同
        Object v = eval("{{1, 2}, {3}};");
        assertEquals("int[][]", v.getClass().getTypeName());
        assertEquals("[[1, 2], [3]]", java.util.Arrays.deepToString((Object[]) v));
    }

    @Test
    public void regression_tripleNestedArrayLiteral() {
        Object v = eval("{{{1}}};");
        assertEquals("int[][][]", v.getClass().getTypeName());
        assertEquals("[[[1]]]", java.util.Arrays.deepToString((Object[]) v));
    }

    @Test
    public void regression_nestedLiteralOfNewArrayElements() {
        // 元素是 new 表达式产生的数组时同样要组成多维数组
        Object v = eval("{new int[]{1}, new int[]{2}};");
        assertEquals("int[][]", v.getClass().getTypeName());
        assertEquals("[[1], [2]]", java.util.Arrays.deepToString((Object[]) v));
    }

    @Test
    public void regression_inconsistentNestedLiteralFallsBackToObjectArray() {
        // 元素类型/维度不一致时退化为 Object[]，不得抛异常
        Object v = eval("{{1}, {2.0}};");
        assertEquals("java.lang.Object[]", v.getClass().getTypeName());
    }

    @Test
    public void regression_nestedArrayLiteralSupportsIndexing() {
        Object v = eval("int[][] m = {{1, 2}, {3, 4}}; m[1][0];");
        assertEquals(3, v);
    }

    // ==================== for 初始化变量限定在循环作用域内 ====================

    @Test
    public void regression_repeatedForLoopInOneScript() {
        // 修复前：for (int i = …) 的 i 泄漏到外层，同脚本第二个 for 再声明 i 会报
        // "Duplicate variable declaration"，且该错误被 init 回溯吞掉后误报
        // "Expected ';' after for initialization"
        Object v = eval("int s = 0;"
                + " for (int i = 0; i < 3; i++) { s = s + i; }"
                + " for (int i = 0; i < 2; i++) { s = s + i; }"
                + " s;");
        assertEquals(4, v); // (0+1+2) + (0+1)
    }

    @Test
    public void regression_repeatedForLoopInSharedContext() {
        // REPL（共享 ParseContext）里连续两次声明同名循环变量不应互相干扰
        eval("for (int i = 0; i < 1; i++) { }");
        eval("for (int i = 0; i < 1; i++) { }");
        eval("for (int i = 0; i < 1; i++) { }");
    }

    @Test
    public void regression_forInitVariableNotVisibleAfterLoop() {
        // 作用域边界正确后，循环结束后可重新声明同名变量
        Object v = eval("for (int i = 0; i < 1; i++) { } int i = 7; i;");
        assertEquals(7, v);
    }

    @Test
    public void regression_forInitVariableNotResolvableAfterLoop() {
        try {
            evalLastValue("for (int i = 0; i < 1; i++) { } println(i);");
            fail("Expected parse error: 循环变量在循环外不应可见");
        } catch (CythavaParseException expected) {
            // ok：循环变量被正确限制在 for 作用域内
        }
    }

    @Test
    public void regression_forWithOmittedClausesParses() throws CythavaParseException {
        // for (;;) / for (int i = 0;;) 等省略条件或更新的形式必须能解析
        evalLastValue("for (;;) { break; }");
        evalLastValue("for (int i = 0;;) { break; }");
        evalLastValue("for (int i = 0; i < 3;) { i = i + 1; }");
        evalLastValue("for (; true;) { break; }");
    }

    // ==================== 多变量声明不引入新作用域 ====================

    @Test
    public void regression_multiDeclaratorAllVisibleAfterwards() {
        // 修复前：多变量声明被包成词法块，i/j 随块结束一起丢弃，后续引用 i 报 Undefined variable
        assertEquals(3, eval("int i = 0, j = 3; i + j;"));
    }

    @Test
    public void regression_multiDeclaratorThreeVariables() {
        assertEquals(6, eval("int a = 1, b = 2, c = 3; a + b + c;"));
    }

    @Test
    public void regression_multiDeclaratorInForInit() {
        // for 的 init 同样走多变量声明路径
        assertEquals(3, eval("int[] arr = {1, 2}; int t = 0;"
                + " for (int i = 0, n = arr.length; i < n; i++) { t += arr[i]; } t;"));
    }

    @Test
    public void regression_autoMultiDeclarator() {
        assertEquals(3, eval("auto a = 1, b = 2; a + b;"));
    }

    @Test
    public void regression_realBlockStillScopes() {
        // 真正的词法块必须仍然隔离：块内 q 不应泄漏到块外（否则此处会报重复声明）
        assertEquals(6, eval("{ int q = 5; } int q = 6; q;"));
    }

    // ==================== 反射访问 JDK 内部实现类 ====================

    @Test
    public void regression_listOfSizeIsAccessible() {
        // 修复前：List.of 的实现类 ImmutableCollections$List12 非 public，
        // Method.invoke 抛 IllegalAccessException
        assertEquals(2, eval("java.util.List.of(1, 2).size();"));
    }

    @Test
    public void regression_listOfGetIsAccessible() {
        assertEquals(1, eval("java.util.List.of(1, 2).get(0);"));
    }

    @Test
    public void regression_mapOfAndSetOfAreAccessible() {
        assertEquals(1, eval("java.util.Map.of(\"a\", 1).get(\"a\");"));
        assertEquals(true, eval("java.util.Set.of(1, 2).contains(1);"));
    }

    @Test
    public void regression_arraysAsListAndUnmodifiableAreAccessible() {
        assertEquals(3, eval("java.util.Arrays.asList(1, 2, 3).size();"));
        assertEquals(2, eval("java.util.Collections.unmodifiableList(java.util.List.of(1, 2)).get(1);"));
    }

    @Test
    public void regression_streamOnListOfWorks() {
        assertEquals(3L, eval("java.util.List.of(3, 1, 2).stream().count();"));
    }

    // ==================== 脚本内声明的类可被后续语句引用 ====================

    @Test
    public void regression_classDeclaredInScriptIsUsable() {
        // 修复前：非 REPL 模式下 resetParseContext 未接上 codegen，
        // resolveClass 对脚本内声明的类返回 null → "Unknown type"
        assertEquals(3, eval("class Foo { int v = 3; } Foo f = new Foo(); f.v;"));
    }

    @Test
    public void regression_classMethodCallInSameScript() {
        assertEquals(3, eval("class Foo { int v() { return 3; } } Foo f = new Foo(); f.v();"));
    }

    @Test
    public void regression_interfaceImplementationInSameScript() {
        assertEquals(9, eval("interface I { int get(); }"
                + " class C implements I { int get() { return 9; } }"
                + " C c = new C(); c.get();"));
    }

    @Test
    public void regression_staticFieldOfScriptClass() {
        assertEquals(5, eval("class S { static int n = 5; } S.n;"));
    }

    @Test
    public void regression_forwardReferenceToClassDeclaredLater() {
        // 解析期不再给裸名字定身份（登记为 NameRefNode，由 link 阶段判定），
        // 于是方法体里引用"声明在后面"的类是合法的：解析时看不到，运行到方法时才需要它。
        // 修复前：解析 A 的方法体时 B 还没登记 → 解析期就报 "Cannot find symbol: 'B'"
        assertEquals(42, eval("class A { int f() { return B.v; } }"
                + " class B { static int v = 42; }"
                + " A a = new A(); a.f();"));
    }

    @Test
    public void regression_forwardStaticFieldOfClassDeclaredLater() {
        // 同上：语句级的前向引用（解析期判不出 Late 是不是类，link 阶段才判出）
        assertEquals(1, eval("y = Late.VALUE; class Late { static int VALUE = 1; } y;"));
    }

    // ==================== var / auto 类型推断标记 ====================

    @Test
    public void regression_varDeclarationWorks() {
        // 修复前：var 未列入词法器关键字表，被 TypeParser 当成类型名 → "Unknown type 'var'"
        assertEquals(6, eval("var x = 5; x + 1;"));
    }

    @Test
    public void regression_varDeclarationWithGenericNew() {
        assertEquals(1, eval("var l = new java.util.ArrayList<>(); l.add(1); l.size();"));
    }

    @Test
    public void regression_varInForEach() {
        assertEquals(6, eval("int n = 0; for (var v : new int[]{1, 2, 3}) { n += v; } n;"));
    }

    @Test
    public void regression_autoInForEach() {
        assertEquals(3, eval("int n = 0; for (auto v : new int[]{1, 2}) { n += v; } n;"));
    }

    @Test
    public void regression_varStillUsableAsVariableName() {
        // var 作为普通标识符的既有用法不能被破坏
        assertEquals(4, eval("int var = 3; var + 1;"));
        assertEquals(4, eval("int var2 = 3; var2 = 4; var2;"));
    }

    // ==================== 空值合并运算符不受类型约束 ====================

    @Test
    public void regression_nullCoalescingOnString() {
        // 修复前：?? 参与运算符重载检查，String ?? String 被误判为"无匹配运算符"
        assertEquals("dflt", eval("String s = null; s ?? \"dflt\";"));
        assertEquals("x", eval("String t = \"x\"; t ?? \"dflt\";"));
    }

    @Test
    public void regression_elvisOnString() {
        assertEquals("dflt", eval("String u = null; u ?: \"dflt\";"));
        assertEquals("x", eval("String w = \"x\"; w ?: \"dflt\";"));
    }

    // ==================== 脚本类：字段读写 ====================

    @Test
    public void regression_thisFieldAssignmentPersists() {
        // 修复前：this.v = x 走反射写入字段，随后被未修改的镜像变量写回覆盖 → 0
        assertEquals(7, eval("class P { int v; void set(int x) { this.v = x; } }"
                + " P p = new P(); p.set(7); p.v;"));
    }

    @Test
    public void regression_bareFieldAssignmentPersists() {
        assertEquals(7, eval("class Q { int v; void set(int x) { v = x; } }"
                + " Q q = new Q(); q.set(7); q.v;"));
    }

    @Test
    public void regression_fieldReadSeesPendingAssignment() {
        // 同一方法内先写后读：读取必须看到镜像中的新值，而非尚未写回的旧字段值
        assertEquals(9, eval("class D { int v; int setAndGet(int x) { this.v = x; return this.v; } }"
                + " D d = new D(); d.setAndGet(9);"));
    }

    @Test
    public void regression_compoundFieldAssignment() {
        assertEquals(5, eval("class I { int v = 1; void add(int d) { v += d; } }"
                + " I i = new I(); i.add(4); i.v;"));
    }

    @Test
    public void regression_stringFieldAssignment() {
        assertEquals("abc", eval("class K { String s; void add(String x) { this.s = x; } }"
                + " K k = new K(); k.add(\"abc\"); k.s;"));
    }

    @Test
    public void regression_unmodifiedFieldSurvivesNestedCall() {
        // 修复前：twice() 结束时不加判断地写回全部字段镜像，覆盖掉 inc() 的修改 → 0
        assertEquals(2, eval("class A { int v; void inc() { this.v = this.v + 1; }"
                + " void twice() { inc(); inc(); } }"
                + " A a = new A(); a.twice(); a.v;"));
    }

    @Test
    public void regression_fieldReadAfterNestedCall() {
        assertEquals(6, eval("class H { int v = 5; int get() { return this.v; }"
                + " void bump() { v = v + 1; } }"
                + " H h = new H(); h.bump(); h.get();"));
    }

    // ==================== 脚本类：构造器 ====================

    @Test
    public void regression_constructorParameterAssignsField() {
        // 修复前：构造器参数未进入作用域 → Cannot find symbol: 'x'
        assertEquals(7, eval("class R { int v; R(int x) { this.v = x; } } R r = new R(7); r.v;"));
    }

    @Test
    public void regression_constructorBareParameterAssignsField() {
        assertEquals(7, eval("class S { int v; S(int x) { v = x; } } S s = new S(7); s.v;"));
    }

    @Test
    public void regression_constructorBodyIsExecuted() {
        // 修复前：构造器体只注册不调用（字节码里没有 execute 调用）→ 字段保持默认值
        assertEquals(42, eval("class B { int v; B() { init(); } void init() { this.v = 42; } }"
                + " B b = new B(); b.v;"));
    }

    // ==================== 脚本类：同类方法互调 ====================

    @Test
    public void regression_methodCallsSiblingMethod() {
        // 修复前：类内未限定方法名不在标识符消歧表中 → Cannot find symbol: 'f'
        assertEquals(8, eval("class C { int f(int n) { return n + 1; }"
                + " int g(int n) { return f(n) * 2; } } C c = new C(); c.g(3);"));
    }

    @Test
    public void regression_methodCallsSiblingDeclaredLater() {
        // 类成员按书写顺序解析：被调方法声明在调用点之后也要能解析
        assertEquals(42, eval("class N { int a() { return b() + 1; } int b() { return 41; } }"
                + " N n = new N(); n.a();"));
    }

    @Test
    public void regression_explicitThisMethodCall() {
        assertEquals(8, eval("class C2 { int f(int n) { return n + 1; }"
                + " int g(int n) { return this.f(n) * 2; } } C2 c = new C2(); c.g(3);"));
    }

    @Test
    public void regression_methodSelfRecursion() {
        assertEquals(120, eval("class F { int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); } }"
                + " F f = new F(); f.fact(5);"));
    }

    @Test
    public void regression_staticMethodCallsSiblingStaticMethod() {
        assertEquals(8, eval("class E { static int f(int n) { return n + 1; }"
                + " static int g(int n) { return f(n) * 2; } } E.g(3);"));
    }

    @Test
    public void regression_selfCallDispatchesPolymorphically() {
        // Base.g() 内的 f() 应分发到 Sub 覆写的 f()
        assertEquals(12, eval("class Base { int f() { return 1; } int g() { return f() + 10; } }"
                + " class Sub extends Base { int f() { return 2; } }"
                + " Sub s = new Sub(); s.g();"));
    }

    // ==================== instanceof 模式变量绑定 ====================

    @Test
    public void regression_instanceofPatternBindsInThenBranch() {
        // 修复前：if (o instanceof String s) 报 "Expected ')' after if condition"
        assertEquals(5, eval("Object o = \"hello\"; int r = 0;"
                + " if (o instanceof String s) { r = s.length(); } r;"));
    }

    @Test
    public void regression_instanceofPatternBindsWithoutBlock() {
        // then 分支非块语句时也要能解析模式变量
        assertEquals(3, eval("Object o = \"abc\"; int r = 0;"
                + " if (o instanceof String s) r = s.length(); r;"));
    }

    @Test
    public void regression_instanceofPatternInConjunction() {
        // o instanceof String s && s.length() > 1 —— 模式变量在 && 右侧可见
        assertEquals(2, eval("Object o = \"hi\"; int r = -1;"
                + " if (o instanceof String s && s.length() > 1) { r = s.length(); } r;"));
    }

    @Test
    public void regression_instanceofPatternNotMatchedTakesElse() {
        assertEquals(2, eval("Object o = 42; int r = 0;"
                + " if (o instanceof String s) { r = 1; } else { r = 2; } r;"));
    }

    @Test
    public void regression_instanceofPatternInWhile() {
        assertEquals(3, eval("Object o = \"abc\"; int n = 0;"
                + " while (o instanceof String s) { n = s.length(); o = null; } n;"));
    }

    @Test
    public void regression_instanceofPatternVariableNotVisibleAfterIf() {
        try {
            evalLastValue("Object o = \"x\"; if (o instanceof String s) { } println(s);");
            fail("Expected parse error: 模式变量不应泄漏到 if 之外");
        } catch (CythavaParseException expected) {
            // ok：模式变量被限制在分支作用域内
        }
    }

    @Test
    public void regression_instanceofPatternDoesNotSwallowNextStatement() {
        // 无分号换行写法：x instanceof Foo 后面的标识符不能当成模式变量吞掉
        assertEquals(true, eval("Object o = \"x\"; boolean q = o instanceof String;"
                + " int later = 1; q;"));
    }

    // ==================== 类内字段前向引用 ====================

    @Test
    public void regression_fieldDeclaredAfterMethodIsReadable() {
        // 修复前：int get() { return v; } 在 v 声明之前 → Cannot find symbol: 'v'
        assertEquals(9, eval("class M { int get() { return v; } int v = 9; }"
                + " M m = new M(); m.get();"));
    }

    @Test
    public void regression_fieldDeclaredAfterMethodIsWritable() {
        assertEquals(7, eval("class M2 { void set(int x) { v = x; } int get() { return v; } int v; }"
                + " M2 m = new M2(); m.set(7); m.get();"));
    }

    @Test
    public void regression_forwardFieldAssignmentViaThis() {
        assertEquals(5, eval("class M3 { void set(int x) { this.v = x; } int v; }"
                + " M3 m = new M3(); m.set(5); m.v;"));
    }

    @Test
    public void regression_localVariableStillShadowsForwardField() {
        // 方法内同名局部变量必须仍然遮蔽字段（预扫描不能把局部变量当字段）
        assertEquals(3, eval("class M4 { int f() { int v = 3; return v; } int v = 9; }"
                + " M4 m = new M4(); m.f();"));
    }

    @Test
    public void regression_methodParameterIsNotRegisteredAsField() {
        // 形参不能被预扫描误登记为字段：q 在 g() 中仍应是未知符号
        try {
            evalLastValue("class M5 { int f(int q) { return q; } int g() { return q; } }");
            fail("Expected parse error: 形参不应被当成字段");
        } catch (CythavaParseException expected) {
            // ok
        }
    }

    @Test
    public void regression_forwardFieldOfArrayType() {
        // nums 前一个 token 是 ']'、items 前一个 token 是 '>'，都要能被预扫描识别为字段
        assertEquals("hi", eval("class M6 { String get() { return label; }"
                + " int[] nums; List<String> items; String label = \"hi\"; }"
                + " M6 m = new M6(); m.get();"));
    }

    // ==================== 类体成员的类型支持泛型 / 限定名 ====================

    @Test
    public void regression_classFieldOfGenericType() {
        // 修复前：List<String> items; 报 "Expected field name"（parseTypeReference 只取第一个 token）
        assertEquals(2, eval("class Repo {"
                + " List<String> items;"
                + " void add(String s) { if (items == null) { items = new ArrayList<>(); } items.add(s); }"
                + " int count() { return items == null ? 0 : items.size(); } }"
                + " Repo r = new Repo(); r.add(\"x\"); r.add(\"y\"); r.count();"));
    }

    @Test
    public void regression_methodReturnAndParameterOfGenericType() {
        assertEquals(2, eval("class Util {"
                + " List<String> make(String a, String b) {"
                + "   List<String> r = new ArrayList<>(); r.add(a); r.add(b); return r; } }"
                + " Util u = new Util(); u.make(\"x\", \"y\").size();"));
    }

    @Test
    public void regression_classFieldOfNestedGenericType() {
        // Map<String, List<Integer>> 会走 >> 的拆分路径
        assertEquals(0, eval("class Nested {"
                + " Map<String, List<Integer>> table;"
                + " int size() { return table == null ? 0 : table.size(); } }"
                + " Nested n = new Nested(); n.size();"));
    }

    @Test
    public void regression_classFieldOfQualifiedGenericType() {
        assertEquals(0, eval("class Qualified {"
                + " java.util.List<String> items;"
                + " int size() { return items == null ? 0 : items.size(); } }"
                + " Qualified q = new Qualified(); q.size();"));
    }

    @Test
    public void regression_methodReturnTypeOfQualifiedName() {
        assertEquals(3, eval("class Q2 {"
                + " java.util.List<String> items;"
                + " java.util.List<String> all() { return items; }"
                + " void fill() { items = new java.util.ArrayList<>(); items.add(\"a\"); items.add(\"b\");"
                + "   items.add(\"c\"); }"
                + " int n() { fill(); return all().size(); } }"
                + " Q2 q = new Q2(); q.n();"));
    }

    @Test
    public void regression_chainedCallOnSelfMethodResult() {
        assertEquals(3, eval("class Plain { String s = \"abc\";"
                + " String all() { return s; } int n() { return all().length(); } }"
                + " Plain p = new Plain(); p.n();"));
    }

    @Test
    public void regression_genericFieldDeclaredAfterMethodIsReadable() {
        // 泛型字段 + 前向引用 + 预扫描的 '>' 启发式三者一起
        assertEquals("hi", eval("class Late {"
                + " String get() { return label; }"
                + " Map<String, Integer> counts; String label = \"hi\"; }"
                + " Late l = new Late(); l.get();"));
    }

    @Test
    public void regression_classTypeParameterFieldStillWorksInStrictMode() {
        // 类泛型类型参数 T 不是真实类：严格模式下也不能报 Unknown type
        assertEquals(7, eval("class Holder<T> { T value;"
                + " void set(T v) { value = v; } T get() { return value; } }"
                + " Holder h = new Holder(); h.set(7); h.get();"));
    }

    @Test
    public void regression_extendsWithGenericTypeArgument() {
        // 修复前：class MyList extends ArrayList<String> 报 "Expected '{' before class body"
        assertEquals(1, eval("class MyList extends ArrayList<String> { }"
                + " MyList list = new MyList(); list.add(\"a\"); list.size();"));
    }

    @Test
    public void regression_implementsWithGenericTypeArgument() {
        assertEquals(1, eval("class MyCmp implements Comparable<MyCmp> {"
                + " public int compareTo(MyCmp other) { return 0; } }"
                + " MyCmp a = new MyCmp(); int r = 1; a.compareTo(a) + r;"));
    }

    // ==================== 字段赋值立即写穿 + 嵌套调用后刷新镜像 ====================

    @Test
    public void regression_nestedCallSeesPendingFieldAssignment() {
        // 修复前：s = "abc" 只落在本方法帧的镜像里（方法结束才写回字段），
        // 同一方法内随后的 all() 从实例上读到 null → NPE
        assertEquals(3, eval("class C { String s;"
                + " String all() { return s; }"
                + " int n() { s = \"abc\"; String g = all(); return g == null ? -1 : g.length(); } }"
                + " C c = new C(); c.n();"));
    }

    @Test
    public void regression_callerSeesFieldWrittenByNestedCall() {
        // s = "a" 之后 t() 把 s 改成 "b"：后写的赢，m() 读到的应是 "b"
        assertEquals("b", eval("class D { String s;"
                + " void t() { s = \"b\"; }"
                + " String m() { s = \"a\"; t(); return s; } }"
                + " D d = new D(); d.m();"));
    }

    @Test
    public void regression_fieldIncrementThenReadIsConsistent() {
        // s++ 走 storeLValue 直接写字段，若不同步镜像，紧接着读 s 会拿到自增前的旧值
        assertEquals(1, eval("class E { int s;"
                + " int bump() { s++; return s; } }"
                + " E e = new E(); e.bump();"));
    }

    @Test
    public void regression_fieldIncrementPersistsToField() {
        // s++ 不仅要更新本方法帧的镜像，还必须写穿到实例字段本身
        assertEquals(1, eval("class E2 { int s; void bump() { s++; } }"
                + " E2 e = new E2(); e.bump(); e.s;"));
    }

    @Test
    public void regression_nestedCallsAccumulateFieldChanges() {
        assertEquals(3, eval("class F { int n;"
                + " void inc() { n = n + 1; }"
                + " void three() { inc(); inc(); inc(); } }"
                + " F f = new F(); f.three(); f.n;"));
    }

    @Test
    public void regression_fieldAssignmentVisibleAfterNestedChain() {
        assertEquals(12, eval("class G { int n;"
                + " void add(int k) { n = n + k; }"
                + " void addTwice() { add(5); add(7); }"
                + " int run() { n = 0; addTwice(); return n; } }"
                + " G g = new G(); g.run();"));
    }

    @Test
    public void regression_parameterShadowingField() {
        // 形参与字段同名（this.v = v 是 Java 里最常见的写法之一）：
        // 字段镜像必须与形参分开存放，否则会读到字段旧值
        assertEquals(7, eval("class P1 { int v; void set(int v) { this.v = v; } }"
                + " P1 p = new P1(); p.set(7); p.v;"));
    }

    @Test
    public void regression_localShadowingFieldDoesNotTouchField() {
        // 局部变量遮蔽字段时，赋值不应写进字段
        assertEquals(0, eval("class P2 { int v; int f() { int v = 3; v = 5; return this.v; } }"
                + " P2 p = new P2(); p.f();"));
    }

    // ==================== assert 语句 ====================

    @Test
    public void assertStatementPassesOnTrueCondition() {
        assertEquals(1, eval("int x = 1; assert x > 0; x;"));
    }

    @Test
    public void assertStatementFailsOnFalseCondition() {
        assertEvalException("assert false;");
    }

    @Test
    public void assertStatementFailureMessageIsIncluded() {
        try {
            evalLastValue("assert 1 > 2 : \"boom\";");
            fail("Expected EvalException");
        } catch (EvalException e) {
            assertTrue("失败信息应包含断言消息，实际: " + e.getMessage(),
                    e.getMessage().contains("boom"));
        } catch (CythavaParseException e) {
            throw new AssertionError("Parse error: " + e.getMessage(), e);
        }
    }

    @Test
    public void assertStatementFailureIsCatchableAsAssertionError() {
        // 失败以 EvalException（cause 为 AssertionError）抛出，两种 catch 都应命中
        assertEquals("caught|caught", eval("String w = \"\";"
                + " try { assert false : \"boom\"; } catch (java.lang.AssertionError e) { w = \"caught\"; }"
                + " try { assert false; } catch (java.lang.RuntimeException e) { w = w + \"|caught\"; } w;"));
    }

    @Test
    public void assertStatementDoesNotEvaluateMessageWhenPassing() {
        // Java 语义：断言通过时消息表达式不求值
        assertEquals(0, eval("int i = 0; assert true : (i++); i;"));
    }

    @Test
    public void assertStatementWorksInsideMethodBody() {
        assertEquals(7, eval("class C { void check(int n) { assert n > 0 : \"n must be positive\"; } }"
                + " C c = new C(); c.check(1); 7;"));
        assertEvalException("class C2 { void check(int n) { assert n > 0 : \"n must be positive\"; } }"
                + " C2 c2 = new C2(); c2.check(-1); 7;");
    }

    // ==================== 位运算/移位的常量折叠保持 int 类型 ====================

    @Test
    public void regression_bitwiseFoldKeepsIntSoEqualityHolds() {
        // 修复前：BitwiseFolder 一律用 applyLong，1|2 折叠成 Long(3)，而运行时
        // OperatorRegistry 的 int,int→int 给出 IntValue(3)，于是
        //   (1 | 2) == 3   → 折叠后 false，未折叠时 true（折叠改变语义）
        assertEquals(Boolean.TRUE, eval("(1 | 2) == 3;"));
        assertEquals(Boolean.TRUE, eval("(1 & 3) == 1;"));
        assertEquals(Boolean.TRUE, eval("(1 ^ 3) == 2;"));
    }

    @Test
    public void regression_shiftFoldKeepsIntSoEqualityHolds() {
        // 同理：1 << 1 折叠成 Long(2) → (1 << 1) == 2 变成 false
        assertEquals(Boolean.TRUE, eval("(1 << 1) == 2;"));
        assertEquals(Boolean.TRUE, eval("(8 >> 2) == 2;"));
        assertEquals(Boolean.TRUE, eval("(-1 >>> 28) == 15;"));
    }

    @Test
    public void regression_foldedBitwiseMatchesUnfoldedResult() {
        // 折叠路径与运行期路径必须给出同一结果
        assertEquals(eval("int x1 = 1; int y1 = 2; (x1 | y1) == 3;"), eval("(1 | 2) == 3;"));
        assertEquals(eval("int x2 = 1; (x2 << 1) == 2;"), eval("(1 << 1) == 2;"));
        assertEquals(eval("int x3 = -1; (x3 >>> 28) == 15;"), eval("(-1 >>> 28) == 15;"));
    }

    @Test
    public void regression_bitwiseFoldOfLongOperandsStaysLong() {
        // 左/右操作数含 long 时仍按 long 提升（不能被上面的修复误伤）
        assertEquals(Boolean.TRUE, eval("(1L | 2L) == 3L;"));
        assertEquals(Boolean.TRUE, eval("(1L << 1) == 2L;"));
    }

    // ==================== synchronized 语句 ====================

    @Test
    public void synchronizedStatementExecutesBody() {
        assertEquals("ab", eval("StringBuilder sb = new StringBuilder(\"a\");"
                + " synchronized (sb) { sb.append(\"b\"); } sb.toString();"));
    }

    @Test
    public void synchronizedStatementReturnsFromMethod() {
        assertEquals(7, eval("class Sync { int f() { StringBuilder monitor = new StringBuilder();"
                + " synchronized (monitor) { return 7; } } }"
                + " Sync s = new Sync(); s.f();"));
    }

    @Test
    public void synchronizedStatementAllowsReentrancy() {
        assertEquals(1, eval("StringBuilder monitor = new StringBuilder();"
                + " int x = 0; synchronized (monitor) { synchronized (monitor) { x = 1; } } x;"));
    }

    @Test
    public void synchronizedStatementPropagatesBreakToEnclosingLoop() {
        assertEquals(1, eval("StringBuilder monitor = new StringBuilder(); int n = 0;"
                + " for (int i = 0; i < 3; i++) { synchronized (monitor) { n = 1; break; } } n;"));
    }

    @Test
    public void synchronizedStatementRejectsNullLock() {
        assertEvalException("Object monitor = null; synchronized (monitor) { }");
    }

    // ==================== yield 语句（switch 表达式的值） ====================

    @Test
    public void yieldSuppliesSwitchExpressionValue() {
        assertEquals(10, eval("int sy1 = switch (1) { case 1: yield 10; default: yield 20; }; sy1;"));
        assertEquals(20, eval("int sy2 = switch (9) { case 1: yield 10; default: yield 20; }; sy2;"));
    }

    @Test
    public void yieldValueIsAFullExpression() {
        assertEquals(12, eval("int sy3 = switch (1) { case 1: yield 3 * 4; default: yield 0; }; sy3;"));
    }

    @Test
    public void yieldStopsAtFirstHitWithoutFallingThrough() {
        assertEquals(1, eval("int sy4 = switch (1) { case 1: yield 1; case 2: yield 2; default: yield 3; }; sy4;"));
    }

    @Test
    public void yieldStillUsableAsPlainIdentifier() {
        // 受限标识符：yield 作为普通变量名不能被破坏
        assertEquals(4, eval("int yield = 3; yield + 1;"));
        assertEquals(9, eval("yield = 9; yield;"));
    }

    // ==================== record / sealed ====================

    @Test
    public void recordCanBeInstantiatedAndAccessorsWork() {
        // 记录在解析期脱糖：组件 → 字段 + 规范构造器 + 访问器
        assertEquals(7, eval("record Point(int x, int y) {}"
                + " Point p = new Point(3, 4); p.x() + p.y();"));
    }

    @Test
    public void recordComponentIsReadableInsideMethods() {
        assertEquals(6, eval("record Point(int x) { int doubled() { return x * 2; } }"
                + " Point p = new Point(3); p.doubled();"));
    }

    @Test
    public void recordWorksWithReferenceComponent() {
        assertEquals("hi!", eval("record Greeting(String who) {}"
                + " Greeting g = new Greeting(\"hi\"); g.who() + \"!\";"));
    }

    @Test
    public void recordComponentNameStaysUsableAsPlainIdentifier() {
        // record 是上下文关键字：作为普通变量名不能被破坏
        assertEquals(5, eval("int record = 5; record;"));
    }

    @Test
    public void recordEqualsComparesComponents() {
        assertEquals(true, eval("record Point(int x, int y) {}"
                + " Point a = new Point(1, 2); Point b = new Point(1, 2); Point c = new Point(9, 2);"
                + " a.equals(b) && !a.equals(c);"));
    }

    @Test
    public void recordEqualsRejectsNullAndForeignType() {
        assertEquals(true, eval("record Point(int x) {} Point p = new Point(1);"
                + " !p.equals(null) && !p.equals(\"Point[1]\");"));
    }

    @Test
    public void recordEqualsComparesReferenceComponentsByValue() {
        // 引用组件必须按值比较：两个内容相同但不是同一个对象
        assertEquals(true, eval("record Named(String n) {}"
                + " StringBuilder sb = new StringBuilder();"
                + " sb.append(\"ab\"); sb.append(\"c\");"
                + " Named a = new Named(sb.toString()); Named b = new Named(\"abc\");"
                + " a.equals(b);"));
    }

    @Test
    public void recordHashCodeAgreesWithEquals() {
        assertEquals(true, eval("record Point(int x, int y) {}"
                + " Point a = new Point(1, 2); Point b = new Point(1, 2);"
                + " a.hashCode() == b.hashCode();"));
    }

    @Test
    public void recordToStringListsComponents() {
        assertEquals("Point[x=1, y=2]", eval("record Point(int x, int y) {}"
                + " Point p = new Point(1, 2); p.toString();"));
    }

    @Test
    public void recordToStringWithoutComponents() {
        assertEquals("Empty[]", eval("record Empty() {} Empty e = new Empty(); e.toString();"));
    }

    @Test
    public void recordProtocolMemberCanBeOverriddenByUser() {
        assertEquals("custom", eval("record Point(int x) {"
                + " String toString() { return \"custom\"; } }"
                + " Point p = new Point(1); p.toString();"));
    }

    @Test
    public void recordWithPrimitiveAndReferenceComponentsRoundTrips() {
        assertEquals(0, eval("record Mixed(long big, boolean flag, String name) {}"
                + " Mixed a = new Mixed(1L, true, \"x\"); Mixed b = new Mixed(1L, true, \"x\");"
                + " a.hashCode() - b.hashCode();"));
    }

    @Test
    public void sealedAndPermitsAreDeclarationOnly() {
        // sealed / permits 只做声明层校验，不影响运行期使用
        assertEquals(4, eval("sealed class Shape permits Circle {}"
                + " class Circle extends Shape { int r = 4; }"
                + " Circle c = new Circle(); c.r;"));
    }

    // ==================== 数值运算符的类型提升 ====================

    /** 用带 builtin 注册的 ScriptRunner 求值（builtin 只在 ScriptRunner 的上下文里注册）。 */
    private Object evalWithRunner(String source) {
        return new ScriptRunner().executeWithResult(source);
    }

    /** 执行并把 stdout 输出收集为字符串。 */
    private String evalCapturingOutput(String source) {
        DefaultOutputHandler handler = new DefaultOutputHandler(
                new PrintStream(new ByteArrayOutputStream()), System.in);
        new ScriptRunner(handler, handler).executeWithResult(source);
        return handler.getString();
    }

    @Test
    public void regression_numericEqualityPromotesTypes() {
        // 修复前 == 落到 Value.equals，而 IntValue/LongValue/DoubleValue/CharValue 的 equals
        // 是"同类型才相等"，1 == 1L、1 == 1.0、'a' == 97 全为 false
        assertEquals(Boolean.TRUE, eval("1 == 1L;"));
        assertEquals(Boolean.TRUE, eval("1 == 1.0;"));
        assertEquals(Boolean.TRUE, eval("1.0 == 1;"));
        assertEquals(Boolean.TRUE, eval("'a' == 97;"));
        assertEquals(Boolean.FALSE, eval("1 != 1L;"));
        assertEquals(Boolean.FALSE, eval("1 == 2L;"));
    }

    @Test
    public void regression_numericEqualityPromotesTypesAtRuntime() {
        assertEquals(Boolean.TRUE, eval("int a1 = 1; long b1 = 1L; a1 == b1;"));
        assertEquals(Boolean.TRUE, eval("int a2 = 1; double d1 = 1.0; a2 == d1;"));
        assertEquals(Boolean.TRUE, eval("char c1 = 'a'; c1 == 97;"));
    }

    @Test
    public void regression_mixedNumericLiteralComparisonDoesNotCrash() {
        // 修复前常量折叠用 ((Comparable) a).compareTo(b)：Integer.compareTo(Long) 抛
        // ClassCastException，还被包成"内部错误"冒到调用方
        assertEquals(Boolean.FALSE, eval("1 < 1L;"));
        assertEquals(Boolean.TRUE, eval("1 <= 1.0;"));
        assertEquals(Boolean.TRUE, eval("2 > 1L;"));
        assertEquals(Boolean.TRUE, eval("1L <= 1;"));
        assertEquals(Boolean.TRUE, eval("1.5 > 1;"));
    }

    @Test
    public void regression_longBitwiseOpsSupported() {
        // 修复前位运算只注册了 (int,int)：`long | long` 报 "No matching operator '|'"
        assertEquals(Long.valueOf(3L), eval("long z1 = 1L; z1 | 2L;"));
        assertEquals(Long.valueOf(2L), eval("long z2 = 6L; z2 & 3L;"));
        assertEquals(Long.valueOf(2L), eval("long z3 = 1L; z3 ^ 3L;"));
        assertEquals(Long.valueOf(3L), eval("long z4 = 1L; z4 | 2;"));
        assertEquals(Long.valueOf(3L), eval("int i1 = 1; long z5 = 2L; i1 | z5;"));
        assertEquals(Long.valueOf(-7L), eval("long z6 = 6L; ~z6;"));
    }

    @Test
    public void regression_longShiftKeeps64BitWidth() {
        // 修复前移位同样只有 (int,int) 重载，long 左操作数被 asInt() 截断：
        // 1L << 40 得到 256（正确 1099511627776），3000000000L >> 1 得到负数
        assertEquals(Long.valueOf(1099511627776L), eval("long x1 = 1L; x1 << 40;"));
        assertEquals(Long.valueOf(1500000000L), eval("long y1 = 3000000000L; y1 >> 1;"));
        assertEquals(Long.valueOf(1500000000L), eval("long y2 = 3000000000L; y2 >>> 1;"));
        assertEquals(8, eval("int i2 = 1; long d2 = 3L; i2 << d2;"));
    }

    @Test
    public void regression_charArithmeticPromotesToInt() {
        assertEquals(98, eval("'a' + 1;"));
    }

    // ==================== builtin 函数 ====================

    @Test
    public void regression_printlnHandlesNullAndPrimitiveArrays() {
        // 修复前 formatValue 对 null 直接 NPE，对 int[] 强转 Object[] 抛 ClassCastException
        assertEquals("null", evalCapturingOutput("println(null);").trim());
        assertEquals("[1, 2, 3]", evalCapturingOutput("println([1, 2, 3]);").trim());
    }

    @Test
    public void regression_higherOrderBuiltinsAcceptLambdaLiterals() {
        // 修复前 callFunctionValue 只认 Function/Method，lambda 字面量（Lambda 对象）
        // 一律报 "Not a callable function: Lambda[...]"
        assertEquals(6, evalWithRunner("reduce([1, 2, 3], (a, b) -> a + b, 0);"));
        assertEquals(4, evalWithRunner("map([1, 2, 3], x -> x * 2)[1];"));
        assertEquals(2, evalWithRunner("filter([1, 2, 3], x -> x > 1)[0];"));
    }

    @Test
    public void regression_minMaxAbsClampKeepIntegralTypes() {
        // 修复前一律走 toDouble，min(1, 2) 返回 1.0（DoubleValue），整数下标/赋值会类型不匹配
        assertEquals(Integer.valueOf(1), evalWithRunner("min(1, 2);"));
        assertEquals(Integer.valueOf(3), evalWithRunner("max(1, 2, 3);"));
        assertEquals(Integer.valueOf(3), evalWithRunner("abs(-3);"));
        assertEquals(Integer.valueOf(3), evalWithRunner("clamp(5, 1, 3);"));
        assertEquals(Long.valueOf(1L), evalWithRunner("min(1L, 2);"));
        assertEquals(Double.valueOf(1.5), evalWithRunner("min(1.5, 2);"));
    }

    @Test
    public void regression_toBoolUsesTruthiness() {
        // 修复前一律 Boolean.parseBoolean(obj.toString())：toBool(1) → false，toBool(null) → NPE
        assertEquals(Boolean.TRUE, evalWithRunner("toBool(1);"));
        assertEquals(Boolean.FALSE, evalWithRunner("toBool(0);"));
        assertEquals(Boolean.FALSE, evalWithRunner("toBool(null);"));
        assertEquals(Boolean.TRUE, evalWithRunner("toBool(\"true\");"));
        assertEquals(Boolean.FALSE, evalWithRunner("toBool(\"false\");"));
    }

    @Test
    public void regression_rangeRejectsZeroStep() {
        // 修复前 step == 0 两个分支都不进入，静默返回空列表
        try {
            evalWithRunner("range(1, 5, 0);");
            fail("step == 0 应当报错");
        } catch (RuntimeException expected) {
            assertTrue("异常信息应说明 step 为 0，实际: " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains("step"));
        }
    }

    @Test
    public void regression_hexAndBinSupportLong() {
        // 修复前经 toInt 截断，hex(4294967296L) 得到 "0"
        assertEquals("100000000", evalWithRunner("hex(4294967296L);"));
        assertEquals("ffffffff", evalWithRunner("hex(-1);"));
        assertEquals("100000000000000000000000000000000", evalWithRunner("bin(4294967296L);"));
    }

    // ==================== 脚本类字段初始化与访问标志 ====================

    @Test
    public void regression_charFieldInitializerGenerates() {
        // 修复前 pushLiteral 对 char 走 (Number) 强转，Character 不是 Number → ClassCastException
        assertEquals(Character.valueOf('a'),
                eval("class CharF { char c = 'a'; } CharF f = new CharF(); f.c;"));
        assertEquals(Character.valueOf('a'), eval("class CharS { static char c = 'a'; } CharS.c;"));
    }

    @Test
    public void regression_fieldInitializerUsesDeclaredType() {
        // 修复前按字面量自身类型压栈：long a = 30; 会得到 VerifyError: Bad type on operand stack
        assertEquals(Long.valueOf(30L), eval("class LongF { long a = 30; } LongF x1 = new LongF(); x1.a;"));
        assertEquals(Double.valueOf(1.0), eval("class DblF { double a = 1; } DblF x2 = new DblF(); x2.a;"));
        // 引擎把 float 统一表示为 DoubleValue（Value.of 没有 FloatValue），这里按 Double 断言
        assertEquals(Double.valueOf(1.5), eval("class FltF { float a = 1.5f; } FltF x3 = new FltF(); x3.a;"));
        // 大 long 不能被 double 中间量吃掉精度
        assertEquals(Long.valueOf(9007199254740993L),
                eval("class HugeF { long a = 9007199254740993L; } HugeF x4 = new HugeF(); x4.a;"));
    }

    @Test
    public void regression_staticFinalConstantCoercesToFieldType() {
        // 修复前 ConstantValue 直接写字面量自身类型：`static final long L = 30;` 产出
        // "字段描述符 J + CONSTANT_Integer"，JVM 抛
        // ClassFormatError: Inconsistent constant value type
        assertEquals(Long.valueOf(30L), eval("class LongC { static final long L = 30; } LongC.L;"));
        assertEquals(Double.valueOf(1.0), eval("class DblC { static final double D = 1; } DblC.D;"));
        assertEquals(Double.valueOf(1.5), eval("class FltC { static final float F = 1.5f; } FltC.F;"));
        assertEquals(Integer.valueOf(7), eval("class IntC { static final int I = 7; } IntC.I;"));
        assertEquals("s", eval("class StrC { static final String S = \"s\"; } StrC.S;"));
        assertEquals(Boolean.TRUE, eval("class BoolC { static final boolean B = true; } BoolC.B;"));
    }

    @Test
    public void regression_explicitAccessModifiersDoNotProduceIllegalFlags() {
        // 修复前无条件 mods |= ACC_PUBLIC：private int x; 得到 0x0003（private|public），
        // JVM 抛 ClassFormatError: Illegal field modifiers。
        // 按引擎约定（成员解析走反射、宿主需直接可见）显式 private/protected 归一化为 public
        assertEquals(1, eval("class PrivF { private int a = 1; } new PrivF().a;"));
        assertEquals(1, eval("class PrivM { private int f() { return 1; } } new PrivM().f();"));
        assertEquals(2, eval("class P1 { private int f() { return 1; } int g() { return f() + 1; } } new P1().g();"));
        assertEquals(9, eval("class P3 { private int a = 5; void set(int v) { a = v; } int get() { return a; } }"
                + " P3 p = new P3(); p.set(9); p.get();"));
        assertEquals(3, eval("class P4 { private int a; private P4(int v) { a = v; }"
                + " static P4 make(int v) { return new P4(v); } int get() { return a; } } P4.make(3).get();"));
        assertEquals(1, eval("class ProtF { protected int a = 1; } new ProtF().a;"));
    }

    @Test
    public void regression_abstractMethodHasNoCodeAttribute() {
        // 修复前抽象方法也写 visitCode()，JVM 抛
        // ClassFormatError: Code attribute in native or abstract methods
        eval("abstract class AbsM { abstract int f(); }");
        // 抽象方法的存在不应影响同类的具体方法
        assertEquals(2, eval("abstract class AbsM2 { abstract int f(); int g() { return 2; } } new AbsM2() {"
                + " int f() { return 0; } }.g();"));
    }

    // ==================== 与 Java 语义对齐 ====================

    @Test
    public void regression_floatDivisionByZeroFollowsIeee754() {
        // 修复前浮点除法也走整数除零检查，`1.0 / 0` 直接抛 ArithmeticException。
        // Java 语义：浮点除法遵循 IEEE 754 → Infinity / NaN，不抛异常
        assertEquals(Double.POSITIVE_INFINITY, eval("1.0 / 0;"));
        assertEquals(Double.POSITIVE_INFINITY, eval("1 / 0.0;"));
        assertEquals(Double.NEGATIVE_INFINITY, eval("1.0 / -0.0;"));
        assertEquals(Double.NaN, eval("0.0 / 0;"));
        // 运行期（非常量折叠）路径同样如此
        assertEquals(Double.POSITIVE_INFINITY, eval("double a = 1.0; double b = 0; a / b;"));
        assertEquals(Double.NaN, eval("double c = 0.0; double d = 0.0; c % d;"));
    }

    @Test
    public void regression_integerDivisionByZeroStillThrows() {
        // 浮点放开不能顺手放过整数除零
        try {
            eval("1 / 0;");
            fail("整数除零应当抛异常");
        } catch (RuntimeException expected) {
            // ok：ConstantFolder 放弃折叠 + 运行期整除检查
        }
        try {
            eval("int a = 1; int b = 0; a % b;");
            fail("整数取模零应当抛异常");
        } catch (RuntimeException expected) {
            // ok
        }
    }

    @Test
    public void regression_constantNarrowingAssignment() {
        // 与 Java 对齐（JLS §5.2）：值在目标范围内的 int 常量可以赋给 byte/short/char
        assertEquals(1, ((Number) eval("byte b1 = 1; b1;")).intValue());
        assertEquals(4, ((Number) eval("short s1 = 4; s1;")).intValue());
        assertEquals(65, ((Number) eval("char c1 = 65; c1;")).intValue());
        // 常量折叠后的表达式同样算"常量"
        assertEquals(2, ((Number) eval("byte b2 = 1 + 1; b2;")).intValue());
    }

    // ==================== 跨运行的缓存/登记表隔离 ====================

    @Test
    public void regression_newScriptLoaderClearsClassResolutionCache() {
        // 每次运行都会新建 DynamicClassGenerator（内含可定义脚本类的 Loader）。
        // 类解析缓存只按类名索引、不含 Loader：
        //   - 不清空 → 同名脚本类跨运行命中上一次的 Class；
        //   - 更隐蔽的是负缓存：上一次记下的 NOT_FOUND 会让这一次新定义的类永远解析不到。
        ClassResolver.clearClassCache();
        ClassLoader loaderA = new DynamicClassGenerator(getClass().getClassLoader()).getLoader();
        assertNotNull(ClassResolver.findClass("java.lang.String", loaderA));
        assertNull(ClassResolver.findClass("no.such.ClassHere", loaderA));
        assertTrue("前置条件：缓存里应有条目", ClassResolver.getCacheSize() > 0);

        new DynamicClassGenerator(getClass().getClassLoader());
        assertEquals("新的脚本 Loader 出现后必须清空类解析缓存", 0, ClassResolver.getCacheSize());
    }

    @Test
    public void regression_scriptClassBodyIsNotReusedAcrossRuns() {
        // 方法体登记在生成它的 DynamicClassGenerator 上。修复前是静态表（key 只有
        // 类名+方法名+描述符），第二次运行同名类会命中上一次的方法体 —— 改了脚本却不生效
        ScriptRunner first = new ScriptRunner(getClass().getClassLoader());
        assertEquals(1, first.executeWithResult("class Reuse { int f() { return 1; } } new Reuse().f();"));

        ScriptRunner second = new ScriptRunner(getClass().getClassLoader());
        assertEquals(2, second.executeWithResult("class Reuse { int f() { return 2; } } new Reuse().f();"));
    }

    @Test
    public void regression_methodBodiesAreNotSharedBetweenCodeGenerators() {
        // 方法体必须挂在"生成它的那个" codegen 上：静态表会让两个运行器互相看见对方的
        // 同名类方法体，且条目永不回收（AST 跟着泄漏）
        assertEquals(1, eval("class MBody { int f() { return 1; } } 1;"));
        assertNotNull("方法体应登记在本测试的 codegen 上",
                codegen.findMethodBody("MBody", "f", "()I", 0));

        DynamicClassGenerator other = new DynamicClassGenerator(getClass().getClassLoader());
        assertNull("另一个 codegen 不应看见别人登记的方法体",
                other.findMethodBody("MBody", "f", "()I", 0));
    }
}
