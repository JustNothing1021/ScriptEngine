package com.justnothing.engine.repl;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.codegen.DynamicClassGenerator;
import com.justnothing.engine.eval.CustomClassExecutor;
import com.justnothing.engine.eval.EvalContext;
import com.justnothing.engine.exception.EvalException;
import com.justnothing.engine.eval.Evaluator;
import com.justnothing.engine.eval.Value;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

import java.util.List;
import java.util.Scanner;

public class EvalRepl {

    private static final String PROMPT = "eval> ";
    private static final String CONTINUE = "   ... ";

    public static void main(String[] args) {
        System.out.println("Cythava Eval REPL");
        System.out.println("输入代码执行 | :help 获取帮助");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        ParseContext parseContext = new ParseContext();
        EvalContext evalContext = new EvalContext();
        DynamicClassGenerator codegen = new DynamicClassGenerator(parseContext.getClassLoader());
        codegen.setDelegateToExecutor(true);
        parseContext.setClassLoader(codegen.getLoader());
        parseContext.setCodeGenerator(codegen);

        parseContext.addImport("java.util.*");

        while (true) {
            System.out.print(PROMPT);
            String input = readInput(scanner);

            if (input == null) {
                System.out.println("\n再见！");
                break;
            }

            String trimmed = input.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }

            if (isCommand(trimmed)) {
                if (handleCommand(trimmed, evalContext)) {
                    break;
                }
                continue;
            }

            evalAndPrint(input, parseContext, evalContext, codegen);
        }
    }

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

    private static boolean handleCommand(String cmd, EvalContext evalContext) {
        return switch (cmd.toLowerCase()) {
            case ":q", ":quit" -> {
                System.out.println("再见！");
                yield true;
            }
            case ":h", ":help" -> {
                printHelp();
                yield false;
            }
            case ":vars" -> {
                // TODO: list variables
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
                │  :vars        显示当前变量              │
                │                                         │
                │  多行输入:                              │
                │    - 括号/花括号未闭合时自动续行          │
                └─────────────────────────────────────────┘""");
    }

    private static void evalAndPrint(String source, ParseContext parseContext,
                                      EvalContext evalContext,
                                       DynamicClassGenerator codegen) {
        try {
            Lexer lexer = new Lexer(source, "<repl>");
            Parser parser = new Parser(lexer.tokenize(), parseContext, "<repl>");
            List<ASTNode> nodes = parser.parse();

            // 注册类声明 + 生成类（带执行器委托）
            for (ASTNode node : nodes) {
                if (node instanceof ClassDeclarationNode classDecl) {
                    parseContext.declareClass(classDecl);
                    try {
                        codegen.generate(classDecl);
                    } catch (Exception e) {
                        // codegen skipped: {e.getMessage()}
                    }
                }
            }

            // 设置执行器上下文
            CustomClassExecutor.setContext(evalContext, parseContext);

            Evaluator evaluator = new Evaluator(evalContext, parseContext);
            List<Value> results = evaluator.evaluateAll(nodes);

            CustomClassExecutor.clearContext();

            for (Value v : results) {
                if (!(v instanceof Value.VoidValue)) {
                    System.out.println("=> " + formatValue(v));
                }
            }
            if (results.isEmpty()) {
                System.out.println("=> <void>");
            }

        } catch (CythavaParseException e) {
            System.out.println("  解析错误: " + e.getMessage());
            if (e.getLocation() != null) {
                System.out.println("    位置: " + e.getLocation());
            }
        } catch (EvalException e) {
            System.out.println("  执行错误: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  内部错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    private static String formatValue(Value v) {
        return v.toString();
    }
}
