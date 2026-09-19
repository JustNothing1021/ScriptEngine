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
    private boolean isVolatile = false;
    private boolean isTransient = false;
    private boolean isStrictfp = false;
    private boolean isSealed = false;
    private boolean isNonSealed = false;
    
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

    public boolean isVolatile() { return isVolatile; }
    public void setVolatile(boolean isVolatile) { this.isVolatile = isVolatile; }

    public boolean isTransient() { return isTransient; }
    public void setTransient(boolean isTransient) { this.isTransient = isTransient; }

    public boolean isStrictfp() { return isStrictfp; }
    public void setStrictfp(boolean isStrictfp) { this.isStrictfp = isStrictfp; }

    public boolean isSealed() { return isSealed; }
    public void setSealed(boolean isSealed) { this.isSealed = isSealed; }

    public boolean isNonSealed() { return isNonSealed; }
    public void setNonSealed(boolean isNonSealed) { this.isNonSealed = isNonSealed; }
    
    public String toModifierString() {
        List<String> mods = new ArrayList<>();
        if (isPublic) mods.add("public");
        if (isPrivate) mods.add("private");
        if (isProtected) mods.add("protected");
        if (isStatic) mods.add("static");
        if (isFinal) mods.add("final");
        if (isAbstract) mods.add("abstract");
        if (isSealed) mods.add("sealed");
        if (isNonSealed) mods.add("non-sealed");
        if (isNative) mods.add("native");
        if (isSynchronized) mods.add("synchronized");
        if (isVolatile) mods.add("volatile");
        if (isTransient) mods.add("transient");
        if (isStrictfp) mods.add("strictfp");
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
        if (isVolatile) flags |= 0x0040;
        if (isTransient) flags |= 0x0080;
        if (isNative) flags |= 0x0100;
        if (isAbstract) flags |= 0x0400;
        if (isStrictfp) flags |= 0x0800;
        return flags;
    }
    
    @Override
    public String toString() {
        return toModifierString();
    }
}
