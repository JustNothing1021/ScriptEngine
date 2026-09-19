package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.NameRef;
import com.justnothing.engine.ast.nodes.BlockNode;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.ast.nodes.ImportNode;
import com.justnothing.engine.ast.nodes.NameRefNode;
import com.justnothing.engine.exception.ErrorCode;
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
        // 拆分探针：标记解析阶段，使"关掉解析期语义"不波及运行期调用（见 ParseContext 的探针说明）
        ParseContext.enterParsePhase();
        try {
            return parseInternal();
        } finally {
            ParseContext.exitParsePhase();
        }
    }

    /** {@link #parse()} 的实际实现（拆出来只是为了给解析阶段加上 try/finally 标记）。 */
    private List<ASTNode> parseInternal() throws CythavaParseException {
        context.clearParseErrors();
        context.clearNameRefs();
        // 上一次解析若在嵌套泛型中途失败，可能留下待还的 '>'（见 ParseContext#consumePendingAngleBracket）
        context.clearPendingAngleBrackets();
        DeclParser declParser = new DeclParser(tokens, context, fileName);
        StmtParser stmtParser = new StmtParser(tokens, context, fileName);
        List<ASTNode> result = new ArrayList<>();
        while (!(isAtEnd() || peek().type() == TokenType.EOF)) {
            int startPos = position;
            TokenType startType = peek().type();
            try {
                ASTNode node = declParser.parseNextCompilationUnit();
                if (node == null) break;
                result.add(node);
                // 立即注册 class 声明，使同一次 parse 中后续语句可引用
                if (node instanceof ClassDeclarationNode cd) {
                    context.declareClass(cd);
                }
                // 立即注册 import，使同一次 parse 中后续语句可引用导入的类
                if (node instanceof ImportNode importNode) {
                    String importStr = importNode.getPackageName();
                    if (importStr != null && importStr.startsWith("import ")) {
                        context.addImport(importStr.substring("import ".length()).trim());
                    }
                }
                syncFrom(declParser, stmtParser);
                continue;
            } catch (CythavaParseException e) {
                // 放弃这段声明解析：中途若在嵌套泛型里拆过 '>'，待还的部分不该留给后面
                context.clearPendingAngleBrackets();
                // 两种情况说明输入确实是声明（而不是"这段输入不是声明"）：
                //   1) 声明解析已消费 token（parseFunctionOrVariable 内部已转交语句解析）
                //   2) 首 token 明确开启一个声明（class/interface/enum/import/...）
                // 此时直接报告错误并恢复，不再退回语句解析 —— 否则会重复解析该输入，
                // 报出"重复声明"之类的派生错误，掩盖真正的错误
                int declPos = declParser.getPosition();
                if (!e.isSemanticError() && declPos == startPos && !startsDeclaration(startType)) {
                    // 输入不是声明：交给语句解析器，从声明解析停下的位置继续
                    this.setPosition(declPos);
                    syncFrom(this, declParser, stmtParser);
                } else {
                    context.reportError(e);
                    this.setPosition(declPos);
                    recoverFrom(declPos, declParser, stmtParser);
                    continue;
                }
            }
            try {
                result.add(stmtParser.parseNextStatement());
                syncFrom(stmtParser, declParser);
            } catch (CythavaParseException e) {
                // 语句级错误恢复：记录错误后跳到下一个同步点，继续解析后续语句，
                // 使一次解析能报告全部错误（而不是只报第一个）
                context.clearPendingAngleBrackets();
                context.reportError(e);
                int stmtPos = stmtParser.getPosition();
                this.setPosition(stmtPos);
                recoverFrom(stmtPos, declParser, stmtParser);
            }
        }
        resolvePendingNameRefs();
        throwIfParseErrors();
        return result;
    }

    /**
     * link 前的名字解析：把解析期挂起的未定名引用（{@link NameRefNode}）统一判定身份。
     * <p>
     * 解析期遇到裸名字时不去查类，只登记（见 {@code PostfixExpressionParser} 的名字解析阶梯）。
     * 这里在整棵 AST 建好、类声明与符号表都齐了之后统一判定，顺带解决"类名声明在引用之后"
     * 这类解析期无从回答的问题。严格模式下仍判不出来的名字，在这里报"找不到符号" ——
     * 报错位置与口径与解析期一致，只是时机挪到了收集完整信息之后。
     * </p>
     */
    private void resolvePendingNameRefs() {
        for (NameRefNode node : context.getNameRefs()) {
            NameRef ref = node.getRef();
            if (ref.isResolved()) continue;
            String name = ref.name();

            if (context.isKnownVariable(name)) {
                ref.resolveAs(NameRef.Kind.VARIABLE);
            } else if (context.shouldResolveAsField(name)) {
                ref.resolveAs(NameRef.Kind.FIELD);
            } else if (context.isBuiltinFunction(name)) {
                ref.resolveAs(NameRef.Kind.BUILTIN);
            } else {
                Class<?> resolved = context.resolveClass(name);
                if (resolved != null) {
                    ref.resolveAsClass(resolved);
                    context.setType(node, JType.of(resolved));
                } else if (context.isStrictMode()) {
                    context.reportError(new CythavaParseException(
                            "Cannot find symbol: '" + name + "'",
                            node.getLocation(), ErrorCode.SCOPE_VARIABLE_NOT_FOUND, true));
                }
            }
        }
    }

    /** 把 source 的位置同步到本解析器与其余解析器。 */
    private void syncFrom(BaseParser source, BaseParser... others) {
        this.setPosition(source.getPosition());
        for (BaseParser parser : others) {
            parser.setPosition(position);
        }
    }

    /** 跳到下一个语句同步点，并把所有解析器位置同步到恢复后的位置（保证至少前进一个 token）。 */
    private void recoverFrom(int failPos, BaseParser... parsers) {
        synchronizeToStatementBoundary();
        if (position == failPos) {
            advance();
        }
        for (BaseParser parser : parsers) {
            parser.setPosition(position);
        }
    }

    /** 该 token 是否明确开启一个声明（用于决定出错时是否回退到语句解析）。 */
    private static boolean startsDeclaration(TokenType type) {
        return type == TokenType.KEYWORD_CLASS
                || type == TokenType.KEYWORD_INTERFACE
                || type == TokenType.KEYWORD_ENUM
                || type == TokenType.KEYWORD_IMPORT
                || type == TokenType.KEYWORD_PACKAGE
                || type == TokenType.KEYWORD_USING;
    }

    /**
     * 存在已恢复的错误时抛出聚合异常，一次列出全部错误。
     *
     * @throws CythavaParseException 收集到的第一个错误的聚合形式
     */
    private void throwIfParseErrors() throws CythavaParseException {
        List<CythavaParseException> errors = context.getParseErrors();
        if (errors.isEmpty()) return;
        if (errors.size() == 1) throw errors.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(errors.size()).append(" error(s):");
        for (CythavaParseException error : errors) {
            sb.append("\n  ").append(error.getMessage());
        }
        // 每条错误自带 "(at line X, column Y)"，聚合信息不再附加位置后缀
        throw new CythavaParseException(sb.toString(), null,
                errors.get(0).getErrorCode(), true);
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
