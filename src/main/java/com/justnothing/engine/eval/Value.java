package com.justnothing.engine.eval;

import com.justnothing.engine.exception.ErrorCode;
import com.justnothing.engine.exception.EvalException;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;

public abstract class Value {

    public abstract Object asJavaObject();

    public abstract String toString();

    public int asInt() { throw new EvalException("Not an int: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public long asLong() { throw new EvalException("Not a long: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public double asDouble() { throw new EvalException("Not a double: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public boolean asBoolean() { throw new EvalException("Not a boolean: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public char asChar() { throw new EvalException("Not a char: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public String asString() { throw new EvalException("Not a String: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public Object[] asArray() { throw new EvalException("Not an array: " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH); }
    public Value requiresNonNull() { if (asJavaObject() == null) throw new EvalException("Non-null assertion failed", ErrorCode.EVAL_NULL_POINTER); return this; }

    public boolean isTruthy() {
        throw new EvalException("Cannot evaluate truthiness of " + getClass().getSimpleName(), ErrorCode.EVAL_TYPE_MISMATCH);
    }

    public static Value of(Object obj) {
        if (obj == null) return NullValue.INSTANCE;
        if (obj instanceof Value v) return v;
        if (obj instanceof Integer i) return new IntValue(i);
        if (obj instanceof Long l) return new LongValue(l);
        if (obj instanceof Double d) return new DoubleValue(d);
        if (obj instanceof Float f) return new DoubleValue(f.doubleValue());
        if (obj instanceof Boolean b) return new BooleanValue(b);
        if (obj instanceof Character c) return new CharValue(c);
        if (obj instanceof Byte b) return new IntValue(b.intValue());
        if (obj instanceof Short s) return new IntValue(s.intValue());
        if (obj instanceof String s) return new StringValue(s);
        if (obj.getClass().isArray()) {
            return new ArrayValue(obj);
        }
        return new ObjectValue(obj);
    }

    public static class IntValue extends Value {
        private final int value;
        public IntValue(int value) { this.value = value; }
        public int getValue() { return value; }
        public int asInt() { return value; }
        public long asLong() { return value; }
        public double asDouble() { return value; }
        public String asString() { return Integer.toString(value); }
        public boolean isTruthy() { return value != 0; }
        public Object asJavaObject() { return value; }
        public String toString() { return Integer.toString(value); }
        public boolean equals(Object o) { return this == o || (o instanceof IntValue i && i.value == value); }
        public int hashCode() { return value; }
    }

    public static class LongValue extends Value {
        private final long value;
        public LongValue(long value) { this.value = value; }
        public long getValue() { return value; }
        public long asLong() { return value; }
        public double asDouble() { return value; }
        public int asInt() { return (int) value; }
        public String asString() { return Long.toString(value); }
        public boolean isTruthy() { return value != 0; }
        public Object asJavaObject() { return value; }
        public String toString() { return Long.toString(value); }
        public boolean equals(Object o) { return this == o || (o instanceof LongValue l && l.value == value); }
        public int hashCode() { return Long.hashCode(value); }
    }

    public static class DoubleValue extends Value {
        private final double value;
        public DoubleValue(double value) { this.value = value; }
        public double getValue() { return value; }
        public double asDouble() { return value; }
        public int asInt() { return (int) value; }
        public long asLong() { return (long) value; }
        public String asString() { return Double.toString(value); }
        public boolean isTruthy() { return value != 0.0 && !Double.isNaN(value); }
        public Object asJavaObject() { return value; }
        public String toString() { return Double.toString(value); }
        public boolean equals(Object o) { return this == o || (o instanceof DoubleValue d && Double.compare(d.value, value) == 0); }
        public int hashCode() { return Double.hashCode(value); }
    }

    public static class BooleanValue extends Value {
        private final boolean value;
        public BooleanValue(boolean value) { this.value = value; }
        public boolean getValue() { return value; }
        public boolean asBoolean() { return value; }
        public String asString() { return Boolean.toString(value); }
        public boolean isTruthy() { return value; }
        public Object asJavaObject() { return value; }
        public String toString() { return Boolean.toString(value); }
        public boolean equals(Object o) { return this == o || (o instanceof BooleanValue b && b.value == value); }
        public int hashCode() { return Boolean.hashCode(value); }
    }

    public static class CharValue extends Value {
        private final char value;
        public CharValue(char value) { this.value = value; }
        public char getValue() { return value; }
        public char asChar() { return value; }
        public int asInt() { return value; }
        public String asString() { return Character.toString(value); }
        public boolean isTruthy() { return value != 0; }
        public Object asJavaObject() { return value; }
        public String toString() { return Character.toString(value); }
        public boolean equals(Object o) { return this == o || (o instanceof CharValue c && c.value == value); }
        public int hashCode() { return value; }
    }

    public static class StringValue extends Value {
        private final String value;
        public StringValue(String value) { this.value = Objects.requireNonNull(value); }
        public String getValue() { return value; }
        public String asString() { return value; }
        public boolean isTruthy() { return !value.isEmpty(); }
        public Object asJavaObject() { return value; }
        public String toString() { return "\"" + value + "\""; }
        public boolean equals(Object o) { return this == o || (o instanceof StringValue s && s.value.equals(value)); }
        public int hashCode() { return value.hashCode(); }
    }

    public static class NullValue extends Value {
        public static final NullValue INSTANCE = new NullValue();
        private NullValue() {}
        public boolean isTruthy() { return false; }
        public Object asJavaObject() { return null; }
        public String asString() { return "null"; }
        public String toString() { return "null"; }
    }

    public static class VoidValue extends Value {
        public static final VoidValue INSTANCE = new VoidValue();
        private VoidValue() {}
        public Object asJavaObject() { return null; }
        public String asString() { return ""; }
        public String toString() { return "<void>"; }
    }

    public static class ObjectValue extends Value {
        private final Object value;
        public ObjectValue(Object value) { this.value = Objects.requireNonNull(value); }
        public Object getValue() { return value; }
        public boolean isTruthy() { return true; }
        public Object asJavaObject() { return value; }
        public String asString() { return value.toString(); }
        public String toString() { return value.toString(); }
        public boolean equals(Object o) { return this == o || (o instanceof ObjectValue ov && ov.value.equals(value)); }
        public int hashCode() { return value.hashCode(); }
    }

    public static class ArrayValue extends Value {
        private final Object rawArray;
        public ArrayValue(Object rawArray) {
            this.rawArray = Objects.requireNonNull(rawArray);
            if (!rawArray.getClass().isArray())
                throw new IllegalArgumentException("Not an array: " + rawArray.getClass());
        }
        public Object[] asArray() {
            if (rawArray instanceof Object[] oa) return oa;
            int len = Array.getLength(rawArray);
            Object[] result = new Object[len];
            for (int i = 0; i < len; i++) result[i] = Array.get(rawArray, i);
            return result;
        }
        public int length() { return Array.getLength(rawArray); }
        public Object asJavaObject() { return rawArray; }
        public String asString() { return Arrays.toString(asArray()); }
        public String toString() { return Arrays.toString(asArray()); }
        public boolean equals(Object o) {
            return this == o || (o instanceof ArrayValue a && Arrays.equals(a.asArray(), asArray()));
        }
        public int hashCode() { return Arrays.hashCode(asArray()); }
    }
}
