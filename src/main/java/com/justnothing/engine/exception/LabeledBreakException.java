package com.justnothing.engine.exception;

public class LabeledBreakException extends RuntimeException {
    private final String label;

    public LabeledBreakException(String label) {
        super("Labeled break: " + label);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
