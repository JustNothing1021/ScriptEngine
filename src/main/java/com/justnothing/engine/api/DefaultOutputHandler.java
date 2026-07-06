package com.justnothing.engine.api;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class DefaultOutputHandler implements IOutputHandler {
    private final PrintStream out;
    private final PrintStream err;
    private final BufferedReader reader;
    private final List<String> outputBuffer;
    private final List<String> errorBuffer;
    private final boolean bufferOutput;
    private boolean closed;

    public DefaultOutputHandler() {
        this(System.out, System.err, true);
    }

    public DefaultOutputHandler(boolean bufferOutput) {
        this(System.out, System.err, bufferOutput);
    }

    public DefaultOutputHandler(PrintStream out, InputStream in) {
        this.out = out;
        this.err = out;
        this.reader = new BufferedReader(new InputStreamReader(in != null ? in : System.in));
        this.outputBuffer = new ArrayList<>();
        this.errorBuffer = new ArrayList<>();
        this.bufferOutput = true;
        this.closed = false;
    }

    public DefaultOutputHandler(PrintStream out, PrintStream err, boolean bufferOutput) {
        this.out = out;
        this.err = err;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.outputBuffer = new ArrayList<>();
        this.errorBuffer = new ArrayList<>();
        this.bufferOutput = bufferOutput;
        this.closed = false;
    }

    @Override
    public void print(String text) {
        checkClosed();
        out.print(text);
        if (bufferOutput) {
            outputBuffer.add(text);
        }
    }

    @Override
    public void println(String line) {
        checkClosed();
        out.println(line);
        if (bufferOutput) {
            outputBuffer.add(line + "\n");
        }
    }

    @Override
    public void printf(String format, Object... args) {
        checkClosed();
        String formatted = String.format(format, args);
        out.printf(format, args);
        if (bufferOutput) {
            outputBuffer.add(formatted);
        }
    }

    @Override
    public void printError(String text) {
        checkClosed();
        err.print(text);
        if (bufferOutput) {
            errorBuffer.add(text);
        }
    }

    @Override
    public void printlnError(String text) {
        checkClosed();
        err.println(text);
        if (bufferOutput) {
            errorBuffer.add(text + "\n");
        }
    }

    @Override
    public void printStackTrace(Throwable t) {
        checkClosed();
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String stackTrace = sw.toString();
        err.println(stackTrace);
        if (bufferOutput) {
            errorBuffer.add(stackTrace + "\n");
        }
    }

    @Override
    public void flush() {
        out.flush();
        err.flush();
    }

    @Override
    public void close() {
        closed = true;
        flush();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void clear() {
        outputBuffer.clear();
        errorBuffer.clear();
    }

    @Override
    public String getString() {
        return String.join("", outputBuffer);
    }

    @Override
    public String readLine(String prompt) {
        checkClosed();
        if (prompt != null && !prompt.isEmpty()) {
            out.print(prompt);
            out.flush();
        }
        try {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String readPassword(String prompt) {
        return readLine(prompt);
    }

    @Override
    public boolean isInteractive() {
        return true;
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("OutputHandler is closed");
        }
    }
}
