package com.solosu.mtforum.network

import java.util.regex.Matcher
import java.util.regex.Pattern

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
 * 调用方需配合 [looksLikeChallenge] 判断。失配（本地求解后仍返回挑战页）时不再
 * 计入熔断窗口，而是由 [HttpClient.hasPendingWafChallenge] 置位，交由
 * WafVerificationActivity 让用户手动通过人机验证（见 HttpClient 挑战处理）。
 */
object WafChallenge {

    /** 挑战 Cookie 名 */
    const val COOKIE_NAME = "acw_sc__v2"

    /** arg1 提取：var arg1='3B4CE26B…' （长度不固定，32~40 位十六进制实测） */
    private val P_ARG1 =
        Pattern.compile("var\\s+arg1\\s*=\\s*['\"]([0-9A-Fa-f]+)['\"]")

    /** 40 项位置置换表（1-based 值）。修改站点后需重新采样更新。 */
    private val PERMUTATION = intArrayOf(
        15, 35, 29, 24, 33, 16, 1, 38, 10, 9,
        19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
        39, 18, 20, 8, 14, 21, 32, 26, 2, 30,
        7, 4, 17, 5, 3, 28, 34, 37, 12, 36,
    )

    /** 异或密钥（十六进制字符串），与 PERMUTATION 同周期更新。 */
    private const val XOR_KEY_HEX = "3000176000856006061501533003690027800375"

    /**
     * 判断响应体是否为 WAF 挑战页。
     *
     * <p>判定分两级：先按「没有 {@code <body>} 的完整 HTML + 内嵌 script」识别经典注入式
     * 挑战页；对可能带 {@code <body>} 的新版挑战页，再用阿里云挑战标识
     * （{@code acw_sc__v2} / {@code arg1=}）兜底。
     *
     * <p>不直接依赖 HTTP 403：本站下发的 JS 挑战页是 200 正常响应的 HTML，浏览器执行
     * 脚本写 Cookie 后 reload 才放行；而 403 也可能是非 JS 挑战的硬拦截，两者不能混判。
     */
    @JvmStatic
    fun looksLikeChallenge(body: String?): Boolean {
        if (body == null || body.isEmpty()) return false
        // 真实论坛页远大于挑战页（实测挑战页约 4 KB，论坛页 16 万+ 字符）
        if (body.length > 64 * 1024) return false
        val lower = body.lowercase()
        if (!lower.contains("<html")) return false
        if (!lower.contains("<body")) {
            // 经典注入式挑战页：无 body、有 script/meta
            return lower.contains("<script") || lower.contains("<meta")
        }
        // 新版挑战页可能已带 body，但必定出现阿里云挑战标识
        return lower.contains("acw_sc__v2") || P_ARG1.matcher(body).find()
    }

    /** 从挑战页提取 arg1，提取不到返回 null。 */
    @JvmStatic
    fun extractArg1(body: String?): String? {
        if (body == null || body.isEmpty()) return null
        val m: Matcher = P_ARG1.matcher(body)
        return if (m.find()) m.group(1) else null
    }

    /**
     * 由 arg1 计算 acw_sc__v2 的值。
     *
     * @return 小写十六进制串；arg1 非法时返回 null
     */
    @JvmStatic
    fun solve(arg1: String?): String? {
        if (arg1 == null || arg1.isEmpty()) return null
        val src = arg1.trim()
        // 必须为偶数长度十六进制
        if ((src.length and 1) != 0) return null
        for (i in src.indices) {
            if (Character.digit(src[i], 16) < 0) return null
        }

        val key = XOR_KEY_HEX
        if (key.length < src.length) return null

        // ① 按置换表重排：PERMUTATION[z] == x+1 时，把 arg1 第 x 个字符放到第 z 位
        val reordered = CharArray(PERMUTATION.size)
        for (x in src.indices) {
            val ch = src[x]
            for (z in PERMUTATION.indices) {
                if (PERMUTATION[z] == x + 1) {
                    reordered[z] = ch
                }
            }
        }

        // ② 逐字节与密钥异或，输出两位小写 hex
        val out = StringBuilder(src.length)
        var i = 0
        while (i < reordered.size) {
            val a = Character.digit(reordered[i], 16)
            val b = Character.digit(reordered[i + 1], 16)
            val k1 = Character.digit(key[i], 16)
            val k2 = Character.digit(key[i + 1], 16)
            if (a < 0 || b < 0 || k1 < 0 || k2 < 0) return null
            val v = ((a shl 4) or b) xor ((k1 shl 4) or k2)
            out.append(String.format("%02x", v))
            i += 2
        }
        return out.toString()
    }

    /** 一步到位：从挑战页直接算出 Cookie 值，失败返回 null。 */
    @JvmStatic
    fun solveFromPage(body: String?): String? {
        val arg1 = extractArg1(body)
        if (arg1 == null || arg1.isEmpty()) return null
        return solve(arg1)
    }
}
