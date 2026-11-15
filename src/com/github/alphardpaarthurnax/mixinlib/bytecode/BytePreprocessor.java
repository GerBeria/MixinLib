package com.github.alphardpaarthurnax.mixinlib.bytecode;

import com.github.alphardpaarthurnax.mixinlib.loader.ClassOrigin;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import org.objectweb.asm.ClassReader;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 所有交给 {@code TargetClassLoader} 定义的字节在清洗 / 织入之前的统一前置处理。
 *
 * <p>D6 的两套自愈（迁移自 limixinlib 并验证过的实现）在这里对所有类一致执行：
 * <ol>
 *   <li><b>非法名自发现-改名</b>：{@link ConstPoolSanitizer} 在常量池里做等长替换
 *       {@code A.B → A_B}。这是全局契约 —— {@code X} 声明的 {@code float.new} 与
 *       {@code Y} 里对它的 {@code getfield} 分属两个独立常量池，只改一边必
 *       {@code NoSuchFieldError}，所以不能只对 mixin 目标做。</li>
 *   <li><b>损坏栈帧自发现-重算</b>：{@link FrameSanity} 纯字节判定
 *       {@code Uninitialized(offset)} 是否指向 {@code new}(0xBB)；命中才由
 *       {@link FrameRepair} 用 {@code COMPUTE_FRAMES} 真正重算，修完复检。</li>
 * </ol>
 *
 * <p>两条定义链路共用本入口：{@code TargetClassLoader.findClass}（CORE / MOD）与
 * JANINO 分派都走 {@code transform()} → {@link #prepare}；外部子 loader 的类由
 * {@code RuntimeTransformer} 直接调本类。{@link ClassOrigin} 只用于诊断，不参与判定。
 *
 * <p><b>不交半个修正</b>：清洗后若 {@link BytecodeLegality} 仍发现规则外的非法名，
 * 整类回退到原始字节；任何异常都原样放行（fail-open），绝不把可疑字节交给
 * {@code defineClass}。{@link #rejection} 实现 EC-9 的最小 sanity。
 */
public final class BytePreprocessor {
    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private static final AtomicLong INVOKED = new AtomicLong();
    private static final AtomicLong SANITIZED = new AtomicLong();
    private static final AtomicLong FRAME_REPAIRED = new AtomicLong();
    private static final AtomicLong SANITIZE_REJECTED = new AtomicLong();
    private static final AtomicLong FAILED_OPEN = new AtomicLong();

    /** 帧重算需要按 class 字节解析继承层级，由 TargetClassLoader 在构造时登记。 */
    private static volatile ClassLoader namespace;

    private BytePreprocessor() {
    }

    /**
     * 登记用于层级解析的命名空间。只保存引用，不触发编译器或类加载。
     * 未登记时 {@link #prepare} 退化为 {@code BytePreprocessor} 自身的加载器。
     */
    public static void bind(ClassLoader loader) {
        namespace = loader;
    }

    /**
     * 必须在 {@code addTransformer} 之前调用一次：把 5 个迁移类的静态初始化、
     * ASM 内部类与符号表全部提前加载完，避免回调里首次加载触发
     * {@code ClassCircularityError}（limixinlib D1 实测）。
     */
    public static void warmUp() {
        BytecodeLegality.warmUp();
        ConstPoolSanitizer.warmUp();
        FrameRepair.warmUp();
    }

    /** 冻结签名：使用已登记的命名空间。 */
    public static byte[] prepare(String name, byte[] bytes, ClassOrigin origin) {
        ClassLoader current = namespace;
        return prepare(name, bytes, origin,
                current == null ? BytePreprocessor.class.getClassLoader() : current);
    }

    /**
     * 供 {@code RuntimeTransformer} 使用：外部子 loader 的类把实际 loader 传进来，
     * 帧重算才能读到该命名空间下的父类字节。
     *
     * @return 与原数组同一引用表示未改写；绝不返回 {@code null}（除非入参为 {@code null}）
     */
    public static byte[] prepare(String name, byte[] bytes, ClassOrigin origin, ClassLoader loader) {
        if (bytes == null) {
            return null;
        }
        INVOKED.incrementAndGet();
        try {
            byte[] current = bytes;
            byte[] sanitized = ConstPoolSanitizer.sanitize(bytes);
            if (sanitized != null) {
                byte[] candidate = sanitized;
                byte[] repaired = FrameRepair.autoRepair(name, candidate, loader);
                if (repaired != null) {
                    candidate = repaired;
                    FRAME_REPAIRED.incrementAndGet();
                }
                String left = BytecodeLegality.firstIllegalMemberName(candidate);
                if (left != null) {
                    // 规则没覆盖到：宁可交回原字节，也不要交出半个修正。
                    SANITIZE_REJECTED.incrementAndGet();
                    MixinLog.info("[Sanitize] incomplete class=" + name + " reason=" + left
                            + " origin=" + origin);
                    return bytes;
                }
                current = candidate;
                if (SANITIZED.incrementAndGet() == 1) {
                    MixinLog.info("[Sanitize] active from " + name
                            + " (no upstream sanitizer detected)");
                }
            } else {
                // 上游（FR 等）已洗过时 sanitize 返回 null，这里零开销；
                // 仍要对自带坏帧的类做一次自发现。
                byte[] repaired = FrameRepair.autoRepair(name, current, loader);
                if (repaired != null) {
                    current = repaired;
                    FRAME_REPAIRED.incrementAndGet();
                }
            }
            return current;
        } catch (Throwable throwable) {
            FAILED_OPEN.incrementAndGet();
            MixinLog.error("[Sanitize] fail-open class=" + name + " origin=" + origin, throwable);
            return bytes;
        }
    }

    /**
     * EC-9：通过返回 {@code null}，不通过返回可供日志输出的原因。
     */
    public static String rejection(String name, byte[] bytes) {
        if (bytes == null) {
            return "null-bytes";
        }
        if (bytes.length < 8) {
            return "too-short:" + bytes.length;
        }
        int magic = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        if (magic != CLASS_MAGIC) {
            return "bad-magic:" + Integer.toHexString(magic);
        }
        String actual;
        try {
            actual = new ClassReader(bytes).getClassName();
        } catch (Throwable throwable) {
            return "unreadable:" + throwable.getClass().getSimpleName();
        }
        String expected = name.replace('.', '/');
        if (!expected.equals(actual)) {
            return "name-mismatch:expected=" + expected + " actual=" + actual;
        }
        return null;
    }

    /** 供离线门禁与实机诊断；字段名与 limixinlib 的 summary 保持一致。 */
    public static String stats() {
        return "invoked=" + INVOKED.get()
                + " sanitized=" + SANITIZED.get()
                + " frameRepaired=" + FRAME_REPAIRED.get()
                + " sanitizeRejected=" + SANITIZE_REJECTED.get()
                + " failOpen=" + FAILED_OPEN.get();
    }

    public static long sanitized() {
        return SANITIZED.get();
    }

    public static long frameRepaired() {
        return FRAME_REPAIRED.get();
    }

    public static long sanitizeRejected() {
        return SANITIZE_REJECTED.get();
    }

    public static long failOpen() {
        return FAILED_OPEN.get();
    }

    /** 仅供离线装置做计数基线，产品代码不调用。 */
    public static void resetStats() {
        INVOKED.set(0);
        SANITIZED.set(0);
        FRAME_REPAIRED.set(0);
        SANITIZE_REJECTED.set(0);
        FAILED_OPEN.set(0);
    }
}
