package com.justnothing.engine.util;

import com.justnothing.engine.api.ClassResolver;
import com.justnothing.engine.api.IClassFinder;

import java.util.List;

public class DefaultClassFinder implements IClassFinder {

    ClassLoader classLoader;

    public DefaultClassFinder(ClassLoader cl) {
        classLoader = (cl != null ? cl : DefaultClassFinder.class.getClassLoader());
    }
    
    @Override
    public Class<?> findClass(String className) {
        return ClassResolver.findClass(className, classLoader);
    }
    
    @Override
    public Class<?> findClass(String className, ClassLoader classLoader) {
        return ClassResolver.findClass(className, classLoader);
    }
    
    @Override
    public Class<?> findClassWithImports(String className, ClassLoader classLoader, List<String> imports) {
        return ClassResolver.findClassWithImports(className, classLoader, imports);
    }
    
    @Override
    public Class<?> findClassOrFail(String className) throws ClassNotFoundException {
        return ClassResolver.findClassOrFail(className);
    }
    
    @Override
    public Class<?> findClassOrFail(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return ClassResolver.findClassOrFail(className, classLoader);
    }

    @Override
    public void clearBlacklist() {
        ClassResolver.clearBlacklist();
    }

    @Override
    public void clearCache() {
        ClassResolver.clearClassCache();
    }
}
