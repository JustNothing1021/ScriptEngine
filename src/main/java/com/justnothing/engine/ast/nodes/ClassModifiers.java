package com.justnothing.engine.ast.nodes;


import java.util.ArrayList;
import java.util.List;

public class ClassModifiers {
    
    private boolean isPublic = false;
    private boolean isPrivate = false;
    private boolean isProtected = false;
    private boolean isStatic = false;
    private boolean isFinal = false;
    private boolean isAbstract = false;
    private boolean isNative = false;
    private boolean isSynchronized = false;
    
    public ClassModifiers() {
        // 不做操作
    }
    
    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
    
    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }
    
    public boolean isProtected() { return isProtected; }
    public void setProtected(boolean isProtected) { this.isProtected = isProtected; }
    
    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }
    
    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean isFinal) { this.isFinal = isFinal; }
    
    public boolean isAbstract() { return isAbstract; }
    public void setAbstract(boolean isAbstract) { this.isAbstract = isAbstract; }
    
    public boolean isNative() { return isNative; }
    public void setNative(boolean isNative) { this.isNative = isNative; }
    
    public boolean isSynchronized() { return isSynchronized; }
    public void setSynchronized(boolean isSynchronized) { this.isSynchronized = isSynchronized; }
    
    public String toModifierString() {
        List<String> mods = new ArrayList<>();
        if (isPublic) mods.add("public");
        if (isPrivate) mods.add("private");
        if (isProtected) mods.add("protected");
        if (isStatic) mods.add("static");
        if (isFinal) mods.add("final");
        if (isAbstract) mods.add("abstract");
        if (isNative) mods.add("native");
        if (isSynchronized) mods.add("synchronized");
        return String.join(" ", mods);
    }
    
    public int toAccessFlags() {
        int flags = 0;
        if (isPublic) flags |= 0x0001;
        if (isPrivate) flags |= 0x0002;
        if (isProtected) flags |= 0x0004;
        if (isStatic) flags |= 0x0008;
        if (isFinal) flags |= 0x0010;
        if (isSynchronized) flags |= 0x0020;
        if (isNative) flags |= 0x0100;
        if (isAbstract) flags |= 0x0400;
        return flags;
    }
    
    @Override
    public String toString() {
        return toModifierString();
    }
}
