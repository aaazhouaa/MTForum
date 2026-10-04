package com.solosu.mtforum.network

import android.text.TextUtils

import com.solosu.mtforum.model.ChatMessage
import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.model.PostDetail
import com.solosu.mtforum.model.ReplyItem
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.model.Message
import com.solosu.mtforum.model.UserProfile
import com.solosu.mtforum.model.Friend

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern

/**
 * 论坛 HTML 解析器
 * 解析 Discuz! 移动端输出的结构化 HTML
 */
object ForumParser {

    private const val BASE_DOMAIN = "https://bbs.binmt.cc/"

    @JvmStatic
    fun getBaseDomain(): String = BASE_DOMAIN

    /**
     * 解析首页/帖子列表（Comiis App 模板适配）
     */
    @JvmStatic
    fun parseThreadList(html: String?): MutableList<Thread> {
        val threads = ArrayList<Thread>()
        val doc = Jsoup.parse(html ?: "")

        // Comiis App 模板的帖子容器
        var items = doc.select("li.forumlist_li")
        if (items.isEmpty()) {
            // 兜底：查找包含 thread- 链接的 li 元素
            val links = doc.select("a[href*=thread-]")
            for (link in links) {
                val parent = link.closest("li")
                if (parent != null) {
                    items.add(parent)
                }
            }
        }

        // 预编译正则
        val tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html")
        val fidPattern = Pattern.compile("forum-(\\d+)-1\\.html")

        for (item in items) {
            try {
                val t = Thread()

                // === 标题 & tid ===
                var titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first()
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first()
                }
                if (titleLink == null) continue

                val href = titleLink.attr("href")
                val tidMatcher = tidPattern.matcher(href)
                if (!tidMatcher.find()) continue
                t.tid = tidMatcher.group(1)

                // 标题：只取 ownText（排除热度徽章等子元素文本）
                var title = titleLink.ownText().trim()
                if (title.isEmpty()) title = titleLink.text().trim()
                t.title = title

                // === 作者 ===
                val authorEl = item.select(".forumlist_li_top .top_user").first()
                if (authorEl != null) {
                    t.author = authorEl.text().trim()
                    // 从作者链接中提取 UID
                    val authorHref = authorEl.attr("href")
                    val m = Pattern.compile("uid=(\\d+)").matcher(authorHref)
                    if (m.find()) t.authorUid = m.group(1)
                }

                // === 头像 ===
                val avatarEl = item.select(".forumlist_li_top .top_tximg").first()
                if (avatarEl != null) {
                    var src = avatarEl.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    t.avatarUrl = src
                }

                // === 等级 ===
                val levelEl = item.select(".forumlist_li_top .top_lev").first()
                if (levelEl != null) {
                    t.authorLevel = levelEl.text().trim()
                }

                // === 发布时间 ===
                val timeEl = item.select(".forumlist_li_time .f_d").first()
                if (timeEl != null) {
                    t.publishTime = timeEl.text().trim()
                }

                // === 版块 ===
                val forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first()
                if (forumLink != null) {
                    // 版块名称：ownText 排除 <i> 图标文字
                    var forumName = forumLink.ownText().trim()
                    if (forumName.isEmpty()) forumName = forumLink.text().trim()
                    t.forumName = forumName

                    val forumHref = forumLink.attr("href")
                    val fidMatcher = fidPattern.matcher(forumHref)
                    if (fidMatcher.find()) t.forumFid = fidMatcher.group(1)
                }

                // === 内容摘要 ===
                val summaryEl = item.select(".list_body .f_b").first()
                if (summaryEl != null) {
                    t.summary = summaryEl.text().trim()
                }

                // === 统计信息（点赞/回复/查看） ===
                val stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm")
                // 顺序：第1个=点赞，第2个=回复，第3个=查看
                if (stats.size >= 3) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                    t.views = parseIntFromText(stats.get(2).text())
                } else if (stats.size >= 2) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                }

                // === 特殊标记 ===
                // 图片检测
                t.hasImage = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img"
                ).size > 0

                // 提取帖子封面图URL
                val imgEl = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img"
                ).first()
                if (imgEl != null) {
                    val imgSrc = firstNonEmptyAttr(
                        imgEl, "comiis_loadimages", "data-original", "data-src",
                        "data-file", "file", "src"
                    )
                    if (!TextUtils.isEmpty(imgSrc)) {
                        val fullUrl = resolveAttachmentUrl(imgSrc)
                        if (isPostImageUrl(fullUrl)) {
                            t.thumbnailUrl = fullUrl
                        }
                    }
                }

                // === 隐藏内容检测 ===
                val bodyText = item.select(".list_body .f_b").first()
                if (bodyText != null && bodyText.text().contains("本内容被作者隐藏")) {
                    t.hasHiddenContent = true
                }

                populateThreadImages(item, t)
                t.isSticky = isStickyThread(item)

                threads.add(t)
            } catch (ignored: Exception) {
            }
        }

        return threads
    }

    /**
     * 解析搜索结果列表（Comiis 手机版模板适配）
     * 手机版搜索结果页使用与首页/版块列表相同的 Comiis 模板结构。
     */
    @JvmStatic
    fun parseSearchResults(html: String?): MutableList<Thread> {
        val threads = ArrayList<Thread>()
        val doc = Jsoup.parse(html ?: "")

        // Comiis 手机版搜索结果容器
        var items = doc.select("li.forumlist_li")
        if (items.isEmpty()) {
            val links = doc.select("a[href*=thread-]")
            for (link in links) {
                val parent = link.closest("li")
                if (parent != null) {
                    items.add(parent)
                }
            }
        }

        val tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html")
        val fidPattern = Pattern.compile("forum-(\\d+)-1\\.html")

        for (item in items) {
            try {
                val t = Thread()

                // === 标题 & tid ===
                var titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first()
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first()
                }
                if (titleLink == null) continue

                val href = titleLink.attr("href")
                val tidMatcher = tidPattern.matcher(href)
                if (!tidMatcher.find()) continue
                t.tid = tidMatcher.group(1)

                // 标题：用 text() 获取完整文本（高亮关键词被 <strong><font> 包裹）
                val title = titleLink.text().trim()
                if (title.isEmpty()) continue
                t.title = title

                // === 作者 ===
                val authorEl = item.select(".forumlist_li_top .top_user").first()
                if (authorEl != null) {
                    t.author = authorEl.text().trim()
                    val authorHref = authorEl.attr("href")
                    val m = Pattern.compile("uid=(\\d+)").matcher(authorHref)
                    if (m.find()) t.authorUid = m.group(1)
                }

                // === 头像 ===
                val avatarEl = item.select(".forumlist_li_top .top_tximg").first()
                if (avatarEl != null) {
                    var src = avatarEl.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    t.avatarUrl = src
                }

                // === 等级 ===
                val levelEl = item.select(".forumlist_li_top .top_lev").first()
                if (levelEl != null) {
                    t.authorLevel = levelEl.text().trim()
                }

                // === 发布时间 ===
                val timeEl = item.select(".forumlist_li_time .f_d").first()
                if (timeEl != null) {
                    t.publishTime = timeEl.text().trim()
                }

                // === 版块 ===
                val forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first()
                if (forumLink != null) {
                    var forumName = forumLink.ownText().trim()
                    if (forumName.isEmpty()) forumName = forumLink.text().trim()
                    t.forumName = forumName

                    val forumHref = forumLink.attr("href")
                    val fidMatcher = fidPattern.matcher(forumHref)
                    if (fidMatcher.find()) t.forumFid = fidMatcher.group(1)
                }

                // === 内容摘要 ===
                val summaryEl = item.select(".list_body .f_b").first()
                if (summaryEl != null) {
                    val summaryText = summaryEl.text().trim()
                    t.summary = summaryText
                    if (summaryText.contains("本内容被作者隐藏")) {
                        t.hasHiddenContent = true
                    }
                }

                // === 统计信息（点赞/回复/查看） ===
                val stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm")
                if (stats.size >= 3) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                    t.views = parseIntFromText(stats.get(2).text())
                } else if (stats.size >= 2) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                }

                // === 图片检测 ===
                t.hasImage = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img"
                ).size > 0

                // 提取帖子封面图URL
                val imgEl = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img"
                ).first()
                if (imgEl != null) {
                    val imgSrc = firstNonEmptyAttr(
                        imgEl, "comiis_loadimages", "data-original", "data-src",
                        "data-file", "file", "src"
                    )
                    if (!TextUtils.isEmpty(imgSrc)) {
                        val fullUrl = resolveAttachmentUrl(imgSrc)
                        if (isPostImageUrl(fullUrl)) {
                            t.thumbnailUrl = fullUrl
                        }
                    }
                }

                populateThreadImages(item, t)
                threads.add(t)
            } catch (ignored: Exception) {
            }
        }

        return threads
    }

    /**
     * 解析版块内帖子列表（forumdisplay 页面，Comiis App 模板适配）
     * 与 parseThreadList() 使用相同 Comiis 模板选择器
     */
    @JvmStatic
    fun parseForumThreadList(html: String?): MutableList<Thread> {
        val threads = ArrayList<Thread>()
        val doc = Jsoup.parse(html ?: "")

        // Comiis App 模板的帖子容器
        var items = doc.select("li.forumlist_li")
        if (items.isEmpty()) {
            val links = doc.select("a[href*=thread-]")
            for (link in links) {
                val parent = link.closest("li")
                if (parent != null) {
                    items.add(parent)
                }
            }
        }

        val tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html")
        val fidPattern = Pattern.compile("forum-(\\d+)-1\\.html")

        for (item in items) {
            try {
                val t = Thread()

                // === 标题 & tid ===
                var titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first()
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first()
                }
                if (titleLink == null) continue

                val href = titleLink.attr("href")
                val tidMatcher = tidPattern.matcher(href)
                if (!tidMatcher.find()) continue
                t.tid = tidMatcher.group(1)

                // 标题：只取 ownText（排除热度徽章等子元素文本）
                var title = titleLink.ownText().trim()
                if (title.isEmpty()) title = titleLink.text().trim()
                t.title = title

                // === 作者 ===
                val authorEl = item.select(".forumlist_li_top .top_user").first()
                if (authorEl != null) {
                    t.author = authorEl.text().trim()
                    val authorHref = authorEl.attr("href")
                    val m = Pattern.compile("uid=(\\d+)").matcher(authorHref)
                    if (m.find()) t.authorUid = m.group(1)
                }

                // === 头像 ===
                val avatarEl = item.select(".forumlist_li_top .top_tximg").first()
                if (avatarEl != null) {
                    var src = avatarEl.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    t.avatarUrl = src
                }

                // === 等级 ===
                val levelEl = item.select(".forumlist_li_top .top_lev").first()
                if (levelEl != null) {
                    t.authorLevel = levelEl.text().trim()
                }

                // === 发布时间 ===
                val timeEl = item.select(".forumlist_li_time .f_d").first()
                if (timeEl != null) {
                    t.publishTime = timeEl.text().trim()
                }

                // === 版块 ===
                val forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first()
                if (forumLink != null) {
                    var forumName = forumLink.ownText().trim()
                    if (forumName.isEmpty()) forumName = forumLink.text().trim()
                    t.forumName = forumName

                    val forumHref = forumLink.attr("href")
                    val fidMatcher = fidPattern.matcher(forumHref)
                    if (fidMatcher.find()) t.forumFid = fidMatcher.group(1)
                }

                // === 内容摘要 ===
                val summaryEl = item.select(".list_body .f_b").first()
                if (summaryEl != null) {
                    t.summary = summaryEl.text().trim()
                }

                // === 统计信息（点赞/回复/查看） ===
                val stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm")
                if (stats.size >= 3) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                    t.views = parseIntFromText(stats.get(2).text())
                } else if (stats.size >= 2) {
                    t.likes = parseIntFromText(stats.get(0).text())
                    t.replies = parseIntFromText(stats.get(1).text())
                }

                // === 特殊标记 ===
                // 图片检测
                t.hasImage = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img"
                ).size > 0

                // 提取帖子封面图URL
                val imgEl = item.select(
                    ".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img"
                ).first()
                if (imgEl != null) {
                    val imgSrc = firstNonEmptyAttr(
                        imgEl, "comiis_loadimages", "data-original", "data-src",
                        "data-file", "file", "src"
                    )
                    if (!TextUtils.isEmpty(imgSrc)) {
                        val fullUrl = resolveAttachmentUrl(imgSrc)
                        if (isPostImageUrl(fullUrl)) {
                            t.thumbnailUrl = fullUrl
                        }
                    }
                }

                // 隐藏内容检测
                val bodyText = item.select(".list_body .f_b").first()
                if (bodyText != null && bodyText.text().contains("本内容被作者隐藏")) {
                    t.hasHiddenContent = true
                }

                populateThreadImages(item, t)

                // 置顶标记兼容 Comiis 的“热度”徽章和常见 sticky 标记。
                t.isSticky = isStickyThread(item)

                threads.add(t)
            } catch (ignored: Exception) {
            }
        }

        return threads
    }

    private fun isStickyThread(item: Element?): Boolean {
        if (item == null) return false
        if (!item.select(
                "[title*=\"置顶\"], [class*=\"sticky\"], [class*=\"thread_sticky\"], [class*=\"threadtop\"]"
            ).isEmpty()
        ) {
            return true
        }
        for (marker in item.select("span, em, i, label")) {
            val text = marker.text().trim()
            if (text == "置顶" || text.contains("置顶")) return true
        }
        return false
    }

    /**
     * 解析版块分类列表（Comiis App 模板适配）
     * 数据来源：forum.php?forumlist=1&mobile=2
     */
    @JvmStatic
    fun parseForumCategories(html: String?): MutableList<ForumCategory> {
        val categories = ArrayList<ForumCategory>()
        val doc = Jsoup.parse(html ?: "")

        // === 主方案：forumlist 页面结构（forum.php?forumlist=1&mobile=2） ===
        val categoryBlocks = doc.select("div.comiis_forumlist")
        if (categoryBlocks.isEmpty()) {
            // 兜底：尝试发帖弹窗选择结构
            return parseForumCategoriesFromPostDialog(doc)
        }

        val fidPattern = Pattern.compile("forum-(\\d+)-1\\.html")

        for (block in categoryBlocks) {
            try {
                // 分组标题：div.comiis_bbs_show h2 a
                val titleEl = block.select("div.comiis_bbs_show h2 a").first() ?: continue
                val categoryName = titleEl.text().trim()
                if (categoryName.isEmpty()) continue

                // 该分组下的子版块
                val forums = ArrayList<ForumCategory.Forum>()
                val forumLinks = block.select("div.comiis_forum_nbox ul li a[href*=forum-]")
                for (link in forumLinks) {
                    val href = link.attr("href")
                    val m = fidPattern.matcher(href)
                    if (!m.find()) continue
                    val fid = m.group(1)

                    // 版块名称：优先从 img[alt] 获取（更可靠）
                    val img = link.select("img[alt]").first()
                    val fName = if (img != null && !img.attr("alt").isEmpty())
                        img.attr("alt").trim()
                    else link.select("p").text().trim()
                    if (fName.isEmpty()) continue

                    val forum = ForumCategory.Forum(fid, fName)

                    // 版块图标
                    val iconImg = link.select("em img").first()
                    if (iconImg != null) {
                        var src = iconImg.attr("src")
                        if (!src.isEmpty()) {
                            if (!src.startsWith("http")) src = BASE_DOMAIN + src
                            forum.iconUrl = src
                        }
                    }

                    // ★ 今日新帖徽章 <span class="bg_a f_f">860</span>(部分版块无)
                    val todaySpan = link.select("em span.bg_a.f_f").first()
                    if (todaySpan != null) {
                        val today = parseIntFromText(todaySpan.text())
                        forum.todayPosts = today
                        forum.totalThreads = if (today > 0) today else 0
                    }

                    forums.add(forum)
                }

                if (!forums.isEmpty()) {
                    categories.add(ForumCategory(categoryName, forums))
                }
            } catch (ignored: Exception) {
            }
        }

        return categories
    }

    /**
     * 备用解析：发帖弹窗中的版块选择结构（部分 Comiis 版本）
     * 分组 ul 中 li.comiis_fxpostlistkey[fid={gid}] ── 分组标题
     * 对应 ul.comiis_fxpostlistbox_{gid} > li       ── 子版块
     */
    private fun parseForumCategoriesFromPostDialog(doc: Document): MutableList<ForumCategory> {
        val categories = ArrayList<ForumCategory>()

        val groupLis = doc.select("div.comiis_bbslists_gid ul > li.comiis_fxpostlistkey")
        for (groupLi in groupLis) {
            try {
                val gid = groupLi.attr("fid")
                val categoryName = groupLi.select("a").text().trim()
                if (categoryName.isEmpty() || gid.isEmpty()) continue

                val forums = ArrayList<ForumCategory.Forum>()
                val subLis = doc.select("ul.comiis_fxpostlistbox_$gid > li")
                for (subLi in subLis) {
                    val link = subLi.select("a.bbslist_ico[href*=fid=]").first() ?: continue
                    val href = link.attr("href")
                    val fid = extractParam(href, "fid") ?: continue

                    // 版块名称：优先 img[alt]
                    val img = link.select("img[alt]").first()
                    var fName = if (img != null) img.attr("alt").trim() else ""
                    if (fName.isEmpty()) {
                        val nameEl = subLi.select("a.post_tit em").first()
                        if (nameEl != null) fName = nameEl.text().trim()
                    }
                    if (fName.isEmpty()) continue

                    val forum = ForumCategory.Forum(fid, fName)

                    if (img != null) {
                        var src = img.attr("src")
                        if (!src.isEmpty()) {
                            if (!src.startsWith("http")) src = BASE_DOMAIN + src
                            forum.iconUrl = src
                        }
                    }

                    // ★ 今日新帖徽章
                    val todaySpan = subLi.select("em span.bg_a.f_f").first()
                    if (todaySpan != null) {
                        val today = parseIntFromText(todaySpan.text())
                        forum.todayPosts = today
                        forum.totalThreads = if (today > 0) today else 0
                    }

                    forums.add(forum)
                }

                if (!forums.isEmpty()) {
                    categories.add(ForumCategory(categoryName, forums))
                }
            } catch (ignored: Exception) {
            }
        }

        return categories
    }

    /**
     * 检测服务器返回的 HTML 是否为登录页（Cookie 已过期/未登录）
     * 通过检查 Discuz! 登录表单特征来判断
     */
    @JvmStatic
    fun isLoginPage(html: String?): Boolean {
        if (TextUtils.isEmpty(html)) return true
        val doc = Jsoup.parse(html!!)

        // 明确登录页特征必须优先判断。登录页通常仍包含首页/热帖 thread- 链接，
        // 若先判断“多个 thread 链接”，会把登录页误判为已登录收藏页。
        val loginForm = doc.select(
            "form[method=post][action*=login], form[method=post][action*=logging]"
        ).first()
        if (loginForm != null) return true
        val pageTitle = doc.title().lowercase()
        if (pageTitle.contains("登录") || pageTitle.contains("login")) return true
        if (doc.select("input[type=password]").size > 0) return true

        // === 正向确认：页面含有已登录特征 → 快速返回 false ===

        // 特征A：好友/粉丝列表 — 存在多个 a[href*=uid=] 链接（含用户名文本）
        val uidLinks = doc.select("a[href*=uid=]")
        if (uidLinks.size >= 2) {
            var hasUsernames = false
            for (link in uidLinks) {
                if (!TextUtils.isEmpty(link.text().trim())) {
                    hasUsernames = true
                    break
                }
            }
            if (hasUsernames) return false
        }

        // 特征B：收藏/帖子列表 — 存在多个 a[href*=thread-] 链接（含标题文本）
        val threadLinks = doc.select("a[href*=thread-]")
        if (threadLinks.size >= 2) {
            var hasTitles = false
            for (link in threadLinks) {
                if (!TextUtils.isEmpty(link.text().trim())) {
                    hasTitles = true
                    break
                }
            }
            if (hasTitles) return false
        }

        // 特征C：帖子列表（Comiis 模板结构）
        if (doc.select("li.forumlist_li").size > 0) return false

        // 特征D：个人空间/资料页
        if (doc.select(".comiis_space_tx, .comiis_space_profile, .comiis_space_profileico, .comiis_space_profilejf").size > 0) {
            return false
        }

        // 走到这里：没有任何已登录特征，也没有明确的登录页特征
        return false
    }

    /**
     * 解析网页端关注状态。
     *
     * @return 1=已关注，0=未关注，-1=网页未提供可确认状态
     */
    @JvmStatic
    fun parseFollowState(html: String?): Int {
        if (TextUtils.isEmpty(html)) return -1
        val doc = Jsoup.parse(html!!)
        val buttons = doc.select(
            ".comiis_space_flw a#followmod, .comiis_space_flw a.followmod, " +
                    ".comiis_space_tx a#followmod, .comiis_space_tx a.followmod, " +
                    "a#followmod, a.followmod"
        )
        if (buttons.isEmpty()) return -1

        var sawAdd = false
        var sawBg0 = false
        for (button in buttons) {
            // Jsoup 会把 &amp; 还原，但这里再统一解码一次，兼容部分页面把链接编码在属性中。
            val href = button.attr("href").replace("&amp;", "&").lowercase()
            val text = button.text().trim()
            val classes = button.className().lowercase()

            // Comiis 已关注按钮的服务端/JS状态：op=del、已关注/取消关注、bg_b。
            if (href.contains("op=del") || text.contains("已关注")
                || text.contains("取消关注") || classes.contains("bg_b")
            ) {
                return 1
            }
            // 未关注按钮的状态：op=add；未登录提示也只能表示当前未建立关注关系。
            if (href.contains("op=add") || href.contains("logging&action=login")
                || href.contains("logging%26action%3dlogin")
            ) {
                sawAdd = true
            }
            // 登录后的 Comiis 页面有时只保留 bg_0，不输出 op=add href。
            if (classes.contains("bg_0")) sawBg0 = true
        }
        if (sawAdd || sawBg0) return 0
        return -1
    }

    /** 判断关注列表页面是否包含可识别的用户条目或明确的空列表标记。 */
    @JvmStatic
    fun isFollowingListPage(html: String?): Boolean {
        if (TextUtils.isEmpty(html)) return false
        val doc = Jsoup.parse(html!!)
        if (!doc.select(".comiis_userlist01, .comiis_friend_boxs, .comiis_follow_box, .comiis_followlist").isEmpty()) {
            return true
        }
        // 不同版本模板可能省略外层 class，但仍会保留关注列表语义和用户条目。
        val bodyText = if (doc.body() == null) "" else doc.body().text()
        val hasFollowTitle = bodyText.contains("我的关注") || bodyText.contains("关注列表")
                || bodyText.contains("正在关注") || bodyText.contains("关注的人")
        val hasUserItem = !doc.select("li a[href*=uid=], a[href*=home.php?mod=space&uid=]").isEmpty()
        val hasEmptyMarker = bodyText.contains("暂无关注") || bodyText.contains("还没有关注")
                || bodyText.contains("没有关注")
        return hasFollowTitle && (hasUserItem || hasEmptyMarker)
    }

    /**
     * 解析用户信息
     * 适配 Comiis App 模板（bbs.binmt.cc 使用的第三方 Discuz! 模板）
     */
    @JvmStatic
    fun parseUserProfile(html: String?): UserProfile {
        val profile = UserProfile()
        val doc = Jsoup.parse(html ?: "")

        // ============================================================
        // 1. 头部信息区：div.comiis_space_info > div.comiis_space_tx
        // ============================================================

        // 用户名：Comiis 模板使用 h2.fyy；兜底保留通用选择器
        val usernameEl = doc.select(".comiis_space_tx h2, .username, .user_name, h1, .profile_name").first()
        if (usernameEl != null) profile.username = usernameEl.text().trim()

        // 头像：★ 修复 — 必须限定在 .comiis_space_tx 范围内，否则会匹配到导航栏中的当前用户头像
        var avatarEl = doc.select(".comiis_space_tx .user_img img, .comiis_space_tx img[src*=avatar], .comiis_space_tx img").first()
        // 兜底：从 avatar.php?uid=X 直接构造（最可靠）
        if (avatarEl == null) {
            avatarEl = doc.select("img[src*=avatar]").first()
        }
        if (avatarEl != null) {
            var src = avatarEl.attr("src")
            if (!src.startsWith("http")) src = BASE_DOMAIN + src
            profile.avatarUrl = src
        } else {
            // 最后兜底：如果解析到了UID，用 UID 构造头像 URL
            if (profile.uid != null && !profile.uid!!.isEmpty()) {
                profile.avatarUrl = BASE_DOMAIN + "uc_server/avatar.php?uid=" + profile.uid + "&size=middle"
            }
        }

        // UID：从 "用户ID" 行前的 div.profile_rs 获取
        val uidEl = doc.select(".comiis_space_profile li:contains(用户ID) .profile_rs, .uid, em:contains(UID)").first()
        if (uidEl != null) {
            val text = uidEl.text().trim()
            val m = Pattern.compile("(\\d+)").matcher(text)
            if (m.find()) profile.uid = m.group(1)
        }

        // 等级：Comiis 模板使用 span.kmlevs.kmlv（包含 Lv.X 文字）
        // ★ 修复：选择器优先级问题——第一个 .kmlevs 是性别图标（bg_boy/girl），不是等级。
        val levelEl = doc.select(".comiis_space_tx .kmlevs.kmlv, .comiis_space_tx .kmlv, .level, .lv, em:contains(Lv)").first()
        if (levelEl != null) profile.level = levelEl.text().trim()

        // 用户组：Comiis 模板使用 span.kmlev（如 "硕士生"）
        val groupEl = doc.select(".comiis_space_tx .kmlev").first()
        if (groupEl != null) profile.groupName = groupEl.text().trim()

        // ============================================================
        // 1.1 当前登录用户对该用户的关注状态
        // ============================================================
        val followState = parseFollowState(html)
        if (followState >= 0) {
            profile.followed = followState == 1
            profile.followStateKnown = true
        }

        // ============================================================
        // 2. 统计数据图标区：div.comiis_space_profileico
        // ============================================================

        // 帖子数
        val threadEl = doc.select(".comiis_space_profileico li:contains(帖子) span, em:contains(帖子) + span, .thread_count").first()
        if (threadEl != null) profile.threads = parseIntFromText(threadEl.text())

        // 回复数 / 帖子数（UserProfile 有 posts 字段）
        val postsEl = doc.select(".comiis_space_profileico li:contains(回复) span, em:contains(回复) + span, .posts, .user_posts").first()
        if (postsEl != null) profile.posts = parseIntFromText(postsEl.text())

        // 好友
        val friendEl = doc.select(".comiis_space_profileico li:contains(好友) span, em:contains(好友) + span, .friend_count").first()
        if (friendEl != null) profile.friends = parseIntFromText(friendEl.text())

        // 关注数位于 .comiis_space_tx 顶部摘要中，例如“2 关注”。
        var followingEl: Element? = null
        for (span in doc.select(".comiis_space_tx p span")) {
            val text = span.text().trim()
            if (text.matches(Regex(".*\\d[\\d,，]*\\s*关注$"))) {
                followingEl = span
                break
            }
        }
        if (followingEl == null) {
            followingEl = doc.select(".comiis_space_profileico li:contains(关注) span, em:contains(关注) + span, .following_count").first()
        }
        if (followingEl != null) {
            profile.following = parseIntFromText(followingEl.text())
        }

        // 粉丝
        val followerEl = doc.select(".comiis_space_profileico li:contains(粉丝) span, em:contains(粉丝) + span, .follower_count").first()
        if (followerEl != null) profile.followers = parseIntFromText(followerEl.text())

        // 人气/浏览
        val viewsEl = doc.select(".comiis_space_profileico li:contains(人气) span").first()
        if (viewsEl != null) profile.views = parseIntFromText(viewsEl.text())

        // ============================================================
        // 3. 积分/金币区域：div.comiis_space_profilejf
        // ============================================================
        val profileJfLis = doc.select(".comiis_space_profilejf ul li")

        // 积分（第1个 li）
        if (profileJfLis.size >= 1) {
            var creditsEl = profileJfLis.get(0).select(".f_0, span").first()
            if (creditsEl == null) creditsEl = profileJfLis.get(0)
            profile.credits = parseIntFromText(creditsEl.text())
        }

        // 金币（第3个 li）
        if (profileJfLis.size >= 3) {
            var goldEl = profileJfLis.get(2).select(".f_0, span").first()
            if (goldEl == null) goldEl = profileJfLis.get(2)
            profile.gold = parseIntFromText(goldEl.text())
        }

        // ============================================================
        // 4. 详细资料区：div.comiis_space_profile
        // ============================================================

        // 注册时间
        val regLi = doc.select(".comiis_space_profile li:contains(注册时间)").first()
        if (regLi != null) {
            val regVal = regLi.select(".profile_rs").first()
            if (regVal != null) profile.regDate = regVal.text().trim()
        } else {
            val regEl = doc.select("li:contains(注册时间), .regdate").first()
            if (regEl != null) profile.regDate = regEl.text().replace("注册时间:", "").trim()
        }

        // 最后访问时间
        val lastVisitLi = doc.select(".comiis_space_profile li:contains(最后访问)").first()
        if (lastVisitLi != null) {
            val lastVisitVal = lastVisitLi.select(".profile_rs").first()
            if (lastVisitVal != null) profile.lastVisit = lastVisitVal.text().trim()
        }

        // 在线时间
        val onlineLi = doc.select(".comiis_space_profile li:contains(在线时间)").first()
        if (onlineLi != null) {
            val onlineVal = onlineLi.select(".profile_rs").first()
            if (onlineVal != null) profile.onlineTime = onlineVal.text().trim()
        }

        // 性别
        val genderLi = doc.select(".comiis_space_profile li:contains(性别)").first()
        if (genderLi != null) {
            val genderVal = genderLi.select(".profile_rs").first()
            if (genderVal != null) {
                val g = genderVal.text().trim()
                if (g.contains("男")) profile.gender = "boy"
                else if (g.contains("女")) profile.gender = "girl"
            }
        }

        // ============================================================
        // 5. 签名区
        // ============================================================
        val sigEl = doc.select(".profile_face, .signature, .sigin").first()
        if (sigEl != null) profile.signature = sigEl.text().trim()

        return profile
    }

    /**
     * 解析消息/私信列表
     */
    @JvmStatic
    fun parseMessageList(html: String?): MutableList<Message> {
        val messages = ArrayList<Message>()
        val doc = Jsoup.parse(html ?: "")

        val items = doc.select("li.pm, li.msg, div.message_item, li[data-pmid]")
        for (item in items) {
            try {
                val msg = Message()

                val link = item.select("a[href*=pmid=], a[href*=do=pm]").first()
                if (link != null) {
                    val pmid = extractParam(link.attr("href"), "pmid")
                    if (pmid != null) msg.pmid = pmid
                }

                val titleEl = item.select(".title, .subject, h4").first()
                if (titleEl != null) msg.title = titleEl.text().trim()

                val authorEl = item.select(".author, .by, .from").first()
                if (authorEl != null) msg.author = authorEl.text().trim()

                val timeEl = item.select(".time, .date, .dateline").first()
                if (timeEl != null) msg.time = timeEl.text().trim()

                val summaryEl = item.select(".summary, .message_preview, p").first()
                if (summaryEl != null) msg.summary = summaryEl.text().trim()

                val avatarEl = item.select("img[src*=avatar]").first()
                if (avatarEl != null) msg.avatarUrl = avatarEl.attr("src")

                msg.isRead = item.select("em:contains(已读), .read").size > 0

                messages.add(msg)
            } catch (ignored: Exception) {
            }
        }

        return messages
    }

    /**
     * 解析 Comiis 移动版通知条目。
     *
     * 真实结构：li.b_b.bg_f.cl > .ntc_body + h2.f_d。
     * Comiis 移动模板不会使用桌面版的 div.nts/dl.cl，因此必须单独处理。
     */
    private fun parseComiisNoticeItems(items: Elements): MutableList<Message> {
        val notices = ArrayList<Message>()
        for (item in items) {
            try {
                val body = item.select("div.ntc_body").first() ?: continue

                val notice = Message()
                notice.type = 0

                // 某些版本会给未读条目添加 ntc_l/new/unread，已读条目添加 read/old。
                // 没有状态类时按已读处理，避免把历史通知全部误报成未读。
                val unread = item.hasClass("ntc_l") || item.hasClass("new")
                        || item.hasClass("unread") || item.hasClass("un_read")
                        || "0" == item.attr("data-read")
                        || "unread".equals(item.attr("data-status"), ignoreCase = true)
                var read = !unread
                if (item.hasClass("read") || item.hasClass("old")
                    || "1" == item.attr("data-read")
                ) read = true
                notice.isRead = read

                // 移动版没有 notice 属性，使用屏蔽链接 id 中的通知 ID。
                val ignore = item.select("h2 a[id^=a_note_]").first()
                if (ignore != null) {
                    val idMatcher = Pattern.compile("a_note_(\\d+)").matcher(ignore.id())
                    if (idMatcher.find()) notice.pmid = idMatcher.group(1)
                }

                val author = body.select("a[href*=space-uid-], a[href*=uid=]").first()
                if (author != null) {
                    notice.author = author.text().trim()
                    val href = author.attr("href")
                    val uidMatcher = Pattern.compile("space-uid-(\\d+)").matcher(href)
                    if (uidMatcher.find()) notice.authorUid = uidMatcher.group(1)
                    else {
                        val uid = extractParam(href, "uid")
                        if (!TextUtils.isEmpty(uid)) notice.authorUid = uid
                    }
                }

                val avatar = item.select("a.notice_img img[src*=avatar], img[src*=avatar], img[src*=uc_server]").first()
                if (avatar != null) {
                    var src = avatar.attr("abs:src")
                    if (TextUtils.isEmpty(src)) src = avatar.attr("src")
                    if (!TextUtils.isEmpty(src) && !src.startsWith("http")) src = BASE_DOMAIN + src
                    notice.avatarUrl = src
                }

                var titleLink = body.select("a[href*=forum.php?mod=redirect]").first()
                if (titleLink == null) titleLink = body.select("a[href*=thread-]").first()
                if (titleLink != null) notice.summary = titleLink.text().trim()

                var fullText = body.text().replace('\u00a0', ' ').trim()
                if (fullText.length > 200) fullText = fullText.substring(0, 200)
                notice.title = fullText

                if (titleLink != null) {
                    val tidMatcher = Pattern.compile("(?:ptid=|thread-)(\\d+)").matcher(titleLink.attr("href"))
                    if (tidMatcher.find()) {
                        val summary = notice.summary
                        notice.summary = (if (summary == null) "" else summary) + " tid=" + tidMatcher.group(1)
                    }
                }

                val time = item.select("h2.f_d").first()
                if (time != null) {
                    var timeText = time.ownText().replace('\u00a0', ' ').trim()
                    if (TextUtils.isEmpty(timeText)) timeText = time.text().replace('\u00a0', ' ').trim()
                    notice.time = timeText
                }

                if (fullText.contains("回复了您的") || fullText.contains("评论了您的")) {
                    notice.type = 2
                } else if (fullText.contains("系统") || fullText.contains("管理")
                    || fullText.contains("删除")
                ) {
                    notice.type = 1
                }
                notices.add(notice)
            } catch (ignored: Exception) {
            }
        }
        return notices
    }

    /**
     * 解析通知列表（桌面版 div.nts > dl.cl 结构；Comiis 移动版自动转交）
     */
    @JvmStatic
    fun parseNoticeList(html: String?): MutableList<Message> {
        val notices = ArrayList<Message>()
        if (TextUtils.isEmpty(html)) return notices

        val doc = Jsoup.parse(html!!)

        // Comiis 移动版通知页面使用 li.b_b.bg_f.cl；优先走移动版解析器。
        val mobileItems = doc.select("li.b_b.bg_f.cl")
        if (!mobileItems.isEmpty()) {
            return parseComiisNoticeItems(mobileItems)
        }

        // 检查是否有“没有提醒内容”
        if (html.contains("没有提醒内容") || html.contains("暂无提醒")
            || html.contains("没有新的") || html.contains("notip")
        ) {
            return notices
        }

        // ★ 核心选择器：桌面版通知使用 dl.cl 元素，位于 div.nts 容器内
        var items = doc.select("div.nts > dl.cl")
        if (items.isEmpty()) {
            // 兜底：直接查找所有带 notice 属性的 dl
            items = doc.select("dl.cl[notice]")
        }
        if (items.isEmpty()) {
            // 最终兜底：查找所有 dl.cl
            items = doc.select("dl.cl")
        }

        for (item in items) {
            try {
                val notice = Message()
                notice.type = 0

                // ★ 判断已读/未读：桌面版 Discuz! 未读通知的 dl.cl 含有 ntc_l 类
                notice.isRead = !item.hasClass("ntc_l")

                // ★ 提取 notice ID（用于删除操作）
                val noticeId = item.attr("notice")
                if (!TextUtils.isEmpty(noticeId)) {
                    notice.pmid = noticeId
                }

                // ★ 提取发送者 UID（用于屏蔽操作）
                var avatarLink = item.select("dd.avt a[href*=space-uid-]").first()
                if (avatarLink == null) {
                    avatarLink = item.select("dd.m a[href*=uid=]").first()
                }
                if (avatarLink != null) {
                    val m = Pattern.compile("uid=(\\d+)").matcher(avatarLink.attr("href"))
                    if (m.find()) {
                        notice.authorUid = m.group(1)
                    }
                }

                // ★ 头像
                val avatarImg = item.select("dd.avt img[src*=avatar], img[src*=uc_server]").first()
                if (avatarImg != null) {
                    var src = avatarImg.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    notice.avatarUrl = src
                }

                // ★ 时间
                var timeEl = item.select("dt span.xg1 span[title], dt span.xg1").first()
                if (timeEl == null) {
                    timeEl = item.select("span[title]").first()
                }
                if (timeEl != null) {
                    var timeText = timeEl.attr("title")
                    if (TextUtils.isEmpty(timeText)) {
                        timeText = timeEl.text().trim()
                    }
                    notice.time = timeText
                }

                // ★ 通知正文（ntc_body）
                val bodyEl = item.select("dd.ntc_body").first() ?: continue

                // 发送者用户名（第一个 a[href*=space-uid-] 或 a[href*=uid=]）
                val authorEl = bodyEl.select("a[href*=space-uid-], a[href*=uid=]").first()
                if (authorEl != null) {
                    notice.author = authorEl.text().trim()
                }

                // 帖子标题（指向 forum.php?mod=redirect 的链接）
                val titleLink = bodyEl.select("a[href*=forum.php?mod=redirect]").first()
                if (titleLink != null) {
                    val title = titleLink.text().trim()
                    // 去除"回复了您的帖子"等前缀
                    notice.summary = title
                }

                // ★ 完整通知文本作为标题（如 "ruolin 回复了您的帖子 XXX"）
                var fullText = bodyEl.text().trim()
                // 去除"查看"等操作文字
                fullText = Regex("查看\\s*$").replace(fullText, "").trim()
                if (fullText.length > 200) fullText = fullText.substring(0, 200)
                notice.title = fullText

                // ★ 提取帖子 tid（用于点击跳转）
                val postLink = bodyEl.select("a[href*=ptid=]").first()
                if (postLink != null) {
                    val href = postLink.attr("href")
                    val m = Pattern.compile("ptid=(\\d+)").matcher(href)
                    if (m.find()) {
                        notice.summary = (if (notice.summary != null) notice.summary + " " else "") + "tid=" + m.group(1)
                    }
                }

                // ★ 判断通知类型
                if (fullText.contains("回复了您的帖子") || fullText.contains("回复了您的") || fullText.contains("评论了您的")) {
                    notice.type = 2 // 回复提醒
                } else if (fullText.contains("删除") || fullText.contains("系统") || fullText.contains("管理")) {
                    notice.type = 1 // 系统通知
                }

                notices.add(notice)
            } catch (ignored: Exception) {
            }
        }

        return notices
    }

    /**
     * 解析私信列表（Comiis App 模板 home.php?mod=space&do=pm&mobile=2 页面）
     */
    @JvmStatic
    fun parsePmList(html: String?): MutableList<Message> {
        val messages = ArrayList<Message>()
        if (TextUtils.isEmpty(html)) return messages

        val doc = Jsoup.parse(html!!)

        // 主方案：Comiis App 模板结构 div.comiis_pmlist > ul > li
        var items = doc.select("div.comiis_pmlist ul > li")
        // 兜底：尝试直接查找 li 或 a.b_b
        if (items.isEmpty()) {
            items = doc.select("li.b_b, li.pm, li[data-pmid]")
        }
        // 再兜底：从 subop=view 链接向上查找父 li
        if (items.isEmpty()) {
            val pmLinks = doc.select("a[href*=subop=view]")
            for (pmLink in pmLinks) {
                val parent = pmLink.closest("li")
                if (parent != null) items.add(parent)
            }
        }

        for (item in items) {
            try {
                val msg = Message()

                // 主链接 (a.b_b 或 a[href*=subop=view])
                var link = item.select("a.b_b, a[href*=subop=view]").first()
                if (link == null) link = item.select("a[href*=touid=], a[href*=uid=]").first()
                if (link == null) link = item.select("a").first()
                if (link == null) continue

                val href = link.attr("href")

                // 会话ID = touid（Comiis 模板用 touid 标识会话）
                var touid = extractParam(href, "touid")
                if (touid == null) touid = extractParam(href, "uid")
                msg.pmid = touid // 将 touid 存入 pmid 字段，用于 ChatActivity 跳转

                // 对方UID（与会话ID相同）
                msg.authorUid = touid

                // 用户名：从 h2 中提取（排除 span.f_d 的时间部分）
                val h2 = link.select("h2").first()
                if (h2 != null) {
                    var fullText = h2.text().trim()
                    val timeSpan = h2.select("span.f_d").first()
                    if (timeSpan != null) {
                        val timeText = timeSpan.text().trim()
                        fullText = fullText.replace(timeText, "").trim()
                    }
                    msg.author = fullText
                } else {
                    // 兜底：从 a[href*=touid=] 文本提取
                    val linkText = link.text().trim()
                    if (!linkText.isEmpty()) msg.author = linkText
                }

                // 保存网页端删除按钮生成的真实 open_href，Comiis 删除接口依赖其中的 formhash。
                val deleteEl = item.select("a.kmdel[open_href]").first()
                if (deleteEl != null) {
                    var deleteUrl = deleteEl.attr("abs:open_href")
                    if (TextUtils.isEmpty(deleteUrl)) deleteUrl = deleteEl.attr("open_href")
                    msg.deleteUrl = deleteUrl
                }

                // 消息预览 (p.f_c)
                val previewEl = link.select("p.f_c").first()
                if (previewEl != null) msg.summary = previewEl.text().trim()

                // 标题（预览内容作为标题）
                val summary = msg.summary
                msg.title = summary ?: ""

                // 时间 (span.f_d)
                val timeEl = link.select("span.f_d").first()
                if (timeEl != null) msg.time = timeEl.text().trim()

                // 头像 (img[src*=avatar.php])
                val avatarEl = link.select("img[src*=avatar]").first()
                if (avatarEl != null) {
                    var src = avatarEl.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    msg.avatarUrl = src
                }

                // 已读/未读：Comiis 模板未读/已读通常通过 li 的 class 或子元素标记
                val isUnread = item.hasClass("new") || item.hasClass("unread")
                        || item.select(".new, .unread, .un_read, .no_read").size > 0
                // 没有明确标记时默认未读
                msg.isRead = !isUnread

                messages.add(msg)
            } catch (ignored: Exception) {
            }
        }

        return messages
    }

    /** 从聊天页标题读取 Comiis 的实时在线状态。 */
    @JvmStatic
    fun parseChatOnlineStatus(html: String?): String {
        if (TextUtils.isEmpty(html)) return "离线"
        val doc = Jsoup.parse(html!!)
        val title = doc.select(".comiis_head h2, #comiis_head h2, h2.flex").first()
        val text = if (title != null) title.text().trim() else doc.title()
        if (text.contains("在线")) return "在线"
        if (text.contains("离线")) return "离线"
        return "离线"
    }

    /**
     * 解析私信会话详情（Comiis App 模板）。
     */
    @JvmStatic
    fun parseChatMessages(html: String?, selfUid: String?, fallbackAvatar: String?): MutableList<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        if (TextUtils.isEmpty(html)) return result
        val doc = Jsoup.parse(html!!)
        val seen = HashSet<String>()

        // 主方案：Comiis 模板结构
        var items = doc.select("div.comiis_friend_msg, div.comiis_self_msg")
        // 兜底：原有标准选择器
        if (items.isEmpty()) {
            items = doc.select("div.pmbox, div.pm_c, div.pm_body, li.pm, li.pm_list, li.cl, .comiis_pm, .comiis_pmitem, [data-pmid]")
        }

        for (item in items) {
            try {
                val isOutgoing = item.hasClass("comiis_self_msg")

                // 消息内容：div.msg_mes
                var body = item.select("div.msg_mes").first()
                if (body == null) {
                    // 兜底：原有选择器
                    body = item.select(".pm_content, .pm_body, .message, .content, .pmbox_content, .t_f, .msgtext, .f_c").first()
                }
                if (body == null) body = item
                val content = body.text().trim()
                if (content.isEmpty() || content.length > 5000) continue

                // 头像链接：img.msg_avt 或 img[src*=avatar]
                val avatarEl = item.select("img.msg_avt, img[src*=avatar], img[src*=uc_server], img.top_tximg").first()
                var avatar = if (avatarEl != null) avatarEl.attr("src") else fallbackAvatar
                if (avatar != null && !avatar!!.isEmpty() && !avatar!!.startsWith("http")) avatar = BASE_DOMAIN + avatar

                // 对方UID：从头像/用户链接中提取
                val userLink = item.select("a[href*=uid=]").first()
                val authorUid = if (userLink != null) extractParam(userLink.attr("href"), "uid") else null

                // 日期分隔线：读取当前消息之前最近的 comiis_msg_date。
                var date = ""
                var previous = item.previousElementSibling()
                while (previous != null) {
                    if (previous.hasClass("comiis_msg_date")) {
                        date = previous.text().trim()
                        break
                    }
                    previous = previous.previousElementSibling()
                }

                // 时间：div.msg_time.f_d
                val timeEl = item.select("div.msg_time.f_d, .time, .date, .dateline, .kmtime, .pm_time, .f_g").first()
                val time = if (timeEl != null) timeEl.text().trim() else ""

                // 去重键
                val key = (if (isOutgoing) "self" else "friend") + "|" + time + "|" + content
                if (!seen.add(key)) continue

                val message = ChatMessage()
                message.content = content
                message.avatarUrl = avatar
                message.authorUid = authorUid
                message.date = date
                message.time = time
                // 方向判断：comiis_self_msg = 自己发送 (outgoing=true)
                message.outgoing = isOutgoing

                // 作者名：从头像链接的 alt 获取，或直接使用 selfUid 判断
                if (isOutgoing) {
                    message.author = selfUid // 自己
                } else {
                    val img = item.select("img[alt]").first()
                    val author = if (img != null) img.attr("alt").trim() else ""
                    message.author = author
                }

                result.add(message)
            } catch (ignored: Exception) {
            }
        }
        return result
    }

    /**
     * 解析粉丝列表为 Message。
     *
     * Comiis 粉丝页真实结构：
     * div.comiis_userlist01 > li.b_t
     */
    @JvmStatic
    fun parseFollowerList(html: String?): MutableList<Message> {
        val followers = ArrayList<Message>()
        if (TextUtils.isEmpty(html)) return followers

        val doc = Jsoup.parse(html!!)
        var items = doc.select("div.comiis_userlist01 > li.b_t")
        if (items.isEmpty()) {
            // 兼容部分 Comiis 页面省略外层容器的情况。
            items = doc.select("li.b_t")
        }

        val seenUids = HashSet<String>()
        for (item in items) {
            try {
                var userLink = item.select("p.tit > a[href*=uid=]").first()
                if (userLink == null) userLink = item.select("a[href*=uid=]").first()
                if (userLink == null) continue

                val href = userLink.attr("href")
                var uid = extractParam(href, "uid")
                if (TextUtils.isEmpty(uid)) {
                    val uidMatcher = Pattern.compile("(?:^|[?&])uid=(\\d+)").matcher(href)
                    if (uidMatcher.find()) uid = uidMatcher.group(1)
                }
                if (TextUtils.isEmpty(uid) || !seenUids.add(uid!!)) continue

                val author = userLink.text().trim()
                if (TextUtils.isEmpty(author)) continue

                val follower = Message()
                follower.type = 1
                follower.pmid = uid
                follower.authorUid = uid
                follower.author = author
                follower.summary = "关注了你"
                follower.title = author + " 关注了你"
                follower.time = ""
                follower.isRead = true

                val avatarEl = item.select("a.list01_limg img[src*=avatar], a.list01_limg img, img[src*=avatar]").first()
                if (avatarEl != null) {
                    var src = avatarEl.attr("abs:src")
                    if (TextUtils.isEmpty(src)) src = avatarEl.attr("src")
                    if (!TextUtils.isEmpty(src) && !src.startsWith("http")) {
                        src = BASE_DOMAIN + (if (src.startsWith("/")) src.substring(1) else src)
                    }
                    follower.avatarUrl = src
                }

                followers.add(follower)
            } catch (ignored: Exception) {
            }
        }
        return followers
    }

    /**
     * 代码块标准化:将 Comiis/Discuz 移动版代码块转换为统一的 <pre> 结构。
     *
     * 真实页面存在两种结构:
     *   1. 移动版:<div class="comiis_blockcode"><div><ol><li>行1</li><li>行2</li></ol></div></div>
     *   2. 桌面版/标准:<pre>...</pre>
     *
     * 转换结果:<pre class="comiis_blockcode">行号+空格+内容<br>...</pre>
     */
    @JvmStatic
    fun normalizeCodeBlocks(html: String?): String? {
        if (html == null || html.isEmpty()) return html
        if (html.indexOf("comiis_blockcode") < 0 && html.indexOf("<pre") < 0
            && html.indexOf("blockcode") < 0
        ) {
            return html
        }
        try {
            val doc = Jsoup.parseBodyFragment(html)
            // 1. 裸 <pre>:保留结构,仅转义空格
            for (pre in doc.select("pre")) {
                escapeSpacesInTree(pre)
            }
            // 2. div.comiis_blockcode 与 div.blockcode:提取行并重建为带两位对齐行号的 <pre>
            for (code in doc.select("div.comiis_blockcode, div.blockcode")) {
                var target = code
                val lines = ArrayList<String>()
                val lis = code.select("ol > li")
                if (!lis.isEmpty()) {
                    var lineNo = 1
                    for (li in lis) {
                        val t = collectRawText(li).replace('\u00a0', ' ').trim()
                        if (t.isEmpty()) {
                            lineNo++
                            continue
                        }
                        val lineMatcher = Pattern.compile("^([0-9]{1,3})\\.?\\s*(.*)$").matcher(t)
                        val numStr: String
                        val codeText: String
                        if (lineMatcher.find()) {
                            val n = lineMatcher.group(1)?.toIntOrNull() ?: lineNo
                            numStr = String.format(Locale.US, "%02d.", n)
                            codeText = lineMatcher.group(2) ?: ""
                        } else {
                            numStr = String.format(Locale.US, "%02d.", lineNo)
                            codeText = t
                        }
                        val escapedCode = escapeHtml(codeText).replace(" ", "&nbsp;")
                        lines.add("<font color=\"#94A3B8\">$numStr</font>&nbsp;&nbsp;$escapedCode")
                        lineNo++
                    }
                } else {
                    val inner = code.selectFirst("pre")
                    if (inner != null) target = inner
                    val raw = splitLinesByBr(target)
                    var lineNo = 1
                    for (s in raw) {
                        val t = s.replace('\u00a0', ' ').trim()
                        if (t.isEmpty()) {
                            lineNo++
                            continue
                        }
                        val lineMatcher = Pattern.compile("^([0-9]{1,3})\\.?\\s*(.*)$").matcher(t)
                        val numStr: String
                        val codeText: String
                        if (lineMatcher.find()) {
                            val n = lineMatcher.group(1)?.toIntOrNull() ?: lineNo
                            numStr = String.format(Locale.US, "%02d.", n)
                            codeText = lineMatcher.group(2) ?: ""
                        } else {
                            numStr = String.format(Locale.US, "%02d.", lineNo)
                            codeText = t
                        }
                        val escapedCode = escapeHtml(codeText).replace(" ", "&nbsp;")
                        lines.add("<font color=\"#94A3B8\">$numStr</font>&nbsp;&nbsp;$escapedCode")
                        lineNo++
                    }
                }
                val sb = StringBuilder("<br><pre class=\"comiis_blockcode\">")
                for (line in lines) {
                    sb.append(line).append("<br>")
                }
                sb.append("</pre><br>")
                val parsed = Jsoup.parseBodyFragment(sb.toString()).body()
                val nodes = ArrayList(parsed.childNodes())
                for (node in nodes) {
                    code.before(node)
                }
                code.remove()
            }
            return doc.body().html()
        } catch (e: Exception) {
            return html
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /** 收集元素下所有文本节点原文(保留 \u00a0、连续空格,不做空白规范化) */
    private fun collectRawText(el: Element): String {
        val sb = StringBuilder()
        collectRawTextNode(el, sb)
        return sb.toString()
    }

    private fun collectRawTextNode(node: Node, sb: StringBuilder) {
        if (node is TextNode) {
            sb.append(node.wholeText)
        } else if (node is Element) {
            for (child in node.childNodes()) {
                collectRawTextNode(child, sb)
            }
        }
    }

    /** 将节点树内所有文本节点的普通空格转为 &nbsp;,防止 Html 渲染时被合并 */
    private fun escapeSpacesInTree(root: Element) {
        for (node in root.childNodes()) {
            if (node is TextNode) {
                val text = node.wholeText
                if (text.indexOf(' ') >= 0) {
                    node.text(text.replace(" ", "\u00a0"))
                }
            } else if (node is Element) {
                escapeSpacesInTree(node)
            }
        }
    }

    /** 按 <br> 拆分元素文本为多行(保留子树文本) */
    private fun splitLinesByBr(root: Element): MutableList<String> {
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        for (node in root.childNodes()) {
            if (node is TextNode) {
                cur.append(node.wholeText)
            } else if (node.nodeName() == "br") {
                lines.add(cur.toString())
                cur.setLength(0)
            } else if (node is Element) {
                cur.append(node.text())
            }
        }
        lines.add(cur.toString())
        return lines
    }

    /** 普通空格转 &nbsp;,保留  原样 */
    private fun escapeNbsp(text: String): String {
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val ch = text[i]
            if (ch == ' ') sb.append("&nbsp;")
            else sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * 解析帖子详情页（Comiis App 模板适配，viewthread 页面）
     *
     * @param html 帖子详情页 HTML
     * @return PostDetail 对象，包含帖主信息、正文、回复列表等
     */
    @JvmStatic
    fun parseThreadDetail(html: String?): PostDetail {
        val detail = PostDetail()

        // 某些帖子请求可能返回空响应（网络断开、服务端临时无响应或被重定向）。
        if (TextUtils.isEmpty(html)) {
            detail.replies = ArrayList<ReplyItem>()
            detail.imageUrls = ArrayList<String>()
            detail.currentPage = 1
            detail.totalPages = 1
            return detail
        }

        val doc = Jsoup.parse(html!!)

        // === 1. 版块信息 ===
        val forumLink = doc.select("div.comiis_head a.kmtit[href*=forum-]").first()
        if (forumLink != null) {
            detail.forumName = forumLink.ownText().trim()
            val href = forumLink.attr("href")
            val m = Pattern.compile("forum-(\\d+)-").matcher(href)
            if (m.find()) detail.forumFid = m.group(1)
        }

        // === 2. 帖子标题 ===
        val titleEl = doc.select("div.comiis_viewtit h2 div.km_tits").first()
        if (titleEl != null) {
            detail.title = titleEl.text().trim()
        }

        // === 3. formhash & tid ===
        val fhInput = doc.select("input[name=formhash]").first()
        if (fhInput != null) {
            detail.formhash = fhInput.attr("value")
        }
        if (detail.formhash == null || detail.formhash!!.isEmpty()) {
            val fhM = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html)
            if (fhM.find()) detail.formhash = fhM.group(1)
        }
        // === 3.1.1 noticeauthor(用于回复时 @通知对方) ===
        val naInput = doc.select("input[name=noticeauthor]").first()
        if (naInput != null) {
            detail.noticeauthor = naInput.attr("value")
        }
        val tidM = Pattern.compile("tid\\s*=\\s*['\"](\\d+)['\"]").matcher(html)
        if (tidM.find()) detail.tid = tidM.group(1)

        // === 3.1 当前用户的点赞/收藏状态（必须以网页端状态为准） ===
        parseCurrentActionStates(doc, html, detail)

        // === 4. 所有 postli 元素 ===
        var allPostlis = doc.select("div.comiis_postli")
        // 非标准模板：只把真正包含正文容器的帖子外壳当作 postli，
        // 避免把导航栏/按钮等普通 div[class*=post] 误当成楼主帖子。
        if (allPostlis.isEmpty()) {
            allPostlis = doc.select(
                "article:has(div.comiis_message), article:has(td.t_f), " +
                        "div[class*=post]:has(div.comiis_message), " +
                        "div[class*=post]:has(td.t_f), " +
                        "div[class*=thread]:has(div.comiis_message), " +
                        "div[class*=thread]:has(td.t_f)"
            )
        }
        if (allPostlis.isEmpty()) {
            // 页面可能没有 postli 外壳，但仍然包含正文容器。此时不能直接返回空 detail，
            // 否则客户端会把一个实际有内容的帖子显示成空白。
            val fallbackContent = doc.select(
                "div.comiis_message, td.t_f, div.message, div.postbody, article"
            ).first()
            if (fallbackContent != null) {
                val clone = fallbackContent.clone()
                clone.select("script, style, div.comiis_favshare, div.comiis_postli_time, a.followmod")
                    .remove()
                val fallbackHtml = clone.html().trim()
                if (!TextUtils.isEmpty(fallbackHtml)) {
                    detail.contentHtml = fallbackHtml
                }
            }
            detail.replies = ArrayList<ReplyItem>()
            detail.imageUrls = ArrayList<String>()
            detail.currentPage = 1
            detail.totalPages = 1
            return detail
        }

        // 第一个 postli = 楼主帖子
        val opPostli = allPostlis.first()!!
        // Discuz! 的赞赏接口不是只按 tid 操作，必须传入具体正文 pid。
        // Comiis 模板把它放在楼主 postli 的 id="pidxxxx" 中。
        val opPid = opPostli.id()
        if (!TextUtils.isEmpty(opPid) && opPid.startsWith("pid")) {
            detail.postPid = opPid.substring(3)
        }

        // === 5. 帖主（楼主）信息 ===
        val opTop = opPostli.select("div.comiis_postli_top").first()
        if (opTop != null) {
            // 头像
            val avatarImg = opTop.select("a.postli_top_tximg img.top_tximg").first()
            if (avatarImg != null) {
                var src = avatarImg.attr("src")
                if (!src.startsWith("http")) src = BASE_DOMAIN + src
                detail.avatarUrl = src
            }
            // 用户名 & UID
            val authorEl = opTop.select("a.top_user.f_b").first()
            if (authorEl != null) {
                detail.author = authorEl.text().trim()
                val m = Pattern.compile("uid=(\\d+)").matcher(authorEl.attr("href"))
                if (m.find()) detail.authorUid = m.group(1)
            }
            // 等级
            val levelEl = opTop.select("a.top_lev.bg_a").first()
            if (levelEl != null) detail.authorLevel = levelEl.text().trim()
            // 性别
            val genderEl = opTop.select("i.comiis_font.top_gender").first()
            if (genderEl != null) {
                val cls = genderEl.className()
                if (cls.contains("bg_boy")) detail.gender = "boy"
                else if (cls.contains("bg_girl")) detail.gender = "girl"
            }
            // 关注状态
            val followEl = opTop.select("a.followmod").first()
            if (followEl != null) {
                detail.isFollowed = followEl.text().trim().contains("已关注")
            }
            // 楼主时间 & 地点
            val opTimeArea = opTop.select("div.comiis_postli_time").first()
            if (opTimeArea != null) {
                val timeEl = opTimeArea.select("span.kmtime").first()
                if (timeEl != null) detail.publishTime = timeEl.text().trim()
                val locCode = opTimeArea.select("code.comiis_iplocality").first()
                if (locCode != null) detail.location = locCode.text().trim()
            }
        }

        // === 6. 帖子正文 + 附件图片 ===
        val opMsg = opPostli.select("div.comiis_message").first()
        if (opMsg != null) {
            // ★ 尝试多种选择器提取正文内容（优先精确选择器，兜底取整个消息区）
            var contentDiv = opMsg.select("div.comiis_a.comiis_message_table.cl").first()
            if (contentDiv == null) {
                contentDiv = opMsg.select("div.comiis_a").first()
            }
            if (contentDiv == null) {
                contentDiv = opMsg.select("div.comiis_message_table").first()
            }
            if (contentDiv == null && !TextUtils.isEmpty(opMsg.html())) {
                // ★ 最终兜底：移除回复按钮等干扰元素后直接取内容
                val msgClone = opMsg.clone()
                msgClone.select("div.comiis_favshare").remove()
                msgClone.select("a.followmod").remove()
                msgClone.select("div.comiis_postli_time").remove()
                msgClone.select("div.comiis_message_table.cl").remove()
                val remaining = msgClone.html().trim()
                if (!TextUtils.isEmpty(remaining) && remaining.length > 50) {
                    contentDiv = opMsg // 使用opMsg自身作为内容容器
                }
            }
            if (contentDiv != null) {
                // 深度清理正文容器中的赞赏模块、点赞列表、分享模块等网页附加元素，
                // 避免作者被屏蔽或被删除时残留“赞赏、打赏、好评、点赞列表”等杂乱内容
                val cleanDiv = contentDiv.clone()
                cleanDiv.select(
                    "div.comiis_rate, div[class*=comiis_rate], " +
                    "div.comiis_praise, div[class*=praise], " +
                    "ul.comiis_recommend_list_a, ul.comiis_recommend_list_t, ul[class*=recommend_list], " +
                    "em.comiis_recommend_num, a.comiis_recommend_addkey, " +
                    "div.comiis_favshare, div[class*=favshare], a.followmod, " +
                    "div.comiis_postli_time, div.manage, div.modact"
                ).remove()

                var content = cleanDiv.html().trim()
                // 如果内容太短可能只是干扰文本，尝试更精确提取
                val tf = opMsg.select("td.t_f").first()
                if (content.length < 10 && tf != null) {
                    content = tf.html().trim()
                }
                detail.contentHtml = normalizeCodeBlocks(content)
            } else {
                // 兜底：直接取整个消息区，并移除操作区域与赞赏点赞残留
                val msgClone = opMsg.clone()
                msgClone.select(
                    "div.comiis_rate, div[class*=comiis_rate], " +
                    "div.comiis_praise, div[class*=praise], " +
                    "ul.comiis_recommend_list_a, ul.comiis_recommend_list_t, ul[class*=recommend_list], " +
                    "em.comiis_recommend_num, a.comiis_recommend_addkey, " +
                    "div.comiis_favshare, div[class*=favshare], a.followmod, " +
                    "div.comiis_postli_time, div.manage, div.modact"
                ).remove()
                val fallbackHtml = msgClone.html().trim()
                if (fallbackHtml.length > 30) {
                    detail.contentHtml = normalizeCodeBlocks(fallbackHtml)
                }
            }

            // 无论正文使用哪一个选择器，都必须提取正文中的懒加载图片。
            var messagesDiv = opMsg.select("div.comiis_messages").first()
            if (messagesDiv == null) messagesDiv = opMsg
            val attachImageUrls = ArrayList<String>()
            for (img in messagesDiv.select("img")) {
                val realSrc = firstNonEmptyAttr(
                    img,
                    "zoomfile", "file", "comiis_loadimages", "data-original", "data-src",
                    "data-file", "data-lazy-src", "src"
                )
                val fullUrl = resolveAttachmentUrl(realSrc)
                if (isPostImageUrl(fullUrl) && !attachImageUrls.contains(fullUrl)) {
                    attachImageUrls.add(fullUrl!!)
                }
            }
            detail.imageUrls = attachImageUrls

            // 隐藏内容检测
            val quoteDiv = opMsg.select("div.comiis_quote").first()
            if (quoteDiv != null) {
                detail.hasHiddenContent = true
                detail.hiddenContentHtml = normalizeCodeBlocks(quoteDiv.html().trim())
            }
            // 点赞数(优先从推荐数标签解析,fallback到列表项数)
            val recommendNum = opMsg.selectFirst("em.comiis_recommend_num")
            if (recommendNum != null) {
                detail.likeCount = parseIntFromText(recommendNum.text())
            } else {
                val recommendLis = opMsg.select("ul.comiis_recommend_list_a li")
                detail.likeCount = recommendLis.size
            }
            // === 点赞人列表(登录态渲染) ===
            val likeUids = ArrayList<String>()
            val likeAvatars = ArrayList<String>()
            val likeNames = ArrayList<String>()
            val avatarLis = opMsg.select("ul.comiis_recommend_list_a li")
            for (li in avatarLis) {
                val liId = li.id()
                var uid: String? = null
                if (liId != null && liId.startsWith("comiis_recommend_list_a")) {
                    uid = liId.substring("comiis_recommend_list_a".length)
                }
                val img = li.selectFirst("img")
                var avatar = if (img != null) img.absUrl("src") else ""
                if (avatar.isEmpty()) {
                    avatar = if (img != null) img.attr("src") else ""
                }
                // 头像 url 相对路径补全
                if (!avatar.isEmpty() && avatar.startsWith("/") && !avatar.startsWith("//")) {
                    avatar = BASE_DOMAIN + avatar
                }
                if (uid == null || uid.isEmpty()) {
                    // 无 id 时从链接 uid=N 抓
                    val link = li.selectFirst("a[href*=uid=]")
                    val um = Pattern.compile("uid=(\\d+)").matcher(if (link != null) link.attr("href") else "")
                    if (um.find()) uid = um.group(1)
                }
                if (uid != null && !uid.isEmpty()) {
                    likeUids.add(uid)
                    likeAvatars.add(avatar)
                }
            }
            // 用户名版(comiis_recommend_list_t)
            var nameSpans = opMsg.select("ul.comiis_recommend_list_t span[id^=comiis_recommend_list_t]")
            if (nameSpans.isEmpty()) {
                nameSpans = doc.select("ul.comiis_recommend_list_t span[id^=comiis_recommend_list_t]")
            }
            for (sp in nameSpans) {
                val a = sp.selectFirst("a")
                if (a != null && !a.text().trim().isEmpty()) {
                    likeNames.add(a.text().trim())
                }
            }
            if (!likeUids.isEmpty()) {
                detail.likeUserUids = likeUids
                detail.likeUserAvatars = likeAvatars
            }
            if (!likeNames.isEmpty()) {
                detail.likeUserNames = likeNames
            }
        }

        // 收藏数(帖子头部操作栏 #comiis_favorite_a 内,与 opMsg 同级在 doc 下,
        // 不能用 opMsg.selectFirst 否则永远 null)
        val favoriteNum = doc.selectFirst("#comiis_favorite_a span.comiis_favorite_a_num")
        if (favoriteNum != null) {
            detail.favoriteCount = parseIntFromText(favoriteNum.text())
        }
        // 兜底:部分模板收藏数只带 class 不带 id
        if (detail.favoriteCount <= 0) {
            val favAlt = doc.selectFirst("span.comiis_favorite_a_num")
            if (favAlt != null) {
                detail.favoriteCount = parseIntFromText(favAlt.text())
            }
        }

        // === 7. 回复标题栏:评论总数 ===
        val pltit = doc.select("div.comiis_pltit").first()
        if (pltit != null) {
            val countSpan = pltit.select("span.f_d").first()
            if (countSpan != null) {
                detail.replyCount = parseIntFromText(countSpan.text())
            }
        }

        // === 9. 分页信息 ===
        val pageInput = doc.select("input[name=page]").first()
        if (pageInput != null) {
            val curPage = parseIntFromText(pageInput.attr("value"))
            detail.currentPage = if (curPage > 0) curPage else 1
        } else {
            detail.currentPage = 1
        }

        // === 8. 回复列表 ===
        // 在 Discuz 移动版中：第 1 页的第 0 个 postli 是楼主帖子（需跳过）；
        // 而在第 2 页及之后的页面，或者没有楼主区域的独立回复页中，
        // 第 0 个 postli 本身就是该页的第一条正常评论（例如 16#）！
        // 如果盲目跳过索引 0，后续每一页都会凭空丢掉第 1 条评论（翻 10 页就会丢 10 条）。
        val replies = ArrayList<ReplyItem>()
        val uidPtn = Pattern.compile("uid=(\\d+)")
        val startIndex = if (detail.currentPage > 1) 0 else 1
        for (i in startIndex until allPostlis.size) {
            try {
                val rp = allPostlis.get(i)
                val reply = ReplyItem()

                // pid
                val pidAttr = rp.id()
                if (pidAttr != null && pidAttr.startsWith("pid")) {
                    reply.pid = pidAttr.substring(3)
                }

                val rTop = rp.select("div.comiis_postli_top").first() ?: continue

                // 楼层号
                val floorEl = rTop.select("span.f_d.y").first()
                if (floorEl != null) reply.floorLabel = floorEl.text().trim()

                // 用户名 & UID
                val userEl = rTop.select("a.top_user.f_b").first()
                if (userEl != null) {
                    reply.author = userEl.text().trim()
                    val m = uidPtn.matcher(userEl.attr("href"))
                    if (m.find()) reply.authorUid = m.group(1)
                }
                // 等级
                val levEl = rTop.select("a.top_lev.bg_a").first()
                if (levEl != null) reply.authorLevel = levEl.text().trim()
                // 性别
                val gEl = rTop.select("i.comiis_font.top_gender").first()
                if (gEl != null) {
                    val cls = gEl.className()
                    if (cls.contains("bg_boy")) reply.gender = "boy"
                    else if (cls.contains("bg_girl")) reply.gender = "girl"
                }
                // 楼主标识
                val opTag = rTop.select("span.top_lev.bg_c.f_f").first()
                if (opTag != null && opTag.text().contains("楼主")) {
                    reply.isOP = true
                }
                // 头像
                val rAvatar = rTop.select("a.postli_top_tximg img.top_tximg").first()
                if (rAvatar != null) {
                    var src = rAvatar.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    reply.avatarUrl = src
                }

                // 回复内容（★ 添加兜底选择器）
                var rContent = rp.select("div.comiis_message div.comiis_a.comiis_message_table.cl").first()
                if (rContent == null) {
                    rContent = rp.select("div.comiis_message div.comiis_a").first()
                }
                if (rContent == null) {
                    rContent = rp.select("div.comiis_message").first()
                }
                if (rContent != null) {
                    // Discuz 回复内容中通常包含 div.comiis_quote 引用块。
                    // 引用与当前回复必须拆开保存，否则客户端会把引用文本和新回复连成一段。
                    val quote = rContent.select("div.comiis_quote, blockquote, .quote").first()
                    if (quote != null) {
                        reply.quotedContentHtml = quote.html().trim()
                        reply.quotedContentText = quote.text().replace('\u00a0', ' ').trim()
                        quote.remove()
                    }
                    reply.contentHtml = normalizeCodeBlocks(rContent.html().trim())
                    reply.contentText = rContent.text().replace('\u00a0', ' ').trim()
                }

                // 回复时间 & 地点（底栏 div.comiis_postli_times）
                val rTimes = rp.select("div.comiis_postli_times").first()
                if (rTimes != null) {
                    val timeEl = rTimes.select("span.comiis_tm").first()
                    if (timeEl != null) reply.time = timeEl.text().trim()
                    val locCode = rTimes.select("code.comiis_iplocality").first()
                    if (locCode != null) reply.location = locCode.text().trim()
                }

                replies.add(reply)
            } catch (ignored: Exception) {
            }
        }
        detail.replies = replies

        // 从分页组件中提取最大页码
        var maxPage = 1
        // 方式1: 从 <span title="共 N 页"> 提取
        val pgDiv = doc.select("div.pg").first()
        if (pgDiv != null) {
            val span = pgDiv.select("span[title*=共]").first()
            if (span != null) {
                val title = span.attr("title")
                val titleM = Pattern.compile("共\\s*(\\d+)\\s*页").matcher(title)
                if (titleM.find()) {
                    maxPage = parseIntFromText(titleM.group(1))
                } else {
                    // 从文本 " / N 页" 提取
                    val textM = Pattern.compile("/?\\s*(\\d+)\\s*页").matcher(span.text())
                    if (textM.find()) {
                        maxPage = parseIntFromText(textM.group(1))
                    }
                }
            }
            // 方式2: 从分页数字链接 thread-{tid}-{page}-1.html 提取
            if (maxPage <= 1) {
                val numLinks = pgDiv.select("a[href*=-1.html]")
                val tidPagePtn = Pattern.compile("thread-\\d+-(\\d+)-1\\.html")
                for (pl in numLinks) {
                    val pm = tidPagePtn.matcher(pl.attr("href"))
                    if (pm.find()) {
                        try {
                            val p = pm.group(1).toInt()
                            if (p > maxPage) maxPage = p
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        }
        // 方式3: 兜底，从 a[href*=page=] 提取（兼容桌面版）
        if (maxPage <= 1) {
            val pageLinks = doc.select("a[href*=page=]")
            val pagePtn = Pattern.compile("[&?]page=(\\d+)")
            for (pl in pageLinks) {
                val pm = pagePtn.matcher(pl.attr("href"))
                if (pm.find()) {
                    try {
                        val p = pm.group(1).toInt()
                        if (p > maxPage) maxPage = p
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
        detail.totalPages = maxPage

        // === 10. 赞赏与好评统计（如有） ===
        // Comiis 移动端结构：rate_tip 同时包含“X人打赏”和“Y好评”，
        // 下方 ul li 为打赏头像列表；rate_tip 中的链接指向全部打赏明细。
        val rateDiv = doc.select("div.comiis_rate").first()
        val rewardAvatars = ArrayList<String>()
        val goodReviewAvatars = ArrayList<String>()
        if (rateDiv != null) {
            val rateTip = rateDiv.select("p.rate_tip").first()
            val rateTipText = if (rateTip != null) rateTip.text().trim() else rateDiv.text().trim()
            val rewardCountMatcher = Pattern.compile("(\\d+)\\s*人?打赏").matcher(rateTipText)
            if (rewardCountMatcher.find()) {
                detail.rewardCount = parseIntFromText(rewardCountMatcher.group(1))
            } else {
                val rateBtn = rateDiv.select("h2.rate_btn").first()
                if (rateBtn != null && rateBtn.text().contains("赞赏")) {
                    detail.rewardCount = parseIntFromText(rateBtn.text())
                }
            }

            val goodCountMatcher = Pattern.compile("(\\d+)\\s*好评").matcher(rateTipText)
            if (goodCountMatcher.find()) {
                detail.goodReviewCount = parseIntFromText(goodCountMatcher.group(1))
            }

            if (rateTip != null) {
                val rewardLink = rateTip.select("a[href*=viewratings]").first()
                if (rewardLink != null) {
                    val href = rewardLink.attr("href")
                    detail.rewardDetailUrl = resolveAttachmentUrl(href)
                }
            }

            for (img in rateDiv.select("ul > li img")) {
                val avatar = resolveAvatarUrl(
                    firstNonEmptyAttr(img, "src", "data-src", "data-original", "data-lazy-src")
                )
                if (!TextUtils.isEmpty(avatar) && !rewardAvatars.contains(avatar)) {
                    rewardAvatars.add(avatar!!)
                }
            }
        }
        detail.rewardUserAvatars = rewardAvatars
        detail.goodReviewUserAvatars = goodReviewAvatars

        return detail
    }

    /**
     * 解析“查看全部打赏”页面中的打赏金币总数。
     * 页面格式通常为：总计：金币 +4，好评 +2。
     */
    @JvmStatic
    fun parseRewardCoins(html: String?): Int {
        if (TextUtils.isEmpty(html)) return 0
        val text = Jsoup.parse(html!!).text()
        val matcher = Pattern.compile("金币\\s*\\+\\s*(\\d+)").matcher(text)
        if (matcher.find()) return parseIntFromText(matcher.group(1))
        return 0
    }

    /**
     * 从桌面版评分表提取真正获得“好评”的用户头像。
     * 移动版只展示打赏头像，不包含好评用户明细，因此由详情页加载时补充。
     */
    @JvmStatic
    fun parseGoodReviewAvatarUrls(html: String?): MutableList<String> {
        val avatars = ArrayList<String>()
        if (TextUtils.isEmpty(html)) return avatars
        val doc = Jsoup.parse(html!!)
        for (row in doc.select("tr[id^=rate_]")) {
            val cells = row.select("> td")
            if (cells.size >= 2 && cells.get(1).text().contains("+")) {
                val img = row.select("td:first-child img").first()
                val avatar = resolveAvatarUrl(
                    firstNonEmptyAttr(img, "src", "data-src", "data-original", "data-lazy-src")
                )
                if (!TextUtils.isEmpty(avatar) && !avatars.contains(avatar)) {
                    avatars.add(avatar!!)
                }
            }
        }
        return avatars
    }

    /**
     * 从 Comiis 详情页解析当前登录用户的点赞/收藏状态。
     * uid 非 0 且页面存在对应操作控件时标记状态已知。
     */
    private fun parseCurrentActionStates(doc: Document?, html: String?, detail: PostDetail?) {
        if (doc == null || detail == null || TextUtils.isEmpty(html)) return

        val uidMatcher = Pattern.compile(
            "(?:var\\s+)?uid\\s*=\\s*['\"](\\d+)['\"]",
            Pattern.CASE_INSENSITIVE
        ).matcher(html!!)
        if (!uidMatcher.find() || "0" == uidMatcher.group(1)) return

        val recommend = doc.select(
            "a.comiis_recommend_addkey, a.comiis_recommend_new, " +
                    ".comiis_recommend_addkey"
        ).first()
        if (recommend != null) {
            val icon = recommend.select("i.comiis_recommend_color").first()
            val classes = recommend.className() + " " +
                    (if (icon != null) icon.className() else "")
            val iconHtml = if (icon != null) icon.html() else ""
            val liked = classes.contains("f_a") || iconHtml.contains("e654")
            detail.isLiked = liked
            detail.likedStateKnown = true
        }

        val favoriteIcon = doc.select("#comiis_favorite_a i.comiis_favorite_a_color").first()
        if (favoriteIcon != null) {
            val classes = favoriteIcon.className()
            val iconHtml = favoriteIcon.html()
            val favorited = classes.contains("f_a") || iconHtml.contains("e64c")
            detail.isFavorited = favorited
            detail.favoritedStateKnown = true
        }
    }

    /**
     * 提取 formhash（发帖/回复需要）
     * 优先从 input[name=formhash] 提取，再尝试 JS 变量，最后尝试收藏对话框表单。
     */
    @JvmStatic
    fun parseFormhash(html: String?): String? {
        if (TextUtils.isEmpty(html)) return null
        val doc = Jsoup.parse(html!!)
        val input = doc.select("input[name=formhash]").first()
        if (input != null) return input.attr("value")

        // 尝试从 JavaScript 变量中提取
        val m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html)
        if (m.find()) return m.group(1)

        return null
    }

    /**
     * 从收藏页面 HTML 中提取删除/添加收藏对话框里的 formhash。
     * Comiis 模板的收藏对话框表单 id 为 favoriteform_{favid}，
     * 内部包含隐藏的 input[name=formhash]。
     */
    @JvmStatic
    fun parseFavoriteFormhash(html: String?): String? {
        if (TextUtils.isEmpty(html)) return null
        val doc = Jsoup.parse(html!!)
        // 收藏对话框表单 id 包含 "favoriteform"
        val form = doc.select("form[id*=favoriteform]").first()
        if (form != null) {
            val input = form.select("input[name=formhash]").first()
            if (input != null) {
                val v = input.attr("value")
                if (!TextUtils.isEmpty(v)) return v
            }
        }
        // 兜底：尝试从页面中任意 form 的 input[name=formhash] 提取
        val fallback = doc.select("input[name=formhash]").first()
        if (fallback != null) {
            val v = fallback.attr("value")
            if (!TextUtils.isEmpty(v)) return v
        }
        // 最后尝试从 JS 变量提取
        val m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html)
        if (m.find()) return m.group(1)
        return null
    }

    /**
     * 从 URL 中提取参数
     */
    private fun extractParam(url: String?, param: String): String? {
        if (url == null || url.isEmpty()) return null
        try {
            val p = Pattern.compile(param + "=(\\d+)")
            val m = p.matcher(url)
            if (m.find()) return m.group(1)

            // 尝试更通用的匹配（非纯数字参数）
            val p2 = Pattern.compile(param + "=([^&]+)")
            val m2 = p2.matcher(url)
            if (m2.find()) return m2.group(1)
        } catch (ignored: Exception) {
        }
        return null
    }

    /** Extract user UIDs from a following-list page. */
    @JvmStatic
    fun extractFollowingUids(html: String?): MutableSet<String> {
        val uids = HashSet<String>()
        if (TextUtils.isEmpty(html)) return uids
        val doc = Jsoup.parse(html!!)
        var links = doc.select("div.comiis_userlist01 > li.b_t > p.tit > a[href*=uid=], li.b_t p.tit a[href*=uid=]")
        if (links.isEmpty()) links = doc.select("a[href*=home.php?mod=space&uid=]")
        for (link in links) {
            val uid = extractParam(link.attr("href"), "uid")
            if (!TextUtils.isEmpty(uid)) uids.add(uid!!)
        }
        return uids
    }

    /** 判断关注列表是否还有下一页。 */
    @JvmStatic
    fun hasUserListPageAfter(html: String?, currentPage: Int): Boolean {
        if (TextUtils.isEmpty(html)) return false
        val doc = Jsoup.parse(html!!)
        for (link in doc.select("a[href*=page=]")) {
            val matcher = Pattern.compile("(?:^|[?&])page=(\\d+)")
                .matcher(link.attr("href"))
            if (matcher.find()) {
                try {
                    if (matcher.group(1).toInt() > currentPage) return true
                } catch (ignored: Exception) {
                }
            }
        }
        return false
    }

    /**
     * 解析好友/关注/粉丝列表
     * 适配 Comiis App 模板。
     *
     * 真实 DOM 结构（好友页）：
     *   div.comiis_friend_boxs > ... > div.comiis_userlist01.cl > li.b_t
     *     p.ytit.f_d ── 操作链接
     *     a.list01_limg > img[src*=avatar] ── 头像
     *     p.tit > a[href*="home.php?mod=space&uid="] ── 用户名（唯一可靠标识）
     *     p.txt > font ── 等级/积分
     */
    @JvmStatic
    fun parseFriendList(html: String?): MutableList<Friend> {
        val friends = ArrayList<Friend>()
        if (TextUtils.isEmpty(html)) return friends
        val doc = Jsoup.parse(html!!)

        // ★ 精确策略：先找用户条目容器 li.b_t，再从中提取用户名链接
        var userItems = doc.select("div.comiis_userlist01 > li.b_t")
        if (userItems.isEmpty()) {
            // 兜底：直接找 li.b_t（某些页面结构略有不同）
            userItems = doc.select("li.b_t")
        }

        for (li in userItems) {
            try {
                // 用户名链接：唯一可靠的是 p.tit 内的 a[href*=uid=]
                var usernameLink = li.select("p.tit > a[href*=uid=]").first()
                if (usernameLink == null) {
                    // 兜底：直接的 a[href*=uid=]（关注页结构可能略有不同）
                    usernameLink = li.select("a[href*=uid=]").first()
                }
                if (usernameLink == null) continue

                val username = usernameLink.text().trim()
                if (TextUtils.isEmpty(username)) continue

                val href = usernameLink.attr("href")
                val m = Pattern.compile("uid=(\\d+)").matcher(href)
                if (!m.find()) continue
                val uid = m.group(1)

                // 去重
                var duplicate = false
                for (existing in friends) {
                    if (uid == existing.uid) {
                        duplicate = true
                        break
                    }
                }
                if (duplicate) continue

                val f = Friend()
                f.uid = uid
                f.username = username

                // 头像
                val avatarImg = li.select("a.list01_limg img[src*=avatar], img[src*=avatar]").first()
                if (avatarImg != null) {
                    var src = avatarImg.attr("src")
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src
                    f.avatarUrl = src
                }

                // 等级（从 p.txt 或 span.kmlevs 中提取）
                val levelEl = li.select(".kmlevs, .kmlv, span:contains(Lv)").first()
                if (levelEl == null) {
                    // 从 p.txt > font 中提取（好友页结构）
                    val txtFonts = li.select("p.txt > font")
                    for (font in txtFonts) {
                        var t = font.text().trim()
                        if (t.contains("Lv") || t.contains("硕士") || t.contains("博士")
                            || t.contains("大学") || t.contains("高中") || t.contains("初中")
                            || t.contains("小学") || t.contains("学前")
                        ) {
                            if (t.contains("积分")) {
                                t = Regex("积分.*").replace(t, "").trim()
                            }
                            f.level = t
                            break
                        }
                    }
                } else {
                    f.level = levelEl.text().trim()
                }

                // 积分/用户组（从 p.txt 中提取）
                val creditsEl = li.select("p.txt > font:contains(积分)").first()
                if (creditsEl != null) {
                    f.credits = creditsEl.text().trim()
                }

                friends.add(f)
            } catch (ignored: Exception) {
            }
        }

        return friends
    }

    /**
     * 解析收藏列表(home.php?mod=space&do=favorite&mobile=2)
     * Comiis 模板结构: 包含 收藏标题链接 的条目
     */
    @JvmStatic
    fun parseFavoriteList(html: String?): MutableList<Thread> {
        val favorites = ArrayList<Thread>()
        if (TextUtils.isEmpty(html)) return favorites
        val doc = Jsoup.parse(html!!)

        // 收藏页不同 UA/模板可能使用 thread-xxx.html、thread.php?tid=xxx，
        // 或 forum.php?mod=viewthread&tid=xxx，不能只依赖一种 URL。
        val links = doc.select("a[href*=thread-], a[href*=thread.php], a[href*=viewthread]")
        val tidPretty = Pattern.compile("(?:^|/)thread-(\\d+)(?:-[^./?#]+)*\\.html", Pattern.CASE_INSENSITIVE)
        val tidQuery = Pattern.compile("[?&](?:tid|threadid)=(\\d+)", Pattern.CASE_INSENSITIVE)
        val favidPattern = Pattern.compile("(?:favid|fav_id)\\s*[=:/]\\s*['\"]?(\\d+)", Pattern.CASE_INSENSITIVE)
        val favidHrefPattern = Pattern.compile("[?&](?:favid|fav_id)=(\\d+)", Pattern.CASE_INSENSITIVE)
        val seen = HashSet<String>()

        for (link in links) {
            try {
                val href = link.attr("href")
                var m = tidPretty.matcher(href)
                var tid = if (m.find()) m.group(1) else ""
                if (TextUtils.isEmpty(tid)) {
                    m = tidQuery.matcher(href)
                    if (m.find()) tid = m.group(1)
                }
                if (TextUtils.isEmpty(tid) || seen.contains(tid)) continue

                var title = link.attr("title").trim()
                if (TextUtils.isEmpty(title)) title = link.text().trim()
                if (TextUtils.isEmpty(title)) continue

                val t = Thread()
                t.tid = tid
                t.title = title

                var favid = firstNonEmpty(
                    link.attr("data-favid"), link.attr("data-fav-id"),
                    link.attr("favid"), link.attr("data-id")
                )
                val hrefFav = favidHrefPattern.matcher(href)
                if (TextUtils.isEmpty(favid) && hrefFav.find()) favid = hrefFav.group(1)

                // 删除链接通常与标题同处于 li，也可能放在 div/表格行的上层容器。
                var parent: Element? = link
                var depth = 0
                while (parent != null && depth < 6 && TextUtils.isEmpty(favid)) {
                    val deleteLinks = parent.select(
                        "a[href*=spacecp][href*=favorite], " +
                                "a[href*=favid], a[data-favid], a[data-fav-id]"
                    )
                    for (deleteLink in deleteLinks) {
                        val deleteHref = deleteLink.attr("href")
                        val dm = favidHrefPattern.matcher(deleteHref)
                        if (dm.find()) {
                            favid = dm.group(1)
                            break
                        }
                        favid = firstNonEmpty(
                            deleteLink.attr("data-favid"),
                            deleteLink.attr("data-fav-id"), deleteLink.attr("favid")
                        )
                        if (!TextUtils.isEmpty(favid)) break
                    }
                    if (TextUtils.isEmpty(favid)) {
                        val fm = favidPattern.matcher(parent.outerHtml())
                        if (fm.find()) favid = fm.group(1)
                    }
                    parent = parent.parent()
                    depth++
                }

                t.favid = favid
                seen.add(tid)
                favorites.add(t)
            } catch (ignored: Exception) {
            }
        }
        return favorites
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (value in values) {
            if (!TextUtils.isEmpty(value)) return value!!
        }
        return ""
    }

    /**
     * 解析积分详情（home.php?mod=space&do=profile&mobile=2）
     * 返回积分、金币、好评、信誉等键值对
     */
    @JvmStatic
    fun parseCreditDetails(html: String?): MutableMap<String, String> {
        val credits = HashMap<String, String>()
        if (TextUtils.isEmpty(html)) return credits
        val doc = Jsoup.parse(html!!)
        val jfLis = doc.select(".comiis_space_profilejf ul li")
        for (i in 0 until jfLis.size) {
            try {
                val li = jfLis.get(i)
                val valEl = li.select(".f_0, span").first()
                val value = if (valEl != null) valEl.text().trim() else li.text().trim()
                // 提取标签名（去掉数字值）
                var label = li.ownText().trim()
                if (label.isEmpty()) label = "项目" + (i + 1)
                credits[label] = value
            } catch (ignored: Exception) {
            }
        }
        // 再从 comiis_space_profile 中提取详细资料
        val profileLis = doc.select(".comiis_space_profile li")
        for (li in profileLis) {
            try {
                val labelSpan = li.select("span").first()
                val label = if (labelSpan != null) labelSpan.text().trim() else ""
                val valueEl = li.select(".profile_rs").first()
                val value = if (valueEl != null) valueEl.text().trim() else ""
                if (!label.isEmpty() && !value.isEmpty()) {
                    credits[label] = value
                }
            } catch (ignored: Exception) {
            }
        }
        return credits
    }

    /**
     * 解析用户资料数据中的可编辑字段（供编辑资料页使用）
     */
    @JvmStatic
    fun parseEditableProfile(html: String?): MutableMap<String, String> {
        val fields = HashMap<String, String>()
        if (TextUtils.isEmpty(html)) return fields
        val doc = Jsoup.parse(html!!)
        // 查找所有表单输入字段
        val inputs = doc.select("input[type=text], input[type=email], input[type=url], textarea, select")
        for (input in inputs) {
            try {
                val name = input.attr("name")
                val value = input.`val`().trim()
                if (!name.isEmpty()) {
                    fields[name] = value
                }
            } catch (ignored: Exception) {
            }
        }
        return fields
    }

    private fun parseIntFromText(text: String?): Int {
        if (text == null || text.isEmpty()) return 0
        try {
            val m = Pattern.compile("(\\d+)").matcher(text)
            if (m.find()) return m.group(1).toInt()
        } catch (ignored: Exception) {
        }
        return 0
    }

    private fun firstNonEmptyAttr(element: Element?, vararg names: String?): String? {
        if (element == null) return null
        for (name in names) {
            if (name != null && element.hasAttr(name)) {
                val value = element.attr(name)
                if (!TextUtils.isEmpty(value)) return value.trim()
            }
        }
        return null
    }

    private fun resolveAvatarUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) return null
        if (url!!.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return BASE_DOMAIN + url.substring(1)
        return BASE_DOMAIN + url
    }

    private fun isPostImageUrl(url: String?): Boolean {
        if (TextUtils.isEmpty(url)) return false
        val lower = url!!.lowercase()
        return !lower.contains("none.gif")
                && !lower.contains("none.png")
                && !lower.contains("loading")
                && !lower.contains("smiley")
                && !lower.contains("face")
                && !lower.contains("icon")
                && !lower.contains("stamp")
                && !lower.contains("magic")
                && !lower.contains("emoticon")
                && !lower.contains("/static/image/")
                && !lower.contains("avatar.php")
    }

    /**
     * 补全附件图片URL（处理相对路径、懒加载路径等）
     * MT论坛移动版使用 comiis_loadimages 属性存储真实URL
     */
    private fun resolveAttachmentUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) return null
        // 如果已经是完整URL
        if (url!!.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        if (url.startsWith("//")) {
            return "https:$url"
        }
        if (url.startsWith("/")) {
            return BASE_DOMAIN + url.substring(1)
        }
        // 纯相对路径
        return BASE_DOMAIN + url
    }

    /**
     * 提取列表卡片中的多张帖子图片，供所有帖子列表页面统一使用。
     */
    private fun populateThreadImages(item: Element, thread: Thread) {
        val imageUrls = ArrayList<String>()
        val imageElements = item.select(
            ".comiis_pyqlist_imgs img, .comiis_pyqlist_img img"
        )
        for (image in imageElements) {
            val src = firstNonEmptyAttr(
                image,
                "comiis_loadimages", "data-original", "data-src",
                "data-file", "file", "data-lazy-src", "src"
            )
            val fullUrl = resolveAttachmentUrl(src)
            if (isPostImageUrl(fullUrl) && !imageUrls.contains(fullUrl)) {
                imageUrls.add(fullUrl!!)
                if (imageUrls.size >= 9) break
            }
        }

        // 某些空间帖子页没有图片容器，使用卡片正文区域中的图片作为兜底。
        if (imageUrls.isEmpty()) {
            val fallbackImages = item.select(".mmlist_li_box img")
            for (image in fallbackImages) {
                val src = firstNonEmptyAttr(
                    image,
                    "comiis_loadimages", "data-original", "data-src",
                    "data-file", "file", "data-lazy-src", "src"
                )
                val fullUrl = resolveAttachmentUrl(src)
                if (isPostImageUrl(fullUrl) && !imageUrls.contains(fullUrl)) {
                    imageUrls.add(fullUrl!!)
                    if (imageUrls.size >= 9) break
                }
            }
        }

        thread.imageUrls = imageUrls
        if (!imageUrls.isEmpty()) {
            thread.hasImage = true
            if (TextUtils.isEmpty(thread.thumbnailUrl)) {
                thread.thumbnailUrl = imageUrls.get(0)
            }
        }
    }

    /**
     * 获取移动端帖子列表URL
     */
    @JvmStatic
    fun getThreadListUrl(fid: String?, page: Int): String {
        return BASE_DOMAIN + "forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page + "&mobile=2"
    }

    /**
     * 获取版块帖子列表URL(桌面版,不带 mobile=2 参数)
     * 用于 mobile=2 页面返回登录提示页时的回退请求:桌面版未登录也可查看帖子列表
     */
    @JvmStatic
    fun getThreadListUrlDesktop(fid: String?, page: Int): String {
        return BASE_DOMAIN + "forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page
    }

    /**
     * 获取首页帖子流URL
     */
    @JvmStatic
    fun getHomeUrl(page: Int): String {
        return BASE_DOMAIN + "forum.php?mod=guide&view=newthread&page=" + page + "&mobile=2"
    }

    /**
     * 获取导读URL
     */
    @JvmStatic
    fun getGuideUrl(view: String?, page: Int): String {
        return BASE_DOMAIN + "forum.php?mod=guide&view=" + view + "&page=" + page + "&mobile=2"
    }

    /**
     * 获取登录URL
     */
    @JvmStatic
    fun getLoginUrl(): String {
        return BASE_DOMAIN + "member.php?mod=logging&action=login&mobile=2"
    }

    /**
     * 获取登录提交URL
     * ★ 修复：从登录页面HTML中提取 form 的 action 属性作为登录提交地址
     * 不再手动拼接，因为 loginhash 和 formhash 是不同的值
     */
    @JvmStatic
    fun getLoginPostUrl(): String {
        return BASE_DOMAIN + "member.php?mod=logging&action=login&loginsubmit=yes&loginhash=" + "&mobile=2"
    }

    /**
     * 从登录页面HTML中提取正确的登录提交URL（含 loginhash）
     */
    @JvmStatic
    fun extractLoginPostUrl(loginPageHtml: String?): String {
        if (TextUtils.isEmpty(loginPageHtml)) return getLoginPostUrl()
        val doc = Jsoup.parse(loginPageHtml!!)
        var form = doc.select("form[id=loginform]").first()
        if (form == null) {
            form = doc.select("form[method=post][action*=login]").first()
        }
        if (form != null) {
            val action = form.attr("action")
            if (!TextUtils.isEmpty(action)) {
                if (action.startsWith("http")) return action
                return BASE_DOMAIN + action
            }
        }
        return getLoginPostUrl()
    }

    /**
     * 获取用户空间URL
     */
    @JvmStatic
    fun getUserSpaceUrl(uid: String?): String {
        return BASE_DOMAIN + "home.php?mod=space&uid=" + uid + "&do=profile&mobile=2"
    }

    /**
     * 获取消息列表URL
     */
    @JvmStatic
    fun getMessageUrl(): String {
        return BASE_DOMAIN + "home.php?mod=space&do=pm"
    }

    /**
     * 获取帖子详情URL。
     * 本论坛 Comiis 模板的评论排序参数实际为：
     *   ordertype=1：倒序（最新评论在前）
     *   不传 ordertype：正序（最早评论在前）
     */
    @JvmStatic
    fun getThreadDetailUrl(tid: String?): String {
        return BASE_DOMAIN + "forum.php?mod=viewthread&tid=" + tid + "&mobile=2"
    }

    @JvmStatic
    fun getThreadDetailUrl(tid: String?, order: String?): String {
        val url = StringBuilder(BASE_DOMAIN)
            .append("forum.php?mod=viewthread&tid=").append(tid)
            .append("&mobile=2")
        if ("desc".equals(order, ignoreCase = true)) {
            url.append("&ordertype=1")
        }
        return url.toString()
    }

    /** 获取指定评论页，并沿用论坛实际的 ordertype 排序参数。 */
    @JvmStatic
    fun getThreadDetailUrl(tid: String?, page: Int, order: String?): String {
        val url = StringBuilder(BASE_DOMAIN)
            .append("forum.php?mod=viewthread&tid=").append(tid)
            .append("&page=").append(page)
            .append("&mobile=2")
        if ("desc".equals(order, ignoreCase = true)) {
            url.append("&ordertype=1")
        }
        return url.toString()
    }

    /**
     * 获取帖子详情URL（桌面版）
     */
    @JvmStatic
    fun getThreadDesktopDetailUrl(tid: String?): String {
        return BASE_DOMAIN + "forum.php?mod=viewthread&tid=" + tid
    }

    /**
     * 获取发帖页面URL
     */
    @JvmStatic
    fun getNewThreadUrl(fid: String?): String {
        return BASE_DOMAIN + "forum.php?mod=post&action=newthread&fid=" + fid + "&mobile=2"
    }

    /**
     * 构建 Discuz! 搜索排序参数
     * @param orderby lastpost(最新回复)/dateline(最新发布)/replies(最多回复)
     * @return 排序参数字符串，含前导 &
     */
    @JvmStatic
    fun buildSortQuery(orderby: String?): String {
        if ("replies" == orderby) {
            return "&orderby=replies&ascdesc=desc"
        } else if ("dateline" == orderby) {
            return "&orderby=dateline&ascdesc=desc"
        }
        return "&orderby=lastpost&ascdesc=desc" // 默认：最新回复
    }

    /**
     * 获取搜索URL（强制 mobile=2，否则返回桌面版HTML无法解析forumlist_li）
     * @param keyword 搜索关键词
     * @param page 页码，从1开始
     * @param orderby 排序方式：lastpost/dateline/replies
     */
    @JvmStatic
    fun getSearchUrl(keyword: String?, page: Int, orderby: String?): String {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            var url = BASE_DOMAIN + "search.php?mod=forum&searchsubmit=yes&srchtxt=" + encoded +
                    buildSortQuery(orderby) + "&mobile=2"
            if (page > 1) {
                url += "&page=$page"
            }
            return url
        } catch (e: UnsupportedEncodingException) {
            e.printStackTrace()
            var url = BASE_DOMAIN + "search.php?mod=forum&searchsubmit=yes&mobile=2"
            if (page > 1) {
                url += "&page=$page"
            }
            return url
        }
    }

    /**
     * 获取搜索URL（默认按最新回复排序）
     * @param keyword 搜索关键词
     * @param page 页码，从1开始
     */
    @JvmStatic
    fun getSearchUrl(keyword: String?, page: Int): String {
        return getSearchUrl(keyword, page, "lastpost")
    }

    /**
     * 从搜索结果HTML中提取分页信息
     * @return 最大页码，如果只有一页或无分页则返回1
     */
    @JvmStatic
    fun parseSearchTotalPages(html: String?): Int {
        if (TextUtils.isEmpty(html)) return 1
        val doc = Jsoup.parse(html!!)
        // 搜索分页链接: <a href="search.php?mod=forum&searchid=XXX&page=N"
        var pageLinks = doc.select("a[href*=searchid]")
        if (pageLinks.isEmpty()) {
            // 兜底：查找任何带 page= 的链接
            pageLinks = doc.select("a[href*=page=]")
        }
        var maxPage = 1
        val pagePtn = Pattern.compile("[&?]page=(\\d+)")
        for (pl in pageLinks) {
            val pm = pagePtn.matcher(pl.attr("href"))
            if (pm.find()) {
                try {
                    val p = pm.group(1).toInt()
                    if (p > maxPage) maxPage = p
                } catch (ignored: Exception) {
                }
            }
        }
        return maxPage
    }

    /**
     * 从搜索结果第一页的HTML中提取 searchid（Discuz! 搜索会话ID）
     * @return searchid 字符串，如果没有找到则返回 null
     */
    @JvmStatic
    fun extractSearchId(html: String?): String? {
        if (TextUtils.isEmpty(html)) return null
        val m = Pattern.compile("searchid=(\\d+)").matcher(html!!)
        if (m.find()) {
            return m.group(1)
        }
        return null
    }

    /**
     * 获取搜索分页URL（带 searchid 和完整参数，匹配真实分页链接格式）
     * 真实分页URL示例：
     * search.php?mod=forum&searchid=290&orderby=lastpost&ascdesc=desc&searchsubmit=yes&page=2&mobile=2
     * @param searchId 搜索会话ID（从第1页HTML中提取）
     * @param page 页码，从2开始
     * @param orderby 排序方式：lastpost/dateline/replies
     * @return 分页URL
     */
    @JvmStatic
    fun getSearchPageUrl(searchId: String?, page: Int, orderby: String?): String {
        return BASE_DOMAIN + "search.php?mod=forum&searchid=" + searchId +
                buildSortQuery(orderby) + "&searchsubmit=yes&page=" + page + "&mobile=2"
    }

    /**
     * 获取搜索分页URL（默认按最新回复排序）
     * @param searchId 搜索会话ID
     * @param page 页码
     * @return 分页URL
     */
    @JvmStatic
    fun getSearchPageUrl(searchId: String?, page: Int): String {
        return getSearchPageUrl(searchId, page, "lastpost")
    }

    /**
     * 获取社区页面URL（版块列表+签到入口+统计数据）
     * forumlist=1 返回版块列表页面，包含签到入口和统计信息
     */
    @JvmStatic
    fun getForumlistMobileUrl(): String {
        return BASE_DOMAIN + "forum.php?forumlist=1&mobile=2"
    }

    /**
     * 解析社区页面（forum.php?forumlist=1&mobile=2）
     * 提取：签到入口文本、formhash、登录状态、4项统计数据、全量版块列表
     *
     * @param html 社区页面 HTML
     * @return CommunityPageData 对象
     */
    @JvmStatic
    fun parseCommunityPage(html: String?): CommunityPageData {
        val data = CommunityPageData()
        val doc = Jsoup.parse(html ?: "")

        // === 1. 提取 formhash ===
        // 方式1：从 input[name=formhash] 提取
        val fhInput = doc.select("input[name=formhash]").first()
        if (fhInput != null) {
            val fh = fhInput.attr("value")
            if (!fh.isEmpty()) {
                data.formhash = fh
            }
        }
        // 方式2：从 JavaScript 中 formhash=xxx 提取（MT论坛 k_misign 插件）
        if (data.formhash == null || data.formhash!!.isEmpty()) {
            val fhMatcher = Pattern.compile("formhash\\s*=\\s*['\"]?([a-f0-9]{8})['\"]?").matcher(html ?: "")
            if (fhMatcher.find()) {
                data.formhash = fhMatcher.group(1)
            }
        }

        // === 2. 解析签到入口（适配 MT 论坛 k_misign 签到插件） ===
        // MT论坛使用科站网签到插件 k_misign，签到按钮结构：
        // <a class="signBtn" href="member.php?mod=logging&action=login&mobile=2">  （未登录时指向登录页）
        // JS代码: $.ajax({type:'POST', url:'plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=xxx'})

        // 2a. 查找 signBtn class 的签到按钮
        val signBtn = doc.select("a.signBtn").first()
        if (signBtn != null) {
            var signHref = signBtn.attr("href")
            data.signInText = signBtn.text().trim()
            if (!signHref.isEmpty()) {
                if (!signHref.startsWith("http")) {
                    signHref = BASE_DOMAIN + signHref
                }
                data.signInUrl = signHref
            }
            // 检查签到链接是否指向登录页 → 当前未登录
            if (signHref.contains("member.php?mod=logging")) {
                data.loginRequired = true
            }
        } else {
            // 2b. 兜底：查找包含 "签到" 文字的元素
            val signInLink = doc.select("a:containsOwn(签到)").first()
            if (signInLink != null) {
                val signText = signInLink.text().trim()
                data.signInText = signText
                var signHref = signInLink.attr("href")
                if (!signHref.isEmpty()) {
                    if (!signHref.startsWith("http")) {
                        signHref = BASE_DOMAIN + signHref
                    }
                    data.signInUrl = signHref
                }
                if (signHref.contains("member.php?mod=logging")) {
                    data.loginRequired = true
                }
            } else {
                // 2c. 兜底：查找包含 "签到" 图标文字的元素
                val signIcon = doc.select("i:contains(签到), span:contains(签到), em:contains(签到)").first()
                if (signIcon != null) {
                    data.signInText = signIcon.text().trim()
                }
            }
        }

        // 2d. 提取真实的签到 AJAX URL（k_misign 插件）
        val signUrlMatcher = Pattern.compile("(plugin\\.php\\?id=k_misign[^'\"\\s]*)").matcher(html ?: "")
        if (signUrlMatcher.find()) {
            var ajaxUrl = signUrlMatcher.group(1)
            if (!ajaxUrl.startsWith("http")) {
                ajaxUrl = BASE_DOMAIN + ajaxUrl
            }
            // 替换 formhash 占位符为实际值
            val fh = data.formhash
            if (fh != null && !fh.isEmpty()) {
                ajaxUrl = Regex("formhash=[a-f0-9]*").replace(ajaxUrl, "formhash=" + fh)
            }
            data.signInUrl = ajaxUrl
        }

        // === 3. 解析4项统计数据 ===
        // 统计数据通常位于页面顶部或版块列表区域上方，格式如 "今日23 昨日4584 帖子4474223 会员345343"
        val pageText = if (doc.body() != null) doc.body().text() else ""

        // 今日
        val todayPtn = Pattern.compile("今日[：:\\s]*([0-9,，]+)")
        var m = todayPtn.matcher(pageText)
        if (m.find()) {
            data.todayPosts = parseIntFromText(m.group(1))
        }

        // 昨日
        val yesterdayPtn = Pattern.compile("昨日[：:\\s]*([0-9,，]+)")
        m = yesterdayPtn.matcher(pageText)
        if (m.find()) {
            data.yesterdayPosts = parseIntFromText(m.group(1))
        }

        // 帖子总数
        val postsPtn = Pattern.compile("帖子[：:\\s]*([0-9,，]+)")
        m = postsPtn.matcher(pageText)
        if (m.find()) {
            data.totalPosts = parseIntFromText(m.group(1))
        }

        // 会员总数
        val membersPtn = Pattern.compile("会员[：:\\s]*([0-9,，]+)")
        m = membersPtn.matcher(pageText)
        if (m.find()) {
            data.totalMembers = parseIntFromText(m.group(1))
        }

        // 方法2：如果上述正则没匹配到，尝试从特定 CSS 选择器中提取
        if (data.todayPosts == 0 && data.yesterdayPosts == 0) {
            // Comiis 模板中统计信息可能位于 div.comiis_stats 或类似容器中
            val statsEl = doc.select("div.comiis_stats, div.stats, .forum_stats, .statistic").first()
            if (statsEl != null) {
                val statsText = statsEl.text()
                m = todayPtn.matcher(statsText)
                if (m.find()) data.todayPosts = parseIntFromText(m.group(1))
                m = yesterdayPtn.matcher(statsText)
                if (m.find()) data.yesterdayPosts = parseIntFromText(m.group(1))
                m = postsPtn.matcher(statsText)
                if (m.find()) data.totalPosts = parseIntFromText(m.group(1))
                m = membersPtn.matcher(statsText)
                if (m.find()) data.totalMembers = parseIntFromText(m.group(1))
            }
        }

        // === 3. 解析全量版块列表 ===
        // 复用 parseForumCategories 的解析逻辑，将结果扁平化为 Forum 列表
        val categories = parseForumCategories(html)
        val allForums = ArrayList<ForumCategory.Forum>()
        for (cat in categories) {
            if (cat.forums != null) {
                for (forum in cat.forums!!) {
                    allForums.add(forum)
                }
            }
        }
        data.forums = allForums
        data.categories = categories

        return data
    }

    /**
     * 解析桌面版论坛列表页(forum.php?forumlist=1&mobile=no)各版块的统计:
     * 主题数、总帖数、今日新帖。
     * 结构:<dl><dt><a href="...forum-41-1.html">版块名</a><em title="今日"> (860)</em></dt>
     *       <dd class="kmlineheight"><em>主题: 145</em>, <em>帖数: <span title="12714">1万</span></em></dd></dl>
     *
     * @return Map<fid, long[]> 其中 long[0]=主题数, long[1]=总帖数, long[2]=今日新帖
     */
    @JvmStatic
    fun parseDesktopForumStats(html: String?): MutableMap<String, LongArray> {
        val result = HashMap<String, LongArray>()
        if (TextUtils.isEmpty(html)) return result
        try {
            val doc = Jsoup.parse(html!!)
            val dts = doc.select("dl dt")
            val fidPtn = Pattern.compile("forum-(\\d+)-1\\.html")
            for (dt in dts) {
                val a = dt.select("a[href*=forum-]").first() ?: continue
                val m = fidPtn.matcher(a.attr("href"))
                if (!m.find()) continue
                val fid = m.group(1)
                val stats = LongArray(3)
                // 今日新帖:<em title="今日"> (860)</em>
                val todayEm = dt.select("em[title=今日]").first()
                if (todayEm != null) {
                    val tText = Regex("[^0-9]").replace(todayEm.text(), "").trim()
                    if (!tText.isEmpty()) stats[2] = tText.toLong()
                }
                // 主题/帖数:从 dt 所在 dl 的第一个 dd 中取
                val dlEl = dt.parent()?.parent()
                var dd: Element? = null
                if (dlEl != null) dd = dlEl.select("dd").first()
                if (dd != null) {
                    val mt = Pattern.compile("主题[::\\s]*([0-9,,]+)").matcher(dd.text())
                    if (mt.find()) stats[0] = parseLongFromText(mt.group(1))
                    // 帖数优先取 <span title="12714"> 的 title 精确值
                    val postSpan = dd.select("span[title]").first()
                    if (postSpan != null && !postSpan.attr("title").isEmpty()) {
                        stats[1] = parseLongFromText(postSpan.attr("title"))
                    } else {
                        val mp = Pattern.compile("帖[数子][::\\s]*([0-9,,]+)").matcher(dd.text())
                        if (mp.find()) stats[1] = parseLongFromText(mp.group(1))
                    }
                }
                result[fid] = stats
            }
        } catch (ignored: Exception) {
        }
        return result
    }

    /**
     * 解析版块首页头部信息(forum-XX-1.html):
     * <h2 class="f_f">逆向交流</h2>
     * <p class="f_f comiis_tm8">今日 862&nbsp;&nbsp;帖子 2221581&nbsp;&nbsp;关注 5358</p>
     * <p class="f_f comiis_tm8">逆向技术交流分享,求助帖请发到其它版块~</p>
     *
     * @return String[] {描述, 今日数, 总帖数},缺失字段为空/0
     */
    @JvmStatic
    fun parseForumHeaderInfo(html: String?): Array<String?> {
        val info = arrayOfNulls<String>(3)
        if (TextUtils.isEmpty(html)) return info
        try {
            val doc = Jsoup.parse(html!!)
            var statsLine = ""

            // === 方案1:定位第一个包含"今日"统计的 p.comiis_tm8(Comiis 版块头) ===
            var statsP: Element? = null
            for (el in doc.allElements) {
                if (el.tagName() == "p" && el.hasClass("comiis_tm8")) {
                    val t = el.text()
                    if (t.contains("今日") || t.contains("帖子")) {
                        statsP = el
                        break
                    }
                }
            }

            if (statsP != null) {
                statsLine = statsP.text().trim()
                // 紧随其后的下一个 p 即版块描述
                var next = statsP.nextElementSibling()
                while (next != null && next.tagName() != "p") {
                    next = next.nextElementSibling()
                }
                if (next != null) {
                    val d = next.text().trim()
                    if (!d.isEmpty()) info[0] = d
                }
            }

            // === 方案2(兜底):h2.f_f 容器内的 p 组合 ===
            if (info[0] == null || info[0]!!.isEmpty()) {
                val h2 = doc.select("h2.f_f").first()
                val h2Parent = if (h2 != null) h2.parent() else null
                if (h2Parent != null) {
                    val ps = h2Parent.select(":scope > p")
                    for (p in ps) {
                        val t = p.ownText().trim()
                        if (t.isEmpty()) continue
                        if (statsLine.isEmpty() && (t.contains("今日") || t.contains("帖子"))) {
                            statsLine = t
                        } else if (info[0] == null || info[0]!!.isEmpty()) {
                            info[0] = t
                        }
                    }
                }
            }

            // === 提取统计数值 ===
            if (!statsLine.isEmpty()) {
                var m = Pattern.compile("今日[\\s\\u00A0]*([0-9,,]+)").matcher(statsLine)
                if (m.find()) info[1] = parseLongFromText(m.group(1)).toString()
                m = Pattern.compile("帖子[\\s\\u00A0]*([0-9,,]+)").matcher(statsLine)
                if (m.find()) info[2] = parseLongFromText(m.group(1)).toString()
            }
        } catch (ignored: Exception) {
        }
        return info
    }

    /**
     * 从文本中解析长整型数值(兼容千分位逗号)
     */
    private fun parseLongFromText(text: String?): Long {
        if (text == null) return 0
        val cleaned = Regex("[^0-9]").replace(text, "")
        if (cleaned.isEmpty()) return 0
        try {
            return cleaned.toLong()
        } catch (e: NumberFormatException) {
            return 0
        }
    }

    /**
     * 社区页面数据容器（内部静态类，用于封装 parseCommunityPage 的返回值）
     */
    class CommunityPageData {
        var signInText: String = ""
        var signInUrl: String = ""
        var formhash: String = ""
        var alreadySignedIn: Boolean = false
        var loginRequired: Boolean = false
        var todayPosts: Int = 0
        var yesterdayPosts: Int = 0
        var totalPosts: Int = 0
        var totalMembers: Int = 0
        var forums: MutableList<ForumCategory.Forum> = ArrayList<ForumCategory.Forum>()
        var categories: MutableList<ForumCategory> = ArrayList<ForumCategory>()
    }
}
