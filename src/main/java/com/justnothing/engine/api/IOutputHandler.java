package com.justnothing.engine.api;

public interface IOutputHandler {
    void print(String text);

    void println(String line);

    void printf(String format, Object... args);

    void printError(String text);

    void printlnError(String text);

    void printStackTrace(Throwable t);

    void flush();

    void close();

    boolean isClosed();

    void clear();

    String getString();
    
    String readLine(String prompt);

    String readPassword(String prompt);

    boolean isInteractive();
}
