package com.justnothing.engine.security;

/**
 * 权限类型枚举，定义脚本执行时可控制的所有权限维度。
 *
 * <p>分为三大类：
 * <ul>
 *   <li><b>解释器级别</b>：类访问、方法调用、字段读写、实例创建、反射操作</li>
 *   <li><b>I/O 级别</b>：文件读/写/删、网络、外部命令执行</li>
 *   <li><b>系统级别</b>：线程/进程管理、系统退出、属性/环境变量、ClassLoader、Unsafe、Native</li>
 * </ul>
 */
public enum PermissionType {

    // ===== 解释器级别 =====

    /** 访问类（通过 Class.forName 或字面量引用）。 */
    CLASS_ACCESS("class.access", "访问类"),

    /** 动态加载类（通过 ClassLoader.defineClass 或 Dex 加载）。 */
    CLASS_LOAD("class.load", "动态加载类"),

    /** 调用方法。 */
    METHOD_CALL("method.call", "调用方法"),

    /** 反射方式调用方法（通过 Method.invoke 而非直接调用）。 */
    METHOD_REFLECTION("method.reflection", "反射调用方法"),

    /** 读取字段值。 */
    FIELD_READ("field.read", "读取字段"),

    /** 修改字段值。 */
    FIELD_WRITE("field.write", "修改字段"),

    /** 反射方式访问字段。 */
    FIELD_REFLECTION("field.reflection", "反射访问字段"),

    /** 创建新实例（new / Constructor.newInstance）。 */
    NEW_INSTANCE("newInstance", "创建新实例"),

    /** 创建数组。 */
    ARRAY_CREATE("array.create", "创建数组"),

    /** 通用反射操作（Class.getMethod, getDeclaredMethod 等）。 */
    REFLECTION("reflection", "反射操作"),

    // ===== I/O 级别 =====

    /** 读取文件。 */
    FILE_READ("file.read", "读取文件"),

    /** 写入文件。 */
    FILE_WRITE("file.write", "写入文件"),

    /** 删除文件。 */
    FILE_DELETE("file.delete", "删除文件"),

    /** 网络操作。 */
    NETWORK("network", "网络操作"),

    /** 执行外部命令（Runtime.exec 等）。 */
    EXEC("exec", "执行外部命令"),

    // ===== 系统级别 =====

    /** 创建线程。 */
    THREAD_CREATE("thread.create", "创建线程"),

    /** 修改线程状态（stop, suspend, setPriority 等）。 */
    THREAD_MODIFY("thread.modify", "修改线程"),

    /** 系统退出（System.exit）。 */
    SYSTEM_EXIT("system.exit", "系统退出"),

    /** 访问系统属性（System.getProperty/setProperty）。 */
    SYSTEM_PROPERTY("system.property", "访问系统属性"),

    /** 访问环境变量（System.getenv）。 */
    SYSTEM_ENV("system.env", "访问环境变量"),

    /** 访问/操作 ClassLoader。 */
    CLASS_LOADER("classLoader", "访问类加载器"),

    /** 访问 SecurityManager。 */
    SECURITY_MANAGER("securityManager", "访问安全管理器"),

    /** Unsafe 操作（sun.misc.Unsafe）。 */
    UNSAFE("unsafe", "Unsafe操作"),

    /** 调用 Native 方法（JNI）。 */
    NATIVE("native", "调用Native方法");

    private final String id;
    private final String description;

    PermissionType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }
}
