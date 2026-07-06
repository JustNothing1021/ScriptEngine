package com.justnothing.engine.ast;

import com.justnothing.engine.eval.Value;

@FunctionalInterface
public interface OperatorCallback {
    Value call(Value left, Value right);
}
