package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;
import java.util.Scanner;

/**
 * Cythava AST REPL（Read-Eval-Print Loop）。
 * <p>
 * 交互式解析工具：输入 Cythava 源代码，输出对应的 AST 节点树。
 * 用于在开发 Evaluator 之前验证 Parser 的输出是否符合预期。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>
 *   cythava&gt; class MyClass { int x; }
 *   ClassDeclarationNode
 *     className: MyClass
 *     modifiers:
 *     fields: 1
 *       field[0]:
 *         FieldDeclarationNode
 *           fieldName: x
 *           type: int
 *           initialValue: null
 *   cythava&gt;
 * </pre>
 *
 * <h3>特殊命令</h3>
 * <ul>
 *   <li>{@code :q} 或 {@code :quit} — 退出 REPL</li>
 *   <li>{@code :h} 或 {@code :help} — 显示帮助信息</li>
 *   <li>空行 — 跳过</li>
 * </ul>
 */
public class AstRepl {

    private static final String PROMPT = "cythava> ";
    private static final String CONTINUE = "      ... ";

    /** 骨架类生成器（在 parseAndPrint 中用于将 ClassDeclarationNode 转为可反射的 Class）。 */
    private static DynamicClassGenerator codegen;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         Cythava AST REPL  v0.1               ║");
        System.out.println("║   输入代码查看解析结果 | :help 获取帮助      ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        ParseContext context = new ParseContext();
        codegen = new DynamicClassGenerator(context.getClassLoader());
        context.setClassLoader(codegen.getLoader());
        context.setCodeGenerator(codegen);

        // 默认导入：java.lang.* 由 JVM 隐含导入，java.util.* 是最常用的工具包
        context.addImport("java.util.*");

        while (true) {
            System.out.print(PROMPT);
            String input = readInput(scanner);

            if (input == null) {
                // EOF (Ctrl+D / Ctrl+Z)
                System.out.println("\n再见！");
                break;
            }

            String trimmed = input.trim();

            // 跳过空行和纯注释行
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }

            if (isCommand(trimmed)) {
                if (handleCommand(trimmed)) {
                    break;
                }
                continue;
            }

            parseAndPrint(input, context);
        }
    }

    /**
     * 读取输入（支持多行模式）。
     * <p>
     * 如果当前行以 {@code \} 结尾或括号/花括号未闭合，
     * 则继续读取下一行直到完整。
     * </p>
     */
    private static String readInput(Scanner scanner) {
        if (!scanner.hasNextLine()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(scanner.nextLine());
        int depth = countUnclosedBrackets(sb.toString());

        while (depth > 0) {
            System.out.print(CONTINUE);
            String line = scanner.nextLine();
            if (line == null) return null;
            sb.append('\n').append(line);
            depth = countUnclosedBrackets(sb.toString());
        }

        return sb.toString();
    }

    /** 计算未闭合的括号深度（圆括号 + 方括号 + 花括号）。 */
    private static int countUnclosedBrackets(String s) {
        int depth = 0;
        for (char c : s.toCharArray()) {
            switch (c) {
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> depth--;
            }
        }
        return Math.max(0, depth);
    }

    private static boolean isCommand(String input) {
        return input.startsWith(":");
    }

    /**
     * 处理特殊命令。
     *
     * @return true 表示应该退出
     */
    private static boolean handleCommand(String cmd) {
        return switch (cmd.toLowerCase()) {
            case ":q", ":quit" -> {
                System.out.println("再见！");
                yield true;
            }
            case ":h", ":help" -> {
                printHelp();
                yield false;
            }
            default -> {
                System.out.println("未知命令: " + cmd + " (输入 :help 查看帮助)");
                yield false;
            }
        };
    }

    private static void printHelp() {
        System.out.println("""
                ┌─────────────────────────────────────────┐
                │  可用命令                                │
                ├─────────────────────────────────────────┤
                │  :q, :quit    退出 REPL                 │
                │  :h, :help    显示此帮助                │
                │                                         │
                │  多行输入:                              │
                │    - 输入以 \\ 结尾的行会继续下一行       │
                │    - 括号/花括号未闭合时自动续行          │
                └─────────────────────────────────────────┘""");
    }

    /** 解析输入并打印 AST 树（支持声明、语句、表达式三种模式）。 */
    private static void parseAndPrint(String source, ParseContext context) {
        try {
            long start = System.nanoTime();
            Lexer lexer = new Lexer(source, "<repl>");
            Parser parser = new Parser(lexer.tokenize(), context, "<repl>");
            List<ASTNode> nodes = parser.parse();
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            // 将声明的类注册到上下文 + 生成骨架类（使 Java 反射可用）
            for (ASTNode node : nodes) {
                if (node instanceof ClassDeclarationNode classDecl) {
                    context.declareClass(classDecl);
                    try {
                        codegen.generate(classDecl);
                    } catch (Exception e) {
                        System.out.println("  [CodeGen] 骨架类生成跳过: " + e.getMessage());
                    }
                }
            }

            if (nodes.size() == 1) {
                System.out.println("┌─ 解析结果 (" + elapsed + "ms) ─────────────────────");
                System.out.println(nodes.get(0).formatString(1));
            } else {
                System.out.println("┌─ 解析结果 (" + nodes.size() + " 个节点, " + elapsed + "ms) ───────────");
                for (int i = 0; i < nodes.size(); i++) {
                    System.out.println("│ 节点 #" + (i + 1) + ":");
                    System.out.println(nodes.get(i).formatString(1));
                    if (i < nodes.size() - 1) {
                        System.out.println("│ ─────────────────────────────────────");
                    }
                }
            }
            System.out.println("└──────────────────────────────────────────────┘");

        } catch (CythavaParseException e) {
            System.out.println("  解析错误: " + e.getMessage());
            if (e.getLocation() != null) {
                System.out.println("    位置: " + e.getLocation());
            }
        } catch (Exception e) {
            System.out.println("  ✗ 内部错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println();
    }
}
