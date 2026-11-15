package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理原版 {@code ScriptStore}：清空冗余 classpath，并把动态脚本类的加载权交回
 * {@code TargetClassLoader}。
 *
 * <p>三段独立 pass，按「能力探测 + 按形态匹配」实现，不依赖常量池索引或混淆名：
 *
 * <ol>
 *   <li>URLClassLoader pass：把 {@code new URLClassLoader(urls, parent)} 的 urls 换成空数组，
 *       去掉游戏重复的 classpath。形态不符即报错（既有行为）。</li>
 *   <li><b>P2 构造层</b>：整体替换「唯一一个向 JavaSourceClassLoader 静态字段 putstatic 的
 *       {@code static void (ClassLoader)} 方法」的方法体，改为
 *       {@code ScriptStore.class.getClassLoader()} → {@code JaninoCompiler.create} →
 *       {@code CHECKCAST} → {@code putstatic}。这样无论几个构造分支、
 *       {@code enableScriptCaching} 取何值，都只创建我们的纯编译器。
 *       形态不符可降级（记 SKIPPED），不影响 P1。</li>
 *   <li><b>P1 加载入口（必需）</b>：把 {@code Class.forName(name,true,loader)} 与
 *       {@code janinoLoader.loadClass(name)} 两处使用的
 *       {@code getstatic &lt;JavaSourceClassLoader&gt;} 换成
 *       {@code ldc ScriptStore.class; Class.getClassLoader()}，并把 loadClass 的 owner
 *       改成 {@code java/lang/ClassLoader}。⇒ 两条入口都走 TargetClassLoader。
 *       改写范围覆盖 {@code ScriptStore} 与全部 {@code ScriptStore$N} 内部类
 *       （实测 0.98a 的后台加载线程 {@code ScriptStore$3} 就各有一处 forName / loadClass）。
 *       <b>外部类必须恰好 2 处、任何类改写后不得有残留</b>，否则硬失败，
 *       不静默回退到游戏 loader。</li>
 * </ol>
 *
 * <p>刻意保留：{@code ScriptStore} 的 public getter 与
 * {@code JaninoBytecodeCacheHooks.finish(loader, exception)} 处的
 * {@code getstatic} —— 它们不是加载入口，字段仍应指向纯编译器。
 */
public final class StarsectorTransformer {
    private static final String SCRIPT_STORE = "com.fs.starfarer.loading.scripts.ScriptStore";
    private static final String SCRIPT_STORE_INTERNAL = "com/fs/starfarer/loading/scripts/ScriptStore";
    private static final String URL_LOADER = "java/net/URLClassLoader";
    private static final String URL = "java/net/URL";
    private static final String CONSTRUCTOR = "([Ljava/net/URL;Ljava/lang/ClassLoader;)V";

    private static final String JANINO_LOADER = "org/codehaus/janino/JavaSourceClassLoader";
    private static final String JANINO_LOADER_DESC = "Lorg/codehaus/janino/JavaSourceClassLoader;";
    private static final String CLASS_LOADER = "java/lang/ClassLoader";
    private static final String COMPILER = "com/github/alphardpaarthurnax/mixinlib/janino/JaninoCompiler";
    private static final String FOR_NAME_DESC =
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;";
    private static final String LOAD_CLASS_DESC = "(Ljava/lang/String;)Ljava/lang/Class;";
    private static final String GET_CLASS_LOADER_DESC = "()Ljava/lang/ClassLoader;";
    private static final String CREATE_DESC =
            "(Ljava/lang/ClassLoader;)Ljava/lang/Object;";

    private StarsectorTransformer() {
    }

    public static byte[] transform(String className, byte[] bytes) {
        if (!isScriptStore(className)) {
            return bytes;
        }
        boolean outer = SCRIPT_STORE.equals(className);

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        // 构造层与 URLClassLoader 清理只存在于外部类；内部类各自持有加载入口。
        int emptied = 0;
        boolean constructorRewritten = false;
        if (outer) {
            emptied = emptyUrlClassLoaderArguments(node);
            FieldNode compilerField = findCompilerField(node);
            constructorRewritten = rewriteConstructor(node, compilerField);
        }

        int loadEntries = rewriteLoadEntries(node);
        int residual = countLoaderFieldEntries(node);

        if (outer && loadEntries != 2) {
            throw new IllegalStateException(
                    "Unexpected ScriptStore load entry shape: expected 2 (Class.forName + loadClass),"
                            + " rewritten=" + loadEntries);
        }
        // 任何仍把 JavaSourceClassLoader 静态字段当加载器用的入口都必须被改写掉：
        // 内部类（如 ScriptStore$3 的后台加载线程）漏改会让脚本重新由 JavaSourceClassLoader 定义。
        if (residual != 0) {
            throw new IllegalStateException(
                    "Unrewritten ScriptStore load entry: class=" + className
                            + " residual=" + residual);
        }
        if (!outer && loadEntries == 0) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        MixinLog.info("[Compat] ScriptStore"
                + (outer ? "" : " inner=" + shortName(className))
                + " emptied=" + emptied
                + " constructor-rewrite="
                + (outer ? (constructorRewritten ? "APPLIED" : "SKIPPED") : "n/a")
                + " load-entries=" + loadEntries);
        return writer.toByteArray();
    }

    /** ScriptStore 及其内部类（$1..$N）都携带脚本加载路径。 */
    private static boolean isScriptStore(String className) {
        return SCRIPT_STORE.equals(className) || className.startsWith(SCRIPT_STORE + "$");
    }

    private static String shortName(String className) {
        int index = className.lastIndexOf('$');
        return index < 0 ? className : className.substring(index + 1);
    }

    /** 既有 pass：清空游戏重复注入的 URLClassLoader classpath。 */
    private static int emptyUrlClassLoaderArguments(ClassNode node) {
        int allocations = 0;
        int constructors = 0;
        int emptiedArguments = 0;

        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && URL_LOADER.equals(type.desc)) {
                    allocations++;
                } else if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && URL_LOADER.equals(call.owner) && "<init>".equals(call.name)
                        && CONSTRUCTOR.equals(call.desc)) {
                    constructors++;
                    AbstractInsnNode urlArgument = previousReal(call, 2);
                    if (!(urlArgument instanceof VarInsnNode variable)
                            || variable.getOpcode() != Opcodes.ALOAD) {
                        throw new IllegalStateException(
                                "Unexpected ScriptStore URLClassLoader argument shape");
                    }

                    method.instructions.insertBefore(urlArgument, new InsnNode(Opcodes.ICONST_0));
                    method.instructions.set(urlArgument, new TypeInsnNode(Opcodes.ANEWARRAY, URL));
                    emptiedArguments++;
                }
            }
        }

        if (allocations == 0 || allocations != constructors || constructors != emptiedArguments) {
            throw new IllegalStateException("Unexpected ScriptStore URLClassLoader shape: new="
                    + allocations + " init=" + constructors + " emptied=" + emptiedArguments);
        }
        return emptiedArguments;
    }

    /** 按字段描述符定位 JavaSourceClassLoader 静态字段，不依赖混淆名。 */
    private static FieldNode findCompilerField(ClassNode node) {
        FieldNode found = null;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0
                    || !JANINO_LOADER_DESC.equals(field.desc)) {
                continue;
            }
            if (found != null) {
                MixinLog.info("[Compat] constructor-rewrite=SKIPPED reason=ambiguous-field");
                return null;
            }
            found = field;
        }
        return found;
    }

    /** P2：整体替换构造层方法体。失败可降级，不影响 P1。 */
    private static boolean rewriteConstructor(ClassNode node, FieldNode compilerField) {
        if (compilerField == null) {
            MixinLog.info("[Compat] constructor-rewrite=SKIPPED reason=no-compiler-field");
            return false;
        }
        List<MethodNode> candidates = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    || !"(Ljava/lang/ClassLoader;)V".equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTSTATIC
                        && compilerField.name.equals(field.name)
                        && JANINO_LOADER_DESC.equals(field.desc)) {
                    candidates.add(method);
                    break;
                }
            }
        }
        if (candidates.size() != 1) {
            MixinLog.info("[Compat] constructor-rewrite=SKIPPED reason=candidates="
                    + candidates.size());
            return false;
        }

        MethodNode method = candidates.get(0);
        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        // 用 ScriptStore.class.getClassLoader() 直接拿 target：构造层实参在不同版本里
        // 可能是空的 URLClassLoader 或 B，只有 ScriptStore 自身的定义加载器恒为 target。
        method.instructions.add(new LdcInsnNode(Type.getObjectType(node.name)));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getClassLoader", GET_CLASS_LOADER_DESC, false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPILER,
                "create", CREATE_DESC, false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, JANINO_LOADER));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name,
                compilerField.name, JANINO_LOADER_DESC));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return true;
    }

    /** P1：改写加载入口，返回成功改写数；外部类要求恰好为 2。 */
    private static int rewriteLoadEntries(ClassNode node) {
        int rewritten = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean forName = isForName(call);
                boolean loadClass = isLoadClass(call);
                if (!forName && !loadClass) {
                    continue;
                }

                FieldInsnNode field = precedingCompilerField(call, forName ? 2 : 3);
                if (field == null) {
                    continue;
                }
                InsnList replacement = new InsnList();
                // 一律用外部类取加载器：内部类的定义加载器同样是 target，
                // 但外部类是唯一在所有版本里都稳定的锚点。
                replacement.add(new LdcInsnNode(Type.getObjectType(SCRIPT_STORE_INTERNAL)));
                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                        "getClassLoader", GET_CLASS_LOADER_DESC, false));
                method.instructions.insertBefore(field, replacement);
                method.instructions.remove(field);
                if (loadClass) {
                    call.owner = CLASS_LOADER;
                }
                rewritten++;
            }
        }
        return rewritten;
    }

    /** 改写后的残留检查：仍以编译器静态字段为加载器的入口一律计为漏改。 */
    private static int countLoaderFieldEntries(ClassNode node) {
        int residual = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean forName = isForName(call);
                boolean loadClass = isLoadClass(call);
                if ((forName || loadClass) && precedingCompilerField(call, forName ? 2 : 3) != null) {
                    residual++;
                }
            }
        }
        return residual;
    }

    private static boolean isForName(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && "java/lang/Class".equals(call.owner)
                && "forName".equals(call.name) && FOR_NAME_DESC.equals(call.desc);
    }

    private static boolean isLoadClass(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "loadClass".equals(call.name) && LOAD_CLASS_DESC.equals(call.desc);
    }

    private static FieldInsnNode precedingCompilerField(AbstractInsnNode call, int maxSteps) {
        AbstractInsnNode current = call;
        for (int step = 0; step < maxSteps; step++) {
            current = previousReal(current, 1);
            if (current == null) {
                return null;
            }
            if (current instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && JANINO_LOADER_DESC.equals(field.desc)) {
                return field;
            }
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction, int steps) {
        AbstractInsnNode current = instruction;
        for (int step = 0; step < steps && current != null; step++) {
            current = current.getPrevious();
            while (current != null && (current.getType() == AbstractInsnNode.LABEL
                    || current.getType() == AbstractInsnNode.LINE
                    || current.getType() == AbstractInsnNode.FRAME)) {
                current = current.getPrevious();
            }
        }
        return current;
    }
}
