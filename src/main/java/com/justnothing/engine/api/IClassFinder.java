package com.justnothing.engine.api;

import java.util.List;

public interface IClassFinder {
    
    Class<?> findClass(String className);
    
    Class<?> findClass(String className, ClassLoader classLoader);
    
    Class<?> findClassWithImports(String className, ClassLoader classLoader, List<String> imports);
    
    Class<?> findClassOrFail(String className) throws ClassNotFoundException;
    
    Class<?> findClassOrFail(String className, ClassLoader classLoader) throws ClassNotFoundException;

    /** 清空类查找的黑名单缓存（找不到的类），新增 import 后应调用此方法以允许重新查找 */
    default void clearBlacklist() {}

    /** 清空所有类查找缓存（包括已找到的类和黑名单） */
    default void clearCache() {}
    
}
