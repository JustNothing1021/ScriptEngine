package com.justnothing.engine.builtins;

import com.justnothing.engine.api.IOutputHandler;
import com.justnothing.engine.eval.Value;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Builtins {

    @FunctionalInterface
    public interface BuiltinFunction {
        Value call(List<Value> args);
    }

    private final BuiltinRegistry registry;
    private IOutputHandler outputHandler;

    public Builtins(BuiltinRegistry registry, IOutputHandler outputHandler) {
        this.registry = registry;
        this.outputHandler = outputHandler;
        registerAll();
    }

    public void setOutputHandler(IOutputHandler outputHandler) {
        this.outputHandler = outputHandler;
    }

    public void registerFunction(String name, BuiltinFunction function) {
        if (registry != null) {
            registry.register(name, function);
        }
    }

    public boolean hasFunction(String name) {
        return registry != null && registry.isKnown(name);
    }

    public BuiltinFunction getFunction(String name) {
        return registry != null ? registry.get(name) : null;
    }

    public Map<String, BuiltinFunction> getAllFunctions() {
        return registry != null ? registry.getAllFunctions() : Map.of();
    }

    /** 获取底层共享注册表（供 ParseContext / ScriptRunner 使用）。 */
    public BuiltinRegistry getRegistry() {
        return registry;
    }

    // ============================================================
    // Registration
    // ============================================================

    private void registerAll() {
        registerOutputFunctions();
        registerCollectionFunctions();
        registerReflectionFunctions();
        registerMathFunctions();
        registerTimeFunctions();
        registerUtilityFunctions();
        registerFunctionalInterfaceFunctions();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private IOutputHandler output() {
        return outputHandler != null ? outputHandler : fallbackOutput;
    }

    private static final IOutputHandler fallbackOutput = new IOutputHandler() {
        public void print(String s) { System.out.print(s); }
        public void println(String s) { System.out.println(s); }
        public void printf(String format, Object... args) { System.out.printf(format, args); }
        public void printError(String s) { System.err.print(s); }
        public void printlnError(String s) { System.err.println(s); }
        public void flush() { System.out.flush(); }
        public String readLine(String prompt) { return System.console() != null ? System.console().readLine(prompt) : null; }
        public String readPassword(String prompt) { return System.console() != null ? new String(System.console().readPassword(prompt)) : null; }
        public boolean isInteractive() { return System.console() != null; }
        public boolean isClosed() { return false; }
        public void close() {}
        public String getString() { return ""; }
        public void clear() {}
        public void printStackTrace(Throwable th) { th.printStackTrace(System.err); }
    };

    private static String formatValue(Object value) {
        if (value.getClass().isArray()) return Arrays.deepToString((Object[]) value);
        return Objects.toString(value);
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private static List<Integer> createRange(int start, int end, int step) {
        List<Integer> list = new ArrayList<>();
        if (step > 0) {
            for (int i = start; i < end; i += step) list.add(i);
        } else if (step < 0) {
            for (int i = start; i > end; i += step) list.add(i);
        }
        return list;
    }

    /** Unwrap all args to raw Java objects. */
    private static Object[] unwrap(List<Value> args) {
        Object[] raw = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            raw[i] = args.get(i) != null ? args.get(i).asJavaObject() : null;
        }
        return raw;
    }

    /** Wrap a raw value back to Value. */
    private static Value wrap(Object obj) {
        return Value.of(obj);
    }

    /** Call a function stored as Value.ObjectValue(Function<Value[], Value>). */
    private Object callFunctionValue(Object funcObj, Object... callArgs) {
        if (funcObj instanceof Function) {
            @SuppressWarnings("unchecked")
            Function<Value[], Value> f = (Function<Value[], Value>) funcObj;
            Value[] wrapped = new Value[callArgs.length];
            for (int i = 0; i < callArgs.length; i++) {
                wrapped[i] = Value.of(callArgs[i]);
            }
            Value result = f.apply(wrapped);
            return result != null ? result.asJavaObject() : null;
        }
        if (funcObj instanceof Method m) {
            try {
                return m.invoke(null, callArgs);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call method: " + e.getMessage());
            }
        }
        throw new RuntimeException("Not a callable function: " + funcObj);
    }

    private boolean callPredicate(Object predicate, Object item) {
        Object result = callFunctionValue(predicate, item);
        return result instanceof Boolean b ? b : result != null;
    }

    // ============================================================
    // Output functions
    // ============================================================

    private void registerOutputFunctions() {
        registerFunction("println", args -> {
            StringBuilder sb = new StringBuilder();
            Object[] raw = unwrap(args);
            for (int i = 0; i < raw.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(formatValue(raw[i]));
            }
            output().println(sb.toString());
            return Value.VoidValue.INSTANCE;
        });

        registerFunction("print", args -> {
            StringBuilder sb = new StringBuilder();
            Object[] raw = unwrap(args);
            for (int i = 0; i < raw.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(formatValue(raw[i]));
            }
            output().print(sb.toString());
            return Value.VoidValue.INSTANCE;
        });

        registerFunction("printf", args -> {
            if (args.isEmpty()) throw new RuntimeException("printf() requires at least 1 argument");
            String format = args.get(0).asString();
            Object[] params = Arrays.copyOfRange(unwrap(args), 1, args.size());
            output().printf(format, params);
            return Value.VoidValue.INSTANCE;
        });

        registerFunction("readLine", args -> {
            String prompt = args.isEmpty() ? "" : args.get(0).asString();
            return Value.of(output().readLine(prompt));
        });

        registerFunction("readPassword", args -> {
            String prompt = args.isEmpty() ? "" : args.get(0).asString();
            return Value.of(output().readPassword(prompt));
        });
    }

    // ============================================================
    // Collection functions
    // ============================================================

    private void registerCollectionFunctions() {
        registerFunction("range", args -> {
            int argCount = args.size();
            if (argCount == 1) {
                return wrap(createRange(0, toInt(unwrap(args)[0]), 1));
            } else if (argCount == 2) {
                return wrap(createRange(toInt(unwrap(args)[0]), toInt(unwrap(args)[1]), 1));
            } else if (argCount == 3) {
                return wrap(createRange(toInt(unwrap(args)[0]), toInt(unwrap(args)[1]), toInt(unwrap(args)[2])));
            }
            throw new RuntimeException("range() takes 1-3 arguments");
        });

        registerFunction("keys", args -> {
            if (args.size() != 1) throw new RuntimeException("keys() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            if (!(obj instanceof Map)) throw new RuntimeException("keys() argument must be a Map");
            return wrap(new ArrayList<>(((Map<?, ?>) obj).keySet()));
        });

        registerFunction("values", args -> {
            if (args.size() != 1) throw new RuntimeException("values() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            if (!(obj instanceof Map)) throw new RuntimeException("values() argument must be a Map");
            return wrap(new ArrayList<>(((Map<?, ?>) obj).values()));
        });

        registerFunction("entries", args -> {
            if (args.size() != 1) throw new RuntimeException("entries() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            if (!(obj instanceof Map)) throw new RuntimeException("entries() argument must be a Map");
            return wrap(new ArrayList<>(((Map<?, ?>) obj).entrySet()));
        });

        registerFunction("size", args -> {
            if (args.size() != 1) throw new RuntimeException("size() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            if (obj instanceof Collection<?> c) return wrap(c.size());
            if (obj instanceof Map<?, ?> m) return wrap(m.size());
            if (obj != null && obj.getClass().isArray()) return wrap(Array.getLength(obj));
            throw new RuntimeException("size() argument must be a Collection, Map, or Array");
        });

        registerFunction("contains", args -> {
            if (args.size() != 2) throw new RuntimeException("contains() requires exactly 2 arguments");
            Object col = args.get(0).asJavaObject();
            Object elem = args.get(1).asJavaObject();
            if (col instanceof Collection<?> c) return wrap(c.contains(elem));
            if (col instanceof Map<?, ?> m) return wrap(m.containsKey(elem));
            if (col != null && col.getClass().isArray()) {
                int len = Array.getLength(col);
                for (int i = 0; i < len; i++) {
                    Object item = Array.get(col, i);
                    if (item == null && elem == null) return wrap(true);
                    if (item != null && item.equals(elem)) return wrap(true);
                }
                return wrap(false);
            }
            throw new RuntimeException("contains() first argument must be Collection, Map, or Array");
        });

        registerFunction("split", args -> {
            if (args.size() != 2) throw new RuntimeException("split() requires exactly 2 arguments");
            return wrap(Arrays.asList(args.get(0).asString().split(args.get(1).asString())));
        });

        registerFunction("join", args -> {
            if (args.isEmpty() || args.size() > 2) throw new RuntimeException("join() requires 1 or 2 arguments");
            Object col = args.get(0).asJavaObject();
            String delimiter = args.size() > 1 ? args.get(1).asString() : "";
            StringBuilder sb = new StringBuilder();
            if (col instanceof Collection<?> c) {
                boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(delimiter);
                    sb.append(item != null ? item.toString() : "null");
                    first = false;
                }
            } else if (col != null && col.getClass().isArray()) {
                int len = Array.getLength(col);
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(delimiter);
                    Object item = Array.get(col, i);
                    sb.append(item != null ? item.toString() : "null");
                }
            } else {
                throw new RuntimeException("join() first argument must be a Collection or Array");
            }
            return wrap(sb.toString());
        });

        registerFunction("filter", args -> {
            if (args.size() != 2) throw new RuntimeException("filter() requires exactly 2 arguments: collection and predicate");
            Object col = args.get(0).asJavaObject();
            Object predicate = args.get(1).asJavaObject();
            List<Object> result = new ArrayList<>();
            if (col instanceof Collection<?> c) {
                for (Object item : c) {
                    if (callPredicate(predicate, item)) result.add(item);
                }
            } else if (col != null && col.getClass().isArray()) {
                int len = Array.getLength(col);
                for (int i = 0; i < len; i++) {
                    Object item = Array.get(col, i);
                    if (callPredicate(predicate, item)) result.add(item);
                }
            } else {
                throw new RuntimeException("filter() first argument must be a Collection or Array");
            }
            return wrap(result.toArray());
        });

        registerFunction("map", args -> {
            if (args.size() != 2) throw new RuntimeException("map() requires exactly 2 arguments: collection and mapper");
            Object col = args.get(0).asJavaObject();
            Object mapper = args.get(1).asJavaObject();
            List<Object> result = new ArrayList<>();
            if (col instanceof Collection<?> c) {
                for (Object item : c) {
                    result.add(callFunctionValue(mapper, item));
                }
            } else if (col != null && col.getClass().isArray()) {
                int len = Array.getLength(col);
                for (int i = 0; i < len; i++) {
                    result.add(callFunctionValue(mapper, Array.get(col, i)));
                }
            } else {
                throw new RuntimeException("map() first argument must be a Collection or Array");
            }
            return wrap(result.toArray());
        });

        registerFunction("reduce", args -> {
            if (args.size() < 2 || args.size() > 3) throw new RuntimeException("reduce() requires 2 or 3 arguments");
            Object col = args.get(0).asJavaObject();
            Object reducer = args.get(1).asJavaObject();
            Object acc = args.size() > 2 ? args.get(2).asJavaObject() : null;
            if (col instanceof Collection<?> c) {
                boolean first = (acc == null);
                for (Object item : c) {
                    if (first) { acc = item; first = false; }
                    else acc = callFunctionValue(reducer, acc, item);
                }
            } else if (col != null && col.getClass().isArray()) {
                int len = Array.getLength(col);
                boolean first = (acc == null);
                for (int i = 0; i < len; i++) {
                    Object item = Array.get(col, i);
                    if (first) { acc = item; first = false; }
                    else acc = callFunctionValue(reducer, acc, item);
                }
            } else {
                throw new RuntimeException("reduce() first argument must be a Collection or Array");
            }
            return wrap(acc);
        });
    }

    // ============================================================
    // Reflection functions
    // ============================================================

    private void registerReflectionFunctions() {
        registerFunction("typename", args -> {
            if (args.size() != 1) throw new RuntimeException("typename() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            return wrap(obj != null ? obj.getClass().getName() : "null");
        });

        registerFunction("typeof", args -> {
            if (args.size() != 1) throw new RuntimeException("typeof() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            return wrap(obj != null ? obj.getClass().getSimpleName() : "null");
        });

        registerFunction("typeOf", args -> {
            if (args.size() != 1) throw new RuntimeException("typeOf() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            return wrap(obj != null ? obj.getClass().getSimpleName() : "null");
        });

        registerFunction("isInstanceOf", args -> {
            if (args.size() != 2) throw new RuntimeException("isInstanceOf() requires exactly 2 arguments");
            Object obj = args.get(0).asJavaObject();
            Object className = args.get(1).asJavaObject();
            if (obj == null) return wrap(false);
            try {
                Class<?> targetClass = (className instanceof Class) ? (Class<?>) className : Class.forName(className.toString());
                return wrap(targetClass.isInstance(obj));
            } catch (Exception e) {
                throw new RuntimeException("Failed to find class: " + className);
            }
        });

        registerFunction("cast", args -> {
            if (args.size() != 2) throw new RuntimeException("cast() requires exactly 2 arguments");
            Object obj = args.get(0).asJavaObject();
            Object className = args.get(1).asJavaObject();
            if (obj == null) return Value.NullValue.INSTANCE;
            try {
                Class<?> targetClass = (className instanceof Class) ? (Class<?>) className : Class.forName(className.toString());
                if (!targetClass.isInstance(obj)) {
                    throw new RuntimeException("Cannot cast " + obj.getClass().getName() + " to " + targetClass.getName());
                }
                return wrap(targetClass.cast(obj));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Failed to find class: " + className);
            }
        });

        registerFunction("getField", args -> {
            if (args.size() != 2) throw new RuntimeException("getField() requires exactly 2 arguments");
            Object obj = args.get(0).asJavaObject();
            String fieldName = args.get(1).asString();
            if (obj == null) throw new RuntimeException("getField() first argument cannot be null");
            try {
                Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                return wrap(f.get(obj));
            } catch (Exception e) {
                throw new RuntimeException("Failed to get field: " + fieldName, e);
            }
        });

        registerFunction("setField", args -> {
            if (args.size() != 3) throw new RuntimeException("setField() requires exactly 3 arguments");
            Object obj = args.get(0).asJavaObject();
            String fieldName = args.get(1).asString();
            Object value = args.get(2).asJavaObject();
            if (obj == null) throw new RuntimeException("setField() first argument cannot be null");
            try {
                Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return Value.VoidValue.INSTANCE;
            } catch (Exception e) {
                throw new RuntimeException("Failed to set field: " + fieldName, e);
            }
        });

        registerFunction("invokeMethod", args -> {
            if (args.size() < 2) throw new RuntimeException("invokeMethod() requires at least 2 arguments");
            Object obj = args.get(0).asJavaObject();
            String methodName = args.get(1).asString();
            if (obj == null) throw new RuntimeException("invokeMethod() first argument cannot be null");
            List<Object> methodArgs = new ArrayList<>();
            for (int i = 2; i < args.size(); i++) {
                methodArgs.add(args.get(i).asJavaObject());
            }
            try {
                for (Method m : obj.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        m.setAccessible(true);
                        return wrap(m.invoke(obj, methodArgs.toArray()));
                    }
                }
                throw new RuntimeException("Method not found: " + methodName);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke method: " + methodName, e);
            }
        });

        registerFunction("analyze", args -> {
            if (args.size() != 1) throw new RuntimeException("analyze() requires exactly 1 argument");
            Object target = args.get(0).asJavaObject();
            StringBuilder r = new StringBuilder();
            if (target == null) {
                r.append("Target object is null\n");
                output().println(r.toString());
                return Value.VoidValue.INSTANCE;
            }
            Class<?> clazz = target.getClass();
            r.append("=== Object Analysis ===\n");
            r.append("String: ").append(target).append("\n");
            r.append("Class Name: ").append(clazz.getName()).append("\n");
            r.append("Simple Name: ").append(clazz.getSimpleName()).append("\n");
            r.append("Package: ").append(clazz.getPackage() != null ? clazz.getPackage().getName() : "None").append("\n");
            r.append("Is Array: ").append(clazz.isArray()).append("\n");
            r.append("Is Interface: ").append(clazz.isInterface()).append("\n");
            r.append("Is Primitive: ").append(clazz.isPrimitive()).append("\n\n");
            r.append("=== Fields ===\n");
            Field[] fields = clazz.getDeclaredFields();
            if (fields.length == 0) r.append("No fields\n");
            else {
                for (Field f : fields) {
                    r.append("  ").append(f);
                    try { f.setAccessible(true); r.append(" = ").append(formatValue(f.get(target))); }
                    catch (Exception e) { r.append(" = [Cannot access: ").append(e.getMessage()).append("]"); }
                    r.append("\n");
                }
            }
            r.append("Total Fields: ").append(fields.length).append("\n\n");
            r.append("=== Methods ===\n");
            Method[] methods = clazz.getDeclaredMethods();
            if (methods.length == 0) r.append("No methods\n");
            else for (Method m : methods) r.append("  ").append(m).append("\n");
            r.append("Total Methods: ").append(methods.length).append("\n");
            output().println(r.toString());
            return Value.VoidValue.INSTANCE;
        });
    }

    // ============================================================
    // Math functions
    // ============================================================

    private void registerMathFunctions() {
        registerFunction("random", args -> wrap(Math.random()));

        registerFunction("randint", args -> {
            if (args.size() != 2) throw new RuntimeException("randint() requires exactly 2 arguments");
            Object[] raw = unwrap(args);
            int min = toInt(raw[0]);
            int max = toInt(raw[1]);
            return wrap(min + (int) (Math.random() * (max - min + 1)));
        });

        registerFunction("abs", args -> {
            if (args.size() != 1) throw new RuntimeException("abs() requires exactly 1 argument");
            return wrap(Math.abs(toDouble(unwrap(args)[0])));
        });

        registerFunction("min", args -> {
            if (args.isEmpty()) throw new RuntimeException("min() requires at least 1 argument");
            Object[] raw = unwrap(args);
            double m = toDouble(raw[0]);
            for (int i = 1; i < raw.length; i++) m = Math.min(m, toDouble(raw[i]));
            return wrap(m);
        });

        registerFunction("max", args -> {
            if (args.isEmpty()) throw new RuntimeException("max() requires at least 1 argument");
            Object[] raw = unwrap(args);
            double m = toDouble(raw[0]);
            for (int i = 1; i < raw.length; i++) m = Math.max(m, toDouble(raw[i]));
            return wrap(m);
        });

        registerFunction("clamp", args -> {
            if (args.size() != 3) throw new RuntimeException("clamp() requires exactly 3 arguments");
            Object[] raw = unwrap(args);
            double val = toDouble(raw[0]), minVal = toDouble(raw[1]), maxVal = toDouble(raw[2]);
            return wrap(Math.max(minVal, Math.min(maxVal, val)));
        });
    }

    // ============================================================
    // Time functions
    // ============================================================

    private void registerTimeFunctions() {
        registerFunction("currentTimeMillis", args -> wrap(System.currentTimeMillis()));
        registerFunction("nanoTime", args -> wrap(System.nanoTime()));

        registerFunction("sleep", args -> {
            if (args.size() != 1) throw new RuntimeException("sleep() requires exactly 1 argument");
            long ms = toLong(unwrap(args)[0]);
            try { Thread.sleep(ms); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Value.VoidValue.INSTANCE;
        });
    }

    // ============================================================
    // Utility functions
    // ============================================================

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private void registerUtilityFunctions() {
        registerFunction("toStr", args -> {
            if (args.size() != 1) throw new RuntimeException("toStr() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            return wrap(obj != null ? obj.toString() : "null");
        });

        registerFunction("toInt", args -> {
            if (args.size() != 1) throw new RuntimeException("toInt() requires exactly 1 argument");
            return wrap(toInt(unwrap(args)[0]));
        });

        registerFunction("toDouble", args -> {
            if (args.size() != 1) throw new RuntimeException("toDouble() requires exactly 1 argument");
            return wrap(toDouble(unwrap(args)[0]));
        });

        registerFunction("toBool", args -> {
            if (args.size() != 1) throw new RuntimeException("toBool() requires exactly 1 argument");
            Object obj = args.get(0).asJavaObject();
            if (obj instanceof Boolean b) return wrap(b);
            return wrap(Boolean.parseBoolean(obj.toString()));
        });

        registerFunction("hex", args -> {
            if (args.size() != 1) throw new RuntimeException("hex() requires exactly 1 argument");
            return wrap(Integer.toHexString(toInt(unwrap(args)[0])));
        });

        registerFunction("bin", args -> {
            if (args.size() != 1) throw new RuntimeException("bin() requires exactly 1 argument");
            return wrap(Integer.toBinaryString(toInt(unwrap(args)[0])));
        });

        registerFunction("isNull", args -> {
            if (args.size() != 1) throw new RuntimeException("isNull() requires exactly 1 argument");
            return wrap(args.get(0).asJavaObject() == null);
        });

        registerFunction("nonNull", args -> {
            if (args.size() != 1) throw new RuntimeException("nonNull() requires exactly 1 argument");
            return wrap(args.get(0).asJavaObject() != null);
        });

        registerFunction("uuid", args -> wrap(UUID.randomUUID().toString()));

        registerFunction("parseInt", args -> {
            if (args.size() != 1) throw new RuntimeException("parseInt() requires exactly 1 argument");
            return wrap(Integer.parseInt(args.get(0).asString()));
        });

        registerFunction("parseDouble", args -> {
            if (args.size() != 1) throw new RuntimeException("parseDouble() requires exactly 1 argument");
            return wrap(Double.parseDouble(args.get(0).asString()));
        });

        registerFunction("parseLong", args -> {
            if (args.size() != 1) throw new RuntimeException("parseLong() requires exactly 1 argument");
            return wrap(Long.parseLong(args.get(0).asString()));
        });

        registerFunction("assert", args -> {
            if (args.isEmpty() || args.size() > 2) throw new RuntimeException("assert() requires 1 or 2 arguments");
            boolean cond = args.get(0).asJavaObject() instanceof Boolean b ? b : args.get(0).asJavaObject() != null;
            if (!cond) {
                String msg = args.size() > 1 ? args.get(1).asString() : "assertion failed";
                throw new RuntimeException("AssertionError: " + msg);
            }
            return Value.VoidValue.INSTANCE;
        });

        registerFunction("setPrintAST", args -> {
            boolean value = true;
            if (!args.isEmpty()) {
                Object raw = args.get(0).asJavaObject();
                value = raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
            }
            output().println("setPrintAST(" + value + ") — AST 打印模式已切换（此版本中不影响行为）");
            return wrap(value);
        });

        registerFunction("isPrintAST", args -> wrap(false));

        registerFunction("setupInteractiveInput", args -> {
            InputStream interactiveInputStream = new InputStream() {
                private byte[] buffer = null;
                private int pos = 0;
                private boolean eof = false;

                @Override
                public int read() {
                    if (eof) return -1;
                    if (buffer == null || pos >= buffer.length) {
                        String line = output().readLine("");
                        if (line == null) { eof = true; return -1; }
                        buffer = (line + "\n").getBytes();
                        pos = 0;
                    }
                    return buffer[pos++] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (b == null) throw new NullPointerException();
                    else if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
                    else if (len == 0) return 0;

                    int totalRead = 0;
                    while (totalRead < len) {
                        if (eof) break;
                        if (buffer == null || pos >= buffer.length) {
                            String line = output().readLine("");
                            if (line == null) { eof = true; break; }
                            buffer = (line + "\n").getBytes();
                            pos = 0;
                        }
                        int available = Math.min(buffer.length - pos, len - totalRead);
                        System.arraycopy(buffer, pos, b, off + totalRead, available);
                        pos += available;
                        totalRead += available;
                        if (pos >= buffer.length) break;
                    }
                    return totalRead > 0 ? totalRead : -1;
                }
            };

            System.setIn(interactiveInputStream);
            return wrap(true);
        });
    }

    // ============================================================
    // Functional interface conversion (asRunnable, asConsumer, etc.)
    // ============================================================

    private void registerFunctionalInterfaceFunctions() {
        registerFunction("asRunnable", args -> {
            if (args.size() != 1) throw new RuntimeException("asRunnable() requires one parameter");
            Object func = args.get(0).asJavaObject();
            return wrap(Proxy.newProxyInstance(
                Builtins.class.getClassLoader(),
                new Class[] { Runnable.class },
                    (proxy, method, params) -> switch (method.getName()) {
                        case "run" -> callFunctionValue(func);
                        case "toString" -> "Lambda[as=Runnable]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> Objects.equals(params[0], proxy);
                        case "getClass" -> Function.class;
                        default -> null;
                    }));
        });

        registerFunction("asConsumer", args -> {
            if (args.size() != 1) throw new RuntimeException("asConsumer() requires one parameter");
            Object func = args.get(0).asJavaObject();
            return wrap(Proxy.newProxyInstance(
                Builtins.class.getClassLoader(),
                new Class[] { Consumer.class },
                    (proxy, method, params) -> switch (method.getName()) {
                        case "accept" -> callFunctionValue(func, params[0]);
                        case "toString" -> "Lambda[as=Consumer]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> Objects.equals(params[0], proxy);
                        case "getClass" -> Function.class;
                        default -> null;
                    }));
        });

        registerFunction("asSupplier", args -> {
            if (args.size() != 1) throw new RuntimeException("asSupplier() requires one parameter");
            Object func = args.get(0).asJavaObject();
            return wrap(Proxy.newProxyInstance(
                Builtins.class.getClassLoader(),
                new Class[] { Supplier.class },
                    (proxy, method, params) -> switch (method.getName()) {
                        case "apply" -> callFunctionValue(func);
                        case "toString" -> "Lambda[as=Supplier]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> Objects.equals(params[0], proxy);
                        case "getClass" -> Supplier.class;
                        default -> null;
                    }));
        });

        registerFunction("asFunction", args -> {
            if (args.size() != 1) throw new RuntimeException("asFunction() requires one parameter");
            Object func = args.get(0).asJavaObject();
            return wrap(Proxy.newProxyInstance(
                Builtins.class.getClassLoader(),
                new Class[] { Function.class },
                (proxy, method, params) -> switch (method.getName()) {
                    case "apply" -> callFunctionValue(func, params[0]);
                    case "toString" -> "Lambda[as=Function]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> Objects.equals(params[0], proxy);
                    case "getClass" -> Function.class;
                    default -> null;
                }));
        });

        registerFunction("asPredicate", args -> {
            if (args.size() != 1) throw new RuntimeException("asPredicate() requires one parameter");
            Object func = args.get(0).asJavaObject();
            return wrap(Proxy.newProxyInstance(
                Builtins.class.getClassLoader(),
                new Class[] { Predicate.class },
                    (proxy, method, params) -> switch (method.getName()) {
                        case "test" -> callFunctionValue(func, params[0]);
                        case "toString" -> "Lambda[as=Predicate]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> Objects.equals(params[0], proxy);
                        case "getClass" -> Predicate.class;
                        default -> null;
                    }));
        });

        registerFunction("runLater", args -> {
            if (args.size() != 1) throw new RuntimeException("runLater() requires one parameter: Runnable");
            Object runnable = args.get(0).asJavaObject();
            Thread thread = new Thread(() -> {
                try {
                    callFunctionValue(runnable);
                } catch (Exception e) {
                    output().println("runLater error: " + e.getMessage());
                }
            });
            thread.start();
            return Value.VoidValue.INSTANCE;
        });
    }
}
