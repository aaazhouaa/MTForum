package com.solosu.mtforum.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.model.ChatMessage;
import com.solosu.mtforum.model.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 消息角标快照管理器。
 *
 * Comiis 页面不提供可靠的 unread 标记，因此不再依赖 Message.isRead()。
 * 角标含义为：当前服务器列表相对于用户上次进入该分类时，新增的内容数量。
 *
 * PM 使用“会话内消息总数”计算差值；粉丝使用新增 UID 数量；
 * 其它通知使用通知内容指纹计算新增条目数量。
 */
public final class NoticeBadgeManager {
    private static final String PREF_NAME = "notice_badge_snapshot_v2";
    private static final String KEY_BASELINE_PREFIX = "baseline_";
    private static final String KEY_CURRENT_PREFIX = "current_";
    private static final String KEY_PENDING_PREFIX = "pending_";

    private NoticeBadgeManager() {}

    /** build83: 分类被查看时回调. MainActivity 注册后可在本地即时清零红点, 无需等 60s 轮询, 零额外请求 */
    public interface OnViewedListener { void onViewed(String viewType); }

    private static volatile OnViewedListener sViewedListener;

    public static void setOnViewedListener(OnViewedListener listener) { sViewedListener = listener; }

    private static void fireViewed(String viewType) {
        OnViewedListener listener = sViewedListener;
        if (listener == null) return;
        try { listener.onViewed(viewType); } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 根据当前 HTML 建立规范化快照。
     * PM 会继续请求每个会话的详情页，统计当前可见聊天消息总数。
     */
    public static String buildSnapshot(String viewType, String html, HttpClient client) {
        if (TextUtils.isEmpty(html)) return "";

        List<Message> items;
        if ("pm".equals(viewType)) items = ForumParser.parsePmList(html);
        else if ("follower".equals(viewType)) items = ForumParser.parseFollowerList(html);
        else items = ForumParser.parseNoticeList(html);
        if (items == null) items = new ArrayList<>();

        if ("pm".equals(viewType)) return buildPmSnapshot(items, client);
        if ("follower".equals(viewType)) return buildFollowerSnapshot(items);
        return buildNoticeSnapshot(items);
    }

    private static String buildPmSnapshot(List<Message> items, HttpClient client) {
        Map<String, Integer> totals = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Message item : items) {
            String uid = firstNonEmpty(item.getAuthorUid(), item.getPmid(), item.getAuthor());
            if (TextUtils.isEmpty(uid)) continue;
            String key = sha256(uid);
            if (!seen.add(key)) continue;

            // 列表页通常只有会话预览，详情页才包含实际消息条数。
            int total = 1;
            if (client != null && !TextUtils.isEmpty(item.getPmid())) {
                try {
                    String url = HttpClient.BASE_URL
                            + "home.php?mod=space&do=pm&subop=view&touid="
                            + item.getPmid() + "&mobile=2&_badge_ts="
                            + System.currentTimeMillis();
                    String detail = client.get(url);
                    List<ChatMessage> chat = ForumParser.parseChatMessages(
                            detail, "", item.getAvatarUrl());
                    if (chat != null && !chat.isEmpty()) {
                        int incoming = 0;
                        for (ChatMessage message : chat) {
                            if (!message.isOutgoing()) incoming++;
                        }
                        // 只统计对方发来的消息；用户自己的回复不应增加未读角标。
                        total = incoming;
                    }
                } catch (Exception ignored) {
                    // 详情请求失败时保留列表中的一条会话消息，避免将该会话丢失。
                }
            }
            totals.put(key, total);
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            result.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return result.toString();
    }

    private static String buildFollowerSnapshot(List<Message> items) {
        Set<String> ids = new HashSet<>();
        for (Message item : items) {
            String uid = firstNonEmpty(item.getAuthorUid(), item.getAuthor(), item.getTitle());
            if (!TextUtils.isEmpty(uid)) ids.add(sha256(uid));
        }
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return join(sorted);
    }

    private static String buildNoticeSnapshot(List<Message> items) {
        Set<String> ids = new HashSet<>();
        for (Message item : items) {
            ids.add(sha256(stableNoticeKey(item)));
        }
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return join(sorted);
    }

    private static String stableNoticeKey(Message item) {
        String id = firstNonEmpty(item.getPmid(), "");
        if (!TextUtils.isEmpty(id)) return "id|" + id;
        return "content|" + firstNonEmpty(item.getAuthorUid(), "") + "|"
                + firstNonEmpty(item.getAuthor(), "") + "|"
                + firstNonEmpty(item.getTitle(), "") + "|"
                + firstNonEmpty(item.getSummary(), "");
    }

    /**
     * 保存当前快照并返回相对于上次查看基线的新增数量。
     * 首次运行只建立基线，返回 0。
     */
    public static synchronized int saveCurrentAndGetNewCount(Context context,
                                                               String viewType,
                                                               String snapshot) {
        if (context == null || TextUtils.isEmpty(viewType)) return 0;
        SharedPreferences p = prefs(context);
        String baselineKey = KEY_BASELINE_PREFIX + viewType;
        String currentKey = KEY_CURRENT_PREFIX + viewType;
        String pendingKey = KEY_PENDING_PREFIX + viewType;
        String oldBaseline = p.getString(baselineKey, null);

        if (p.getBoolean(pendingKey, false) || oldBaseline == null) {
            p.edit().putString(baselineKey, snapshot == null ? "" : snapshot)
                    .putString(currentKey, snapshot == null ? "" : snapshot)
                    .putBoolean(pendingKey, false)
                    .apply();
            return 0;
        }

        int count = calculateNewCount(viewType, oldBaseline, snapshot);
        p.edit().putString(currentKey, snapshot == null ? "" : snapshot).apply();
        return count;
    }

    /** 点击进入某分类时，将该分类当前内容标记为已查看。 */
    public static synchronized void markViewed(Context context, String viewType) {
        if (context == null || TextUtils.isEmpty(viewType)) return;
        SharedPreferences p = prefs(context);
        String current = p.getString(KEY_CURRENT_PREFIX + viewType, null);
        SharedPreferences.Editor editor = p.edit();
        if (current == null) {
            editor.putBoolean(KEY_PENDING_PREFIX + viewType, true);
        } else {
            editor.putString(KEY_BASELINE_PREFIX + viewType, current)
                    .putBoolean(KEY_PENDING_PREFIX + viewType, false);
        }
        editor.apply();
        fireViewed(viewType);
    }

    /** 手动“全部已读”时同步重置所有分类基线。 */
    public static synchronized void markAllViewed(Context context) {
        if (context == null) return;
        String[] types = {"pm", "follower", "mypost", "interactive", "system", "app"};
        for (String type : types) markViewed(context, type);
        fireViewed("*");
    }

    private static int calculateNewCount(String viewType, String baseline, String current) {
        if (TextUtils.isEmpty(current)) return 0;
        if ("pm".equals(viewType)) return calculatePmDelta(baseline, current);

        Set<String> oldSet = parseSet(baseline);
        int count = 0;
        for (String key : parseSet(current)) if (!oldSet.contains(key)) count++;
        return count;
    }

    private static int calculatePmDelta(String baseline, String current) {
        Map<String, Integer> oldMap = parsePmMap(baseline);
        Map<String, Integer> currentMap = parsePmMap(current);
        int count = 0;
        for (Map.Entry<String, Integer> entry : currentMap.entrySet()) {
            int old = oldMap.containsKey(entry.getKey()) ? oldMap.get(entry.getKey()) : 0;
            if (entry.getValue() > old) count += entry.getValue() - old;
        }
        return count;
    }

    private static Set<String> parseSet(String value) {
        Set<String> result = new HashSet<>();
        if (TextUtils.isEmpty(value)) return result;
        for (String item : value.split(";")) {
            if (!TextUtils.isEmpty(item)) result.add(item);
        }
        return result;
    }

    private static Map<String, Integer> parsePmMap(String value) {
        Map<String, Integer> result = new HashMap<>();
        if (TextUtils.isEmpty(value)) return result;
        for (String item : value.split(";")) {
            int index = item.lastIndexOf('=');
            if (index <= 0) continue;
            try {
                result.put(item.substring(0, index), Integer.parseInt(item.substring(index + 1)));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value).append(';');
        return result.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return String.valueOf(value == null ? "" : value.hashCode());
        }
    }
}
