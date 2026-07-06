package com.justnothing.engine.lexer;

import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.exception.ParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 词法分析器（Lexer）
 * <p>
 * 将源代码字符串转换为token流。
 * </p>
 * 
 * @author JustNothing1021
 * @since 1.0.0
 */
public class Lexer {
    
    private final String source;
    private final String fileName;
    private final int length;
    private int position;
    private int line;
    private int column;
    private final List<Token> tokens;

    public Lexer(String source, String fileName) {
        this.source = source;
        this.fileName = fileName;
        this.length = source.length();
        this.position = 0;
        this.line = 1;
        this.column = 1;
        this.tokens = new ArrayList<>();
    }
    
    public List<Token> tokenize() throws ParseException {
        while (!isAtEnd()) {
            skipWhitespace();
            
            if (isAtEnd()) {
                break;
            }
            
            char c = peek();
            
            if (c == '/' && (peekNext() == '/' || peekNext() == '*')) {
                skipComment();
                continue;
            }
            
            if (Character.isDigit(c)) {
                readNumber();
            } else if ((c == '"' || c == 'f')
                    && position + 2 < length && source.charAt(position + 1) == '"'
                    && source.charAt(position + 2) == '"') {
                // Triple-quote multi-line string: """ or f"""
                boolean interpolated = (c == 'f');
                if (interpolated) advance(); // consume 'f'
                readMultiLineString(interpolated);
            } else if (c == 'f' && (peekNext() == '"' || peekNext() == '\'')) {
                advance();
                readInterpolatedString();
            } else if (c == '"' || c == '\'') {
                readString();
            } else if (Character.isLetter(c) || c == '_') {
                readIdentifier();
            } else if (isOperatorChar(c)) {
                readOperator();
            } else if (isDelimiterChar(c)) {
                readDelimiter();
            } else {
                throw error("Unexpected character: '" + c + "'", ErrorCode.LEXICAL_UNEXPECTED_CHARACTER);
            }
        }
        
        addToken(TokenType.EOF, createLocation());
        return tokens;
    }
    
    private boolean isAtEnd() {
        return position >= length;
    }
    
    private char peek() {
        if (isAtEnd()) {
            return '\0';
        }
        return source.charAt(position);
    }
    
    private char peekNext() {
        if (position + 1 >= length) {
            return '\0';
        }
        return source.charAt(position + 1);
    }
    
    private char advance() {
        char c = peek();
        position++;
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }
    
    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(peek())) {
            advance();
        }
    }
    
    private void skipComment() throws ParseException {
        advance();
        char c = advance();
        
        if (c == '/') {
            while (!isAtEnd() && peek() != '\n') {
                advance();
            }
        } else if (c == '*') {
            while (!isAtEnd()) {
                if (peek() == '*' && peekNext() == '/') {
                    advance();
                    advance();
                    return;
                }
                advance();
            }
            throw error("Unterminated multi-line comment");
        }
    }
    
    private boolean match(char expected) {
        if (isAtEnd() || peek() != expected) {
            return false;
        }
        advance();
        return true;
    }
    
    private void addToken(TokenType type, SourceLocation location) {
        addToken(type, null, location);
    }
    
    private void addToken(TokenType type, Object value, SourceLocation location) {
        tokens.add(new Token(type, value, location));
    }
    
    private SourceLocation createLocation() {
        return new SourceLocation(line, column, fileName);
    }

    private SourceLocation createLocation(int line, int column) {
        return new SourceLocation(line, column, fileName);
    }

    private ParseException error(String message, ErrorCode errorCode) {
        return new ParseException(
                message,
                createLocation(),
                errorCode
        );
    }

    private ParseException error(String message) {
        return error(message, ErrorCode.LEXICAL_ERROR);
    }
    
    private boolean isOperatorChar(char c) {
        return "+-*/%=!&|^~<>?:.".indexOf(c) >= 0;
    }
    
    private boolean isDelimiterChar(char c) {
        return ";,(){}[]@".indexOf(c) >= 0;
    }
    
    private void readNumber() {
        int startLine = line;
        int startColumn = column;
        StringBuilder sb = new StringBuilder();
        
        if (peek() == '0' && !isAtEnd()) {
            char next = peekNext();
            if (next == 'x' || next == 'X') {
                advance();
                advance();
                while (!isAtEnd() && isHexDigit(peek())) {
                    sb.append(advance());
                }
                String hexStr = sb.toString();
                SourceLocation location = createLocation(startLine, startColumn);
                try {
                    long value = Long.parseLong(hexStr, 16);
                    addToken(TokenType.LITERAL_INTEGER, value, location);
                } catch (NumberFormatException e) {
                    throw error("Invalid hexadecimal number: 0x" + hexStr, ErrorCode.LEXICAL_INVALID_NUMBER);
                }
                return;
            } else if (next == 'b' || next == 'B') {
                advance();
                advance();
                while (!isAtEnd() && (peek() == '0' || peek() == '1')) {
                    sb.append(advance());
                }
                String binStr = sb.toString();
                SourceLocation location = createLocation(startLine, startColumn);
                try {
                    long value = Long.parseLong(binStr, 2);
                    addToken(TokenType.LITERAL_INTEGER, value, location);
                } catch (NumberFormatException e) {
                    throw error("Invalid binary number: 0b" + binStr, ErrorCode.LEXICAL_INVALID_NUMBER);
                }
                return;
            } else if (next == 'o' || next == 'O') {
                advance();
                advance();
                while (!isAtEnd() && isOctalDigit(peek())) {
                    sb.append(advance());
                }
                String octStr = sb.toString();
                SourceLocation location = createLocation(startLine, startColumn);
                try {
                    long value = Long.parseLong(octStr, 8);
                    addToken(TokenType.LITERAL_INTEGER, value, location);
                } catch (NumberFormatException e) {
                    throw error("Invalid octal number: 0o" + octStr, ErrorCode.LEXICAL_INVALID_NUMBER);
                }
                return;
            }
        }
        
        while (!isAtEnd() && Character.isDigit(peek())) {
            sb.append(advance());
        }
        
        if (!isAtEnd() && peek() == '.') {
            char nextNext = position + 1 < length ? source.charAt(position + 1) : '\0';
            if (nextNext == '.') {

            } else if (Character.isDigit(nextNext)) {
                do {
                    sb.append(advance());
                } while (!isAtEnd() && Character.isDigit(peek()));
            }
        }
        
        if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
            sb.append(advance());
            if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
                sb.append(advance());
            }
            while (!isAtEnd() && Character.isDigit(peek())) {
                sb.append(advance());
            }
        }
        
        String numberStr = sb.toString();
        SourceLocation location = createLocation(startLine, startColumn);
        
        boolean isFloat = false;
        boolean isLong = false;
        boolean isDouble = false;
        boolean hasExponent = numberStr.contains("e") || numberStr.contains("E");
        
        if (!isAtEnd()) {
            char suffix = Character.toLowerCase(peek());
            if (suffix == 'f') {
                advance();
                isFloat = true;
            } else if (suffix == 'l') {
                advance();
                isLong = true;
            } else if (suffix == 'd') {
                advance();
                isDouble = true;
            }
        }
        
        if (isFloat) {
            try {
                float value = Float.parseFloat(numberStr);
                addToken(TokenType.LITERAL_DECIMAL, (double) value, location);
            } catch (NumberFormatException e) {
                throw error("Invalid float number: " + numberStr, ErrorCode.LEXICAL_INVALID_NUMBER);
            }
        } else if (isLong) {
            try {
                long value = Long.parseLong(numberStr);
                addToken(TokenType.LITERAL_LONG, value, location);
            } catch (NumberFormatException e) {
                throw error("Invalid long number: " + numberStr, ErrorCode.LEXICAL_INVALID_NUMBER);
            }
        } else if (isDouble || numberStr.contains(".") || hasExponent) {
            try {
                double value = Double.parseDouble(numberStr);
                addToken(TokenType.LITERAL_DECIMAL, value, location);
            } catch (NumberFormatException e) {
                throw error("Invalid decimal number: " + numberStr, ErrorCode.LEXICAL_INVALID_NUMBER);
            }
        } else {
            try {
                long longValue = Long.parseLong(numberStr);
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    addToken(TokenType.LITERAL_INTEGER, (int) longValue, location);
                } else {
                    addToken(TokenType.LITERAL_LONG, longValue, location);
                }
            } catch (NumberFormatException e) {
                throw error("Invalid integer number: " + numberStr, ErrorCode.LEXICAL_INVALID_NUMBER);
            }
        }
    }
    
    private boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
    
    private boolean isOctalDigit(char c) {
        return c >= '0' && c <= '7';
    }
    
    private void readString() {
        char quote = advance();
        int startLine = line;
        int startColumn = column;
        StringBuilder sb = new StringBuilder();
        
        while (!isAtEnd() && peek() != quote) {
            char c = peek();
            
            advance();
            if (c == '\\') {
                if (!isAtEnd()) {
                    char next = advance();
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        case '\"': sb.append('\"'); break;
                        case '$': sb.append('$'); break;
                        case 'x': {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < 2 && !isAtEnd(); i++) {
                                char h = peek();
                                if ((h >= '0' && h <= '9') || 
                                    (h >= 'a' && h <= 'f') || 
                                    (h >= 'A' && h <= 'F')) {
                                    hex.append(advance());
                                } else {
                                    break;
                                }
                            }
                            if (hex.length() > 0) {
                                try {
                                    int codePoint = Integer.parseInt(hex.toString(), 16);
                                    sb.append((char) codePoint);
                                } catch (NumberFormatException e) {
                                    sb.append("\\x").append(hex);
                                }
                            } else {
                                sb.append("\\x");
                            }
                            break;
                        }
                        case 'u': {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < 4 && !isAtEnd(); i++) {
                                char h = peek();
                                if ((h >= '0' && h <= '9') || 
                                    (h >= 'a' && h <= 'f') || 
                                    (h >= 'A' && h <= 'F')) {
                                    hex.append(advance());
                                } else {
                                    break;
                                }
                            }
                            if (hex.length() == 4) {
                                try {
                                    int codePoint = Integer.parseInt(hex.toString(), 16);
                                    sb.append((char) codePoint);
                                } catch (NumberFormatException e) {
                                    sb.append("\\u").append(hex);
                                }
                            } else {
                                sb.append("\\u").append(hex);
                            }
                            break;
                        }
                        case '0': case '1': case '2': case '3':
                        case '4': case '5': case '6': case '7': {
                            int octal = next - '0';
                            for (int i = 0; i < 2 && !isAtEnd(); i++) {
                                char d = peek();
                                if (d >= '0' && d <= '7') {
                                    octal = octal * 8 + (advance() - '0');
                                } else {
                                    break;
                                }
                            }
                            sb.append((char) octal);
                            break;
                        }
                        default:
                            sb.append('\\');
                            sb.append(next);
                            break;
                    }
                } else {
                    throw error("Unexpected end of input in escape sequence", ErrorCode.LEXICAL_INVALID_ESCAPE_SEQUENCE);
                }
            } else {
                sb.append(c);
            }
        }

        if (isAtEnd()) {
            throw error("Unterminated string literal", ErrorCode.LEXICAL_UNTERMINATED_STRING);
        }

        advance();
        SourceLocation location = createLocation(startLine, startColumn);

        if (quote == '\'') {
            if (sb.length() != 1) {
                throw error("Invalid character literal: must contain exactly one character", ErrorCode.LEXICAL_ERROR);
            }
            addToken(TokenType.LITERAL_CHAR, sb.charAt(0), location);
        } else {
            addToken(TokenType.LITERAL_STRING, sb.toString(), location);
        }
    }
    
    private void readInterpolatedString() {
        char quote = advance();
        int startLine = line;
        int startColumn = column;
        StringBuilder sb = new StringBuilder();
        List<Object> interpolatedParts = new ArrayList<>();
        
        while (!isAtEnd() && peek() != quote) {
            char c = peek();
            
            if (c == '$') {
                if (sb.length() > 0) {
                    interpolatedParts.add(sb.toString());
                    sb = new StringBuilder();
                }
                advance();
                
                if (peek() == '{') {
                    advance();
                    StringBuilder expr = new StringBuilder();
                    int braceCount = 1;
                    while (!isAtEnd() && braceCount > 0) {
                        char ec = advance();
                        if (ec == '{') braceCount++;
                        else if (ec == '}') braceCount--;
                        if (braceCount > 0) {
                            expr.append(ec);
                        }
                    }
                    interpolatedParts.add(new InterpolationPart(expr.toString().trim()));
                } else {
                    StringBuilder varName = new StringBuilder();
                    while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
                        varName.append(advance());
                    }
                    if (varName.length() > 0) {
                        interpolatedParts.add(new InterpolationPart(varName.toString()));
                    }
                }
                continue;
            }
            
            advance();
            if (c == '\\') {
                if (!isAtEnd()) {
                    char next = advance();
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        case '\"': sb.append('\"'); break;
                        case '$': sb.append('$'); break;
                        default:
                            sb.append('\\');
                            sb.append(next);
                            break;
                    }
                } else {
                    throw error("Unexpected end of input in escape sequence", ErrorCode.LEXICAL_INVALID_ESCAPE_SEQUENCE);
                }
            } else {
                sb.append(c);
            }
        }

        if (isAtEnd()) {
            throw error("Unterminated interpolated string literal", ErrorCode.LEXICAL_UNTERMINATED_STRING);
        }
        
        advance();
        SourceLocation location = createLocation(startLine, startColumn);
        
        if (sb.length() > 0) {
            interpolatedParts.add(sb.toString());
        }
        addToken(TokenType.LITERAL_INTERPOLATED_STRING, interpolatedParts, location);
    }

    /**
     * 读取三引号多行字符串（{@code """..."""} 或 {@code f"""..."""}）。
     * <p>
     * 特性：
     * <ul>
     *   <li>支持跨行内容，保留原始换行符</li>
     *   <li>自动缩进修剪（Python 风格）：以首行非空内容的缩进为基准，裁剪所有行的前导空白</li>
     *   <li>Raw 模式：不处理转义序列，内容原样保留</li>
     *   <li>插值模式（{@code f"""}）：支持 {@code ${expr}} 和 {@code $var}</li>
     * </ul>
     *
     * @param interpolated 是否为插值模式
     */
    private void readMultiLineString(boolean interpolated) throws ParseException {
        // 消耗开头的 """
        for (int i = 0; i < 3; i++) advance();
        int startLine = line;
        int startColumn = column;

        List<String> rawLines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();

        while (!isAtEnd()) {
            if (peek() == '"' && peekNext() == '"'
                    && position + 2 < length && source.charAt(position + 2) == '"') {
                // 找到 closing """
                break;
            }
            char c = advance();
            if (c == '\n') {
                rawLines.add(currentLine.toString());
                currentLine = new StringBuilder();
            } else {
                currentLine.append(c);
            }
        }
        // 最后一行（可能为空）
        rawLines.add(currentLine.toString());

        if (isAtEnd()) {
            throw error("Unterminated multi-line string literal", ErrorCode.LEXICAL_UNTERMINATED_STRING);
        }

        // 消耗 closing """
        for (int i = 0; i < 3; i++) advance();

        SourceLocation location = createLocation(startLine, startColumn);

        if (!interpolated) {
            // 单行内容（无换行）：直接使用原始文本，不做缩进修剪
            String result;
            if (rawLines.size() <= 1) {
                result = rawLines.isEmpty() ? "" : rawLines.get(0);
            } else {
                result = trimIndentation(rawLines);
            }
            addToken(TokenType.LITERAL_MULTI_LINE_STRING, result, location);
        } else {
            // 插值模式：逐行处理 ${expr} / $var
            List<Object> parts = parseMultiLineInterpolation(rawLines);
            addToken(TokenType.LITERAL_MULTI_LINE_INTERPOLATED_STRING, parts, location);
        }
    }

    /**
     * 多行字符串缩进修剪（Swift/Kotlin/Python 混合风格）。
     * <p>
     * 处理步骤：
     * <ol>
     *   <li>移除开头的空行（{@code """} 后紧跟的换行产生的空行）</li>
     *   <li>移除结尾的纯空白行（{@code """} 前的缩进产生的空行）</li>
     *   <li>计算剩余行的最小公共前导空白长度</li>
     *   <li>从每行裁剪该长度的前导空白</li>
     * </ol>
     */
    private static String trimIndentation(List<String> rawLines) {
        if (rawLines.isEmpty()) return "";

        // 1. 移除开头的空行
        int start = 0;
        while (start < rawLines.size() && rawLines.get(start).trim().isEmpty()) {
            start++;
        }
        if (start >= rawLines.size()) return ""; // 全是空行

        // 2. 移除结尾的纯空白行
        int end = rawLines.size() - 1;
        while (end > start && rawLines.get(end).trim().isEmpty()) {
            end--;
        }

        // 3. 计算最小公共缩进（仅基于非空行）
        int minIndent = Integer.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            String line = rawLines.get(i);
            if (line.trim().isEmpty()) continue; // 中间空行不参与缩进计算
            int indent = 0;
            while (indent < line.length()
                    && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        // 4. 裁剪 + 拼接
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String line = rawLines.get(i);
            if (i > start) sb.append('\n');
            if (line.trim().isEmpty()) {
                continue; // 中间空行保留为空行
            }
            if (line.length() > minIndent) {
                sb.append(line.substring(minIndent));
            }
        }
        return sb.toString();
    }

    /**
     * 解析多行插值字符串中的 {@code ${expr}} 和 {@code $var}。
     * 返回混合了 String 和 InterpolationPart 的列表。
     */
    private List<Object> parseMultiLineInterpolation(List<String> rawLines) {
        List<Object> parts = new ArrayList<>();
        StringBuilder pendingText = new StringBuilder();

        for (int lineIdx = 0; lineIdx < rawLines.size(); lineIdx++) {
            String line = rawLines.get(lineIdx);
            if (lineIdx > 0) pendingText.append('\n');

            int pos = 0;
            while (pos < line.length()) {
                char c = line.charAt(pos);
                if (c == '$' && pos + 1 < line.length()) {
                    char next = line.charAt(pos + 1);
                    if (next == '{') {
                        // ${expr} 形式
                        flushPending(parts, pendingText);
                        int braceStart = pos + 2;
                        int braceCount = 1;
                        int exprStart = braceStart;
                        while (exprStart < line.length() && braceCount > 0) {
                            if (line.charAt(exprStart) == '{') braceCount++;
                            else if (line.charAt(exprStart) == '}') braceCount--;
                            exprStart++;
                        }
                        String expr = line.substring(braceStart, exprStart - 1).trim();
                        parts.add(new InterpolationPart(expr));
                        pos = exprStart;
                        continue;
                    } else if (Character.isLetterOrDigit(next) || next == '_') {
                        // $var 形式
                        flushPending(parts, pendingText);
                        int varStart = pos + 1;
                        while (varStart < line.length()
                                && (Character.isLetterOrDigit(line.charAt(varStart)) || line.charAt(varStart) == '_')) {
                            varStart++;
                        }
                        parts.add(new InterpolationPart(line.substring(pos + 1, varStart)));
                        pos = varStart;
                        continue;
                    }
                }
                pendingText.append(c);
                pos++;
            }
        }
        flushPending(parts, pendingText);
        return parts;
    }

    private static void flushPending(List<Object> parts, StringBuilder pending) {
        if (pending.length() > 0) {
            parts.add(pending.toString());
            pending.setLength(0);
        }
    }

    public static class InterpolationPart {
        private final String expression;
        
        public InterpolationPart(String expression) {
            this.expression = expression;
        }
        
        public String getExpression() {
            return expression;
        }
    }
    
    private void readIdentifier() {
        int startLine = line;
        int startColumn = column;
        StringBuilder sb = new StringBuilder();
        
        while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(advance());
        }
        
        String text = sb.toString();
        SourceLocation location = createLocation(startLine, startColumn);
        
        if (TokenType.isKeyword(text)) {
            TokenType type = TokenType.getKeywordType(text);
            if (type == TokenType.KEYWORD_TRUE) {
                addToken(TokenType.LITERAL_BOOLEAN, true, location);
            } else if (type == TokenType.KEYWORD_FALSE) {
                addToken(TokenType.LITERAL_BOOLEAN, false, location);
            } else if (type == TokenType.KEYWORD_NULL) {
                addToken(TokenType.LITERAL_NULL, null, location);
            } else {
                addToken(type, text, location);
            }
        } else {
            addToken(TokenType.IDENTIFIER, text, location);
        }
    }
    
    private void readOperator() {
        int startLine = line;
        int startColumn = column;
        char c = advance();
        
        SourceLocation location = createLocation(startLine, startColumn);
        
        switch (c) {
            case '+':
                if (match('=')) addToken(TokenType.OPERATOR_PLUS_ASSIGN, location);
                else if (match('+')) addToken(TokenType.OPERATOR_INCREMENT, location);
                else addToken(TokenType.OPERATOR_PLUS, location);
                break;
            case '-':
                if (match('=')) addToken(TokenType.OPERATOR_MINUS_ASSIGN, location);
                else if (match('-')) addToken(TokenType.OPERATOR_DECREMENT, location);
                else if (match('>')) addToken(TokenType.DELIMITER_ARROW, location);
                else addToken(TokenType.OPERATOR_MINUS, location);
                break;
            case '*':
                if (match('=')) addToken(TokenType.OPERATOR_MULTIPLY_ASSIGN, location);
                else if (match('*')) addToken(TokenType.OPERATOR_POWER, location);
                else addToken(TokenType.OPERATOR_MULTIPLY, location);
                break;
            case '/':
                if (match('=')) addToken(TokenType.OPERATOR_DIVIDE_ASSIGN, location);
                else if (match('/')) addToken(TokenType.OPERATOR_INT_DIVIDE, location);
                else addToken(TokenType.OPERATOR_DIVIDE, location);
                break;
            case '%':
                if (match('=')) addToken(TokenType.OPERATOR_MODULO_ASSIGN, location);
                else if (match('%')) addToken(TokenType.OPERATOR_MATH_MODULO, location);
                else addToken(TokenType.OPERATOR_MODULO, location);
                break;
            case '=':
                if (match('=')) addToken(TokenType.OPERATOR_EQUAL, location);
                else if (match('>')) addToken(TokenType.DELIMITER_ARROW, location);  // C# 风格 lambda: =>
                else addToken(TokenType.OPERATOR_ASSIGN, location);
                break;
            case '!':
                if (match('=')) addToken(TokenType.OPERATOR_NOT_EQUAL, location);
                else if (match('!')) addToken(TokenType.OPERATOR_NOT_NULL, location);
                else addToken(TokenType.OPERATOR_LOGICAL_NOT, location);
                break;
            case '<':
                if (match('=')) {
                    if (match('>')) addToken(TokenType.OPERATOR_SPACESHIP, location);
                    else addToken(TokenType.OPERATOR_LESS_THAN_OR_EQUAL, location);
                } else if (match('<')) {
                    if (match('=')) addToken(TokenType.OPERATOR_LEFT_SHIFT_ASSIGN, location);
                    else addToken(TokenType.OPERATOR_LEFT_SHIFT, location);
                } else {
                    addToken(TokenType.OPERATOR_LESS_THAN, location);
                }
                break;
            case '>':
                if (match('=')) addToken(TokenType.OPERATOR_GREATER_THAN_OR_EQUAL, location);
                else if (match('>')) {
                    if (match('>')) {
                        if (match('=')) addToken(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT_ASSIGN, location);
                        else addToken(TokenType.OPERATOR_UNSIGNED_RIGHT_SHIFT, location);
                    } else if (match('=')) {
                        addToken(TokenType.OPERATOR_RIGHT_SHIFT_ASSIGN, location);
                    } else {
                        addToken(TokenType.OPERATOR_RIGHT_SHIFT, location);
                    }
                } else {
                    addToken(TokenType.OPERATOR_GREATER_THAN, location);
                }
                break;
            case '&':
                if (match('&')) addToken(TokenType.OPERATOR_LOGICAL_AND, location);
                else if (match('=')) addToken(TokenType.OPERATOR_BITWISE_AND_ASSIGN, location);
                else addToken(TokenType.OPERATOR_BITWISE_AND, location);
                break;
            case '|':
                if (match('|')) addToken(TokenType.OPERATOR_LOGICAL_OR, location);
                else if (match('>')) addToken(TokenType.OPERATOR_PIPELINE, location);
                else if (match('=')) addToken(TokenType.OPERATOR_BITWISE_OR_ASSIGN, location);
                else addToken(TokenType.OPERATOR_BITWISE_OR, location);
                break;
            case '^':
                if (match('=')) addToken(TokenType.OPERATOR_BITWISE_XOR_ASSIGN, location);
                else addToken(TokenType.OPERATOR_BITWISE_XOR, location);
                break;
            case '~':
                addToken(TokenType.OPERATOR_BITWISE_NOT, location);
                break;
            case '.':
                if (match('.')) {
                    if (match('<')) {
                        addToken(TokenType.OPERATOR_RANGE_EXCLUSIVE, location);
                    } else {
                        addToken(TokenType.OPERATOR_RANGE, location);
                    }
                } else {
                    addToken(TokenType.OPERATOR_DOT, location);
                }
                break;
            case ':':
                if (match(':')) addToken(TokenType.OPERATOR_DOUBLE_COLON, location);
                else if (match('=')) addToken(TokenType.OPERATOR_DECLARE_ASSIGN, location);
                else addToken(TokenType.OPERATOR_COLON, location);
                break;
            case '?':
                if (match('?')) {
                    if (match('=')) addToken(TokenType.OPERATOR_NULL_COALESCING_ASSIGN, location);
                    else addToken(TokenType.OPERATOR_NULL_COALESCING, location);
                } else if (match(':')) addToken(TokenType.OPERATOR_ELVIS, location);
                else if (match('=')) addToken(TokenType.OPERATOR_CONDITIONAL_ASSIGN, location);
                else if (match('.')) addToken(TokenType.OPERATOR_SAFE_DOT, location);
                else addToken(TokenType.OPERATOR_QUESTION, location);
                break;
            default:
                throw error("Unknown operator: '" + c + "'", ErrorCode.LEXICAL_UNEXPECTED_CHARACTER);
        }
    }

    private void readDelimiter() {
        int startLine = line;
        int startColumn = column;
        char c = advance();

        SourceLocation location = createLocation(startLine, startColumn);

        switch (c) {
            case ';':
                addToken(TokenType.DELIMITER_SEMICOLON, location);
                break;
            case ',':
                addToken(TokenType.DELIMITER_COMMA, location);
                break;
            case '(':
                addToken(TokenType.DELIMITER_LEFT_PAREN, location);
                break;
            case ')':
                addToken(TokenType.DELIMITER_RIGHT_PAREN, location);
                break;
            case '{':
                addToken(TokenType.DELIMITER_LEFT_BRACE, location);
                break;
            case '}':
                addToken(TokenType.DELIMITER_RIGHT_BRACE, location);
                break;
            case '[':
                addToken(TokenType.DELIMITER_LEFT_BRACKET, location);
                break;
            case ']':
                addToken(TokenType.DELIMITER_RIGHT_BRACKET, location);
                break;
            case '@':
                addToken(TokenType.DELIMITER_AT, location);
                break;
            default:
                throw error("Unknown delimiter: '" + c + "'", ErrorCode.LEXICAL_UNEXPECTED_CHARACTER);
        }
    }
}
