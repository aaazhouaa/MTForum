package com.solosu.mtforum.ai

import android.content.Context
import android.text.TextUtils

import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.model.PostDetail
import com.solosu.mtforum.model.ReplyItem
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.UserSessionManager

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.ArrayList
import java.util.HashMap

/**
 * 论坛接口的 AI 工具层。
 * 把论坛的读接口包装成可被大模型 function calling 调用的工具，
 * 让 AI 能主动查询、学习、总结帖子内容。
 *
 * 所有方法都是阻塞式的，必须在后台线程调用。
 */
object ForumTools {

    // ==================== 工具定义 ====================

    /** 返回全部可供 AI 调用的工具定义 */
    @JvmStatic
    fun definitions(): MutableList<AiClient.ToolDef> {
        val list = ArrayList<AiClient.ToolDef>()

        list.add(
            AiClient.ToolDef(
                "search_forum",
                "在 MT 论坛搜索帖子。返回匹配的帖子列表，含标题、作者、版块、回复数、链接和摘要。" +
                        "适合查找某个技术话题的讨论，例如「脱壳」「smali 修改」「签名校验」。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"}," +
                        "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始，默认1\"}," +
                        "\"orderby\":{\"type\":\"string\",\"enum\":[\"lastpost\",\"dateline\",\"replies\"]," +
                        "\"description\":\"排序：lastpost最新回复 / dateline最新发布 / replies最多回复\"}" +
                        "},\"required\":[\"keyword\"]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "list_threads",
                "列出某个版块下的帖子。不传 fid 时返回论坛最新帖子。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"fid\":{\"type\":\"string\",\"description\":\"版块ID，例如 2。不传则取最新帖子\"}," +
                        "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始\"}" +
                        "},\"required\":[]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "list_forums",
                "获取论坛的全部版块列表（含版块ID，供 list_threads 使用）。",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "get_thread",
                "获取帖子完整详情，包含楼主正文、隐藏内容、统计数据和回复列表。" +
                        "默认会把该帖的回复一次性抓到底（跨所有分页），无需手动翻页。" +
                        "这是学习帖子内容的主要接口，返回纯文本正文，便于阅读和总结。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"}," +
                        "\"page\":{\"type\":\"integer\",\"description\":\"起始回复页码，从1开始\"}," +
                        "\"max_replies\":{\"type\":\"integer\",\"description\":\"最多返回多少条回复，默认200\"}," +
                        "\"fetch_all\":{\"type\":\"boolean\",\"description\":\"是否抓全所有分页的回复，默认 true\"}" +
                        "},\"required\":[\"tid\"]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "get_replies",
                "获取帖子的回复列表。默认自动翻页、一次抓到底（跨所有分页），" +
                        "返回带 total_pages / fetched 字段，可确认是否抓全。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"}," +
                        "\"page\":{\"type\":\"integer\",\"description\":\"起始页码，从1开始\"}," +
                        "\"max_replies\":{\"type\":\"integer\",\"description\":\"最多返回多少条，默认300\"}," +
                        "\"fetch_all\":{\"type\":\"boolean\",\"description\":\"是否抓全所有分页，默认 true\"}" +
                        "},\"required\":[\"tid\"]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "my_threads",
                "获取当前登录用户的帖子列表。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始\"}" +
                        "},\"required\":[]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "get_notices",
                "获取当前用户的通知和私信列表，含未读状态。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"type\":{\"type\":\"string\",\"enum\":[\"pm\",\"mypost\",\"interactive\",\"system\"]," +
                        "\"description\":\"通知类型：pm私信 / mypost我的帖子 / interactive互动 / system系统\"}" +
                        "},\"required\":[]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "get_user_profile",
                "获取指定用户的资料信息。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"uid\":{\"type\":\"string\",\"description\":\"用户UID\"}" +
                        "},\"required\":[\"uid\"]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "post_reply",
                "在指定帖子下发表回复。这是有副作用的写操作，仅在明确需要回复时调用。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"}," +
                        "\"message\":{\"type\":\"string\",\"description\":\"回复内容\"}" +
                        "},\"required\":[\"tid\",\"message\"]}"
            )
        )

        list.add(
            AiClient.ToolDef(
                "unlock_hidden",
                "解锁帖子的隐藏内容：当帖子含「回复可见」的隐藏内容时，自动发表一条回复以解锁，" +
                        "然后回读页面确认是否已解锁并返回隐藏内容。「回复可见」指帖子里显示" +
                        "「游客，如果您要查看本帖隐藏内容请回复」。",
                "{\"type\":\"object\",\"properties\":{" +
                        "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"}," +
                        "\"message\":{\"type\":\"string\",\"description\":\"可选，自定义回复内容；不传则根据帖子内容自动生成\"}" +
                        "},\"required\":[\"tid\"]}"
            )
        )

        return list
    }

    // ==================== 工具执行 ====================
    /**
     * 把工具清单渲染成纯文本说明，供不支持 function calling 的模型使用。
     * 模型按约定单独输出一行 {"tool":"名字","args":{...}} 即被本地执行。
     */
    @JvmStatic
    fun textToolCatalog(): String {
        val sb = StringBuilder()
        sb.append("你可以调用下列工具读取 MT 论坛的真实数据。\n")
        sb.append("需要调用工具时，只输出一行 JSON，不要任何额外文字、不要 markdown 代码块：\n")
        sb.append("{\"tool\":\"工具名\",\"args\":{参数}}\n")
        sb.append("要一次拿多个数据，就输出多行，每行一个调用。\n")
        sb.append("拿到工具结果后：还需要更多数据就继续输出调用行；")
        sb.append("信息已经够回答用户时，直接输出最终回答的正文，不要再输出任何 JSON。\n")
        sb.append("绝对不要只在脑子里计划要调用什么，要么真的输出调用行，要么直接回答。\n\n")
        sb.append("可用工具：\n")
        for (t in definitions()) {
            sb.append("- ").append(t.name).append("：").append(t.description).append('\n')
            sb.append("  参数：").append(t.parametersJson).append('\n')
        }
        return sb.toString()
    }

    /**
     * 执行一次工具调用。
     *
     * @return JSON 字符串结果，供回填给模型
     */
    @JvmStatic
    fun execute(context: Context, toolName: String?, args: JSONObject): String {
        try {
            if (TextUtils.isEmpty(toolName)) return err("缺少工具名")
            return when (toolName) {
                "search_forum" -> searchForum(context, args)
                "list_threads" -> listThreads(context, args)
                "list_forums" -> listForums(context)
                "get_thread" -> getThread(context, args)
                "get_replies" -> getReplies(context, args)
                "my_threads" -> myThreads(context, args)
                "get_notices" -> getNotices(context, args)
                "get_user_profile" -> getUserProfile(context, args)
                "post_reply" -> postReply(context, args)
                "unlock_hidden" -> unlockHidden(context, args)
                else -> err("未知工具: $toolName")
            }
        } catch (e: Exception) {
            return err(e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ==================== 读接口实现 ====================

    @Throws(Exception::class)
    private fun searchForum(context: Context, args: JSONObject): String {
        val kw = args.optString("keyword", "").trim()
        if (TextUtils.isEmpty(kw)) return err("关键词为空")
        val page = Math.max(1, args.optInt("page", 1))
        val orderby = args.optString("orderby", "lastpost")

        val client = requireLogin(context)
        val url = ForumParser.getSearchUrl(kw, page, orderby)
        val html = client.get(url)
        if (isBad(html)) return err("搜索失败，登录态可能已失效")

        var list: MutableList<Thread> = ForumParser.parseSearchResults(html)
        if (list.isEmpty()) list = ForumParser.parseThreadList(html)

        val out = JSONObject()
        out.put("keyword", kw)
        out.put("page", page)
        out.put("total_pages", ForumParser.parseSearchTotalPages(html))
        out.put("count", list.size)
        out.put("threads", threadsToJson(list))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun listThreads(context: Context, args: JSONObject): String {
        val fid = args.optString("fid", "").trim()
        val page = Math.max(1, args.optInt("page", 1))

        val client = HttpClient.getInstance()
        var html: String
        if (TextUtils.isEmpty(fid)) {
            html = client.get(ForumParser.getHomeUrl(page))
            if (isBad(html)) {
                html = client.get(ForumParser.getGuideUrl("newthread", page))
            }
        } else {
            html = client.get(ForumParser.getThreadListUrl(fid, page))
            if (isBad(html)) {
                html = client.getDesktop(ForumParser.getThreadListUrlDesktop(fid, page))
            }
        }
        if (isBad(html)) return err("帖子列表获取失败")

        val list = if (TextUtils.isEmpty(fid))
            ForumParser.parseThreadList(html)
        else
            ForumParser.parseForumThreadList(html)

        val out = JSONObject()
        out.put("fid", fid)
        out.put("page", page)
        out.put("count", list.size)
        out.put("threads", threadsToJson(list))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun listForums(context: Context): String {
        val client = HttpClient.getInstance()
        var html = client.get(ForumParser.getForumlistMobileUrl())
        if (isBad(html)) html = client.getDesktop(
            HttpClient.BASE_URL + "forum.php?forumlist=1"
        )
        if (isBad(html)) return err("版块列表获取失败")

        val cats = ForumParser.parseForumCategories(html)
        val arr = JSONArray()
        if (cats != null) {
            for (c in cats) {
                val cat = JSONObject()
                cat.put("category", safe(c.name))
                val subs = JSONArray()
                val forums = c.forums
                if (forums != null) {
                    for (f in forums) {
                        val o = JSONObject()
                        o.put("fid", safe(f.fid))
                        o.put("name", safe(f.name))
                        o.put("description", safe(f.description))
                        o.put("today_posts", f.todayPosts)
                        o.put("total_threads", f.totalThreads)
                        subs.put(o)
                    }
                }
                cat.put("forums", subs)
                arr.put(cat)
            }
        }
        val out = JSONObject()
        out.put("count", arr.length())
        out.put("categories", arr)
        return out.toString()
    }

    @Throws(Exception::class)
    private fun getThread(context: Context, args: JSONObject): String {
        val tid = args.optString("tid", "").trim()
        if (TextUtils.isEmpty(tid)) return err("tid 为空")
        val page = Math.max(1, args.optInt("page", 1))
        var maxReplies = args.optInt("max_replies", 200)
        if (maxReplies <= 0) maxReplies = 200
        // 默认把回复一次性抓到底
        val fetchAll = !args.has("fetch_all") || args.optBoolean("fetch_all", true)

        val client = HttpClient.getInstance()
        val url = if (page > 1)
            ForumParser.getThreadDetailUrl(tid, page, null)
        else
            ForumParser.getThreadDetailUrl(tid)
        val html = client.get(url)
        if (isBad(html)) return err("帖子读取失败，可能不存在或需要登录")

        val d = ForumParser.parseThreadDetail(html) ?: return err("帖子解析失败")

        // 抓全回复：从当前页往后逐页拉取
        val allReplies = ArrayList<ReplyItem>()
        if (d.replies != null) allReplies.addAll(d.replies!!)
        var totalPages = Math.max(1, d.totalPages)
        var truncated = false
        if (fetchAll) {
            val maxPages = Math.min(Math.max(page, totalPages), 60)
            for (p in page + 1..maxPages) {
                if (allReplies.size >= maxReplies) {
                    truncated = true
                    break
                }
                try {
                    val h2 = client.get(ForumParser.getThreadDetailUrl(tid, p, null))
                    if (isBad(h2)) break
                    val d2 = ForumParser.parseThreadDetail(h2) ?: break
                    if (d2.totalPages > totalPages) totalPages = d2.totalPages
                    if (d2.replies != null && d2.replies!!.isNotEmpty()) allReplies.addAll(d2.replies!!)
                } catch (e: Exception) {
                    break
                }
            }
        }

        val out = JSONObject()
        out.put("tid", tid)
        out.put("title", safe(d.title))
        out.put("forum_name", safe(d.forumName))
        out.put("forum_fid", safe(d.forumFid))
        out.put("author", safe(d.author))
        out.put("author_uid", safe(d.authorUid))
        out.put("author_level", safe(d.authorLevel))
        out.put("publish_time", safe(d.publishTime))
        out.put("reply_count", d.replyCount)
        out.put("like_count", d.likeCount)
        out.put("view_page", d.currentPage)
        out.put("total_pages", totalPages)
        out.put("replies_fetched", allReplies.size)
        out.put("replies_fetched_all", fetchAll && !truncated)

        val body = htmlToText(d.contentHtml)
        out.put("content", clip(body, 12000))
        out.put("content_length", body.length)

        if (d.hasHiddenContent && !TextUtils.isEmpty(d.hiddenContentHtml)) {
            out.put("hidden_content", clip(htmlToText(d.hiddenContentHtml), 4000))
        }

        val imgs = d.imageUrls
        if (imgs != null && imgs.isNotEmpty()) {
            val ia = JSONArray()
            var i = 0
            while (i < imgs.size && i < 12) {
                ia.put(imgs[i])
                i++
            }
            out.put("images", ia)
        }

        out.put("replies", repliesToJson(allReplies, maxReplies))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun getReplies(context: Context, args: JSONObject): String {
        val tid = args.optString("tid", "").trim()
        if (TextUtils.isEmpty(tid)) return err("tid 为空")
        val page = Math.max(1, args.optInt("page", 1))
        var max = args.optInt("max_replies", 300)
        if (max <= 0) max = 300
        // 默认一次抓到最后一页
        val fetchAll = !args.has("fetch_all") || args.optBoolean("fetch_all", true)

        val client = HttpClient.getInstance()
        val html = client.get(ForumParser.getThreadDetailUrl(tid, page, null))
        if (isBad(html)) return err("回复读取失败")

        val d = ForumParser.parseThreadDetail(html) ?: return err("回复解析失败")

        val all = ArrayList<ReplyItem>()
        if (d.replies != null) all.addAll(d.replies!!)
        var totalPages = Math.max(1, d.totalPages)
        val cur = Math.max(1, d.currentPage)
        var truncated = false

        if (fetchAll) {
            var maxPages = Math.max(cur, totalPages)
            if (maxPages > 60) maxPages = 60   // 硬上限，防死循环
            for (p in cur + 1..maxPages) {
                if (all.size >= max) {
                    truncated = true
                    break
                }
                try {
                    val h2 = client.get(ForumParser.getThreadDetailUrl(tid, p, null))
                    if (isBad(h2)) break
                    val d2 = ForumParser.parseThreadDetail(h2) ?: break
                    if (d2.totalPages > totalPages) totalPages = d2.totalPages
                    if (d2.replies != null && d2.replies!!.isNotEmpty()) {
                        all.addAll(d2.replies!!)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }

        val out = JSONObject()
        out.put("tid", tid)
        out.put("start_page", cur)
        out.put("total_pages", totalPages)
        out.put("reply_count", d.replyCount)
        out.put("fetched", all.size)
        out.put("fetched_all", fetchAll && !truncated)
        if (truncated) out.put("note", "回复条数超过单次上限 $max，已截断")
        out.put("replies", repliesToJson(all, max))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun myThreads(context: Context, args: JSONObject): String {
        val client = requireLogin(context)
        // 关键诊断：本地会话与 Cookie 都没有登录态时直接说明，别让上层以为是"没有帖子"
        if (!client.isLoggedIn() && TextUtils.isEmpty(UserSessionManager.getInstance().getUid(context))) {
            return err("当前未检测到登录态。请先在应用内登录 MT 论坛账号，再让 AI 读你的帖子。")
        }
        val page = Math.max(1, args.optInt("page", 1))
        val uid = UserSessionManager.getInstance().getUid(context)

        // 优先用 uid 定位个人空间；若本地没有 uid（会话信息缺失，只有 Cookie 登录态），
        // 退回 view=me —— 服务端会按当前 Cookie 识别本人，避免直接返回空。
        val url: String
        if (TextUtils.isEmpty(uid)) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&page=" + page + "&mobile=2"
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&uid=" + uid +
                    "&do=thread&view=me&page=" + page + "&mobile=2"
        }

        var html = client.get(url)
        if (isBad(html) && !TextUtils.isEmpty(uid)) {
            // uid 路径失败时同样退回 view=me 再试一次
            html = client.get(
                HttpClient.BASE_URL +
                        "home.php?mod=space&do=thread&view=me&page=" + page + "&mobile=2"
            )
        }
        if (isBad(html)) return err("我的帖子读取失败，可能是登录态已失效")

        var list = ForumParser.parseForumThreadList(html)
        if (list.isEmpty()) list = ForumParser.parseThreadList(html)

        val out = JSONObject()
        out.put("page", page)
        out.put("uid", safe(uid))
        out.put("count", list.size)
        if (list.isEmpty()) {
            out.put("hint", "没有解析到帖子。可能该账号确实没有主题帖，或页面结构与解析规则不符。")
        }
        out.put("threads", threadsToJson(list))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun getNotices(context: Context, args: JSONObject): String {
        val client = requireLogin(context)
        val type = args.optString("type", "pm")

        val url: String
        if ("pm" == type) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2"
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + type
        }
        val html = if ("pm" == type)
            client.get(url)
        else
            client.getDesktop(url)
        if (isBad(html)) return err("通知读取失败")

        val items: MutableList<com.solosu.mtforum.model.Message>
        if ("pm" == type) items = ForumParser.parsePmList(html)
        else if ("follower" == type) items = ForumParser.parseFollowerList(html)
        else items = ForumParser.parseNoticeList(html)

        val arr = JSONArray()
        var unread = 0
        for (m in items) {
            val o = JSONObject()
            o.put("author", safe(m.author))
            o.put("author_uid", safe(m.authorUid))
            o.put("title", safe(m.title))
            o.put("summary", clip(safe(m.summary), 300))
            o.put("time", safe(m.time))
            o.put("read", m.isRead)
            arr.put(o)
            if (!m.isRead) unread++
        }
        val out = JSONObject()
        out.put("type", type)
        out.put("unread", unread)
        out.put("count", arr.length())
        out.put("items", arr)
        return out.toString()
    }

    @Throws(Exception::class)
    private fun getUserProfile(context: Context, args: JSONObject): String {
        val uid = args.optString("uid", "").trim()
        if (TextUtils.isEmpty(uid)) return err("uid 为空")

        val client = HttpClient.getInstance()
        val html = client.get(ForumParser.getUserSpaceUrl(uid))
        if (isBad(html)) return err("用户资料读取失败")

        val p = ForumParser.parseUserProfile(html) ?: return err("用户资料解析失败")

        val out = JSONObject()
        out.put("uid", uid)
        out.put("username", safe(p.username))
        out.put("level", safe(p.level))
        out.put("group", safe(p.groupName))
        out.put("signature", safe(p.signature))
        out.put("credits", p.credits)
        out.put("gold", p.gold)
        out.put("threads", p.threads)
        out.put("posts", p.posts)
        out.put("followers", p.followers)
        out.put("following", p.following)
        out.put("reg_date", safe(p.regDate))
        out.put("last_visit", safe(p.lastVisit))
        out.put("online", p.isOnline)
        return out.toString()
    }

    // ==================== 写接口实现 ====================

    /**
     * 解锁帖子的隐藏内容：发一条回复 → 回读确认 → 返回隐藏内容。
     * 若帖子本身没有隐藏内容，或已经解锁，直接返回状态，不做写操作。
     *
     * 门控判定与回复文案均复用 AutoReplyEngine，不再本地另写一套：
     * 此前本类用 AI 生成解锁文案（generateUnlockReply），而进帖解锁用本地模板，
     * 两者行为不一致且 AI 可能拒答导致解锁失败。现统一为本地模板 + 用户自定义模板。
     */
    @Throws(Exception::class)
    private fun unlockHidden(context: Context, args: JSONObject): String {
        val tid = args.optString("tid", "").trim()
        if (TextUtils.isEmpty(tid)) return err("tid 为空")
        var message = args.optString("message", "").trim()

        val client = requireLogin(context)
        if (!client.isLoggedIn()) {
            client.syncFromCookieManager()
            if (!client.isLoggedIn()) return err("未登录，无法解锁")
        }

        // 1. 先看当前状态
        val html = client.get(ForumParser.getThreadDetailUrl(tid))
        if (isBad(html)) return err("获取帖子信息失败")
        val d = ForumParser.parseThreadDetail(html) ?: return err("帖子解析失败")

        val out = JSONObject()
        out.put("tid", tid)
        out.put("title", safe(d.title))

        if (!d.hasHiddenContent) {
            out.put("success", true)
            out.put("hidden", false)
            out.put("note", "该帖子没有隐藏内容，无需解锁")
            return out.toString()
        }

        val hiddenHtml = d.hiddenContentHtml
        if (!TextUtils.isEmpty(hiddenHtml)
            && !AutoReplyEngine.isLockedHidden(hiddenHtml)
        ) {
            out.put("success", true)
            out.put("hidden", true)
            out.put("already_unlocked", true)
            out.put("content", clip(htmlToText(hiddenHtml), 6000))
            return out.toString()
        }

        // 2. 生成回复内容：优先用调用方/用户自定义文案，否则走共用的本地模板
        var text = cleanupReply(message)
        if (TextUtils.isEmpty(text)) {
            text = AutoReplyEngine.buildUnlockText(context, d)
        }
        if (TextUtils.isEmpty(text)) text = "感谢分享，回复支持一下。"
        text = cleanupReply(text)

        // 3. 发表回复
        var formhash = d.formhash
        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html)
        if (TextUtils.isEmpty(formhash)) return err("无法获取 formhash，回复可能要登录态")

        val fid = safe(d.forumFid)
        val params = HashMap<String, String>()
        params["formhash"] = formhash!!
        params["message"] = text
        params["replysubmit"] = "yes"
        params["posttime"] = (System.currentTimeMillis() / 1000).toString()

        val url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply" +
                "&fid=" + fid + "&tid=" + tid +
                "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1"
        val result = client.post(url, params)
        if (TextUtils.isEmpty(result)) return err("回复请求无响应")
        val lower = result.lowercase()
        if (lower.contains("请先登录") || lower.contains("没有权限")
            || lower.contains("非法操作") || lower.contains("回复失败")
        ) {
            out.put("success", false)
            out.put("error", clip(stripTags(result), 200))
            out.put("message_sent", text)
            return out.toString()
        }

        // 4. 回读确认是否解锁
        val html2 = client.get(
            ForumParser.getThreadDetailUrl(tid)
                + "&_unlock=" + System.currentTimeMillis()
        )
        val d2 = if (isBad(html2)) null else ForumParser.parseThreadDetail(html2)
        val hidden2 = if (d2 == null) null else d2.hiddenContentHtml
        val ok = !TextUtils.isEmpty(hidden2) &&
                !AutoReplyEngine.isLockedHidden(hidden2)

        out.put("success", ok)
        out.put("hidden", true)
        out.put("message_sent", text)
        if (ok) {
            out.put("content", clip(htmlToText(hidden2), 6000))
        } else {
            out.put("note", "回复已提交，但回读时仍是未解锁状态。可能被论坛风控拦截，或隐藏内容需要审核后才可见。")
        }
        return out.toString()
    }

    private fun cleanupReply(text: String?): String {
        if (text == null) return ""
        var t = text.trim()
        if (t.length > 1 && ((t.startsWith("\"") && t.endsWith("\""))
                    || (t.startsWith("「") && t.endsWith("」"))
                    || (t.startsWith("“") && t.endsWith("”")))
        ) {
            t = t.substring(1, t.length - 1).trim()
        }
        t = t.replaceFirst(Regex("^(回复|回答|评论)[:：]\\s*"), "")
        t = t.replace("**", "").trim()
        if (t.length > 200) t = t.substring(0, 200)
        return t
    }

    @Throws(Exception::class)
    private fun postReply(context: Context, args: JSONObject): String {
        val tid = args.optString("tid", "").trim()
        val message = args.optString("message", "").trim()
        if (TextUtils.isEmpty(tid)) return err("tid 为空")
        if (TextUtils.isEmpty(message)) return err("回复内容为空")

        val client = requireLogin(context)

        // 先取帖子详情，拿 formhash 和 fid
        val detailUrl = ForumParser.getThreadDetailUrl(tid)
        val html = client.get(detailUrl)
        if (isBad(html)) return err("获取帖子信息失败")

        val d = ForumParser.parseThreadDetail(html)
        var formhash = if (d != null) d.formhash else null
        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html)
        if (TextUtils.isEmpty(formhash)) return err("无法获取 formhash")

        val fid = if (d != null) safe(d.forumFid) else ""

        val params = HashMap<String, String>()
        params["formhash"] = formhash!!
        params["message"] = message
        params["replysubmit"] = "yes"
        params["posttime"] = (System.currentTimeMillis() / 1000).toString()

        val url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply" +
                "&fid=" + fid + "&tid=" + tid +
                "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1"

        val result = client.post(url, params)
        val ok = isReplySuccess(result)

        val out = JSONObject()
        out.put("success", ok)
        out.put("tid", tid)
        out.put("message", message)
        if (!ok) out.put("raw", clip(stripTags(result), 300))
        return out.toString()
    }

    // ==================== 辅助 ====================

    /** 需要登录的接口统一检查，未登录时尝试从 WebView 补同步一次 */
    private fun requireLogin(context: Context): HttpClient {
        val client = HttpClient.getInstance()
        if (!client.isLoggedIn()) client.syncFromCookieManager()
        return client
    }

    private fun isBad(html: String?): Boolean {
        return html == null || html.trim().isEmpty() || ForumParser.isLoginPage(html)
    }

    private fun isReplySuccess(result: String?): Boolean {
        if (TextUtils.isEmpty(result)) return false
        val lower = result!!.lowercase()
        if (lower.contains("请先登录") || lower.contains("formhash")
            || lower.contains("没有权限") || lower.contains("非法操作")
            || lower.contains("回复失败")
        ) {
            return false
        }
        return lower.contains("回复成功") || lower.contains("succeedhandle")
                || lower.contains("showmessage") || lower.contains("success")
    }

    @Throws(Exception::class)
    private fun threadsToJson(list: MutableList<Thread>?): JSONArray {
        val arr = JSONArray()
        if (list == null) return arr
        for (t in list) {
            val o = JSONObject()
            o.put("tid", safe(t.tid))
            o.put("title", safe(t.title))
            o.put("author", safe(t.author))
            o.put("author_uid", safe(t.authorUid))
            o.put("forum_name", safe(t.forumName))
            o.put("forum_fid", safe(t.forumFid))
            o.put("publish_time", safe(t.publishTime))
            o.put("replies", t.replies)
            o.put("views", t.views)
            o.put("summary", clip(safe(t.summary), 400))
            o.put("url", ForumParser.getThreadDetailUrl(safe(t.tid)))
            arr.put(o)
        }
        return arr
    }

    @Throws(Exception::class)
    private fun repliesToJson(list: MutableList<ReplyItem>?, max: Int): JSONArray {
        val arr = JSONArray()
        if (list == null) return arr
        var n = 0
        val budget = 80000   // 总字符预算，防止抓全后撑爆模型上下文
        var used = 0
        for (r in list) {
            if (n >= max) break
            var content = safe(r.contentText)
            if (content.length > 1500) content = content.substring(0, 1500) + "…"
            if (used + content.length > budget) break
            used += content.length
            val o = JSONObject()
            o.put("pid", safe(r.pid))
            o.put("author", safe(r.author))
            o.put("author_uid", safe(r.authorUid))
            o.put("time", safe(r.time))
            o.put("content", content)
            o.put("is_op", r.isOP)
            arr.put(o)
            n++
        }
        return arr
    }

    /** HTML 转纯文本，保留段落换行，去掉脚本样式 */
    @JvmStatic
    fun htmlToText(html: String?): String {
        if (TextUtils.isEmpty(html)) return ""
        try {
            val doc = Jsoup.parse(html!!)
            for (e in doc.select("script,style,br")) {
                if ("br" == e.tagName()) e.after("\n")
            }
            val text = doc.text()
            return text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        } catch (e: Exception) {
            return stripTags(html)
        }
    }

    @JvmStatic
    fun stripTags(html: String?): String {
        if (html == null) return ""
        return html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun safe(s: String?): String {
        return s ?: ""
    }

    private fun clip(s: String?, max: Int): String {
        if (s == null) return ""
        return if (s.length > max) s.substring(0, max) + "…[已截断]" else s
    }

    private fun err(msg: String): String {
        return try {
            val o = JSONObject()
            o.put("error", msg)
            o.toString()
        } catch (e: Exception) {
            "{\"error\":\"unknown\"}"
        }
    }
}
