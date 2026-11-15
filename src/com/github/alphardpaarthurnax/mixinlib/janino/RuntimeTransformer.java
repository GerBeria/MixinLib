package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.bytecode.BytePreprocessor;
import com.github.alphardpaarthurnax.mixinlib.loader.ClassOrigin;
import com.github.alphardpaarthurnax.mixinlib.loader.ParentFirstRules;
import com.github.alphardpaarthurnax.mixinlib.loader.TargetClassLoader;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.logger.TransformDiagnostics;
import com.github.alphardpaarthurnax.mixinlib.mixin.InjectorSnapshot;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformContext;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformerExclusions;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** 处理在加载器之外定义、但属于其后代加载器的类，主要来自 Janino 生成的类。 */
public final class RuntimeTransformer implements ClassFileTransformer {
    private final TargetClassLoader target;
    private final IMixinTransformer transformer;
    private final AtomicLong counter = new AtomicLong();

    public RuntimeTransformer(TargetClassLoader target, IMixinTransformer transformer) {
        this.target = target;
        this.transformer = transformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (loader == null || loader == target || name == null) {
            return null;
        }
        String dotted = name.replace('/', '.');
        // G11：排除判定先于 isHostClass / couldTransformClass，命中即硬短路。
        String exclusion = TransformerExclusions.reasonFor(dotted);
        if (exclusion != null) {
            MixinLog.info("[Exclusion] class=" + dotted + " reason=" + exclusion);
            return null;
        }
        if (isHostClass(name) || !isTargetDescendant(loader)) {
            return null;
        }
        // D6：清洗必须先于 couldTransformClass 的提前 return。等长改名是全局契约，
        // 本类若被清洗而引用方没有，就会 NoSuchFieldError；因此即使后面没有织入，
        // 也必须把清洗后的字节交回去（passthrough 语义）。
        byte[] prepared = BytePreprocessor.prepare(dotted, classfileBuffer,
                ClassOrigin.JANINO, loader);
        try {
            synchronized (transformer) {
                try (TransformContext.Scope ignored = TransformContext.enter(
                        loader, dotted, prepared, ClassOrigin.JANINO);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("external", loader, dotted,
                                     ClassOrigin.JANINO.name())) {
                    MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
                    byte[] result = null;
                    if (transformer.couldTransformClass(environment, dotted)) {
                        result = transformer.transformClass(environment, dotted, prepared);
                        // ③ 自检在首次真正 mixin transform 之后触发一次（另一条链路见 TargetClassLoader）。
                        InjectorSnapshot.runSelfCheckIfNeeded();
                    }
                    if (result != null && result != prepared) {
                        MixinLog.info("[Transform] external #" + counter.incrementAndGet() + " " + dotted
                                + " origin=" + ClassOrigin.JANINO
                                + " loader=" + loader.getClass().getName());
                        return result;
                    }
                    return prepared == classfileBuffer ? null : prepared;
                }
            }
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:TRANSFORM_FAIL] kind=external class=" + dotted
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(loader), throwable);
            IllegalClassFormatException failure = new IllegalClassFormatException(
                    "Mixin transformation failed for " + dotted);
            failure.initCause(throwable);
            throw failure;
        }
    }

    private static boolean isHostClass(String name) {
        // parent-first 的类由系统加载器定义，既不经 target 的字节转换链，
        // 也不允许由此外部 transformer 织入；规则统一由 ParentFirstRules 决定。
        if (ParentFirstRules.isParentFirst(name.replace('/', '.'))) {
            return true;
        }
        return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
                || name.startsWith("org/spongepowered/asm/") || name.startsWith("org/objectweb/asm/")
                || name.startsWith("com/github/alphardpaarthurnax/mixinlib/");
    }

    private boolean isTargetDescendant(ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null) {
            if (current == target) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
