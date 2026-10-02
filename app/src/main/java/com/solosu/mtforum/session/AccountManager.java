package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多账号管理器：保存多份登录 cookie 快照，一键切换。
 * 数据结构（SharedPreferences "sqapp_accounts"）：
 *   accounts: JSON 数组，每项 { uid, username, avatar, level, cookies }
 *   active_uid: 当前激活账号的 uid
 * 切换 = 用快照整体替换 HttpClient 内存 cookieStore + 覆写 sqapp_cookies 持久层。
 */
public class AccountManager {

    public static class Account {
        public String uid;
        public String username;
        public String avatar;
        public String level;
        public String cookies; // 该账号完整 cookie JSON 数组字符串

        @Override public String toString() { return username + "(" + uid + ")"; }
    }

    private static final String PREF = "sqapp_accounts";
    private static final String KEY_LIST = "accounts";
    private static final String KEY_ACTIVE = "active_uid";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 把当前登录态保存为新账号（uid 已存在则覆盖更新） */
    public static void saveCurrent(Context c, String uid, String username, String avatar, String level) {
        if (TextUtils.isEmpty(uid)) return;
        try {
            String cookiesJson = readCurrentCookiesJson(c);
            if (TextUtils.isEmpty(cookiesJson)) return;

            Account acc = new Account();
            acc.uid = uid;
            acc.username = TextUtils.isEmpty(username) ? ("UID_" + uid) : username;
            acc.avatar = avatar;
            acc.level = level;
            acc.cookies = cookiesJson;

            List<Account> list = list(c);
            List<Account> out = new ArrayList<>();
            boolean replaced = false;
            for (Account a : list) {
                if (uid.equals(a.uid)) { out.add(acc); replaced = true; }
                else out.add(a);
            }
            if (!replaced) out.add(acc);

            JSONArray arr = new JSONArray();
            for (Account a : out) {
                JSONObject o = new JSONObject();
                o.put("uid", a.uid);
                o.put("username", a.username);
                o.put("avatar", a.avatar);
                o.put("level", a.level);
                o.put("cookies", a.cookies);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_LIST, arr.toString())
                    .putString(KEY_ACTIVE, uid)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** 账号列表 */
    public static List<Account> list(Context c) {
        List<Account> out = new ArrayList<>();
        try {
            String json = prefs(c).getString(KEY_LIST, null);
            if (TextUtils.isEmpty(json)) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Account a = new Account();
                a.uid = o.optString("uid");
                a.username = o.optString("username");
                a.avatar = o.optString("avatar");
                a.level = o.optString("level");
                a.cookies = o.optString("cookies");
                if (!TextUtils.isEmpty(a.uid)) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 当前激活账号 uid */
    public static String activeUid(Context c) {
        return prefs(c).getString(KEY_ACTIVE, null);
    }

    /** 切换账号：整体替换内存 cookieStore + 覆写 sqapp_cookies + 更新 UserSessionManager */
    public static boolean switchTo(Context c, String uid) {
        try {
            Account target = null;
            for (Account a : list(c)) {
                if (uid.equals(a.uid)) { target = a; break; }
            }
            if (target == null || TextUtils.isEmpty(target.cookies)) return false;

            // ① 覆写 cookie 持久层，再让 HttpClient 从持久层恢复（清内存→写盘→读盘）
            prefs(c).edit().putString(KEY_ACTIVE, uid).apply();
            SharedPreferences cookiePrefs = c.getApplicationContext()
                    .getSharedPreferences("sqapp_cookies", Context.MODE_PRIVATE);
            cookiePrefs.edit().putString("cookies_json", target.cookies).apply();
            HttpClient.getInstance().clearCookies();
            HttpClient.getInstance().restoreCookieStore(c.getApplicationContext());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除账号 */
    public static void remove(Context c, String uid) {
        try {
            List<Account> list = list(c);
            List<Account> out = new ArrayList<>();
            for (Account a : list) {
                if (!uid.equals(a.uid)) out.add(a);
            }
            JSONArray arr = new JSONArray();
            for (Account a : out) {
                JSONObject o = new JSONObject();
                o.put("uid", a.uid);
                o.put("username", a.username);
                o.put("avatar", a.avatar);
                o.put("level", a.level);
                o.put("cookies", a.cookies);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
            if (uid.equals(activeUid(c))) {
                prefs(c).edit().remove(KEY_ACTIVE).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取当前 sqapp_cookies 的原始 JSON（保存快照用） */
    private static String readCurrentCookiesJson(Context c) {
        try {
            SharedPreferences sp = c.getApplicationContext()
                    .getSharedPreferences("sqapp_cookies", Context.MODE_PRIVATE);
            String json = sp.getString("cookies_json", null);
            return json != null && !json.isEmpty() ? json : null;
        } catch (Exception e) {
            return null;
        }
    }
}