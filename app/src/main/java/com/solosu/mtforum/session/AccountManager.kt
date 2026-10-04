package com.solosu.mtforum.session

import android.content.Context
import android.content.SharedPreferences
import com.solosu.mtforum.network.HttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多账号管理器：保存多份登录 cookie 快照，一键切换。
 *
 * 数据结构（SharedPreferences "sqapp_accounts"）：
 *   accounts: JSON 数组，每项 { uid, username, avatar, level, cookies }
 *   active_uid: 当前激活账号的 uid
 * 切换 = 用快照整体替换 HttpClient 内存 cookieStore + 覆写 sqapp_cookies 持久层。
 */
object AccountManager {

    class Account {
        // @JvmField：Java 侧是直接访问 .uid 字段（非 getUid()），
        // 不加会生成私有字段 + getter，导致 Java 调用点编译失败。
        @JvmField var uid: String? = null
        @JvmField var username: String? = null
        @JvmField var avatar: String? = null
        @JvmField var level: String? = null
        @JvmField var cookies: String? = null // 该账号完整 cookie JSON 数组字符串

        override fun toString(): String = "$username($uid)"
    }

    private const val PREF = "sqapp_accounts"
    private const val KEY_LIST = "accounts"
    private const val KEY_ACTIVE = "active_uid"
    private const val PREF_COOKIES = "sqapp_cookies"
    private const val KEY_COOKIES_JSON = "cookies_json"

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 把当前登录态保存为新账号（uid 已存在则覆盖更新） */
    @JvmStatic
    fun saveCurrent(c: Context, uid: String?, username: String?, avatar: String?, level: String?) {
        if (uid.isNullOrEmpty()) return
        try {
            val cookiesJson = readCurrentCookiesJson(c) ?: return

            val acc = Account().apply {
                this.uid = uid
                this.username = if (username.isNullOrEmpty()) "UID_$uid" else username
                this.avatar = avatar
                this.level = level
                this.cookies = cookiesJson
            }

            val out = ArrayList<Account>()
            var replaced = false
            for (a in list(c)) {
                if (uid == a.uid) {
                    out.add(acc); replaced = true
                } else {
                    out.add(a)
                }
            }
            if (!replaced) out.add(acc)

            val arr = JSONArray()
            for (a in out) {
                arr.put(JSONObject().apply {
                    put("uid", a.uid)
                    put("username", a.username)
                    put("avatar", a.avatar)
                    put("level", a.level)
                    put("cookies", a.cookies)
                })
            }
            prefs(c).edit().putString(KEY_LIST, arr.toString())
                .putString(KEY_ACTIVE, uid)
                .apply()
        } catch (ignored: Exception) {
        }
    }

    /** 账号列表 */
    @JvmStatic
    fun list(c: Context): List<Account> {
        val out = ArrayList<Account>()
        try {
            val json = prefs(c).getString(KEY_LIST, null)
            if (json.isNullOrEmpty()) return out
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = Account().apply {
                    uid = o.optString("uid")
                    username = o.optString("username")
                    avatar = o.optString("avatar")
                    level = o.optString("level")
                    cookies = o.optString("cookies")
                }
                if (!a.uid.isNullOrEmpty()) out.add(a)
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    /** 当前激活账号 uid */
    @JvmStatic
    fun activeUid(c: Context): String? = prefs(c).getString(KEY_ACTIVE, null)

    /** 切换账号：整体替换内存 cookieStore + 覆写 sqapp_cookies + 更新 UserSessionManager */
    @JvmStatic
    fun switchTo(c: Context, uid: String?): Boolean {
        return try {
            val target = list(c).firstOrNull { uid == it.uid } ?: return false
            if (target.cookies.isNullOrEmpty()) return false

            // ① 覆写 cookie 持久层，再让 HttpClient 从持久层恢复（清内存→写盘→读盘）
            prefs(c).edit().putString(KEY_ACTIVE, uid).apply()
            val cookiePrefs = c.applicationContext
                .getSharedPreferences(PREF_COOKIES, Context.MODE_PRIVATE)
            cookiePrefs.edit().putString(KEY_COOKIES_JSON, target.cookies).apply()
            HttpClient.getInstance().clearCookies()
            HttpClient.getInstance().restoreCookieStore(c.applicationContext)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除账号 */
    @JvmStatic
    fun remove(c: Context, uid: String?) {
        try {
            val out = list(c).filter { uid != it.uid }

            val arr = JSONArray()
            for (a in out) {
                arr.put(JSONObject().apply {
                    put("uid", a.uid)
                    put("username", a.username)
                    put("avatar", a.avatar)
                    put("level", a.level)
                    put("cookies", a.cookies)
                })
            }
            prefs(c).edit().putString(KEY_LIST, arr.toString()).apply()
            if (uid == activeUid(c)) {
                prefs(c).edit().remove(KEY_ACTIVE).apply()
            }
        } catch (ignored: Exception) {
        }
    }

    /** 读取当前 sqapp_cookies 的原始 JSON（保存快照用） */
    private fun readCurrentCookiesJson(c: Context): String? {
        return try {
            val sp = c.applicationContext
                .getSharedPreferences(PREF_COOKIES, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_COOKIES_JSON, null)
            if (!json.isNullOrEmpty()) json else null
        } catch (e: Exception) {
            null
        }
    }

    /** 弹出账号切换弹窗：当前账号高亮，点选切换，支持登录新账号 */
    @JvmStatic
    fun showAccountSwitcherDialog(
        activity: androidx.fragment.app.FragmentActivity,
        onAccountChanged: (() -> Unit)? = null
    ) {
        var accounts = list(activity)
        var activeUid = activeUid(activity)
        val curName = UserSessionManager.getInstance().getUsername(activity)
        val curUid = UserSessionManager.getInstance().getUid(activity)
        val curLogged = UserSessionManager.getInstance().isLoggedIn(activity)

        // 若当前登录账号未入库，先补存
        if (curLogged && !android.text.TextUtils.isEmpty(curUid)) {
            saveCurrent(
                activity, curUid, curName,
                UserSessionManager.getInstance().getAvatarUrl(activity),
                UserSessionManager.getInstance().getLevel(activity)
            )
            accounts = list(activity)
            activeUid = activeUid(activity)
        }
        val fAccounts = accounts
        val fActiveUid = activeUid

        val labels = ArrayList<String>()
        for (a in fAccounts) {
            val mark = if (fActiveUid != null && fActiveUid == a.uid) "  [当前]" else ""
            labels.add((a.username ?: "UID_${a.uid}") + mark)
        }
        labels.add("＋ 登录新账号")

        val arr = labels.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("切换账号")
            .setItems(arr) { _, which ->
                if (which == fAccounts.size) {
                    // 登录新账号
                    com.solosu.mtforum.ui.login.LoginBottomSheet.show(activity) {
                        val n = UserSessionManager.getInstance().getUsername(activity)
                        val u = UserSessionManager.getInstance().getUid(activity)
                        if (!android.text.TextUtils.isEmpty(u)) {
                            saveCurrent(
                                activity, u, n,
                                UserSessionManager.getInstance().getAvatarUrl(activity),
                                UserSessionManager.getInstance().getLevel(activity)
                            )
                        }
                        onAccountChanged?.invoke()
                    }
                    return@setItems
                }
                val target = fAccounts[which]
                if (target.uid == curUid && curLogged) {
                    com.solosu.mtforum.util.ToastUtil.makeText(activity, "已是当前账号", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT).show()
                    return@setItems
                }
                val ok = switchTo(activity, target.uid)
                if (ok) {
                    val info = HashMap<String, String>()
                    info["username"] = target.username ?: ""
                    info["uid"] = target.uid ?: ""
                    info["avatarUrl"] = target.avatar ?: ""
                    info["level"] = target.level ?: ""
                    UserSessionManager.getInstance().saveLoginInfo(activity, info)
                    com.solosu.mtforum.util.ToastUtil.makeText(activity, "已切换到 ${target.username}", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT).show()
                    onAccountChanged?.invoke()
                } else {
                    com.solosu.mtforum.util.ToastUtil.makeText(activity, "切换失败，请重新登录该账号", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
