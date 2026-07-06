package com.justnothing.engine.repl;

/**
 * 类型推导测试用辅助类。
 * <p>
 * 提供各种字段、方法、重载组合，供 TypeInferenceTestCustom 解析器专项测试使用。
 * 所有成员都是 public 以便反射访问。
 */
public class TypeInferenceTestFixtures {

    // ==================== 字段 ====================

    public int intField = 42;
    public String stringField = "hello";
    public double doubleField = 3.14;
    public Object objectField = new Object();
    public int[] intArrayField = {1, 2, 3};

    // ==================== 无参方法（各返回类型） ====================

    public int getInt() { return 99; }
    public long getLong() { return 999L; }
    public double getDouble() { return 2.718; }
    public String getString() { return "world"; }
    public boolean getBoolean() { return true; }
    public Object getObject() { return new Object(); }
    public void doNothing() {}

    // ==================== 重载方法（参数类型区分） ====================

    /** 重载: int 参数 */
    public String overloaded(int x) { return "int:" + x; }
    /** 重载: double 参数 */
    public String overloaded(double x) { return "double:" + x; }
    /** 重载: String 参数 */
    public String overloaded(String s) { return "String:" + s; }
    /** 重载: Object 参数 */
    public String overloaded(Object o) { return "Object:" + o; }
    /** 重载: 两个参数 */
    public String overloaded(int a, int b) { return "int,int:" + a + "," + b; }

    // ==================== 链式调用（返回 this 或其他自定义类型） ====================

    public TypeInferenceTestFixtures chain() { return this; }
    public TypeInferenceTestFixtures setInt(int v) { this.intField = v; return this; }

    // ==================== 泛型相关（集合工厂） ====================

    public java.util.List<String> stringList() {
        return java.util.Arrays.asList("a", "b", "c");
    }

    public java.util.Map<String, Integer> stringIntMap() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("x", 1);
        return m;
    }

    // ==================== 嵌套类：继承测试 ====================

    public static class ParentFixture {
        public int parentInt = 100;
        public String parentMethod() { return "parent"; }
        public String overridden() { return "parent-overridden"; }
    }

    public static class ChildFixture extends ParentFixture {
        public int childInt = 200;
        public String childMethod() { return "child"; }
        @Override
        public String overridden() { return "child-overridden"; }
    }
}
