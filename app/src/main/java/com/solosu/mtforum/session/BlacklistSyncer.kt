package com.solosu.mtforum.session

import android.content.Context

import com.solosu.mtforum.network.HttpClient

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.util.ArrayList

/**
 * 服务端黑名单同步:拉取 Discuz blacklist 页解析 uid 列表。
 * 对齐油猴脚本:home.php?mod=space&do=friend&view=blacklist&mobile=2
 * 解析 li.b_t 内 a[href*=uid=N&do=profile] / subop=delete 链接。
 */
object BlacklistSyncer {
    const val LIST_URL = "home.php?mod=space&do=friend&view=blacklist&mobile=2"

    /** 同步回调 */
    interface Callback {
        fun onSynced(count: Int, error: String?)
    }

    /** 后台同步(仅当超过 7 天未同步时) */
    @JvmStatic
    fun syncIfNeeded(c: Context, cb: Callback?) {
        if (!BlacklistManager.needsServerSync(c) && !hasBadNames(c)) {
            if (cb != null) cb.onSynced(BlacklistManager.getServerList(c).size, null)
            return
        }
        java.lang.Thread {
            var err: String? = null
            var entries: MutableList<BlacklistManager.Entry> = ArrayList()
            try {
                val html = HttpClient.getInstance().get("https://bbs.binmt.cc/" + LIST_URL)
                entries = parseBlacklistHtml(html)
                BlacklistManager.saveServerList(c, entries)
            } catch (e: Exception) {
                err = e.message ?: "网络错误"
            }
            val fe_ = entries
            val fe = err
            if (cb != null) {
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                h.post { cb.onSynced(fe_.size, fe) }
            }
        }.start()
    }

    /** 解析 blacklist 页 HTML(build51 重写):
     * 主路 p.tit > a[do=profile] 取真实用户名; 操作按钮("加好友"等)不再误当用户名;
     * 末路名字留空, 由 hasBadNames 触发重同步自愈。 */
    @JvmStatic
    fun parseBlacklistHtml(html: String?): MutableList<BlacklistManager.Entry> {
        val out = ArrayList<BlacklistManager.Entry>()
        if (html == null || html.isEmpty()) return out
        val doc: Document = Jsoup.parse(html)
        for (li in doc.select("li")) {
            val cls: String? = li.className()
            if (cls == null || !cls.contains("b_t")) continue
            var uid: String? = null
            var user = ""
            // 主路: p.tit 内 do=profile 链接 -> 真实用户名
            for (p in li.select("p")) {
                val pcls: String? = p.className()
                if (pcls == null || !pcls.contains("tit")) continue
                for (a in p.select("a")) {
                    val href = a.attr("href")
                    if (href.contains("do=profile")) {
                        val m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href)
                        if (m.find()) {
                            uid = m.group(1)
                            user = a.text().trim()
                            break
                        }
                    }
                }
                if (uid != null) break
            }
            // 次路: li 内任意 do=profile 链接带文本
            if (uid == null) {
                for (a in li.select("a")) {
                    val href = a.attr("href")
                    if (href.contains("do=profile")) {
                        val m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href)
                        if (m.find()) {
                            uid = m.group(1)
                            user = a.text().trim()
                            break
                        }
                    }
                }
            }
            // 末路: 任意 uid 链接, 名字留空(hasBadNames 触发下次重同步自愈)
            if (uid == null) {
                for (a in li.select("a")) {
                    val href = a.attr("href")
                    val m = java.util.regex.Pattern.compile("uid=([0-9]+)").matcher(href)
                    if (m.find()) {
                        uid = m.group(1)
                        break
                    }
                }
            }
            if (uid != null && !containsUid(out, uid)) out.add(BlacklistManager.Entry(uid, user, 0, "server"))
        }
        // 兜底: 整页 do=profile 链接的 uid + 可见链接文本(.. 匹配两侧引号)
        if (out.isEmpty()) {
            val p = java.util.regex.Pattern.compile("href=..[^>]*uid=([0-9]+)&amp;do=profile[^>]*>([^<]*)<")
            val m = p.matcher(html)
            while (m.find()) {
                val u = m.group(1)
                val n = m.group(2).trim()
                if (!containsUid(out, u)) out.add(BlacklistManager.Entry(u, n, 0, "server"))
            }
        }
        return out
    }

    /** build51: 已存 server 镜像含坏名字(空名/操作按钮文字)时返回 true, syncIfNeeded 据此强制重同步自愈 */
    @JvmStatic
    fun hasBadNames(c: Context): Boolean {
        val list = BlacklistManager.getServerList(c)
        for (e in list) {
            val n: String? = e.user
            if (n == null || n.trim().isEmpty()) return true
            val t = n.trim()
            if (t.contains("加好友") || t.contains("关注") || t.contains("打招呼")
                    || t.contains("发消息") || t.contains("解除黑名单") || t.contains("移出")) return true
        }
        return false
    }

    private fun containsUid(list: MutableList<BlacklistManager.Entry>, uid: String): Boolean {
        for (e in list) if (e.uid.equals(uid)) return true
        return false
    }
}
