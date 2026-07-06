package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AnnotationNode extends ASTNode {
    
    private final String annotationName;
    private final Map<String, Object> values;
    private final boolean hasSingleValue;
    private final String singleValueKey;
    
    private AnnotationNode(String annotationName, Map<String, Object> values,
                          SourceLocation location) {
        super(location);
        this.annotationName = annotationName;
        this.values = values != null ? new LinkedHashMap<>(values) : Collections.emptyMap();
        this.hasSingleValue = false;
        this.singleValueKey = null;
    }
    
    public String getAnnotationName() {
        return annotationName;
    }
    
    public Map<String, Object> getValues() {
        return Collections.unmodifiableMap(values);
    }
    
    public boolean hasValues() {
        return !values.isEmpty();
    }
    
    public boolean hasSingleValue() {
        return hasSingleValue;
    }
    
    public String getSingleValueKey() {
        return singleValueKey;
    }
    
    public Object getValue(String key) {
        return values.get(key);
    }
    
    public Object getValue() {
        if (hasSingleValue && singleValueKey != null) {
            return values.get(singleValueKey);
        }
        return values.isEmpty() ? null : values.values().iterator().next();
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("AnnotationNode\n");
        sb.append(indent(indent + 1)).append("name: @").append(annotationName).append("\n");
        
        if (!values.isEmpty()) {
            sb.append(indent(indent + 1)).append("values:\n");
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                sb.append(indent(indent + 2))
                  .append(entry.getKey()).append(" = ")
                  .append(formatValue(entry.getValue())).append("\n");
            }
        }
        
        return sb.toString().stripTrailing();
    }
    
    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        if (value instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatValue(arr[i]));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Class<?>) {
            return ((Class<?>) value).getSimpleName();
        }
        return value.toString();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private String annotationName;
        private Map<String, Object> values;

        public Builder annotationName(String annotationName) {
            this.annotationName = annotationName;
            return this;
        }

        public Builder values(Map<String, Object> values) {
            this.values = values;
            return this;
        }

        @Override
        public AnnotationNode build() {
            return new AnnotationNode(annotationName, values != null ? new LinkedHashMap<>(values) : Collections.emptyMap(), location);
        }
    }
}
