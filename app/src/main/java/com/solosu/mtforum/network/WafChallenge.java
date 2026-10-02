package com.solosu.mtforum.network;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云 ESA WAF 的 JS 挑战（acw_sc__v2）本地求解。
 *
 * <p>站点 bbs.binmt.cc 在 WAF 判定请求可疑时，不会返回论坛页面，而是返回一段
 * 约 4 KB 的混淆 JS：
 * <pre>
 *   &lt;html&gt;&lt;script&gt;var arg1='3B4CE26B…';(function(a,c){… document.cookie='acw_sc__v2='+v+…;location.reload()}());
 * </pre>
 * 浏览器执行该脚本后会写入 {@code acw_sc__v2} Cookie 并刷新页面，WAF 见到该
 * Cookie 即放行。原生 HTTP 客户端没有 JS 引擎，因此每次都被拦，页面解析结果
 * 恒为空——表现为「界面正常但没有任何内容」。
 *
 * <p>这里把该脚本的算法直接用 Java 复现：把 {@code arg1} 按固定的 40 项置换表
 * 重排，再与固定密钥逐字节异或，输出小写十六进制。算法已用多组真实样本交叉
 * 验证，与浏览器执行结果一致。
 *
 * <p>注意：置换表与密钥取自站点当前版本。若站点更换挑战实现，本类会失配；
 * 调用方需配合 {@link #looksLikeChallenge(String)} 判断，失配时不静默——见
 * HttpClient 中挑战处理后的重试结果检查。
 */
public final class WafChallenge {

    /** 挑战 Cookie 名 */
    public static final String COOKIE_NAME = "acw_sc__v2";

    /** arg1 提取：var arg1='3B4CE26B…' （长度不固定，32~40 位十六进制实测） */
    private static final Pattern P_ARG1 =
            Pattern.compile("var\\s+arg1\\s*=\\s*['\"]([0-9A-Fa-f]+)['\"]");

    /** 40 项位置置换表（1-based 值）。修改站点后需重新采样更新。 */
    private static final int[] PERMUTATION = {
            15, 35, 29, 24, 33, 16, 1, 38, 10, 9,
            19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
            39, 18, 20, 8, 14, 21, 32, 26, 2, 30,
            7, 4, 17, 5, 3, 28, 34, 37, 12, 36,
    };

    /** 异或密钥（十六进制字符串），与 PERMUTATION 同周期更新。 */
    private static final String XOR_KEY_HEX = "3000176000856006061501533003690027800375";

    private WafChallenge() {}

    /**
     * 判断响应体是否为 WAF 挑战页。
     *
     * <p>判定刻意不只看 {@code arg1}：挑战页的共同结构是「没有 {@code <body>}
     * 的完整 HTML 文档 + 内嵌 script」。真实 Discuz 页面必然带 {@code <body>}，
     * 因此该判据对换版后的挑战页同样成立。
     */
    public static boolean looksLikeChallenge(String body) {
        if (body == null || body.isEmpty()) return false;
        // 真实论坛页远大于挑战页（实测挑战页约 4 KB，论坛页 16 万+ 字符）
        if (body.length() > 64 * 1024) return false;
        String lower = body.toLowerCase();
        if (!lower.contains("<html")) return false;
        // 有 body 就不是这种注入式挑战页
        if (lower.contains("<body")) return false;
        return lower.contains("<script") || lower.contains("<meta");
    }

    /** 从挑战页提取 arg1，提取不到返回 null。 */
    public static String extractArg1(String body) {
        if (body == null || body.isEmpty()) return null;
        Matcher m = P_ARG1.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 由 arg1 计算 acw_sc__v2 的值。
     *
     * @return 小写十六进制串；arg1 非法时返回 null
     */
    public static String solve(String arg1) {
        if (arg1 == null || arg1.isEmpty()) return null;
        String src = arg1.trim();
        // 必须为偶数长度十六进制
        if ((src.length() & 1) != 0) return null;
        for (int i = 0; i < src.length(); i++) {
            if (Character.digit(src.charAt(i), 16) < 0) return null;
        }

        String key = XOR_KEY_HEX;
        if (key.length() < src.length()) return null;

        // ① 按置换表重排：PERMUTATION[z] == x+1 时，把 arg1 第 x 个字符放到第 z 位
        char[] reordered = new char[PERMUTATION.length];
        for (int x = 0; x < src.length(); x++) {
            char ch = src.charAt(x);
            for (int z = 0; z < PERMUTATION.length; z++) {
                if (PERMUTATION[z] == x + 1) {
                    reordered[z] = ch;
                }
            }
        }

        // ② 逐字节与密钥异或，输出两位小写 hex
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < reordered.length; i += 2) {
            int a = Character.digit(reordered[i], 16);
            int b = Character.digit(reordered[i + 1], 16);
            int k1 = Character.digit(key.charAt(i), 16);
            int k2 = Character.digit(key.charAt(i + 1), 16);
            if (a < 0 || b < 0 || k1 < 0 || k2 < 0) return null;
            int v = ((a << 4) | b) ^ ((k1 << 4) | k2);
            out.append(String.format("%02x", v));
        }
        return out.toString();
    }

    /** 一步到位：从挑战页直接算出 Cookie 值，失败返回 null。 */
    public static String solveFromPage(String body) {
        String arg1 = extractArg1(body);
        if (arg1 == null || arg1.isEmpty()) return null;
        return solve(arg1);
    }
}
