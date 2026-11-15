package com.github.alphardpaarthurnax.mixinlib.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 判断一个类能否安全地被 transformer 改写。
 *
 * <h2>为什么需要它</h2>
 * 2026-11-07 实测（JDK 28-beta 与 JDK 17/21 对照）：<b>只要某个类的字节经过
 * {@code ClassFileLoadHook} 返回，JVM 就会对它做完整格式校验</b>，即使启动参数里带了
 * {@code -XX:-BytecodeVerificationLocal -XX:-BytecodeVerificationRemote}。
 * 未经改写的同一类不受影响。
 *
 * <p>而 Starsector 的混淆类里存在<b>非法成员名</b>——以 {@code .} 分隔，例如
 * {@code CombatMain} 引用的 {@code interface.new}、{@code StarfarerSettings} 声明的
 * 字段 {@code new.super}、{@code GLLauncher} 的 {@code float.new}。这类类一旦被改写就必抛
 * {@code ClassFormatError: Illegal method name ...}，整个 JVM 起不来。
 *
 * <p>实测影响面（0.98a-RC8，2026-11-07 复扫，口径 = 常量池 NameAndType + 声明表）：
 * {@code starfarer_obf.jar} 1042/2818 = 37.0%、{@code fs.common_obf.jar} 3/90、
 * {@code fs.sound_obf.jar} 3/24；{@code starfarer.api.jar} 0/3450。
 *
 * <p>非法名是<b>封闭集合</b>：去重 2018 个，形态 100% 是 {@code 关键字.关键字}
 * （左段恰好 18 个 Java 关键字，右段是 18 个单符号 + 18×18 个 {@code X$Y}），
 * 且两个汉化包的集合完全相同。⇒ 可用规则生成的字符替换表 100% 消除，
 * 不需要任何「混淆名 → 可读名」的映射。
 *
 * <p><b>检查范围必须包括常量池里的 NameAndType</b>，不能只看 declared methods/fields：
 * {@code CombatMain} 自身没有非法声明名，非法名出现在它<b>引用</b>的方法上，JVM 一样拒。
 */
public final class BytecodeLegality {

    private BytecodeLegality() {
    }

    /** 预热：纯字节解析无外部依赖，函数体里不做任何类加载。 */
    public static void warmUp() {
        // 本类不使用 ASM，也不产生内部类，注册后调用不会触发类加载。
    }

    /**
     * @return {@code null} 表示可安全改写；否则返回第一处非法名（如 {@code method interface.new}）
     */
    public static String firstIllegalMemberName(byte[] classfileBuffer) {
        if (classfileBuffer == null) {
            return null;
        }
        try {
            return scan(classfileBuffer);
        } catch (Throwable t) {
            // 解析不动就不拦：宁可放行让 JVM 自己报，也不要静默吞掉一次织入。
            return null;
        }
    }

    private static String scan(byte[] b) {
        int p = 8;                                   // magic + minor + major
        int count = u2(b, p);
        p += 2;

        String[] utf8 = new String[count];
        int[] nameAndTypeNameIndex = new int[count];

        for (int i = 1; i < count; i++) {
            int tag = b[p++] & 0xFF;
            switch (tag) {
                case 1 -> {                          // Utf8
                    int len = u2(b, p);
                    p += 2;
                    utf8[i] = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8);
                    p += len;
                }
                case 3, 4 -> p += 4;                 // Integer / Float
                case 5, 6 -> {                       // Long / Double 占两个 slot
                    p += 8;
                    i++;
                }
                case 7, 8, 16, 19, 20 -> p += 2;     // Class / String / MethodType / Module / Package
                case 9, 10, 11, 17, 18 -> p += 4;    // Field/Method/InterfaceMethod/Dynamic/InvokeDynamic
                case 15 -> p += 3;                   // MethodHandle
                case 12 -> {                         // NameAndType
                    nameAndTypeNameIndex[i] = u2(b, p);
                    p += 4;
                }
                default -> {
                    return null;                     // 未知 tag：不拦
                }
            }
        }

        // 1) 常量池里的名字（覆盖「引用别人的非法方法名」）
        for (int i = 1; i < count; i++) {
            int index = nameAndTypeNameIndex[i];
            if (index > 0 && index < count && illegal(utf8[index])) {
                return "name " + utf8[index];
            }
        }

        // 2) 声明表（字段/方法名直接引用 Utf8，不一定出现在 NameAndType 里）
        p += 6;                                      // access_flags + this_class + super_class
        int interfaces = u2(b, p);
        p += 2 + interfaces * 2;
        for (int pass = 0; pass < 2; pass++) {        // fields then methods
            int members = u2(b, p);
            p += 2;
            for (int i = 0; i < members; i++) {
                int nameIndex = u2(b, p + 2);
                p += 6;
                if (nameIndex > 0 && nameIndex < count && illegal(utf8[nameIndex])) {
                    return (pass == 0 ? "field " : "method ") + utf8[nameIndex];
                }
                p = skipAttributes(b, p);
            }
        }
        return null;
    }

    private static int skipAttributes(byte[] b, int p) {
        int attributes = u2(b, p);
        p += 2;
        for (int i = 0; i < attributes; i++) {
            int length = u4(b, p + 2);
            p += 6 + length;
        }
        return p;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int u4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static boolean illegal(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/') {
                return true;
            }
        }
        return false;
    }

    /** 诊断用：列出所有非法名（不做短路），供排查批量失败。 */
    public static List<String> allIllegalNames(byte[] classfileBuffer) {
        List<String> out = new ArrayList<>();
        try {
            int p = 8;
            int count = u2(classfileBuffer, p);
            p += 2;
            for (int i = 1; i < count; i++) {
                int tag = classfileBuffer[p++] & 0xFF;
                if (tag == 1) {
                    int len = u2(classfileBuffer, p);
                    p += 2;
                    String value = new String(classfileBuffer, p, len,
                            java.nio.charset.StandardCharsets.UTF_8);
                    p += len;
                    if (illegal(value)) {
                        out.add(value);
                    }
                } else if (tag == 3 || tag == 4) {
                    p += 4;
                } else if (tag == 5 || tag == 6) {
                    p += 8;
                    i++;
                } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                    p += 2;
                } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                    p += 4;
                } else if (tag == 15) {
                    p += 3;
                } else {
                    return out;
                }
            }
        } catch (Throwable ignored) {
            // 诊断接口，忽略
        }
        return out;
    }
}
