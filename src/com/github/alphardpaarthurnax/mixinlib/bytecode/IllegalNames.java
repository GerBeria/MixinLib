package com.github.alphardpaarthurnax.mixinlib.bytecode;

/**
 * 混淆器生成的<b>非法成员名</b>到合法名的规则映射。
 *
 * <h2>为什么是规则而不是表</h2>
 * 2026-11-07 全量普查（`starfarer_obf.jar` 2818 类 + `starfarer.api.jar` 3450 类）：
 * 去重后的非法成员名恰好 <b>2018 个</b>，形态 <b>100%</b> 是
 * {@code 关键字.关键字} 或 {@code 关键字.关键字$关键字}，左段来自下面这 18 个符号，
 * 没有第二种形态。它们是混淆器把 Java 关键字当符号池、按笛卡尔积拼出来的——
 * 与游戏内容无关，因此<b>跨版本、跨汉化包完全相同</b>（两包交集 2018、差集 0）。
 *
 * <h2>与 Fast Rendering 的等价性</h2>
 * FR 的 {@code IllegalTransformations} 用 18×18 + 18×18×18 = 6156 条 Map 表达同一规则。
 * 这里改成按规则判定，结果<b>逐条等价</b>（已用 2018 个真实名字双向验证），但省掉
 * 6156 条 Map 的静态初始化与查找。目标名必须是 {@code A_B}——与 FR 一致，
 * 这样两者同时存在时谁先跑都幂等（先跑者改完，后跑者的 key 消失，无害）。
 *
 * <h2>热路径约束</h2>
 * 本类在 {@code ClassFileLoadHook} 回调里被调用（每类一次），因此：
 * <ul>
 *   <li><b>不做字符串拼接</b>——{@code +} 会链接 {@code java.lang.invoke}，
 *       而它可能正被定义，造成 {@code ClassCircularityError}（D1 实测）；</li>
 *   <li>本类只提供「判定」，实际替换由 {@link ConstPoolSanitizer} 在<b>字节层面</b>做
 *       （{@code '.'} → {@code '_'}，长度不变，无需重建数组）。</li>
 * </ul>
 */
public final class IllegalNames {

    /**
     * 与 FR {@code IllegalTransformations.symbols} 完全相同的 18 个符号。
     * 顺序不影响结果（规则是笛卡尔积），保持一致只为便于对照。
     */
    private static final String[] SYMBOLS = {
            "class", "do", "float", "for", "if", "int", "interface",
            "new", "null", "Object", "private", "public", "return",
            "String", "super", "this", "void", "while"
    };

    /** 同上的 ASCII 字节形式，供 {@link #illegalDot} 做零分配比较。 */
    private static final byte[][] SYMBOL_BYTES = new byte[SYMBOLS.length][];

    /** 符号最短长度（`do`/`if`）与最长长度（`interface`）。 */
    private static final int MIN_LEN = 2;
    private static final int MAX_LEN = 9;

    private static final byte DOT = (byte) '.';
    private static final byte UNDERSCORE = (byte) '_';
    private static final byte DOLLAR = (byte) '$';

    static {
        for (int i = 0; i < SYMBOLS.length; i++) {
            String s = SYMBOLS[i];
            byte[] b = new byte[s.length()];
            for (int j = 0; j < b.length; j++) {
                b[j] = (byte) s.charAt(j);
            }
            SYMBOL_BYTES[i] = b;
        }
    }

    private IllegalNames() {
    }

    /** 必须在 {@code addTransformer} 之前调用：完成本类与 {@code String} 相关加载。 */
    static void warmUp() {
        isSymbol("class");
        isSymbol("if");
        isSymbol("interface");
        isSymbol("while");
    }

    /**
     * 在一个 UTF8 常量的字节区间里，判断它是否符合
     * {@code 符号.符号} / {@code 符号.符号$符号} 的形态。
     *
     * @return 待替换的那个 {@code '.'} 在 {@code data} 中的绝对偏移；不符合规则返回 {@code -1}
     */
    static int illegalDot(byte[] data, int start, int end) {
        int len = end - start;
        // 最短组合 "do.if" = 5，最长 "interface.interface$interface" = 29。
        if (len < 5 || len > 29) {
            return -1;
        }
        int dot = -1;
        for (int i = start; i < end; i++) {
            if (data[i] == DOT) {
                if (dot >= 0) {
                    return -1;                  // 多个点：不是本规则的形态，保守放行
                }
                dot = i;
            }
        }
        if (dot < 0) {
            return -1;
        }
        int leftLen = dot - start;
        if (leftLen < MIN_LEN || leftLen > MAX_LEN || !isSymbol(data, start, dot)) {
            return -1;
        }
        // 右段：符号，或 符号$符号
        int restStart = dot + 1;
        int restLen = end - restStart;
        if (restLen < MIN_LEN) {
            return -1;
        }
        if (isSymbol(data, restStart, end)) {
            return dot;
        }
        int dollar = -1;
        for (int i = restStart; i < end; i++) {
            if (data[i] == DOLLAR) {
                if (dollar >= 0) {
                    return -1;                  // 多个 $：不处理
                }
                dollar = i;
            }
        }
        if (dollar < 0) {
            return -1;
        }
        if (!isSymbol(data, restStart, dollar) || !isSymbol(data, dollar + 1, end)) {
            return -1;
        }
        return dot;
    }

    /** 字节区间是否恰好等于 18 个符号之一。零分配。 */
    private static boolean isSymbol(byte[] data, int start, int end) {
        int len = end - start;
        if (len < MIN_LEN || len > MAX_LEN) {
            return false;
        }
        byte first = data[start];
        for (byte[] symbol : SYMBOL_BYTES) {
            if (symbol.length != len || symbol[0] != first) {
                continue;
            }
            int i = 1;
            while (i < len && symbol[i] == data[start + i]) {
                i++;
            }
            if (i == len) {
                return true;
            }
        }
        return false;
    }

    /** 字符串版，仅供离线门禁与诊断使用；热路径走 {@link #illegalDot}。 */
    public static boolean isSymbol(String s) {
        if (s == null || s.length() < MIN_LEN || s.length() > MAX_LEN) {
            return false;
        }
        for (String symbol : SYMBOLS) {
            if (symbol.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 字符串版替换，仅供离线门禁与诊断。
     *
     * @return 替换结果；不需要替换或不符合规则返回 {@code null}
     */
    public static String translate(String name) {
        if (name == null) {
            return null;
        }
        int dot = -1;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '.') {
                if (dot >= 0) {
                    return null;
                }
                dot = i;
            }
        }
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        String left = name.substring(0, dot);
        if (!isSymbol(left)) {
            return null;
        }
        String rest = name.substring(dot + 1);
        if (isSymbol(rest)) {
            return left + '_' + rest;
        }
        int dollar = rest.indexOf('$');
        if (dollar <= 0 || dollar == rest.length() - 1) {
            return null;
        }
        if (rest.indexOf('$', dollar + 1) >= 0) {
            return null;
        }
        if (!isSymbol(rest.substring(0, dollar)) || !isSymbol(rest.substring(dollar + 1))) {
            return null;
        }
        return left + '_' + rest;
    }

    /** 替换字符常量，供 {@link ConstPoolSanitizer} 复用。 */
    static byte replacement() {
        return UNDERSCORE;
    }
}
