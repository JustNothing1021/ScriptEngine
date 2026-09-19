package com.justnothing.engine.parser;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.nodes.*;
import com.justnothing.engine.lexer.Keywords;
import com.justnothing.engine.lexer.Token;
import com.justnothing.engine.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda 与块表达式解析层。
 */
abstract class LambdaParser extends ObjectCreationParser {

    protected LambdaParser(List<Token> tokens, ParseContext context, String fileName) {
        super(tokens, context, fileName);
    }

    /**
     * 解析括号表达式或 Lambda。
     * <p>
     * 当遇到 {@code (} 时，尝试以下顺序：
     * <ol>
     * <li>Lambda: {@code (params) -> body}</li>
     * <li>普通括号表达式: {@code (expression)}</li>
     * </ol>
     * </p>
     */
    protected ASTNode parseParenExpressionOrLambda() throws CythavaParseException {
        savePosition();

        // 先尝试按 lambda 解析
        advance(); // 吃掉 (
        ASTNode lambda = tryParseLambdaInParens();
        if (lambda != null) {
            releasePosition();
            return lambda;
        }
        // tryParseLambdaInParens 返回 null = 确定不是 lambda（无 -> 匹配），不会抛异常
        // 只有确认是 Lambda 后 buildLambdaBody 才可能抛异常，那应该直接传播而非 fallback

        restorePosition();

        // 普通括号表达式
        advance(); // 吃掉 (
        ASTNode expr = parseNextExpression();
        consume(TokenType.DELIMITER_RIGHT_PAREN, "Expected ')' after expression");
        return expr;
    }

    /** 检查下一个 token（不消费）是否是类型起始或标识符，用于 lambda 参数消歧。 */
    private boolean checkNextIsTypeStartOrIdentifier() {
        if (position + 1 >= tokens.size())
            return false;
        return isTypeStartToken(tokens.get(position + 1).type());
    }

    private ASTNode tryParseLambdaInParens() throws CythavaParseException {
        // 空括号 () ->
        if (check(TokenType.DELIMITER_RIGHT_PAREN)) {
            advance(); // 吃掉 )
            if (!match(TokenType.DELIMITER_ARROW)) {
                return null; // () 但后面不是 -> ，不是 lambda
            }
            return buildLambdaBody(new ArrayList<>());
        }

        // 尝试解析参数列表
        List<LambdaNode.Parameter> params = new ArrayList<>();
        boolean isLambda = true;

        do {
            if (!check(TokenType.IDENTIFIER) && !isTypeStartToken()) {
                isLambda = false;
                break;
            }

            // 可选的类型标注：只有当标识符后紧跟另一个标识符/类型时才视为类型名
            // 例如 (int x) → int 是类型, x 是参数名; (x) → x 是参数名（无类型标注）
            Class<?> paramTypeClass = null;
            if (isTypeStartToken()
                    && !check(TokenType.DELIMITER_RIGHT_PAREN)
                    && !check(TokenType.DELIMITER_COMMA)
                    && (checkNextIsTypeStartOrIdentifier())) {
                StringBuilder typeBuilder = new StringBuilder(advance().text());
                // 可能还有包路径 foo.bar.Type
                while (check(TokenType.OPERATOR_DOT) && checkNext(TokenType.IDENTIFIER)) {
                    advance(); // .
                    typeBuilder.append('.').append(advance().text()); // identifier
                }
                paramTypeClass = context.resolveClass(typeBuilder.toString());
                if (paramTypeClass == null) {
                    paramTypeClass = Object.class; // 非基本类型暂用 Object 占位
                }
            }

            // 参数名
            if (!check(TokenType.IDENTIFIER)) {
                isLambda = false;
                break;
            }
            String paramName = advance().text();
            params.add(new LambdaNode.Parameter(paramName, paramTypeClass));

        } while (match(TokenType.DELIMITER_COMMA) && !check(TokenType.DELIMITER_RIGHT_PAREN));

        if (!isLambda) {
            return null;
        }

        // 必须 ) ->
        if (!match(TokenType.DELIMITER_RIGHT_PAREN)) {
            return null;
        }
        if (!match(TokenType.DELIMITER_ARROW)) {
            return null;
        }

        return buildLambdaBody(params);
    }

    /**
     * 尝试解析单参数简写 lambda {@code x -> expr}。
     * <p>
     * 调用时标识符已消费，箭头尚未消费。
     * </p>
     */
    protected ASTNode tryParseSingleParamLambda(String paramName, SourceLocation location)
            throws CythavaParseException {
        if (!match(TokenType.DELIMITER_ARROW)) {
            return new VariableNode.Builder().name(paramName).location(location).build(); // 不是
                                                                                                         // lambda，回退为变量
        }

        List<LambdaNode.Parameter> params = List.of(new LambdaNode.Parameter(paramName, null));
        return buildLambdaBody(params);
    }

    /**
     * 构建 Lambda 节点的 body 部分（箭头之后的内容）。
     * <p>
     * 如果箭头后是 {@code {} 则为块体，否则为表达式体。
     * 
    </p>
     */
    private ASTNode buildLambdaBody(List<LambdaNode.Parameter> params) throws CythavaParseException {
        SourceLocation location = createLocation();

        context.enterScope(ParseContext.ScopeKind.LAMBDA);
        for (LambdaNode.Parameter param : params) {
            if (param.type() != null) {
                // 显式类型标注：传递类型信息给符号表
                context.declareVariable(param.name(), param.type());
            } else {
                // 自动推断类型：用 inferred 标记，避免被当成 Object
                context.declareInferredVariable(param.name());
            }
        }

        ASTNode body;
        try {
            if (check(TokenType.DELIMITER_LEFT_BRACE)) {
                // 块体：消费 { 后循环解析，表达式用 ExprParser，声明语句用 StmtParser
                advance();
                List<ASTNode> stmts = new ArrayList<>();
                while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
                    if (match(TokenType.DELIMITER_SEMICOLON)) continue;
                    // 检测关键字开头的语句（auto/var/if/for 等）→ 委托 StmtParser
                    if (isStatementKeywordStart()) {
                        StmtParser stmtParser = new StmtParser(tokens, context, fileName);
                        stmtParser.setPosition(position);
                        stmts.add(stmtParser.parseNextStatement());
                        position = stmtParser.getPosition();
                    } else {
                        stmts.add(parseNextExpression());
                        match(TokenType.DELIMITER_SEMICOLON);
                    }
                }
                consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after lambda body");
                body = new BlockNode.Builder()
                        .statements(stmts)
                        .location(createLocation())
                        .build();
            } else {
                body = parseNextExpression();
            }
        } finally {
            context.exitScope();
        }

        LambdaNode lambda = (LambdaNode) new LambdaNode.Builder()
                .parameters(params)
                .body(body)
                .location(location)
                .build();
        // 标注 Lambda 类型：从 body 推断返回类型（无目标函数接口时用 Object 占位）
        JType bodyType = context.getType(body);
        if (bodyType != null) {
            annotate(lambda, bodyType);
        } else {
            annotate(lambda, Object.class);
        }
        return lambda;
    }

    /** 检查当前 token 是否为需要 StmtParser 处理的语句关键字。 */
    private boolean isStatementKeywordStart() {
        if (check(TokenType.IDENTIFIER) && Keywords.FUNCTION.equals(peek().text())) return true;
        return switch (peek().type()) {
            case KEYWORD_AUTO, KEYWORD_VAR, KEYWORD_IF, KEYWORD_WHILE,
                 KEYWORD_DO, KEYWORD_FOR, KEYWORD_RETURN, KEYWORD_BREAK,
                 KEYWORD_CONTINUE, KEYWORD_THROW, KEYWORD_TRY, KEYWORD_SWITCH,
                 KEYWORD_ASYNC -> true;
            default -> false;
        };
    }

    /**
     * 解析块表达式 {@code { statements }}（用于 lambda 体、async 体等）。
     */
    protected BlockNode parseBlockExpression() throws CythavaParseException {
        consume(TokenType.DELIMITER_LEFT_BRACE, "Expected '{'");
        List<ASTNode> statements = new ArrayList<>();

        while (!check(TokenType.DELIMITER_RIGHT_BRACE) && !isAtEnd()) {
            statements.add(parseNextExpression());
        }

        consume(TokenType.DELIMITER_RIGHT_BRACE, "Expected '}' after block");
        return (BlockNode) new BlockNode.Builder()
                .statements(statements)
                .location(createLocation())
                .build();
    }
}
