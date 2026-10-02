package com.solosu.mtforum.util;

import android.content.Context;
import com.solosu.mtforum.R;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Editable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;

import org.xml.sax.XMLReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public final class BBCodeUtil {

    private BBCodeUtil() {}

    // ==================== 预编译 Pattern(性能:避免每次格式串重新编译) ====================

    private static final Pattern P_ATTACHIMG = Pattern.compile("(?i)\\[attachimg]([0-9]+)\\[/attachimg]");
    private static final Pattern P_ATTACH = Pattern.compile("(?i)\\[attach]([0-9]+)\\[/attach]");
    private static final Pattern P_IMG = Pattern.compile("(?is)\\[img(?:=[^\\]]*)?]\\s*([^\\[\\]]+?)\\s*\\[/img]");
    private static final Pattern P_CODE = Pattern.compile("(?is)\\[code(?:=([^\\]]+))?](.*?)\\[/code]");
    private static final Pattern P_QUOTE = Pattern.compile("(?is)\\[quote(?:=([^\\]]+))?](.*?)\\[/quote]");
    private static final Pattern P_HIDE = Pattern.compile("(?is)\\[hide(?:=([^\\]]+))?](.*?)\\[/hide]");
    private static final Pattern P_REPLY = Pattern.compile("(?is)\\[reply](.*?)\\[/reply]");
    private static final Pattern P_FREE = Pattern.compile("(?is)\\[free](.*?)\\[/free]");
    private static final Pattern P_SPOILER = Pattern.compile("(?is)\\[spoiler](.*?)\\[/spoiler]");
    private static final Pattern P_B = Pattern.compile("(?is)\\[b](.*?)\\[/b]");
    private static final Pattern P_I = Pattern.compile("(?is)\\[i](.*?)\\[/i]");
    private static final Pattern P_U = Pattern.compile("(?is)\\[u](.*?)\\[/u]");
    private static final Pattern P_S = Pattern.compile("(?is)\\[s](.*?)\\[/s]");
    private static final Pattern P_DEL = Pattern.compile("(?is)\\[del](.*?)\\[/del]");
    private static final Pattern P_COLOR = Pattern.compile("(?is)\\[color\\s*=\\s*([^\\]]+)](.*?)\\[/color]");
    private static final Pattern P_SIZE = Pattern.compile("(?is)\\[size\\s*=\\s*([^\\]]+)](.*?)\\[/size]");
    private static final Pattern P_FONT = Pattern.compile("(?is)\\[font\\s*=\\s*([^\\]]+)](.*?)\\[/font]");
    private static final Pattern P_ALIGN = Pattern.compile("(?is)\\[align\\s*=\\s*([^\\]]+)](.*?)\\[/align]");
    private static final Pattern P_URL1 = Pattern.compile("(?is)\\[url\\s*=\\s*([^\\]\\s]+)]([^\\[]*?)\\[/url]");
    private static final Pattern P_URL2 = Pattern.compile("(?is)\\[url]([^\\[]+?)\\[/url]");
    private static final Pattern P_EMAIL1 = Pattern.compile("(?is)\\[email\\s*=\\s*([^\\]]+)](.*?)\\[/email]");
    private static final Pattern P_EMAIL2 = Pattern.compile("(?is)\\[email]([^\\[]+?)\\[/email]");
    private static final Pattern P_LIST1 = Pattern.compile("(?is)\\[list\\s*=\\s*1](.*?)\\[/list]");
    private static final Pattern P_LIST = Pattern.compile("(?is)\\[list(?:\\s*=\\s*[^\\]]+)?](.*?)\\[/list]");
    private static final Pattern P_LI = Pattern.compile("(?is)\\[\\*](.*?)(?=\\[\\*]|\\[/list])");
    private static final Pattern P_HR = Pattern.compile("(?i)\\[hr]");
    private static final Pattern P_TABLE = Pattern.compile("(?is)\\[table(?:\\s*=\\s*[^\\]]+)?](.*?)\\[/table]");
    private static final Pattern P_TR = Pattern.compile("(?is)\\[tr](.*?)\\[/tr]");
    private static final Pattern P_TD = Pattern.compile("(?i)\\[td(?:\\s*=\\s*[^\\]]+)?]");
    private static final Pattern P_TD_END = Pattern.compile("(?i)\\[/td]");
    private static final Pattern P_INDENT = Pattern.compile("(?is)\\[indent](.*?)\\[/indent]");
    private static final Pattern P_SUB = Pattern.compile("(?is)\\[sub](.*?)\\[/sub]");
    private static final Pattern P_SUP = Pattern.compile("(?is)\\[sup](.*?)\\[/sup]");
    private static final Pattern P_USER = Pattern.compile("(?is)\\[user\\s*=\\s*(\\d+)](.*?)\\[/user]");
    private static final Pattern P_USER2 = Pattern.compile("(?is)\\[user]([^\\[]+?)\\[/user]");
    private static final Pattern P_MEDIA = Pattern.compile("(?is)\\[(?:media|audio|video|flash)\\s*=\\s*([^\\]]+)]");
    private static final Pattern P_MEDIA_END = Pattern.compile("(?i)\\[/(?:media|audio|video|flash)]");
    private static final Pattern P_MEDIA2 = Pattern.compile("(?is)\\[(?:media|audio|video|flash)]([^\\[]+?)\\[/(?:media|audio|video|flash)]");
    private static final Pattern P_TIDY_TAG = Pattern.compile("(?i)\\[/?[a-z0-9]+(?:=[^\\]]*)?]");

    // Discuz [size=N] 档位映射(1~7 -> px),超出按 px 原样
    private static final int[] SIZE_MAP = {12, 14, 16, 18, 22, 26, 30};

    // ==================== HTML 转义 ====================

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 转义为可安全放入 <pre> 的内容:保留换行/空格,防止 Html 渲染时合并 */
    private static String escapeCodeText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case ' ': sb.append("&nbsp;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 行拆分:保留 \r\n\n 结构 */
    private static String[] splitCodeLines(String s) {
        if (s == null) return new String[0];
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        // 去掉首尾多余空行
        return normalized.split("\n", -1);
    }

    /** 属性值转义:仅处理会影响 HTML 解析的字符 */
    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String mapSize(String raw) {
        if (raw == null) return "16px";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("px")) return s;
            // Discuz 支持 [size=1]~[size=7];也可能直接是数字像素
            int v = Integer.parseInt(s.trim());
            if (v >= 1 && v <= 7) return SIZE_MAP[v - 1] + "px";
            return Math.max(10, Math.min(40, v)) + "px";
        } catch (NumberFormatException e) {
            return raw.trim();
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
    public static String convertBBCodeToHtml(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        String result = html;

        // 1) 保护代码块:整体替换为占位符,最后还原
        List<String> codeBlocks = new ArrayList<>();
        Matcher codeM = P_CODE.matcher(result);
        StringBuffer sb1 = new StringBuffer();
        while (codeM.find()) {
            String lang = codeM.group(1);
            String content = codeM.group(2);
            StringBuilder inner = new StringBuilder();
            String[] lines = splitCodeLines(content);
            for (String line : lines) {
                // 空行也保留(防止 Html 合并)
                inner.append(escapeCodeText(line)).append("<br>");
            }
            String langLabel = (!TextUtils.isEmpty(lang) && !"code".equalsIgnoreCase(lang.trim()))
                    ? "<span style=\"color:#9CA3AF;font-size:12px\">" + escapeHtml(lang.trim()) + "</span><br>"
                    : "";
            codeBlocks.add("<pre class=\"comiis_blockcode\">" + langLabel + inner + "</pre>");
            codeM.appendReplacement(sb1, "\u0001CODE" + (codeBlocks.size() - 1) + "\u0001");
        }
        codeM.appendTail(sb1);
        result = sb1.toString();

        // 2) 常用标签替换(每次替换都是编译好的 Pattern)
        result = P_ATTACHIMG.matcher(result).replaceAll(
                "<img src=\"https://bbs.binmt.cc/forum.php?mod=image&aid=$1&size=300x300&key=&nocache=1\">");
        result = P_ATTACH.matcher(result).replaceAll(
                "<img src=\"https://bbs.binmt.cc/forum.php?mod=image&aid=$1&size=300x300&key=&nocache=1\">");
        result = P_IMG.matcher(result).replaceAll("<img src=\"$1\">");
        result = P_B.matcher(result).replaceAll("<strong>$1</strong>");
        result = P_I.matcher(result).replaceAll("<em>$1</em>");
        result = P_U.matcher(result).replaceAll("<u>$1</u>");
        result = P_S.matcher(result).replaceAll("<s>$1</s>");
        result = P_DEL.matcher(result).replaceAll("<del>$1</del>");
        result = P_COLOR.matcher(result).replaceAll("<span style=\"color:$1\">$2</span>");
        Matcher sizeM = P_SIZE.matcher(result);
        StringBuffer sbSize = new StringBuffer();
        while (sizeM.find()) {
            String px = mapSize(sizeM.group(1));
            String inner = sizeM.group(2);
            sizeM.appendReplacement(sbSize, Matcher.quoteReplacement(
                    "<span style=\"font-size:" + px + "\">" + inner + "</span>"));
        }
        sizeM.appendTail(sbSize);
        result = sbSize.toString();
        result = P_FONT.matcher(result).replaceAll("<span style=\"font-family:$1\">$2</span>");
        result = P_ALIGN.matcher(result).replaceAll("<div style=\"text-align:$1\">$2</div>");
        // url/email:href 中的 & 需转义为 &amp;,避免 Html 解析实体出错
        Matcher urlM = P_URL1.matcher(result);
        StringBuffer sbUrl = new StringBuffer();
        while (urlM.find()) {
            String href = escapeAttr(urlM.group(1));
            String label = urlM.group(2);
            urlM.appendReplacement(sbUrl, Matcher.quoteReplacement("<a href=\"" + href + "\">" + label + "</a>"));
        }
        urlM.appendTail(sbUrl);
        result = sbUrl.toString();
        result = P_URL2.matcher(result).replaceAll("<a href=\"$1\">$1</a>");
        Matcher emailM = P_EMAIL1.matcher(result);
        StringBuffer sbEmail = new StringBuffer();
        while (emailM.find()) {
            String href = escapeAttr(emailM.group(1));
            String label = emailM.group(2);
            emailM.appendReplacement(sbEmail, Matcher.quoteReplacement("<a href=\"mailto:" + href + "\">" + label + "</a>"));
        }
        emailM.appendTail(sbEmail);
        result = sbEmail.toString();
        result = P_EMAIL2.matcher(result).replaceAll("<a href=\"mailto:$1\">$1</a>");
        result = P_INDENT.matcher(result).replaceAll("<div style=\"margin-left:24px\">$1</div>");
        result = P_SUB.matcher(result).replaceAll("<sub>$1</sub>");
        result = P_SUP.matcher(result).replaceAll("<sup>$1</sup>");

        // user 标签 -> 空间链接
        result = P_USER.matcher(result).replaceAll("<a href=\"home.php?mod=space&uid=$1\">$2</a>");
        result = P_USER2.matcher(result).replaceAll("<a href=\"home.php?mod=space&username=$1\">$1</a>");

        // media/audio/video/flash -> 链接(客户端不内嵌播放器)
        result = P_MEDIA2.matcher(result).replaceAll("<a href=\"$1\">$1</a>");
        Matcher mediaM = P_MEDIA.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (mediaM.find()) {
            String url = mediaM.group(1).trim();
            mediaM.appendReplacement(sb2, Matcher.quoteReplacement("<a href=\"" + url + "\">" + url + "</a>"));
        }
        mediaM.appendTail(sb2);
        result = sb2.toString();
        result = P_MEDIA_END.matcher(result).replaceAll("");

        // 列表
        result = P_LIST1.matcher(result).replaceAll("<ol>$1</ol>");
        result = P_LIST.matcher(result).replaceAll("<ul>$1</ul>");
        result = P_LI.matcher(result).replaceAll("<li>$1</li>");

        // 表格降级:等宽 pre 风格渲染(TextView 不支持 table 布局)
        // 每行一个 [tr],单元格用竖线分隔,整体以代码块样式展示便于对齐
        result = P_TD.matcher(result).replaceAll("&nbsp;│&nbsp;");
        result = P_TD_END.matcher(result).replaceAll("");
        result = P_TR.matcher(result).replaceAll("<br>$1");
        result = P_TABLE.matcher(result).replaceAll("<pre class=\"comiis_blockcode\">$1</pre>");

        // 引用 / 隐藏 / 回复可见 / 免费
        result = P_QUOTE.matcher(result).replaceAll("<div class=\"comiis_quote\">$2</div>");
        result = P_HIDE.matcher(result).replaceAll("<div class=\"comiis_quote\">$2</div>");
        result = P_REPLY.matcher(result).replaceAll("<div class=\"comiis_quote\">$1</div>");
        result = P_SPOILER.matcher(result).replaceAll("<div class=\"comiis_quote\">$1</div>");
        result = P_FREE.matcher(result).replaceAll("$1");

        result = P_HR.matcher(result).replaceAll("<hr>");

        // 3) 清理未识别的孤立标签(在还原代码块之前执行,避免误删代码块内 [b] 等字面量)
        result = P_TIDY_TAG.matcher(result).replaceAll("");

        // 4) 还原代码块占位符
        StringBuffer sb3 = new StringBuffer();
        Matcher phM = Pattern.compile("\u0001CODE(\\d+)\u0001").matcher(result);
        while (phM.find()) {
            int idx = Integer.parseInt(phM.group(1));
            if (idx >= 0 && idx < codeBlocks.size()) {
                phM.appendReplacement(sb3, Matcher.quoteReplacement(codeBlocks.get(idx)));
            } else {
                phM.appendReplacement(sb3, "");
            }
        }
        phM.appendTail(sb3);
        result = sb3.toString();

        return result;
    }

    // ==================== Html.fromHtml TagHandler(<pre> 代码块渲染) ====================

    /**
     * 创建带当前主题配色的代码块 TagHandler。
     * 每次调用新建实例(Html.fromHtml 为同步解析,实例可安全复用)。
     */
    public static Html.TagHandler createTagHandler(Context context) {
        int bg = (context != null) ? context.getColor(R.color.code_block_bg)
                : 0xFFEBEEF2;
        int text = (context != null) ? context.getColor(R.color.code_block_text)
                : 0xFF2D3339;
        return new CodeBlockTagHandler(bg, text);
    }

    private static final class CodeBlockTagHandler implements Html.TagHandler {
        private final int bgColor;
        private final int textColor;
        private int preStart = -1;
        private boolean inPre = false;

        CodeBlockTagHandler(int bgColor, int textColor) {
            this.bgColor = bgColor;
            this.textColor = textColor;
        }

        @Override
        public void handleTag(boolean opening, String tag, Editable output, XMLReader xmlReader) {
            if ("pre".equalsIgnoreCase(tag)) {
                if (opening) {
                    inPre = true;
                    preStart = output.length();
                } else if (inPre) {
                    int start = Math.max(0, preStart);
                    int end = output.length();
                    if (end > start) {
                        output.setSpan(new CodeBlockSpan(start, end, bgColor), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new android.text.style.ForegroundColorSpan(textColor),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new TypefaceSpan("monospace"), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new RelativeSizeSpan(0.95f), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new LeadingMarginSpan.Standard((int) dp(4), 0), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    inPre = false;
                    preStart = -1;
                }
            }
        }
    }

    /**
     * 代码块整块背景:LineBackgroundSpan 实现,支持圆角与整块连续绘制。
     * 需要记录区间 [start,end),只在自身范围内绘制。
     */
    public static final class CodeBlockSpan implements android.text.style.LineBackgroundSpan {
        private final int start;
        private final int end;
        private final int bgColor;
        private final float radius;
        private final float padLeft;
        private final float padRight;

        public CodeBlockSpan(int start, int end, int bgColor) {
            this(start, end, bgColor, 6f, 8f, 8f);
        }

        public CodeBlockSpan(int start, int end, int bgColor,
                             float radiusDp, float padLeftDp, float padRightDp) {
            this.start = start;
            this.end = end;
            this.bgColor = bgColor;
            this.radius = dp(radiusDp);
            this.padLeft = dp(padLeftDp);
            this.padRight = dp(padRightDp);
        }

        @Override
        public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top,
                                   int baseline, int bottom, CharSequence text, int lineStart,
                                   int lineEnd, int lstart) {
            // 该行不在代码块范围内则跳过
            if (lineEnd <= start || lineStart >= end) {
                return;
            }
            int textStart = Math.max(lineStart, start);
            int textEnd = Math.min(lineEnd, end);
            if (textEnd <= textStart) {
                return;
            }

            int oldColor = paint.getColor();
            paint.setColor(bgColor);
            paint.setStyle(Paint.Style.FILL);
            int rectLeft = left + (int) padLeft;
            int rectRight = right - (int) padRight;

            boolean firstLine = lineStart <= start && lineEnd > start;
            boolean lastLine = lineStart < end && lineEnd >= end;
            if (firstLine && lastLine) {
                canvas.drawRoundRect(new RectF(rectLeft, top, rectRight, bottom), radius, radius, paint);
            } else if (firstLine) {
                // 首行:上圆角下直角
                Path p = new Path();
                p.moveTo(rectLeft, bottom);
                p.lineTo(rectLeft, top + radius);
                p.quadTo(rectLeft, top, rectLeft + radius, top);
                p.lineTo(rectRight - radius, top);
                p.quadTo(rectRight, top, rectRight, top + radius);
                p.lineTo(rectRight, bottom);
                p.close();
                canvas.drawPath(p, paint);
            } else if (lastLine) {
                // 末行:下圆角上直角
                Path p = new Path();
                p.moveTo(rectLeft, top);
                p.lineTo(rectLeft, bottom - radius);
                p.quadTo(rectLeft, bottom, rectLeft + radius, bottom);
                p.lineTo(rectRight - radius, bottom);
                p.quadTo(rectRight, bottom, rectRight, bottom - radius);
                p.lineTo(rectRight, top);
                p.close();
                canvas.drawPath(p, paint);
            } else {
                canvas.drawRect(rectLeft, top, rectRight, bottom, paint);
            }
            paint.setColor(oldColor);
        }
    }

    // ==================== 便捷方法 ====================

    public static CharSequence render(String html, Context context) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, null, createTagHandler(context));
    }

    private static float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                android.content.res.Resources.getSystem().getDisplayMetrics());
    }
}
