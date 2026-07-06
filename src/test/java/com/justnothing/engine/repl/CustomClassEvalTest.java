package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.eval.CustomClassExecutor;
import com.justnothing.engine.eval.EvalContext;
import com.justnothing.engine.eval.Evaluator;
import com.justnothing.engine.eval.Value;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CustomClassEvalTest {

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

    private Value lastValue(String source) {
        try {
            Lexer lexer = new Lexer(source, "<test>");
            Parser parser = new Parser(lexer.tokenize(), parseContext, "<test>");
            List<ASTNode> nodes = parser.parse();

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
        } catch (CythavaParseException e) {
            throw new RuntimeException("Parse error: " + e.getMessage(), e);
        }
    }

    @Test
    public void classWithMethod_returnLiteral() {
        Value v = lastValue("""
                class Calc {
                    int answer() { return 42; }
                }
                Calc c = new Calc();
                c.answer()
                """);
        assertEquals(42, v.asJavaObject());
    }

    @Test
    public void classWithMethod_returnSum() {
        Value v = lastValue("""
                class Calc {
                    int add(int a, int b) { return a + b; }
                }
                Calc c = new Calc();
                c.add(3, 4)
                """);
        assertEquals(7, v.asJavaObject());
    }

    @Test
    public void classWithStaticFieldAccess() {
        Value v = lastValue("""
                class Constants {
                    static int MAX = 100;
                }
                Constants.MAX
                """);
        assertEquals(100, v.asJavaObject());
    }

    @Test
    public void classWithInstanceField_readWrite() {
        Value v = lastValue("""
                class Point {
                    int x;
                    int y;
                }
                Point p = new Point();
                p.x = 10;
                p.y = 20;
                p.x + p.y
                """);
        assertEquals(30, v.asJavaObject());
    }

    @Test
    public void classWithDoubleMethod() {
        Value v = lastValue("""
                class MathUtil {
                    double circleArea(int r) { return 3.14 * r * r; }
                }
                MathUtil m = new MathUtil();
                m.circleArea(10)
                """);
        assertEquals(314.0, (Double) v.asJavaObject(), 0.01);
    }

    @Test
    public void classWithStaticMethod() {
        Value v = lastValue("""
                class Utils {
                    static String greet(String name) { return "Hello, " + name + "!"; }
                }
                Utils.greet("World")
                """);
        assertEquals("Hello, World!", v.asJavaObject());
    }

    @Test
    public void classWithStringConcatInMethod() {
        Value v = lastValue("""
                class Greeter {
                    String hello(String name) { return "Hi " + name; }
                }
                Greeter g = new Greeter();
                g.hello("Test")
                """);
        assertEquals("Hi Test", v.asJavaObject());
    }

    @Test
    public void classWithBooleanReturn() {
        Value v = lastValue("""
                class Checker {
                    boolean isPositive(int x) { return x > 0; }
                }
                Checker c = new Checker();
                c.isPositive(5)
                """);
        assertEquals(true, v.asJavaObject());
    }

    @Test
    public void classWithMultipleMethods() {
        Value v = lastValue("""
                class MathBox {
                    int square(int x) { return x * x; }
                    int cube(int x) { return x * x * x; }
                }
                MathBox m = new MathBox();
                m.square(3) + m.cube(2)
                """);
        assertEquals(17, v.asJavaObject());
    }

    @Test
    public void classWithStringReturn() {
        Value v = lastValue("""
                class Echo {
                    String echo(String s) { return s; }
                }
                Echo e = new Echo();
                e.echo("hello")
                """);
        assertEquals("hello", v.asJavaObject());
    }

    @Test
    public void classWithVoidMethod() {
        Value v = lastValue("""
                class Action {
                    int count;
                    void inc() { count = count + 1; }
                }
                Action a = new Action();
                a.inc();
                a.count
                """);
        assertEquals(1, v.asJavaObject());
    }

    @Test
    public void anonymousClassWithMethod() {
        Value v = lastValue("""
                Object obj = new Object() {
                    int value() { return 99; }
                };
                obj.value()
                """);
        assertEquals(99, v.asJavaObject());
    }

    // ==================== 变量作用域 ====================

    @Test
    public void blockScope_localVarHidesField() {
        Value v = lastValue("""
                class Scope {
                    int x = 1;
                    int test() {
                        int x = 2;
                        return x;
                    }
                }
                Scope s = new Scope();
                s.test()
                """);
        assertEquals(2, v.asJavaObject());
    }

    @Test
    public void blockScope_fieldAccessibleAfterBlock() {
        Value v = lastValue("""
                class Scope {
                    int x = 1;
                    int test() {
                        { int y = 2; }
                        return x;
                    }
                }
                Scope s = new Scope();
                s.test()
                """);
        assertEquals(1, v.asJavaObject());
    }

    @Test
    public void blockScope_nestedBlocks() {
        Value v = lastValue("""
                class Scope {
                    int test() {
                        int a = 1;
                        { int b = 2; { a = a + b; } }
                        return a;
                    }
                }
                Scope s = new Scope();
                s.test()
                """);
        assertEquals(3, v.asJavaObject());
    }

    @Test
    public void blockScope_fieldWriteInBlock() {
        Value v = lastValue("""
                class Scope {
                    int count = 0;
                    void inc() { { count = count + 1; } }
                }
                Scope s = new Scope();
                s.inc();
                s.inc();
                s.count
                """);
        assertEquals(2, v.asJavaObject());
    }

    // ==================== 匿名类与 Java 兼容性 ====================

    @Test
    public void anonymousClass_extendThread() throws Exception {
        Value v = lastValue("""
                Thread t = new Thread() {
                    String result;
                    void run() { result = "ran"; }
                };
                t.run();
                t.getClass().getDeclaredField("result").get(t) + ""
                """);
        assertEquals("ran", v.asJavaObject());
    }

    @Test
    public void anonymousClass_inheritsThreadMethods() {
        Value v = lastValue("""
                Thread t = new Thread("test-thread");
                t.getName()
                """);
        assertEquals("test-thread", v.asJavaObject());
    }

    @Test
    public void anonymousClass_extendsConcreteClass() {
        Value v = lastValue("""
                java.util.ArrayList<Object> list = new java.util.ArrayList<Object>() {
                    int count;
                };
                list.getClass().getSuperclass().getSimpleName()
                """);
        assertEquals("ArrayList", v.asJavaObject());
    }

    @Test
    public void anonymousClass_inheritsObjectMethods() {
        Value v = lastValue("""
                Object obj = new Object() {
                    int id;
                };
                obj.hashCode()
                """);
        assertNotNull(v);
        assertTrue(v.asJavaObject() instanceof Integer);
    }

    @Test
    public void anonymousClass_withFieldAndMethod() {
        Value v = lastValue("""
                Object obj = new Object() {
                    int val = 42;
                    int getVal() { return val; }
                };
                obj.getVal()
                """);
        assertEquals(42, v.asJavaObject());
    }

    @Test
    public void anonymousClass_methodWithParam() {
        Value v = lastValue("""
                Object obj = new Object() {
                    int add(int a, int b) { return a + b; }
                };
                obj.add(5, 7)
                """);
        assertEquals(12, v.asJavaObject());
    }

    @Test
    public void anonymousClass_multipleMethods() {
        Value v = lastValue("""
                Object obj = new Object() {
                    int twice(int x) { return x * 2; }
                    int triple(int x) { return x * 3; }
                };
                obj.twice(3) + obj.triple(4)
                """);
        assertEquals(18, v.asJavaObject());
    }

    @Test
    public void anonymousClass_callingMethodOnField() {
        Value v = lastValue("""
                Object obj = new Object() {
                    String greeting = "Hello";
                    String greet(String name) { return greeting + " " + name; }
                };
                obj.greet("World")
                """);
        assertEquals("Hello World", v.asJavaObject());
    }
}
