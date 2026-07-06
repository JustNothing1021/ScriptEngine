package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.BlockNode;
import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Cythava 统一解析器（门面模式）。
 * <p>
 * 将 {@code List<Token>} 转换为 {@code List<ASTNode>}，
 * 内部按优先级尝试 DeclParser → StmtParser → ExprParser 三级 fallback。
 * </p>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>REPL：用户输入可能是声明、语句或表达式</li>
 *   <li>Evaluator 入口：统一接收 token 流输出 AST</li>
 *   <li>脚本文件解析：整段代码的统一入口</li>
 * </ul>
 */
public class Parser extends BaseParser {

    public Parser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 公共入口 ====================

    /**
     * 解析输入为 AST 节点列表。
     * <p>
     * 三级 fallback 策略：
     * <ol>
     *   <li>DeclParser — 顶层声明（class, function, import, using 等）</li>
     *   <li>StmtParser — 语句（if/while/for/return/var 声明等）</li>
     *   <li>ExprParser — 表达式（算术、方法调用、lambda 等）</li>
     * </ol>
     *
     * @return 解析得到的 AST 节点列表（至少包含一个节点）
     */
    public List<ASTNode> parse() throws CythavaParseException {
        DeclParser declParser = new DeclParser(tokens, context, fileName);
        StmtParser stmtParser = new StmtParser(tokens, context, fileName);
        List<ASTNode> result = new ArrayList<>();
        while (!(isAtEnd() || peek().type() == TokenType.EOF)) {
            try {
                ASTNode node = declParser.parseNextCompilationUnit();
                if (node == null) break;
                result.add(node);
                // 立即注册 class 声明，使同一次 parse 中后续语句可引用
                if (node instanceof com.justnothing.engine.ast.nodes.ClassDeclarationNode cd) {
                    context.declareClass(cd);
                }
                // 立即注册 import，使同一次 parse 中后续语句可引用导入的类
                if (node instanceof com.justnothing.engine.ast.nodes.ImportNode importNode) {
                    String importStr = importNode.getPackageName();
                    if (importStr != null && importStr.startsWith("import ")) {
                        context.addImport(importStr.substring("import ".length()).trim());
                    }
                }
                stmtParser.setPosition(declParser.getPosition());
                this.setPosition(declParser.getPosition());
                continue;
            } catch (CythavaParseException e) {
                if (e.isSemanticError() || isSemanticError(e)) throw e;
            }
            result.add(stmtParser.parseNextStatement());
            declParser.setPosition(stmtParser.getPosition());
            this.setPosition(stmtParser.getPosition());
        }
        return result;
    }

    /**
     * 判断异常是否为应直接传播的错误（不参与 fallback）。
     * <p>
     * 包含两类：
     * <ul>
     *   <li><b>语义错误</b>：变量已存在、类型不匹配、未知类型等 — 代码本身有问题</li>
     *   <li><b>结构性语法错误</b>：解析器已经深入到语句/声明内部才失败的错误，
     *       说明输入确实以该结构开头（如 new 表达式、字段访问），只是内部语法不对</li>
     * </ul>
     */
    private static boolean isSemanticError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        // 语义错误关键词
        boolean semantic = msg.contains("already declared")
                || msg.contains("Type mismatch") || msg.contains("type mismatch")
                || msg.contains("Unknown type")
                || msg.contains("cannot resolve class")
                || msg.contains("forward references")
                || msg.contains("must be initialized")
                || msg.contains("Cannot find symbol")       // 标识符/符号未找到（解析器已确认结构正确）
                || msg.contains("symbol not found")        // 同上（变体）
                || msg.contains("declares return type")    // 方法返回值类型不匹配
                || msg.contains("declares void");          // void 方法返回了值
        // 结构性语法错误：解析器已确认输入结构但内部语法不对
        // 注意：不含泛型的 "Expected ..."（DeclParser/StmtParser 入口处的正常 fallback）
        boolean structuralSyntax = msg.contains("requires ")    // "requires '()' before it"
                || msg.startsWith("No such field")             // 字段不存在（已确认是字段访问）
                || msg.startsWith("Cannot resolve field")     // 字段无法解析
                || msg.contains("member declaration");       // 匿名类体成员语法错误
        return semantic || structuralSyntax || e instanceof IllegalStateException;
    }

    /**
     * 解析输入为单个 AST 节点。
     * <p>
     * 如果产生多个声明，包装为一个 BlockNode。
     *
     * @return 单个 AST 节点
     */
    public ASTNode parseSingleNode() throws CythavaParseException {
        List<ASTNode> nodes = parse();
        if (nodes.size() == 1) {
            return nodes.get(0);
        }
        return (BlockNode) new BlockNode.Builder().statements(nodes).location(createLocation()).build();
    }

    // ==================== 便捷工厂方法 ====================

    /**
     * 从源代码字符串直接解析为 AST 节点列表。
     *
     * @param source  源代码
     * @param context 解析上下文
     * @return AST 节点列表
     * @throws CythavaParseException 如果解析失败
     */
    public static List<ASTNode> parseSource(String source, ParseContext context) throws CythavaParseException {
        Lexer lexer = new Lexer(source, "<source>");
        Parser parser = new Parser(lexer.tokenize(), context, "<source>");
        return parser.parse();
    }
}
