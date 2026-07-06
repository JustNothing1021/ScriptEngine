package com.justnothing.engine.util;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合类加载器：按优先级顺序尝试多个 ClassLoader，适用于需要同时访问
 * 动态生成类（DCG/DexClassLoader）和目标应用类的场景。
 *
 * <p>典型用法：
 * <pre>
 *   CompositeClassLoader composite = new CompositeClassLoader(appClassLoader);
 *   composite.addFirst(dcgLoader);        // 动态生成的类优先
 *   // 查找时：dcgLoader → appClassLoader → parent chain
 * </pre>
 */
public class CompositeClassLoader extends ClassLoader {

    private final CopyOnWriteArrayList<ClassLoader> delegates = new CopyOnWriteArrayList<>();

    public CompositeClassLoader() {
        super(CompositeClassLoader.class.getClassLoader());
    }

    public CompositeClassLoader(ClassLoader primary) {
        this();
        if (primary != null) {
            delegates.add(primary);
        }
    }

    /** 在优先级最高位置插入一个 ClassLoader。 */
    public void addFirst(ClassLoader loader) {
        if (loader != null && !delegates.contains(loader)) {
            delegates.add(0, loader);
        }
    }

    /** 在优先级最低位置追加一个 ClassLoader。 */
    public void addLast(ClassLoader loader) {
        if (loader != null && !delegates.contains(loader)) {
            delegates.add(loader);
        }
    }

    /** 移除指定 ClassLoader。 */
    public boolean remove(ClassLoader loader) {
        return delegates.remove(loader);
    }

    /** 获取所有委托 ClassLoader（不可变视图）。 */
    public List<ClassLoader> getDelegates() {
        return Collections.unmodifiableList(delegates);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1) 检查是否已加载
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            if (resolve) resolveClass(loaded);
            return loaded;
        }

        // 2) 按优先级尝试所有委托 ClassLoader
        for (ClassLoader delegate : delegates) {
            try {
                Class<?> found = delegate.loadClass(name);
                if (resolve) resolveClass(found);
                return found;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个
            }
        }

        // 3) 最后委托给父 ClassLoader
        return super.loadClass(name, resolve);
    }

    @Override
    public String toString() {
        return "CompositeClassLoader[delegates=" + delegates.size() + "]";
    }
}
