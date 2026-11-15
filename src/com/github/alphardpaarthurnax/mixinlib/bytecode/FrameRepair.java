package com.github.alphardpaarthurnax.mixinlib.bytecode;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧重算（StackMapTable 修复）。
 *
 * <h2>为什么需要</h2>
 * 「被 transformer 返回过字节」的类，JDK 28 会强制完整校验。而核心 jar 里有
 * 6 个类自带坏的 {@code Uninitialized} 帧（见 {@link FrameSanity} 的实测清单），
 * 宽松路径下能加载，一旦链上任何 transformer 为它们返回非 null 字节（本环境 FR 会做
 * {@code float.class → float_class} 之类改名）就必崩。
 *
 * <p>JDK 28 在「系统加载器 + 有 transformer 返回非 null + 类被链接」时强制 verification，
 * 于是抛 {@code ClassFormatError: StackMapTable format error: bad offset for Uninitialized}。
 * 2026-11-07 实机 + 离线三条件复现确认（缺任一条件都测不出来，这也是之前多轮误判为
 * 「字节合法」的原因）。
 *
 * <h2>为什么不能用 COMPUTE_MAXS 原样往返</h2>
 * ASM 读入时会把坏掉的 {@code Uninitialized(offset)} 原样转成 Label、写出时再按新布局落回，
 * 坏的关系被完整搬过去，实测仍然报同一个错。必须真正重算帧。
 *
 * <h2>为什么不能用默认 ClassWriter</h2>
 * {@code COMPUTE_FRAMES} 会调 {@code getCommonSuperClass}，其默认实现用
 * {@code Class.forName} 加载类 ⇒ 在 {@code ClassFileLoadHook} 回调里必然
 * {@code ClassCircularityError}。这里改为只读 class 字节解析层级，与补丁包
 * {@code LoaderNeutralClassWriter} 的两次实测修正一致：
 * <ul>
 *   <li>接口的 {@code getSuperclass()} 为 null，{@code ArrayDeque} 不接受 null ⇒ 必须判空；</li>
 *   <li>求公共祖先只能沿 type1 的父类链上行，在「祖先集合」里扫会先命中共同接口。</li>
 * </ul>
 */
public final class FrameRepair {

    private static final Map<String, String[]> SUPERS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> ANCESTORS = new ConcurrentHashMap<>();

    private FrameRepair() {
    }

    /** 必须在 {@code addTransformer} 之前调用：把内部类与 ASM 用到的类型全部加载完。 */
    public static void warmUp() {
        new LoaderNeutralWriter(0, null);
        FrameSanity.warmUp();
    }

    /**
     * 自动兜底：只在**确认帧非法**时才重算。
     *
     * <p>两道闸，缺一不可：
     * <ol>
     *   <li>{@link FrameSanity} 判定 {@code Uninitialized} 指向了非 {@code new} 指令 ——
     *       这类字节在强制 verification 下必崩，修它没有下行风险；</li>
     *   <li>{@link BytecodeLegality} 判定没有非法成员名 —— 否则我们返回字节会造成
     *       「本来宽松路径能加载，被我们一交就 Illegal method name」的新故障。</li>
     * </ol>
     *
     * @return 修好的字节；不需要修或修不好都返回 {@code null}（调用方原样放行）
     */
    public static byte[] autoRepair(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        if (!FrameSanity.hasBadFrames(classfileBuffer)) {
            return null;
        }
        String illegal = BytecodeLegality.firstIllegalMemberName(classfileBuffer);
        if (illegal != null) {
            return null;
        }
        String detail = FrameSanity.describe(classfileBuffer);
        byte[] repaired = repair(classfileBuffer, loader);
        if (repaired == null) {
            MixinLog.info("[Repair] cannot recompute frames for " + internalName
                    + " (" + detail + "), leaving it untouched");
            return null;
        }
        if (FrameSanity.hasBadFrames(repaired)) {
            MixinLog.info("[Repair] recompute did not fix " + internalName + " (" + detail + ")");
            return null;
        }
        MixinLog.info("[Repair] " + internalName + " " + detail
                + ": " + classfileBuffer.length + " -> " + repaired.length);
        return repaired;
    }

    /**
     * @return 重算过帧的字节；失败返回 {@code null}（调用方应原样放行）
     */
    public static byte[] repair(byte[] classfileBuffer, ClassLoader loader) {
        try {
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            ClassWriter writer = new LoaderNeutralWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            MixinLog.error("[Repair] cannot recompute frames", t);
            return null;
        }
    }

    /** {superName, isInterface, 逗号分隔接口} */
    private static String[] supers(String type, ClassLoader loader) {
        String[] cached = SUPERS.get(type);
        if (cached != null) {
            return cached;
        }
        String[] computed = computeSupers(type, loader);
        SUPERS.put(type, computed);
        return computed;
    }

    private static String[] computeSupers(String type, ClassLoader loader) {
        if (isJdkType(type)) {
            try {
                Class<?> c = Class.forName(type.replace('/', '.'), false, null);
                Class<?> s = c.getSuperclass();
                StringBuilder sb = new StringBuilder();
                for (Class<?> i : c.getInterfaces()) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(i.getName().replace('.', '/'));
                }
                return new String[]{
                        s == null ? "" : s.getName().replace('.', '/'),
                        c.isInterface() ? "1" : "0",
                        sb.toString()};
            } catch (Throwable ignored) {
                return new String[]{"", "0", ""};
            }
        }
        try (InputStream in = openClass(type, loader)) {
            if (in == null) {
                return new String[]{"", "0", ""};
            }
            ClassReader reader = new ClassReader(in.readAllBytes());
            String[] interfaces = reader.getInterfaces();
            return new String[]{
                    reader.getSuperName() == null ? "" : reader.getSuperName(),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0",
                    interfaces == null ? "" : String.join(",", interfaces)};
        } catch (Throwable ignored) {
            return new String[]{"", "0", ""};
        }
    }

    private static InputStream openClass(String type, ClassLoader loader) {
        String resource = type + ".class";
        if (loader != null) {
            InputStream in = loader.getResourceAsStream(resource);
            if (in != null) {
                return in;
            }
        }
        ClassLoader system = ClassLoader.getSystemClassLoader();
        return system == null ? null : system.getResourceAsStream(resource);
    }

    private static boolean isJdkType(String type) {
        return type.startsWith("java/") || type.startsWith("javax/") || type.startsWith("jdk/")
                || type.startsWith("sun/") || type.startsWith("com/sun/");
    }

    private static Set<String> ancestors(String type, ClassLoader loader) {
        Set<String> cached = ANCESTORS.get(type);
        if (cached != null) {
            return cached;
        }
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || current.isEmpty() || !out.add(current)) {
                continue;
            }
            String[] info = supers(current, loader);
            if (!info[0].isEmpty()) {
                queue.add(info[0]);
            }
            if (!info[2].isEmpty()) {
                for (String each : info[2].split(",")) {
                    if (!each.isEmpty()) {
                        queue.add(each);
                    }
                }
            }
        }
        ANCESTORS.put(type, out);
        return out;
    }

    private static String commonSuper(String type1, String type2, ClassLoader loader) {
        if (type1.equals(type2)) {
            return type1;
        }
        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
            return "java/lang/Object";
        }
        try {
            if (ancestors(type2, loader).contains(type1)) {
                return type1;
            }
            if (ancestors(type1, loader).contains(type2)) {
                return type2;
            }
            if ("1".equals(supers(type1, loader)[1]) || "1".equals(supers(type2, loader)[1])) {
                return "java/lang/Object";
            }
            String current = type1;
            for (int guard = 0; guard < 64; guard++) {
                String superName = supers(current, loader)[0];
                if (superName == null || superName.isEmpty()) {
                    return "java/lang/Object";
                }
                if (ancestors(type2, loader).contains(superName)) {
                    return superName;
                }
                current = superName;
            }
        } catch (Throwable ignored) {
            // 解析不了就退化
        }
        return "java/lang/Object";
    }

    private static final class LoaderNeutralWriter extends ClassWriter {

        private final ClassLoader loader;

        LoaderNeutralWriter(int flags, ClassLoader loader) {
            super(flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return commonSuper(type1, type2, loader);
        }
    }
}
