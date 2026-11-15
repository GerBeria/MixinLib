package com.github.alphardpaarthurnax.mixinlib.loader;

/**
 * 类的字节来源类别。
 *
 * <p>分类基准是「字节从哪里来」，而不是「由哪个加载器定义」：CORE / MOD 来自
 * LaunchSnapshot 交接的两组 jar，JANINO 来自 Janino 运行时编译，GENERATED 来自
 * mixin 生成，HOST 由父加载器提供。JANINO 单独成类，不与 CORE / MOD 混淆。
 */
public enum ClassOrigin {
    CORE,
    MOD,
    JANINO,
    GENERATED,
    HOST
}
