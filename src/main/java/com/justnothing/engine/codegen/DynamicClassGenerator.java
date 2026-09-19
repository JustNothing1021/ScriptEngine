package com.justnothing.engine.codegen;

import com.justnothing.engine.api.ClassResolver;
import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.nodes.ClassDeclarationNode;
import com.justnothing.engine.ast.nodes.ClassModifiers;
import com.justnothing.engine.ast.nodes.ClassReferenceNode;
import com.justnothing.engine.ast.nodes.ConstructorDeclarationNode;
import com.justnothing.engine.ast.nodes.FieldDeclarationNode;
import com.justnothing.engine.ast.nodes.LiteralNode;
import com.justnothing.engine.ast.nodes.MethodDeclarationNode;
import com.justnothing.engine.ast.nodes.ParameterNode;
import com.justnothing.engine.eval.CustomClassExecutor;
import com.justnothing.engine.util.DescriptorUtils;
import com.justnothing.engine.util.StandardClassDefiner;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DynamicClassGenerator {

    private static final int CLASS_VERSION = Opcodes.V1_8;

    private static volatile ClassDefiner defaultClassDefiner = new StandardClassDefiner();
    private static volatile boolean defaultClassDefinerCustomized = false;

    public static void setDefaultClassDefiner(ClassDefiner definer) {
        if (definer != null) {
            defaultClassDefiner = definer;
            defaultClassDefinerCustomized = true;
        }
    }

    public static ClassDefiner getDefaultClassDefiner() {
        return defaultClassDefiner;
    }

    // CustomClassExecutor.execute(className, methodName, descriptor, instance, args)
    private static final String EXECUTE_SIGNATURE = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String EXECUTE_CLASS = "com/justnothing/engine/eval/CustomClassExecutor";
    private static final String EXECUTE_METHOD = "execute";
    private static final String OBJECT = "java/lang/Object";
    private static final String SHORT = "java/lang/Short";
    private static final String INTEGER = "java/lang/Integer";
    private static final String LONG = "java/lang/Long";
    private static final String FLOAT = "java/lang/Float";
    private static final String DOUBLE = "java/lang/Double";
    private static final String BOOLEAN = "java/lang/Boolean";
    private static final String CHARACTER = "java/lang/Character";
    private static final String BYTE = "java/lang/Byte";
    private static final String NUMBER = "java/lang/Number";


    private static final String INIT = "<init>";
    private static final String CLINIT = "<clinit>";
    private static final String VOID_NO_ARGS = "()V";

    private static final String VALUE_OF = "valueOf";
    private static final String VALUE_OF_SHORT_SIG = "(S)Ljava/lang/Short;";
    private static final String VALUE_OF_INTEGER_SIG = "(I)Ljava/lang/Integer;";
    private static final String VALUE_OF_LONG_SIG = "(J)Ljava/lang/Long;";
    private static final String VALUE_OF_FLOAT_SIG = "(F)Ljava/lang/Float;";
    private static final String VALUE_OF_DOUBLE_SIG = "(D)Ljava/lang/Double;";
    private static final String VALUE_OF_BOOLEAN = "(Z)Ljava/lang/Boolean;";
    private static final String VALUE_OF_CHARACTER_SIG = "(C)Ljava/lang/Character;";
    private static final String VALUE_OF_BYTE_SIG = "(B)Ljava/lang/Byte;";

    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();
    /**
     * 已生成类的方法体（{@code 类名#方法名#描述符} → AST），供 {@link CustomClassExecutor}
     * 解释执行。
     * <p><b>刻意放在实例上而不是静态表里</b>：这个 Loader 生成的全部类都由本实例持有，
     * 方法体跟着实例走，生命周期天然对齐 —— 静态表既会跨运行泄漏 AST（条目永不回收），
     * 又会让"同名脚本类"在两次运行之间互相覆盖方法体。
     */
    private final Map<String, MethodDeclarationNode> methodBodies = new ConcurrentHashMap<>();
    private final Loader loader;
    private ClassDefiner classDefiner;
    private boolean delegateToExecutor;
    private Map<String, ClassDeclarationNode> classDeclarations;

    public DynamicClassGenerator(ClassLoader parentLoader) {
        this.loader = new Loader(parentLoader);
        // 新 Loader 意味着接下来可以定义出新的脚本类。类解析缓存只按类名索引、不含 Loader，
        // 不清掉会跨运行命中上一次同名的脚本类（详见 ClassResolver#clearClassCache）
        ClassResolver.clearClassCache();
        // 未显式指定 ClassDefiner 时使用代理：每次 defineClass 时动态读取 defaultClassDefiner
        // 这样构造之后再 setDefaultClassDefiner() 也能生效
        this.classDefiner = new LazyDefaultDefiner(this.loader);
    }

    public DynamicClassGenerator(ClassLoader parentLoader, ClassDefiner classDefiner) {
        this.loader = new Loader(parentLoader);
        ClassResolver.clearClassCache();
        // 显式指定的 classDefiner 直接使用（不经过代理）
        this.classDefiner = classDefiner != null ? classDefiner : this.loader;
    }

    public void setClassDefiner(ClassDefiner classDefiner) {
        this.classDefiner = classDefiner != null ? classDefiner : this.loader;
    }

    public ClassLoader getLoader() {
        return loader;
    }

    public void setDelegateToExecutor(boolean delegate) {
        this.delegateToExecutor = delegate;
    }

    public void setClassDeclarations(Map<String, ClassDeclarationNode> classDeclarations) {
        this.classDeclarations = classDeclarations;
    }

    public Class<?> generate(ClassDeclarationNode classDecl) {
        String name = classDecl.getClassName();
        return cache.computeIfAbsent(name, k -> doGenerate(classDecl));
    }

    public Class<?> generateAnonymous(String name, ClassDeclarationNode classDecl) {
        return cache.computeIfAbsent(name, k -> doGenerateAnonymous(name, classDecl));
    }

    /**
     * 生成匿名类，指定父类构造器的参数类型。
     * <p>当匿名类声明为 {@code new SuperType(arg1, arg2) { ... }} 时，
     * 必须用此重载传入父类构造器签名，否则生成的子类构造器参数列表不匹配。
     *
     * @param name            匿名类名
     * @param classDecl       类声明节点（superClass 已设置）
     * @param ctorSuperArgTypes 父类构造器参数类型列表，null 等同于无参
     */
    public Class<?> generateAnonymous(String name, ClassDeclarationNode classDecl,
                                       List<Class<?>> ctorSuperArgTypes) {
        String key = name + "$" + (ctorSuperArgTypes != null ? ctorSuperArgTypes.size() : "0");
        return cache.computeIfAbsent(key, k -> {
            preprocessParentClass(classDecl);
            return doGenerateInternal(name, classDecl, ctorSuperArgTypes, null);
        });
    }

    public boolean hasGenerated(String className) {
        return cache.containsKey(className);
    }

    public Class<?> getGenerated(String className) {
        return cache.get(className);
    }

    /** 登记由 {@link CustomClassExecutor} 解释执行的方法体（生成期调用）。 */
    private void registerMethodBody(String className, String methodName, String descriptor,
                                    MethodDeclarationNode decl) {
        methodBodies.put(methodKey(className, methodName, descriptor), decl);
    }

    /**
     * 查找方法体，供 {@link CustomClassExecutor#execute} 使用。
     *
     * @param paramCount 参数个数，精确描述符匹配不上时用于回退查找
     * @return 方法体 AST，未登记时返回 null
     */
    public MethodDeclarationNode findMethodBody(String className, String methodName,
                                                String descriptor, int paramCount) {
        MethodDeclarationNode exact = methodBodies.get(methodKey(className, methodName, descriptor));
        if (exact != null) return exact;

        // 回退：部分调用场景没有精确描述符，按"类名 + 方法名 + 参数个数"匹配
        String prefix = className + "#" + methodName + "#";
        for (Map.Entry<String, MethodDeclarationNode> e : methodBodies.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            List<ParameterNode> params = e.getValue().getParameters();
            if ((params != null ? params.size() : 0) == paramCount) return e.getValue();
        }
        return null;
    }

    private static String methodKey(String className, String methodName, String descriptor) {
        return className + "#" + methodName + "#" + descriptor;
    }

    private Class<?> doGenerate(ClassDeclarationNode classDecl) {
        preprocessParentClass(classDecl);
        return doGenerateInternal(classDecl.getClassName(), classDecl, null, null);
    }

    private Class<?> doGenerateAnonymous(String name, ClassDeclarationNode classDecl) {
        preprocessParentClass(classDecl);
        return doGenerateInternal(name, classDecl, null, null);
    }

    private void preprocessParentClass(ClassDeclarationNode classDecl) {
        if (classDeclarations == null) return;
        ClassReferenceNode superRef = classDecl.getSuperClass();
        if (superRef == null) return;
        String superName = superRef.getOriginalTypeName();
        if (superName == null || superName.equals("java.lang.Object") || cache.containsKey(superName)) return;
        ClassDeclarationNode superDecl = classDeclarations.get(superName);
        if (superDecl != null) {
            preprocessParentClass(superDecl);
            generate(superDecl);
        }
    }

    private Class<?> doGenerateInternal(String name, ClassDeclarationNode classDecl,
                                         // FIXME: 这下面的两个参数永远是null
                                         List<Class<?>> ctorSuperArgTypes,
                                         List<ParameterNode> ctorExtraParams) {
        String internalName = DescriptorUtils.toInternalName(name);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        int access = Opcodes.ACC_PUBLIC;
        if (classDecl.isInterface()) {
            access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        }
        String superName = resolveSuperInternalName(classDecl);
        cw.visit(CLASS_VERSION, access, internalName, null, superName, null);

        // ★ 泛型类型参数列表（用于类型擦除：T → Object）
        List<String> typeParams = classDecl.getTypeParameters();

        // 字段
        for (FieldDeclarationNode field : classDecl.getFields()) {
            addField(cw, field, typeParams);
        }

        // 构造器
        if (!classDecl.isInterface()) {
            if (ctorSuperArgTypes != null) {
                addAnonymousConstructor(cw, name, superName, classDecl.getFields(),
                        ctorSuperArgTypes, ctorExtraParams);
            } else if (classDecl.getConstructors().isEmpty()) {
                addDefaultConstructor(cw, internalName, superName, classDecl.getFields());
            } else {
                for (ConstructorDeclarationNode ctor : classDecl.getConstructors()) {
                    addConstructorWithBody(cw, ctor, name, superName, classDecl.getFields());
                }
            }
        }

        // 静态字段初始化
        if (hasStaticLiteralFields(classDecl.getFields())) {
            addStaticInitializer(cw, name, classDecl.getFields());
        }

        // 方法
        for (MethodDeclarationNode method : classDecl.getMethods()) {
            if (delegateToExecutor) {
                String desc = buildDescriptor(method);
                addDelegateMethod(cw, method, name, classDecl.isInterface());
                registerMethodBody(name, method.getMethodName(), desc, method);
            } else {
                addEmptyMethod(cw, method, classDecl.isInterface());
            }
        }

        cw.visitEnd();

        byte[] bytecode = cw.toByteArray();
        try {
            return classDefiner.defineClass(name, bytecode, loader.getParent());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to define class: " + name, e);
        }
    }

    private static String resolveSuperInternalName(ClassDeclarationNode decl) {
        ClassReferenceNode superRef = decl.getSuperClass();
        if (superRef != null) {
            // 优先使用解析期的全限定类名
            Class<?> resolved = superRef.getResolvedClass();
            if (resolved != null) {
                return DescriptorUtils.toInternalName(resolved.getName());
            }
            String superName = superRef.getOriginalTypeName();
            if (superName != null && !superName.equals(java.lang.Object.class.getName())) {
                return DescriptorUtils.toInternalName(superName);
            }
        }
        return OBJECT;
    }

    // ==================== 字段 ====================

    private static void addField(ClassWriter cw, FieldDeclarationNode field, List<String> typeParams) {
        String fieldName = field.getFieldName();
        // ★ 泛型擦除：如果字段类型是泛型参数（T, K 等），替换为 Object
        ClassReferenceNode fieldType = eraseTypeParameter(field.getType(), typeParams);
        String descriptor = DescriptorUtils.fieldDescriptor(fieldType);

        int mods = memberAccessFlags(field.getModifiers());

        // static final 字面量 → ConstantValue 属性
        Object constVal = extractConstantValue(field, fieldType);
        cw.visitField(mods, fieldName, descriptor, null, constVal).visitEnd();
    }

    /**
     * 计算字段 / 方法 / 构造器的访问标志。
     * <p>
     * 脚本成员的访问控制一律按 public 处理，理由是引擎自身的成员解析完全依赖反射
     * （{@code getMethods()} / {@code getField()}），宿主也要能直接访问脚本类成员 ——
     * 真正的 private 会让类内 {@code this.f()} 都解析不到。
     * </p>
     * <p>
     * 因此先清掉显式的 private/protected 位再置 public。绝不能只做
     * {@code mods |= ACC_PUBLIC}：{@code private int x;} 会得到 {@code 0x0003}
     * （private|public），JVM 直接抛 {@code ClassFormatError: Illegal modifiers}。
     * </p>
     */
    private static int memberAccessFlags(ClassModifiers modifiers) {
        int flags = modifiers != null ? modifiers.toAccessFlags() : 0;
        flags &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        return flags | Opcodes.ACC_PUBLIC;
    }

    /**
     * 泛型类型擦除：如果类型的原始名称匹配某个泛型参数（T, K, V 等），
     * 返回一个指向 Object 的 ClassReferenceNode；否则原样返回。
     */
    private static ClassReferenceNode eraseTypeParameter(ClassReferenceNode typeRef, List<String> typeParams) {
        if (typeRef == null || typeParams.isEmpty() || typeRef.getResolvedClass() != null) return typeRef;
        String originalName = typeRef.getOriginalTypeName();
        if (originalName != null && typeParams.contains(originalName)) {
            // 泛型参数 → 擦除为 Object
            return ClassReferenceNode.of("java.lang.Object", Object.class, true, typeRef.getLocation());
        }
        return typeRef;
    }

    /**
     * 提取可写入 ConstantValue 属性的常量。
     * <p>
     * 字面量自身的类型未必等于字段声明类型（{@code static final long L = 30;} 里的 {@code 30}
     * 是 int）。JVMS §4.7.2 要求 {@code J} 字段必须配 CONSTANT_Long、{@code D} 必须配
     * CONSTANT_Double，类型不一致会抛
     * {@code ClassFormatError: Inconsistent constant value type}，所以这里先按字段类型转换。
     * </p>
     */
    private static Object extractConstantValue(FieldDeclarationNode field, ClassReferenceNode fieldType) {
        var mods = field.getModifiers();
        if (mods == null || !mods.isStatic() || !mods.isFinal()) return null;
        if (!(field.getInitialValue() instanceof LiteralNode lit)) return null;
        Object val = coerceToFieldType(lit.getValue(), fieldType);
        // 只有 JVMS 允许出现在 ConstantValue 里的类型才写成常量属性；
        // boolean / char 不在其中，交给 <clinit> 赋值
        if (val instanceof Integer || val instanceof Long
                || val instanceof Float || val instanceof Double
                || val instanceof String) {
            return val;
        }
        return null;
    }

    /**
     * 把字面量的原始值转换成字段声明类型对应的值（如 {@code long a = 30;} 的 {@code 30} → {@code 30L}）。
     * <p>
     * 类型无法转换（非数值字面量、字段类型未解析等）时原样返回，由调用方按原值处理。
     * </p>
     */
    private static Object coerceToFieldType(Object value, ClassReferenceNode fieldType) {
        if (value == null || fieldType == null) return value;
        Class<?> target = fieldType.getResolvedClass();
        if (target == null || !target.isPrimitive() || target == boolean.class) return value;
        if (!(value instanceof Number) && !(value instanceof Character)) return value;

        long asLong = value instanceof Character c ? c.charValue() : ((Number) value).longValue();
        double asDouble = value instanceof Character c ? c.charValue() : ((Number) value).doubleValue();
        if (target == int.class) return (int) asLong;
        if (target == byte.class) return (int) (byte) asLong;
        if (target == short.class) return (int) (short) asLong;
        if (target == char.class) return (int) (char) asLong;
        if (target == long.class) return asLong;
        if (target == float.class) return (float) asDouble;
        if (target == double.class) return asDouble;
        return value;
    }

    private static boolean hasStaticLiteralFields(List<FieldDeclarationNode> fields) {
        for (FieldDeclarationNode f : fields) {
            var mods = f.getModifiers();
            if (mods != null && mods.isStatic() && f.getInitialValue() instanceof LiteralNode) {
                return true;
            }
        }
        return false;
    }

    // ==================== 构造函数 ====================

    private static void addDefaultConstructor(ClassWriter cw, String internalName,
                                               String superName,
                                               List<FieldDeclarationNode> fields) {
        // 尝试标准无参构造器；如果父类没有可访问的无参构造器，fallback 到任意可用构造器
        if (!tryAddDefaultConstructor(cw, internalName, superName, fields)) {
            addFallbackConstructor(cw, internalName, superName, fields);
        }
    }

    /**
     * 尝试添加标准无参构造器。返回 false 表示父类无可访问的无参构造器。
     */
    private static boolean tryAddDefaultConstructor(ClassWriter cw, String internalName,
                                                    String superName,
                                                    List<FieldDeclarationNode> fields) {
        try {
            Class<?> superClass = Class.forName(DescriptorUtils.toClassName(superName));
            // getConstructor(): 查找 public 构造器（含继承的），兼容 Android ART
            superClass.getConstructor();

            // 找到可访问的无参构造器，生成标准 super()V 调用
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, INIT, VOID_NO_ARGS, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, INIT, VOID_NO_ARGS, false);
            initializeFieldLiterals(mv, internalName, fields);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(3, 1);
            mv.visitEnd();
            return true;
        } catch (NoSuchMethodException e) {
            // 无公共无参构造器 → fallback
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Fallback 构造器：当父类无可访问的无参构造器时，
     * 找到父类第一个可访问的构造器，用默认参数值生成匹配的构造器。
     * <p>如果所有构造器都是 private，提前抛出异常止损（字节码生成阶段即失败），
     * 测试框架会将其识别为平台限制跳过。
     */
    private static void addFallbackConstructor(ClassWriter cw, String internalName,
                                               String superName,
                                               List<FieldDeclarationNode> fields) {
        try {
            Class<?> superClass = Class.forName(DescriptorUtils.toClassName(superName));
            Constructor<?>[] ctors = superClass.getDeclaredConstructors();
            // 过滤掉 private 构造器（Android DEX 不允许子类 INVOKESPECIAL 父类私有构造器）
            Constructor<?> ctor = null;
            for (Constructor<?> c : ctors) {
                if (!Modifier.isPrivate(c.getModifiers())) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                // 所有构造器都是 private → 无法生成合法子类（INVOKESPECIAL 不能调 private）
                throw new UnsupportedOperationException(
                        "Cannot extend " + superClass.getName() +
                                ": all constructors are private/inaccessible");
            }
            Class<?>[] paramTypes = ctor.getParameterTypes();
            String desc = buildDescriptorFromTypes(java.util.Arrays.asList(paramTypes));

            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, INIT, desc, null, null);
            mv.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;
            for (Class<?> t : paramTypes) {
                loadDefaultValue(mv, t, slot);
                slot += (t == long.class || t == double.class) ? 2 : 1;
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, INIT, desc, false);

            initializeFieldLiterals(mv, internalName, fields);
            mv.visitInsn(Opcodes.RETURN);

            int maxStack = Math.max(3, paramTypes.length + 2);
            int maxLocals = 1 + slot - 1;
            mv.visitMaxs(maxStack, maxLocals);
            mv.visitEnd();

        } catch (Exception e) {
            // 最终兜底：直接调 super()V，让 JVM 报错
            addSimpleFallback(cw, internalName, superName, fields);
        }
    }

    /** 最终兜底：无条件生成 super()V 无参构造器。 */
    private static void addSimpleFallback(ClassWriter cw, String internalName,
                                          String superName,
                                          List<FieldDeclarationNode> fields) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, INIT, VOID_NO_ARGS, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, INIT, VOID_NO_ARGS, false);
        initializeFieldLiterals(mv, internalName, fields);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
    }

    private static void addAnonymousConstructor(ClassWriter cw, String className,
                                                 String superName,
                                                 List<FieldDeclarationNode> fields,
                                                 List<Class<?>> superArgTypes,
                                                 List<ParameterNode> extraParams) {
        // 构造描述符: (superArgs..., extraParams...)V
        StringBuilder desc = new StringBuilder("(");
        int paramCount = 0;
        if (superArgTypes != null) {
            for (Class<?> t : superArgTypes) {
                desc.append(DescriptorUtils.descriptor(t));
                paramCount++;
            }
        }
        if (extraParams != null) {
            for (ParameterNode p : extraParams) {
                desc.append(DescriptorUtils.fieldDescriptor(p.getType()));
                paramCount++;
            }
        }
        desc.append(")V");
        String descriptor = desc.toString();

        String internalName = DescriptorUtils.toInternalName(className);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, INIT, descriptor, null, null);
        mv.visitCode();

        // super(args...)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        if (superArgTypes != null) {
            for (Class<?> t : superArgTypes) {
                loadPrimitiveOrObject(mv, t, slot);
                slot += (t == long.class || t == double.class) ? 2 : 1;
            }
        }
        String superDesc = buildDescriptorFromTypes(superArgTypes);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, INIT, superDesc, false);

        initializeFieldLiterals(mv, internalName, fields);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(Math.max(3, paramCount + 2), paramCount + 1);
        mv.visitEnd();
    }

    private void addConstructorWithBody(ClassWriter cw, ConstructorDeclarationNode ctor,
                                                String className, String superName,
                                                List<FieldDeclarationNode> fields) {
        StringBuilder desc = new StringBuilder("(");
        for (ParameterNode p : ctor.getParameters()) {
            desc.append(DescriptorUtils.fieldDescriptor(p.getType()));
        }
        desc.append(")V");
        String descriptor = desc.toString();

        int mods = memberAccessFlags(ctor.getModifiers());

        String internalName = DescriptorUtils.toInternalName(className);
        MethodVisitor mv = cw.visitMethod(mods, INIT,  descriptor, null, null);
        mv.visitCode();

        // super(args...) for explicit super() call or default
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, INIT, VOID_NO_ARGS, false);

        initializeFieldLiterals(mv, internalName, fields);

        // register method body for executor
        if (delegateToExecutor && ctor.getBody() != null) {
            registerMethodBody(className, INIT, descriptor, wrapConstructorAsMethod(ctor));
            // ★ 构造器体由 CustomClassExecutor 解释执行（在此之前只注册不调用，
            //   导致构造器体内的字段赋值等逻辑从未运行）
            emitExecuteCall(mv, className, INIT, descriptor, false, ctor.getParameters());
            mv.visitInsn(Opcodes.POP); // 丢弃 execute 的 Object 返回值（构造器返回 void）
        }

        mv.visitInsn(Opcodes.RETURN);
        int maxLocals = 1 + (ctor.getParameters() != null ? ctor.getParameters().size() : 0);
        mv.visitMaxs(3, maxLocals);
        mv.visitEnd();
    }

    private static MethodDeclarationNode wrapConstructorAsMethod(ConstructorDeclarationNode ctor) {
        return (MethodDeclarationNode) new MethodDeclarationNode.Builder()
                .methodName(INIT)
                .returnType(ClassReferenceNode.of("void", void.class, true, null))
                .parameters(ctor.getParameters())
                .body(ctor.getBody())
                .modifiers(ctor.getModifiers())
                .build();
    }

    private static void initializeFieldLiterals(MethodVisitor mv, String internalName,
                                                 List<FieldDeclarationNode> fields) {
        if (fields == null) return;
        for (FieldDeclarationNode field : fields) {
            var mods = field.getModifiers();
            if (mods != null && mods.isStatic()) continue; // static 在 <clinit> 处理
            ASTNode init = field.getInitialValue();
            if (init instanceof LiteralNode lit) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                pushLiteral(mv, lit, field.getType());
                mv.visitFieldInsn(Opcodes.PUTFIELD, internalName,
                        field.getFieldName(), DescriptorUtils.fieldDescriptor(field.getType()));
            }
        }
    }

    private static void addStaticInitializer(ClassWriter cw, String className,
                                              List<FieldDeclarationNode> fields) {
        String internalName = DescriptorUtils.toInternalName(className);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, CLINIT, VOID_NO_ARGS, null, null);
        mv.visitCode();
        for (FieldDeclarationNode field : fields) {
            var mods = field.getModifiers();
            if (mods == null || !mods.isStatic()) continue;
            ASTNode init = field.getInitialValue();
            if (init instanceof LiteralNode lit) {
                pushLiteral(mv, lit, field.getType());
                mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName,
                        field.getFieldName(), DescriptorUtils.fieldDescriptor(field.getType()));
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    /**
     * 按**字段声明类型**压入字面量。
     * <p>
     * 不能用字面量自身的类型决定压栈指令：{@code long a = 30;} 里的 {@code 30} 是 int，
     * 按 int 压栈再 PUTFIELD 到 {@code J} 字段会得到
     * {@code VerifyError: Bad type on operand stack}。这里先按字段类型转换再压栈。
     * </p>
     */
    private static void pushLiteral(MethodVisitor mv, LiteralNode lit, ClassReferenceNode fieldType) {
        pushValue(mv, coerceToFieldType(lit.getValue(), fieldType));
    }

    private static void pushValue(MethodVisitor mv, Object val) {
        if (val == null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else if (val instanceof Boolean b) {
            mv.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        } else if (val instanceof Integer i) {
            pushInt(mv, i);
        } else if (val instanceof Long l) {
            if (l == 0L) mv.visitInsn(Opcodes.LCONST_0);
            else if (l == 1L) mv.visitInsn(Opcodes.LCONST_1);
            else mv.visitLdcInsn(l);
        } else if (val instanceof Float f) {
            if (f == 0.0f) mv.visitInsn(Opcodes.FCONST_0);
            else if (f == 1.0f) mv.visitInsn(Opcodes.FCONST_1);
            else if (f == 2.0f) mv.visitInsn(Opcodes.FCONST_2);
            else mv.visitLdcInsn(f);
        } else if (val instanceof Double d) {
            if (d == 0.0d) mv.visitInsn(Opcodes.DCONST_0);
            else if (d == 1.0d) mv.visitInsn(Opcodes.DCONST_1);
            else mv.visitLdcInsn(d);
        } else if (val instanceof Character c) {
            // 字段声明类型未解析时的兜底：char 按 int 压栈
            pushInt(mv, c);
        } else {
            mv.visitLdcInsn(val);
        }
    }

    private static String buildDescriptorFromTypes(List<Class<?>> types) {
        if (types == null || types.isEmpty()) return VOID_NO_ARGS;
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> t : types) {
            desc.append(DescriptorUtils.descriptor(t));
        }
        desc.append(")V");
        return desc.toString();
    }

    private static void loadPrimitiveOrObject(MethodVisitor mv, Class<?> type, int slot) {
        if (!type.isPrimitive()) {
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            return;
        }
        String name = type.getName();
        switch (name) {
            case "int", "byte", "short", "char", "boolean" -> mv.visitVarInsn(Opcodes.ILOAD, slot);
            case "long" -> mv.visitVarInsn(Opcodes.LLOAD, slot);
            case "float" -> mv.visitVarInsn(Opcodes.FLOAD, slot);
            case "double" -> mv.visitVarInsn(Opcodes.DLOAD, slot);
            default -> mv.visitVarInsn(Opcodes.ALOAD, slot);
        }
    }

    /** 加载类型的默认值（0 / 0L / 0.0 / false / null），用于 fallback 构造器。 */
    private static void loadDefaultValue(MethodVisitor mv, Class<?> type, int unusedSlot) {
        if (!type.isPrimitive()) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }
        String name = type.getName();
        switch (name) {
            case "int", "byte", "short", "char" -> mv.visitInsn(Opcodes.ICONST_0);
            case "long" -> mv.visitInsn(Opcodes.LCONST_0);
            case "float" -> mv.visitInsn(Opcodes.FCONST_0);
            case "double" -> mv.visitInsn(Opcodes.DCONST_0);
            case "boolean" -> mv.visitInsn(Opcodes.ICONST_0);
            default -> mv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    // ==================== 委托执行器方法 ====================

    private void addDelegateMethod(ClassWriter cw, MethodDeclarationNode method,
                                    String className, boolean isInterface) {
        String methodName = method.getMethodName();
        String descriptor = buildDescriptor(method);
        String returnDesc = descriptor.substring(descriptor.indexOf(')') + 1);

        int mods = memberAccessFlags(method.getModifiers());

        if (isInterface) {
            mods |= Opcodes.ACC_ABSTRACT;
            cw.visitMethod(mods, methodName, descriptor, null, null).visitEnd();
            return;
        }

        // 抽象方法没有方法体：写 visitCode() 会得到
        // ClassFormatError: Code attribute in native or abstract methods
        if (method.getModifiers() != null && method.getModifiers().isAbstract()) {
            cw.visitMethod(mods, methodName, descriptor, null, null).visitEnd();
            return;
        }

        MethodVisitor mv = cw.visitMethod(mods, methodName, descriptor, null, null);
        mv.visitCode();

        boolean isStatic = method.getModifiers() != null && method.getModifiers().isStatic();
        List<ParameterNode> params = method.getParameters();
        emitExecuteCall(mv, className, methodName, descriptor, isStatic, params);

        // 拆箱并返回
        unboxAndReturn(mv, returnDesc);

        int paramCount = params != null ? params.size() : 0;
        int maxLocals = paramCount + (isStatic ? 0 : 1);
        mv.visitMaxs(6 + paramCount * 2, maxLocals);
        mv.visitEnd();
    }

    /**
     * 生成调用 {@link CustomClassExecutor#execute} 的字节码序列：
     * {@code execute(className, methodName, descriptor, this|static-null, Object[] args)}。
     * <p>
     * 调用结束后栈顶为该方法的 Object 结果，由调用方决定拆箱返回还是直接 POP。
     * </p>
     *
     * @param mv         目标方法访问器
     * @param className  脚本类名（executor 注册表键）
     * @param methodName 方法名（构造器为 {@code <init>}）
     * @param descriptor 完整 JVM 描述符
     * @param isStatic   是否为静态方法（决定第二个对象参数是 null 还是 this）
     * @param params     方法/构造器参数列表
     */
    private static void emitExecuteCall(MethodVisitor mv, String className, String methodName,
                                        String descriptor, boolean isStatic, List<ParameterNode> params) {
        // LDC className
        mv.visitLdcInsn(className);
        // LDC methodName
        mv.visitLdcInsn(methodName);
        // LDC descriptor (全描述符，用于精确重载匹配)
        mv.visitLdcInsn(descriptor);

        // this / null (static)
        if (isStatic) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }

        // Object[] args
        int paramCount = params != null ? params.size() : 0;
        if (paramCount > 0) {
            pushInt(mv, paramCount);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT);

            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < paramCount; i++) {
                mv.visitInsn(Opcodes.DUP);
                pushInt(mv, i);
                loadAndBox(mv, params.get(i), slot);
                mv.visitInsn(Opcodes.AASTORE);
                slot += slotSize(params.get(i));
            }
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EXECUTE_CLASS, EXECUTE_METHOD, EXECUTE_SIGNATURE, false);
    }

    private static void loadAndBox(MethodVisitor mv, ParameterNode param, int slot) {
        ClassReferenceNode type = param.getType();
        if (type == null) {
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            return;
        }
        Class<?> resolved = type.getResolvedClass();
        if (resolved == null || !resolved.isPrimitive()) {
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            return;
        }
        String name = resolved.getName();
        switch (name) {
            case "int" -> {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, INTEGER, VALUE_OF, VALUE_OF_INTEGER_SIG, false);
            }
            case "long" -> {
                mv.visitVarInsn(Opcodes.LLOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, LONG, VALUE_OF, VALUE_OF_LONG_SIG, false);
            }
            case "float" -> {
                mv.visitVarInsn(Opcodes.FLOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT, VALUE_OF, VALUE_OF_FLOAT_SIG, false);
            }
            case "double" -> {
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DOUBLE, VALUE_OF, VALUE_OF_DOUBLE_SIG, false);
            }
            case "boolean" -> {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BOOLEAN, VALUE_OF, VALUE_OF_BOOLEAN, false);
            }
            case "char" -> {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CHARACTER, VALUE_OF, VALUE_OF_CHARACTER_SIG, false);
            }
            case "byte" -> {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BYTE, VALUE_OF, VALUE_OF_BYTE_SIG, false);
            }
            case "short" -> {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SHORT, VALUE_OF, VALUE_OF_SHORT_SIG, false);
            }
            default -> mv.visitVarInsn(Opcodes.ALOAD, slot);
        }
    }

    private static void unboxAndReturn(MethodVisitor mv, String returnDesc) {
        switch (returnDesc) {
            case "V" -> {
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
            }
            case "Z" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOLEAN, "booleanValue", "()Z", false);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "B" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "byteValue", "()B", false);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "C" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, CHARACTER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHARACTER, "charValue", "()C", false);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "S" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "shortValue", "()S", false);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "I" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, INTEGER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INTEGER, "intValue", "()I", false);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "J" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "longValue", "()J", false);
                mv.visitInsn(Opcodes.LRETURN);
            }
            case "F" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "floatValue", "()F", false);
                mv.visitInsn(Opcodes.FRETURN);
            }
            case "D" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, NUMBER);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NUMBER, "doubleValue", "()D", false);
                mv.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                String internalName = returnDesc;
                if (internalName.startsWith("L") && internalName.endsWith(";")) {
                    internalName = internalName.substring(1, internalName.length() - 1);
                }
                mv.visitTypeInsn(Opcodes.CHECKCAST, internalName);
                mv.visitInsn(Opcodes.ARETURN);
            }
        }
    }

    // ==================== 空方法体（回退） ====================

    private static void addEmptyMethod(ClassWriter cw, MethodDeclarationNode method, boolean isInterface) {
        String descriptor = buildDescriptor(method);
        ClassReferenceNode returnTypeRef = method.getReturnType();

        int mods = memberAccessFlags(method.getModifiers());

        if (isInterface) {
            mods |= Opcodes.ACC_ABSTRACT;
            cw.visitMethod(mods, method.getMethodName(), descriptor, null, null).visitEnd();
            return;
        }

        if (method.getModifiers() != null && method.getModifiers().isAbstract()) {
            cw.visitMethod(mods, method.getMethodName(), descriptor, null, null).visitEnd();
            return;
        }

        MethodVisitor mv = cw.visitMethod(mods, method.getMethodName(), descriptor, null, null);
        mv.visitCode();
        emitDefaultReturn(mv, returnTypeRef);

        List<ParameterNode> params = method.getParameters();
        mv.visitMaxs(1, params != null ? params.size() + 1 : 1);
        mv.visitEnd();
    }

    private static void emitDefaultReturn(MethodVisitor mv, ClassReferenceNode returnTypeRef) {
        if (returnTypeRef == null || isVoid(returnTypeRef)) {
            mv.visitInsn(Opcodes.RETURN);
            return;
        }
        Class<?> rawType = returnTypeRef.getResolvedClass();
        if (rawType == null || !rawType.isPrimitive()) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }
        switch (rawType.getName()) {
            case "int", "byte", "short", "char", "boolean" -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "long" -> {
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LRETURN);
            }
            case "float" -> {
                mv.visitInsn(Opcodes.FCONST_0);
                mv.visitInsn(Opcodes.FRETURN);
            }
            case "double" -> {
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
        }
    }

    // ==================== 通用工具 ====================

    private static String buildDescriptor(MethodDeclarationNode method) {
        StringBuilder desc = new StringBuilder("(");
        List<ParameterNode> params = method.getParameters();
        if (params != null) {
            for (ParameterNode p : params) {
                desc.append(DescriptorUtils.fieldDescriptor(p.getType()));
            }
        }
        desc.append(")");
        ClassReferenceNode ret = method.getReturnType();
        desc.append(ret != null ? DescriptorUtils.fieldDescriptor(ret) : "V");
        return desc.toString();
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= -128 && value <= 127) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= -32768 && value <= 32767) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static int slotSize(ParameterNode param) {
        ClassReferenceNode type = param.getType();
        if (type == null) return 1;
        Class<?> resolved = type.getResolvedClass();
        if (resolved == long.class || resolved == double.class) return 2;
        return 1;
    }

    private static boolean isVoid(ClassReferenceNode ref) {
        if (ref == null) return true;
        Class<?> c = ref.getResolvedClass();
        return c == void.class;
    }

    /**
     * 懒加载代理 ClassDefiner。
     * <p>
     * 每次 {@code defineClass()} 调用时动态读取 static defaultClassDefiner，
     * 因此构造之后再调用 {@link #setDefaultClassDefiner(ClassDefiner)} 也能立即生效。
     * 未被替换时回退到内部 Loader（保持 ClassLoader 一致性）。
     */
    private record LazyDefaultDefiner(ClassDefiner fallback) implements ClassDefiner {
        @Override
        public Class<?> defineClass(String name, byte[] bytecode, ClassLoader parent) throws Exception {
            // 优先用已替换的 default，否则回退到 fallback (Loader)
            return (defaultClassDefinerCustomized ? defaultClassDefiner : fallback)
                    .defineClass(name, bytecode, parent);
        }
    }

    private static final class Loader extends ClassLoader implements ClassDefiner {
        Loader(ClassLoader parent) { super(parent); }

        @Override
        public Class<?> defineClass(String name, byte[] bytecode, ClassLoader parent) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

}
