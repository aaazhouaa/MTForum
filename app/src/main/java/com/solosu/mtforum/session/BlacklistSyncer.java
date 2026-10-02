package com.solosu.mtforum.session;

import android.content.Context;

import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端黑名单同步:拉取 Discuz blacklist 页解析 uid 列表。
 * 对齐油猴脚本:home.php?mod=space&do=friend&view=blacklist&mobile=2
 * 解析 li.b_t 内 a[href*=uid=N&do=profile] / subop=delete 链接。
 */
public class BlacklistSyncer {
    public static final String LIST_URL = "home.php?mod=space&do=friend&view=blacklist&mobile=2";

    /** 同步回调 */
    public interface Callback {
        void onSynced(int count, String error);
    }

    /** 后台同步(仅当超过 7 天未同步时) */
    public static void syncIfNeeded(final Context c, final Callback cb) {
        if (!BlacklistManager.needsServerSync(c) && !hasBadNames(c)) {
            if (cb != null) cb.onSynced(BlacklistManager.getServerList(c).size(), null);
            return;
        }
        new java.lang.Thread(() -> {
            String err = null;
            List<BlacklistManager.Entry> entries = new ArrayList<>();
            try {
                String html = HttpClient.getInstance().get("https://bbs.binmt.cc/" + LIST_URL);
                entries = parseBlacklistHtml(html);
                BlacklistManager.saveServerList(c, entries);
            } catch (Exception e) {
                err = e.getMessage() == null ? "网络错误" : e.getMessage();
            }
            final List<BlacklistManager.Entry> fe_ = entries;
            final String fe = err;
            if (cb != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> cb.onSynced(fe_.size(), fe));
            }
        }).start();
    }

    /** 解析 blacklist 页 HTML(build51 重写):
     * 主路 p.tit > a[do=profile] 取真实用户名; 操作按钮("加好友"等)不再误当用户名;
     * 末路名字留空, 由 hasBadNames 触发重同步自愈。 */
    static List<BlacklistManager.Entry> parseBlacklistHtml(String html) {
        List<BlacklistManager.Entry> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Document doc = Jsoup.parse(html);
        for (Element li : doc.select("li")) {
            String cls = li.className();
            if (cls == null || !cls.contains("b_t")) continue;
            String uid = null, user = "";
            // 主路: p.tit 内 do=profile 链接 -> 真实用户名
            for (Element p : li.select("p")) {
                String pcls = p.className();
                if (pcls == null || !pcls.contains("tit")) continue;
                for (Element a : p.select("a")) {
                    String href = a.attr("href");
                    if (href.contains("do=profile")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href);
                        if (m.find()) { uid = m.group(1); user = a.text().trim(); break; }
                    }
                }
                if (uid != null) break;
            }
            // 次路: li 内任意 do=profile 链接带文本
            if (uid == null) {
                for (Element a : li.select("a")) {
                    String href = a.attr("href");
                    if (href.contains("do=profile")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href);
                        if (m.find()) { uid = m.group(1); user = a.text().trim(); break; }
                    }
                }
            }
            // 末路: 任意 uid 链接, 名字留空(hasBadNames 触发下次重同步自愈)
            if (uid == null) {
                for (Element a : li.select("a")) {
                    String href = a.attr("href");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href);
                    if (m.find()) { uid = m.group(1); break; }
                }
            }
            if (uid != null && !containsUid(out, uid)) out.add(new BlacklistManager.Entry(uid, user, 0, "server"));
        }
        // 兜底: 整页 do=profile 链接的 uid + 可见链接文本(.. 匹配两侧引号)
        if (out.isEmpty()) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("href=..[^>]*uid=([0-9]+)&amp;do=profile[^>]*>([^<]*)<");
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                String u = m.group(1);
                String n = m.group(2).trim();
                if (!containsUid(out, u)) out.add(new BlacklistManager.Entry(u, n, 0, "server"));
            }
        }
        return out;
    }

    /** build51: 已存 server 镜像含坏名字(空名/操作按钮文字)时返回 true, syncIfNeeded 据此强制重同步自愈 */
    static boolean hasBadNames(Context c) {
        List<BlacklistManager.Entry> list = BlacklistManager.getServerList(c);
        for (BlacklistManager.Entry e : list) {
            String n = e.user;
            if (n == null || n.trim().isEmpty()) return true;
            String t = n.trim();
            if (t.contains("加好友") || t.contains("关注") || t.contains("打招呼")
                    || t.contains("发消息") || t.contains("解除黑名单") || t.contains("移出")) return true;
        }
        return false;
    }

    private static boolean containsUid(List<BlacklistManager.Entry> list, String uid) {
        for (BlacklistManager.Entry e : list) if (e.uid.equals(uid)) return true;
        return false;
    }
}
