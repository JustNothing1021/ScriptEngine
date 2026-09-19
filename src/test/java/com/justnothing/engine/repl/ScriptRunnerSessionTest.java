package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ScriptRunner} 会话生命周期测试。
 * <p>
 * 校验"解析上下文"与"运行期变量"两个 Context 同进同退：
 * <ul>
 *   <li>脚本模式（{@code replMode=false}，默认）：每次 execute 前两者一起重置，多次执行互相隔离</li>
 *   <li>REPL 模式（{@code replMode=true}）：两者一起保留，变量 / import / 类声明跨 execute 生效</li>
 *   <li>{@code clearVariables()} / {@code deleteVariable()}：两侧一起清，不留分歧</li>
 * </ul>
 * 历史缺陷：{@code replMode} 原先只重置 ParseContext，运行期变量表从不清空，
 * 于是脚本模式下"解析器忘了、运行期记得"，同一段代码时而报 Cannot find symbol、
 * 时而报 Undefined variable。
 */
public class ScriptRunnerSessionTest {

    private static RuntimeException expectThrow(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        fail("expected an exception, but none was thrown");
        return null; // unreachable
    }

    /** 脚本模式：第一次执行声明的变量，第二次执行看不见。 */
    @Test
    public void scriptMode_isolatesVariablesBetweenExecutes() {
        ScriptRunner runner = new ScriptRunner();

        assertEquals(5, runner.executeWithResult("int x = 5;"));

        RuntimeException e = expectThrow(() -> runner.executeWithResult("x;"));
        assertTrue("应是解析期报错（两侧都忘了），实际: " + e.getMessage(),
                e.getMessage().startsWith("Parse error"));
    }

    /** 脚本模式：变量表在下一次 execute 时被清空。 */
    @Test
    public void scriptMode_clearsVariableTableOnNextExecute() {
        ScriptRunner runner = new ScriptRunner();

        runner.executeWithResult("int x = 5;");
        assertTrue("本次执行后变量仍在（供读回），实际应保留到下次执行", runner.hasVariable("x"));

        runner.executeWithResult("1 + 1;");
        assertFalse("下一次执行应已清空变量表", runner.hasVariable("x"));
    }

    /** 脚本模式：隔离使得同名变量可以重复声明。 */
    @Test
    public void scriptMode_allowsRedeclaringSameName() {
        ScriptRunner runner = new ScriptRunner();

        assertEquals(1, runner.executeWithResult("int n = 1;"));
        assertEquals(2, runner.executeWithResult("int n = 2;"));
    }

    /** REPL 模式：变量跨 execute 可读可写。 */
    @Test
    public void replMode_keepsVariablesAcrossExecutes() {
        ScriptRunner runner = new ScriptRunner();
        runner.setReplMode(true);

        assertEquals(5, runner.executeWithResult("int x = 5;"));
        assertEquals(5, runner.executeWithResult("x;"));
        assertEquals(99, runner.executeWithResult("x = 99;"));
        assertEquals(99, runner.executeWithResult("x;"));
    }

    /** REPL 模式：clearVariables() 把两侧一起清掉，后续读取是解析期报错而非运行期报错。 */
    @Test
    public void clearVariables_clearsBothSides() {
        ScriptRunner runner = new ScriptRunner();
        runner.setReplMode(true);

        runner.executeWithResult("int x = 5;");
        runner.clearVariables();
        assertFalse(runner.hasVariable("x"));

        RuntimeException e = expectThrow(() -> runner.executeWithResult("x;"));
        assertTrue("两侧应一起清空，报解析期错误，实际: " + e.getMessage(),
                e.getMessage().startsWith("Parse error"));
    }

    /** deleteVariable() 同样两侧一起清。 */
    @Test
    public void deleteVariable_clearsBothSides() {
        ScriptRunner runner = new ScriptRunner();
        runner.setReplMode(true);

        runner.executeWithResult("int y = 7;");
        runner.deleteVariable("y");
        assertFalse(runner.hasVariable("y"));

        RuntimeException e = expectThrow(() -> runner.executeWithResult("y;"));
        assertTrue("两侧应一起清空，报解析期错误，实际: " + e.getMessage(),
                e.getMessage().startsWith("Parse error"));
    }

    /** REPL 模式可切回脚本模式：切回后下次执行恢复隔离。 */
    @Test
    public void switchingBackToScriptModeRestoresIsolation() {
        ScriptRunner runner = new ScriptRunner();
        runner.setReplMode(true);
        assertEquals(5, runner.executeWithResult("int x = 5;"));
        assertEquals(5, runner.executeWithResult("x;"));

        runner.setReplMode(false);
        // 清空发生在下一次执行之前（懒重置），此前的变量在本次执行后即不可见
        assertEquals(1, runner.executeWithResult("int n = 1;"));
        RuntimeException e = expectThrow(() -> runner.executeWithResult("x;"));
        assertTrue("切回脚本模式后应重新隔离，实际: " + e.getMessage(),
                e.getMessage().startsWith("Parse error"));
    }
}
