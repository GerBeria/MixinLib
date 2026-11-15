package com.github.alphardpaarthurnax.mixinlib.bytecode;

/**
 * StackMapTable 里 {@code Uninitialized(offset)} 的合法性检查。
 *
 * <h2>判据的来源</h2>
 * JVMS 4.7.4：{@code Uninitialized_variable_info} 的 {@code offset_of_new} 必须是
 * <b>一条 {@code new} 指令</b>在 code 数组里的偏移。HotSpot 在 verification 阶段校验这一点，
 * 不满足就抛 {@code ClassFormatError: StackMapTable format error: bad offset for Uninitialized}。
 *
 * <p>实测（2026-11-07，两套汉化包 × 全部核心 jar 字节级全扫）：违反该约束的只有
 * <b>6 个类</b>，且它们的坏帧是 <b>ZYT 汉化重打包引入</b>的
 * （YHCS 的同名类全部合法、坏帧数为 0），不是运行时改写造成的：
 * <pre>
 *   CampaignGameManager.o00000(...)            4 处，偏移指向 ICONST_2
 *   codex2/CodexDetailPanel.showDetail(...)    4 处
 *   ui/B/new.&lt;init&gt;(Ship)                     8 处
 *   FGRaidAction.orderFleetMovements(...)      4 处
 *   CRPluginImpl.getCREffectDetails(...)       4 处
 * </pre>
 *
 * <h2>为什么必须自己查</h2>
 * 这 5 个类在「无 transformer 返回字节」的宽松路径下能加载；一旦链上任何 transformer
 * 为它们返回了非 null 字节（本环境的 FR 会改名 {@code float.class → float_class}），
 * JDK 28 就强制完整校验，于是必崩。崩溃点在**链接**阶段而不是 {@code defineClass}，
 * 所以只能在字节层面预判，不能靠 try/catch 兜住。
 *
 * <h2>实现约束</h2>
 * 纯字节扫描：不构 ASM 树、不分配中间数组、不加载任何类——这个方法每加载一个类都会被调用一次
 * （本环境约 3300+ 次），必须做到「没问题时零分配」。
 */
public final class FrameSanity {

    private static final int TAG_UTF8 = 1;
    private static final int NEW_OPCODE = 0xBB;

    private FrameSanity() {
    }

    /**
     * 必须在 {@code addTransformer} 之前调用一次：确保本类在注册前完成加载，
     * 否则回调里的首次加载会 {@code ClassCircularityError}（D1）。
     */
    static void warmUp() {
        // 无外部依赖；调用即触发本类的类加载与验证。
        hasBadFrames(new byte[]{0, 0, 0, 0});
    }

    /** 是否存在坏的 {@code Uninitialized} 偏移。无问题时空转一遍，不分配对象。 */
    /** 离线门禁与诊断用；热路径由 {@link FrameRepair#autoRepair} 内部调用。 */
    public static boolean hasBadFrames(byte[] bytes) {
        return scan(bytes, false, null) != null;
    }

    /**
     * 细节版：返回首个出问题的方法（{@code name desc}）与坏偏移数量，供日志用。
     * 只在已经判定为坏类时调用（罕见），这里的分配无所谓。
     */
    /** 诊断用：返回第一处坏帧的可读描述。 */
    public static String describe(byte[] bytes) {
        String[] out = new String[1];
        scan(bytes, true, out);
        return out[0];
    }

    /**
     * @param detail 为 true 时把首个问题方法写进 {@code detailOut[0]}
     * @return null 表示合法；否则返回非 null 标记（detail 模式下同时写 detailOut）
     */
    private static String scan(byte[] bytes, boolean detail, String[] detailOut) {
        int length = bytes == null ? 0 : bytes.length;
        if (length < 12 || u4(bytes, 0) != 0xCAFEBABEL) {
            return null;
        }

        int pos = 8;
        int constantCount = u2(bytes, pos);
        pos += 2;
        if (constantCount <= 0) {
            return null;
        }

        int codeNameIndex = 0;
        int frameNameIndex = 0;
        int[] utf8Offset = detail ? new int[constantCount] : null;
        int[] utf8Length = detail ? new int[constantCount] : null;

        for (int i = 1; i < constantCount; i++) {
            if (pos >= length) {
                return null;
            }
            int tag = bytes[pos] & 0xFF;
            pos++;
            switch (tag) {
                case TAG_UTF8: {
                    if (pos + 2 > length) {
                        return null;
                    }
                    int size = u2(bytes, pos);
                    pos += 2;
                    if (pos + size > length) {
                        return null;
                    }
                    if (detail) {
                        utf8Offset[i] = pos;
                        utf8Length[i] = size;
                    }
                    if (size == 4 && matches(bytes, pos, "Code")) {
                        codeNameIndex = i;
                    } else if (size == 13 && matches(bytes, pos, "StackMapTable")) {
                        frameNameIndex = i;
                    }
                    pos += size;
                    break;
                }
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    pos += 2;
                    break;
                case 15:
                    pos += 3;
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    pos += 4;
                    break;
                case 5:
                case 6:
                    pos += 8;
                    i++;
                    break;
                default:
                    // 不认识的常量池标签：交给 JVM 自己报，这里不猜。
                    return null;
            }
        }

        // 整个类没有 StackMapTable：无需检查。
        if (codeNameIndex == 0 || frameNameIndex == 0) {
            return null;
        }

        // class 头：access_flags / this_class / super_class
        pos += 6;
        if (pos + 2 > length) {
            return null;
        }
        int interfaces = u2(bytes, pos);
        pos += 2 + 2 * interfaces;
        if (pos + 2 > length) {
            return null;
        }
        int fields = u2(bytes, pos);
        pos += 2;
        for (int i = 0; i < fields; i++) {
            pos += 6;
            pos = skipAttributes(bytes, pos, length);
            if (pos < 0) {
                return null;
            }
        }

        if (pos + 2 > length) {
            return null;
        }
        int methods = u2(bytes, pos);
        pos += 2;
        for (int i = 0; i < methods; i++) {
            if (pos + 6 > length) {
                return null;
            }
            int nameIndex = u2(bytes, pos + 2);
            int descIndex = u2(bytes, pos + 4);
            pos += 6;
            if (pos + 2 > length) {
                return null;
            }
            int attributes = u2(bytes, pos);
            pos += 2;
            for (int j = 0; j < attributes; j++) {
                if (pos + 6 > length) {
                    return null;
                }
                int attributeName = u2(bytes, pos);
                long attributeLength = u4(bytes, pos + 2);
                pos += 6;
                int end = (int) Math.min((long) pos + attributeLength, length);
                if (attributeName == codeNameIndex) {
                    int bad = checkCode(bytes, pos, end, frameNameIndex);
                    if (bad != 0) {
                        if (detailOut != null) {
                            detailOut[0] = name(bytes, utf8Offset, utf8Length, nameIndex) + " "
                                    + name(bytes, utf8Offset, utf8Length, descIndex) + " (" + bad + " bad)";
                        }
                        return "bad";
                    }
                }
                pos = end;
            }
        }
        return null;
    }

    /** @return 0 = 正常；否则为坏 {@code Uninitialized} 的处数（上限 99） */
    private static int checkCode(byte[] bytes, int start, int end, int frameNameIndex) {
        if (start + 8 > end) {
            return 0;
        }
        int codeLength = (int) u4(bytes, start + 4);
        int code = start + 8;
        if (codeLength < 0 || code + codeLength > end) {
            return 0;
        }
        int pos = code + codeLength;
        if (pos + 2 > end) {
            return 0;
        }
        int exceptions = u2(bytes, pos);
        pos += 2 + 8 * exceptions;
        if (pos + 2 > end) {
            return 0;
        }
        int attributes = u2(bytes, pos);
        pos += 2;
        for (int i = 0; i < attributes; i++) {
            if (pos + 6 > end) {
                return 0;
            }
            int attributeName = u2(bytes, pos);
            long attributeLength = u4(bytes, pos + 2);
            pos += 6;
            int attributeEnd = (int) Math.min((long) pos + attributeLength, end);
            if (attributeName == frameNameIndex) {
                return countBadUninitialized(bytes, pos, attributeEnd, code, codeLength);
            }
            pos = attributeEnd;
        }
        return 0;
    }

    /** 遍历 StackMapTable 的各个 frame，统计指向非 {@code new} 的 Uninitialized。 */
    private static int countBadUninitialized(byte[] bytes, int start, int end,
                                             int codeStart, int codeLength) {
        if (start + 2 > end) {
            return 0;
        }
        int entries = u2(bytes, start);
        int pos = start + 2;
        int bad = 0;
        for (int i = 0; i < entries; i++) {
            if (pos >= end) {
                // 结构被截断：按坏处理，交给重算去救；救不回来 FrameRepair 会放弃。
                bad++;
                break;
            }
            int frameType = bytes[pos] & 0xFF;
            pos++;
            if (frameType <= 63) {
                continue;
            }
            if (frameType <= 127) {
                pos = stepVerificationType(bytes, pos, end, codeStart, codeLength);
                if (pos < 0) {
                    bad++;
                    break;
                }
                continue;
            }
            if (frameType == 247) {
                pos += 2;
                pos = stepVerificationType(bytes, pos, end, codeStart, codeLength);
                if (pos < 0) {
                    bad++;
                    break;
                }
                continue;
            }
            if (frameType <= 251) {
                pos += 2;
                continue;
            }
            if (frameType <= 254) {
                pos += 2;
                int locals = frameType - 251;
                for (int k = 0; k < locals; k++) {
                    pos = stepVerificationType(bytes, pos, end, codeStart, codeLength);
                    if (pos < 0) {
                        bad++;
                        break;
                    }
                }
                if (pos < 0) {
                    break;
                }
                continue;
            }
            if (frameType == 255) {
                if (pos + 2 > end) {
                    bad++;
                    break;
                }
                pos += 2;
                int locals = u2(bytes, pos);
                pos += 2;
                for (int k = 0; k < locals; k++) {
                    pos = stepVerificationType(bytes, pos, end, codeStart, codeLength);
                    if (pos < 0) {
                        bad++;
                        break;
                    }
                }
                if (pos < 0) {
                    break;
                }
                if (pos + 2 > end) {
                    bad++;
                    break;
                }
                int stack = u2(bytes, pos);
                pos += 2;
                for (int k = 0; k < stack; k++) {
                    pos = stepVerificationType(bytes, pos, end, codeStart, codeLength);
                    if (pos < 0) {
                        bad++;
                        break;
                    }
                }
                if (pos < 0) {
                    break;
                }
                continue;
            }
            // 128..246 是保留值。
            break;
        }
        return bad;
    }

    /** @return 下一个位置；发现坏的 Uninitialized 时返回 -1 */
    private static int stepVerificationType(byte[] bytes, int pos, int end,
                                            int codeStart, int codeLength) {
        if (pos >= end) {
            return -1;
        }
        int tag = bytes[pos] & 0xFF;
        pos++;
        if (tag == 7) {
            return pos + 2 <= end ? pos + 2 : -1;
        }
        if (tag == 8) {
            if (pos + 2 > end) {
                return -1;
            }
            int offset = u2(bytes, pos);
            if (offset < 0 || offset >= codeLength
                    || (bytes[codeStart + offset] & 0xFF) != NEW_OPCODE) {
                return -1;
            }
            return pos + 2;
        }
        return pos;
    }

    private static int skipAttributes(byte[] bytes, int pos, int length) {
        if (pos + 2 > length) {
            return -1;
        }
        int count = u2(bytes, pos);
        pos += 2;
        for (int i = 0; i < count; i++) {
            if (pos + 6 > length) {
                return -1;
            }
            long size = u4(bytes, pos + 2);
            pos += 6;
            if ((long) pos + size > length) {
                return -1;
            }
            pos += (int) size;
        }
        return pos;
    }

    private static String name(byte[] bytes, int[] offsets, int[] lengths, int index) {
        if (offsets == null || index <= 0 || index >= offsets.length) {
            return "#" + index;
        }
        int offset = offsets[index];
        int length = lengths[index];
        if (offset <= 0 || offset + length > bytes.length) {
            return "#" + index;
        }
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean matches(byte[] bytes, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if ((bytes[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u2(byte[] bytes, int pos) {
        return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
    }

    private static long u4(byte[] bytes, int pos) {
        return ((long) (bytes[pos] & 0xFF) << 24)
                | ((long) (bytes[pos + 1] & 0xFF) << 16)
                | ((long) (bytes[pos + 2] & 0xFF) << 8)
                | (bytes[pos + 3] & 0xFF);
    }
}
