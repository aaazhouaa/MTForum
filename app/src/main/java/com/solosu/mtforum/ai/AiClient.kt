package com.solosu.mtforum.ai

import android.content.Context
import android.text.TextUtils

import com.solosu.mtforum.network.HttpClient

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import java.util.concurrent.TimeUnit

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI 兼容协议客户端。
 * 支持 /chat/completions 的普通对话与 function calling（工具调用）。
 */
object AiClient {

    private val JSON_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

    /** 对话消息 */
    class Msg(role: String?, content: String?) {
        @JvmField
        var role: String? = role          // system / user / assistant / tool
        @JvmField
        var content: String? = content
        @JvmField
        var toolCallId: String? = null    // role=tool 时对应哪次调用
        @JvmField
        var toolName: String? = null

        /** assistant 发起工具调用时的原始 tool_calls JSON 数组字符串 */
        @JvmField
        var toolCallsJson: String? = null

        /**
         * 兼容层内部轮次的消息（工具结果回灌、计划文本、重申协议提示）。
         * 标记后：不进主 history、不渲染气泡、不落盘 —— 用户可见的对话
         * 只留 user 提问和 assistant 最终回答（Codex 式干净会话）。
         */
        @JvmField
        var `internal`: Boolean = false

        companion object {
            @JvmStatic
            fun system(s: String?): Msg {
                return Msg("system", s)
            }

            @JvmStatic
            fun user(s: String?): Msg {
                return Msg("user", s)
            }

            @JvmStatic
            fun assistant(s: String?): Msg {
                return Msg("assistant", s)
            }

            @JvmStatic
            fun tool(callId: String?, name: String?, result: String?): Msg {
                val m = Msg("tool", result)
                m.toolCallId = callId
                m.toolName = name
                return m
            }
        }
    }

    /** 工具定义 */
    class ToolDef(name: String?, description: String?, parametersJson: String?) {
        @JvmField
        var name: String? = name
        @JvmField
        var description: String? = description
        @JvmField
        var parametersJson: String? = parametersJson   // JSON Schema 字符串
    }

    /** 单次补全结果 */
    class Result {
        @JvmField
        var success: Boolean = false
        @JvmField
        var error: String? = null
        @JvmField
        var content: String? = null             // 文本内容
        @JvmField
        var toolCalls: JSONArray? = null        // 工具调用数组，可能为 null
        @JvmField
        var finishReason: String? = null

        /** 推理型模型的思考内容（deepseek-reasoner 等），正式回答仍在 content */
        @JvmField
        var reasoningContent: String? = null
        @JvmField
        var promptTokens: Int = 0
        @JvmField
        var completionTokens: Int = 0

        /** listModels 时返回的可用模型 id 列表 */
        @JvmField
        var models: MutableList<String>? = null
    }

    @JvmStatic
    fun isLocalEndpoint(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase(java.util.Locale.ROOT).trim()
        return lower.contains("127.0.0.1") ||
                lower.contains("localhost") ||
                lower.contains("192.168.") ||
                lower.contains("10.") ||
                lower.contains("172.16.") ||
                lower.contains("172.17.") ||
                lower.contains("172.18.") ||
                lower.contains("172.19.") ||
                lower.contains("172.2") ||
                lower.contains("172.3") ||
                lower.startsWith("http://")
    }

    private fun buildClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(Math.max(30, timeoutSeconds).toLong(), TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 发起一次 chat 补全。
     *
     * @param tools 可传 null 表示不使用工具调用
     */
    @JvmStatic
    fun chat(context: Context, messages: List<Msg>, tools: List<ToolDef>?): Result {
        return chat(context, messages, tools, -1)
    }

    /**
     * 发起一次 chat 补全。
     *
     * @param maxTokensOverride 大于 0 时覆盖配置里的 max_tokens，用于「输出被截断」时放宽重试
     */
    @JvmStatic
    fun chat(
        context: Context, messages: List<Msg>, tools: List<ToolDef>?,
        maxTokensOverride: Int
    ): Result {
        val r = Result()
        val base = AiConfigManager.getBaseUrl(context)
        val key = AiConfigManager.getApiKey(context)
        val model = AiConfigManager.getModel(context)

        if (TextUtils.isEmpty(key) && !isLocalEndpoint(base)) {
            r.error = "未配置 API Key"
            return r
        }
        if (TextUtils.isEmpty(model)) {
            r.error = "未配置模型名称"
            return r
        }

        val url = normalizeEndpoint(base)
        try {
            val body = JSONObject()
            body.put("model", model)

            val arr = JSONArray()
            for (m in messages) {
                val o = JSONObject()
                o.put("role", m.role)
                if ("tool" == m.role) {
                    o.put("tool_call_id", if (m.toolCallId == null) "" else m.toolCallId)
                    o.put("content", if (m.content == null) "" else m.content)
                    if (!TextUtils.isEmpty(m.toolName)) o.put("name", m.toolName)
                } else if ("assistant" == m.role && !TextUtils.isEmpty(m.toolCallsJson)) {
                    // 工具调用轮的 content 通常为空，严格服务端要求 null 而非空串
                    o.put("content", if (TextUtils.isEmpty(m.content)) JSONObject.NULL else m.content)
                    o.put("tool_calls", JSONArray(m.toolCallsJson))
                } else {
                    o.put("content", if (m.content == null) "" else m.content)
                }
                arr.put(o)
            }
            body.put("messages", arr)

            if (tools != null && tools.isNotEmpty()) {
                val toolArr = JSONArray()
                for (t in tools) {
                    val fn = JSONObject()
                    fn.put("name", t.name)
                    fn.put("description", t.description)
                    if (!TextUtils.isEmpty(t.parametersJson)) {
                        fn.put("parameters", JSONObject(t.parametersJson))
                    } else {
                        val empty = JSONObject()
                        empty.put("type", "object")
                        empty.put("properties", JSONObject())
                        fn.put("parameters", empty)
                    }
                    val wrapper = JSONObject()
                    wrapper.put("type", "function")
                    wrapper.put("function", fn)
                    toolArr.put(wrapper)
                }
                body.put("tools", toolArr)
                body.put("tool_choice", "auto")
            }

            body.put("temperature", AiConfigManager.getTemperature(context).toDouble())
            val maxTokens = if (maxTokensOverride > 0) maxTokensOverride
            else AiConfigManager.getMaxTokens(context)
            body.put("max_tokens", maxTokens)
            // 显式声明非流式，避免部分中转默认走 SSE 导致这里解析到空
            body.put("stream", false)

            // 部分模型（推理型 / o 系列 / 强制 temperature=1 的中转）不接受自定义 temperature，
            // 传了会直接 400。这里做成开关：默认只在允许时才带。
            val sendTemperature = AiConfigManager.isSendTemperature(context)
            if (!sendTemperature) {
                body.remove("temperature")
            }

            val requestBody0 = body.toString()
            AiLog.i(
                "ai-req", "POST " + url + "\nmodel=" + model +
                        " max_tokens=" + maxTokens +
                        " temperature=" + (if (sendTemperature) AiConfigManager.getTemperature(context).toString() else "不发送") +
                        " tools=" + (if (tools == null) 0 else tools.size) +
                        " messages=" + messages.size +
                        " bodyBytes=" + requestBody0.toByteArray(charset("UTF-8")).size +
                        "\nbody=" + AiLog.clip(requestBody0, 1200)
            )

            val client = buildClient(AiConfigManager.getTimeoutSeconds(context))
            var response = post(client, url, key, requestBody0)
            var resp = if (response.body != null) response.body!!.string() else ""
            var code = response.code
            response.close()

            // 温度被拒：去掉 temperature 原样重试，并把「不发 temperature」记进配置，避免每次都白跑一次
            if (!response.isSuccessful && looksLikeTemperatureError(resp)) {
                AiLog.i("ai-req", "temperature 被服务端拒绝，去掉后重试，并记住该选择")
                body.remove("temperature")
                AiConfigManager.setSendTemperature(context, false)
                val requestBody1 = body.toString()
                AiLog.i(
                    "ai-req", "POST(重试, 不带 temperature) " + url +
                            "\nbody=" + AiLog.clip(requestBody1, 1200)
                )
                val retry = post(client, url, key, requestBody1)
                resp = if (retry.body != null) retry.body!!.string() else ""
                code = retry.code
                retry.close()
            }

            AiLog.i(
                "ai-resp", "HTTP " + code + " bytes=" + resp.length +
                        "\n" + AiLog.clip(resp, 1500)
            )
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code + " " + brief(resp)
                return r
            }
            return parseResponse(resp)
        } catch (e: Exception) {
            r.error = e.javaClass.simpleName + ": " + e.message
            return r
        }
    }

    /** 发送一次 POST /chat/completions */
    @Throws(Exception::class)
    private fun post(client: OkHttpClient, url: String, key: String?, body: String): Response {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
        if (!TextUtils.isEmpty(key)) {
            reqBuilder.header("Authorization", "Bearer " + key)
        }
        val request = reqBuilder
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return client.newCall(request).execute()
    }

    /** 判断错误体是不是「temperature 不被接受」这一类 */
    private fun looksLikeTemperatureError(resp: String?): Boolean {
        if (TextUtils.isEmpty(resp)) return false
        val lower = resp!!.lowercase()
        return lower.contains("temperature")
    }

    private fun parseResponse(resp: String): Result {
        val r = Result()
        try {
            val json = JSONObject(resp)
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                r.error = if (err != null) err.optString("message", resp) else resp
                return r
            }
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                r.promptTokens = usage.optInt("prompt_tokens", 0)
                r.completionTokens = usage.optInt("completion_tokens", 0)
            }
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                r.error = "返回内容为空: " + brief(resp)
                return r
            }
            val choice = choices.getJSONObject(0)
            r.finishReason = choice.optString("finish_reason", "")
            val msg = choice.optJSONObject("message")
            if (msg != null) {
                r.content = msg.optString("content", "")
                if (msg.isNull("content")) r.content = ""
                // 推理型模型把思考写在 reasoning_content，正式回答在 content；
                // 若只有思考没有回答，把思考当作内容兜底，避免用户看到一片空白
                r.reasoningContent = msg.optString("reasoning_content", "")
                if (TextUtils.isEmpty(r.content) && !TextUtils.isEmpty(r.reasoningContent)) {
                    r.content = r.reasoningContent
                }
                val tc = msg.optJSONArray("tool_calls")
                if (tc != null && tc.length() > 0) r.toolCalls = tc
            }
            r.success = true
            return r
        } catch (e: Exception) {
            r.error = "解析失败: " + e.message
            return r
        }
    }

    /** 把 base URL 拼成完整的 chat/completions 端点，兼容用户填 /v1 或直接填完整地址 */
    @JvmStatic
    fun normalizeEndpoint(base: String?): String {
        var b = if (base == null) "" else base.trim()
        if (b.isEmpty()) b = "https://api.openai.com/v1"
        while (b.endsWith("/")) b = b.substring(0, b.length - 1)
        if (b.endsWith("/chat/completions")) return b
        if (b.endsWith("/v1")) return "$b/chat/completions"
        return "$b/chat/completions"
    }

    /** 拼出模型列表端点 /models */
    @JvmStatic
    fun normalizeModelsEndpoint(base: String?): String {
        var b = if (base == null) "" else base.trim()
        if (b.isEmpty()) b = "https://api.openai.com/v1"
        while (b.endsWith("/")) b = b.substring(0, b.length - 1)
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length - "/chat/completions".length)
        if (b.endsWith("/v1")) return "$b/models"
        return "$b/models"
    }

    /**
     * 拉取服务端可用模型列表（OpenAI 兼容的 GET /models）。
     * 接口不支持时返回带 error 的结果，调用方自行兜底。
     */
    @JvmStatic
    fun listModels(context: Context): Result {
        val r = Result()
        val base = AiConfigManager.getBaseUrl(context)
        val key = AiConfigManager.getApiKey(context)
        if (TextUtils.isEmpty(key) && !isLocalEndpoint(base)) {
            r.error = "未配置 API Key"
            return r
        }
        val url = normalizeModelsEndpoint(base)
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
            if (!TextUtils.isEmpty(key)) {
                reqBuilder.header("Authorization", "Bearer " + key)
            }
            val request = reqBuilder.build()
            val client = buildClient(Math.max(30, AiConfigManager.getTimeoutSeconds(context)))
            client.newCall(request).execute().use { response ->
                val resp = if (response.body != null) response.body!!.string() else ""
                if (!response.isSuccessful) {
                    r.error = "HTTP " + response.code + " " + brief(resp)
                    return r
                }
                return parseModelList(resp)
            }
        } catch (e: Exception) {
            r.error = e.javaClass.simpleName + ": " + e.message
            return r
        }
    }

    /** 解析 /models 返回，把模型 id 列表塞进 Result 的 models 字段 */
    private fun parseModelList(resp: String): Result {
        val r = Result()
        try {
            val json = JSONObject(resp)
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                r.error = if (err != null) err.optString("message", resp) else resp
                return r
            }
            val ids = ArrayList<String>()
            var data = json.optJSONArray("data")
            if (data == null) data = json.optJSONArray("models")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i)
                    if (o == null) {
                        // 少数服务端直接返回字符串数组
                        val s = data.optString(i, "")
                        if (!TextUtils.isEmpty(s)) ids.add(s)
                        continue
                    }
                    var id = o.optString("id", "")
                    if (TextUtils.isEmpty(id)) id = o.optString("name", "")
                    if (!TextUtils.isEmpty(id)) ids.add(id)
                }
            }
            if (ids.isEmpty()) {
                r.error = "接口未返回模型列表: " + brief(resp)
                return r
            }
            java.util.Collections.sort(ids)
            r.models = ids
            r.success = true
            return r
        } catch (e: Exception) {
            r.error = "解析失败: " + e.message
            return r
        }
    }

    /** 阻塞式单轮对话（不带工具），供自动回复等场景使用 */
    @JvmStatic
    fun simpleChat(context: Context, systemPrompt: String?, userPrompt: String?): String? {
        val msgs = ArrayList<Msg>()
        if (!TextUtils.isEmpty(systemPrompt)) msgs.add(Msg.system(systemPrompt))
        msgs.add(Msg.user(if (userPrompt == null) "" else userPrompt))
        val r = chat(context, msgs, null)
        if (!r.success) return null
        return r.content
    }

    private fun brief(s: String?): String {
        if (s == null) return ""
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 220) t.substring(0, 220) + "..." else t
    }

    /** 供外部复用的论坛会话 HTTP 实例 */
    @JvmStatic
    fun forum(): HttpClient {
        return HttpClient.getInstance()
    }
}
