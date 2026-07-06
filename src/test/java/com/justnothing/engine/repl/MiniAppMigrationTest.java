package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MiniAppMigrationTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private ScriptRunner runner;

    @Before
    public void setUp() {
        System.setOut(new PrintStream(outContent));
        runner = new ScriptRunner();
        runner.setStrictMode(false);  // 旧版 raw class 代码兼容
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    public void testHashMapNewConstructorCall() {

        Object result = runner.executeWithResult("""
            auto map = HashMap.new();
            map.put("a", 1);
            map.put("b", 2);
            map.get("a");
            """);
        assertEquals(1, result);
    }

    @Test
    public void testArrayListNewConstructorCall() {

        Object result = runner.executeWithResult("""
            auto list = ArrayList.new();
            list.add(10);
            list.add(20);
            list.size();
            """);
        assertEquals(2, result);
    }

    @Test
    public void testLambdaAndInvoke() {

        Object result = runner.executeWithResult("""
            auto map = HashMap.new();
            map.put("double", (x) -> { x * 2; });
            map.get("double").invoke(5);
            """);
        assertEquals(10, result);
    }

    @Test
    public void testLambdaWithMultipleArgs() {

        Object result = runner.executeWithResult("""
            auto map = HashMap.new();
            map.put("add", (a, b) -> { a + b; });
            map.get("add").invoke(3, 7);
            """);
        assertEquals(10, result);
    }

    @Test
    public void testBuiltinPrintln() {

        runner.execute("println(\"hello\", \"world\")");
        String output = outContent.toString().trim();
        assertTrue("Expected output to contain 'hello world', got: " + output, output.contains("hello world"));
    }

    @Test
    public void testMiniAppPlayableComponents() {

        Object result = runner.executeWithResult("""
            auto createPlayer = (name) -> {
                auto player = HashMap.new();
                player.put("name", name);
                player.put("hp", 100);
                player.put("maxHp", 100);
                player.put("attack", 15);
                player.put("defense", 5);
                player.put("level", 1);
                player.put("exp", 0);
                player.put("inventory", ArrayList.new());
                player.put("takeDamage", (damage) -> {
                    auto currentHp = player.get("hp");
                    player.put("hp", currentHp - damage);
                    player.get("hp");
                });
                player;
            };
            auto p = createPlayer("Hero");
            p.get("hp");
            """);
        assertEquals(100, result);

        Object hpAfter = runner.executeWithResult("""
            p.get("takeDamage").invoke(30);
            """);
        assertEquals(70, hpAfter);
    }

    @Test
    public void testImportAndRandomNew() {

        runner.addImport("java.util.Random");
        Object randObj = runner.executeWithResult("""
            auto r = Random.new();
            r.nextInt(100);
            """);
        assertNotNull(randObj);
        assertTrue((int) randObj >= 0 && (int) randObj < 100);
    }

    @Test
    public void testBuiltinSleep() {

        long start = System.currentTimeMillis();
        runner.execute("sleep(200)");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("sleep should wait at least 200ms, got " + elapsed + "ms", elapsed >= 150);
    }

    @Test
    public void testPrintfAndReadLine() {

        runner.execute("printf(\"score: %d\", 42)");
        String output = outContent.toString().trim();
        assertTrue(output.contains("score: 42"));
    }

    @Test
    public void testBuiltinCollectionFunctions() {

        Object result = runner.executeWithResult("""
            auto r = range(1, 5);
            size(r);
            """);
        assertEquals(4, result);
    }

    @Test
    public void testBuiltinTypeFunctions() {

        Object result = runner.executeWithResult("typeof(\"hello\")");
        assertEquals("String", result);
    }

    @Test
    public void testStaticMethodCall() {

        Object result = runner.executeWithResult("Math.max(10, 20)");
        assertEquals(20, result);
    }
}
