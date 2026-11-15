package com.github.alphardpaarthurnax.mixinlib.mixin;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不参与 mixin 织入的类名单。
 *
 * <p>「来源」与「判定」分离：当前只由各 config 声明的 plugin 类提供。后续若要追加其它来源
 * （例如插件 jar 的包前缀），只需调用 {@link #add(String, String)} 或
 * {@link #addAll(Collection, String)} 并重新同步 sponge 通道，两条 transform 链路的判定
 * 无需改动。
 */
public final class TransformerExclusions {
    /** 精确类名或包前缀 → 排除原因（日志与诊断用）。 */
    private static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();

    private TransformerExclusions() {
    }

    public static void add(String name, String reason) {
        if (name == null || name.isBlank() || reason == null || reason.isBlank()) {
            return;
        }
        ENTRIES.putIfAbsent(name, reason);
    }

    public static void addAll(Collection<String> names, String reason) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            add(name, reason);
        }
    }

    /** 当前名单快照；后续新增来源只改这个集合，不改判定方式。 */
    public static Map<String, String> entries() {
        return Map.copyOf(ENTRIES);
    }

    /**
     * 命中则返回排除原因，未命中返回 {@code null}。
     *
     * <p>与 sponge 的 {@code getClassRestrictions()} 一致采用前缀匹配，因此 plugin 的内部类
     * （{@code Plugin$Inner}）也一并排除。
     */
    public static String reasonFor(String dottedName) {
        if (dottedName == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : ENTRIES.entrySet()) {
            if (dottedName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static boolean isExcluded(String dottedName) {
        return reasonFor(dottedName) != null;
    }
}
