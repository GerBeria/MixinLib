package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态编译类的唯一字节码来源，也是当前 feat 暴露给下一 feat 的唯一扩展缝。
 *
 * <p>职责边界：这里只负责把「类名」变成 Janino 生成的<strong>原始字节码</strong>
 * （未清洗、未织入、未落盘）；EC-9 校验、D6 清洗、Mixin 织入与 defineClass
 * 全部由 {@code TargetClassLoader} 统一完成。
 *
 * <p>{@code generateBytecodes} 一次会编译出多个 class（内部类、匿名类），
 * 这里把整张 Map 预登记到字节池，之后按类名逐条取走；已取走或已尝试过
 * 的类名不再重复编译，避免同名类被重复定义。
 *
 * <p>全程 fail-open：无源码、编译失败、Janino 不可用时返回 {@code null}
 * 并记日志，由调用方回退到既有生成路径，绝不把可疑字节交给 defineClass。
 */
public final class JaninoClassSource {
    private static final Map<String, byte[]> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> KNOWN = ConcurrentHashMap.newKeySet();
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static volatile ClassLoader namespace;
    private static final ThreadLocal<int[]> COMPILING = ThreadLocal.withInitial(() -> new int[1]);
    private static final AtomicBoolean NOTICE = new AtomicBoolean();
    private static final AtomicLong MISSES = new AtomicLong();

    private JaninoClassSource() {
    }

    /**
     * 当前线程是否正处于 Janino 编译期。编译器会通过 parent 回查正在定义的脚本类，
     * 这类 {@code ClassNotFoundException} 是正常探测，不应记成递归定义错误。
     */
    public static boolean isCompiling() {
        return COMPILING.get()[0] > 0;
    }

    /**
     * 只记录脚本类所在的命名空间，不创建编译器。
     * 编译器延迟到首次 {@link #bytecodesFor} 才建立（见 JaninoCompiler 幂等闸）。
     */
    public static void bind(ClassLoader target) {
        namespace = target;
    }

    public static ClassLoader namespace() {
        return namespace;
    }

    /**
     * 该类是否走过编译尝试（无论成功失败）。用于区分「脚本类找不到」与
     * 「普通类交给父加载器」：前者必须把原始失败抛出去，不能被父加载器的
     * ClassNotFoundException 盖掉真实原因。
     */
    public static boolean wasAttempted(String className) {
        return className != null
                && (ATTEMPTED.contains(className) || KNOWN.contains(className));
    }

    /** 累计「没有源码」次数；只做汇总，不逐条写日志。 */
    public static long misses() {
        return MISSES.get();
    }

    /**
     * 唯一入口：返回该类的原始编译字节码；无源码或编译失败返回 {@code null}。
     */
    public static byte[] bytecodesFor(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        byte[] pending = PENDING.remove(className);
        if (pending != null) {
            return pending;
        }
        if (KNOWN.contains(className) || ATTEMPTED.contains(className)) {
            return null;
        }
        return compileDirectly(className);
    }

    /**
     * 默认行为：直接调用自持编译器，零缓存、零校验、零落盘。
     * 下一 feat 只替换这一处为 ScriptCompiler 插件调用，其余文件不动。
     */
    private static byte[] compileDirectly(String className) {
        // 编译期回问：Janino 解析歧义名时会把同一个标识符按多种包组合试探，
        // 这类请求注定编译不出东西，静默放弃即可；不登记 ATTEMPTED，留待真正需要时再试。
        if (JaninoCompiler.isCompiling()) {
            return null;
        }
        if (!ATTEMPTED.add(className)) {
            return null;
        }
        ClassLoader current = namespace;
        if (current == null) {
            current = JaninoClassSource.class.getClassLoader();
        }
        COMPILING.get()[0]++;
        try {
            Map<String, byte[]> compiled = JaninoCompiler.compile(current, className);
            if (compiled == null || compiled.isEmpty()) {
                noteNoSource(className);
                return null;
            }
            int accepted = 0;
            for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
                String key = entry.getKey();
                byte[] bytes = entry.getValue();
                if (key == null || key.isBlank() || bytes == null || bytes.length == 0) {
                    continue;
                }
                KNOWN.add(key);
                PENDING.putIfAbsent(key, bytes);
                accepted++;
            }
            MixinLog.info("[Janino] compiled root=" + className + " classes=" + accepted);
            return PENDING.remove(className);
        } catch (ClassNotFoundException failure) {
            // 有源码但编译失败：与「找不到源码」严格区分，否则实机上只能看到 no-source。
            MixinLog.error("[Janino] compile-failed class=" + className
                    + " reason=" + failure.getMessage(), failure);
            return null;
        } catch (Throwable throwable) {
            MixinLog.error("[Janino] 直接编译失败 class=" + className, throwable);
            return null;
        } finally {
            int[] counter = COMPILING.get();
            if (--counter[0] <= 0) {
                COMPILING.remove();
            }
        }
    }

    /**
     * 「没有源码」不是异常：游戏自己就靠「换包名再试一次」来解析 rulecmd 命令，
     * Janino 解析歧义名时也会对同一标识符逐个包试探。这类请求每次启动上千条，
     * 逐条记录只会把真正的失败淹掉，因此只留一条说明 + 计数。
     *
     * <p>真正的脚本问题有两条独立出口：{@code compile-failed}（有源码但编译不过）
     * 与游戏侧的 {@code Error loading [X]}（脚本最终没能加载）。
     */
    private static void noteNoSource(String className) {
        MISSES.incrementAndGet();
        if (NOTICE.compareAndSet(false, true)) {
            MixinLog.info("[Janino] no-source 不逐条记录（首例 " + className
                    + "）：多为按包名逐个试探的查找；脚本编译失败见 compile-failed");
        }
    }
}
