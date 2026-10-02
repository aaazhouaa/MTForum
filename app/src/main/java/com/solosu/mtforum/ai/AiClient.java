package com.solosu.mtforum.ai;

import android.content.Context;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI 兼容协议客户端。
 * 支持 /chat/completions 的普通对话与 function calling（工具调用）。
 */
public final class AiClient {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 对话消息 */
    public static class Msg {
        public String role;          // system / user / assistant / tool
        public String content;
        public String toolCallId;    // role=tool 时对应哪次调用
        public String toolName;
        /** assistant 发起工具调用时的原始 tool_calls JSON 数组字符串 */
        public String toolCallsJson;
        /**
         * 兼容层内部轮次的消息（工具结果回灌、计划文本、重申协议提示）。
         * 标记后：不进主 history、不渲染气泡、不落盘 —— 用户可见的对话
         * 只留 user 提问和 assistant 最终回答（Codex 式干净会话）。
         */
        public boolean internal;

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Msg system(String s) { return new Msg("system", s); }
        public static Msg user(String s) { return new Msg("user", s); }
        public static Msg assistant(String s) { return new Msg("assistant", s); }

        public static Msg tool(String callId, String name, String result) {
            Msg m = new Msg("tool", result);
            m.toolCallId = callId;
            m.toolName = name;
            return m;
        }
    }

    /** 工具定义 */
    public static class ToolDef {
        public String name;
        public String description;
        public String parametersJson;   // JSON Schema 字符串

        public ToolDef(String name, String description, String parametersJson) {
            this.name = name;
            this.description = description;
            this.parametersJson = parametersJson;
        }
    }

    /** 单次补全结果 */
    public static class Result {
        public boolean success;
        public String error;
        public String content;             // 文本内容
        public JSONArray toolCalls;        // 工具调用数组，可能为 null
        public String finishReason;
        /** 推理型模型的思考内容（deepseek-reasoner 等），正式回答仍在 content */
        public String reasoningContent;
        public int promptTokens;
        public int completionTokens;
        /** listModels 时返回的可用模型 id 列表 */
        public List<String> models;
    }

    private AiClient() {}

    private static OkHttpClient buildClient(int timeoutSeconds) {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Math.max(30, timeoutSeconds), TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 发起一次 chat 补全。
     *
     * @param tools 可传 null 表示不使用工具调用
     */
    public static Result chat(Context context, List<Msg> messages, List<ToolDef> tools) {
        return chat(context, messages, tools, -1);
    }

    /**
     * 发起一次 chat 补全。
     *
     * @param maxTokensOverride 大于 0 时覆盖配置里的 max_tokens，用于「输出被截断」时放宽重试
     */
    public static Result chat(Context context, List<Msg> messages, List<ToolDef> tools,
                              int maxTokensOverride) {
        Result r = new Result();
        String base = AiConfigManager.getBaseUrl(context);
        String key = AiConfigManager.getApiKey(context);
        String model = AiConfigManager.getModel(context);

        if (TextUtils.isEmpty(key)) {
            r.error = "未配置 API Key";
            return r;
        }
        if (TextUtils.isEmpty(model)) {
            r.error = "未配置模型名称";
            return r;
        }

        String url = normalizeEndpoint(base);
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);

            JSONArray arr = new JSONArray();
            for (Msg m : messages) {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                if ("tool".equals(m.role)) {
                    o.put("tool_call_id", m.toolCallId == null ? "" : m.toolCallId);
                    o.put("content", m.content == null ? "" : m.content);
                    if (!TextUtils.isEmpty(m.toolName)) o.put("name", m.toolName);
                } else if ("assistant".equals(m.role)
                        && !TextUtils.isEmpty(m.toolCallsJson)) {
                    // 工具调用轮的 content 通常为空，严格服务端要求 null 而非空串
                    o.put("content", TextUtils.isEmpty(m.content) ? JSONObject.NULL : m.content);
                    o.put("tool_calls", new JSONArray(m.toolCallsJson));
                } else {
                    o.put("content", m.content == null ? "" : m.content);
                }
                arr.put(o);
            }
            body.put("messages", arr);

            if (tools != null && !tools.isEmpty()) {
                JSONArray toolArr = new JSONArray();
                for (ToolDef t : tools) {
                    JSONObject fn = new JSONObject();
                    fn.put("name", t.name);
                    fn.put("description", t.description);
                    if (!TextUtils.isEmpty(t.parametersJson)) {
                        fn.put("parameters", new JSONObject(t.parametersJson));
                    } else {
                        JSONObject empty = new JSONObject();
                        empty.put("type", "object");
                        empty.put("properties", new JSONObject());
                        fn.put("parameters", empty);
                    }
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("type", "function");
                    wrapper.put("function", fn);
                    toolArr.put(wrapper);
                }
                body.put("tools", toolArr);
                body.put("tool_choice", "auto");
            }

            body.put("temperature", AiConfigManager.getTemperature(context));
            int maxTokens = maxTokensOverride > 0 ? maxTokensOverride
                    : AiConfigManager.getMaxTokens(context);
            body.put("max_tokens", maxTokens);
            // 显式声明非流式，避免部分中转默认走 SSE 导致这里解析到空
            body.put("stream", false);

            // 部分模型（推理型 / o 系列 / 强制 temperature=1 的中转）不接受自定义 temperature，
            // 传了会直接 400。这里做成开关：默认只在允许时才带。
            boolean sendTemperature = AiConfigManager.isSendTemperature(context);
            if (!sendTemperature) {
                body.remove("temperature");
            }

            final String requestBody0 = body.toString();
            AiLog.i("ai-req", "POST " + url + "\nmodel=" + model
                    + " max_tokens=" + maxTokens
                    + " temperature=" + (sendTemperature ? String.valueOf(AiConfigManager.getTemperature(context)) : "不发送")
                    + " tools=" + (tools == null ? 0 : tools.size())
                    + " messages=" + messages.size()
                    + " bodyBytes=" + requestBody0.getBytes("UTF-8").length
                    + "\nbody=" + AiLog.clip(requestBody0, 1200));

            OkHttpClient client = buildClient(AiConfigManager.getTimeoutSeconds(context));
            Response response = post(client, url, key, requestBody0);
            String resp = response.body() != null ? response.body().string() : "";
            int code = response.code();
            response.close();

            // 温度被拒：去掉 temperature 原样重试，并把「不发 temperature」记进配置，避免每次都白跑一次
            if (!response.isSuccessful() && looksLikeTemperatureError(resp)) {
                AiLog.i("ai-req", "temperature 被服务端拒绝，去掉后重试，并记住该选择");
                body.remove("temperature");
                AiConfigManager.setSendTemperature(context, false);
                final String requestBody1 = body.toString();
                AiLog.i("ai-req", "POST(重试, 不带 temperature) " + url
                        + "\nbody=" + AiLog.clip(requestBody1, 1200));
                Response retry = post(client, url, key, requestBody1);
                resp = retry.body() != null ? retry.body().string() : "";
                code = retry.code();
                retry.close();
            }

            AiLog.i("ai-resp", "HTTP " + code + " bytes=" + resp.length()
                    + "\n" + AiLog.clip(resp, 1500));
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code + " " + brief(resp);
                return r;
            }
            return parseResponse(resp);
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
    }

    /** 发送一次 POST /chat/completions */
    private static Response post(OkHttpClient client, String url, String key, String body)
            throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON_TYPE, body))
                .build();
        return client.newCall(request).execute();
    }

    /** 判断错误体是不是「temperature 不被接受」这一类 */
    private static boolean looksLikeTemperatureError(String resp) {
        if (TextUtils.isEmpty(resp)) return false;
        String lower = resp.toLowerCase();
        return lower.contains("temperature");
    }

    private static Result parseResponse(String resp) {
        Result r = new Result();
        try {
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                JSONObject err = json.optJSONObject("error");
                r.error = err != null ? err.optString("message", resp) : resp;
                return r;
            }
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null) {
                r.promptTokens = usage.optInt("prompt_tokens", 0);
                r.completionTokens = usage.optInt("completion_tokens", 0);
            }
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.error = "返回内容为空: " + brief(resp);
                return r;
            }
            JSONObject choice = choices.getJSONObject(0);
            r.finishReason = choice.optString("finish_reason", "");
            JSONObject msg = choice.optJSONObject("message");
            if (msg != null) {
                r.content = msg.optString("content", "");
                if (msg.isNull("content")) r.content = "";
                // 推理型模型把思考写在 reasoning_content，正式回答在 content；
                // 若只有思考没有回答，把思考当作内容兜底，避免用户看到一片空白
                r.reasoningContent = msg.optString("reasoning_content", "");
                if (TextUtils.isEmpty(r.content) && !TextUtils.isEmpty(r.reasoningContent)) {
                    r.content = r.reasoningContent;
                }
                JSONArray tc = msg.optJSONArray("tool_calls");
                if (tc != null && tc.length() > 0) r.toolCalls = tc;
            }
            r.success = true;
            return r;
        } catch (Exception e) {
            r.error = "解析失败: " + e.getMessage();
            return r;
        }
    }

    /** 把 base URL 拼成完整的 chat/completions 端点，兼容用户填 /v1 或直接填完整地址 */
    public static String normalizeEndpoint(String base) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) b = "https://api.openai.com/v1";
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/chat/completions";
    }

    /** 拼出模型列表端点 /models */
    public static String normalizeModelsEndpoint(String base) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) b = "https://api.openai.com/v1";
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        if (b.endsWith("/v1")) return b + "/models";
        return b + "/models";
    }

    /**
     * 拉取服务端可用模型列表（OpenAI 兼容的 GET /models）。
     * 接口不支持时返回带 error 的结果，调用方自行兜底。
     */
    public static Result listModels(Context context) {
        Result r = new Result();
        String key = AiConfigManager.getApiKey(context);
        if (TextUtils.isEmpty(key)) {
            r.error = "未配置 API Key";
            return r;
        }
        String url = normalizeModelsEndpoint(AiConfigManager.getBaseUrl(context));
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            OkHttpClient client = buildClient(Math.max(30, AiConfigManager.getTimeoutSeconds(context)));
            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    r.error = "HTTP " + response.code() + " " + brief(resp);
                    return r;
                }
                return parseModelList(resp);
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
    }

    /** 解析 /models 返回，把模型 id 列表塞进 Result 的 models 字段 */
    private static Result parseModelList(String resp) {
        Result r = new Result();
        try {
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                JSONObject err = json.optJSONObject("error");
                r.error = err != null ? err.optString("message", resp) : resp;
                return r;
            }
            List<String> ids = new ArrayList<>();
            JSONArray data = json.optJSONArray("data");
            if (data == null) data = json.optJSONArray("models");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o == null) {
                        // 少数服务端直接返回字符串数组
                        String s = data.optString(i, "");
                        if (!TextUtils.isEmpty(s)) ids.add(s);
                        continue;
                    }
                    String id = o.optString("id", "");
                    if (TextUtils.isEmpty(id)) id = o.optString("name", "");
                    if (!TextUtils.isEmpty(id)) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                r.error = "接口未返回模型列表: " + brief(resp);
                return r;
            }
            java.util.Collections.sort(ids);
            r.models = ids;
            r.success = true;
            return r;
        } catch (Exception e) {
            r.error = "解析失败: " + e.getMessage();
            return r;
        }
    }

    /** 阻塞式单轮对话（不带工具），供自动回复等场景使用 */
    public static String simpleChat(Context context, String systemPrompt, String userPrompt) {
        List<Msg> msgs = new ArrayList<>();
        if (!TextUtils.isEmpty(systemPrompt)) msgs.add(Msg.system(systemPrompt));
        msgs.add(Msg.user(userPrompt == null ? "" : userPrompt));
        Result r = chat(context, msgs, null);
        if (!r.success) return null;
        return r.content;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }

    /** 供外部复用的论坛会话 HTTP 实例 */
    public static HttpClient forum() {
        return HttpClient.getInstance();
    }
}