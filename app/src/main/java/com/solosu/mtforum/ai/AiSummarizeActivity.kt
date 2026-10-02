package com.solosu.mtforum.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.solosu.mtforum.R
import org.json.JSONObject

/**
 * AI 一键总结页：帖子内容 + 评论区 → AI 总结。
 *
 * 流程：
 *  1. 拉取帖子详情（含正文与隐藏内容）
 *  2. 检测隐藏内容是否处于锁定态：
 *     - 锁定 → 用用户自定义/内置固定模板回帖解锁 → 回读确认 → 拉全内容
 *     - 已解锁/无隐藏 → 直接用
 *  3. 拉取评论（最多 80 条，预算内）
 *  4. 组包交给 AI 总结（simpleChat，无工具调用，纯一次生成）
 */
class AiSummarizeActivity : AppCompatActivity() {

    private var tid: String? = null
    private var title: String? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        tid = intent.getStringExtra("tid")
        title = intent.getStringExtra("title")
        buildUi()
        if (TextUtils.isEmpty(tid)) {
            tvStatus.text = "缺少帖子 ID，无法总结"
            return
        }
        startSummarize()
    }

    /** 纯代码 UI：顶栏（返回+标题）+ 状态行 + 总结内容区 */
    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.solosu.mtforum.R.color.background))

        // 顶栏
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        )
        bar.setPadding(dp(8), 0, dp(12), 0)

        val btnBack = android.widget.ImageView(this)
        btnBack.setImageResource(com.solosu.mtforum.R.drawable.ic_back)
        btnBack.setColorFilter(androidx.core.content.ContextCompat.getColor(this, com.solosu.mtforum.R.color.text_primary))
        btnBack.setPadding(dp(8), dp(8), dp(8), dp(8))
        btnBack.setOnClickListener { finish() }
        bar.addView(btnBack, LinearLayout.LayoutParams(dp(40), dp(40)))

        val tvTitle = TextView(this)
        tvTitle.text = if (TextUtils.isEmpty(title)) "AI 总结" else title
        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.solosu.mtforum.R.color.text_primary))
        tvTitle.setTextSize(16f)
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        tvTitle.isSingleLine = true
        tvTitle.ellipsize = TextUtils.TruncateAt.END
        tvTitle.setPadding(dp(6), 0, dp(6), 0)
        val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(tvTitle, tp)

        // 复制按钮
        val btnCopy = TextView(this)
        btnCopy.text = "复制"
        btnCopy.setTextColor(com.solosu.mtforum.util.ThemeManager.getThemeColor(this))
        btnCopy.setTextSize(14f)
        btnCopy.setPadding(dp(10), dp(6), dp(10), dp(6))
        btnCopy.setOnClickListener { copySummary() }
        bar.addView(btnCopy)
        root.addView(bar)

        // 状态行（显示进行中的步骤）
        tvStatus = TextView(this)
        tvStatus.text = "正在拉取帖子…"
        tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.solosu.mtforum.R.color.accent_ai))
        tvStatus.setTextSize(13f)
        tvStatus.setPadding(dp(16), dp(6), dp(16), dp(6))
        root.addView(tvStatus)

        // 总结内容区
        scroll = ScrollView(this)
        val contentBox = LinearLayout(this)
        contentBox.orientation = LinearLayout.VERTICAL
        contentBox.setPadding(dp(16), dp(4), dp(16), dp(24))
        tvSummary = TextView(this)
        tvSummary.text = ""
        tvSummary.setTextIsSelectable(true)
        tvSummary.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.solosu.mtforum.R.color.text_primary))
        tvSummary.setTextSize(15f)
        tvSummary.setLineSpacing(dp(3).toFloat(), 1f)
        contentBox.addView(tvSummary)
        scroll.addView(contentBox)
        val sp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(scroll, sp)

        setContentView(root)
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun postStatus(s: String) {
        runOnUiThread { tvStatus.text = s }
    }

    private fun appendSummary(s: String) {
        runOnUiThread {
            tvSummary.append(s)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun startSummarize() {
        java.lang.Thread({
            try {
                doSummarize()
            } catch (e: Exception) {
                postStatus("总结失败：" + e.javaClass.simpleName)
                AiLog.e("ai-summarize", "failed: " + e.javaClass.simpleName + ": " + e.message)
            }
        }, "ai-summarize").start()
    }

    @Throws(Exception::class)
    private fun doSummarize() {
        val args = org.json.JSONObject()
        args.put("tid", tid)
        args.put("fetch_all", true)
        args.put("max_replies", 80)
        // 第 1 步：拉帖（若隐藏内容已解锁，返回体里已带 hidden_content）
        postStatus("正在拉取帖子…")
        val raw = ForumTools.execute(this, "get_thread", args)
        val thread = JSONObject(raw)
        if (!thread.optBoolean("success", true) && thread.has("error")) {
            postStatus("拉取失败：" + thread.optString("error", "未知错误"))
            return
        }
        val content = thread.optString("content", "")
        val hidden = thread.optString("hidden_content", "")
        val hasHidden = thread.has("hidden_content")
        // 第 2 步：隐藏内容锁定检测（复用统一门控判定）
        var hiddenLocked = false
        if (hasHidden && !TextUtils.isEmpty(hidden)
            && AutoReplyEngine.containsGateWord(hidden)
        ) {
            hiddenLocked = true
        }
        if (!hasHidden && AutoReplyEngine.containsGateWord(content)) {
            hiddenLocked = true
        }
        // 第 3 步：锁定则回帖解锁（unlock_hidden 内部用用户自定义模板或内置本地模板，不调 AI）
        var hiddenResolved = hidden
        if (hiddenLocked) {
            postStatus("检测到隐藏内容，正在回帖解锁…")
            val unlockArgs = org.json.JSONObject()
            unlockArgs.put("tid", tid)
            // 不传 message:让 unlockHidden 走用户自定义模板(AiConfigManager)或内置本地模板
            val unlockRaw = ForumTools.execute(this, "unlock_hidden", unlockArgs)
            val unlockResult = JSONObject(unlockRaw)
            AiLog.i("ai-summarize", "unlock_hidden -> " + unlockRaw)
            if (unlockResult.optBoolean("success") && unlockResult.has("content")) {
                hiddenResolved = unlockResult.optString("content", "")
                postStatus("解锁成功，正在整理…")
            } else if (unlockResult.optBoolean("hidden", false) == false) {
                // 无隐藏内容的分支
                postStatus("继续总结…")
            } else {
                postStatus("回复已发出但未能确认解锁，按已获取内容总结")
            }
            // 解锁或回复后重新拉一次，确保拿到最新正文（可能解锁改变正文可见性）
            try {
                val raw2 = ForumTools.execute(this, "get_thread", args)
                val thread2 = JSONObject(raw2)
                val h2 = thread2.optString("hidden_content", "")
                if (!TextUtils.isEmpty(h2) && !AutoReplyEngine.isLockedHidden(h2)) {
                    hiddenResolved = h2
                }
            } catch (ignore: Exception) {
            }
        }
        // 第 4 步：组包交给 AI
        postStatus("正在 AI 总结（内容+评论区）…")
        val prompt = StringBuilder()
        prompt.append("【帖子标题】").append(if (title == null) safe(thread.optString("title")) else title).append("\n\n")
        prompt.append("【帖子正文】\n").append(clip(content, 6000))
        if (!TextUtils.isEmpty(hiddenResolved)) {
            prompt.append("\n\n【隐藏内容(已解锁)】\n").append(clip(hiddenResolved, 3000))
        }
        // 评论:直接用第 1 步 get_thread 返回的 replies 数组
        val repliesStr = thread.optString("replies", "[]")
        val replies = org.json.JSONArray(repliesStr)
        if (replies.length() > 0) {
            prompt.append("\n\n【评论区(").append(replies.length()).append("条)")
            var budget = 12000
            for (i in 0 until replies.length()) {
                val r = replies.optJSONObject(i) ?: continue
                var line = r.optString("author", "?") + ": " + r.optString("content", "").trim()
                if (line.length > 300) line = line.substring(0, 300) + "…"
                if (budget - line.length < 0) break
                budget -= line.length
                prompt.append("\n").append("-").append(line)
            }
            prompt.append("\n")
        }
        val sys = "你是论坛帖子总结助手。基于给定的帖子正文与评论区，用中文写一份结构化总结，格式：\n" +
                "【一句话总结】…\n【要点】- …\n【隐藏内容】…(如有)\n【评论区氛围/高赞观点】…\n" +
                "只依据给定材料，不要编造；材料不足的部分直接说明。"
        val result = AiClient.simpleChat(this, sys, prompt.toString())
        if (TextUtils.isEmpty(result)) {
            postStatus("AI 总结返回为空，稍后重试")
            return
        }
        // 第 5 步：显示（整段替换状态行）
        postStatus("总结完成")
        appendSummary(result!!.trim())
    }

    /** build70: 复制总结全文到剪贴板 */
    private fun copySummary() {
        val txt = if (tvSummary.text == null) "" else tvSummary.text.toString()
        if (TextUtils.isEmpty(txt.trim())) {
            Toast.makeText(this, "暂无内容可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AI总结", txt))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safe(s: String?): String {
        return s ?: ""
    }

    private fun clip(s: String?, max: Int): String {
        if (s == null) return ""
        return if (s.length > max) s.substring(0, max) + "…" else s
    }

    companion object {

        /** build70: 从 AI 输出中剥离【小节】标题与 Markdown 记号, 得到可直接发帖的纯文本 */
        @JvmStatic
        fun extractText(ai: String?): String {
            if (ai == null) return ""
            var t = ai.trim()
            t = t.replace("【[^】]{1,30}】".toRegex(), "")
            t = t.replace("(?m)^#{1,6}\\s*".toRegex(), "")
            t = t.replace("\\*\\*?".toRegex(), "")
            t = t.replace("`{1,3}".toRegex(), "")
            t = t.replace("(?m)^\\s*[-*·]\\s+".toRegex(), "")
            t = t.replace("\\n{3,}".toRegex(), "\n\n")
            return t.trim()
        }
    }
}
