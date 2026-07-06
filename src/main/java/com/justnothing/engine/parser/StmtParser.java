package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.GenericType;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;
import com.justnothing.engine.parser.ParseContext.VariableSymbol;
import com.justnothing.engine.util.MethodResolver;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cythava 语句解析器。
 * <p>
 * 解析所有类型的 Java/Cythava 语句，包括：
 * <ul>
 *   <li>表达式语句（赋值、方法调用等）</li>
 *   <li>变量/字段声明</li>
 *   <li>控制流: if/else, while, do-while, for, for-each</li>
 *   <li>跳转: return, break, continue, throw</li>
 *   <li>异常处理: try-catch-finally</li>
 *   <li>switch 语句</li>
 *   <li>块语句、空语句、标签语句</li>
 *   <li>Cythava 扩展: async/await</li>
 * </ul>
 * </p>
 *
 * @see ExprParser
 */
public class StmtParser extends BaseParser {

    /**
     * 构造器。
     *
     * @param tokens   token 流
     * @param context  解析上下文
     * @param fileName 源文件名
     */
    public StmtParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    // ==================== 入口 ====================

    /**
     * 解析单条语句。
     *
     * @return AST 节点表示的语句
     * @throws CythavaParseException 语法错误
     */
    public ASTNode parseNextStatement() throws CythavaParseException {
        return parseStatementInternal();
    }

    /**
     * 解析表达式（从当前位置创建新的 ExprParser 避免状态污染）。
     */
    private ASTNode parseExpr() throws CythavaParseException {
        ExprParser freshParser = new ExprParser(tokens, context, fileName);
        freshParser.setPosition(position);
        ASTNode result = freshParser.parseNextExpression();
        position = freshParser.getPosition();
        return result;
    }

    /**
     * 解析语句列表（直到遇到 } 或 EOF）。
     *
     * @return 语句节点列表
     * @throws CythavaParseException 语法错误
     */
    public List<ASTNode> parseBlockBody() throws CythavaParseException {
        List<ASTNode> statements = new ArrayList<>();
        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            // 跳过可能的空语句（连续分号）
            if (match(TokenType.DELIMITER_SEMICOLON)) {
                continue;
            }
            statements.add(parseStatementInternal());
        }
        return statements;
    }

    // ==================== 内部分发 ====================

    private ASTNode parseStatementInternal() throws CythavaParseException {
        // --- 关键字开头 ---
        if (match(TokenType.KEYWORD_IF)) {
            return parseIfStatement();
        }
        if (match(TokenType.KEYWORD_WHILE)) {
            return parseWhileStatement();
        }
        if (match(TokenType.KEYWORD_DO)) {
            return parseDoWhileStatement();
        }
        if (match(TokenType.KEYWORD_FOR)) {
            return parseForStatement();
        }
        if (match(TokenType.KEYWORD_RETURN)) {
            return parseReturnStatement();
        }
        if (match(TokenType.KEYWORD_BREAK)) {
            return parseBreakStatement();
        }
        if (match(TokenType.KEYWORD_CONTINUE)) {
            return parseContinueStatement();
        }
        if (match(TokenType.KEYWORD_THROW)) {
            return parseThrowStatement();
        }
        if (match(TokenType.KEYWORD_TRY)) {
            return parseTryStatement();
        }
        if (match(TokenType.KEYWORD_SWITCH)) {
            return parseSwitchStatement();
        }
        if (match(TokenType.KEYWORD_ASYNC)) {
            return parseAsyncStatement();
        }
        if (match(TokenType.KEYWORD_DELETE)) {
            return parseDeleteStatement();
        }
        if (match(TokenType.KEYWORD_AUTO)) {
            return parseAutoVariableDeclaration();
        }
        if (match(TokenType.KEYWORD_VAR)) {
            // var 等同于 auto：类型推断
            return parseAutoVariableDeclaration();
        }
        if (check(TokenType.IDENTIFIER) && Keywords.FUNCTION.equals(peek().text())) {
            advance();
            return parseFunctionDefinition();
        }

        // --- 分隔符开头 ---
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            // 先尝试解析为块语句，若失败则回退让表达式解析器处理（map/array 字面量）
            savePosition();
            advance();
            try {
                ASTNode block = parseBlockStatement();
                releasePosition();
                return block;
            } catch (CythavaParseException e) {
                if (e.isSemanticError()) throw e;
                restorePosition();
            }
        }

        // 空语句 '';'
        if (match(TokenType.DELIMITER_SEMICOLON)) {
            return new LiteralNode.Builder().value(null).type(void.class).location(createLocation()).build();
        }

        // --- 修饰符关键字开头: final int x 等 ---
        if (isModifierKeyword(peek().text())) {
            return parseModifierLocalVariableDeclaration();
        }

        // --- 注解开头: @Annotation [final] Type varName ... ---
        if (check(TokenType.DELIMITER_AT)) {
            return parseAnnotatedLocalVariableDeclaration();
        }

        // --- 标识符开头: 可能是标签、局部变量声明或表达式 ---
        if (check(TokenType.IDENTIFIER)) {
            return parseIdentifierStartStatement();
        }

        // --- 类型关键字开头: 可能是变量声明 ---
        if (isPrimitiveTypeKeyword(peek().type())) {
            return parseLocalVariableDeclaration(null, false);
        }

        // --- 其他: 当作表达式语句 ---
        return parseExpressionStatement();
    }

    // ==================== 控制流语句 ====================

    /** if / else if / else */
    private ASTNode parseIfStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'if'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after if condition");

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
    private ASTNode parseWhileStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after 'while'");
        ASTNode condition = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after while condition");

        ASTNode body = parseStatementOrBlock();

        return new WhileNode.Builder().condition(condition).body(body).location(location).build();
    }

    /** do-while 循环 */
    private ASTNode parseDoWhileStatement() throws CythavaParseException {
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
    private ASTNode parseForStatement() throws CythavaParseException {
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

        // 策略 1: 尝试解析为 "Type varName : expr"（有显式类型声明）
        // ★ 包含 auto/var 关键字（它们在语法上等同于无类型声明）
        if (isTypeStart(peek())) {
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

        // ★ 策略 1b: auto/var 关键字模式（for (auto i : iterable) 或 for (var i : iterable)）
        //   auto/var 是关键字而非标识符，isTypeStart 和 IDENTIFIER 检查都匹配不到
        if (itemName == null && (check(TokenType.KEYWORD_AUTO) || check(TokenType.KEYWORD_VAR))) {
            advance(); // 消费 auto/var
            itemName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name after auto/var").text();
            if (!match(TokenType.OPERATOR_COLON)) {
                position = savedPos; // 不是 for-each（可能是 auto x = expr; 等其他语句）
                return null;
            }
            // 冒号已消费，匹配成功
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
        if (itemTypeGT != null) {
            context.declareVariable(itemName, false, itemTypeGT);
        } else {
            context.declareVariable(itemName);
        }

        ASTNode body = parseStatementOrBlock();
        context.exitScope();



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
                update = new BlockNode.Builder().statements(updates).location(createLocation()).build();
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
    }

    // ==================== 跳转语句 ====================

    /** return [expression] ; */
    private ReturnNode parseReturnStatement() throws CythavaParseException {
        SourceLocation location = createLocation();

        ASTNode value = null;
        if (!check(TokenType.DELIMITER_SEMICOLON)) {
            value = parseExpr();
        }
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after return value");

        return (ReturnNode) new ReturnNode.Builder().value(value).location(location).build();
    }

    /** break [label] ; */
    private BreakNode parseBreakStatement() throws CythavaParseException {
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
    private ContinueNode parseContinueStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after continue");
        return (ContinueNode) new ContinueNode.Builder().location(location).build();
    }

    /** throw expression ; */
    private ThrowNode parseThrowStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        ASTNode expression = parseExpr();
        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after throw expression");
        return (ThrowNode) new ThrowNode.Builder().expression(expression).location(location).build();
    }

    // ==================== 异常处理 ====================

    /** try [-catch]* [-finally] */
    private TryNode parseTryStatement() throws CythavaParseException {
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
    private SwitchNode parseSwitchStatement() throws CythavaParseException {
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
            ASTNode body = c.getStatements().get(0);
            JType t = context.getType(body);
            if (t != null) types.add(t.getRawType());
            else if (body instanceof LiteralNode lit && lit.getType() != null)
                types.add(lit.getType());
        }
        if (defaultCase != null) {
            JType dt = context.getType(defaultCase);
            if (dt != null) types.add(dt.getRawType());
            else if (defaultCase instanceof com.justnothing.engine.ast.nodes.LiteralNode lit && lit.getType() != null)
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
    private void annotate(ASTNode node, Class<?> clazz) {
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

        // 多值 case (case 1, 2, 3 ->): Parser 层将多值 case 拆分为多个 CaseNode，
        // 每个 CaseNode 取第一个值作为匹配值，完整的多值匹配在 Evaluator 层实现。
        // 这样设计的好处是 Parser 保持简单，语义等价性由运行时保证。
        return (CaseNode) new CaseNode.Builder().value(values.get(0)).statements(statements).location(location).build();
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
        // 收集语句直到遇到 break、case、default 或 }
        List<ASTNode> statements = new ArrayList<>();
        while (!isAtEnd()
                && !check(TokenType.KEYWORD_BREAK)
                && !check(TokenType.KEYWORD_CASE)
                && !check(TokenType.KEYWORD_DEFAULT)
                && !check(TokenType.DELIMITER_RIGHT_BRACE)) {
            statements.add(parseStatementInternal());
        }
        // 消费 break 语句（如果有）
        if (match(TokenType.KEYWORD_BREAK)) {
            consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after break");
        }
        return new BlockNode.Builder().statements(statements).location(createLocation()).build();
    }

    // ==================== Async/Await ====================

    /** async 语句 */
    private ASTNode parseAsyncStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        ASTNode expression = parseStatementOrBlock();
        AsyncNode node = (AsyncNode) new AsyncNode.Builder().expression(expression).location(location).build();
        JType innerType = context.getType(expression);
        if (innerType != null) {
            context.setType(node, innerType);
        } else {
            annotate(node, Object.class);
        }
        return node;
    }

    private ASTNode parseDeleteStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        if (match(TokenType.OPERATOR_MULTIPLY)) {
            consume(TokenType.DELIMITER_SEMICOLON, "Expected ';' after delete *");
            return new DeleteNode.Builder().deleteAll(true).location(location).build();
        }
        Token name = consume(TokenType.IDENTIFIER, "Expected variable name after 'delete'");
        consume(TokenType.DELIMITER_SEMICOLON, "Expected ';' after delete statement");
        return new DeleteNode.Builder().variableName(name.text()).location(location).build();
    }


    // ==================== 块与表达式语句 ====================

    /** { ... } 块语句 */
    private BlockNode parseBlockStatement() throws CythavaParseException {
        return parseBlock();
    }

    /** 解析块 { statements } */
    public BlockNode parseBlock() throws CythavaParseException {
        SourceLocation location = createLocation();
        context.enterScope(ParseContext.ScopeKind.BLOCK);
        List<ASTNode> statements = parseBlockBody();
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after block");
        context.exitScope();
        return (BlockNode) new BlockNode.Builder().statements(statements).location(location).build();
    }

    /**
     * ASI (Automatic Semicolon Insertion): 自动分号插入
     * 如果下一个 token 是分号就吃掉，如果是 EOF 或流结束也允许省略。
     */
    private void consumeSemicolon() throws CythavaParseException {
        if (match(TokenType.DELIMITER_SEMICOLON)) {
            return;
        }
        if (!check(TokenType.EOF)) {
            throw error("Expected ';' after statement", ErrorCode.PARSE_UNEXPECTED_TOKEN);
        }
    }

    /** expression ; */
    private ASTNode parseExpressionStatement() throws CythavaParseException {
        ASTNode expression = parseExpr();
        consumeSemicolon();
        return expression; // 表达式语句直接返回表达式节点
    }

    // ==================== 标识符开头的语句 ====================

    /**
     * 标识符开头的情况:
     * - 标签: label:
     * - 局部变量声明: varName = expr 或 Type varName [= expr]
     * - 表达式语句: methodCall(), obj.field 等
     */
    private ASTNode parseIdentifierStartStatement() throws CythavaParseException {
        // 检查是否是标签: identifier :
        if (checkNext(TokenType.OPERATOR_COLON) && !checkNextIsLambdaArrow()) {
            return parseLabeledStatement();
        }

        // 可能是变量声明或表达式语句
        savePosition();
        String firstName = advance().text(); // 消费标识符

        // 检查是否是修饰符开头的变量声明: final / static 等
        if (isModifierKeyword(firstName)) {
            restorePosition();
            return parseModifierLocalVariableDeclaration();
        }

        // 检查是否是带类型标注的变量声明: Type varName ...
        // 贪心策略（参考老版 Parser.isQualifiedTypeVariableDeclaration）：
        //   从当前 position 向前扫描，不消费 token，判断是否符合
        //   "TypePart[.TypePart]* [<gen>] [[]]* varName" 模式
        boolean ltd = looksLikeTypeDeclaration();
        if (ltd && !check(TokenType.OPERATOR_ASSIGN)) {
            restorePosition();
            return parseLocalVariableDeclaration(null, false);
        }

        // 检查是否是简单变量声明或赋值: varName = expr ;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            releasePosition();
            SourceLocation location = createLocation();
            ASTNode value = parseExpr();

            if (context.isKnownVariable(firstName) || context.isFieldOfCurrentClass(firstName)) {
                // 已声明的变量 / 当前类的字段 → 赋值（非新声明）
                GenericType declaredType = context.getDeclaredType(firstName);
                if (declaredType != null) {
                    checkTypeCompatibility(firstName, declaredType, value);
                }
                consumeSemicolon();
                AssignmentNode assignNode = (AssignmentNode) new AssignmentNode.Builder().variableName(firstName).value(value).isDeclaration(false).declaredType(declaredType).isFinal(context.isFinal(firstName)).location(location).build();
                JType valueType = context.getType(value);
                if (valueType != null) {
                    context.setType(assignNode, valueType);
                }
                return assignNode;
            } else {
                // 新变量 → 声明（auto 类型推断）
                consumeSemicolon();
                context.declareVariable(firstName);
                // auto 变量：从初始化器推断类型并注册到符号表
                GenericType inferredType = context.getInferredType(value);
                if (inferredType != null) {
                    VariableSymbol sym = context.resolveVariable(firstName);
                    if (sym != null) sym.setDeclaredType(inferredType);
                }
                return new VarDeclNode.Builder().varName(firstName).declaredType(null).initializer(value).isFinal(false).annotations(null).location(location).build();
            }
        }

        // 检查复合赋值: varName += expr 等
        if (isCompoundAssignmentOperator()) {
            releasePosition();
            return parseCompoundAssignment(firstName);
        }

        // 不是特殊形式，回退让表达式解析器处理
        restorePosition();
        return parseExpressionStatement();
    }

    /** label: statement */
    private LabeledStatementNode parseLabeledStatement() throws CythavaParseException {
        SourceLocation location = createLocation();
        String label = advance().text(); // 标签名
        consumeOrSemanticError(TokenType.OPERATOR_COLON, "Expected ':' after label");
        ASTNode statement = parseStatementInternal();
        return (LabeledStatementNode) new LabeledStatementNode.Builder().label(label).statement(statement).location(location).build();
    }

    /**
     * 局部变量声明: [@Annotation...] [final] Type varName [= initializer] [, varName2 ...] ;
     *
     * @param annotations 前置注解（可能为 null）
     * @param hasFinal   是否有 final 修饰符
     */
    private ASTNode parseLocalVariableDeclaration(List<AnnotationNode> annotations, boolean hasFinal)
            throws CythavaParseException {
        SourceLocation location = createLocation();

        // 消费 auto/var 关键字（类型推断，无需显式类型）
        boolean isAuto = match(TokenType.KEYWORD_AUTO) || match(TokenType.KEYWORD_VAR);

        // 使用 TypeParser 解析完整类型（支持泛型、数组、通配符等）
        // auto/var 已消费，不需要显式类型，跳过类型解析
        GenericType declaredType = null;
        if (!isAuto && isTypeStart(peek())) {
            int savedPos = position;
            try {
                TypeParser typeParser = new TypeParser(tokens, context, fileName);
                typeParser.setPosition(position);
                declaredType = typeParser.parseType();
                position = typeParser.getPosition();

                // 防御性检查：如果类型名是 "var" 或 "auto"，说明这些关键字被误当类型名解析了
                //   （可能因 DeclParser 安全网未拦截到），应降级为 auto 类型推断
                String origName = declaredType.getOriginalTypeName();
                if ((Keywords.VAR.equals(origName) || Keywords.AUTO.equals(origName))) {
                    declaredType = null; // 降级为 auto：类型从初始化器推断
                }
            } catch (CythavaParseException e) {
                // 类型解析错误应直接抛出，不应静默回退
                if (e.getMessage() != null && e.getMessage().contains("Unknown type")) {
                    throw e;
                }
                if (e.getErrorCode() == ErrorCode.PARSE_INVALID_TYPE) {
                    throw e;
                }
                position = savedPos;
            }
        }

        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name");
        String varName = nameToken.text();

        // 提前注册变量到符号表（在解析初始化器之前），确保即使初始化器解析失败
        //   （如匿名类体语法复杂导致 parseExpr 异常），变量名仍然可用
        if (context.isVariableDeclared(varName)) {
            if (context.isStrictMode()) {
                throw new CythavaParseException(
                        "Duplicate variable declaration: '" + varName + "'",
                        nameToken.location(),
                        ErrorCode.SCOPE_VARIABLE_ALREADY_DECLARED);
            }
            // 非严格模式：静默允许重新声明（REPL 兼容）
        } else {
            context.declareVariable(varName, hasFinal, declaredType);
        }

        // 可选初始化器
        ASTNode initializer = null;
        if (match(TokenType.OPERATOR_ASSIGN)) {
            initializer = parseExpr();
            // 声明时检查初始化器类型兼容性
            if (declaredType != null && initializer != null) {
                checkTypeCompatibility(varName, declaredType, initializer);
            }
        }

        // 原始类型无显式初始化器时，自动赋予 Java 默认值（与 Java 语义一致）
        if (initializer == null && isPrimitiveType(declaredType)) {
            initializer = defaultLiteralFor(declaredType.getRawType(), location);
        }

        // 逗号分隔的多变量声明：int a, b = 1, c;
        List<ASTNode> declarations = new ArrayList<>();
        VarDeclNode firstDecl = (VarDeclNode) new VarDeclNode.Builder().varName(varName).declaredType(declaredType).initializer(initializer).isFinal(hasFinal).annotations(annotations).location(location).build();
        declarations.add(firstDecl);

        while (match(TokenType.DELIMITER_COMMA)) {
            SourceLocation nextLocation = createLocation();
            String nextName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name").text();

            // 后续变量可带独立数组维度: int[] a, b[], c[][]
            GenericType nextType = declaredType;
            int nextDims = 0;
            while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
                if (!match(TokenType.DELIMITER_RIGHT_BRACKET)) { position -= 2; break; }
                nextDims++;
            }
            if (nextDims > 0 && nextType != null) {
                Class<?> base = nextType.getRawType();
                if (base != null) {
                    nextType = GenericType.of(Array.newInstance(base, new int[nextDims]).getClass());
                }
            }

            ASTNode nextInit = null;
            if (match(TokenType.OPERATOR_ASSIGN)) {
                nextInit = parseExpr();
                if (nextType != null && nextInit != null) {
                    checkTypeCompatibility(nextName, nextType, nextInit);
                }
            }

            // 后续变量同样自动赋予原始类型默认值
            if (nextInit == null && isPrimitiveType(nextType)) {
                nextInit = defaultLiteralFor(nextType.getRawType(), nextLocation);
            }

            declarations.add(new VarDeclNode.Builder().varName(nextName).declaredType(nextType).initializer(nextInit).isFinal(hasFinal).annotations(null).location(nextLocation).build());
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after local variable declaration");

        // 将声明的变量注册到符号表（后续引用和严格模式消歧需要）
        for (ASTNode decl : declarations) {
            if (decl instanceof VarDeclNode vd) {
                if (!context.isVariableDeclared(vd.getVarName())) {
                    context.declareVariable(vd.getVarName(), vd.isFinal(), vd.getDeclaredType());
                }
                // ★ 匿名类初始化器：将匿名类关联到变量符号（供后续字段访问解析）
                if (vd.getInitializer() instanceof com.justnothing.engine.ast.nodes.ConstructorCallNode cc
                        && cc.getAnonymousClass() != null) {
                    context.setVariableAnonymousClass(vd.getVarName(), cc.getAnonymousClass());
                }
            }
        }

        return declarations.size() == 1 ? declarations.get(0)
                : new BlockNode.Builder().statements(declarations).location(location).build();
    }

    /** final / static 等修饰符开头的局部变量声明 */
    private ASTNode parseModifierLocalVariableDeclaration() throws CythavaParseException {
        boolean hasFinal = false;
        while (isModifierKeyword(peek().text())) {
            if (Keywords.FINAL.equals(peek().text())) {
                hasFinal = true;
            }
            advance(); // 消费修饰符
        }
        return parseLocalVariableDeclaration(null, hasFinal);
    }

    /**
     * auto 变量声明: auto varName = expr [, varName2 = expr2] ;
     * <p>
     * 类型从初始化表达式的解析期类型标注自动推断。
     * 要求必须有初始化器（auto 不能没有初始值）。
     * </p>
     */
    private ASTNode parseAutoVariableDeclaration() throws CythavaParseException {
        SourceLocation location = createLocation();

        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name after 'auto'");
        String varName = nameToken.text();

        consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "auto variable '" + varName + "' requires an initializer (= expression)");
        ASTNode initializer = parseExpr();

        // 从 typeMap 推断类型
        GenericType inferredType = context.getInferredType(initializer);

        List<ASTNode> declarations = new ArrayList<>();
        declarations.add(new AssignmentNode.Builder().variableName(varName).value(initializer).isDeclaration(true).declaredType(inferredType).isFinal(false).location(location).build());

        // 逗号分隔的后续 auto 变量（每个都必须有初始化器）
        while (match(TokenType.DELIMITER_COMMA)) {
            SourceLocation nextLoc = createLocation();
            String nextName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected variable name").text();
            consumeOrSemanticError(TokenType.OPERATOR_ASSIGN, "auto variable '" + nextName + "' requires an initializer");
            ASTNode nextInit = parseExpr();
            GenericType nextType = context.getInferredType(nextInit);
            declarations.add(new AssignmentNode.Builder().variableName(nextName).value(nextInit).isDeclaration(true).declaredType(nextType).isFinal(false).location(nextLoc).build());
        }

        consumeOrSemanticError(TokenType.DELIMITER_SEMICOLON, "Expected ';' after auto variable declaration");

        // 注册到符号表（含类型信息和匿名类关联）
        for (ASTNode decl : declarations) {
            if (decl instanceof AssignmentNode assign) {
                context.declareVariable(assign.getVariableName(), assign.getDeclaredType());
                // ★ 匿名类初始化器：将匿名类关联到变量符号（供后续字段访问解析）
                if (assign.getValue() instanceof com.justnothing.engine.ast.nodes.ConstructorCallNode cc
                        && cc.getAnonymousClass() != null) {
                    context.setVariableAnonymousClass(assign.getVariableName(), cc.getAnonymousClass());
                }
            }
        }

        return declarations.size() == 1 ? declarations.get(0)
                : new BlockNode.Builder().statements(declarations).location(location).build();
    }

    /** "@Annotation [final] Type varName ..." 注解开头的局部变量声明 */
    private ASTNode parseAnnotatedLocalVariableDeclaration() throws CythavaParseException {
        List<AnnotationNode> annotations = new ArrayList<>();
        while (match(TokenType.DELIMITER_AT)) {
            String annoName = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected annotation name").text();
            if (match(TokenType.DELIMITER_LEFT_PAREN)) {
                Map<String, Object> args = parseAnnotationArguments();
                consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after annotation arguments");
                annotations.add(new AnnotationNode.Builder().annotationName(annoName).values(args).location(createLocation()).build());
            } else {
                annotations.add(new AnnotationNode.Builder().annotationName(annoName).location(createLocation()).build());
            }
        }

        // 可选的 final 修饰符（final 可能是 KEYWORD_FINAL 或 IDENTIFIER）
        boolean hasFinal = false;
        if (isModifierKeyword(peek().text())) {
            if (Keywords.FINAL.equals(peek().text())) {
                hasFinal = true;
            }
            advance();
        }

        return parseLocalVariableDeclaration(annotations, hasFinal);
    }

    /** 解析注解参数列表: value 或 k1=v1, k2=v2 */
    private Map<String, Object> parseAnnotationArguments() throws CythavaParseException {
        Map<String, Object> args = new LinkedHashMap<>();
        do {
            SourceLocation loc = createLocation();
            String keyOrValue = advance().text();
            if (match(TokenType.OPERATOR_ASSIGN)) {
                ASTNode valExpr = parseExpr();
                args.put(keyOrValue, valExpr);
            } else {
                args.put("value", new LiteralNode.Builder().value(keyOrValue).type(String.class).location(loc).build());
            }
        } while (match(TokenType.DELIMITER_COMMA));
        return args;
    }

    /** 判断标识符文本是否为 Java 修饰符关键字 */
    private static boolean isModifierKeyword(String text) {
        return (
                Keywords.FINAL.equals(text)
                        || Keywords.STATIC.equals(text)
                        || Keywords.PUBLIC.equals(text)
                        || Keywords.PRIVATE.equals(text)
                        || Keywords.PROTECTED.equals(text)
                        || Keywords.ABSTRACT.equals(text)
                        || Keywords.NATIVE.equals(text)
                        || Keywords.SYNCHRONIZED.equals(text));
    }

    /**
     * 检查赋值/初始化值的类型与声明类型是否兼容。
     * <p>
     * 基本规则（简化版，后续可扩展为完整 JLS 赋值转换）：
     * <ul>
     *   <li>相同类型 → 兼容</li>
     *   <li>int → long/double/float（ widening 基本类型提升）→ 兼容</li>
     *   <li>子类 → 父类（引用类型向上转型）→ 兼容</li>
     *   <li>null → 任何引用类型 → 兼容</li>
     *   <li>其他情况 → 报错</li>
     * </ul>
     *
     * @param varName       变量名（用于错误信息）
     * @param declaredType  声明类型
     * @param valueNode     赋值表达式节点
     */
    private void checkTypeCompatibility(String varName, GenericType declaredType, ASTNode valueNode)
            throws CythavaParseException {
        if (declaredType == null || declaredType.getRawType() == null) return;  // auto 类型无法检查

        Class<?> targetType = declaredType.getRuntimeType();
        JType valueJType = context.getType(valueNode);
        Class<?> valueType = valueJType != null ? resolveRuntimeType(valueJType) : context.getRawType(valueNode);

        // null 兼容所有引用类型
        if (valueType == null) return;

        // 完全匹配
        if (targetType.isAssignableFrom(valueType)) return;

        // 基本类型 widening: int→long, int→double, float→double 等
        if (isWideningConversion(valueType, targetType)) return;

        // 自动装箱: int → Integer, long → Long 等
        if (valueType.isPrimitive() && !targetType.isPrimitive()) {
            Class<?> boxed = box(valueType);
            if (boxed != null && targetType.isAssignableFrom(boxed)) return;
        }
        // 自动拆箱: Integer → int, Long → long 等
        if (!valueType.isPrimitive() && targetType.isPrimitive()) {
            Class<?> unboxed = unbox(valueType);
            if (unboxed != null && targetType.isAssignableFrom(unboxed)) return;
        }

        // Lambda/方法引用隐式转换为函数式接口（必须在 Object 放行之前处理）
        if (MethodResolver.isLambdaOrMethodRefNode(valueNode)
                && MethodResolver.isFunctionalInterface(targetType)) {
            if (valueNode instanceof LambdaNode lambda) {
                lambda.setFunctionalInterfaceType(targetType);
            } else if (valueNode instanceof MethodReferenceNode methodRef) {
                methodRef.setFunctionalInterfaceType(targetType);
            }
            return;
        }

        // 类型推断返回 Object（泛型擦除/无法精确推断）时允许赋给任意类型
        // 因为此时没有足够信息做静态检查，信任运行时行为
        if (valueType == Object.class) {
            return;
        }

        // 钻石操作符/未导入构造器回退：new HashMap<>() 等因类未导入被标注为 Object，
        // 通过 ClassResolver.findClassWithImports 利用已注册的 import 列表重新解析
        if (valueType == Object.class && valueNode instanceof ConstructorCallNode ctor) {
            String ctorTypeName = ctor.getType().getTypeName();
            if (ctorTypeName != null && !ctorTypeName.isEmpty()) {
                // ★ 直接使用 context.resolveClass（内部调用 findClassWithImports），
                //   无需手动枚举包前缀 — import 机制已覆盖 java.lang / java.util / java.io 等
                Class<?> resolved = context.resolveClass(ctorTypeName);
                if (resolved != null && targetType.isAssignableFrom(resolved)) return;
            }
        }

        // 非严格模式：所有类型不匹配静默放行（兼容旧版 raw class 代码）
        if (!context.isStrictMode()) {
            return;
        }

        throw semanticError("Type mismatch: cannot assign " + valueType.getSimpleName()
                + " to variable '" + varName + "' of type " + targetType.getSimpleName(),
                ErrorCode.EVAL_TYPE_MISMATCH);
    }

    /** 将 JType 解析为完整的运行时 Class（对数组类型合成 int[]、String[][] 等）。 */
    private static Class<?> resolveRuntimeType(JType type) {
        Class<?> raw = type.getRawType();
        if (raw == null) return null;
        int depth = type.getArrayDepth();
        if (depth > 0) {
            return java.lang.reflect.Array.newInstance(raw, new int[depth]).getClass();
        }
        return raw;
    }

    /** 判断是否为基本类型的 widening 转换。 */
    private static boolean isWideningConversion(Class<?> from, Class<?> to) {
        if (!from.isPrimitive() || !to.isPrimitive()) return false;
        // JLS §5.1.2 Widening Primitive Conversion
        if (from == byte.class)   return to == short.class || to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == short.class)  return to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == char.class)   return to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == int.class)    return to == long.class || to == float.class || to == double.class;
        if (from == long.class)   return to == float.class || to == double.class;
        if (from == float.class)  return to == double.class;
        return false;
    }

    /** 判断 GenericType 是否为 Java 原始类型（int/long/double/float/boolean/char/byte/short）。 */
    private static boolean isPrimitiveType(GenericType type) {
        if (type == null || type.getRawType() == null) {
            return false;
        }
        return type.getRawType().isPrimitive();
    }

    /**
     * 为原始类型生成默认值字面量（与 Java 默认值语义一致）。
     * <ul>
     *   <li>int, short, byte → 0</li>
     *   <li>long → 0L</li>
     *   <li>double → 0.0</li>
     *   <li>float → 0.0f</li>
     *   <li>boolean → false</li>
     *   <li>char → '\0'</li>
     * </ul>
     */
    private static LiteralNode defaultLiteralFor(Class<?> primitiveType, SourceLocation location) {
        if (primitiveType == int.class || primitiveType == short.class || primitiveType == byte.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0).type(int.class).location(location).build();
        }
        if (primitiveType == long.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0L).type(long.class).location(location).build();
        }
        if (primitiveType == double.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0.0).type(double.class).location(location).build();
        }
        if (primitiveType == float.class) {
            return (LiteralNode) new LiteralNode.Builder().value(0.0f).type(float.class).location(location).build();
        }
        if (primitiveType == boolean.class) {
            return (LiteralNode) new LiteralNode.Builder().value(false).type(boolean.class).location(location).build();
        }
        if (primitiveType == char.class) {
            return (LiteralNode) new LiteralNode.Builder().value('\0').type(char.class).location(location).build();
        }
        // 不应到达这里
        return (LiteralNode) new LiteralNode.Builder().value(null).type(void.class).location(location).build();
    }

    /** x += expr ; 等复合赋值 */
    private ASTNode parseCompoundAssignment(String varName) throws CythavaParseException {
        SourceLocation location = createLocation();

        // 读取并消费复合赋值操作符
        BinaryOpNode.Operator op = consumeCompoundOperator();
        if (op == null) {
            throw error("Expected compound assignment operator", ErrorCode.PARSE_UNEXPECTED_TOKEN);
        }

        ASTNode value = parseExpr();
        consumeSemicolon();

        // 创建变量引用节点（带类型标注），使 read-modify-write 的 BinaryOpNode 能正确读取当前值
        VariableNode varRef = (VariableNode) new VariableNode.Builder().name(varName).location(location).build();
        GenericType declaredType = context.getDeclaredType(varName);
        if (declaredType != null) {
            context.setType(varRef, JType.fromGenericType(declaredType));
        }

        // 包装为 BinaryOpNode：Evaluator 在 visitAssignment 中 evaluate 此节点时，
        // 会递归 evaluate(varRef) 读取当前值，再与 RHS 做运算
        ASTNode combinedValue = new BinaryOpNode.Builder()
                .operator(op)
                .left(varRef)
                .right(value)
                .location(location)
                .build();

        // 复合赋值的结果类型与 LHS 变量类型一致（如 int += int → int）
        JType assignType = declaredType != null ? JType.fromGenericType(declaredType) : null;

        AssignmentNode assignNode = (AssignmentNode) new AssignmentNode.Builder()
                                .variableName(varName)
                                .value(combinedValue)
                                .isDeclaration(false)
                                .declaredType(declaredType)
                                .location(location)
                                .build();
        if (assignType != null) {
            context.setType(assignNode, assignType);
        }
        return assignNode;
    }

    /** 消费复合赋值操作符并返回对应的 BinaryOpNode.Operator，无匹配则返回 null。 */
    private BinaryOpNode.Operator consumeCompoundOperator() {
        if (match(TokenType.OPERATOR_PLUS_ASSIGN)) return BinaryOpNode.Operator.ADD;
        if (match(TokenType.OPERATOR_MINUS_ASSIGN)) return BinaryOpNode.Operator.SUBTRACT;
        if (match(TokenType.OPERATOR_MULTIPLY_ASSIGN)) return BinaryOpNode.Operator.MULTIPLY;
        if (match(TokenType.OPERATOR_DIVIDE_ASSIGN)) return BinaryOpNode.Operator.DIVIDE;
        if (match(TokenType.OPERATOR_MODULO_ASSIGN)) return BinaryOpNode.Operator.MODULO;
        if (match(TokenType.OPERATOR_BITWISE_AND_ASSIGN)) return BinaryOpNode.Operator.BITWISE_AND;
        if (match(TokenType.OPERATOR_BITWISE_OR_ASSIGN)) return BinaryOpNode.Operator.BITWISE_OR;
        if (match(TokenType.OPERATOR_BITWISE_XOR_ASSIGN)) return BinaryOpNode.Operator.BITWISE_XOR;
        if (match(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.LEFT_SHIFT;
        if (match(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.RIGHT_SHIFT;
        if (match(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN)) return BinaryOpNode.Operator.UNSIGNED_RIGHT_SHIFT;
        if (match(TokenType.OPERATOR_NULL_COALESCING_ASSIGN)) return BinaryOpNode.Operator.NULL_COALESCING;
        return null;
    }

    // ==================== 函数定义 ====================

    /**
     * 解析函数定义语句。
     * <p>
     * 支持两种语法：
     * <ol>
     *   <li>{@code function name(params) { body }} — 无返回类型声明</li>
     *   <li>{@code function returnType name(params) { body }} — 带返回类型声明</li>
     * </ol>
     *
     * @return FunctionDefNode
     */
    private FunctionDefNode parseFunctionDefinition() throws CythavaParseException {
        SourceLocation location = createLocation();

        // 可选的返回类型：尝试用 TypeParser 解析，成功则下一个 token 是函数名
        ClassReferenceNode returnType = null;
        if (isTypeStart(peek()) && !check(TokenType.DELIMITER_LEFT_PAREN)) {
            // 保存位置：如果 TypeParser 解析失败或后面不是函数名，回退
            int savedPos = position;
            try {
                TypeParser typeParser = new TypeParser(tokens, context, fileName);
                typeParser.setPosition(position);
                GenericType gt = typeParser.parseType();
                // 解析成功后，下一个 token 必须是标识符（函数名）
                if (typeParser.getPosition() < tokens.size()
                        && tokens.get(typeParser.getPosition()).type() == TokenType.IDENTIFIER) {
                    position = typeParser.getPosition();
                    // GenericType.of(原始类型) 不设置 originalTypeName，需从 rawType 推导
                    String typeName = gt.getOriginalTypeName();
                    if (typeName == null && gt.getRawType() != null) {
                        typeName = gt.getRawType().getName();
                    }
                    returnType = new ClassReferenceNode.Builder()
                            .originalTypeName(typeName)
                            .resolvedClass(gt.getRawType())
                            .location(createLocation())
                            .build();
                } else {
                    // 类型解析成功但后面不是函数名 → 不是返回类型声明
                    position = savedPos;
                }
            } catch (CythavaParseException e) {
                // TypeParser 完全失败 → 回退到简单名称解析（兼容非泛型场景）
                position = savedPos;
                StringBuilder typeSb = new StringBuilder(advance().text());
                while (check(TokenType.OPERATOR_DOT) && position + 1 < tokens.size()
                        && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
                    typeSb.append(advance().text()); // .
                    typeSb.append(advance().text()); // 标识符
                }
                // 数组后缀
                while (check(TokenType.DELIMITER_LEFT_BRACKET)) {
                    consume(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in return type");
                    typeSb.append("[]");
                }
                returnType = new ClassReferenceNode.Builder()
                        .originalTypeName(typeSb.toString())
                        .resolvedClass(context.resolveClass(typeSb.toString()))
                        .location(createLocation())
                        .build();
            }
        }

        // 函数名
        Token nameToken = consumeOrSemanticError(TokenType.IDENTIFIER, "Expected function name after 'function'");
        String functionName = nameToken.text();

        // 参数列表
        consumeOrSemanticError(TokenType.DELIMITER_LEFT_PAREN, "Expected '(' after function name '" + functionName + "'");
        List<LambdaNode.Parameter> parameters = new ArrayList<>();
        while (!check(TokenType.DELIMITER_RIGHT_PAREN) && !isAtEnd()) {
            // 参数类型（可选）
            ClassReferenceNode paramType = null;
            if (isTypeStart(peek()) && !check(TokenType.IDENTIFIER)
                    || (isTypeStart(peek()) && check(TokenType.IDENTIFIER)
                    && position + 1 < tokens.size() && tokens.get(position + 1).type() == TokenType.IDENTIFIER)) {
                StringBuilder paramTypeSb = new StringBuilder(advance().text());
                while (check(TokenType.OPERATOR_DOT) && position + 1 < tokens.size()
                        && tokens.get(position + 1).type() == TokenType.IDENTIFIER) {
                    paramTypeSb.append(advance().text());
                    paramTypeSb.append(advance().text());
                }
                paramType = new ClassReferenceNode.Builder()
                        .originalTypeName(paramTypeSb.toString())
                        .resolvedClass(context.resolveClass(paramTypeSb.toString()))
                        .location(createLocation())
                        .build();
            }

            // 参数名
            String paramName = advance().text();
            Class<?> paramClazz = paramType != null ? paramType.getResolvedClass() : Object.class;
            parameters.add(new LambdaNode.Parameter(paramName, paramClazz));

            if (!match(TokenType.DELIMITER_COMMA)) break;
        }
        consumeOrSemanticError(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after function parameter list");

        // 函数体：进入新作用域，注册参数和函数名（支持递归）
        // 同时在当前作用域注册函数名，使后续语句可引用
        context.declareVariable(functionName);
        context.enterScope(ParseContext.ScopeKind.METHOD);
        context.declareVariable(functionName);
        for (LambdaNode.Parameter param : parameters) {
            context.declareVariable(param.name());
        }
        ASTNode body = parseStatementOrBlock();
        context.exitScope();

        return (FunctionDefNode) new FunctionDefNode.Builder()
                .functionName(functionName)
                .returnType(returnType)
                .parameters(parameters)
                .body(body)
                .location(location)
                .build();
    }

    // ==================== 辅助方法 ====================

    /** 判断 token 是否可能作为类型声明的起始（基本类型关键字或标识符/类名） */
    private boolean isTypeStart(Token token) {
        return token != null && isTypeStartToken(token.type());
    }

    /** 判断 token 类型是否为关键字（排除类型关键字，只判断结构关键字）。 */
    private boolean isKeywordToken(TokenType type) {
        return type.name().startsWith("KEYWORD_");
    }

    /**
     * 贪心判断当前位置之后是否构成 "类型名 变量名" 的声明模式。
     * <p>
     * 参考老版 engine Parser.isQualifiedTypeVariableDeclaration() 的设计：
     * 从当前 position（已消费第一个标识符后）向前扫描，不消费任何 token，
     * 检查是否匹配以下模式之一：
     * <ul>
     *   <li>{@code TypePart (. TypePart)* [< gen >] [[]]* ID} — 限定名/泛型/数组 + 变量名</li>
     *   <li>{@code < gen >} — 泛型参数开头（如 {@code List<String> x} 中消费了 List 后的 &lt;）</li>
     *   <li>基本类型 / {@code ?} 通配符 / {@code [} 数组开头</li>
     * </ul>
     * 关键排除：方法调用（如 {@code m.put(} 中 put 后是 {@code (}）不会匹配。
     *
     * @return true 如果后续 token 更像是类型声明而非表达式语句
     */
    private boolean looksLikeTypeDeclaration() {
        // 快速路径：单 token 类型起始（基本类型、通配符、泛尖括号）
        if (isTypeStartToken(peek().type())
                || check(TokenType.OPERATOR_LESS_THAN)
                || check(TokenType.OPERATOR_QUESTION)) {
            return true;
        }

        // 数组维度 [ 起始：需要进一步检查不是数组方法引用（String[]::method）
        if (check(TokenType.DELIMITER_LEFT_BRACKET)) {
            return looksLikeArrayTypeNotMethodRef();
        }

        // 慢速路径：限定名类型（Map.Entry e, java.util.List<String> arr 等）
        // 贪心扫描: (DOT IDENTIFIER)* (< generic >)? ([][])* 然后看下一个是否是变量名
        if (check(TokenType.OPERATOR_DOT)) {
            int pos = position;
            boolean hadDot = false;

            while (pos < tokens.size()) {
                TokenType t = tokens.get(pos).type();

                if (t == TokenType.OPERATOR_DOT) {
                    if (pos + 1 >= tokens.size()) return false;
                    TokenType next = tokens.get(pos + 1).type();
                    if (next != TokenType.IDENTIFIER && !isKeywordToken(next)) {
                        return false;
                    }
                    hadDot = true;
                    pos += 2;
                    continue;
                }

                if (t == TokenType.OPERATOR_LESS_THAN) {
                    // 泛型参数 < ... >，跳过（处理嵌套 >> >>>）
                    int depth = 1;
                    pos++;
                    while (pos < tokens.size() && depth > 0) {
                        TokenType gt = tokens.get(pos).type();
                        if (gt == TokenType.OPERATOR_LESS_THAN) depth++;
                        else if (gt == TokenType.OPERATOR_GREATER_THAN) depth--;
                        else if (gt == TokenType.OPERATOR_RIGHT_SHIFT) depth -= 2;
                        else if (gt == TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT) depth -= 3;
                        pos++;
                    }
                    continue;
                }

                if (t == TokenType.DELIMITER_LEFT_BRACKET) {
                    if (pos + 1 >= tokens.size()
                            || tokens.get(pos + 1).type() != TokenType.DELIMITER_RIGHT_BRACKET) {
                        return false;
                    }
                    pos += 2;
                    continue;
                }

                return hadDot && t == TokenType.IDENTIFIER;
            }
        }

        return false;
    }

    /**
     * 检查当前 token 是否为数组维度 {@code [} 且不是数组方法引用。
     * <p>
     * 用于消歧 {@code String[] arr}（变量声明） vs {@code String[]::method}（方法引用）。
     * 扫描 {@code [...]} 对并检查后续 token 是否为 {@code ::}。
     * </p>
     */
    private boolean looksLikeArrayTypeNotMethodRef() {
        int pos = position;
        while (pos < tokens.size()) {
            TokenType t = tokens.get(pos).type();
            if (t == TokenType.DELIMITER_LEFT_BRACKET) {
                if (pos + 1 >= tokens.size()
                        || tokens.get(pos + 1).type() != TokenType.DELIMITER_RIGHT_BRACKET) {
                    return false;
                }
                pos += 2;
                continue;
            }
            // [][] 后跟 :: → 方法引用，不是类型声明
            if (t == TokenType.OPERATOR_DOUBLE_COLON) {
                return false;
            }
            // [][] 后跟标识符 → 变量声明
            return t == TokenType.IDENTIFIER;
        }
        return false;
    }

    /** 解析一条语句或一个 {} 块。 */
    private ASTNode parseStatementOrBlock() throws CythavaParseException {
        if (check(TokenType.DELIMITER_LEFT_BRACE)) {
            advance();
            return parseBlock();
        }
        return parseStatementInternal();
    }

    /** 解析类型名称（用于 catch 子句）。 */
    private Class<?> parseTypeName() throws CythavaParseException {
        StringBuilder sb = new StringBuilder(advance().text()); // 第一个标识符或基本类型

        while (match(TokenType.OPERATOR_DOT)) {
            sb.append('.').append(advance().text());
        }

        // 数组后缀
        int dimensions = 0;
        while (match(TokenType.DELIMITER_LEFT_BRACKET)) {
            consumeOrSemanticError(TokenType.DELIMITER_RIGHT_BRACKET, "Expected ']' in array type");
            sb.append("[]");
            dimensions++;
        }

        Class<?> baseType = context.resolveClass(sb.toString());
        // 按照数组维度创建对应的数组类型
        if (dimensions > 0 && baseType != null) {
            return Array.newInstance(baseType, new int[dimensions]).getClass();
        }
        return baseType;
    }


    /** 当前 token 是否是复合赋值操作符。 */
    private boolean isCompoundAssignmentOperator() {
        return check(TokenType.OPERATOR_PLUS_ASSIGN)
                || check(TokenType.OPERATOR_MINUS_ASSIGN)
                || check(TokenType.OPERATOR_MULTIPLY_ASSIGN)
                || check(TokenType.OPERATOR_DIVIDE_ASSIGN)
                || check(TokenType.OPERATOR_MODULO_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_AND_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_OR_ASSIGN)
                || check(TokenType.OPERATOR_BITWISE_XOR_ASSIGN)
                || check(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN)
                || check(TokenType.OPERATOR_NULL_COALESCING_ASSIGN);
    }

    /**
     * 检查下一个下一个 token 是否是 lambda 箭头 ->。
     * 用于区分 label: 和 lambda 参数中的冒号。
     */
    private boolean checkNextIsLambdaArrow() {
        // peek(1) 是 :, 再看后面有没有 ->
        if (position + 2 >= tokens.size()) {
            return false;
        }
        return tokens.get(position + 2).type() == TokenType.DELIMITER_ARROW;
    }

    /** 用于暂存 tryParseForEach 返回值的临时字段。 */
    private ASTNode lastParsedNode;

    private ASTNode getLastParsedNode() {
        return lastParsedNode;
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == char.class) return Character.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == void.class) return Void.class;
        return primitive;
    }

    private static Class<?> unbox(Class<?> wrapper) {
        if (wrapper == Integer.class) return int.class;
        if (wrapper == Long.class) return long.class;
        if (wrapper == Double.class) return double.class;
        if (wrapper == Float.class) return float.class;
        if (wrapper == Boolean.class) return boolean.class;
        if (wrapper == Character.class) return char.class;
        if (wrapper == Byte.class) return byte.class;
        if (wrapper == Short.class) return short.class;
        return null;
    }
}
