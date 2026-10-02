package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.HashSet;
import java.util.Set;

/**
 * 全局关注状态管理器。
 *
 * 关注状态以当前登录 UID 隔离保存，避免切换账号后串号；网络成功后才写入本地，
 * 所有页面通过这里读取同一份状态。
 */
public final class FollowStateManager {
    private static final String PREF_NAME = "sqapp_follow_state";
    private static final String KEY_FOLLOWING_PREFIX = "following_";
    private static final String KEY_KNOWN_PREFIX = "known_";

    private FollowStateManager() {}

    private static String accountKey(Context context) {
        String uid = UserSessionManager.getInstance().getUid(context.getApplicationContext());
        return TextUtils.isEmpty(uid) ? "guest" : uid;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static Set<String> readSet(Context context, String key) {
        return new HashSet<>(prefs(context).getStringSet(key, new HashSet<>()));
    }

    public static synchronized boolean hasLocalState(Context context, String targetUid) {
        if (TextUtils.isEmpty(targetUid)) return false;
        return readSet(context, KEY_KNOWN_PREFIX + accountKey(context)).contains(targetUid);
    }

    public static synchronized boolean isFollowed(Context context, String targetUid) {
        if (TextUtils.isEmpty(targetUid)) return false;
        return readSet(context, KEY_FOLLOWING_PREFIX + accountKey(context)).contains(targetUid);
    }
    /**
     * 关注状态解析规则：
     * 1. 服务端明确返回“已关注”时立即同步为 true，顺便修复旧版本缓存的错误 false；
     * 2. 服务端返回 false 且本地有记录时，保留本地成功操作结果；
     * 3. 两边都没有明确的已关注证据时，不把 false 写入 known_，避免污染缓存。
     */
    public static synchronized boolean resolve(Context context, String targetUid, boolean serverState) {
        if (TextUtils.isEmpty(targetUid)) return serverState;

        // “已关注”是服务端的明确正向证据，优先用于修复旧缓存或跨页面状态。
        if (serverState) {
            setState(context, targetUid, true);
            return true;
        }

        // 服务端 false 可能只是页面未提供状态；只有此前存在明确本地状态时才读取本地。
        if (hasLocalState(context, targetUid)) return isFollowed(context, targetUid);

        // 未知状态：返回 false 给当前 UI，但不缓存为“明确未关注”。
        return false;
    }


    public static synchronized void setState(Context context, String targetUid, boolean followed) {
        if (TextUtils.isEmpty(targetUid)) return;
        String account = accountKey(context);
        String followKey = KEY_FOLLOWING_PREFIX + account;
        String knownKey = KEY_KNOWN_PREFIX + account;
        Set<String> following = readSet(context, followKey);
        Set<String> known = readSet(context, knownKey);
        if (followed) following.add(targetUid); else following.remove(targetUid);
        known.add(targetUid);
        prefs(context).edit().putStringSet(followKey, following).putStringSet(knownKey, known).apply();
    }

    public static synchronized int getFollowedCount(Context context) {
        return readSet(context, KEY_FOLLOWING_PREFIX + accountKey(context)).size();
    }

    public static boolean isLoggedIn(Context context) {
        return HttpClient.getInstance().isLoggedIn()
                && UserSessionManager.getInstance().isLoggedIn(context.getApplicationContext());
    }
    /**
     * Read the complete following list from the server.
     * @return a UID set, or null when the response cannot be trusted
     */
    public static Set<String> queryServerFollowingUids(Context context) {
        if (context == null) return null;
        try {
            HttpClient client = HttpClient.getInstance();
            client.syncFromCookieManager();
            if (!client.isLoggedIn()) return null;

            String currentUid = UserSessionManager.getInstance().getUid(context.getApplicationContext());
            if (TextUtils.isEmpty(currentUid)) return null;

            Set<String> result = new HashSet<>();
            for (int page = 1; page <= 50; page++) {
                String url = HttpClient.BASE_URL
                        + "home.php?mod=follow&do=following&uid=" + currentUid
                        + "&mobile=2&page=" + page
                        + "&_ts=" + System.currentTimeMillis();
                String html = client.get(url);
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)
                        || !ForumParser.isFollowingListPage(html)) {
                    return null;
                }
                result.addAll(ForumParser.extractFollowingUids(html));
                if (!ForumParser.hasUserListPageAfter(html, page)) break;
            }
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Directly confirm whether the current account follows a target user.
     * @return 1 followed, 0 not followed, -1 unknown
     */
    public static int queryServerFollowState(Context context, String targetUid) {
        if (context == null || TextUtils.isEmpty(targetUid)) return -1;
        Set<String> following = queryServerFollowingUids(context);
        if (following == null) return -1;
        return following.contains(targetUid) ? 1 : 0;
    }



    /**
     * 调用 Comiis/Discuz! 实际使用的关注接口。必须在后台线程调用。
     *
     * 关注：home.php?mod=spacecp&ac=follow&op=add&hash={formhash}&fuid={uid}&inajax=1
     * 取消：home.php?mod=spacecp&ac=follow&op=del&fuid={uid}&inajax=1
     */
    public static boolean syncFollow(Context context, String targetUid, boolean follow) {
        if (TextUtils.isEmpty(targetUid)) return false;
        try {
            HttpClient client = HttpClient.getInstance();

            // 原生登录可能来自 WebView。只有 OkHttp 没有登录 Cookie 时才同步，
            // 避免用空的 WebView Cookie 覆盖已经有效的 OkHttp 会话。
            if (!client.isLoggedIn()) {
                client.syncFromCookieManager();
            }
            if (!client.isLoggedIn()) return false;

            String currentUid = UserSessionManager.getInstance()
                    .getUid(context.getApplicationContext());
            String pageUrl = HttpClient.BASE_URL + "home.php?mod=space&do=profile"
                    + (TextUtils.isEmpty(currentUid) ? "" : "&uid=" + currentUid)
                    + "&mobile=2";
            String pageHtml = client.get(pageUrl);
            String formhash = ForumParser.parseFormhash(pageHtml);

            // 移动空间页没有 hash 时，用移动版论坛首页补取；再用桌面版兜底。
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(
                        client.get(HttpClient.BASE_URL + "forum.php?mobile=2"));
            }
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(
                        client.getDesktop(HttpClient.BASE_URL + "forum.php"));
            }
            if (TextUtils.isEmpty(formhash)) return false;

            String url;
            if (follow) {
                // 注意参数名必须是 hash，目标用户参数必须是 fuid。
                url = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=follow&op=add"
                        + "&hash=" + formhash + "&fuid=" + targetUid + "&inajax=1";
            } else {
                url = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=follow&op=del"
                        + "&fuid=" + targetUid + "&inajax=1";
            }

            String result = client.get(url);
            if (ForumParser.isLoginPage(result)) return false;

            String text = result == null ? "" : result.toLowerCase();
            boolean operationTypeReturned = follow
                    ? text.contains("'type':'add'") || text.contains("\"type\":\"add\"")
                    || text.contains("type=add")
                    : text.contains("'type':'del'") || text.contains("\"type\":\"del\"")
                    || text.contains("type=del");

            // Discuz 在重复操作时也可能直接返回提示文字；这两类结果应视为目标状态已达成。
            boolean alreadyInTargetState = follow
                    ? text.contains("已关注") || text.contains("已经关注")
                    : text.contains("已取消关注") || text.contains("未关注")
                    || text.contains("不存在");

            boolean failed = text.contains("formhash错误") || text.contains("请先登录")
                    || text.contains("没有权限") || text.contains("操作失败")
                    || text.contains("发生错误") || text.contains("失败")
                    || text.contains("error");
            if (failed && !alreadyInTargetState) return false;
            if (!operationTypeReturned && !alreadyInTargetState) return false;

            setState(context, targetUid, follow);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
