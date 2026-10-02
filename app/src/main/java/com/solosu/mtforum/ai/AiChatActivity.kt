package com.solosu.mtforum.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import java.util.HashSet
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * AI 助手页。
 *
 * 核心是把论坛接口以「工具」形式交给大模型：
 *   用户提问 → 模型决定调哪个工具 → 本地执行 ForumTools → 结果回灌模型 → 循环 → 输出答案
 *
 * 最大 6 轮，防止模型陷入死循环空转。
 */
class AiChatActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageView
    private lateinit var container: LinearLayout
    private lateinit var scrollChat: ScrollView

    /** 完整对话上下文（含工具调用往返），用于多轮记忆 */
    private val history = ArrayList<AiClient.Msg>()
    private var busy = false

    /** 当前会话 id（Codex 风格会话管理）；null 表示尚未归属会话，首条消息时创建 */
    private var sessionId: String? = null

    /** 是否新建的空会话（从会话列表「新建会话」进入时，不再自动开新会话） */
    private var freshSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.solosu.mtforum.R.layout.activity_ai_chat)

        etInput = findViewById(com.solosu.mtforum.R.id.et_input)
        btnSend = findViewById(com.solosu.mtforum.R.id.btn_send)
        container = findViewById(com.solosu.mtforum.R.id.chat_container)
        scrollChat = findViewById(com.solosu.mtforum.R.id.scroll_chat)

        findViewById<View>(com.solosu.mtforum.R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(com.solosu.mtforum.R.id.btn_clear).setOnClickListener { clearChat() }
        val btnHistory = findViewById<View>(com.solosu.mtforum.R.id.btn_history)
        if (btnHistory != null) btnHistory.setOnClickListener {
            startActivity(Intent(this, AiSessionListActivity::class.java))
        }

        btnSend.setOnClickListener { sendCurrentInput() }
        etInput.setOnEditorActionListener { v, actionId, event ->
            sendCurrentInput()
            true
        }

        bindQuick(com.solosu.mtforum.R.id.quick_latest, "看看论坛最新发布的帖子，挑 3 个值得看的简单说说")
        bindQuick(com.solosu.mtforum.R.id.quick_my, "我最近发过哪些帖子？有人说我什么吗")
        bindQuick(com.solosu.mtforum.R.id.quick_forums, "论坛有哪些版块，各自是干什么的")
        bindQuick(com.solosu.mtforum.R.id.quick_notice, "我有哪些新通知或私信")

        // 会话恢复：从会话列表点进来 → 载入历史消息并渲染气泡
        val sid = if (intent != null) intent.getStringExtra("session_id") else null
        if (!TextUtils.isEmpty(sid)) {
            sessionId = sid
            freshSession = intent.getBooleanExtra("session_new", false)
            val saved = AiSessionStore.loadMessages(this, sid)
            if (saved.isNotEmpty()) {
                history.addAll(saved)
                renderSavedHistory(saved)
            }
            welcome()
        } else {
            welcome()
        }
    }

    /** 把恢复的历史消息渲染成气泡（不重发请求，纯展示） */
    private fun renderSavedHistory(saved: List<AiClient.Msg>) {
        for (i in saved.indices) {
            val m = saved[i]
            if (m == null) continue
            // 内部轮（工具往返、计划文本）不渲染
            if (m.`internal`) continue
            // 兜底：旧版本会话文件没有 internal 标志，靠内容特征过滤
            if (isInternalNoise(m)) continue
            // 兜底2：旧文件的 assistant 计划散文任意开头、前缀拦不住；
            // 但它后面必然紧跟工具结果回灌/重申协议——按序列位置判定，零误伤
            if ("assistant" == m.role) {
                val next = if (i + 1 < saved.size) saved[i + 1] else null
                if (next != null && isInternalNoise(next)) continue
            }
            if ("user" == m.role && !TextUtils.isEmpty(m.content)) {
                addBubble(m.content!!, true, false)
            } else if ("assistant" == m.role && !TextUtils.isEmpty(m.content)) {
                addBubble(m.content!!, false, false)
            }
            // tool / system 角色不渲染（工具往返属于上下文，不占 UI）
        }
        scrollToBottom()
    }

    /**
     * 旧会话文件的内部噪音识别：工具结果回灌、重申协议这类内容
     * 没有 internal 标志（老版本存的），靠固定前缀/特征拦住。
     */
    private fun isInternalNoise(m: AiClient.Msg?): Boolean {
        if (m == null || TextUtils.isEmpty(m.content)) return false
        val c = m.content!!
        return c.startsWith("工具执行结果如下：") ||
                c.startsWith("你刚才的输出是调用计划而不是最终回答。") ||
                c.startsWith("请基于以上真实数据回答用户")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出前兜底保存（正常情况下每轮对话结束已即时落盘）
        persistSession()
    }

    /** 会话落盘：有会话 id 且有消息时全量保存 */
    private fun persistSession() {
        if (TextUtils.isEmpty(sessionId)) return
        if (history.isEmpty()) {
            // 空会话不占索引；从列表「新建会话」进来但没说话就退出的，从索引里清掉
            if (freshSession) AiSessionStore.deleteSession(this, sessionId!!)
            return
        }
        val title = AiSessionStore.titleFromMessages(history)
        AiSessionStore.saveMessages(this, sessionId!!, history, title)
    }

    /** 开始新会话（当前内容清空）；保持 index 一致 */
    private fun startNewSession() {
        // 已有对话的旧会话已在每轮落盘，这里直接切新会话
        history.clear()
        container.removeAllViews()
        val id = AiSessionStore.createSession(this)
        sessionId = id
        freshSession = true
        welcome()
    }

    private fun bindQuick(id: Int, text: String) {
        val v = findViewById<View>(id)
        if (v != null) v.setOnClickListener {
            etInput.setText(text)
            etInput.setSelection(text.length)
        }
    }

    // ==================== 界面 ====================

    private fun welcome() {
        if (!AiConfigManager.isConfigured(this)) {
            addBubble(
                "还没配置模型。请先到「侧边栏 → AI 配置」填好接口地址、API Key 和模型名称。",
                false, true
            )
            return
        }
        addBubble(
            "我是接了这个论坛接口的助手。可以让我：\n" +
                    "· 查最新帖、搜关键词\n" +
                    "· 读某个帖子的正文和评论\n" +
                    "· 总结、提炼观点\n" +
                    "· 直接回帖（需要你明确说「帮我回复」）", false, true
        )
    }

    private fun clearChat() {
        if (busy) {
            toast("还在处理上一条，稍等")
            return
        }
        // Codex 语义：清空 = 结束当前会话开新的，旧对话保留在「历史」列表里
        if (!TextUtils.isEmpty(sessionId) && !history.isEmpty()) {
            // 已有内容的会话先落盘再切新
            persistSession()
        }
        startNewSession()
    }

    /** 添加一条气泡 */
    private fun addBubble(text: String, isUser: Boolean, isSystem: Boolean) {
        val tv = TextView(this)
        tv.setTextSize(if (isSystem) 13f else 14f)
        tv.setTextColor(
            if (isSystem) Color.parseColor("#606770")
            else (if (isUser) Color.WHITE else Color.parseColor("#1C1E21"))
        )
        tv.text = text
        tv.setLineSpacing(4f, 1.15f)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 8
        lp.bottomMargin = 8
        lp.gravity = if (isSystem) Gravity.CENTER_HORIZONTAL else (if (isUser) Gravity.END else Gravity.START)
        tv.layoutParams = lp

        val pad = dp(12)
        tv.setPadding(pad, dp(9), pad, dp(9))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()

        if (isSystem) {
            tv.setBackgroundColor(Color.TRANSPARENT)
        } else if (isUser) {
            tv.setBackgroundColor(Color.parseColor("#1A73E8"))
        } else {
            tv.setBackgroundColor(Color.parseColor("#FFFFFF"))
        }
        tv.setTextIsSelectable(true)

        container.addView(tv)
        scrollToBottom()
    }

    /** 悬浮的“正在思考”提示，返回可移除的 View */
    private fun addThinking(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Color.parseColor("#FFFFFF"))
        val pad = dp(12)
        box.setPadding(pad, dp(9), pad, dp(9))

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 8
        lp.bottomMargin = 8
        lp.gravity = Gravity.START
        box.layoutParams = lp

        val tv = TextView(this)
        tv.setTextSize(13f)
        tv.setTextColor(Color.parseColor("#9CA3AF"))
        tv.text = "正在思考…"
        box.addView(tv)
        box.tag = tv

        container.addView(box)
        scrollToBottom()
        return box
    }

    private fun updateThinking(box: View?, text: String) {
        if (box == null) return
        val tag = box.tag
        if (tag is TextView) tag.text = text
        scrollToBottom()
    }

    private fun removeView(v: View?) {
        if (v != null && v.parent === container) container.removeView(v)
    }

    private fun scrollToBottom() {
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    // ==================== 主流程 ====================

    private fun sendCurrentInput() {
        if (busy) {
            toast("还在处理上一条，稍等")
            return
        }
        val text = if (etInput.text == null) "" else etInput.text.toString().trim()
        if (TextUtils.isEmpty(text)) return

        if (!AiConfigManager.isConfigured(this)) {
            toast("请先到侧边栏 → AI 配置 填好模型参数")
            return
        }

        etInput.setText("")
        addBubble(text, true, false)
        history.add(AiClient.Msg.user(text))

        // 首条消息时自动归属会话（从聊天页直接进入、无 session_id 的场景）
        if (TextUtils.isEmpty(sessionId)) {
            sessionId = AiSessionStore.createSession(this)
            freshSession = true
        }

        busy = true
        btnSend.alpha = 0.5f

        val thinking = addThinking()

        Thread({
            var answer: String
            try {
                answer = runAgent(thinking)
            } catch (e: Exception) {
                answer = "出错了：" + e.javaClass.simpleName + " " + e.message
            }
            val finalAnswer = answer
            runOnUiThread {
                removeView(thinking)
                addBubble(finalAnswer, false, false)
                history.add(AiClient.Msg.assistant(finalAnswer))
                trimHistory()
                persistSession()
                busy = false
                btnSend.alpha = 1f
            }
        }, "ai-chat").start()
    }

    /**
     * 工具调用循环：
     * 把工具定义和消息历史发给模型；模型若要求调用工具，本地执行后把结果作为
     * tool 角色消息回灌，继续下一轮，直到模型直接给文本回复或用满轮数。
     */
    private fun runAgent(thinking: View): String {
        val tools = ForumTools.definitions()
        val systemPrompt = AiConfigManager.getSystemPrompt(this)

        val msgs = ArrayList<AiClient.Msg>()
        msgs.add(AiClient.Msg.system(buildSystemPrompt(systemPrompt)))
        msgs.addAll(history)

        // 记录本轮真实调用了哪些工具，模型空答时用于给出可诊断的信息
        val calledTools = ArrayList<String>()
        val failedTools = ArrayList<String>()

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val r = round
            runOnUiThread {
                updateThinking(
                    thinking,
                    if (r == 0) "正在思考…" else "正在整理第 " + r + " 轮结果…"
                )
            }

            val result = AiClient.chat(this, msgs, tools)

            if (!result.success) {
                AiLog.e("ai-chat", "模型调用失败: " + result.error)
                return "调用模型失败：" + result.error
            }

            // 没有工具调用 → 直接输出文本，结束
            if (result.toolCalls == null || result.toolCalls!!.length() == 0) {
                val content = result.content

                // 兼容层：模型把"打算调用哪些工具"写成了文字，却没有真的发起调用。
                // 这是不支持 function calling 的中转最典型的表现，必须接住并替它执行。
                // 放在判空之前，因为这种情况 content 是非空的（就是那段计划文字）。
                if (calledTools.isEmpty()) {
                    val compat = runCompatToolCalls(
                        content ?: "", thinking, systemPrompt,
                        calledTools, failedTools
                    )
                    if (compat != null) return compat
                }

                if (TextUtils.isEmpty(content)) {
                    val uniq = uniqToolNames(calledTools)
                    AiLog.e(
                        "ai-chat", "模型返回空内容。finish_reason=" + result.finishReason +
                                " reasonLen=" + (if (result.reasoningContent == null) 0 else result.reasoningContent!!.length) +
                                " promptTokens=" + result.promptTokens +
                                " completionTokens=" + result.completionTokens +
                                " 已调用工具=" + uniq
                    )

                    // 情况一：模型其实调了工具，但首轮就空答回来是 max_tokens 不够
                    // —— 把输出上限放宽后原样再问一次
                    if ("length" == result.finishReason) {
                        AiLog.i("ai-chat", "finish_reason=length，放宽 max_tokens 重试一次")
                        val retry = AiClient.chat(this, msgs, tools, 8192)
                        if (retry.success) {
                            if (retry.toolCalls != null && retry.toolCalls!!.length() > 0) {
                                // 重试拿到了工具调用，退回正常循环处理
                                msgs.add(buildAssistantToolCallMsg(retry))
                                for (i in 0 until retry.toolCalls!!.length()) {
                                    msgs.add(
                                        executeOneToolCall(
                                            retry.toolCalls!!, i, thinking,
                                            calledTools, failedTools
                                        )
                                    )
                                }
                                continue
                            }
                            if (!TextUtils.isEmpty(retry.content)) return retry.content!!
                        }
                    }
                    if (calledTools.isEmpty()) {
                        // 兼容层：模型在文字里说了要调哪些工具却没真的调（典型不支持
                        // function calling 的中转），把它文字里的调用意图接住并真正执行
                        val compat = runCompatToolCalls(
                            content ?: "", thinking, systemPrompt,
                            calledTools, failedTools
                        )
                        if (compat != null) return compat

                        // 兜底：模型既不说话也不调工具，多半是它不支持 function calling。
                        // 改用纯文本协议再走一轮，把工具清单写进提示词里。
                        val fb = runTextFallback(thinking, systemPrompt)
                        if (fb != null) return fb

                        if (TextUtils.isEmpty(result.finishReason)) {
                            // finish_reason 为空说明服务端根本没返回标准结束原因：
                            // 大概率是推理型模型把预算全烧在思考里、或中转没转发 tools
                            return "模型没有返回内容。\n" +
                                    "链路诊断：服务端有响应，但没给出结束原因（finish_reason 为空）。\n" +
                                    "这通常意味着：\n" +
                                    "1. 你用的是推理型模型（deepseek-reasoner / o1 这类）" +
                                    "，只输出思考不输出回答，或它本身不支持工具调用\n" +
                                    "2. 中转没有把 tools 参数转发给上游\n" +
                                    "3. 模型名填错\n" +
                                    "建议先改成 gpt-4o-mini / deepseek-chat 试一次。\n" +
                                    "完整请求体与响应体已写进「侧边栏 → 运行日志」（ai-req / ai-resp）。"
                        }
                        return "模型没有返回内容。\n" +
                                "链路诊断：请求已发出且服务端有响应" +
                                "（finish_reason=" + result.finishReason +
                                "，prompt_tokens=" + result.promptTokens +
                                "，completion_tokens=" + result.completionTokens + "）。\n" +
                                "常见原因：\n" +
                                "1. 该模型不支持 function calling（换成 gpt-4o / deepseek-chat 等）\n" +
                                "2. 模型名填错，或中转不转发 tools 参数\n" +
                                "3. max_tokens 太小被截断（当前 " + AiConfigManager.getMaxTokens(this) + "）\n" +
                                "已把完整请求体与响应体写进「侧边栏 → 运行日志」（ai-req / ai-resp），" +
                                "把那段发我即可定位。"
                    }
                    return "模型读到了数据但没有给出回答。\n调用过的工具：" + uniq +
                            (if (failedTools.isEmpty()) "" else "\n其中失败的：" + uniqToolNames(failedTools))
                }

                // content 非空、兼容层也没接住：如果这段文本看起来是"调用计划"而不是
                // 最终回答（英文散文式 "I'll do multiple searches..."），别把计划原文
                // 甩给用户，转文本协议重试
                if (looksLikeToolPlan(content!!)) {
                    AiLog.i("ai-chat", "content 疑似调用计划（无工具调用），转文本协议重试")
                    val fb = runTextFallback(thinking, systemPrompt)
                    if (fb != null) return fb
                }
                return content
            }

            // 记录 assistant 的 tool_calls 消息
            msgs.add(buildAssistantToolCallMsg(result))

            // 逐个执行工具
            for (i in 0 until result.toolCalls!!.length()) {
                msgs.add(
                    executeOneToolCall(result.toolCalls!!, i, thinking, calledTools, failedTools)
                )
            }
        }

        AiLog.e("ai-chat", "工具调用超过 " + MAX_TOOL_ROUNDS + " 轮")
        return "模型连续调用了太多次工具，已停止。\n已调用：" + calledTools.joinToString(", ") +
                "\n可以换个更具体的说法再问一次。"
    }

    /**
     * 判断模型输出像不像"工具调用计划"而不是最终回答。
     * 英文散文式计划（"I'll do multiple searches... Let me search..."）的特征：
     * 计划词密集 + 工具名/意图出现，且不带答案语义。
     */
    private fun looksLikeToolPlan(text: String?): Boolean {
        if (TextUtils.isEmpty(text) || text!!.length > 1500) return false
        val lower = text.lowercase()
        // 已带答案语义的不算计划
        if (lower.contains("搜索结果") || lower.contains("执行结果") || lower.contains("以下是")
            || lower.contains("总结如下") || lower.contains("为您整理")
        ) {
            return false
        }
        val planMarkers = arrayOf(
            "i'll", "i will", "let me", "i need to", "i want to", "i'm going to",
            "first, i", "multiple searches", "several keywords",
            "调用", "搜索", "先查", "再查", "接下来我会",
            "我来搜", "我来查", "马上搜", "马上查", "我去搜", "我去查", "试搜", "试查",
            "搜一下", "查一下", "搜傅", "搜他的", "找一下", "找找", "马上执行"
        )
        var planWords = 0
        for (w in planMarkers) {
            if (lower.contains(w)) planWords++
        }
        var hasToolName = false
        for (alias in TOOL_ALIASES) {
            for (i in 1 until alias.size) {
                if (lower.contains(alias[i].lowercase())) {
                    hasToolName = true
                    break
                }
            }
            if (hasToolName) break
        }
        return planWords >= 2 || (planWords >= 1 && hasToolName)
    }

    /**
     * 文本协议回退：不带 tools 参数，改为把工具清单写进 system prompt，
     * 靠模型自己输出 {"tool":"x","args":{}} 行来驱动调用。
     * 用于不支持 function calling 的模型 / 中转。
     *
     * @return 最终回答；若这条路径也拿不到内容则返回 null，交回原流程报错
     */
    private fun runTextFallback(thinking: View, systemPrompt: String?): String? {
        runOnUiThread { updateThinking(thinking, "该模型不支持工具调用，改用文本模式重试…") }

        val msgs = ArrayList<AiClient.Msg>()
        msgs.add(
            AiClient.Msg.system(
                (if (TextUtils.isEmpty(systemPrompt))
                    AiConfigManager.defaultSystemPrompt() else systemPrompt)
                        + "\n\n" + ForumTools.textToolCatalog()
            )
        )
        msgs.addAll(history)
        val baseSize = msgs.size   // 之前的历史长度，用于最后只回收本轮新增消息

        val called = ArrayList<String>()
        for (i in 0 until MAX_TOOL_ROUNDS) {
            val r = AiClient.chat(this, msgs, null, 8192)
            if (!r.success || TextUtils.isEmpty(r.content)) return null

            val calls = parseTextToolCalls(r.content!!)
            if (calls == null || calls.length() == 0) {
                // 模型给出了正式回答，结束
                val done = AiClient.Msg.assistant(r.content)
                done.`internal` = true   // 最终回答由 sendCurrentInput 统一入史，这里不重复记
                msgs.add(done)
                recordFallbackHistory(msgs, baseSize)
                return r.content
            }

            val plan = AiClient.Msg.assistant(r.content)
            plan.`internal` = true       // 计划/调用行是内部轮
            msgs.add(plan)
            val results = StringBuilder("工具执行结果如下：\n")
            for (k in 0 until calls.length()) {
                val c = calls.optJSONObject(k) ?: continue
                val name = c.optString("tool", c.optString("name", ""))
                var args = c.optJSONObject("args")
                if (args == null) args = c.optJSONObject("arguments")
                if (args == null) args = JSONObject()
                if (TextUtils.isEmpty(name)) continue

                val tn = name
                runOnUiThread { updateThinking(thinking, "正在调用 $tn …") }
                var toolResult: String
                try {
                    toolResult = ForumTools.execute(this, name, args)
                } catch (e: Exception) {
                    toolResult = "{\"error\":\"" + e.javaClass.simpleName + "\"}"
                }
                called.add(name)
                AiLog.i("ai-tool-text", name + " -> " + AiLog.clip(toolResult, 300))
                results.append("【").append(name).append("】\n")
                    .append(AiLog.clip(toolResult, 4000)).append('\n')
            }
            val resultMsg = AiClient.Msg.user(results.toString())
            resultMsg.`internal` = true   // 工具结果回灌是内部轮
            msgs.add(resultMsg)
        }
        AiLog.e("ai-chat", "文本模式工具调用超过上限，已调用=" + uniqToolNames(called))
        return null
    }

    /** 把回退路径本轮新增的对话补进主历史，保证多轮记忆连续（不重复已有历史） */
    private fun recordFallbackHistory(msgs: List<AiClient.Msg>, baseSize: Int) {
        for (i in baseSize until msgs.size) {
            val m = msgs[i]
            // 内部轮次消息（工具结果回灌、计划文本、重申协议）不进主历史：
            // 它们是兼容层跟模型的"后台对话"，不是用户可见内容，落盘会污染会话渲染
            if (m != null && m.`internal`) continue
            history.add(m)
        }
    }

    /**
     * 从模型纯文本回复里抽取 {"tool":"x","args":{...}} 形式的调用。
     * 逐行扫描，容忍前后夹带的解释文字与 markdown 围栏；没有则返回 null。
     */
    private fun parseTextToolCalls(text: String?): JSONArray? {
        if (TextUtils.isEmpty(text)) return null
        val out = JSONArray()
        // 0) 标签式调用：`<tool_call>调用 search_forum {"keyword":"x"}` 或 `<tool_call>search_forum args`
        //    推理模型很爱用这种写法（它的 reasoning 里明确说了要搜什么），必须接住
        val tagM = Pattern
            .compile(
                "<tool_call>(?:调用\\s*)?([a-z_]+)\\s*([\\{（(][^\\n]{1,300}?)?[\\}）)]?",
                Pattern.CASE_INSENSITIVE
            )
            .matcher(text!!)
        while (tagM.find()) {
            try {
                val nm = tagM.group(1)
                if (nm == null || nm.isEmpty()) continue
                val o = JSONObject()
                o.put("tool", nm)
                val rawArgs = tagM.group(2)
                if (rawArgs != null && !rawArgs.isEmpty()) {
                    val s2 = rawArgs.indexOf('{')
                    if (s2 >= 0) {
                        val e2 = rawArgs.lastIndexOf('}')
                        if (e2 > s2) o.put("args", JSONObject(rawArgs.substring(s2, e2 + 1)))
                    }
                }
                out.put(o)
            } catch (ignore: Exception) {
            }
        }
        val lines = text.split("\\r?\\n".toRegex(), 0).toTypedArray()
        for (raw in lines) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            line = line.replace("```json", "").replace("```", "").trim()
            val s = line.indexOf('{')
            val e = line.lastIndexOf('}')
            if (s < 0 || e <= s) continue
            val jsonPart = line.substring(s, e + 1)
            try {
                val o = JSONObject(jsonPart)
                if (o.has("tool") || o.has("name")) {
                    val nm = o.optString("tool", o.optString("name", ""))
                    if (!TextUtils.isEmpty(nm)) out.put(o)
                }
            } catch (ignore: Exception) {
                // 不是工具调用行，跳过
            }
        }
        // 去重：同名的调用只留第一个
        val dedup = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until out.length()) {
            val o = out.optJSONObject(i) ?: continue
            val nm = o.optString("tool", o.optString("name", ""))
            if (seen.contains(nm)) continue
            seen.add(nm)
            dedup.put(o)
        }
        return if (dedup.length() == 0) null else dedup
    }

    // ==================== 兼容工具调用 ====================

    /**
     * 从模型自然语言回复里推断它想调用哪些工具。
     *
     * 命中条件（任一）：
     *  1) 出现 {"tool":"xxx"} 形式；
     *  2) 出现 `工具名(...)` 或 `调用 工具名` 这种明确的调用写法；
     *  3) 首轮而且文本不长、含意图词 + 至少一个工具名（典型："I should call my_threads..."）
     */
    private fun inferToolCalls(text: String?, firstRound: Boolean): JSONArray? {
        if (TextUtils.isEmpty(text)) return null
        val lower = text!!.lowercase()

        // 形式零：编号式括号调用清单。模型在散文末尾列多个调用计划：
        // "Let me try: 1. search_forum(keyword="傅", orderby="dateline")
        //  2. search_forum(keyword="Godot") 3. search_forum(keyword="godot 密钥")"
        // —— 每个 tool(key="value", ...) 都是真实意图，全部执行而不是只取第一个
        val bracket = parseBracketCallList(text)
        if (bracket != null && bracket.length() > 0) return bracket

        // 形式一：已经是 JSON 调用行，直接交给通用解析器
        val jsonCalls = parseTextToolCalls(text)
        if (jsonCalls != null && jsonCalls.length() > 0) {
            // 标签/JSON 调用里如果没带 keyword，用用户最后的提问兜底
            backfillKeywordFromQuestion(jsonCalls)
            return jsonCalls
        }

        var hasIntent = false
        for (w in INTENT_WORDS) {
            if (lower.contains(w.lowercase())) {
                hasIntent = true
                break
            }
        }

        val out = JSONArray()
        val hit = HashSet<String>()
        for (alias in TOOL_ALIASES) {
            val canonical = alias[0]
            if (hit.contains(canonical)) continue

            var idx = -1
            var matched: String? = null
            for (i in 1 until alias.size) {
                val a = alias[i].lowercase()
                val p = lower.indexOf(a)
                if (p >= 0 && (idx < 0 || p < idx)) {
                    idx = p
                    matched = a
                }
            }
            if (idx < 0) continue

            val strong = isInvocationLike(text, idx, matched!!.length)
            // 内部轮次也要接住纯文字计划（"我来搜傅的其他帖子"）：意图词仍在、文本不长即算。
            // 原来写死 firstRound 导致 compat 循环内永远 soft=false，多轮后模型的中文计划
            // 全被漏掉、原文甩脸——这就是"前期好好的、后期又失效"的根源
            val soft = hasIntent && text.length < 700
            if (!strong && !soft) continue

            hit.add(canonical)
            try {
                val call = JSONObject()
                call.put("tool", canonical)
                call.put("args", extractArgs(canonical, window(text, idx, matched.length)))
                out.put(call)
            } catch (ignore: Exception) {
            }
            if (hit.size >= 4) break
        }
        // 别名推断路径：抽完参数后同样用提问兜底关键词
        if (out.length() > 0) backfillKeywordFromQuestion(out)
        return if (out.length() == 0) null else out
    }

    /**
     * 扫描编号式括号调用清单：`search_forum(keyword="傅", orderby="dateline")`。
     * 支持一个调用多个 key="value" 参数（多出的 key 丢弃、主 key 用第一个），
     * 同 tool+key+value 去重，最多 4 个。反引号里的工具讨论（`my_threads` which...）不带括号参数，天然不误触。
     */
    private fun parseBracketCallList(text: String?): JSONArray? {
        if (text == null || text.length > 2500) return null
        try {
            val p = Pattern.compile(
                "([a-z_]{3,30})\\s*\\(\\s*([a-zA-Z_]{2,20})\\s*=\\s*[\"“]([^\"”]{1,40})[\"”][^)]{0,120}\\)",
                Pattern.CASE_INSENSITIVE
            )
            val m = p.matcher(text)
            val out = JSONArray()
            val seen = HashSet<String>()
            val known = HashSet(
                listOf(
                    "my_threads", "get_notices", "list_threads", "search_forum", "list_forums",
                    "get_thread", "get_replies", "get_user_profile", "post_reply", "unlock_hidden"
                )
            )
            while (m.find()) {
                val tool = m.group(1).lowercase()
                val key = m.group(2).lowercase()
                val `val` = m.group(3).trim()
                if (!known.contains(tool)) continue
                if (isJunkKeyword(`val`)) continue
                val dk = tool + "|" + key + "|" + `val`
                if (seen.contains(dk)) continue
                seen.add(dk)
                val call = JSONObject()
                call.put("tool", tool)
                val args = JSONObject()
                args.put(key, `val`)
                call.put("args", args)
                out.put(call)
                if (out.length() >= 4) break
            }
            return if (out.length() == 0) null else out
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 调用里缺 keyword 时，从用户最后的提问中抓关键词兜底。
     * 问题即意图：问「总结 cocos 教程」，关键词就是 cocos。
     */
    private fun backfillKeywordFromQuestion(calls: JSONArray) {
        try {
            // 用户最后一条消息
            var lastQ: String? = null
            for (i in history.size - 1 downTo 0) {
                val m = history[i]
                if ("user" == m.role && !TextUtils.isEmpty(m.content)) {
                    lastQ = m.content!!.trim()
                    break
                }
            }
            if (TextUtils.isEmpty(lastQ)) return

            for (k in 0 until calls.length()) {
                val c = calls.optJSONObject(k) ?: continue
                if ("search_forum" != c.optString("tool", c.optString("name", ""))) continue
                var args = c.optJSONObject("args")
                if (args == null) args = JSONObject()
                val kw = args.optString("keyword", "")
                if (!TextUtils.isEmpty(kw) && !isJunkKeyword(kw)) {
                    // 模型给的是纯英文词、但提问里能切出中文核心段时，中文覆盖：
                    // 中文站 Discuz 对「游戏逆向」的召回远好于 "game cracking"
                    if (kw.matches(Regex("[A-Za-z][A-Za-z0-9'&+.-]*"))) {
                        val cn = extractCnCore(lastQ)
                        if (!isJunkKeyword(cn)) {
                            AiLog.i(
                                "ai-compat", "关键词覆盖(search_forum): 模型英文词\"" +
                                        kw + "\"改用提问中文核心词: " + cn
                            )
                            args.put("keyword", cn)
                            c.put("args", args)
                        }
                    }
                    continue
                }

                // 从提问里抓关键词，优先级：
                // 1) 中文核心词（中文站搜中文最准，先用停用词切出核心段）
                // 2) 引号词 3) 2 字以上英文/数字词 4) 兜底中文连续段
                var cand = extractCnCore(lastQ!!)
                if (isJunkKeyword(cand)) {
                    cand = match(lastQ, "[\"“]([^\"”]{2,20})[\"”]")
                }
                if (isJunkKeyword(cand)) {
                    cand = match(lastQ, "([A-Za-z0-9][A-Za-z0-9_+#.-]{2,30})")
                }
                if (isJunkKeyword(cand)) {
                    cand = match(lastQ, "([\\u4e00-\\u9fa5]{2,8})")
                }
                if (!isJunkKeyword(cand)) {
                    args.put("keyword", cand)
                    AiLog.i(
                        "ai-compat", "关键词兜底(search_forum): 模型给的\"" +
                                (if (kw == null) "" else kw) + "\"不可用，改用提问核心词: " + cand
                    )
                }
                c.put("args", args)
            }
        } catch (ignore: Exception) {
        }
    }

    /** 关键词是否为垃圾（空/太短/纯标点序号/英文通用功能词）；中文单字是合法搜索词（如用户名"傅"），放行 */
    private fun isJunkKeyword(s: String?): Boolean {
        if (s == null) return true
        var t = s.trim()
        if (t.isEmpty()) return true
        if (t.length < 2 && !t.matches(Regex("[\\u4e00-\\u9fa5]"))) return true
        if (t.matches(Regex("^[\\d.\\-\\s]+$")) || t.matches(Regex("^[.\\-—_\\s]+$"))) return true
        // 英文通用功能词（模型散文里抠出来的 "posts"/"keywords"/"several" 这类）判废
        return KW_STOP.contains(t.lowercase())
    }

    /**
     * 从中文提问里抠核心词：先剔除意图/功能词，再按剩余切缝分段，取最长的纯中文段。
     * 「总结论坛里游戏逆向分析的高质量帖子」→ 剔除后 →「游戏逆向」
     * 单字残段（如用户名"傅"）是合法搜索词：没有更长段时回退取第一个单字段。
     */
    private fun extractCnCore(q: String?): String? {
        if (q == null) return null
        var s = q!!
        for (w in QUESTION_STOP_WORDS) s = s.replace(w, "|")
        var best: String? = null
        var single: String? = null
        for (seg0 in s.split("\\|+".toRegex(), 0).toTypedArray()) {
            val seg = seg0.trim()
            if (seg.isEmpty()) continue
            if (!seg.matches(Regex("[\\u4e00-\\u9fa5]+"))) continue   // 只留纯中文段
            if (seg.length == 1) {
                if (single == null) single = seg
                continue
            }
            if (best == null || seg.length > best!!.length) best = seg
        }
        // 超过 12 个字的段基本是没切开的整句，不算核心词
        if (best != null && best!!.length <= 12) return best
        // 全是单字段时（「看看|傅|||帖子」）取第一个单字，别让"傅"被丢掉
        return if (best == null && single != null) single else null
    }

    /** 工具名附近 ±160 字符的窗口，参数基本都在这里面 */
    private fun window(text: String, idx: Int, nameLen: Int): String {
        val from = Math.max(0, idx - 80)
        val to = Math.min(text.length, idx + nameLen + 200)
        return text.substring(from, to)
    }

    /** 工具名写法像不像"真的在调用"：带括号，或前面紧跟调用动词 */
    private fun isInvocationLike(text: String, idx: Int, nameLen: Int): Boolean {
        val after = text.substring(Math.min(text.length, idx + nameLen))
        val at = after.trim()
        if (at.startsWith("(") || at.startsWith("（")) return true
        if (at.startsWith("=")) return true

        val before = text.substring(Math.max(0, idx - 14), idx).lowercase()
        return before.contains("调用") || before.contains("call") ||
                before.contains("执行") || before.contains("invoke")
    }

    /** 按工具类型从窗口文本里抠参数；抠不到就返回空对象，由工具自己用默认值 */
    private fun extractArgs(tool: String, w: String): JSONObject {
        val a = JSONObject()
        try {
            when (tool) {
                "search_forum" -> {
                    // 关键词抽取：显式 key=value 优先；中文单字（用户名"傅"等）是合法搜索词，放行
                    var kw = match(w, "(?:keyword|kw|关键词|搜索词|srchtxt)[=:：]\\s*[\"“]([^\"”]{1,40})[\"”]")
                    if (kw == null) {
                        kw = match(w, "(?:keyword|kw|关键词|搜索词|srchtxt)[=:：]\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,40})")
                    }
                    if (kw == null) {
                        // 引号词两轮逐试：模型爱列一串候选（"game reverse engineering",
                        // "game cracking", "Unity", "Il2Cpp"...）。单词候选最准，优先取；
                        // 双词短语次之；3 词以上长短语在 Discuz 整词匹配基本零结果，直接废
                        val qm = Pattern
                            .compile("[\"“]([^\"”]{1,40})[\"”]", Pattern.CASE_INSENSITIVE)
                            .matcher(w)
                        while (qm.find()) {
                            val cand = qm.group(1).trim()
                            if (cand.contains(" ")) continue      // 多词短语留给第二轮
                            // 整句引用（"关于找到密钥，我在上一篇帖子有讲"）不是关键词，跳过
                            if (cand.matches(Regex(".*[，。、！？；：,.!?;:].*"))) continue
                            if (isJunkKeyword(cand)) continue
                            kw = cand
                            break
                        }
                        if (kw == null) {
                            qm.reset()
                            while (qm.find()) {
                                val cand = qm.group(1).trim()
                                if (isJunkKeyword(cand)) continue
                                if (cand.matches(Regex("[A-Za-z][A-Za-z'&+.-]*(\\s+[A-Za-z][A-Za-z'&+.-]*){2,}"))) continue
                                kw = cand
                                break
                            }
                        }
                    }
                    // 关键词合法化：统一走 isJunkKeyword（空/太短/纯标点序号/英文通用停用词）
                    if (kw != null && isJunkKeyword(kw)) kw = null
                    // 多词英文长短语（如 "game reverse engineering"）在 Discuz 上整词匹配基本零结果，
                    // 判废交给 backfill 从用户提问里抓核心词
                    if (kw != null && kw.matches(Regex("[A-Za-z][A-Za-z'&+.-]*(\\s+[A-Za-z][A-Za-z'&+.-]*){2,}"))) {
                        kw = null
                    }
                    if (kw != null) a.put("keyword", kw)
                    val ob = match(w, "(lastpost|dateline|replies)")
                    if (ob != null) a.put("orderby", ob)
                }

                "list_threads" -> {
                    val fid = match(w, "(?:fid|forum_?id|版块(?:id|ID)?)[=:：]?\\s*[\"“]?(\\d{1,6})")
                    if (fid != null) a.put("fid", fid)
                }

                "get_thread", "get_replies" -> {
                    var tid = match(w, "(?:tid|thread_?id|帖子(?:id|ID)?|主题(?:id|ID)?)[=:：]?\\s*[\"“]?(\\d{1,9})")
                    if (tid == null) tid = match(w, "thread-(\\d{1,9})")
                    if (tid != null) a.put("tid", tid)
                    val mr = match(w, "(?:max_replies|最多)[=:：]?\\s*(\\d{1,3})")
                    if (mr != null) a.put("max_replies", mr)
                }

                "get_notices" -> {
                    val ty = match(w, "(pm|mypost|interactive|system)")
                    if (ty != null) a.put("type", ty)
                }

                "get_user_profile" -> {
                    val uid = match(w, "(?:uid|用户(?:id|ID)?)[=:：]?\\s*[\"“]?(\\d{1,9})")
                    if (uid != null) a.put("uid", uid)
                }

                "post_reply" -> {
                    val tid = match(w, "(?:tid|帖子(?:id|ID)?)[=:：]?\\s*[\"“]?(\\d{1,9})")
                    if (tid != null) a.put("tid", tid)
                    val msg = match(w, "(?:message|回复内容|内容)[=:：]\\s*[\"“]([^\"”]{1,200})[\"”]")
                    if (msg != null) a.put("message", msg)
                }

                else -> {
                }
            }
            val page = match(w, "(?:page|页码)[=:：]?\\s*(\\d{1,4})")
            if (page != null && !a.has("page")) a.put("page", page)
        } catch (ignore: Exception) {
        }
        return a
    }

    private fun match(src: String, regex: String): String? {
        try {
            val m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(src)
            if (m.find()) {
                val g = m.group(1)
                return if (g == null) null else g.trim()
            }
        } catch (ignore: Exception) {
        }
        return null
    }

    /**
     * 兼容执行：把自然语言里的调用意图真正跑一遍，结果回灌让模型作答。
     *
     * @return 最终回答；识别不出调用意图、或这条路径失败时返回 null（交回原流程）
     */
    private fun runCompatToolCalls(
        firstContent: String, thinking: View, systemPrompt: String?,
        calledTools: MutableList<String>, failedTools: MutableList<String>
    ): String? {
        var calls = inferToolCalls(firstContent, true)
        if (calls == null || calls.length() == 0) return null

        runOnUiThread { updateThinking(thinking, "识别到工具调用意图，正在执行…") }
        AiLog.i("ai-compat", "从文本推断出调用: " + calls.toString())

        val msgs = ArrayList<AiClient.Msg>()
        msgs.add(
            AiClient.Msg.system(
                (if (TextUtils.isEmpty(systemPrompt))
                    AiConfigManager.defaultSystemPrompt() else systemPrompt)
                        + "\n\n" + ForumTools.textToolCatalog()
            )
        )
        msgs.addAll(history)
        val baseSize = msgs.size
        val first = AiClient.Msg.assistant(firstContent)
        first.`internal` = true   // 计划散文是内部轮，不进主历史
        msgs.add(first)

        for (round in 0 until 3) {
            val results = StringBuilder("工具执行结果如下：\n")
            for (k in 0 until calls!!.length()) {
                val c = calls.optJSONObject(k) ?: continue
                val name = c.optString("tool", "")
                var args = c.optJSONObject("args")
                if (args == null) args = JSONObject()
                if (TextUtils.isEmpty(name)) continue

                val tn = name
                runOnUiThread { updateThinking(thinking, "正在调用 $tn …") }
                var toolResult: String
                try {
                    toolResult = ForumTools.execute(this, name, args)
                } catch (e: Exception) {
                    toolResult = "{\"error\":\"" + e.javaClass.simpleName + "\"}"
                }
                calledTools.add(name)
                if (toolResult.contains("\"error\"")) failedTools.add(name)
                AiLog.i("ai-compat", name + " -> " + AiLog.clip(toolResult, 300))
                results.append("【").append(name).append("】\n")
                    .append(AiLog.clip(toolResult, 4000)).append('\n')
            }
            val toolFeedback = AiClient.Msg.user(
                results.toString() + "\n请基于以上真实数据回答用户，不要再输出工具调用计划。"
            )
            toolFeedback.`internal` = true
            msgs.add(toolFeedback)

            val r = AiClient.chat(this, msgs, null, 8192)
            if (!r.success || TextUtils.isEmpty(r.content)) return null

            // 它又说要调工具 → 再跑一轮；否则这就是最终回答
            val next = inferToolCalls(r.content, false)
            val respMsg = AiClient.Msg.assistant(r.content)
            respMsg.`internal` = true   // 中间轮回复是内部轮
            msgs.add(respMsg)
            if (next == null) {
                // 内部轮次的"我要继续调用"计划文本不是最终回答，别甩给用户。
                // 重申一次文本协议，通常下一轮模型就乖乖输出 JSON 调用行
                if (looksLikeToolPlan(r.content)) {
                    AiLog.i("ai-compat", "内部轮次输出疑似调用计划，重申文本协议后继续")
                    val restate = AiClient.Msg.user(
                        "你刚才的输出是调用计划而不是最终回答。" +
                                "请直接输出工具调用行：{\"tool\":\"工具名\",\"args\":{\"参数名\":\"值\"}}，" +
                                "或基于已有数据给出面向用户的最终回答，不要再描述计划。"
                    )
                    restate.`internal` = true
                    msgs.add(restate)
                    continue
                }
                recordFallbackHistory(msgs, baseSize)
                return r.content
            }
            calls = next
        }
        AiLog.e("ai-compat", "兼容模式轮数用尽，已调用=" + uniqToolNames(calledTools))
        return null
    }

    /** 把 assistant 的 tool_calls 轮封成消息，content 为空时留空串（AiClient 会转成 null） */
    private fun buildAssistantToolCallMsg(result: AiClient.Result): AiClient.Msg {
        val assistantMsg = AiClient.Msg.assistant(result.content)
        assistantMsg.toolCallsJson = result.toolCalls.toString()
        return assistantMsg
    }

    /** 执行 tool_calls 里的第 i 个调用，返回可直接回灌模型的 tool 消息 */
    private fun executeOneToolCall(
        toolCalls: JSONArray, i: Int, thinking: View,
        calledTools: MutableList<String>, failedTools: MutableList<String>
    ): AiClient.Msg {
        val call = toolCalls.optJSONObject(i)
            ?: return AiClient.Msg.tool("call_$i", "", "{\"error\":\"空 tool_call\"}")

        val callId = call.optString("id", "call_$i")
        val fn = call.optJSONObject("function")
        val name = if (fn != null) fn.optString("name", "") else ""
        val argsRaw = if (fn != null) fn.optString("arguments", "{}") else "{}"

        var args: JSONObject
        args = try {
            JSONObject(if (TextUtils.isEmpty(argsRaw)) "{}" else argsRaw)
        } catch (e: Exception) {
            JSONObject()
        }

        val toolName = name
        runOnUiThread { updateThinking(thinking, "正在调用 $toolName …") }

        var toolResult: String
        try {
            toolResult = ForumTools.execute(this, name, args)
        } catch (e: Exception) {
            toolResult = "{\"error\":\"" + e.javaClass.simpleName + ":" +
                    (if (e.message == null) "" else e.message!!.replace("\"", "'")) +
                    "\"}"
        }
        if (toolResult.isEmpty()) {
            toolResult = "{\"error\":\"工具返回空\"}"
        }

        calledTools.add(name)
        val isErr = toolResult.contains("\"error\"")
        if (isErr) failedTools.add(name)
        AiLog.i("ai-tool", name + " -> " + AiLog.clip(toolResult, 300))

        return AiClient.Msg.tool(callId, name, toolResult)
    }

    /** 工具名去重并保持顺序，避免日志里同一个工具刷一长串 */
    private fun uniqToolNames(names: List<String>?): String {
        if (names == null || names.isEmpty()) return "无"
        val out = ArrayList<String>()
        for (n in names) {
            if (!TextUtils.isEmpty(n) && !out.contains(n)) out.add(n)
        }
        return out.joinToString(", ")
    }

    private fun buildSystemPrompt(base: String?): String {
        val now = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        return (if (TextUtils.isEmpty(base)) AiConfigManager.defaultSystemPrompt() else base) +
                "\n\n当前时间：" + now +
                "\n你可以调用提供的工具去读取论坛真实内容。" +
                "需要看帖子内容时先调工具拿数据，不要凭空编造。" +
                "调用工具前想清楚需要哪些参数，一次尽量拿全。" +
                "只有在用户明确要求「回复」「发评论」时才调用 post_reply，" +
                "并且要基于真实读到的帖子内容写回复，禁止编造楼层或用户。"
    }

    private fun trimHistory() {
        if (history.size <= MAX_CONTEXT_MESSAGES) return

        // 关键：assistant(tool_calls) 与后续 tool 消息必须成对出现，
        // 不能从中间截断，否则服务端会直接报错或返回空。
        // 做法是从拟删除位置向后找最近的 user 消息作为新的起点。
        val cut = history.size - MAX_CONTEXT_MESSAGES
        var start = cut
        while (start < history.size && "user" != history[start].role) {
            start++
        }
        if (start >= history.size) {
            // 极端情况：找不到 user 边界，退化为保留最后一半
            start = history.size / 2
        }
        while (start > 0) {
            history.removeAt(0)
            start--
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private const val MAX_CONTEXT_MESSAGES = 40

        /** 工具名 + 常见别名，用于从自然语言回复里识别调用意图 */
        private val TOOL_ALIASES = arrayOf(
            arrayOf("my_threads", "my_threads", "mythreads", "my threads", "我的帖子", "我的主题"),
            arrayOf("get_notices", "get_notices", "getnotices", "notices", "notice", "通知", "私信"),
            arrayOf("list_threads", "list_threads", "listthreads", "latest threads", "最新帖", "最新帖子"),
            arrayOf(
                "search_forum", "search_forum", "searchforum", "search for", "search the",
                "search posts", "search several keywords", "search", "find posts", "look up",
                "搜帖", "搜一下", "搜帖子", "搜索帖子", "搜索", "搜"
            ),
            arrayOf("list_forums", "list_forums", "listforums", "版块列表", "论坛版块"),
            arrayOf("get_thread", "get_thread", "getthread", "帖子详情", "帖子正文"),
            arrayOf("get_replies", "get_replies", "getreplies", "回复列表"),
            arrayOf("get_user_profile", "get_user_profile", "getuserprofile", "用户资料"),
            arrayOf("post_reply", "post_reply", "postreply", "发表回复"),
            arrayOf("unlock_hidden", "unlock_hidden", "unlockhidden", "解锁隐藏", "回复可见", "解锁隐藏内容")
        )

        /** 意图词：出现这些词 + 工具名，才认定模型是"想调用"而不是"在解释" */
        private val INTENT_WORDS = arrayOf(
            "我要", "我需要", "我应该", "我来", "打算", "计划", "调用", "检查", "查询", "获取",
            "i should", "i need", "i will", "i'll", "let me", "call the", "use the", "i can",
            "i need to", "i want to", "i'm going to", "i will do", "let's search", "i'll do",
            "我来搜", "我来查", "马上搜", "马上查", "我去搜", "我去查", "试搜", "试查",
            "搜一下", "查一下", "直接搜", "先搜", "再搜", "搜他", "找一下", "找找"
        )

        /** 英文通用功能词黑名单：这些词出现在模型散文里不代表搜索意图 */
        private val KW_STOP: MutableSet<String> = HashSet<String>(
            listOf(
                "search", "searching", "searches", "keyword", "keywords", "posts", "post",
                "forum", "forums", "thread", "threads", "several", "multiple", "parallel",
                "first", "second", "then", "next", "need", "want", "about", "related",
                "topics", "topic", "high", "quality", "summarize", "summarizing", "user",
                "users", "wants", "lets", "will", "with", "sort", "sorted", "sorting",
                "recent", "latest", "replies", "game", "games", "cracking", "modding"
            )
        )

        /** 提问里的意图/功能词黑名单：中文核心词抽取前先剔掉它们 */
        private val QUESTION_STOP_WORDS = arrayOf(
            "总结", "归纳", "概括", "汇总", "整理", "介绍", "讲解", "帮我", "麻烦", "请",
            "找找", "找一下", "看看", "查看", "列出", "推荐", "搜索", "查找", "检索",
            "论坛", "社区", "帖子", "主题", "教程", "相关", "关于", "高质量", "优质",
            "内容", "分析", "回复", "评论",
            "其他", "另外", "哪些", "什么", "怎么",
            "里", "中", "上", "下", "的", "了", "是", "有", "和", "与", "或", "吧",
            "呢", "呀", "个", "这", "那", "些", "想", "要", "需", "一", "二", "三"
        )
    }
}
