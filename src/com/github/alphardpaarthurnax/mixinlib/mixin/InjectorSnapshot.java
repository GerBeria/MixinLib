package com.github.alphardpaarthurnax.mixinlib.mixin;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.Config;

/**
 * L2 注入器注册表快照 + ③ plugin 启动自检。
 *
 * <p>两者时机不同（S1-T1 实证）：L2 在 {@code createTransformer()} 之后立即输出；
 * ③ 必须延迟到首次 {@code transformClass()} 之后 —— {@code IMixinConfig.getPlugin()}
 * 在 {@code onSelect()} 之前会抛 NPE（{@code this.plugin is null}），太早查没有意义。
 */
public final class InjectorSnapshot {
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static final AtomicBoolean POST_TRANSFORM_ANNOUNCED = new AtomicBoolean();
    private static final AtomicBoolean SELF_CHECKED = new AtomicBoolean();
    private static volatile List<PluginExpectation> expectations = List.of();

    private InjectorSnapshot() {
    }

    /** config 名与它在自己的 json 里声明的 plugin 类名（null = 未声明）。 */
    public record PluginExpectation(String configName, String pluginClass) {
    }

    public static void configure(List<PluginExpectation> values) {
        expectations = List.copyOf(values);
    }

    /** L2：注解注册表 + 每个 config 的 plugin 声明。只跑一次。 */
    public static void announce() {
        if (!ANNOUNCED.compareAndSet(false, true)) {
            return;
        }
        logRegistry("startup");
        for (PluginExpectation expectation : expectations) {
            MixinLog.info("[Injector] config=" + expectation.configName()
                    + " pluginDeclared="
                    + (expectation.pluginClass() == null ? "none" : expectation.pluginClass()));
        }
    }

    /**
     * 输出当前注入器注解注册表。单独成方法，供「初始化后」与「首次织入后」两个时机复用
     * —— MixinExtras 会在首次 transformClass() 之后再注册一部分注入器，两次数值不同。
     */
    private static void logRegistry(String stage) {
        try {
            Set<Class<? extends Annotation>> registered = InjectionInfo.getRegisteredAnnotations();
            List<String> names = new ArrayList<>();
            for (Class<? extends Annotation> annotation : registered) {
                names.add("@" + annotation.getSimpleName());
            }
            Collections.sort(names);
            MixinLog.info("[Injector] registered=" + registered.size()
                    + " stage=" + stage
                    + " annotations=" + String.join(",", names));
        } catch (Throwable throwable) {
            MixinLog.error("[Injector] 注入器注解注册表快照失败 stage=" + stage, throwable);
        }
    }

    /**
     * ③：由 {@code TargetClassLoader} / {@code RuntimeTransformer} 在首次
     * {@code transformClass()} 返回之后触发，一次性闸；同时补一条首次织入后的注册表快照。
     *
     * <p>默认只告警；{@code -Dmixinlib.plugin.strict=true} 时以异常硬失败。
     */
    public static void runSelfCheckIfNeeded() {
        // 独立于下方 expectations 早退：即使没有任何 config 声明 plugin，也要记录首次织入后的注册表。
        if (POST_TRANSFORM_ANNOUNCED.compareAndSet(false, true)) {
            logRegistry("post-first-transform");
        }
        List<PluginExpectation> current = expectations;
        if (current.isEmpty() || !SELF_CHECKED.compareAndSet(false, true)) {
            return;
        }
        boolean strict = Boolean.getBoolean("mixinlib.plugin.strict");
        for (PluginExpectation expectation : current) {
            if (expectation.pluginClass() == null) {
                continue;
            }
            String status = statusOf(expectation.configName());
            MixinLog.info("[SelfCheck] MixinRuntime - config=" + expectation.configName()
                    + " plugin=" + expectation.pluginClass() + " status=" + status);
            if (!"OK".equals(status) && strict) {
                throw new IllegalStateException("Mixin plugin self-check failed: config="
                        + expectation.configName() + " plugin=" + expectation.pluginClass()
                        + " status=" + status);
            }
        }
    }

    private static String statusOf(String configName) {
        try {
            for (Config config : Mixins.getConfigs()) {
                if (!configName.equals(config.getName())) {
                    continue;
                }
                IMixinConfig target = config.getConfig();
                return target != null && target.getPlugin() != null ? "OK" : "MISSING";
            }
            return "MISSING";
        } catch (Throwable throwable) {
            // T1：onSelect() 之前 getPlugin() 抛 NPE，视为「尚未实例化」而不是启动失败。
            return "UNKNOWN";
        }
    }
}
