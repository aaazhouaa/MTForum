package com.solosu.mtforum.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * AI 配置存储。
 * 统一管理大模型接入参数、自动回复参数、功能开关。
 */
public final class AiConfigManager {

    private static final String PREF = "mtforum_ai_config";

    // ---- 模型接入 ----
    private static final String KEY_BASE_URL = "ai_base_url";
    private static final String KEY_API_KEY = "ai_api_key";
    private static final String KEY_MODEL = "ai_model";
    private static final String KEY_SYSTEM_PROMPT = "ai_system_prompt";
    private static final String KEY_TEMPERATURE = "ai_temperature";
    /** 是否在请求里发送 temperature（部分模型/中转只接受 temperature=1，发自定义值会 400） */
    private static final String KEY_SEND_TEMPERATURE = "ai_send_temperature";
    private static final String KEY_MAX_TOKENS = "ai_max_tokens";
    private static final String KEY_TIMEOUT = "ai_timeout";

    // ---- 自动回复 ----
    private static final String KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled";
    private static final String KEY_AUTO_REPLY_SILENT = "auto_reply_silent";
    private static final String KEY_AUTO_REPLY_INTERVAL = "auto_reply_interval";
    private static final String KEY_AUTO_REPLY_PROMPT = "auto_reply_prompt";
    private static final String KEY_AUTO_REPLY_MAX_PER_RUN = "auto_reply_max_per_run";
    private static final String KEY_AUTO_REPLY_MIN_LENGTH = "auto_reply_min_length";
    private static final String KEY_AUTO_REPLY_DRY_RUN = "auto_reply_dry_run";
    private static final String KEY_AUTO_REPLY_ONLY_OWN = "auto_reply_only_own";
    /** 解锁隐藏内容：对含「回复可见」的帖子自动回复以解锁 */
    private static final String KEY_AUTO_UNLOCK_HIDDEN = "auto_unlock_hidden";
    /** 进入帖子详情页时自动解锁 */
    private static final String KEY_UNLOCK_ON_VIEW = "unlock_on_view";
    /** 自定义解锁回复模板（空则用内置模板池） */
    private static final String KEY_UNLOCK_REPLY_TEMPLATE = "unlock_reply_template";

    // ---- 自动签到 ----
    private static final String KEY_AUTO_SIGN_IN = "auto_sign_in_enabled";

    // ---- 对话记忆（用于 AI 学习历史） ----
    private static final String KEY_MEMORY = "ai_memory_json";

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 MT 论坛（bbs.binmt.cc）的资深技术助手，专注于 Android 逆向、"
            + "APK 修改、Smali、脱壳、脱敏、协议分析等话题。"
            + "回答要简洁、务实、直给结论，不要客套。"
            + "涉及技术问题请给出可执行的具体步骤或代码片段。";

    private static final String DEFAULT_REPLY_PROMPT =
            "你是 MT 论坛的活跃成员，正在浏览论坛帖子并参与讨论。\n"
            + "请根据帖子标题和正文内容，写一条自然、有信息量的回复。\n"
            + "要求：\n"
            + "1. 直接针对帖子内容，不要泛泛而谈\n"
            + "2. 语气像真实论坛用户，不要过度客套、不要用「楼主」开头\n"
            + "3. 长度控制在 15 到 80 字之间\n"
            + "4. 不要输出任何解释、前缀、引号或 markdown 标记，只输出回复正文\n"
            + "5. 如果帖子内容无法理解或涉及违规话题，只输出：[[SKIP]]";

    private AiConfigManager() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ==================== 模型接入 ====================

    public static String getBaseUrl(Context c) {
        String v = sp(c).getString(KEY_BASE_URL, "https://api.openai.com/v1");
        return TextUtils.isEmpty(v) ? "https://api.openai.com/v1" : v.trim();
    }

    public static void setBaseUrl(Context c, String v) {
        sp(c).edit().putString(KEY_BASE_URL, v == null ? "" : v.trim()).apply();
    }

    public static String getApiKey(Context c) {
        return sp(c).getString(KEY_API_KEY, "");
    }

    public static void setApiKey(Context c, String v) {
        sp(c).edit().putString(KEY_API_KEY, v == null ? "" : v.trim()).apply();
    }

    public static String getModel(Context c) {
        String v = sp(c).getString(KEY_MODEL, "gpt-4o-mini");
        return TextUtils.isEmpty(v) ? "gpt-4o-mini" : v.trim();
    }

    public static void setModel(Context c, String v) {
        sp(c).edit().putString(KEY_MODEL, v == null ? "" : v.trim()).apply();
    }

    public static String getSystemPrompt(Context c) {
        String v = sp(c).getString(KEY_SYSTEM_PROMPT, null);
        return TextUtils.isEmpty(v) ? DEFAULT_SYSTEM_PROMPT : v;
    }

    public static void setSystemPrompt(Context c, String v) {
        sp(c).edit().putString(KEY_SYSTEM_PROMPT, v == null ? "" : v).apply();
    }

    public static float getTemperature(Context c) {
        return sp(c).getFloat(KEY_TEMPERATURE, 0.7f);
    }

    public static void setTemperature(Context c, float v) {
        sp(c).edit().putFloat(KEY_TEMPERATURE, v).apply();
    }

    /**
     * 是否在请求体里发送 temperature。
     * 部分模型（如固定 temperature=1 系列 / 某些中转）只接受 temperature=1，
     * 发送自定义值会直接 400，此时应关掉。
     */
    public static boolean isSendTemperature(Context c) {
        return sp(c).getBoolean(KEY_SEND_TEMPERATURE, true);
    }

    public static void setSendTemperature(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SEND_TEMPERATURE, v).apply();
    }

    public static int getMaxTokens(Context c) {
        return sp(c).getInt(KEY_MAX_TOKENS, 8192);
    }

    public static void setMaxTokens(Context c, int v) {
        sp(c).edit().putInt(KEY_MAX_TOKENS, v).apply();
    }

    public static int getTimeoutSeconds(Context c) {
        return sp(c).getInt(KEY_TIMEOUT, 60);
    }

    public static void setTimeoutSeconds(Context c, int v) {
        sp(c).edit().putInt(KEY_TIMEOUT, v).apply();
    }

    /** 配置是否可用（有地址 + 有 key + 有模型） */
    public static boolean isConfigured(Context c) {
        return !TextUtils.isEmpty(getBaseUrl(c))
                && !TextUtils.isEmpty(getApiKey(c))
                && !TextUtils.isEmpty(getModel(c));
    }

    public static String defaultReplyPrompt() {
        return DEFAULT_REPLY_PROMPT;
    }

    public static String defaultSystemPrompt() {
        return DEFAULT_SYSTEM_PROMPT;
    }

    // ==================== 自动回复 ====================

    public static boolean isAutoReplyEnabled(Context c) {
        return sp(c).getBoolean(KEY_AUTO_REPLY_ENABLED, false);
    }

    public static void setAutoReplyEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_ENABLED, v).apply();
    }

    /** 静默模式：不弹通知、不在界面提示，后台悄悄回复 */
    public static boolean isSilentMode(Context c) {
        return sp(c).getBoolean(KEY_AUTO_REPLY_SILENT, true);
    }

    public static void setSilentMode(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_SILENT, v).apply();
    }

    /** 轮询间隔，秒 */
    public static int getReplyInterval(Context c) {
        return Math.max(30, sp(c).getInt(KEY_AUTO_REPLY_INTERVAL, 300));
    }

    public static void setReplyInterval(Context c, int v) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_INTERVAL, Math.max(30, v)).apply();
    }

    public static String getReplyPrompt(Context c) {
        String v = sp(c).getString(KEY_AUTO_REPLY_PROMPT, null);
        return TextUtils.isEmpty(v) ? DEFAULT_REPLY_PROMPT : v;
    }

    public static void setReplyPrompt(Context c, String v) {
        sp(c).edit().putString(KEY_AUTO_REPLY_PROMPT, v == null ? "" : v).apply();
    }

    /** 单轮最多回复几条，防止刷屏 */
    public static int getMaxReplyPerRun(Context c) {
        return Math.max(1, sp(c).getInt(KEY_AUTO_REPLY_MAX_PER_RUN, 3));
    }

    public static void setMaxReplyPerRun(Context c, int v) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_MAX_PER_RUN, Math.max(1, v)).apply();
    }

    public static int getMinReplyLength(Context c) {
        return Math.max(2, sp(c).getInt(KEY_AUTO_REPLY_MIN_LENGTH, 8));
    }

    public static void setMinReplyLength(Context c, int v) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_MIN_LENGTH, Math.max(2, v)).apply();
    }

    /** 演练模式：只生成不发送，用于调试提示词 */
    public static boolean isDryRun(Context c) {
        return sp(c).getBoolean(KEY_AUTO_REPLY_DRY_RUN, false);
    }

    public static void setDryRun(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_DRY_RUN, v).apply();
    }

    private static final String KEY_AUTO_HIDE_NAV = "auto_hide_nav";

    /** build72: 底部导航栏滚动自动隐藏(默认开启,与历史行为一致) */
    public static boolean isAutoHideNav(Context c) {
        return sp(c).getBoolean(KEY_AUTO_HIDE_NAV, true);
    }

    public static void setAutoHideNav(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_HIDE_NAV, v).apply();
    }

    private static final String KEY_MIGRATE_DRY_RUN_OFF = "migrate_dry_run_off_v12";

    /**
     * 一次性迁移：v1.2 起「演练模式」默认关闭。
     * 旧版本里如果曾被打开过，自动回复会一直"只生成不发送"，
     * 表现为「开关开着却什么都没发生」。这里强制关一次，之后用户可自由开关。
     */
    public static void migrateDefaults(Context c) {
        try {
            android.content.SharedPreferences p = sp(c);
            if (p.getBoolean(KEY_MIGRATE_DRY_RUN_OFF, false)) return;
            boolean was = p.getBoolean(KEY_AUTO_REPLY_DRY_RUN, false);
            p.edit().putBoolean(KEY_AUTO_REPLY_DRY_RUN, false)
                    .putBoolean(KEY_MIGRATE_DRY_RUN_OFF, true).apply();
            if (was) {
                AiLog.i("config", "迁移：已关闭旧的「演练模式」，自动回复恢复真实发送");
            }
        } catch (Throwable ignore) {
        }
    }

    /** 只回复自己帖子里的评论（更安全，避免在别人帖子下乱说话） */
    public static boolean isOnlyReplyOwnThreads(Context c) {
        return sp(c).getBoolean(KEY_AUTO_REPLY_ONLY_OWN, true);
    }

    public static void setOnlyReplyOwnThreads(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_ONLY_OWN, v).apply();
    }

    /**
     * 自动回复模式：true = 解锁隐藏内容（对含「回复可见」的帖子自动回复）；
     * false = 老的模式（回复自己帖子下的新评论）。
     */
    public static boolean isUnlockMode(Context c) {
        return sp(c).getBoolean(KEY_AUTO_UNLOCK_HIDDEN, true);
    }

    public static void setUnlockMode(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_UNLOCK_HIDDEN, v).apply();
    }

    /** 进入帖子详情页时自动回复解锁隐藏内容 */
    public static boolean isUnlockOnView(Context c) {
        return sp(c).getBoolean(KEY_UNLOCK_ON_VIEW, true);
    }

    public static void setUnlockOnView(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_UNLOCK_ON_VIEW, v).apply();
    }

    /** 自定义解锁回复模板：{title} 会被替换为帖子标题关键词，空则用内置模板池 */
    public static String getUnlockReplyTemplate(Context c) {
        return sp(c).getString(KEY_UNLOCK_REPLY_TEMPLATE, "");
    }

    public static void setUnlockReplyTemplate(Context c, String v) {
        sp(c).edit().putString(KEY_UNLOCK_REPLY_TEMPLATE, v == null ? "" : v.trim()).apply();
    }

    // ==================== 自动签到 ====================

    public static boolean isAutoSignInEnabled(Context c) {
        // 直接复用既有的 AutoSignInManager 状态，避免两套开关互相打架
        return com.solosu.mtforum.session.AutoSignInManager.isEnabled(c);
    }

    public static void setAutoSignInEnabled(Context c, boolean v) {
        com.solosu.mtforum.session.AutoSignInManager.setEnabled(c, v);
        sp(c).edit().putBoolean(KEY_AUTO_SIGN_IN, v).apply();
    }

    // ==================== 记忆 ====================

    public static String getMemory(Context c) {
        return sp(c).getString(KEY_MEMORY, "[]");
    }

    public static void setMemory(Context c, String json) {
        sp(c).edit().putString(KEY_MEMORY, json == null ? "[]" : json).apply();
    }
}
