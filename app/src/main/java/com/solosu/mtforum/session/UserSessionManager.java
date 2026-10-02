package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局登录会话管理器
 * 采用单例模式，基于 SharedPreferences 持久化存储用户登录信息。
 * 登录时保存用户信息，退出登录时清除。
 * 全局通过 getInstance() 获取实例并查询登录状态。
 */
public class UserSessionManager {

    private static volatile UserSessionManager instance;
    private static final String PREF_NAME = "sqapp_user_session";
    private static final String KEY_USERNAME = "session_username";
    private static final String KEY_UID = "session_uid";
    private static final String KEY_AVATAR_URL = "session_avatar_url";
    private static final String KEY_LEVEL = "session_level";
    private static final String KEY_LAST_LOGIN = "session_last_login";
    private static final String KEY_SIGN_IN_DATE = "sign_in_date";

    private UserSessionManager() {
    }

    public static UserSessionManager getInstance() {
        if (instance == null) {
            synchronized (UserSessionManager.class) {
                if (instance == null) {
                    instance = new UserSessionManager();
                }
            }
        }
        return instance;
    }

    // ==================== 基础存取 ====================

    /**
     * 保存用户登录信息
     */
    public void saveLoginInfo(Context context, Map<String, String> info) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        if (info.containsKey("username")) {
            editor.putString(KEY_USERNAME, info.get("username"));
        }
        if (info.containsKey("uid")) {
            editor.putString(KEY_UID, info.get("uid"));
        }
        if (info.containsKey("avatarUrl")) {
            editor.putString(KEY_AVATAR_URL, info.get("avatarUrl"));
        }
        if (info.containsKey("level")) {
            editor.putString(KEY_LEVEL, info.get("level"));
        }
        editor.putLong(KEY_LAST_LOGIN, System.currentTimeMillis());
        editor.apply();
    }

    /**
     * 清除所有用户登录信息（退出登录时调用）
     */
    public void clearLoginInfo(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_UID)
                .remove(KEY_AVATAR_URL)
                .remove(KEY_LEVEL)
                .remove(KEY_LAST_LOGIN)
                .apply();
    }

    /**
     * 获取所有已存储的用户登录信息
     */
    public Map<String, String> getLoginInfo(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Map<String, String> info = new HashMap<>();
        String username = sp.getString(KEY_USERNAME, null);
        String uid = sp.getString(KEY_UID, null);
        String avatarUrl = sp.getString(KEY_AVATAR_URL, null);
        String level = sp.getString(KEY_LEVEL, null);
        long lastLogin = sp.getLong(KEY_LAST_LOGIN, -1);

        if (username != null) info.put("username", username);
        if (uid != null) info.put("uid", uid);
        if (avatarUrl != null) info.put("avatarUrl", avatarUrl);
        if (level != null) info.put("level", level);
        if (lastLogin > 0) info.put("lastLoginTime", String.valueOf(lastLogin));

        return info;
    }

    // ==================== 快捷查询 ====================

    /**
     * 判断是否为已登录状态
     * 检查是否存在有效的 username 和 uid
     */
    public boolean isLoggedIn(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String username = sp.getString(KEY_USERNAME, null);
        String uid = sp.getString(KEY_UID, null);
        return username != null && !username.isEmpty()
                && uid != null && !uid.isEmpty() && !uid.equals("0");
    }

    public String getUsername(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_USERNAME, null);
    }

    public String getUid(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_UID, null);
    }

    public String getAvatarUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_AVATAR_URL, null);
    }

    public String getLevel(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_LEVEL, null);
    }

    // ==================== 签到状态持久化 ====================

    /**
     * 保存今日签到日期（格式：yyyy-MM-dd）
     */
    public void saveSignInDate(Context context) {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date());
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SIGN_IN_DATE, today).apply();
    }

    /**
     * 判断今天是否已签到
     */
    public boolean isSignedInToday(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedDate = sp.getString(KEY_SIGN_IN_DATE, "");
        if (TextUtils.isEmpty(savedDate)) return false;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date());
        return today.equals(savedDate);
    }

    /**
     * 清除签到状态（退出登录时调用）
     */
    public void clearSignInDate(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_SIGN_IN_DATE).apply();
    }
}