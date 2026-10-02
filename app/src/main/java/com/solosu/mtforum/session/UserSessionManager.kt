package com.solosu.mtforum.session

import android.content.Context
import android.text.TextUtils

/**
 * 全局登录会话管理器
 * 采用单例模式，基于 SharedPreferences 持久化存储用户登录信息。
 * 登录时保存用户信息，退出登录时清除。
 * 全局通过 getInstance() 获取实例并查询登录状态。
 */
object UserSessionManager {

    private const val PREF_NAME = "sqapp_user_session"
    private const val KEY_USERNAME = "session_username"
    private const val KEY_UID = "session_uid"
    private const val KEY_AVATAR_URL = "session_avatar_url"
    private const val KEY_LEVEL = "session_level"
    private const val KEY_LAST_LOGIN = "session_last_login"
    private const val KEY_SIGN_IN_DATE = "sign_in_date"

    @JvmStatic
    fun getInstance(): UserSessionManager = this

    // ==================== 基础存取 ====================

    /**
     * 保存用户登录信息
     */
    fun saveLoginInfo(context: Context, info: Map<String, String>) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()
        if (info.containsKey("username")) {
            editor.putString(KEY_USERNAME, info.get("username"))
        }
        if (info.containsKey("uid")) {
            editor.putString(KEY_UID, info.get("uid"))
        }
        if (info.containsKey("avatarUrl")) {
            editor.putString(KEY_AVATAR_URL, info.get("avatarUrl"))
        }
        if (info.containsKey("level")) {
            editor.putString(KEY_LEVEL, info.get("level"))
        }
        editor.putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
        editor.apply()
    }

    /**
     * 清除所有用户登录信息（退出登录时调用）
     */
    fun clearLoginInfo(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_UID)
                .remove(KEY_AVATAR_URL)
                .remove(KEY_LEVEL)
                .remove(KEY_LAST_LOGIN)
                .apply()
    }

    /**
     * 获取所有已存储的用户登录信息
     */
    fun getLoginInfo(context: Context): MutableMap<String, String> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val info = HashMap<String, String>()
        val username = sp.getString(KEY_USERNAME, null)
        val uid = sp.getString(KEY_UID, null)
        val avatarUrl = sp.getString(KEY_AVATAR_URL, null)
        val level = sp.getString(KEY_LEVEL, null)
        val lastLogin = sp.getLong(KEY_LAST_LOGIN, -1)

        if (username != null) info.put("username", username)
        if (uid != null) info.put("uid", uid)
        if (avatarUrl != null) info.put("avatarUrl", avatarUrl)
        if (level != null) info.put("level", level)
        if (lastLogin > 0) info.put("lastLoginTime", lastLogin.toString())

        return info
    }

    // ==================== 快捷查询 ====================

    /**
     * 判断是否为已登录状态
     * 检查是否存在有效的 username 和 uid
     */
    fun isLoggedIn(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val username = sp.getString(KEY_USERNAME, null)
        val uid = sp.getString(KEY_UID, null)
        return username != null && !username.isEmpty()
                && uid != null && !uid.isEmpty() && !uid.equals("0")
    }

    fun getUsername(context: Context): String? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_USERNAME, null)
    }

    fun getUid(context: Context): String? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_UID, null)
    }

    fun getAvatarUrl(context: Context): String? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_AVATAR_URL, null)
    }

    fun getLevel(context: Context): String? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LEVEL, null)
    }

    // ==================== 签到状态持久化 ====================

    /**
     * 保存今日签到日期（格式：yyyy-MM-dd）
     */
    fun saveSignInDate(context: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_SIGN_IN_DATE, today).apply()
    }

    /**
     * 判断今天是否已签到
     */
    fun isSignedInToday(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedDate = sp.getString(KEY_SIGN_IN_DATE, "")
        if (TextUtils.isEmpty(savedDate)) return false
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
        return today.equals(savedDate)
    }

    /**
     * 清除签到状态（退出登录时调用）
     */
    fun clearSignInDate(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().remove(KEY_SIGN_IN_DATE).apply()
    }
}
