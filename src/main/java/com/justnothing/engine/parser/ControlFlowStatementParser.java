package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Cythava 语句解析层（控制流与跳转）。
 * <p>
 * 解析以下类型的 Java/Cythava 语句：
 * <ul>
 *   <li>控制流: if/else, while, do-while, for, for-each</li>
 *   <li>跳转: return, break, continue, throw</li>
 *   <li>异常处理: try-catch-finally</li>
 *   <li>switch 语句</li>
 * </ul>
 * </p>
 *
 * @see BaseParser
 */
abstract class ControlFlowStatementParser extends LocalVariableParser {

    protected ControlFlowStatementParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 控制流语句 ====================

    /** if / else if / else */
    protected ASTNode parseIfStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'if'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after if condition");

        try {
            return buildIfStatement(location, condition);
        } finally {
            undeclarePatternVariables(condition);
        }
    }

    /** 解析 if 的两个分支（条件已解析完毕）。 */
    private ASTNode buildIfStatement(SourceLocation location, ASTNode condition)
            throws CythavaParseException {
        ASTNode thenBranch = parseStatementOrBlock();

        ASTNode elseBranch = null;
        if (match(TokenType.KEYWORD_ELSE)) {
            elseBranch = parseStatementOrBlock();
        }

        return new IfNode.Builder()
                .condition(condition)
                .thenBlock(thenBranch)
                .elseBlock(elseBranch)
                .location(location).build();
    }

    /** while 循环 */
    protected ASTNode parseWhileStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'while'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after while condition");

        try {
            return new WhileNode.Builder().condition(condition).body(parseStatementOrBlock())
                    .location(location).build();
        } finally {
            undeclarePatternVariables(condition);
        }
    }

    /**
     * 撤销条件表达式中 {@code instanceof} 模式变量在解析期的临时声明
     * （{@link ExprParser} 解析到 {@code instanceof Type name} 时会立刻声明，
     * 这里在语句解析结束后收回，使模式变量不泄漏到语句之外）。
     */
    private void undeclarePatternVariables(ASTNode condition) {
        for (InstanceofNode pattern : collectPatternVariables(condition, new ArrayList<>())) {
            context.undeclareVariable(pattern.getPatternVariable());
        }
    }

    /** 收集条件表达式中所有带模式变量的 {@code instanceof}（递归处理 {@code &&}/{@code ||}/{@code !}）。 */
    private static List<InstanceofNode> collectPatternVariables(ASTNode node, List<InstanceofNode> out) {
        if (node instanceof InstanceofNode io) {
            if (io.getPatternVariable() != null) {
                out.add(io);
            }
        } else if (node instanceof BinaryOpNode binary) {
            collectPatternVariables(binary.getLeft(), out);
            collectPatternVariables(binary.getRight(), out);
        } else if (node instanceof UnaryOpNode unary) {
            collectPatternVariables(unary.getOperand(), out);
        }
        return out;
    }

    /** do-while 循环 */
    protected ASTNode parseDoWhileStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        ASTNode body = parseStatementOrBlock();

        consumeOrSemanticError(TokenType.KEYWORD_WHILE, "Expected 'while' after do-while body");
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'while'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after do-while condition");
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after do-while condition");

        return new DoWhileNode.Builder()
                .body(body)
                .condition(condition)
                .location(location).build();
    }

    /** for 循环（传统 for 和 for-each） */
    protected ASTNode parseForStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'for'");

        // 判断是传统 for 还是 for-each
        savePosition();

        // 尝试 for-each: type name : iterable 或 name : iterable
        if (tryParseForEach(location) != null) {
            releasePosition();
            // tryParseForEach 内部已消费了 )
            return getLastParsedNode();
        }

        restorePosition();

        // 传统 for 循环
        return parseTraditionalFor(location);
    }

    /**
     * 尝试解析 for-each 语句。
     *
     * @return ForEachNode 如果成功；null 如果不是 for-each
     */
    private ASTNode tryParseForEach(SourceLocation location) throws CythavaParseException {
        // ★ 策略：先用 TypeParser 尝试解析类型，正确处理泛型（如 Map.Entry<String, Integer>）
        // 回退方案：纯标识符模式 (auto i : iterable)

        int savedPos = position;
        String itemName = null;
        GenericType itemTypeGT = null;

        // 策略 0: 类型推断标记 var/auto（for (var i : …) / for (auto i : …)）
        //   注意 auto 是关键字，而 var 未列入词法器关键字表、可能以普通标识符形式出现，故按文本判断
        if (isInferMarker(peek())) {
            advance(); // 消费 var/auto
            itemName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name after var/auto").text();
            if (!match(TokenType.OPERATOR_COLON)) {
                position = savedPos; // 不是 for-each（可能是 var x = expr; 等其他语句）
                return null;
            }
        }

        // 策略 1: 尝试解析为 "Type varName : expr"（有显式类型声明）
        if (itemName == null && isTypeStart(peek())) {
            try {
                TypeParser typeParser = new TypeParser(tokens, context, fileName);
                typeParser.setPosition(position);
                itemTypeGT = typeParser.parseType();
                int afterTypePos = typeParser.getPosition();

                // 类型后面应该是变量名（标识符）
                if (afterTypePos < tokens.size() && tokens.get(afterTypePos).type() == TokenType.IDENTIFIER) {
                    itemName = tokens.get(afterTypePos).text();
                    int afterNamePos = afterTypePos + 1;

                    // 变量名后面应该是 :
                    if (afterNamePos < tokens.size() && tokens.get(afterNamePos).type() == TokenType.OPERATOR_COLON) {
                        position = afterNamePos + 1; // 跳过冒号，匹配成功
                    } else {
                        itemName = null;
                        itemTypeGT = null; // 不是 for-each 模式
                    }
                } else {
                    itemTypeGT = null; // 类型后无标识符
                }
            } catch (CythavaParseException e) {
                itemTypeGT = null; // TypeParser 失败，回退
            }
        }

        // 策略 2: 回退为 "varName : expr"（纯标识符，无显式类型）
        if (itemName == null && check(TokenType.IDENTIFIER)) {
            position = savedPos; // ★ 直接重置位置，不用 restorePosition（避免栈不平衡）
            itemName = advance().text();
            if (!match(TokenType.OPERATOR_COLON)) {
                position = savedPos; // 不是 for-each，回退到原始位置
                return null;
            }
        } else if (itemName == null) {
            position = savedPos; // 两个策略都失败，确保位置不变
            return null;
        }

        // 解析集合表达式和循环体
        ASTNode collection = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after for-each expression");

        // 将迭代变量注册到当前作用域，使循环体内可引用

        // 从 GenericType 提取 Class<?>（保持 ForEachNode.itemType 向后兼容）
        Class<?> itemType = (itemTypeGT != null) ? itemTypeGT.getRawType() : null;

        context.enterScope(ParseContext.ScopeKind.BLOCK);
        ASTNode body;
        try {
            if (itemTypeGT != null) {
                context.declareVariable(itemName, false, itemTypeGT);
            } else {
                context.declareVariable(itemName);
            }
            body = parseStatementOrBlock();
        } finally {
            // 解析失败时也要退出作用域，否则作用域栈不平衡会污染后续解析
            context.exitScope();
        }



        ForEachNode node = (ForEachNode) new ForEachNode.Builder()
                .itemType(itemType)
                .itemName(itemName)
                .collection(collection)
                .body(body)
                .location(location)
                .build();
        this.lastParsedNode = node;

        return node;
    }

    /** 传统 for 循环: for(init; cond; update) body */
    private ForNode parseTraditionalFor(SourceLocation location) throws CythavaParseException {
        // for 的初始化声明（for (int i = 0; …)）只在循环内可见，必须用独立作用域，
        // 否则循环结束后变量会泄漏到外层，导致后续同名声明报 "already declared"，
        // 而该错误又会被初始化部分的回溯逻辑吞掉，最终报出误导性的 "Expected ';'"。
        context.enterScope(ParseContext.ScopeKind.BLOCK);
        try {
            // 初始化部分：可能是表达式或变量声明（Java 允许 for(int i=0; ...)）
            ASTNode initialization = null;
            boolean initConsumedSemicolon = false; // 变量声明会自带分号
            if (!check(TokenType.DELIMITER_SEMICOLON)) {
                // 前瞻判断：如果以类型关键字开头，尝试解析为变量声明
                if (isPrimitiveTypeKeyword(peek().type()) || peek().type() == TokenType.IDENTIFIER
                        || peek().type() == TokenType.KEYWORD_AUTO || peek().type() == TokenType.KEYWORD_VAR) {
                    savePosition();
                    try {
                        initialization = parseLocalVariableDeclaration(null, false);
                        initConsumedSemicolon = true; // 变量声明已消费末尾分号
                    } catch (CythavaParseException | IllegalStateException e) {
                        // 不是变量声明，回退并作为表达式解析
                        restorePosition();
                        initialization = parseExpr();
                    }
                } else {
                    initialization = parseExpr();
                }
            }
            if (!initConsumedSemicolon) {
                consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after for initialization");
            }

            // 条件部分（可能为空）
            ASTNode condition = null;
            if (!check(TokenType.DELIMITER_SEMICOLON)) {
                condition = parseExpr();
            }
            consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after for condition");

            // 更新部分（可能为空）
            ASTNode update = null;
            if (!check(TokenType.DELIMITER_RIGHT_PAREN)) {
                update = parseExpr();
                // 逗号分隔的多个更新表达式：收集为顺序执行块
                List<ASTNode> updates = new ArrayList<>();
                updates.add(update);
                while (match(TokenType.DELIMITER_COMMA)) {
                    updates.add(parseExpr());
                }
                if (updates.size() > 1) {
                    update = new BlockNode.Builder().statements(updates).unscoped().location(createLocation()).build();
                }
            }
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after for clauses");

            ASTNode body = parseStatementOrBlock();

            return (ForNode) new ForNode.Builder()
                    .initialization(initialization)
                    .condition(condition)
                    .update(update)
                    .body(body)
                    .location(location).build();
        } finally {
            context.exitScope();
        }
    }

    // ==================== 跳转语句 ====================

    /** return [expression] ; */
    protected ReturnNode parseReturnStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        ASTNode value = null;
        if (!check(TokenType.DELIMITER_SEMICOLON)) {
            value = parseExpr();
        }
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after return value");

        return (ReturnNode) new ReturnNode.Builder().value(value).location(location).build();
    }

    /** break [label] ; */
    protected BreakNode parseBreakStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        String label = null;
        if (check(TokenType.IDENTIFIER)) {
            label = advance().text();
        }
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after break");

        if (label != null) {
            return (BreakNode) new BreakNode.Builder().label(label).location(location).build();
        }
        return (BreakNode) new BreakNode.Builder().location(location).build();
    }

    /** continue ; */
    protected ContinueNode parseContinueStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after continue");
        return (ContinueNode) new ContinueNode.Builder().location(location).build();
    }

    /** throw expression ; */
    protected ThrowNode parseThrowStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        ASTNode expression = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after throw expression");
        return (ThrowNode) new ThrowNode.Builder().expression(expression).location(location).build();
    }

    /** assert condition [: message] ; */
    protected AssertNode parseAssertStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        ASTNode condition = parseExpr();
        ASTNode message = null;
        if (match(TokenType.OPERATOR_COLON)) {
            message = parseExpr();
        }
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after assert statement");

        return new AssertNode.Builder()
                .condition(condition).message(message).location(location).build();
    }

    /**
     * {@code static_assert(condition [, message]);} —— 编译期断言。
     * <p>
     * 条件在解析期立即判定，通过后不产生 AST 节点（返回空语句占位），因此运行期零开销。
     * 语法糖的判定逻辑见 {@link #verifyStaticAssert}。
     * </p>
     */
    protected ASTNode parseStaticAssertStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'static_assert'");
        ASTNode condition = parseExpr();
        ASTNode message = null;
        if (match(TokenType.DELIMITER_COMMA)) {
            message = parseExpr();
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after static_assert arguments");
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after static_assert");

        verifyStaticAssert(condition, message, location);
        return new LiteralNode.Builder().value(null).type(void.class).location(location).build();
    }

    // ==================== 异常处理 ====================

    /** synchronized (lock) { ... } */
    protected ASTNode parseSynchronizedStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'synchronized'");
        ASTNode lock = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after synchronized lock");
        ASTNode body = parseStatementOrBlock();

        return new SynchronizedNode.Builder().lock(lock).body(body).location(location).build();
    }

    /** try [-catch]* [-finally] */
    protected TryNode parseTryStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        // try 块
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' after 'try'");
        BlockNode tryBlock = parseBlock();

        // catch 子句
        List<CatchClause> catchClauses = new ArrayList<>();
        while (match(TokenType.KEYWORD_CATCH)) {
            catchClauses.add(parseCatchClause());
        }

        // finally 块
        BlockNode finallyBlock = null;
        if (match(TokenType.KEYWORD_FINALLY)) {
            consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' after 'finally'");
            finallyBlock = parseBlock();
        }

        if (catchClauses.isEmpty() && finallyBlock == null) {
            throw error("try must have at least one catch or finally clause",
                    ErrorCode.PARSE_INVALID_SYNTAX);
        }

        return (TryNode) new TryNode.Builder().resources(null).tryBlock(tryBlock).catchClauses(catchClauses).finallyBlock(finallyBlock).location(location).build();
    }

    /** catch (Type varName) { ... } */
    private CatchClause parseCatchClause() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'catch'");

        // 异常类型（支持多异常: Type1 | Type2）
        List<Class<?>> exceptionTypes = new ArrayList<>();
        do {
            exceptionTypes.add(parseTypeName());
        } while (match(TokenType.OPERATOR_BITWISE_OR));

        Token varToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected exception variable name in catch");
        String variableName = varToken.text();

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after catch clause");
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before catch body");
        BlockNode body = parseBlock();

        return new CatchClause.Builder()
                .exceptionTypes(exceptionTypes).variableName(variableName)
                .body(body).location(location).build();
    }

    // ==================== Switch 语句 ====================

    /** switch (expr) { case ... } */
    protected SwitchNode parseSwitchStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'switch'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after switch condition");
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_BRACE, "Expected '{' before switch cases");

        List<CaseNode> cases = new ArrayList<>();
        ASTNode defaultCase = null;

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            if (match(TokenType.KEYWORD_DEFAULT)) {
                // 支持 Java 风格 ':' 和现代箭头 '->'
                boolean isArrowStyle;
                if (match(TokenType.DELIMITER_ARROW)) {
                    isArrowStyle = true;
                } else if (match(TokenType.OPERATOR_COLON)) {
                    isArrowStyle = false;
                } else {
                    throw error("Expected '->' or ':' after 'default'", ErrorCode.PARSE_INVALID_SYNTAX);
                }
                defaultCase = parseSwitchCaseBody(isArrowStyle);
                match(TokenType.DELIMITER_SEMICOLON); // 可选（箭头风格）
            } else if (match(TokenType.KEYWORD_CASE)) {
                cases.add(parseCaseClause());
            } else {
                // 跳过意外 token
                advance();
            }
        }

        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after switch block");

        SwitchNode node = (SwitchNode) new SwitchNode.Builder().expression(condition).cases(cases).defaultCase(defaultCase).location(location).build();
        // 标注 switch 语句/表达式的类型：各分支的 LCM
        annotateSwitchType(node, cases, defaultCase);
        return node;
    }

    /** 为 SwitchNode 标注 LCM 类型（与 ExprParser.computeSwitchLCM 逻辑一致）。 */
    private void annotateSwitchType(SwitchNode node, List<CaseNode> cases, ASTNode defaultCase) {
        List<Class<?>> types = new ArrayList<>();
        for (CaseNode c : cases) {
            if (c.getStatements().isEmpty()) continue; // 空 case 体（case 1:）无类型可推断
            ASTNode body = c.getStatements().get(0);
            JType t = context.getType(body);
            if (t != null) types.add(t.getRawType());
            else if (body instanceof LiteralNode lit && lit.getType() != null)
                types.add(lit.getType());
        }
        if (defaultCase != null) {
            JType dt = context.getType(defaultCase);
            if (dt != null) types.add(dt.getRawType());
            else if (defaultCase instanceof LiteralNode lit && lit.getType() != null)
                types.add(lit.getType());
        }
        if (!types.isEmpty()) {
            // 所有类型相同 → 该类型
            Class<?> first = types.get(0);
            boolean allSame = true;
            for (Class<?> t : types) { if (!t.equals(first)) { allSame = false; break; } }
            if (allSame) { annotate(node, first); return; }

            // 数值类型提升链（索引越大 = 类型越宽）
            Class<?>[] NUMERIC_PROMOTION_CHAIN = {
                    Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE
            };
            Function<Class<?>, Integer> numericRank = t -> {
                for (int i = 0; i < NUMERIC_PROMOTION_CHAIN.length; i++) {
                    if (NUMERIC_PROMOTION_CHAIN[i].equals(t)) return i;
                }
                return -1;
            };

            boolean allNumeric = true;
            for (Class<?> t : types) {
                if (numericRank.apply(t) < 0) { allNumeric = false; break; }
            }
            if (allNumeric) {
                int maxRank = -1;
                Class<?> widest = Integer.TYPE;
                for (Class<?> t : types) {
                    int r = numericRank.apply(t);
                    if (r > maxRank) { maxRank = r; widest = t; }
                }
                annotate(node, widest); return;
            }

            annotate(node, Object.class);  // 混合非数值类型 → Object
        }
    }

    /** 便捷方法：为节点标注 Class 类型。 */
    protected void annotate(ASTNode node, Class<?> clazz) {
        if (clazz != null) context.setType(node, JType.of(clazz));
    }

    /** case value [, value]* (-> | :) statements */
    private CaseNode parseCaseClause() throws CythavaParseException {
        SourceLocation location = createLocation();

        // case 后面的值（支持多值: case 1, 2, 3 -> ...）
        List<ASTNode> values = new ArrayList<>();
        do {
            values.add(parseExpr());
        } while (match(TokenType.DELIMITER_COMMA));

        // 支持 Java 风格 ':' 和现代箭头 '->'
        boolean isArrowStyle;
        if (match(TokenType.DELIMITER_ARROW)) {
            isArrowStyle = true;
        } else if (match(TokenType.OPERATOR_COLON)) {
            isArrowStyle = false;
        } else {
            throw error("Expected '->' or ':' after case values", ErrorCode.PARSE_INVALID_SYNTAX);
        }
        ASTNode caseBody = parseSwitchCaseBody(isArrowStyle);
        match(TokenType.DELIMITER_SEMICOLON); // 可选（箭头风格）

        // CaseNode 构造器接受单个 value + statements 列表
        // 多值 case: 用第一个值作为 case value，body 作为语句列表
        List<ASTNode> statements = new ArrayList<>();
        if (caseBody instanceof BlockNode block) {
            statements.addAll(block.getStatements());
        } else if (caseBody != null) {
            statements.add(caseBody);
        }

        // 多值 case (case 1, 2, 3 ->): 所有值都传给 CaseNode，由 Evaluator 命中任一值即执行
        return (CaseNode) new CaseNode.Builder()
                .values(values).statements(statements).arrowStyle(isArrowStyle)
                .location(location).build();
    }

    /** 解析 case/default 后面的主体（可能是块或单个语句） */
    private ASTNode parseSwitchCaseBody(boolean isArrowStyle) throws CythavaParseException {
        // 箭头风格: case -> { block } 或 case -> statement
        if (isArrowStyle) {
            if (match(TokenType.DELIMITER_LEFT_BRACE)) {
                position--; // 回退，让 parseBlock 处理
                return parseBlock();
            }
            // 单个表达式/语句
            return parseExpr();
        }

        // Java 冒号风格: case : statements... (直到 break/next case/default/})
        // 收集语句直到遇到 break、case、default 或 }；break 作为语句保留以支持 fall-through 终止
        List<ASTNode> statements = new ArrayList<>();
        while (!isAtEnd()
                && !check(TokenType.KEYWORD_CASE)
                && !check(TokenType.KEYWORD_DEFAULT)
                && !check(TokenType.DELIMITER_RIGHT_BRACE)) {
            statements.add(parseStatementInternal());
            if (statements.get(statements.size() - 1) instanceof BreakNode) break;
        }
        return new BlockNode.Builder().statements(statements).location(createLocation()).build();
    }

    // ==================== 语句或块 ====================

    /** 解析一条语句或一个 {} 块。 */
    protected ASTNode parseStatementOrBlock() throws CythavaParseException {
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            advance();
            return parseBlock();
        }
        return parseStatementInternal();
    }

    /** 用于暂存 tryParseForEach 返回值的临时字段。 */
    private ASTNode lastParsedNode;

    private ASTNode getLastParsedNode() {
        return lastParsedNode;
    }
}
