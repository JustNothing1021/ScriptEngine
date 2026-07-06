package com.justnothing.engine.repl;

import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;

public class DebugParse {
    public static void main(String[] args) {
        String[] sources = {
            "Runnable r = () -> {}",
            "java.util.function.Consumer<String> c = s -> {}",
            "Consumer<String> c = System.out::println",
            "package test.foo;",
            "int operator+(int a, int b) { return a + b; }",
            "enum Color { RED, GREEN, BLUE }",
            "int doubleMe(int x) { return x * 2; }"
        };
        for (String source : sources) {
            ParseContext context = new ParseContext();
            context.setStrictMode(false);
            context.setStrictMode(false);
            try {
                Lexer lexer = new Lexer(source, "<test>");
                Parser parser = new Parser(lexer.tokenize(), context, "<test>");
                var nodes = parser.parse();
                // OK: '{source}' → {nodes.size()} node(s)
            } catch (CythavaParseException e) {
                // PARSE ERROR: '{source}' → {e.getMessage()}
            }
        }
    }
}
