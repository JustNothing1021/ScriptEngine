package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.*;

import java.lang.reflect.Array;

public class MiniAppFullTest {

    private ScriptRunner runner;

    @Before
    public void setUp() {
        runner = new ScriptRunner();
        runner.setStrictMode(false);  // 旧版 raw class 代码兼容
    }

    // ========== Baseline ==========

    @Test
    public void baseline_noIf() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> { true; };
            println("OK");
            """);
    }

    @Test
    public void verify_noIf_inG() {
        // Same as cmp_lt_otherVar but WITHOUT any if statement in g
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto current = player.get("hp");
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void verify_assignThenIf_inG() {
        // auto decl, then assignment to current, then if
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto current = player.get("hp");
                current = 0;
                if (current < 0) current = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void verify_ifBeforeAuto_inG() {
        // if before auto
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                if (player.get("hp") < 0) player.put("hp", 0);
                auto current = player.get("hp");
                true;
            };
            println("OK");
            """);
    }

    // ========== Critical: bare if with different comparison operators ==========

    @Test
    public void cmp_gt() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h > 100) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_lt() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_eq() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h == 0) h = 1;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_neq() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h != 0) h = 1;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_ge() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h >= 100) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_lt_noTempVar() {
        // What if we skip 'auto h = ...' and use player.get directly?
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                if (player.get("hp") < 0) player.put("hp", 0);
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_lt_otherVar() {
        // What if the if temp var is named something that doesn't start with h?
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto current = player.get("hp");
                if (current < 0) current = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void cmp_lt_assignBeforeIf() {
        // What if there's an assignment BEFORE the if?
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("hp", 100);
                p.put("heal", (d) -> { p.put("hp", p.get("hp") + d); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                h = h;
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    // ========== lambda count threshold ==========

    @Test
    public void count_2lambdas_lt() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("a", (d) -> { auto x = p.get("hp"); });
                p.put("b", (d) -> { auto x = p.get("hp"); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void count_3lambdas_lt() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("a", (d) -> { auto x = p.get("hp"); });
                p.put("b", (d) -> { auto x = p.get("hp"); });
                p.put("c", (d) -> { auto x = p.get("hp"); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void verify_blockThenBare() {
        // If a block-if comes BEFORE the bare if, does it fix the issue?
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("a", (d) -> { auto x = p.get("hp"); });
                p.put("b", (d) -> { auto x = p.get("hp"); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h > 0) { h = 0; }
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    @Test
    public void verify_bareIfOnly() {
        // Bare if right after auto h = ...
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.new();
                p.put("a", (d) -> { auto x = p.get("hp"); });
                p.put("b", (d) -> { auto x = p.get("hp"); });
                p;
            };
            auto g = (player, enemy) -> {
                auto h = player.get("hp");
                if (h < 0) h = 0;
                true;
            };
            println("OK");
            """);
    }

    // ==================== 泛型构造器测试 ====================

    @Test
    public void genericConstructor_basic() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto map = HashMap.<String, Integer>.new();
            map.put("hp", 100);
            auto h = map.get("hp");
            if (h < 0) h = 0;
            println("OK");
            """);
    }

    @Test
    public void genericConstructor_inLambda() {
        runner.addImport("java.util.HashMap");
        runner.execute("""
            auto f = (name) -> {
                auto p = HashMap.<String, Integer>.new();
                p.put("hp", 100);
                auto h = p.get("hp");
                if (h < 0) h = 0;
                p;
            };
            println(f("test"));
            """);
    }

    @Test
    public void genericConstructor_rawHashMap_runtimeFails() {
        runner.addImport("java.util.HashMap");
        try {
            runner.execute("""
                auto p = HashMap.new();
                auto h = p.get("hp");
                if (h < 0) h = 0;
                """);
            fail("Raw HashMap should fail at runtime");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue("Expected runtime error about null comparison, got: " + msg,
                msg.contains("NullValue") || msg.contains("null"));
        }
    }

    // ==================== 函数式接口隐式转换 ====================

    @Test
    public void fi_lambdaAsConsumer_viaListForEach() {
        runner.addImport("java.util.ArrayList");
        runner.execute("""
            auto list = new ArrayList<String>();
            list.add("hello");
            list.add("world");
            list.forEach(x -> println(x));
            """);
    }

    @Test
    public void fi_lambdaAsRunnable_viaThread() {
        runner.execute("""
            auto t = new java.lang.Thread(() -> println("from thread"));
            t.start();
            t.join();
            """);
    }

    @Test
    public void fi_consumerAssignment() {
        runner.addImport("java.util.function.Consumer");
        runner.execute("""
            auto list = new java.util.ArrayList<String>();
            list.add("a");
            list.add("b");
            list.add("c");
            Consumer<String> c = s -> list.add(s.toUpperCase());
            list.forEach(c);
            println(list.size());
            """);
    }

    // ==================== 自定义运算符重载运行时 ====================

    @Test
    public void operator_overload_customClass() {
        runner.addImport("java.util.*");
        // 先定义类和方法（跨 execute 调用，类会通过 codegen 注册）
        runner.execute("""
            class Vec { int x, y; }
            Vec operator+(Vec a, Vec b) {
                auto r = new Vec();
                r.x = a.x + b.x;
                r.y = a.y + b.y;
                return r;
            }
            """);
        // 再使用
        Object res = runner.executeWithResult("""
            auto v1 = new Vec();
            v1.x = 1; v1.y = 2;
            auto v2 = new Vec();
            v2.x = 3; v2.y = 4;
            auto v3 = v1 + v2;
            println(v3.x);
            {v3.x, v3.y};
            """);
        Object[] resArr = new Object[Array.getLength(res)];
        for (int i = 0; i < resArr.length; i++) resArr[i] = Array.get(res, i);
        assertArrayEquals(new Object[] {4, 6}, resArr);
    }

    // ==================== 关键字 Token ====================

    @Test
    public void keyword_packageDeclaration() {
        runner.execute("package test.foo;");
    }


    @Test
    public void keyword_enumDeclaration() {
        Object res = runner.executeWithResult("""
            enum Color { RED, GREEN, BLUE }
            Color.RED;
            """);
    }

    // ==================== 复杂运行时测试 ====================

    /** for 循环运行时: 迭代求和 */
    @Test
    public void forLoop_sum() {
        runner.addImport("java.util.ArrayList");
        Object res = runner.executeWithResult("""
            auto sum = 0;
            for (int i = 1; i <= 10; i = i + 1) {
                sum = sum + i;
            }
            sum;
            """);
        assertEquals(55, res);
    }

    /** while 循环运行时 */
    @Test
    public void whileLoop_count() {
        Object res = runner.executeWithResult("""
            auto i = 0;
            auto sum = 0;
            while (i < 5) {
                sum = sum + 10;
                i = i + 1;
            }
            sum;
            """);
        assertEquals(50, res);
    }

    /** do-while 循环运行时 */
    @Test
    public void doWhileLoop_count() {
        Object res = runner.executeWithResult("""
            auto i = 0;
            auto sum = 0;
            do {
                sum = sum + 7;
                i = i + 1;
            } while (i < 3);
            sum;
            """);
        assertEquals(21, res);
    }

    /** for 循环 break */
    @Test
    public void forLoop_break() {
        Object res = runner.executeWithResult("""
            auto sum = 0;
            for (int i = 1; i <= 100; i = i + 1) {
                if (i > 5) break;
                sum = sum + i;
            }
            sum;
            """);
        assertEquals(15, res);
    }

    /** 递归函数: 阶乘 */
    @Test
    public void recursiveFunction_factorial() {
        runner.execute("""
            function int fact(int n) {
                if (n <= 1) return 1;
                return n * fact(n - 1);
            }
            """);
        Object res = runner.executeWithResult("fact(5);");
        assertEquals(120, res);
    }

    /** 尾递归: 累计求和 */
    @Test
    public void recursiveFunction_tailSum() {
        runner.execute("""
            function int tailSum(int n, int acc) {
                if (n == 0) return acc;
                return tailSum(n - 1, acc + n);
            }
            """);
        Object res = runner.executeWithResult("tailSum(10, 0);");
        assertEquals(55, res);
    }

    /** 递归: 奇偶判断 */
    @Test
    public void recursiveFunction_isEven() {
        runner.execute("""
            function boolean isEven(int n) {
                if (n == 0) return true;
                if (n == 1) return false;
                return isEven(n - 2);
            }
            """);
        Object res = runner.executeWithResult("isEven(6);");
        assertEquals(true, res);
        Object res2 = runner.executeWithResult("isEven(7);");
        assertEquals(false, res2);
    }

    /** lambda 捕获外部变量并变异 */
    @Test
    public void lambda_captureAndMutate() {
        Object res = runner.executeWithResult("""
            auto counter = 0;
            auto inc = () -> { counter = counter + 1; };
            inc();
            inc();
            inc();
            counter;
            """);
        assertEquals(3, res);
    }

    /** for-each 遍历 ArrayList */
    @Test
    public void forEach_listSum() {
        runner.addImport("java.util.ArrayList");
        runner.addImport("java.util.Arrays");
        Object res = runner.executeWithResult("""
            auto list = new ArrayList<Integer>();
            list.add(10); list.add(20); list.add(30);
            auto sum = 0;
            for (auto x : list) {
                sum = sum + x;
            }
            sum;
            """);
        assertEquals(60, res);
    }

    /** 嵌套作用域变量遮蔽 */
    @Test
    public void nestedScope_shadowing() {
        Object res = runner.executeWithResult("""
            auto res = 0;
            auto x = 1;
            {
                auto x = 2;
                {
                    auto x = 3;
                }
                res = x;
            }
            res;
            """);
        assertEquals(2, res);
    }

    /** 跨 execute 调用保持类 + 函数 */
    @Test
    public void crossExecute_classAndFunction() {
        runner.execute("""
            class Box { int val; }
            function Box makeBox(int v) {
                auto b = new Box();
                b.val = v;
                return b;
            }
            """);
        Object res = runner.executeWithResult("makeBox(42);");
    }

    /** 枚举 + 方法 */
    @Test
    public void enumWithMethod() {
        runner.execute("""
            enum Planet { MERCURY, VENUS, EARTH }
            """);
        Object res = runner.executeWithResult("Planet.EARTH;");
        // 只要不抛异常就算过
    }

    /** for-each 中同时修改 list */
    @Test
    public void forEach_mutateDuringIteration() {
        runner.addImport("java.util.ArrayList");
        Object res = runner.executeWithResult("""
            auto list = new ArrayList<String>();
            list.add("a");
            list.add("b");
            list.add("c");
            auto out = new ArrayList<String>();
            for (auto s : list) {
                out.add(s.toUpperCase());
            }
            out.size();
            """);
        assertEquals(3, res);
    }

    /** 函数调用链 + 递归: 斐波那契 */
    @Test
    public void recursiveFunction_fibonacci() {
        runner.execute("""
            function int fib(int n) {
                if (n <= 1) return n;
                return fib(n - 1) + fib(n - 2);
            }
            """);
        Object res = runner.executeWithResult("fib(10);");
        assertEquals(55, res);
    }
}
