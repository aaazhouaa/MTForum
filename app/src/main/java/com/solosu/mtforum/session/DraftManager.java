package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 发帖草稿箱: SharedPreferences + JSON 持久化, 最多 20 条, 按 time 倒序。
 * PostActivity 退出时自动保存(onPause), 发布成功后自动删除对应草稿。
 */
public class DraftManager {
    public static final String PREF = "sqapp_drafts";
    private static final String KEY = "draft_list";
    private static final int MAX = 20;

    /** 草稿条目 */
    public static class Entry {
        public long id;
        public String title;
        public String content;
        public String fid;
        public String forumName;
        public boolean anonymous;
        public long time;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optLong("id", 0);
                e.title = o.optString("title", "");
                e.content = o.optString("content", "");
                e.fid = o.optString("fid", "");
                e.forumName = o.optString("forum", "");
                e.anonymous = o.optBoolean("anon", false);
                e.time = o.optLong("time", 0);
                if (e.id > 0) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 全部草稿, 最新在前 */
    public static List<Entry> list(Context c) {
        List<Entry> l = parse(prefs(c).getString(KEY, "[]"));
        java.util.Collections.sort(l, (a, b) -> Long.compare(b.time, a.time));
        return l;
    }

    /** 最新一条(无草稿返回 null) */
    public static Entry latest(Context c) {
        List<Entry> l = list(c);
        return l.isEmpty() ? null : l.get(0);
    }

    public static Entry get(Context c, long id) {
        for (Entry e : list(c)) if (e.id == id) return e;
        return null;
    }

    /** 保存或更新草稿(id>0 且存在则覆盖刷新), 返回草稿 id */
    public static long saveDraft(Context c, long id, String title, String content,
                                 String fid, String forumName, boolean anonymous) {
        List<Entry> l = parse(prefs(c).getString(KEY, "[]"));
        Entry e = null;
        if (id > 0) {
            for (Entry x : l) if (x.id == id) { e = x; break; }
        }
        if (e == null) { e = new Entry(); e.id = System.currentTimeMillis(); l.add(e); }
        e.title = title == null ? "" : title;
        e.content = content == null ? "" : content;
        e.fid = fid == null ? "" : fid;
        e.forumName = forumName == null ? "" : forumName;
        e.anonymous = anonymous;
        e.time = System.currentTimeMillis();
        // 超上限丢最旧
        while (l.size() > MAX) {
            Entry oldest = null;
            for (Entry x : l) if (oldest == null || x.time < oldest.time) oldest = x;
            if (oldest == null) break;
            l.remove(oldest);
        }
        prefs(c).edit().putString(KEY, toJSON(l)).commit();
        return e.id;
    }

    public static void delete(Context c, long id) {
        if (id <= 0) return;
        List<Entry> l = parse(prefs(c).getString(KEY, "[]"));
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id == id) { l.remove(i); break; }
        }
        prefs(c).edit().putString(KEY, toJSON(l)).commit();
    }

    public static void clear(Context c) {
        prefs(c).edit().putString(KEY, "[]").commit();
    }

    private static String toJSON(List<Entry> l) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry x : l) {
                JSONObject o = new JSONObject();
                o.put("id", x.id);
                o.put("title", x.title);
                o.put("content", x.content);
                o.put("fid", x.fid);
                o.put("forum", x.forumName);
                o.put("anon", x.anonymous);
                o.put("time", x.time);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }
}