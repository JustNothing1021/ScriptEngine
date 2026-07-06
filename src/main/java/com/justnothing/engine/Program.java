package com.justnothing.engine;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.builtins.Builtins;
import com.justnothing.engine.eval.Value;

import java.util.Collections;
import java.util.List;

public class Program {

    private final ScriptRunner runner;
    private String source;
    private List<ASTNode> nodes;
    private boolean parsed;

    public Program() {
        this("");
    }

    public Program(String source) {
        this.runner = new ScriptRunner();
        this.source = source != null ? source : "";
        this.parsed = false;
    }

    public Program setSource(String source) {
        this.source = source != null ? source : "";
        this.parsed = false;
        this.nodes = null;
        return this;
    }

    public String getSource() {
        return source;
    }

    public Program parse() {
        this.nodes = runner.tryParse(source);
        this.parsed = true;
        return this;
    }

    public Object execute() {
        if (!parsed) parse();
        return runner.executeWithResult(source);
    }

    public List<ASTNode> getNodes() {
        if (!parsed) parse();
        if (nodes == null) return Collections.emptyList();
        return Collections.unmodifiableList(nodes);
    }

    public ASTNode getNode() {
        List<ASTNode> all = getNodes();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public String getCode() {
        if (!parsed) parse();
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTNode node : nodes) {
            sb.append(node.formatCode()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public String getCode(int indent) {
        if (!parsed) parse();
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTNode node : nodes) {
            sb.append(node.formatCode(indent)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    public Program addBuiltin(String name, Builtins.BuiltinFunction func) {
        runner.getEvalContext().addBuiltIn(name, func);
        return this;
    }

    public ScriptRunner getRunner() {
        return runner;
    }
}
