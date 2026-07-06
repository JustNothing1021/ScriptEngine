package com.justnothing.engine.codegen;

public interface ClassDefiner {
    Class<?> defineClass(String name, byte[] bytecode, ClassLoader parent) throws Exception;
}
