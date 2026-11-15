package com.github.alphardpaarthurnax.mixinlib.bytecode;

/**
 * 常量池「非法成员名 → 合法成员名」清洗器。
 *
 * <h2>要解决的问题</h2>
 * Starsector 核心 jar 里 37% 的类含非法成员名（{@code float.super} 这类以 {@code .}
 * 分隔的名字，违反 JVMS 4.2.2 的 unqualified name 约束）。宽松模式下 JVM 不校验，
 * 但<b>只要有任何 transformer 为该类返回过非 null 字节</b>，JDK 28 就强制完整校验，
 * 立刻 {@code ClassFormatError: Illegal field name}。
 *
 * <h2>为什么可以只改字节、不动长度</h2>
 * 规则 {@code A.B → A_B} 是<b>等长替换</b>：{@code '.'}(0x2E) 换成 {@code '_'}(0x5F)，
 * 长度字段不变、常量池条目数不变、所有索引不变、Code 内偏移不变，
 * 因此 {@code StackMapTable} 完全不受影响。这是刻意选择的形态：
 * FR 的 {@code IllegalTransformations} 也全是等长替换。
 *
 * <h2>为什么必须全局做</h2>
 * {@code X} 里声明的 {@code float.new} 与 {@code Y} 里对它的 {@code getfield} 是两个
 * 独立常量池。只改一边必 {@code NoSuchFieldError}。所以调用方必须对<b>所有</b>类调用，
 * 不能只对织入目标调用。
 *
 * <h2>与「谁先谁后」无关</h2>
 * 本方法不关心 FR 是否已经洗过：已洗过的字节里没有符合规则的名字，直接返回 {@code null}。
 * 因此与 FR 共存时零开销、零冲突，注册顺序任意（前提是目标名同为 {@code A_B}）。
 */
public final class ConstPoolSanitizer {

    private ConstPoolSanitizer() {
    }

    /** 必须在 {@code addTransformer} 之前调用。 */
    public static void warmUp() {
        IllegalNames.warmUp();
    }

    /**
     * @return 清洗后的新字节；没有可清洗的名字、或结构无法解析时返回 {@code null}
     *         （调用方必须把 {@code null} 当作「原样放行」，不得当作失败）
     */
    public static byte[] sanitize(byte[] original) {
        if (original == null || original.length < 12) {
            return null;
        }
        try {
            return scan(original);
        } catch (Throwable t) {
            // 结构异常就不动：宁可让该类保持原样（可能仍非法），也不要交出半个字节。
            return null;
        }
    }

    private static byte[] scan(byte[] b) {
        int count = u2(b, 8);
        int p = 10;
        byte[] copy = null;
        for (int i = 1; i < count; i++) {
            int tag = b[p] & 0xFF;
            switch (tag) {
                case 1 -> {                                  // Utf8
                    int len = u2(b, p + 1);
                    int start = p + 3;
                    int end = start + len;
                    if (end > b.length) {
                        return null;
                    }
                    int dot = IllegalNames.illegalDot(b, start, end);
                    if (dot >= 0) {
                        if (copy == null) {
                            copy = b.clone();
                        }
                        copy[dot] = IllegalNames.replacement();
                    }
                    p = end;
                }
                case 3, 4 -> p += 5;                          // Integer / Float
                case 5, 6 -> {                                // Long / Double 占两个 slot
                    p += 9;
                    i++;
                }
                case 7, 8, 16, 19, 20 -> p += 3;               // Class/String/MethodType/Module/Package
                case 9, 10, 11, 12, 17, 18 -> p += 5;           // Field/Method/IfMethod/NameAndType/Dynamic/InvokeDynamic
                case 15 -> p += 4;                             // MethodHandle
                default -> {
                    return null;                              // 未知 tag：不猜
                }
            }
            if (p > b.length) {
                return null;
            }
        }
        return copy;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }
}
