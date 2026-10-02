package com.solosu.mtforum.ui.space

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.ThreadAdapter
import com.solosu.mtforum.databinding.ActivitySpaceThreadListBinding
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.detail.ThreadDetailActivity

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Set
import com.solosu.mtforum.ui.widget.DialogHelper

/**
 * 个人空间帖子/收藏列表页（原生）
 * 支持 3 种模式：my_threads（我的帖子）、my_replies（我的回复）、favorites（我的收藏）
 */
class SpaceThreadListActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpaceThreadListBinding
    private lateinit var httpClient: HttpClient
    private lateinit var adapter: ThreadAdapter
    private var mode: String? = null
    private lateinit var listUrl: String
    private var targetUid: String? = null
    private var currentFormhash: String? = null

    private var initialLoadFinished = false

    private val removedFavoriteKeys: MutableSet<String>
        get() {
            val prefs = getSharedPreferences(FAVORITE_STATE_PREFS, MODE_PRIVATE)
            return HashSet(prefs.getStringSet(KEY_REMOVED_FAVORITES, HashSet())!!)
        }

    private fun favoriteLocalKeys(thread: Thread?): MutableSet<String> {
        val keys = HashSet<String>()
        if (thread == null) return keys
        if (!TextUtils.isEmpty(thread.favid)) keys.add("favid:" + thread.favid)
        if (!TextUtils.isEmpty(thread.tid)) keys.add("tid:" + thread.tid)
        return keys
    }

    private fun favoriteLocalKey(thread: Thread?): String {
        if (thread == null) return ""
        // tid 稳定且始终存在，优先使用 tid；favid 只作为补充状态键。
        if (!TextUtils.isEmpty(thread.tid)) return "tid:" + thread.tid
        if (!TextUtils.isEmpty(thread.favid)) return "favid:" + thread.favid
        return ""
    }

    private fun sharesFavoriteKey(first: Thread?, second: Thread?): Boolean {
        if (first == null || second == null) return false
        val firstKeys = favoriteLocalKeys(first)
        firstKeys.retainAll(favoriteLocalKeys(second))
        return firstKeys.isNotEmpty()
    }

    private fun rememberRemovedFavorite(thread: Thread) {
        val key = favoriteLocalKey(thread)
        if (TextUtils.isEmpty(key)) return
        val prefs = getSharedPreferences(FAVORITE_STATE_PREFS, MODE_PRIVATE)
        val keys = removedFavoriteKeys
        keys.addAll(favoriteLocalKeys(thread))
        prefs.edit().putStringSet(KEY_REMOVED_FAVORITES, keys).apply()
    }

    private fun filterRemovedFavorites(source: MutableList<Thread>?): MutableList<Thread> {
        if (source == null || source.isEmpty()) return ArrayList()
        val removed = removedFavoriteKeys
        val result = ArrayList<Thread>()
        for (thread in source) {
            var removedItem = false
            for (key in favoriteLocalKeys(thread)) {
                if (removed.contains(key)) {
                    removedItem = true
                    break
                }
            }
            if (!removedItem) result.add(thread)
        }
        return result
    }

    private fun isFavoriteDeleteResponseSuccessful(result: String?): Boolean {
        if (TextUtils.isEmpty(result) || ForumParser.isLoginPage(result)) return false
        val text = result!!.trim().lowercase(java.util.Locale.ROOT)
        // Comiis 的 dialog 删除接口成功时常返回空响应或仅返回一段提示页；
        // 真正结果以删除后重新读取收藏列表为准，因此这里只拦截明确错误。
        if (text.contains("formhash错误") || text.contains("请先登录")
            || text.contains("没有权限") || text.contains("操作失败")
            || text.contains("删除失败") || text.contains("ajaxerror")
            || text.contains("error") || text.contains("非法操作")
        ) return false
        return text.contains("\"success\":true")
                || text.contains("\"status\":1")
                || text.contains("\"code\":0")
                || text.contains("success=1")
                || text.contains("succeed")
                || text.contains("删除成功")
                || text.contains("取消收藏成功")
    }

    private fun containsFavorite(list: MutableList<Thread>?, target: Thread?): Boolean {
        if (list == null || target == null) return false
        val key = favoriteLocalKey(target)
        for (item in list) {
            if (!TextUtils.isEmpty(key) && key == favoriteLocalKey(item)) return true
            // favid 可能因模板变化而解析不到，tid 作为最终兜底标识。
            if (!TextUtils.isEmpty(target.tid) && target.tid == item.tid) return true
        }
        return false
    }

    /**
     * 收藏页本身只返回“标题 + 删除链接”，不会返回 ThreadAdapter 所需的作者、头像、版块和统计数据。
     * 因此先解析收藏记录，再按 tid 请求帖子详情补齐卡片数据；单条详情失败时保留基础收藏项，
     * 避免收藏页面出现空白作者、空版块和全部统计为 0 的异常卡片。
     */
    private fun loadFavoriteThreads(favoriteHtml: String?): MutableList<Thread> {
        val basicList = ForumParser.parseFavoriteList(favoriteHtml)
        if (basicList == null || basicList.isEmpty()) return ArrayList()

        val result = ArrayList<Thread>()
        for (basic in basicList) {
            if (basic == null || TextUtils.isEmpty(basic.tid)) continue
            val enriched = enrichFavoriteThread(basic)
            result.add(if (enriched != null) enriched else basic)
        }
        return result
    }

    private fun enrichFavoriteThread(basic: Thread): Thread {
        try {
            val detailHtml = httpClient.get(ForumParser.getThreadDetailUrl(basic.tid))
            if (TextUtils.isEmpty(detailHtml) || ForumParser.isLoginPage(detailHtml)) return basic

            val detail = ForumParser.parseThreadDetail(detailHtml) ?: return basic

            val thread = Thread()
            thread.tid = basic.tid
            thread.favid = basic.favid
            thread.title = if (!TextUtils.isEmpty(detail.title)) detail.title else basic.title
            thread.author = detail.author
            thread.authorUid = detail.authorUid
            thread.authorLevel = detail.authorLevel
            thread.avatarUrl = detail.avatarUrl
            thread.forumName = detail.forumName
            thread.forumFid = detail.forumFid
            thread.publishTime = detail.publishTime
            thread.replies = detail.replyCount
            thread.likes = detail.likeCount
            val imgs = detail.imageUrls
            if (imgs != null) thread.imageUrls = imgs
            thread.hasImage = imgs != null && imgs.isNotEmpty()
            thread.hasHiddenContent = detail.hasHiddenContent
            return thread
        } catch (ignored: Exception) {
            return basic
        }
    }

    @Throws(Exception::class)
    private fun fetchLatestFavorites(): MutableList<Thread> {
        val verifyUrl = listUrl + (if (listUrl.contains("?")) "&" else "?") +
                "_refresh=" + System.currentTimeMillis()
        val verifyHtml = httpClient.get(verifyUrl)
        return ForumParser.parseFavoriteList(verifyHtml)
    }

    /**
     * Discuz! 标准取消收藏接口。旧的 space&do=favorite&delfavorite 参数在部分模板中
     * 只会返回收藏页面，并不会真正执行删除，因此这里使用 spacecp/favorite/delete。
     */
    private fun resolveFavoriteId(target: Thread?, html: String?): String {
        if (target == null || TextUtils.isEmpty(html)) return ""
        val parsed = ForumParser.parseFavoriteList(html)
        if (parsed != null) {
            for (item in parsed) {
                if (item != null && !TextUtils.isEmpty(target.tid)
                    && target.tid == item.tid
                    && !TextUtils.isEmpty(item.favid)
                ) {
                    return item.favid!!
                }
            }
        }
        return target.favid ?: ""
    }

    /**
     * 尝试从收藏页面 HTML 中提取收藏对话框表单里的 formhash 和 favid
     * Comiis 模板的删除对话框表单 id 为 favoriteform_{favid}，
     * 包含隐藏的 input[name=formhash]。
     */
    private fun extractFavoriteFormInfo(html: String?): Array<String?>? {
        if (TextUtils.isEmpty(html)) return null
        try {
            val doc = org.jsoup.Jsoup.parse(html!!)
            // 查找收藏对话框表单
            val form = doc.select("form[id*=favoriteform]").first()
            if (form != null) {
                // 从 action 中提取 favid
                val action = form.attr("action")
                var favid = ""
                val fm = java.util.regex.Pattern.compile("[?&]favid=(\\d+)").matcher(action)
                if (fm.find()) favid = fm.group(1)
                // 提取 formhash
                val fhInput = form.select("input[name=formhash]").first()
                val formhash = if (fhInput != null) fhInput.attr("value") else ""
                return arrayOf(favid, formhash)
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun requestDeleteFavorite(target: Thread): Boolean {
        try {
            // 收藏可能是在 WebView 中登录的；若 OkHttp 尚无有效会话，先同步 Cookie。
            if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager()
            val page = httpClient.get(listUrl + "&_refresh=" + System.currentTimeMillis())

            // ★ 优先从收藏对话框表单中提取 formhash 和 favid（Comiis 模板特有）
            var formhash: String?
            val favid: String

            // 尝试从对话框中提取
            val formInfo = extractFavoriteFormInfo(page)
            if (formInfo != null && !TextUtils.isEmpty(formInfo[0]) && !TextUtils.isEmpty(formInfo[1])) {
                favid = formInfo[0]!!
                formhash = formInfo[1]
            } else {
                // 回退到旧的解析方式
                formhash = ForumParser.parseFormhash(page)
                if (TextUtils.isEmpty(formhash)) {
                    // ★ 收藏页本身没有全局 input[name=formhash]，尝试从论坛首页获取
                    formhash = ForumParser.parseFormhash(
                        httpClient.get(
                            HttpClient.BASE_URL + "forum.php?mobile=2&_refresh=" +
                                    System.currentTimeMillis()
                        )
                    )
                }
                // 如果仍未获取到，尝试从详情页获取（桌面版更可靠）
                if (TextUtils.isEmpty(formhash) && !TextUtils.isEmpty(target.tid)) {
                    val detailHtml = httpClient.getDesktop(
                        HttpClient.BASE_URL +
                                "forum.php?mod=viewthread&tid=" + target.tid
                    )
                    formhash = ForumParser.parseFormhash(detailHtml)
                }
                favid = resolveFavoriteId(target, page)
            }
            currentFormhash = formhash
            if (TextUtils.isEmpty(favid)) return false

            // ★ 真实 Comiis 删除接口：POST 表单提交（与浏览器对话框行为一致）
            // 浏览器确认对话框实际提交的 POST 字段：
            //   referer=.../home.php?mod=space&do=favorite&mobile=2
            //   deletesubmit=true
            //   formhash=f485df32
            //   handlekey=comiis
            val deleteUrl = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=favorite&op=delete" +
                    "&favid=" + favid + "&type=all&mobile=2"

            val params = HashMap<String, String>()
            params["referer"] = listUrl
            params["deletesubmit"] = "true"
            if (!TextUtils.isEmpty(formhash)) params["formhash"] = formhash!!
            params["handlekey"] = "comiis"

            val postResult = httpClient.post(deleteUrl, params)
            var latest = fetchLatestFavorites()
            if (!containsFavorite(latest, target)) return true
            if (isFavoriteDeleteResponseSuccessful(postResult)) return true

            // 备用：尝试 GET 方式（某些旧版模板）
            val getUrl = deleteUrl + "&formhash=" + (if (formhash == null) "" else formhash) + "&inajax=1"
            val getResult = httpClient.get(getUrl)
            latest = fetchLatestFavorites()
            return !containsFavorite(latest, target) ||
                    isFavoriteDeleteResponseSuccessful(getResult)
        } catch (ignored: Exception) {
            return false
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次可见时都重新读取网页端收藏，不使用本地删除缓存覆盖服务器真实数据。
        if (initialLoadFinished && "favorites" == mode) {
            loadData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySpaceThreadListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        httpClient = HttpClient.getInstance()

        mode = intent.getStringExtra("mode")
        if (mode == null) mode = "my_threads"
        targetUid = intent.getStringExtra("uid")

        val title: String
        when (mode) {
            "favorites" -> {
                title = "我的收藏"
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=favorite&mobile=2"
            }

            "uid_threads" -> {
                val username = intent.getStringExtra("username")
                title = if (username == null || username.isEmpty()) "用户的帖子" else username + "的帖子"
                listUrl = if (targetUid == null || targetUid!!.isEmpty()) {
                    HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2"
                } else {
                    HttpClient.BASE_URL + "home.php?mod=space&uid=" + targetUid +
                            "&do=thread&view=me&mobile=2"
                }
            }

            "my_replies" -> {
                title = "我的回复"
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2&type=reply"
            }

            else -> {
                title = "我的帖子"
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2"
            }
        }

        binding.toolbar.title = title
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ThreadAdapter(this)
        adapter.setOnItemClickListener(object : ThreadAdapter.OnItemClickListener {
            override fun onItemClick(thread: Thread?, position: Int) {
                if (thread != null && thread.tid != null) {
                    val intent = Intent(this@SpaceThreadListActivity, ThreadDetailActivity::class.java)
                    intent.putExtra("tid", thread.tid)
                    startActivity(intent)
                }
            }
        })
        adapter.setOnUserClickListener(object : ThreadAdapter.OnUserClickListener {
            override fun onUserClick(thread: Thread?) {
                if (thread == null || TextUtils.isEmpty(thread.authorUid)) return
                val intent = Intent(this@SpaceThreadListActivity, UserProfileActivity::class.java)
                intent.putExtra("uid", thread.authorUid)
                intent.putExtra("username", thread.author)
                startActivity(intent)
            }
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        if ("favorites" == mode) {
            val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                    val position = holder.bindingAdapterPosition
                    val thread = adapter.getItem(position)
                    if (thread == null) return
                    confirmDeleteFavorite(thread, position)
                }
            })
            helper.attachToRecyclerView(binding.recyclerView)
            binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                private val detector = android.view.GestureDetector(
                    this@SpaceThreadListActivity,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            val child = binding.recyclerView.findChildViewUnder(e.x, e.y)
                            if (child == null) return
                            val position = binding.recyclerView.getChildAdapterPosition(child)
                            val thread = adapter.getItem(position)
                            if (thread != null) confirmDeleteFavorite(thread, position)
                        }

                        override fun onDown(e: MotionEvent): Boolean {
                            return true
                        }
                    }
                )

                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    detector.onTouchEvent(e)
                    return false
                }
            })
        }

        binding.swipeRefresh.setOnRefreshListener { loadData() }
        binding.swipeRefresh.setColorSchemeColors(com.solosu.mtforum.util.ThemeManager.getThemeColor(this))

        loadData()
    }

    private fun confirmDeleteFavorite(thread: Thread, position: Int) {
        val alertDialog: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setMessage("确定取消收藏“" + (if (thread.title == null) "此帖子" else thread.title) + "”吗？")
            .setNegativeButton("取消", null)
            .show()
        DialogHelper.applyToAlertDialog(alertDialog, this)
    }

    private fun deleteFavorite(thread: Thread?, position: Int) {
        if (thread == null) {
            Toast.makeText(this, "收藏项为空，无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        if (TextUtils.isEmpty(thread.favid) && TextUtils.isEmpty(thread.tid)) {
            Toast.makeText(this, "未获取到收藏记录ID或帖子ID，无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        java.lang.Thread {
            val success = requestDeleteFavorite(thread)
            val message = if (success) "已取消收藏" else "删除失败"
            val ok = success
            val msg = message
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (ok) {
                    rememberRemovedFavorite(thread)
                    adapter.removeItem(position)
                    if (adapter.itemCount == 0) {
                        binding.recyclerView.visibility = View.GONE
                        binding.tvEmpty.visibility = View.VISIBLE
                    }
                } else {
                    adapter.notifyItemChanged(position)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = false

        java.lang.Thread {
            try {
                httpClient.syncFromCookieManager()
                val html = httpClient.get(listUrl)

                // 检测登录过期
                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        binding.swipeRefresh.isEnabled = true
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }

                val items = ArrayList<Thread>()
                if ("favorites" == mode) {
                    val favList = loadFavoriteThreads(html)
                    if (favList != null) items.addAll(favList)
                } else {
                    val threadList = ForumParser.parseForumThreadList(html)
                    if (threadList != null) items.addAll(threadList)
                }

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true

                    if (items.isEmpty()) {
                        binding.recyclerView.visibility = View.GONE
                        binding.tvEmpty.visibility = View.VISIBLE
                    } else {
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.tvEmpty.visibility = View.GONE
                        adapter.setThreadList(items)
                    }
                    initialLoadFinished = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true
                    Toast.makeText(this, "加载失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    companion object {
        private const val FAVORITE_STATE_PREFS = "favorite_local_state"
        private const val KEY_REMOVED_FAVORITES = "removed_favorite_keys"
    }
}
