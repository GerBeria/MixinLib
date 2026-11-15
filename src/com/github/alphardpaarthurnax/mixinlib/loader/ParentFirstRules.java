package com.github.alphardpaarthurnax.mixinlib.loader;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 父加载器优先（parent-first）规则：命中前缀的类一律交给系统加载器定义，
 * <strong>不进入 TargetClassLoader 的字节转换链，因此不允许被 Mixin 改写</strong>。
 *
 * <p>游戏本体核心（{@code com.fs.*} 以及 {@code starfarer_obf.jar} 内联的
 * {@code org.fossic.*}、{@code fs.sound_obf.jar} 的 {@code sound.*}）刻意不在此列，
 * 仍由 {@link TargetClassLoader} 定义，保持可织入。
 *
 * <p><b>为什么独立成类</b>：不同游戏版本、整合包或未来的插件可能需要增删前缀。
 * 本类把「内置默认清单」与「运行时可修改部分」分开：
 * {@link #CORE_PREFIXES} 是框架自保（JDK、Mixin 框架、MixinLib 自身），不允许停用；
 * {@link #DEPENDENCY_PREFIXES} 是游戏前置依赖，可停用；
 * {@link #addPrefix} / {@link #disablePrefix} / {@link #reset} 供环境或插件调整。
 *
 * <h3>启动 Classpath 实测归属分析</h3>
 * <p>测试环境：Starsector 0.98a (2026-11-15)</p>
 *
 * <p>根据类加载器隔离机制与常量池依赖关系，将启动 Classpath 中的 JAR 划分为以下两类：</p>
 *
 * <h4>1. 前置依赖（Parent-First 加载）</h4>
 * <p>以下第三方库经扫描，其类常量池中均不引用 {@code com.fs.*} 包，
 * 交由系统/父类加载器加载不会引发 {@code NoClassDefFoundError}。</p>
 *
 * <table border="1">
 *   <caption>前置依赖 JAR 归属明细</caption>
 *   <tr><th>JAR 文件</th><th>包名 (Package)</th><th>加载策略</th></tr>
 *   <tr><td>janino / commons-compiler / commons-compiler-jdk</td>
 *       <td>org.codehaus.* / de.unkrig.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>jogg / jorbis</td><td>com.jcraft.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>json.jar</td><td>org.json.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>lwjgl / lwjgl_util</td><td>org.lwjgl.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>jinput.jar</td><td>net.java.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>log4j-1.2.9.jar</td><td>org.apache.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>xstream-1.4.10.jar</td><td>com.thoughtworks.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>webp-imageio-0.1.6.jar</td><td>com.luciad.*</td><td>前置依赖 → parent-first</td></tr>
 *   <tr><td>jaxb-api / txw2</td><td>javax.xml.* / com.sun.xml.*</td>
 *       <td>前置依赖 → 已由 CORE 的 javax. / com.sun. 覆盖</td></tr>
 * </table>
 *
 * <h4>2. 游戏核心（Target 加载器加载）</h4>
 * <p>涉及游戏本体逻辑及内部命名空间耦合，必须保留在 Target 类加载器中。</p>
 *
 * <table border="1">
 *   <caption>游戏核心 JAR 归属明细</caption>
 *   <tr><th>JAR 文件</th><th>包名 (Package)</th><th>加载策略</th></tr>
 *   <tr><td>starfarer.api / starfarer_obf / fs.common_obf</td>
 *       <td>com.fs.*</td><td>游戏核心 → 仍由 target 加载</td></tr>
 *   <tr><td>starfarer_obf.jar (内联)</td><td>org.fossic.*</td>
 *       <td>7 个类反向引用 com.fs.*，需与核心同命名空间，不纳入隔离</td></tr>
 *   <tr><td>fs.sound_obf.jar</td><td>sound.*</td><td>游戏本体 → 仍由 target 加载</td></tr>
 * </table>
 */
public final class ParentFirstRules {
    /** Mixin 合成类必须由 target 定义，优先于所有规则。 */
    private static final String SYNTHETIC = "org.spongepowered.asm.synthetic.";

    /** 框架自保前缀：不可停用，移除会破坏 Mixin 框架与 Loader 契约。 */
    public static final List<String> CORE_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.spongepowered.asm.", "org.objectweb.asm.",
            "com.github.alphardpaarthurnax.mixinlib.",
            "com.llamalad7.mixinextras.");

    /** 游戏前置依赖前缀：可停用、可按环境调整。 */
    public static final List<String> DEPENDENCY_PREFIXES = List.of(
            "org.codehaus.",       // janino / commons-compiler / commons-compiler-jdk
            "de.unkrig.",          // commons-compiler-jdk
            "com.jcraft.",         // jogg / jorbis
            "org.json.",           // json.jar
            "org.lwjgl.",          // lwjgl / lwjgl_util
            "net.java.",           // jinput
            "org.apache.",         // log4j
            "com.thoughtworks.",   // xstream
            "com.luciad.");        // webp-imageio

    private static final Set<String> ADDITIONAL = ConcurrentHashMap.newKeySet();
    private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

    private ParentFirstRules() {
    }

    /** 追加一个包名前缀或精确类名；返回是否真的新增。 */
    public static boolean addPrefix(String prefix) {
        String normalized = normalize(prefix);
        if (normalized == null) {
            return false;
        }
        boolean added = ADDITIONAL.add(normalized);
        if (added) {
            DISABLED.remove(normalized);
            MixinLog.info("[ParentFirst] add=" + normalized + " total=" + effectivePrefixes().size());
        }
        return added;
    }

    /** 批量追加；返回新增条数。 */
    public static int addPrefixes(Collection<String> prefixes) {
        if (prefixes == null) {
            return 0;
        }
        int added = 0;
        for (String prefix : prefixes) {
            if (addPrefix(prefix)) {
                added++;
            }
        }
        return added;
    }

    /**
     * 停用某个前缀，使其回到 target 加载。CORE 前缀不可停用（返回 false）。
     * 主要供「某个整合包把库内联进核心 jar、反向依赖游戏类」这类环境使用。
     */
    public static boolean disablePrefix(String prefix) {
        String normalized = normalize(prefix);
        if (normalized == null || CORE_PREFIXES.contains(normalized)) {
            return false;
        }
        boolean changed = DISABLED.add(normalized);
        if (changed) {
            MixinLog.info("[ParentFirst] disable=" + normalized
                    + " total=" + effectivePrefixes().size());
        }
        return changed;
    }

    /** 重新启用先前停用的前缀；返回是否发生变更。 */
    public static boolean enablePrefix(String prefix) {
        String normalized = normalize(prefix);
        return normalized != null && DISABLED.remove(normalized);
    }

    /** 清空所有外部追加与停用，回到内置默认清单。 */
    public static void reset() {
        ADDITIONAL.clear();
        DISABLED.clear();
    }

    /** 该类名是否应由父加载器优先定义。 */
    public static boolean isParentFirst(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (className.startsWith(SYNTHETIC)) {
            return false;
        }
        for (String prefix : CORE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : DEPENDENCY_PREFIXES) {
            if (!DISABLED.contains(prefix) && className.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : ADDITIONAL) {
            if (!DISABLED.contains(prefix) && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效的前缀快照（CORE + 未停用的依赖 + 外部追加），顺序稳定、去重。 */
    public static List<String> effectivePrefixes() {
        Set<String> result = new LinkedHashSet<>(CORE_PREFIXES);
        for (String prefix : DEPENDENCY_PREFIXES) {
            if (!DISABLED.contains(prefix)) {
                result.add(prefix);
            }
        }
        List<String> extra = new ArrayList<>(ADDITIONAL);
        extra.sort(String::compareTo);
        for (String prefix : extra) {
            if (!DISABLED.contains(prefix)) {
                result.add(prefix);
            }
        }
        return List.copyOf(result);
    }

    /** 诊断用单行摘要。 */
    public static String describe() {
        return "core=" + CORE_PREFIXES.size()
                + " dependencies=" + DEPENDENCY_PREFIXES.size()
                + " additional=" + ADDITIONAL.size()
                + " disabled=" + DISABLED.size()
                + " effective=" + effectivePrefixes().size();
    }

    private static String normalize(String prefix) {
        if (prefix == null) {
            return null;
        }
        String trimmed = prefix.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
