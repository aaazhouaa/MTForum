package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 个人小黑屋:三源黑名单合并(对齐网页油猴脚本原理)
 * ① 个人黑名单(本机 SharedPreferences,uid+username+time)
 * ② 系统「我的屏蔽」(网页端 localStorage shieldList,Discuz 屏蔽规则 option=uid/user)
 * ③ 服务端黑名单(Discuz home.php?mod=space&do=friend&view=blacklist,需登录)
 * 过滤:列表/回帖按 uid 命中即隐藏;主题帖作者命中隐藏整个帖子。
 */
public class BlacklistManager {
    public static final String PREF = "sqapp_blacklist";
    private static final String KEY_LOCAL = "local_list";      // [{uid,user,time}]
    private static final String KEY_SERVER = "server_list";    // [uid,...]
    private static final String KEY_SERVER_TS = "server_ts";   // 上次同步时间戳
    private static final long SERVER_SYNC_INTERVAL = 7 * 24 * 3600 * 1000L; // 7天

    /** 条目:uid+用户名+拉黑时间+来源 */
    public static class Entry {
        public String uid;
        public String user;
        public long time;
        public String source; // local / server
        public Entry(String uid, String user, long time, String source) {
            this.uid = uid == null ? "" : uid; this.user = user == null ? "" : user;
            this.time = time; this.source = source == null ? "local" : source;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ═══ 个人黑名单(本机) ═══

    public static List<Entry> getLocalList(Context c) {
        return parseEntries(prefs(c).getString(KEY_LOCAL, "[]"), "local");
    }

    public static boolean addLocal(Context c, String uid, String user) {
        if (uid == null || uid.isEmpty()) return false;
        List<Entry> list = getLocalList(c);
        for (Entry e : list) {
            if (uid.equals(e.uid)) return true; // 已在
        }
        list.add(new Entry(uid, user, System.currentTimeMillis(), "local"));
        return prefs(c).edit().putString(KEY_LOCAL, toJSON(list)).commit();
    }

    public static boolean removeLocal(Context c, String uid) {
        List<Entry> list = getLocalList(c);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uid != null && uid.equals(list.get(i).uid)) { list.remove(i); removed = true; }
        }
        if (removed) prefs(c).edit().putString(KEY_LOCAL, toJSON(list)).commit();
        return removed;
        }

    public static boolean clearLocal(Context c) {
        return prefs(c).edit().putString(KEY_LOCAL, "[]").commit();
    }

    // ═══ 服务端黑名单(Discuz blacklist 页) ═══

    /** 判断是否需要同步:从未同步过或距上次超过 7 天 */
    public static boolean needsServerSync(Context c) {
        long ts = prefs(c).getLong(KEY_SERVER_TS, 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > SERVER_SYNC_INTERVAL;
    }

    public static List<Entry> getServerList(Context c) {
        List<Entry> out = new ArrayList<>();
        JSONArray a = parseArray(prefs(c).getString(KEY_SERVER, "[]"));
        for (int i = 0; i < a.length(); i++) {
            String uid, user;
            Object o = a.opt(i);
            if (o instanceof JSONObject) {
                JSONObject jo = (JSONObject) o;
                uid = jo.optString("uid", "");
                user = jo.optString("user", "");
            } else {
                uid = a.optString(i, "");
                user = "";
            }
            if (uid != null && !uid.isEmpty()) out.add(new Entry(uid, user, 0, "server"));
        }
        return out;
    }

    /** 本地移除服务端条目(下次 7 天同步会重新拉取) */
    public static boolean removeServer(Context c, String uid) {
        List<Entry> list = getServerList(c);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uid != null && uid.equals(list.get(i).uid)) { list.remove(i); removed = true; }
        }
        if (removed) {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                try { a.put(new JSONObject().put("uid", e.uid).put("user", e.user == null ? "" : e.user)); }
                catch (Exception ignore) {}
            }
            prefs(c).edit().putString(KEY_SERVER, a.toString()).commit();
        }
        return removed;
    }

    public static void saveServerList(Context c, List<Entry> entries) {
        JSONArray a = new JSONArray();
        for (Entry e : entries) {
            if (e.uid == null || e.uid.isEmpty()) continue;
            try { a.put(new JSONObject().put("uid", e.uid).put("user", e.user == null ? "" : e.user)); }
            catch (Exception ignore) {}
        }
        prefs(c).edit().putString(KEY_SERVER, a.toString()).putLong(KEY_SERVER_TS, System.currentTimeMillis()).commit();
    }

    // ═══ 合并集合(过滤用) ═══

    /** 三源合并 uid 集合:个人+服务端(系统「我的屏蔽」为网页端 localStorage,App 无法读取,跳过) */
    public static Set<String> uidSet(Context c) {
        Set<String> set = new HashSet<>();
        for (Entry e : getLocalList(c)) if (!e.uid.isEmpty()) set.add(e.uid);
        for (Entry e : getServerList(c)) if (!e.uid.isEmpty()) set.add(e.uid);
        return set;
    }

    public static boolean isBlack(Context c, String uid) {
        if (uid == null || uid.isEmpty()) return false;
        return uidSet(c).contains(uid);
    }

    public static boolean isEmpty(Context c) {
        return getLocalList(c).isEmpty() && getServerList(c).isEmpty();
    }

    public static int size(Context c) {
        return getLocalList(c).size() + getServerList(c).size();
    }

    // ═══ 序列化 ═══

    private static String toJSON(List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) {
            try { a.put(new JSONObject().put("uid", e.uid).put("user", e.user).put("time", e.time)); }
            catch (Exception ignore) {}
        }
        return a.toString();
    }

    private static JSONArray parseArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static List<Entry> parseEntries(String raw, String source) {
        List<Entry> out = new ArrayList<>();
        JSONArray a = parseArray(raw);
    for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String uid = o.optString("uid", "");
            String user = o.optString("user", "");
            long time = o.optLong("time", 0);
            if (uid != null && !uid.isEmpty()) out.add(new Entry(uid, user, time, source));
        }
        return out;
    }
}
