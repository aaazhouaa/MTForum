package com.solosu.mtforum.util

import android.content.Context
import com.solosu.mtforum.R
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue

import org.xml.sax.XMLReader

import java.util.ArrayList

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * BBCode 渲染工具
 *
 * 1. convertBBCodeToHtml:Discuz 风格 BBCode -> HTML
 *    - 全部 Pattern 预编译(性能)
 *    - 先提取并保护 [code] 代码块,防止内部 [[b]] 等字面量被误替换
 *    - 扩展标签:size 档位映射、表格降级、indent、user、media/audio/video/flash、sub/sup、spoiler
 * 2. CODE_TAG_HANDLER:Html.fromHtml 的 TagHandler
 *    - 识别 <pre> 代码块,应用等宽字体 + 圆角背景 + 左侧缩进
 * 3. CodeBlockSpan:LineBackgroundSpan,为代码块整块绘制圆角底色
 */
object BBCodeUtil {

    // ==================== 预编译 Pattern(性能:避免每次格式串重新编译) ====================

    private val P_ATTACHIMG = Pattern.compile("(?i)\\[attachimg]([0-9]+)\\[/attachimg]")
    private val P_ATTACH = Pattern.compile("(?i)\\[attach]([0-9]+)\\[/attach]")
    private val P_IMG = Pattern.compile("(?is)\\[img(?:=[^\\]]*)?]\\s*([^\\[\\]]+?)\\s*\\[/img]")
    private val P_CODE = Pattern.compile("(?is)\\[code(?:=([^\\]]+))?](.*?)\\[/code]")
    private val P_QUOTE = Pattern.compile("(?is)\\[quote(?:=([^\\]]+))?](.*?)\\[/quote]")
    private val P_HIDE = Pattern.compile("(?is)\\[hide(?:=([^\\]]+))?](.*?)\\[/hide]")
    private val P_REPLY = Pattern.compile("(?is)\\[reply](.*?)\\[/reply]")
    private val P_FREE = Pattern.compile("(?is)\\[free](.*?)\\[/free]")
    private val P_SPOILER = Pattern.compile("(?is)\\[spoiler](.*?)\\[/spoiler]")
    private val P_B = Pattern.compile("(?is)\\[b](.*?)\\[/b]")
    private val P_I = Pattern.compile("(?is)\\[i](.*?)\\[/i]")
    private val P_U = Pattern.compile("(?is)\\[u](.*?)\\[/u]")
    private val P_S = Pattern.compile("(?is)\\[s](.*?)\\[/s]")
    private val P_DEL = Pattern.compile("(?is)\\[del](.*?)\\[/del]")
    private val P_COLOR = Pattern.compile("(?is)\\[color\\s*=\\s*([^\\]]+)](.*?)\\[/color]")
    private val P_SIZE = Pattern.compile("(?is)\\[size\\s*=\\s*([^\\]]+)](.*?)\\[/size]")
    private val P_FONT = Pattern.compile("(?is)\\[font\\s*=\\s*([^\\]]+)](.*?)\\[/font]")
    private val P_ALIGN = Pattern.compile("(?is)\\[align\\s*=\\s*([^\\]]+)](.*?)\\[/align]")
    private val P_URL1 = Pattern.compile("(?is)\\[url\\s*=\\s*([^\\]\\s]+)]([^\\[]*?)\\[/url]")
    private val P_URL2 = Pattern.compile("(?is)\\[url]([^\\[]+?)\\[/url]")
    private val P_EMAIL1 = Pattern.compile("(?is)\\[email\\s*=\\s*([^\\]]+)](.*?)\\[/email]")
    private val P_EMAIL2 = Pattern.compile("(?is)\\[email]([^\\[]+?)\\[/email]")
    private val P_LIST1 = Pattern.compile("(?is)\\[list\\s*=\\s*1](.*?)\\[/list]")
    private val P_LIST = Pattern.compile("(?is)\\[list(?:\\s*=\\s*[^\\]]+)?](.*?)\\[/list]")
    private val P_LI = Pattern.compile("(?is)\\[\\*](.*?)(?=\\[\\*]|\\[/list])")
    private val P_HR = Pattern.compile("(?i)\\[hr]")
    private val P_TABLE = Pattern.compile("(?is)\\[table(?:\\s*=\\s*[^\\]]+)?](.*?)\\[/table]")
    private val P_TR = Pattern.compile("(?is)\\[tr](.*?)\\[/tr]")
    private val P_TD = Pattern.compile("(?i)\\[td(?:\\s*=\\s*[^\\]]+)?]")
    private val P_TD_END = Pattern.compile("(?i)\\[/td]")
    private val P_INDENT = Pattern.compile("(?is)\\[indent](.*?)\\[/indent]")
    private val P_SUB = Pattern.compile("(?is)\\[sub](.*?)\\[/sub]")
    private val P_SUP = Pattern.compile("(?is)\\[sup](.*?)\\[/sup]")
    private val P_USER = Pattern.compile("(?is)\\[user\\s*=\\s*(\\d+)](.*?)\\[/user]")
    private val P_USER2 = Pattern.compile("(?is)\\[user]([^\\[]+?)\\[/user]")
    private val P_MEDIA = Pattern.compile("(?is)\\[(?:media|audio|video|flash)\\s*=\\s*([^\\]]+)]")
    private val P_MEDIA_END = Pattern.compile("(?i)\\[/(?:media|audio|video|flash)]")
    private val P_MEDIA2 = Pattern.compile("(?is)\\[(?:media|audio|video|flash)]([^\\[]+?)\\[/(?:media|audio|video|flash)]")
    private val P_TIDY_TAG = Pattern.compile("(?i)\\[/?[a-z0-9]+(?:=[^\\]]*)?]")

    // Discuz [size=N] 档位映射(1~7 -> px),超出按 px 原样
    private val SIZE_MAP = intArrayOf(12, 14, 16, 18, 22, 26, 30)

    // ==================== HTML 转义 ====================

    private fun escapeHtml(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /** 转义为可安全放入 <pre> 的内容:保留换行/空格,防止 Html 渲染时合并 */
    private fun escapeCodeText(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val ch = s[i]
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                ' ' -> sb.append("&nbsp;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 行拆分:保留 \r\n\n 结构 */
    private fun splitCodeLines(s: String?): Array<String> {
        if (s == null) return arrayOf()
        val normalized = s.replace("\r\n", "\n").replace('\r', '\n')
        // 去掉首尾多余空行
        return normalized.split("\n".toRegex(), 0).toTypedArray()
    }

    /** 属性值转义:仅处理会影响 HTML 解析的字符 */
    private fun escapeAttr(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;").replace("\"", "&quot;")
    }

    private fun mapSize(raw: String?): String {
        if (raw == null) return "16px"
        val s = raw.trim().lowercase(Locale.ROOT)
        try {
            if (s.endsWith("px")) return s
            // Discuz 支持 [size=1]~[size=7];也可能直接是数字像素
            val v = s.trim().toInt()
            if (v in 1..7) return SIZE_MAP[v - 1].toString() + "px"
            return Math.max(10, Math.min(40, v)).toString() + "px"
        } catch (e: NumberFormatException) {
            return raw.trim()
        }
    }

    // ==================== 核心转换 ====================

    /**
     * BBCode -> HTML(供 Html.fromHtml 渲染)
     * 与旧实现相比:
     * - Pattern 全部预编译为 static,避免每次 replaceAll 重新编译
     * - [code] 块先保护:块内 [[b]] 等字面量不会被后续规则误替换
     * - 新增表格/indent/user/media/sub/sup/spoiler 等标签
     */
    @JvmStatic
    fun convertBBCodeToHtml(html: String?): String {
        if (TextUtils.isEmpty(html)) {
            return ""
        }
        var result = html!!

        // 1) 保护代码块:整体替换为占位符,最后还原
        val codeBlocks = ArrayList<String>()
        val codeM = P_CODE.matcher(result)
        val sb1 = StringBuffer()
        while (codeM.find()) {
            val lang = codeM.group(1)
            val content = codeM.group(2)
            val inner = StringBuilder()
            val lines = splitCodeLines(content)
            for (line in lines) {
                // 空行也保留(防止 Html 合并)
                inner.append(escapeCodeText(line)).append("<br>")
            }
            val langLabel = if (!TextUtils.isEmpty(lang) && !"code".equals(lang.trim(), ignoreCase = true))
                "<span style=\"color:#9CA3AF;font-size:12px\">" + escapeHtml(lang.trim()) + "</span><br>"
            else
                ""
            codeBlocks.add("<pre class=\"comiis_blockcode\">" + langLabel + inner + "</pre>")
            codeM.appendReplacement(sb1, "\u0001CODE" + (codeBlocks.size - 1) + "\u0001")
        }
        codeM.appendTail(sb1)
        result = sb1.toString()

        // 2) 常用标签替换(每次替换都是编译好的 Pattern)
        result = P_ATTACHIMG.matcher(result).replaceAll(
            "<img src=\"https://bbs.binmt.cc/forum.php?mod=image&aid=$1&size=300x300&key=&nocache=1\">"
        )
        result = P_ATTACH.matcher(result).replaceAll(
            "<img src=\"https://bbs.binmt.cc/forum.php?mod=image&aid=$1&size=300x300&key=&nocache=1\">"
        )
        result = P_IMG.matcher(result).replaceAll("<img src=\"$1\">")
        result = P_B.matcher(result).replaceAll("<strong>$1</strong>")
        result = P_I.matcher(result).replaceAll("<em>$1</em>")
        result = P_U.matcher(result).replaceAll("<u>$1</u>")
        result = P_S.matcher(result).replaceAll("<s>$1</s>")
        result = P_DEL.matcher(result).replaceAll("<del>$1</del>")
        result = P_COLOR.matcher(result).replaceAll("<span style=\"color:$1\">$2</span>")
        val sizeM = P_SIZE.matcher(result)
        val sbSize = StringBuffer()
        while (sizeM.find()) {
            val px = mapSize(sizeM.group(1))
            val inner = sizeM.group(2)
            sizeM.appendReplacement(
                sbSize, Matcher.quoteReplacement(
                    "<span style=\"font-size:" + px + "\">" + inner + "</span>"
                )
            )
        }
        sizeM.appendTail(sbSize)
        result = sbSize.toString()
        result = P_FONT.matcher(result).replaceAll("<span style=\"font-family:$1\">$2</span>")
        result = P_ALIGN.matcher(result).replaceAll("<div style=\"text-align:$1\">$2</div>")
        // url/email:href 中的 & 需转义为 &amp;,避免 Html 解析实体出错
        val urlM = P_URL1.matcher(result)
        val sbUrl = StringBuffer()
        while (urlM.find()) {
            val href = escapeAttr(urlM.group(1))
            val label = urlM.group(2)
            urlM.appendReplacement(sbUrl, Matcher.quoteReplacement("<a href=\"" + href + "\">" + label + "</a>"))
        }
        urlM.appendTail(sbUrl)
        result = sbUrl.toString()
        result = P_URL2.matcher(result).replaceAll("<a href=\"$1\">$1</a>")
        val emailM = P_EMAIL1.matcher(result)
        val sbEmail = StringBuffer()
        while (emailM.find()) {
            val href = escapeAttr(emailM.group(1))
            val label = emailM.group(2)
            emailM.appendReplacement(sbEmail, Matcher.quoteReplacement("<a href=\"mailto:" + href + "\">" + label + "</a>"))
        }
        emailM.appendTail(sbEmail)
        result = sbEmail.toString()
        result = P_EMAIL2.matcher(result).replaceAll("<a href=\"mailto:$1\">$1</a>")
        result = P_INDENT.matcher(result).replaceAll("<div style=\"margin-left:24px\">$1</div>")
        result = P_SUB.matcher(result).replaceAll("<sub>$1</sub>")
        result = P_SUP.matcher(result).replaceAll("<sup>$1</sup>")

        // user 标签 -> 空间链接
        result = P_USER.matcher(result).replaceAll("<a href=\"home.php?mod=space&uid=$1\">$2</a>")
        result = P_USER2.matcher(result).replaceAll("<a href=\"home.php?mod=space&username=$1\">$1</a>")

        // media/audio/video/flash -> 链接(客户端不内嵌播放器)
        result = P_MEDIA2.matcher(result).replaceAll("<a href=\"$1\">$1</a>")
        val mediaM = P_MEDIA.matcher(result)
        val sb2 = StringBuffer()
        while (mediaM.find()) {
            val url = mediaM.group(1).trim()
            mediaM.appendReplacement(sb2, Matcher.quoteReplacement("<a href=\"" + url + "\">" + url + "</a>"))
        }
        mediaM.appendTail(sb2)
        result = sb2.toString()
        result = P_MEDIA_END.matcher(result).replaceAll("")

        // 列表
        result = P_LIST1.matcher(result).replaceAll("<ol>$1</ol>")
        result = P_LIST.matcher(result).replaceAll("<ul>$1</ul>")
        result = P_LI.matcher(result).replaceAll("<li>$1</li>")

        // 表格降级:等宽 pre 风格渲染(TextView 不支持 table 布局)
        // 每行一个 [tr],单元格用竖线分隔,整体以代码块样式展示便于对齐
        result = P_TD.matcher(result).replaceAll("&nbsp;│&nbsp;")
        result = P_TD_END.matcher(result).replaceAll("")
        result = P_TR.matcher(result).replaceAll("<br>$1")
        result = P_TABLE.matcher(result).replaceAll("<pre class=\"comiis_blockcode\">$1</pre>")

        // 引用 / 隐藏 / 回复可见 / 免费
        result = P_QUOTE.matcher(result).replaceAll("<div class=\"comiis_quote\">$2</div>")
        result = P_HIDE.matcher(result).replaceAll("<div class=\"comiis_quote\">$2</div>")
        result = P_REPLY.matcher(result).replaceAll("<div class=\"comiis_quote\">$1</div>")
        result = P_SPOILER.matcher(result).replaceAll("<div class=\"comiis_quote\">$1</div>")
        result = P_FREE.matcher(result).replaceAll("$1")

        result = P_HR.matcher(result).replaceAll("<hr>")

        // 3) 清理未识别的孤立标签(在还原代码块之前执行,避免误删代码块内 [b] 等字面量)
        result = P_TIDY_TAG.matcher(result).replaceAll("")

        // 4) 还原代码块占位符
        val sb3 = StringBuffer()
        val phM = Pattern.compile("\u0001CODE(\\d+)\u0001").matcher(result)
        while (phM.find()) {
            val idx = phM.group(1).toInt()
            if (idx >= 0 && idx < codeBlocks.size) {
                phM.appendReplacement(sb3, Matcher.quoteReplacement(codeBlocks[idx]))
            } else {
                phM.appendReplacement(sb3, "")
            }
        }
        phM.appendTail(sb3)
        result = sb3.toString()

        return result
    }

    // ==================== Html.fromHtml TagHandler(<pre> 代码块渲染) ====================

    /**
     * 创建带当前主题配色的代码块 TagHandler。
     * 每次调用新建实例(Html.fromHtml 为同步解析,实例可安全复用)。
     */
    @JvmStatic
    fun createTagHandler(context: Context?): Html.TagHandler {
        val bg = if (context != null) context.getColor(R.color.code_block_bg)
        else 0xFFEBEEF2.toInt()
        val text = if (context != null) context.getColor(R.color.code_block_text)
        else 0xFF2D3339.toInt()
        return CodeBlockTagHandler(bg, text)
    }

    private class CodeBlockTagHandler(
        private val bgColor: Int,
        private val textColor: Int
    ) : Html.TagHandler {
        private var preStart = -1
        private var inPre = false

        override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader) {
            if ("pre".equals(tag, ignoreCase = true)) {
                if (opening) {
                    inPre = true
                    preStart = output.length
                } else if (inPre) {
                    val start = Math.max(0, preStart)
                    val end = output.length
                    if (end > start) {
                        output.setSpan(
                            CodeBlockSpan(start, end, bgColor), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        output.setSpan(
                            android.text.style.ForegroundColorSpan(textColor),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        output.setSpan(
                            TypefaceSpan("monospace"), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        output.setSpan(
                            RelativeSizeSpan(0.95f), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        output.setSpan(
                            LeadingMarginSpan.Standard(dp(4f).toInt(), 0), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    inPre = false
                    preStart = -1
                }
            }
        }
    }

    /**
     * 代码块整块背景:LineBackgroundSpan 实现,支持圆角与整块连续绘制。
     * 需要记录区间 [start,end),只在自身范围内绘制。
     */
    class CodeBlockSpan @JvmOverloads constructor(
        private val start: Int,
        private val end: Int,
        private val bgColor: Int,
        radiusDp: Float = 6f,
        padLeftDp: Float = 8f,
        padRightDp: Float = 8f
    ) : android.text.style.LineBackgroundSpan {
        private val radius: Float = dp(radiusDp)
        private val padLeft: Float = dp(padLeftDp)
        private val padRight: Float = dp(padRightDp)

        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, lineStart: Int,
            lineEnd: Int, lstart: Int
        ) {
            // 该行不在代码块范围内则跳过
            if (lineEnd <= start || lineStart >= end) {
                return
            }
            val textStart = Math.max(lineStart, start)
            val textEnd = Math.min(lineEnd, end)
            if (textEnd <= textStart) {
                return
            }

            val oldColor = paint.color
            paint.color = bgColor
            paint.style = Paint.Style.FILL
            val rectLeft = left + padLeft.toInt()
            val rectRight = right - padRight.toInt()

            val firstLine = lineStart <= start && lineEnd > start
            val lastLine = lineStart < end && lineEnd >= end
            if (firstLine && lastLine) {
                canvas.drawRoundRect(RectF(rectLeft.toFloat(), top.toFloat(), rectRight.toFloat(), bottom.toFloat()), radius, radius, paint)
            } else if (firstLine) {
                // 首行:上圆角下直角
                val p = Path()
                p.moveTo(rectLeft.toFloat(), bottom.toFloat())
                p.lineTo(rectLeft.toFloat(), top + radius)
                p.quadTo(rectLeft.toFloat(), top.toFloat(), rectLeft + radius, top.toFloat())
                p.lineTo(rectRight - radius, top.toFloat())
                p.quadTo(rectRight.toFloat(), top.toFloat(), rectRight.toFloat(), top + radius)
                p.lineTo(rectRight.toFloat(), bottom.toFloat())
                p.close()
                canvas.drawPath(p, paint)
            } else if (lastLine) {
                // 末行:下圆角上直角
                val p = Path()
                p.moveTo(rectLeft.toFloat(), top.toFloat())
                p.lineTo(rectLeft.toFloat(), bottom - radius)
                p.quadTo(rectLeft.toFloat(), bottom.toFloat(), rectLeft + radius, bottom.toFloat())
                p.lineTo(rectRight - radius, bottom.toFloat())
                p.quadTo(rectRight.toFloat(), bottom.toFloat(), rectRight.toFloat(), bottom - radius)
                p.lineTo(rectRight.toFloat(), top.toFloat())
                p.close()
                canvas.drawPath(p, paint)
            } else {
                canvas.drawRect(rectLeft.toFloat(), top.toFloat(), rectRight.toFloat(), bottom.toFloat(), paint)
            }
            paint.color = oldColor
        }
    }

    // ==================== 便捷方法 ====================

    @JvmStatic
    fun render(html: String?, context: Context?): CharSequence {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, null, createTagHandler(context))
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value,
            android.content.res.Resources.getSystem().displayMetrics
        )
    }
}
