package com.solosu.mtforum.ai

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

/**
 * AI 配置存储。
 * 统一管理大模型接入参数、自动回复参数、功能开关。
 */
object AiConfigManager {

    private const val PREF = "mtforum_ai_config"

    // ---- 模型接入 ----
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_SYSTEM_PROMPT = "ai_system_prompt"
    private const val KEY_TEMPERATURE = "ai_temperature"

    /** 是否在请求里发送 temperature（部分模型/中转只接受 temperature=1，发自定义值会 400） */
    private const val KEY_SEND_TEMPERATURE = "ai_send_temperature"
    private const val KEY_MAX_TOKENS = "ai_max_tokens"
    private const val KEY_TIMEOUT = "ai_timeout"

    // ---- 自动回复 ----
    private const val KEY_AUTO_REPLY_ENABLED = "auto_reply_enabled"
    private const val KEY_AUTO_REPLY_SILENT = "auto_reply_silent"
    private const val KEY_AUTO_REPLY_INTERVAL = "auto_reply_interval"
    private const val KEY_AUTO_REPLY_PROMPT = "auto_reply_prompt"
    private const val KEY_AUTO_REPLY_MAX_PER_RUN = "auto_reply_max_per_run"
    private const val KEY_AUTO_REPLY_MIN_LENGTH = "auto_reply_min_length"
    private const val KEY_AUTO_REPLY_DRY_RUN = "auto_reply_dry_run"
    private const val KEY_AUTO_REPLY_ONLY_OWN = "auto_reply_only_own"

    /** 解锁隐藏内容：对含「回复可见」的帖子自动回复以解锁 */
    private const val KEY_AUTO_UNLOCK_HIDDEN = "auto_unlock_hidden"

    /** 进入帖子详情页时自动解锁 */
    private const val KEY_UNLOCK_ON_VIEW = "unlock_on_view"

    /** 自定义解锁回复模板（空则用内置模板池） */
    private const val KEY_UNLOCK_REPLY_TEMPLATE = "unlock_reply_template"

    // ---- 自动签到 ----
    private const val KEY_AUTO_SIGN_IN = "auto_sign_in_enabled"

    // ---- 对话记忆（用于 AI 学习历史） ----
    private const val KEY_MEMORY = "ai_memory_json"

    private const val DEFAULT_SYSTEM_PROMPT =
            "你是 MT 论坛（bbs.binmt.cc）的资深技术助手，专注于 Android 逆向、" +
                    "APK 修改、Smali、脱壳、脱敏、协议分析等话题。" +
                    "回答要简洁、务实、直给结论，不要客套。" +
                    "涉及技术问题请给出可执行的具体步骤或代码片段。"

    private const val DEFAULT_REPLY_PROMPT =
            "你是 MT 论坛的活跃成员，正在浏览论坛帖子并参与讨论。\n" +
                    "请根据帖子标题和正文内容，写一条自然、有信息量的回复。\n" +
                    "要求：\n" +
                    "1. 直接针对帖子内容，不要泛泛而谈\n" +
                    "2. 语气像真实论坛用户，不要过度客套、不要用「楼主」开头\n" +
                    "3. 长度控制在 15 到 80 字之间\n" +
                    "4. 不要输出任何解释、前缀、引号或 markdown 标记，只输出回复正文\n" +
                    "5. 如果帖子内容无法理解或涉及违规话题，只输出：[[SKIP]]"

    private fun sp(c: Context): SharedPreferences {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ==================== 模型接入 ====================

    @JvmStatic
    fun getBaseUrl(c: Context): String {
        val v = sp(c).getString(KEY_BASE_URL, "https://api.openai.com/v1")
        return if (TextUtils.isEmpty(v)) "https://api.openai.com/v1" else v!!.trim()
    }

    @JvmStatic
    fun setBaseUrl(c: Context, v: String?) {
        sp(c).edit().putString(KEY_BASE_URL, if (v == null) "" else v.trim()).apply()
    }

    @JvmStatic
    fun getApiKey(c: Context): String {
        return sp(c).getString(KEY_API_KEY, "") ?: ""
    }

    @JvmStatic
    fun setApiKey(c: Context, v: String?) {
        sp(c).edit().putString(KEY_API_KEY, if (v == null) "" else v.trim()).apply()
    }

    @JvmStatic
    fun getModel(c: Context): String {
        val v = sp(c).getString(KEY_MODEL, "gpt-4o-mini")
        return if (TextUtils.isEmpty(v)) "gpt-4o-mini" else v!!.trim()
    }

    @JvmStatic
    fun setModel(c: Context, v: String?) {
        sp(c).edit().putString(KEY_MODEL, if (v == null) "" else v.trim()).apply()
    }

    @JvmStatic
    fun getSystemPrompt(c: Context): String {
        val v = sp(c).getString(KEY_SYSTEM_PROMPT, null)
        return if (TextUtils.isEmpty(v)) DEFAULT_SYSTEM_PROMPT else v!!
    }

    @JvmStatic
    fun setSystemPrompt(c: Context, v: String?) {
        sp(c).edit().putString(KEY_SYSTEM_PROMPT, if (v == null) "" else v).apply()
    }

    @JvmStatic
    fun getTemperature(c: Context): Float {
        return sp(c).getFloat(KEY_TEMPERATURE, 0.7f)
    }

    @JvmStatic
    fun setTemperature(c: Context, v: Float) {
        sp(c).edit().putFloat(KEY_TEMPERATURE, v).apply()
    }

    /**
     * 是否在请求体里发送 temperature。
     * 部分模型（如固定 temperature=1 系列 / 某些中转）只接受 temperature=1，
     * 发送自定义值会直接 400，此时应关掉。
     */
    @JvmStatic
    fun isSendTemperature(c: Context): Boolean {
        return sp(c).getBoolean(KEY_SEND_TEMPERATURE, true)
    }

    @JvmStatic
    fun setSendTemperature(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_SEND_TEMPERATURE, v).apply()
    }

    @JvmStatic
    fun getMaxTokens(c: Context): Int {
        return sp(c).getInt(KEY_MAX_TOKENS, 8192)
    }

    @JvmStatic
    fun setMaxTokens(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_MAX_TOKENS, v).apply()
    }

    @JvmStatic
    fun getTimeoutSeconds(c: Context): Int {
        return sp(c).getInt(KEY_TIMEOUT, 60)
    }

    @JvmStatic
    fun setTimeoutSeconds(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_TIMEOUT, v).apply()
    }

    /** 配置是否可用（有地址 + 有 key + 有模型） */
    @JvmStatic
    fun isConfigured(c: Context): Boolean {
        return !TextUtils.isEmpty(getBaseUrl(c)) &&
                !TextUtils.isEmpty(getApiKey(c)) &&
                !TextUtils.isEmpty(getModel(c))
    }

    @JvmStatic
    fun defaultReplyPrompt(): String {
        return DEFAULT_REPLY_PROMPT
    }

    @JvmStatic
    fun defaultSystemPrompt(): String {
        return DEFAULT_SYSTEM_PROMPT
    }

    // ==================== 自动回复 ====================

    @JvmStatic
    fun isAutoReplyEnabled(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_REPLY_ENABLED, false)
    }

    @JvmStatic
    fun setAutoReplyEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_ENABLED, v).apply()
    }

    /** 静默模式：不弹通知、不在界面提示，后台悄悄回复 */
    @JvmStatic
    fun isSilentMode(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_REPLY_SILENT, true)
    }

    @JvmStatic
    fun setSilentMode(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_SILENT, v).apply()
    }

    /** 轮询间隔，秒 */
    @JvmStatic
    fun getReplyInterval(c: Context): Int {
        return Math.max(30, sp(c).getInt(KEY_AUTO_REPLY_INTERVAL, 300))
    }

    @JvmStatic
    fun setReplyInterval(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_INTERVAL, Math.max(30, v)).apply()
    }

    @JvmStatic
    fun getReplyPrompt(c: Context): String {
        val v = sp(c).getString(KEY_AUTO_REPLY_PROMPT, null)
        return if (TextUtils.isEmpty(v)) DEFAULT_REPLY_PROMPT else v!!
    }

    @JvmStatic
    fun setReplyPrompt(c: Context, v: String?) {
        sp(c).edit().putString(KEY_AUTO_REPLY_PROMPT, if (v == null) "" else v).apply()
    }

    /** 单轮最多回复几条，防止刷屏 */
    @JvmStatic
    fun getMaxReplyPerRun(c: Context): Int {
        return Math.max(1, sp(c).getInt(KEY_AUTO_REPLY_MAX_PER_RUN, 3))
    }

    @JvmStatic
    fun setMaxReplyPerRun(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_MAX_PER_RUN, Math.max(1, v)).apply()
    }

    @JvmStatic
    fun getMinReplyLength(c: Context): Int {
        return Math.max(2, sp(c).getInt(KEY_AUTO_REPLY_MIN_LENGTH, 8))
    }

    @JvmStatic
    fun setMinReplyLength(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_AUTO_REPLY_MIN_LENGTH, Math.max(2, v)).apply()
    }

    /** 演练模式：只生成不发送，用于调试提示词 */
    @JvmStatic
    fun isDryRun(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_REPLY_DRY_RUN, false)
    }

    @JvmStatic
    fun setDryRun(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_DRY_RUN, v).apply()
    }

    private const val KEY_AUTO_HIDE_NAV = "auto_hide_nav"

    /** build72: 底部导航栏滚动自动隐藏(默认开启,与历史行为一致) */
    @JvmStatic
    fun isAutoHideNav(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_HIDE_NAV, true)
    }

    @JvmStatic
    fun setAutoHideNav(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_HIDE_NAV, v).apply()
    }

    private const val KEY_MIGRATE_DRY_RUN_OFF = "migrate_dry_run_off_v12"

    /**
     * 一次性迁移：v1.2 起「演练模式」默认关闭。
     * 旧版本里如果曾被打开过，自动回复会一直"只生成不发送"，
     * 表现为「开关开着却什么都没发生」。这里强制关一次，之后用户可自由开关。
     */
    @JvmStatic
    fun migrateDefaults(c: Context) {
        try {
            val p = sp(c)
            if (p.getBoolean(KEY_MIGRATE_DRY_RUN_OFF, false)) return
            val was = p.getBoolean(KEY_AUTO_REPLY_DRY_RUN, false)
            p.edit().putBoolean(KEY_AUTO_REPLY_DRY_RUN, false)
                    .putBoolean(KEY_MIGRATE_DRY_RUN_OFF, true).apply()
            if (was) {
                AiLog.i("config", "迁移：已关闭旧的「演练模式」，自动回复恢复真实发送")
            }
        } catch (ignore: Throwable) {
        }
    }

    /** 只回复自己帖子里的评论（更安全，避免在别人帖子下乱说话） */
    @JvmStatic
    fun isOnlyReplyOwnThreads(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_REPLY_ONLY_OWN, true)
    }

    @JvmStatic
    fun setOnlyReplyOwnThreads(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_REPLY_ONLY_OWN, v).apply()
    }

    /**
     * 自动回复模式：true = 解锁隐藏内容（对含「回复可见」的帖子自动回复）；
     * false = 老的模式（回复自己帖子下的新评论）。
     */
    @JvmStatic
    fun isUnlockMode(c: Context): Boolean {
        return sp(c).getBoolean(KEY_AUTO_UNLOCK_HIDDEN, true)
    }

    @JvmStatic
    fun setUnlockMode(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_AUTO_UNLOCK_HIDDEN, v).apply()
    }

    /** 进入帖子详情页时自动回复解锁隐藏内容 */
    @JvmStatic
    fun isUnlockOnView(c: Context): Boolean {
        return sp(c).getBoolean(KEY_UNLOCK_ON_VIEW, true)
    }

    @JvmStatic
    fun setUnlockOnView(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_UNLOCK_ON_VIEW, v).apply()
    }

    /** 自定义解锁回复模板：{title} 会被替换为帖子标题关键词，空则用内置模板池 */
    @JvmStatic
    fun getUnlockReplyTemplate(c: Context): String {
        return sp(c).getString(KEY_UNLOCK_REPLY_TEMPLATE, "") ?: ""
    }

    @JvmStatic
    fun setUnlockReplyTemplate(c: Context, v: String?) {
        sp(c).edit().putString(KEY_UNLOCK_REPLY_TEMPLATE, if (v == null) "" else v.trim()).apply()
    }

    // ==================== 自动签到 ====================

    @JvmStatic
    fun isAutoSignInEnabled(c: Context): Boolean {
        // 直接复用既有的 AutoSignInManager 状态，避免两套开关互相打架
        return com.solosu.mtforum.session.AutoSignInManager.isEnabled(c)
    }

    @JvmStatic
    fun setAutoSignInEnabled(c: Context, v: Boolean) {
        com.solosu.mtforum.session.AutoSignInManager.setEnabled(c, v)
        sp(c).edit().putBoolean(KEY_AUTO_SIGN_IN, v).apply()
    }

    // ==================== 记忆 ====================

    @JvmStatic
    fun getMemory(c: Context): String {
        return sp(c).getString(KEY_MEMORY, "[]") ?: "[]"
    }

    @JvmStatic
    fun setMemory(c: Context, json: String?) {
        sp(c).edit().putString(KEY_MEMORY, if (json == null) "[]" else json).apply()
    }
}
