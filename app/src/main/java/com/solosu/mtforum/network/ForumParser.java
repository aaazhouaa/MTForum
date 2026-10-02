package com.solosu.mtforum.network;

import android.text.TextUtils;

import com.solosu.mtforum.model.ChatMessage;
import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.model.Friend;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 论坛 HTML 解析器
 * 解析 Discuz! 移动端输出的结构化 HTML
 */
public class ForumParser {

    private static final String BASE_DOMAIN = "https://bbs.binmt.cc/";

    public static String getBaseDomain() {
        return BASE_DOMAIN;
    }

    /**
     * 解析首页/帖子列表（Comiis App 模板适配）
     */
    public static List<Thread> parseThreadList(String html) {
        List<Thread> threads = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Comiis App 模板的帖子容器
        Elements items = doc.select("li.forumlist_li");
        if (items.isEmpty()) {
            // 兜底：查找包含 thread- 链接的 li 元素
            Elements links = doc.select("a[href*=thread-]");
            for (Element link : links) {
                Element parent = link.closest("li");
                if (parent != null) {
                    items.add(parent);
                }
            }
        }

        // 预编译正则
        Pattern tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html");
        Pattern fidPattern = Pattern.compile("forum-(\\d+)-1\\.html");

        for (Element item : items) {
            try {
                Thread t = new Thread();

                // === 标题 & tid ===
                Element titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first();
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first();
                }
                if (titleLink == null) continue;

                String href = titleLink.attr("href");
                Matcher tidMatcher = tidPattern.matcher(href);
                if (!tidMatcher.find()) continue;
                t.setTid(tidMatcher.group(1));

                // 标题：只取 ownText（排除热度徽章等子元素文本）
                String title = titleLink.ownText().trim();
                if (title.isEmpty()) title = titleLink.text().trim();
                t.setTitle(title);

                // === 作者 ===
                Element authorEl = item.select(".forumlist_li_top .top_user").first();
                if (authorEl != null) {
                    t.setAuthor(authorEl.text().trim());
                    // 从作者链接中提取 UID
                    String authorHref = authorEl.attr("href");
                    Matcher m = Pattern.compile("uid=(\\d+)").matcher(authorHref);
                    if (m.find()) t.setAuthorUid(m.group(1));
                }

                // === 头像 ===
                Element avatarEl = item.select(".forumlist_li_top .top_tximg").first();
                if (avatarEl != null) {
                    String src = avatarEl.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    t.setAvatarUrl(src);
                }

                // === 等级 ===
                Element levelEl = item.select(".forumlist_li_top .top_lev").first();
                if (levelEl != null) {
                    t.setAuthorLevel(levelEl.text().trim());
                }

                // === 发布时间 ===
                Element timeEl = item.select(".forumlist_li_time .f_d").first();
                if (timeEl != null) {
                    t.setPublishTime(timeEl.text().trim());
                }

                // === 版块 ===
                Element forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first();
                if (forumLink != null) {
                    // 版块名称：ownText 排除 <i> 图标文字
                    String forumName = forumLink.ownText().trim();
                    if (forumName.isEmpty()) forumName = forumLink.text().trim();
                    t.setForumName(forumName);

                    String forumHref = forumLink.attr("href");
                    Matcher fidMatcher = fidPattern.matcher(forumHref);
                    if (fidMatcher.find()) t.setForumFid(fidMatcher.group(1));
                }

                // === 内容摘要 ===
                Element summaryEl = item.select(".list_body .f_b").first();
                if (summaryEl != null) {
                    t.setSummary(summaryEl.text().trim());
                }

                // === 统计信息（点赞/回复/查看） ===
                Elements stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm");
                // 顺序：第1个=点赞，第2个=回复，第3个=查看
                if (stats.size() >= 3) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                    t.setViews(parseIntFromText(stats.get(2).text()));
} else if (stats.size() >= 2) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                }

                // === 特殊标记 ===
                // 图片检测
                t.setHasImage(item.select(".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img").size() > 0);
                
                // 提取帖子封面图URL
                Element imgEl = item.select(".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img").first();
                if (imgEl != null) {
                    String imgSrc = firstNonEmptyAttr(imgEl, "comiis_loadimages", "data-original", "data-src", "data-file", "file", "src");
                    if (!TextUtils.isEmpty(imgSrc)) {
                        String fullUrl = resolveAttachmentUrl(imgSrc);
                        if (isPostImageUrl(fullUrl)) {
                            t.setThumbnailUrl(fullUrl);
                        }
                    }
                }

// === 隐藏内容检测 ===
                Element bodyText = item.select(".list_body .f_b").first();
                if (bodyText != null && bodyText.text().contains("本内容被作者隐藏")) {
                    t.setHasHiddenContent(true);
                }

                populateThreadImages(item, t);
                t.setSticky(isStickyThread(item));

                threads.add(t);
            } catch (Exception ignored) {}
        }

        return threads;
    }

    /**
     * 解析搜索结果列表
     * 解析搜索结果列表（Comiis 手机版模板适配）
     * 手机版搜索结果页使用与首页/版块列表相同的 Comiis 模板结构。
     *
     * DOM 结构（手机版搜索结果页）：
     *   div.comiis_forumlist.comiis_xznlist > ul
     *     li.forumlist_li.comiis_znalist ── 每个搜索结果条目
     *       .forumlist_li_top
     *         img.top_tximg                        ── 头像
     *         .top_user                            ── 作者
     *         .top_lev                             ── 等级
     *         .forumlist_li_time .f_d              ── 发布时间
     *       .mmlist_li_box h2 a[href*=thread-]     ── 标题链接（含 tid）
     *       .list_body a.f_b                       ── 内容摘要
     *       .comiis_xznalist_bk a[href*=forum-]    ── 版块
     *       .comiis_xznalist_bottom ul li .comiis_tm ── 点赞/回复/查看数
     *
     * @param html 搜索结果页 HTML
     * @return 帖子列表
     */
    public static List<Thread> parseSearchResults(String html) {
        List<Thread> threads = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Comiis 手机版搜索结果容器
        Elements items = doc.select("li.forumlist_li");
        if (items.isEmpty()) {
            // 兜底：查找包含 thread- 链接的 li 元素
            Elements links = doc.select("a[href*=thread-]");
            for (Element link : links) {
                Element parent = link.closest("li");
                if (parent != null) {
                    items.add(parent);
                }
            }
        }

        // 预编译正则
        Pattern tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html");
        Pattern fidPattern = Pattern.compile("forum-(\\d+)-1\\.html");

        for (Element item : items) {
            try {
                Thread t = new Thread();

                // === 标题 & tid ===
                Element titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first();
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first();
                }
                if (titleLink == null) continue;

                String href = titleLink.attr("href");
                Matcher tidMatcher = tidPattern.matcher(href);
                if (!tidMatcher.find()) continue;
                t.setTid(tidMatcher.group(1));

                // 标题：用 text() 获取完整文本（高亮关键词被 <strong><font> 包裹）
                String title = titleLink.text().trim();
                if (title.isEmpty()) continue;
                t.setTitle(title);

                // === 作者 ===
                Element authorEl = item.select(".forumlist_li_top .top_user").first();
                if (authorEl != null) {
                    t.setAuthor(authorEl.text().trim());
                    String authorHref = authorEl.attr("href");
                    Matcher m = Pattern.compile("uid=(\\d+)").matcher(authorHref);
                    if (m.find()) t.setAuthorUid(m.group(1));
                }

                // === 头像 ===
                Element avatarEl = item.select(".forumlist_li_top .top_tximg").first();
                if (avatarEl != null) {
                    String src = avatarEl.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    t.setAvatarUrl(src);
                }

                // === 等级 ===
                Element levelEl = item.select(".forumlist_li_top .top_lev").first();
                if (levelEl != null) {
                    t.setAuthorLevel(levelEl.text().trim());
                }

                // === 发布时间 ===
                Element timeEl = item.select(".forumlist_li_time .f_d").first();
                if (timeEl != null) {
                    t.setPublishTime(timeEl.text().trim());
                }

                // === 版块 ===
                Element forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first();
                if (forumLink != null) {
                    String forumName = forumLink.ownText().trim();
                    if (forumName.isEmpty()) forumName = forumLink.text().trim();
                    t.setForumName(forumName);

                    String forumHref = forumLink.attr("href");
                    Matcher fidMatcher = fidPattern.matcher(forumHref);
                    if (fidMatcher.find()) t.setForumFid(fidMatcher.group(1));
                }

                // === 内容摘要 ===
                Element summaryEl = item.select(".list_body .f_b").first();
                if (summaryEl != null) {
                    String summaryText = summaryEl.text().trim();
                    t.setSummary(summaryText);
                    if (summaryText.contains("本内容被作者隐藏")) {
                        t.setHasHiddenContent(true);
                    }
                }

                // === 统计信息（点赞/回复/查看） ===
                Elements stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm");
                if (stats.size() >= 3) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                    t.setViews(parseIntFromText(stats.get(2).text()));
                } else if (stats.size() >= 2) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                }

                // === 图片检测 ===
                t.setHasImage(item.select(".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img").size() > 0);
                
                // 提取帖子封面图URL
                Element imgEl = item.select(".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img").first();
                if (imgEl != null) {
                    String imgSrc = firstNonEmptyAttr(imgEl, "comiis_loadimages", "data-original", "data-src", "data-file", "file", "src");
                    if (!TextUtils.isEmpty(imgSrc)) {
                        String fullUrl = resolveAttachmentUrl(imgSrc);
                        if (isPostImageUrl(fullUrl)) {
                            t.setThumbnailUrl(fullUrl);
                        }
                    }
                }

                populateThreadImages(item, t);
                threads.add(t);
            } catch (Exception ignored) {}
        }

        return threads;
    }

    /**
     * 解析版块内帖子列表（forumdisplay 页面，Comiis App 模板适配）
     * 与 parseThreadList() 使用相同 Comiis 模板选择器
     */
    public static List<Thread> parseForumThreadList(String html) {
        List<Thread> threads = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Comiis App 模板的帖子容器
        Elements items = doc.select("li.forumlist_li");
        if (items.isEmpty()) {
            // 兜底:查找包含 thread- 链接的 li 元素
            Elements links = doc.select("a[href*=thread-]");
            for (Element link : links) {
                Element parent = link.closest("li");
                if (parent != null) {
                    items.add(parent);
                }
            }
        }

        // 预编译正则
        Pattern tidPattern = Pattern.compile("thread-(\\d+)-1-1\\.html");
        Pattern fidPattern = Pattern.compile("forum-(\\d+)-1\\.html");

        for (Element item : items) {
            try {
                Thread t = new Thread();

                // === 标题 & tid ===
                Element titleLink = item.select(".mmlist_li_box h2 a[href*=thread-]").first();
                if (titleLink == null) {
                    titleLink = item.select("a[href*=thread-]").first();
                }
                if (titleLink == null) continue;

                String href = titleLink.attr("href");
                Matcher tidMatcher = tidPattern.matcher(href);
                if (!tidMatcher.find()) continue;
                t.setTid(tidMatcher.group(1));

                // 标题：只取 ownText（排除热度徽章等子元素文本）
                String title = titleLink.ownText().trim();
                if (title.isEmpty()) title = titleLink.text().trim();
                t.setTitle(title);

                // === 作者 ===
                Element authorEl = item.select(".forumlist_li_top .top_user").first();
                if (authorEl != null) {
                    t.setAuthor(authorEl.text().trim());
                    // 从作者链接中提取 UID
                    String authorHref = authorEl.attr("href");
                    Matcher m = Pattern.compile("uid=(\\d+)").matcher(authorHref);
                    if (m.find()) t.setAuthorUid(m.group(1));
                }

                // === 头像 ===
                Element avatarEl = item.select(".forumlist_li_top .top_tximg").first();
                if (avatarEl != null) {
                    String src = avatarEl.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    t.setAvatarUrl(src);
                }

                // === 等级 ===
                Element levelEl = item.select(".forumlist_li_top .top_lev").first();
                if (levelEl != null) {
                    t.setAuthorLevel(levelEl.text().trim());
                }

                // === 发布时间 ===
                Element timeEl = item.select(".forumlist_li_time .f_d").first();
                if (timeEl != null) {
                    t.setPublishTime(timeEl.text().trim());
                }

                // === 版块 ===
                Element forumLink = item.select(".comiis_xznalist_bk a[href*=forum-]").first();
                if (forumLink != null) {
                    // 版块名称：ownText 排除 <i> 图标文字
                    String forumName = forumLink.ownText().trim();
                    if (forumName.isEmpty()) forumName = forumLink.text().trim();
                    t.setForumName(forumName);

                    String forumHref = forumLink.attr("href");
                    Matcher fidMatcher = fidPattern.matcher(forumHref);
                    if (fidMatcher.find()) t.setForumFid(fidMatcher.group(1));
                }

                // === 内容摘要 ===
                Element summaryEl = item.select(".list_body .f_b").first();
                if (summaryEl != null) {
                    t.setSummary(summaryEl.text().trim());
                }

                // === 统计信息（点赞/回复/查看） ===
                Elements stats = item.select(".comiis_xznalist_bottom ul li .comiis_tm");
                // 顺序：第1个=点赞，第2个=回复，第3个=查看
                if (stats.size() >= 3) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                    t.setViews(parseIntFromText(stats.get(2).text()));
} else if (stats.size() >= 2) {
                    t.setLikes(parseIntFromText(stats.get(0).text()));
                    t.setReplies(parseIntFromText(stats.get(1).text()));
                }

                // === 特殊标记 ===
                // 图片检测
                t.setHasImage(item.select(".mmlist_li_box .comiis_pyqlist_imgs, .mmlist_li_box .comiis_pyqlist_img").size() > 0);
                
                // 提取帖子封面图URL
                Element imgEl = item.select(".mmlist_li_box .comiis_pyqlist_imgs img, .mmlist_li_box .comiis_pyqlist_img img, .mmlist_li_box img").first();
                if (imgEl != null) {
                    String imgSrc = firstNonEmptyAttr(imgEl, "comiis_loadimages", "data-original", "data-src", "data-file", "file", "src");
                    if (!TextUtils.isEmpty(imgSrc)) {
                        String fullUrl = resolveAttachmentUrl(imgSrc);
                        if (isPostImageUrl(fullUrl)) {
                            t.setThumbnailUrl(fullUrl);
                        }
                    }
                }

                // 隐藏内容检测
                Element bodyText = item.select(".list_body .f_b").first();
                if (bodyText != null && bodyText.text().contains("本内容被作者隐藏")) {
                    t.setHasHiddenContent(true);
                }

                populateThreadImages(item, t);

                // 置顶标记兼容 Comiis 的“热度”徽章和常见 sticky 标记。
                t.setSticky(isStickyThread(item));

                threads.add(t);
            } catch (Exception ignored) {}
        }

        return threads;
    }

    private static boolean isStickyThread(Element item) {
        if (item == null) return false;
        if (!item.select("[title*=\"置顶\"], [class*=\"sticky\"], [class*=\"thread_sticky\"], [class*=\"threadtop\"]").isEmpty()) {
            return true;
        }
        for (Element marker : item.select("span, em, i, label")) {
            String text = marker.text().trim();
            if (text.equals("置顶") || text.contains("置顶")) return true;
        }
        return false;
    }
    /**
     * 解析版块分类列表（Comiis App 模板适配）
     * 数据来源：forum.php?forumlist=1&mobile=2
     *
     * DOM 结构（forumlist 页）：
     *   div.comiis_forumlist.comiis_km{gid} ── 每个分组
     *     div.comiis_bbs_show h2 a               ── 分组标题（如"MT专区"）
     *     div#sub_forum_{gid}.comiis_forum_nbox
     *       ul li a[href*=forum-]                ── 子版块链接
     *         img[alt]                              ── 版块名称
     *         img[src]                              ── 版块图标
     *
     * 备用结构（发帖弹窗页，部分 Comiis 版本仍在使用）：
     *   div.comiis_bbslists_gid ul > li.comiis_fxpostlistkey[fid={gid}]  ── 分组
     *     ul.comiis_fxpostlistbox_{gid} > li      ── 子版块
     */
    public static List<ForumCategory> parseForumCategories(String html) {
        List<ForumCategory> categories = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // === 主方案：forumlist 页面结构（forum.php?forumlist=1&mobile=2） ===
        Elements categoryBlocks = doc.select("div.comiis_forumlist");
        if (categoryBlocks.isEmpty()) {
            // 兜底：尝试发帖弹窗选择结构
            return parseForumCategoriesFromPostDialog(doc);
        }

        Pattern fidPattern = Pattern.compile("forum-(\\d+)-1\\.html");

        for (Element block : categoryBlocks) {
            try {
                // 分组标题：div.comiis_bbs_show h2 a
                Element titleEl = block.select("div.comiis_bbs_show h2 a").first();
                if (titleEl == null) continue;
                String categoryName = titleEl.text().trim();
                if (categoryName.isEmpty()) continue;

                // 该分组下的子版块
                List<ForumCategory.Forum> forums = new ArrayList<>();
                Elements forumLinks = block.select("div.comiis_forum_nbox ul li a[href*=forum-]");
                for (Element link : forumLinks) {
                    String href = link.attr("href");
                    Matcher m = fidPattern.matcher(href);
                    if (!m.find()) continue;
                    String fid = m.group(1);

                    // 版块名称：优先从 img[alt] 获取（更可靠）
                    Element img = link.select("img[alt]").first();
                    String fName = (img != null && !img.attr("alt").isEmpty())
                            ? img.attr("alt").trim()
                            : link.select("p").text().trim();
                    if (fName.isEmpty()) continue;

                    ForumCategory.Forum forum = new ForumCategory.Forum(fid, fName);

                    // 版块图标
                    Element iconImg = link.select("em img").first();
                    if (iconImg != null) {
                        String src = iconImg.attr("src");
                        if (!src.isEmpty()) {
                            if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                            forum.setIconUrl(src);
                        }
                    }

                    // ★ 今日新帖徽章 <span class="bg_a f_f">860</span>(部分版块无)
                    Element todaySpan = link.select("em span.bg_a.f_f").first();
                    if (todaySpan != null) {
                        int today = parseIntFromText(todaySpan.text());
                        forum.setTodayPosts(today);
                        forum.setTotalThreads(today > 0 ? today : 0);
                    }

                    forums.add(forum);
                }

                if (!forums.isEmpty()) {
                    categories.add(new ForumCategory(categoryName, forums));
                }
            } catch (Exception ignored) {}
        }

        return categories;
    }

    /**
     * 备用解析：发帖弹窗中的版块选择结构（部分 Comiis 版本）
     * 分组 ul 中 li.comiis_fxpostlistkey[fid={gid}] ── 分组标题
     * 对应 ul.comiis_fxpostlistbox_{gid} > li       ── 子版块
     */
    private static List<ForumCategory> parseForumCategoriesFromPostDialog(Document doc) {
        List<ForumCategory> categories = new ArrayList<>();

        Elements groupLis = doc.select("div.comiis_bbslists_gid ul > li.comiis_fxpostlistkey");
        for (Element groupLi : groupLis) {
            try {
                String gid = groupLi.attr("fid");
                String categoryName = groupLi.select("a").text().trim();
                if (categoryName.isEmpty() || gid.isEmpty()) continue;

                List<ForumCategory.Forum> forums = new ArrayList<>();
                Elements subLis = doc.select("ul.comiis_fxpostlistbox_" + gid + " > li");
                for (Element subLi : subLis) {
                    Element link = subLi.select("a.bbslist_ico[href*=fid=]").first();
                    if (link == null) continue;
                    String href = link.attr("href");
                    String fid = extractParam(href, "fid");
                    if (fid == null) continue;

                    // 版块名称：优先 img[alt]
                    Element img = link.select("img[alt]").first();
                    String fName = img != null ? img.attr("alt").trim() : "";
                    if (fName.isEmpty()) {
                        Element nameEl = subLi.select("a.post_tit em").first();
                        if (nameEl != null) fName = nameEl.text().trim();
                    }
                    if (fName.isEmpty()) continue;

                    ForumCategory.Forum forum = new ForumCategory.Forum(fid, fName);

                    if (img != null) {
                        String src = img.attr("src");
                        if (!src.isEmpty()) {
                            if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                            forum.setIconUrl(src);
                        }
                    }

                    // ★ 今日新帖徽章
                    Element todaySpan = subLi.select("em span.bg_a.f_f").first();
                    if (todaySpan != null) {
                        int today = parseIntFromText(todaySpan.text());
                        forum.setTodayPosts(today);
                        forum.setTotalThreads(today > 0 ? today : 0);
                    }

                    forums.add(forum);
                }

                if (!forums.isEmpty()) {
                    categories.add(new ForumCategory(categoryName, forums));
                }
            } catch (Exception ignored) {}
        }

        return categories;
    }

    /**
     * 检测服务器返回的 HTML 是否为登录页（Cookie 已过期/未登录）
     * 通过检查 Discuz! 登录表单特征来判断
     *
     * 注意：个人空间子页面（好友/粉丝/收藏/我的帖子/积分详情）各自有不同的 DOM 结构，
     * 检测时必须覆盖这些结构的"已登录特征"，否则会被误判为登录页。
     */
    public static boolean isLoginPage(String html) {
        if (TextUtils.isEmpty(html)) return true;
        Document doc = Jsoup.parse(html);

        // 明确登录页特征必须优先判断。登录页通常仍包含首页/热帖 thread- 链接，
        // 若先判断“多个 thread 链接”，会把登录页误判为已登录收藏页。
        Element loginForm = doc.select(
                "form[method=post][action*=login], form[method=post][action*=logging]").first();
        if (loginForm != null) return true;
        String pageTitle = doc.title().toLowerCase();
        if (pageTitle.contains("登录") || pageTitle.contains("login")) return true;
        if (doc.select("input[type=password]").size() > 0) return true;

        // === 正向确认：页面含有已登录特征 → 快速返回 false ===
        // 这些特征出现在已登录的各种页面中，优先级高于登录表单检测（避免误判）

        // 特征A：好友/粉丝列表 — 存在多个 a[href*=uid=] 链接（含用户名文本）
        Elements uidLinks = doc.select("a[href*=uid=]");
        if (uidLinks.size() >= 2) {
            boolean hasUsernames = false;
            for (Element link : uidLinks) {
                if (!TextUtils.isEmpty(link.text().trim())) {
                    hasUsernames = true;
                    break;
                }
            }
            if (hasUsernames) return false;
        }

        // 特征B：收藏/帖子列表 — 存在多个 a[href*=thread-] 链接（含标题文本）
        Elements threadLinks = doc.select("a[href*=thread-]");
        if (threadLinks.size() >= 2) {
            boolean hasTitles = false;
            for (Element link : threadLinks) {
                if (!TextUtils.isEmpty(link.text().trim())) {
                    hasTitles = true;
                    break;
                }
            }
            if (hasTitles) return false;
        }

        // 特征C：帖子列表（Comiis 模板结构）
        if (doc.select("li.forumlist_li").size() > 0) return false;

        // 特征D：个人空间/资料页
        if (doc.select(".comiis_space_tx, .comiis_space_profile, .comiis_space_profileico, .comiis_space_profilejf").size() > 0) {
            return false;
        }

        // 走到这里：没有任何已登录特征，也没有明确的登录页特征
        // 安全的做法是返回 false（不武断判定为登录页），让调用方自行根据解析结果判断
        return false;
    }

    /**
     * 解析网页端关注状态。
     *
     * @return 1=已关注，0=未关注，-1=网页未提供可确认状态
     */
    public static int parseFollowState(String html) {
        if (TextUtils.isEmpty(html)) return -1;
        Document doc = Jsoup.parse(html);
        Elements buttons = doc.select(
                ".comiis_space_flw a#followmod, .comiis_space_flw a.followmod, "
                        + ".comiis_space_tx a#followmod, .comiis_space_tx a.followmod, "
                        + "a#followmod, a.followmod");
        if (buttons.isEmpty()) return -1;

        boolean sawAdd = false;
        boolean sawBg0 = false;
        for (Element button : buttons) {
            // Jsoup 会把 &amp; 还原，但这里再统一解码一次，兼容部分页面把链接编码在属性中。
            String href = button.attr("href").replace("&amp;", "&").toLowerCase();
            String text = button.text().trim();
            String classes = button.className().toLowerCase();

            // Comiis 已关注按钮的服务端/JS状态：op=del、已关注/取消关注、bg_b。
            if (href.contains("op=del") || text.contains("已关注")
                    || text.contains("取消关注") || classes.contains("bg_b")) {
                return 1;
            }
            // 未关注按钮的状态：op=add；未登录提示也只能表示当前未建立关注关系。
            if (href.contains("op=add") || href.contains("logging&action=login")
                    || href.contains("logging%26action%3dlogin")) {
                sawAdd = true;
            }
            // 登录后的 Comiis 页面有时只保留 bg_0，不输出 op=add href。
            // 注意不能把 bg_a（未登录按钮）当成明确未关注。
            if (classes.contains("bg_0")) sawBg0 = true;
        }
        if (sawAdd || sawBg0) return 0;
        return -1;
    }

    /** 判断关注列表页面是否包含可识别的用户条目或明确的空列表标记。 */
    public static boolean isFollowingListPage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        Document doc = Jsoup.parse(html);
        if (!doc.select(".comiis_userlist01, .comiis_friend_boxs, .comiis_follow_box, .comiis_followlist").isEmpty()) {
            return true;
        }
        // 不同版本模板可能省略外层 class，但仍会保留关注列表语义和用户条目。
        String bodyText = doc.body() == null ? "" : doc.body().text();
        boolean hasFollowTitle = bodyText.contains("我的关注") || bodyText.contains("关注列表")
                || bodyText.contains("正在关注") || bodyText.contains("关注的人");
        boolean hasUserItem = !doc.select("li a[href*=uid=], a[href*=home.php?mod=space&uid=]").isEmpty();
        boolean hasEmptyMarker = bodyText.contains("暂无关注") || bodyText.contains("还没有关注")
                || bodyText.contains("没有关注");
        return hasFollowTitle && (hasUserItem || hasEmptyMarker);
    }

    /**
     * 解析用户信息
     * 适配 Comiis App 模板（bbs.binmt.cc 使用的第三方 Discuz! 模板）
     */
    public static UserProfile parseUserProfile(String html) {
        UserProfile profile = new UserProfile();
        Document doc = Jsoup.parse(html);

        // ============================================================
        // 1. 头部信息区：div.comiis_space_info > div.comiis_space_tx
        // ============================================================

        // 用户名：Comiis 模板使用 h2.fyy；兜底保留通用选择器
        Element usernameEl = doc.select(".comiis_space_tx h2, .username, .user_name, h1, .profile_name").first();
        if (usernameEl != null) profile.setUsername(usernameEl.text().trim());

        // 头像：★ 修复 — 必须限定在 .comiis_space_tx 范围内，否则会匹配到导航栏中的当前用户头像
        Element avatarEl = doc.select(".comiis_space_tx .user_img img, .comiis_space_tx img[src*=avatar], .comiis_space_tx img").first();
        // 兜底：从 avatar.php?uid=X 直接构造（最可靠）
        if (avatarEl == null) {
            avatarEl = doc.select("img[src*=avatar]").first();
        }
        if (avatarEl != null) {
            String src = avatarEl.attr("src");
            if (!src.startsWith("http")) src = BASE_DOMAIN + src;
            profile.setAvatarUrl(src);
        } else {
            // 最后兜底：如果解析到了UID，用 UID 构造头像 URL
            if (profile.getUid() != null && !profile.getUid().isEmpty()) {
                profile.setAvatarUrl(BASE_DOMAIN + "uc_server/avatar.php?uid=" + profile.getUid() + "&size=middle");
            }
        }

        // UID：从 "用户ID" 行前的 div.profile_rs 获取
        Element uidEl = doc.select(".comiis_space_profile li:contains(用户ID) .profile_rs, .uid, em:contains(UID)").first();
        if (uidEl != null) {
            String text = uidEl.text().trim();
            Matcher m = Pattern.compile("(\\d+)").matcher(text);
            if (m.find()) profile.setUid(m.group(1));
        }

        // 等级：Comiis 模板使用 span.kmlevs.kmlv（包含 Lv.X 文字）
        // ★ 修复：选择器优先级问题——第一个 .kmlevs 是性别图标（bg_boy/girl），不是等级。
        // 必须精确匹配同时包含 kmlevs 和 kmlv 类的元素（<span class="kmlevs kmlv">Lv.7</span>）
        // 去掉模糊的 .comiis_space_tx .kmlevs 以防止优先选中性别图标
        Element levelEl = doc.select(".comiis_space_tx .kmlevs.kmlv, .comiis_space_tx .kmlv, .level, .lv, em:contains(Lv)").first();
        if (levelEl != null) profile.setLevel(levelEl.text().trim());

        // 用户组：Comiis 模板使用 span.kmlev（如 "硕士生"）
        Element groupEl = doc.select(".comiis_space_tx .kmlev").first();
        if (groupEl != null) profile.setGroupName(groupEl.text().trim());
        // ============================================================
        // 1.1 当前登录用户对该用户的关注状态
        // ============================================================
        // 不能只看按钮文字：Comiis 在部分页面即使已关注也仍显示“关注”。
        // 统一按服务端生成的 href/class/确认弹窗解析；解析不到时保持 unknown，
        // 绝不能把 unknown 当成“未关注”，也不能用本地缓存覆盖它。
        int followState = parseFollowState(html);
        if (followState >= 0) {
            profile.setFollowed(followState == 1);
            profile.setFollowStateKnown(true);
        }

        // ============================================================
        // 2. 统计数据图标区：div.comiis_space_profileico
        // ============================================================

        // 结构：ul > li > a > i + span，内容如 "帖子 1"、"回复 22"、"好友 52680"、"粉丝 436"

        // 帖子数
        Element threadEl = doc.select(".comiis_space_profileico li:contains(帖子) span, em:contains(帖子) + span, .thread_count").first();
        if (threadEl != null) profile.setThreads(parseIntFromText(threadEl.text()));

        // 回复数 / 帖子数（UserProfile 有 posts 字段）
        Element postsEl = doc.select(".comiis_space_profileico li:contains(回复) span, em:contains(回复) + span, .posts, .user_posts").first();
        if (postsEl != null) profile.setPosts(parseIntFromText(postsEl.text()));

        // 好友
        Element friendEl = doc.select(".comiis_space_profileico li:contains(好友) span, em:contains(好友) + span, .friend_count").first();
        if (friendEl != null) profile.setFriends(parseIntFromText(friendEl.text()));

        // 关注数位于 .comiis_space_tx 顶部摘要中，例如“2 关注”。
        // 不能从 profileico 按固定位置取值：当前论坛实际顺序是 帖子/回复/好友/粉丝/人气，第四项不是关注。
        Element followingEl = null;
        for (Element span : doc.select(".comiis_space_tx p span")) {
            String text = span.text().trim();
            if (text.matches(".*\\d[\\d,，]*\\s*关注$")) {
                followingEl = span;
                break;
            }
        }
        if (followingEl == null) {
            followingEl = doc.select(".comiis_space_profileico li:contains(关注) span, em:contains(关注) + span, .following_count").first();
        }
        if (followingEl != null) {
            profile.setFollowing(parseIntFromText(followingEl.text()));
        }

        // 粉丝
        Element followerEl = doc.select(".comiis_space_profileico li:contains(粉丝) span, em:contains(粉丝) + span, .follower_count").first();
        if (followerEl != null) profile.setFollowers(parseIntFromText(followerEl.text()));

        // 人气/浏览
        Element viewsEl = doc.select(".comiis_space_profileico li:contains(人气) span").first();
        if (viewsEl != null) profile.setViews(parseIntFromText(viewsEl.text()));

        // ============================================================
        // 3. 积分/金币区域：div.comiis_space_profilejf
        // ============================================================
        // 结构：ul > li.b_t.b_r（4列：积分、好评、金币、信誉）
        Elements profileJfLis = doc.select(".comiis_space_profilejf ul li");

        // 积分（第1个 li）
        if (profileJfLis.size() >= 1) {
            Element creditsEl = profileJfLis.get(0).select(".f_0, span").first();
            if (creditsEl == null) creditsEl = profileJfLis.get(0); // 兜底取整个 li 文本
            profile.setCredits(parseIntFromText(creditsEl.text()));
        }

        // 金币（第3个 li）
        if (profileJfLis.size() >= 3) {
            Element goldEl = profileJfLis.get(2).select(".f_0, span").first();
            if (goldEl == null) goldEl = profileJfLis.get(2);
            profile.setGold(parseIntFromText(goldEl.text()));
        }

        // ============================================================
        // 4. 详细资料区：div.comiis_space_profile（UID、生日、性别、在线时间、注册时间、最后访问）
        // ============================================================
        // 结构：ul > li.b_t > div.profile_rs + span（标签名）

        // 注册时间
        Element regLi = doc.select(".comiis_space_profile li:contains(注册时间)").first();
        if (regLi != null) {
            Element regVal = regLi.select(".profile_rs").first();
            if (regVal != null) profile.setRegDate(regVal.text().trim());
        } else {
            // 兜底
            Element regEl = doc.select("li:contains(注册时间), .regdate").first();
            if (regEl != null) profile.setRegDate(regEl.text().replace("注册时间:", "").trim());
        }

        // 最后访问时间
        Element lastVisitLi = doc.select(".comiis_space_profile li:contains(最后访问)").first();
        if (lastVisitLi != null) {
            Element lastVisitVal = lastVisitLi.select(".profile_rs").first();
            if (lastVisitVal != null) profile.setLastVisit(lastVisitVal.text().trim());
        }

        // 在线时间
        Element onlineLi = doc.select(".comiis_space_profile li:contains(在线时间)").first();
        if (onlineLi != null) {
            Element onlineVal = onlineLi.select(".profile_rs").first();
            if (onlineVal != null) profile.setOnlineTime(onlineVal.text().trim());
        }

        // 性别
        Element genderLi = doc.select(".comiis_space_profile li:contains(性别)").first();
        if (genderLi != null) {
            Element genderVal = genderLi.select(".profile_rs").first();
            if (genderVal != null) {
                String g = genderVal.text().trim();
                if (g.contains("男")) profile.setGender("boy");
                else if (g.contains("女")) profile.setGender("girl");
            }
        }

        // ============================================================
        // 5. 签名区
        // ============================================================
        Element sigEl = doc.select(".profile_face, .signature, .sigin").first();
        if (sigEl != null) profile.setSignature(sigEl.text().trim());

        return profile;
    }

    /**
     * 解析消息/私信列表
     */
    public static List<Message> parseMessageList(String html) {
        List<Message> messages = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements items = doc.select("li.pm, li.msg, div.message_item, li[data-pmid]");
        for (Element item : items) {
            try {
                Message msg = new Message();

                Element link = item.select("a[href*=pmid=], a[href*=do=pm]").first();
                if (link != null) {
                    String pmid = extractParam(link.attr("href"), "pmid");
                    if (pmid != null) msg.setPmid(pmid);
                }

                Element titleEl = item.select(".title, .subject, h4").first();
                if (titleEl != null) msg.setTitle(titleEl.text().trim());

                Element authorEl = item.select(".author, .by, .from").first();
                if (authorEl != null) msg.setAuthor(authorEl.text().trim());

                Element timeEl = item.select(".time, .date, .dateline").first();
                if (timeEl != null) msg.setTime(timeEl.text().trim());

                Element summaryEl = item.select(".summary, .message_preview, p").first();
                if (summaryEl != null) msg.setSummary(summaryEl.text().trim());

                Element avatarEl = item.select("img[src*=avatar]").first();
                if (avatarEl != null) msg.setAvatarUrl(avatarEl.attr("src"));

                msg.setRead(item.select("em:contains(已读), .read").size() > 0);

                messages.add(msg);
            } catch (Exception ignored) {}
        }

        return messages;
    }

    /**
     * 解析 Comiis 移动版通知条目。
     *
     * 真实结构：li.b_b.bg_f.cl > .ntc_body + h2.f_d。
     * Comiis 移动模板不会使用桌面版的 div.nts/dl.cl，因此必须单独处理。
     */
    private static List<Message> parseComiisNoticeItems(Elements items) {
        List<Message> notices = new ArrayList<>();
        for (Element item : items) {
            try {
                Element body = item.select("div.ntc_body").first();
                if (body == null) continue;

                Message notice = new Message();
                notice.setType(0);

                // 某些版本会给未读条目添加 ntc_l/new/unread，已读条目添加 read/old。
                // 没有状态类时按已读处理，避免把历史通知全部误报成未读。
                boolean unread = item.hasClass("ntc_l") || item.hasClass("new")
                        || item.hasClass("unread") || item.hasClass("un_read")
                        || "0".equals(item.attr("data-read"))
                        || "unread".equalsIgnoreCase(item.attr("data-status"));
                boolean read = !unread;
                if (item.hasClass("read") || item.hasClass("old")
                        || "1".equals(item.attr("data-read"))) read = true;
                notice.setRead(read);

                // 移动版没有 notice 属性，使用屏蔽链接 id 中的通知 ID。
                Element ignore = item.select("h2 a[id^=a_note_]").first();
                if (ignore != null) {
                    Matcher idMatcher = Pattern.compile("a_note_(\\d+)").matcher(ignore.id());
                    if (idMatcher.find()) notice.setPmid(idMatcher.group(1));
                }

                Element author = body.select("a[href*=space-uid-], a[href*=uid=]").first();
                if (author != null) {
                    notice.setAuthor(author.text().trim());
                    String href = author.attr("href");
                    Matcher uidMatcher = Pattern.compile("space-uid-(\\d+)").matcher(href);
                    if (uidMatcher.find()) notice.setAuthorUid(uidMatcher.group(1));
                    else {
                        String uid = extractParam(href, "uid");
                        if (!TextUtils.isEmpty(uid)) notice.setAuthorUid(uid);
                    }
                }

                Element avatar = item.select("a.notice_img img[src*=avatar], img[src*=avatar], img[src*=uc_server]").first();
                if (avatar != null) {
                    String src = avatar.attr("abs:src");
                    if (TextUtils.isEmpty(src)) src = avatar.attr("src");
                    if (!TextUtils.isEmpty(src) && !src.startsWith("http")) src = BASE_DOMAIN + src;
                    notice.setAvatarUrl(src);
                }

                Element titleLink = body.select("a[href*=forum.php?mod=redirect]").first();
                if (titleLink == null) titleLink = body.select("a[href*=thread-]").first();
                if (titleLink != null) notice.setSummary(titleLink.text().trim());

                String fullText = body.text().replace('\u00a0', ' ').trim();
                if (fullText.length() > 200) fullText = fullText.substring(0, 200);
                notice.setTitle(fullText);

                if (titleLink != null) {
                    Matcher tidMatcher = Pattern.compile("(?:ptid=|thread-)(\\d+)").matcher(titleLink.attr("href"));
                    if (tidMatcher.find()) {
                        String summary = notice.getSummary();
                        notice.setSummary((summary == null ? "" : summary) + " tid=" + tidMatcher.group(1));
                    }
                }

                Element time = item.select("h2.f_d").first();
                if (time != null) {
                    String timeText = time.ownText().replace('\u00a0', ' ').trim();
                    if (TextUtils.isEmpty(timeText)) timeText = time.text().replace('\u00a0', ' ').trim();
                    notice.setTime(timeText);
                }

                if (fullText.contains("回复了您的") || fullText.contains("评论了您的")) {
                    notice.setType(2);
                } else if (fullText.contains("系统") || fullText.contains("管理")
                        || fullText.contains("删除")) {
                    notice.setType(1);
                }
                notices.add(notice);
            } catch (Exception ignored) {
            }
        }
        return notices;
    }
/**
     * 2026-06-26 修复：基于真实 HTML 结构调整选择器，使用桌面版 URL（不加 &mobile=2）
     *
     * 真实 DOM 结构（桌面版）：
     *   div.xld.xlda
     *     div.nts
     *       dl.cl {notice="NOTICE_ID"}              ← 每条通知
     *         dd.m.avt.mbn                           ← 头像区域
     *           a[href*=space-uid-] > img[src*=avatar]
     *         dt                                     ← 屏蔽链接 + 时间
     *           a.d.b[href*=op=ignore]                屏蔽链接
     *           span.xg1.xw0 > span[title]            时间
     *         dd.ntc_body                            ← 通知内容
     *           a[href*=space-uid-]                   用户名（发送者）
     *           a[href*=forum.php?mod=redirect]       帖子标题链接
     *           a.lit[href*=findpost]                 查看链接
     *
     * @param html 通知列表页 HTML（桌面版，不含 &mobile=2）
     * @return 通知条目列表
     */
    public static List<Message> parseNoticeList(String html) {
        List<Message> notices = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return notices;

        Document doc = Jsoup.parse(html);

        // Comiis 移动版通知页面使用 li.b_b.bg_f.cl；优先走移动版解析器。
        Elements mobileItems = doc.select("li.b_b.bg_f.cl");
        if (!mobileItems.isEmpty()) {
            return parseComiisNoticeItems(mobileItems);
        }

        // 检查是否有“没有提醒内容”
        if (html.contains("没有提醒内容") || html.contains("暂无提醒")
                || html.contains("没有新的") || html.contains("notip")) {
            return notices;
        }

        // ★ 核心选择器：桌面版通知使用 dl.cl 元素，位于 div.nts 容器内
        Elements items = doc.select("div.nts > dl.cl");
        if (items.isEmpty()) {
            // 兜底：直接查找所有带 notice 属性的 dl
            items = doc.select("dl.cl[notice]");
        }
        if (items.isEmpty()) {
            // 最终兜底：查找所有 dl.cl
            items = doc.select("dl.cl");
        }

        for (Element item : items) {
            try {
                Message notice = new Message();
                notice.setType(0);

                // ★ 判断已读/未读：桌面版 Discuz! 未读通知的 dl.cl 含有 ntc_l 类
                // 已读通知没有 ntc_l 类，未读通知有 <dl class="cl ntc_l">
                notice.setRead(!item.hasClass("ntc_l"));

                // ★ 提取 notice ID（用于删除操作）
                String noticeId = item.attr("notice");
                if (!TextUtils.isEmpty(noticeId)) {
                    notice.setPmid(noticeId);
                }

                // ★ 提取发送者 UID（用于屏蔽操作）
                Element avatarLink = item.select("dd.avt a[href*=space-uid-]").first();
                if (avatarLink == null) {
                    avatarLink = item.select("dd.m a[href*=uid=]").first();
                }
                if (avatarLink != null) {
                    Matcher m = Pattern.compile("uid=(\\d+)").matcher(avatarLink.attr("href"));
                    if (m.find()) {
                        notice.setAuthorUid(m.group(1));
                    }
                }

                // ★ 头像
                Element avatarImg = item.select("dd.avt img[src*=avatar], img[src*=uc_server]").first();
                if (avatarImg != null) {
                    String src = avatarImg.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    notice.setAvatarUrl(src);
                }

                // ★ 时间
                Element timeEl = item.select("dt span.xg1 span[title], dt span.xg1").first();
                if (timeEl == null) {
                    timeEl = item.select("span[title]").first();
                }
                if (timeEl != null) {
                    String timeText = timeEl.attr("title");
                    if (TextUtils.isEmpty(timeText)) {
                        timeText = timeEl.text().trim();
                    }
                    notice.setTime(timeText);
                }

                // ★ 通知正文（ntc_body）
                Element bodyEl = item.select("dd.ntc_body").first();
                if (bodyEl == null) continue;

                // 发送者用户名（第一个 a[href*=space-uid-] 或 a[href*=uid=]）
                Element authorEl = bodyEl.select("a[href*=space-uid-], a[href*=uid=]").first();
                if (authorEl != null) {
                    notice.setAuthor(authorEl.text().trim());
                }

                // 帖子标题（指向 forum.php?mod=redirect 的链接）
                Element titleLink = bodyEl.select("a[href*=forum.php?mod=redirect]").first();
                if (titleLink != null) {
                    String title = titleLink.text().trim();
                    // 去除"回复了您的帖子"等前缀
                    notice.setSummary(title);
                }

                // ★ 完整通知文本作为标题（如 "ruolin 回复了您的帖子 XXX"）
                String fullText = bodyEl.text().trim();
                // 去除"查看"等操作文字
                fullText = fullText.replaceAll("查看\\s*$", "").trim();
                if (fullText.length() > 200) fullText = fullText.substring(0, 200);
                notice.setTitle(fullText);

                // ★ 提取帖子 tid（用于点击跳转）
                Element postLink = bodyEl.select("a[href*=ptid=]").first();
                if (postLink != null) {
                    String href = postLink.attr("href");
                    Matcher m = Pattern.compile("ptid=(\\d+)").matcher(href);
                    if (m.find()) {
                        notice.setSummary((notice.getSummary() != null ? notice.getSummary() + " " : "") + "tid=" + m.group(1));
                    }
                }

                // ★ 判断通知类型
                if (fullText.contains("回复了您的帖子") || fullText.contains("回复了您的") || fullText.contains("评论了您的")) {
                    notice.setType(2); // 回复提醒
                } else if (fullText.contains("删除") || fullText.contains("系统") || fullText.contains("管理")) {
                    notice.setType(1); // 系统通知
                }

                notices.add(notice);
            } catch (Exception ignored) {}
        }

        return notices;
    }

    /**
     * 解析私信列表（Comiis App 模板 home.php?mod=space&do=pm&mobile=2 页面）
     *
     * 真实 DOM 结构（2026-08-13 抓取）：
     *   div.comiis_pms_box > div.comiis_pmlist > ul > li
     *     a.kmdel (删除按钮)
     *     a.b_b[href*=subop=view&touid=XXX] (主链接)
     *       img[src*=avatar.php?uid=XXX] (头像)
     *       h2 > span.f_d (时间，如"昨天 20:10") + 用户名文本
     *       p.f_c (消息预览)
     *
     * 注意：Comiis 模板没有 pmid 参数，使用 touid 作为会话标识。
     *
     * @param html PM列表页HTML
     * @return 私信列表
     */
    public static List<Message> parsePmList(String html) {
        List<Message> messages = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return messages;

        Document doc = Jsoup.parse(html);

        // 主方案：Comiis App 模板结构
        // div.comiis_pmlist > ul > li
        Elements items = doc.select("div.comiis_pmlist ul > li");
        // 兜底：尝试直接查找 li 或 a.b_b
        if (items.isEmpty()) {
            items = doc.select("li.b_b, li.pm, li[data-pmid]");
        }
        // 再兜底：从 subop=view 链接向上查找父 li
        if (items.isEmpty()) {
            Elements pmLinks = doc.select("a[href*=subop=view]");
            for (Element pmLink : pmLinks) {
                Element parent = pmLink.closest("li");
                if (parent != null) items.add(parent);
            }
        }

        for (Element item : items) {
            try {
                Message msg = new Message();

                // 主链接 (a.b_b 或 a[href*=subop=view])
                Element link = item.select("a.b_b, a[href*=subop=view]").first();
                if (link == null) link = item.select("a[href*=touid=], a[href*=uid=]").first();
                if (link == null) link = item.select("a").first();
                if (link == null) continue;

                String href = link.attr("href");

                // 会话ID = touid（Comiis 模板用 touid 标识会话）
                String touid = extractParam(href, "touid");
                if (touid == null) touid = extractParam(href, "uid");
                msg.setPmid(touid); // 将 touid 存入 pmid 字段，用于 ChatActivity 跳转

                // 对方UID（与会话ID相同）
                msg.setAuthorUid(touid);

                // 用户名：从 h2 中提取（排除 span.f_d 的时间部分）
                Element h2 = link.select("h2").first();
                if (h2 != null) {
                    String fullText = h2.text().trim();
                    Element timeSpan = h2.select("span.f_d").first();
                    if (timeSpan != null) {
                        String timeText = timeSpan.text().trim();
                        fullText = fullText.replace(timeText, "").trim();
                    }
                    msg.setAuthor(fullText);
                } else {
                    // 兜底：从 a[href*=touid=] 文本提取
                    String linkText = link.text().trim();
                    if (!linkText.isEmpty()) msg.setAuthor(linkText);
                }

                // 保存网页端删除按钮生成的真实 open_href，Comiis 删除接口依赖其中的 formhash。
                Element deleteEl = item.select("a.kmdel[open_href]").first();
                if (deleteEl != null) {
                    String deleteUrl = deleteEl.attr("abs:open_href");
                    if (TextUtils.isEmpty(deleteUrl)) deleteUrl = deleteEl.attr("open_href");
                    msg.setDeleteUrl(deleteUrl);
                }

                // 消息预览 (p.f_c)
                Element previewEl = link.select("p.f_c").first();
                if (previewEl != null) msg.setSummary(previewEl.text().trim());

                // 标题（预览内容作为标题）
                String summary = msg.getSummary();
                msg.setTitle(summary != null ? summary : "");

                // 时间 (span.f_d)
                Element timeEl = link.select("span.f_d").first();
                if (timeEl != null) msg.setTime(timeEl.text().trim());

                // 头像 (img[src*=avatar.php])
                Element avatarEl = link.select("img[src*=avatar]").first();
                if (avatarEl != null) {
                    String src = avatarEl.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    msg.setAvatarUrl(src);
                }

                // 已读/未读：Comiis 模板未读/已读通常通过 li 的 class 或子元素标记
                boolean isUnread = item.hasClass("new") || item.hasClass("unread")
                        || item.select(".new, .unread, .un_read, .no_read").size() > 0;
                // 没有明确标记时默认未读
                msg.setRead(!isUnread);

                messages.add(msg);
            } catch (Exception ignored) {}
        }

        return messages;
    }
    /** 从聊天页标题读取 Comiis 的实时在线状态。 */
    public static String parseChatOnlineStatus(String html) {
        if (TextUtils.isEmpty(html)) return "离线";
        Document doc = Jsoup.parse(html);
        Element title = doc.select(".comiis_head h2, #comiis_head h2, h2.flex").first();
        String text = title != null ? title.text().trim() : doc.title();
        if (text.contains("在线")) return "在线";
        if (text.contains("离线")) return "离线";
        return "离线";
    }

/**
     * 解析私信会话详情（Comiis App 模板）。
     *
     * 真实 DOM 结构（2026-08-13 抓取）：
     *   div#comiis_pm_list
     *     div.comiis_msg_date (日期分隔线)
     *     div.comiis_friend_msg.cl / div.comiis_self_msg.cl
     */
    public static List<ChatMessage> parseChatMessages(String html, String selfUid, String fallbackAvatar) {
        List<ChatMessage> result = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return result;
        Document doc = Jsoup.parse(html);
        Set<String> seen = new HashSet<>();

        // 主方案：Comiis 模板结构
        Elements items = doc.select("div.comiis_friend_msg, div.comiis_self_msg");
        // 兜底：原有标准选择器
        if (items.isEmpty()) {
            items = doc.select("div.pmbox, div.pm_c, div.pm_body, li.pm, li.pm_list, li.cl, .comiis_pm, .comiis_pmitem, [data-pmid]");
        }

        for (Element item : items) {
            try {
                boolean isOutgoing = item.hasClass("comiis_self_msg");

                // 消息内容：div.msg_mes
                Element body = item.select("div.msg_mes").first();
                if (body == null) {
                    // 兜底：原有选择器
                    body = item.select(".pm_content, .pm_body, .message, .content, .pmbox_content, .t_f, .msgtext, .f_c").first();
                }
                if (body == null) body = item;
                String content = body.text().trim();
                if (content.isEmpty() || content.length() > 5000) continue;

                // 头像链接：img.msg_avt 或 img[src*=avatar]
                Element avatarEl = item.select("img.msg_avt, img[src*=avatar], img[src*=uc_server], img.top_tximg").first();
                String avatar = avatarEl != null ? avatarEl.attr("src") : fallbackAvatar;
                if (avatar != null && !avatar.isEmpty() && !avatar.startsWith("http")) avatar = BASE_DOMAIN + avatar;

                // 对方UID：从头像/用户链接中提取
                Element userLink = item.select("a[href*=uid=]").first();
                String authorUid = userLink != null ? extractParam(userLink.attr("href"), "uid") : null;

                // 日期分隔线：读取当前消息之前最近的 comiis_msg_date。
                String date = "";
                Element previous = item.previousElementSibling();
                while (previous != null) {
                    if (previous.hasClass("comiis_msg_date")) {
                        date = previous.text().trim();
                        break;
                    }
                    previous = previous.previousElementSibling();
                }

                // 时间：div.msg_time.f_d
                Element timeEl = item.select("div.msg_time.f_d, .time, .date, .dateline, .kmtime, .pm_time, .f_g").first();
                String time = timeEl != null ? timeEl.text().trim() : "";

                // 去重键
                String key = (isOutgoing ? "self" : "friend") + "|" + time + "|" + content;
                if (!seen.add(key)) continue;

                ChatMessage message = new ChatMessage();
                message.setContent(content);
                message.setAvatarUrl(avatar);
                message.setAuthorUid(authorUid);
                message.setDate(date);
                message.setTime(time);
                // 方向判断：comiis_self_msg = 自己发送 (outgoing=true)
                message.setOutgoing(isOutgoing);

                // 作者名：从头像链接的 alt 获取，或直接使用 selfUid 判断
                if (isOutgoing) {
                    message.setAuthor(selfUid); // 自己
                } else {
                    Element img = item.select("img[alt]").first();
                    String author = img != null ? img.attr("alt").trim() : "";
                    message.setAuthor(author);
                }

                result.add(message);
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 解析粉丝列表为 Message。
     *
     * Comiis 粉丝页真实结构：
     * div.comiis_userlist01 > li.b_t
     *   p.ytit.f_d                 操作按钮
     *   a.list01_limg > img        头像
     *   p.tit > a[href*=uid=]       用户名及 UID
     *   p.txt                       粉丝/关注统计
     *
     * 粉丝列表本身没有可靠的已读/未读标记，因此按已读处理；消息页的粉丝角标
     * 仍由服务器返回的通知数据决定，不能把普通粉丝条目误计为未读。
     */
    public static List<Message> parseFollowerList(String html) {
        List<Message> followers = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return followers;

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.comiis_userlist01 > li.b_t");
        if (items.isEmpty()) {
            // 兼容部分 Comiis 页面省略外层容器的情况。
            items = doc.select("li.b_t");
        }

        Set<String> seenUids = new HashSet<>();
        for (Element item : items) {
            try {
                Element userLink = item.select("p.tit > a[href*=uid=]").first();
                if (userLink == null) userLink = item.select("a[href*=uid=]").first();
                if (userLink == null) continue;

                String href = userLink.attr("href");
                String uid = extractParam(href, "uid");
                if (TextUtils.isEmpty(uid)) {
                    Matcher uidMatcher = Pattern.compile("(?:^|[?&])uid=(\\d+)").matcher(href);
                    if (uidMatcher.find()) uid = uidMatcher.group(1);
                }
                if (TextUtils.isEmpty(uid) || !seenUids.add(uid)) continue;

                String author = userLink.text().trim();
                if (TextUtils.isEmpty(author)) continue;

                Message follower = new Message();
                follower.setType(1);
                follower.setPmid(uid);
                follower.setAuthorUid(uid);
                follower.setAuthor(author);
                follower.setSummary("关注了你");
                follower.setTitle(author + " 关注了你");
                follower.setTime("");
                follower.setRead(true);

                Element avatarEl = item.select("a.list01_limg img[src*=avatar], a.list01_limg img, img[src*=avatar]").first();
                if (avatarEl != null) {
                    String src = avatarEl.attr("abs:src");
                    if (TextUtils.isEmpty(src)) src = avatarEl.attr("src");
                    if (!TextUtils.isEmpty(src) && !src.startsWith("http")) {
                        src = BASE_DOMAIN + (src.startsWith("/") ? src.substring(1) : src);
                    }
                    follower.setAvatarUrl(src);
                }

                followers.add(follower);
            } catch (Exception ignored) {
            }
        }
        return followers;
    }

    /**
     * 解析帖子详情页（Comiis App 模板适配，viewthread 页面）
     *
     * DOM 结构关键区域：
     *   div.comiis_head a.kmtit                    — 版块名称
     *   div.comiis_viewtit h2 div.km_tits          — 帖子标题
     *   script 中 var tid / formhash               — tid / formhash（备选：input[name=formhash]）
     *   第一个 div.comiis_postli（id=pidxxxxx）    — 楼主帖子
     *      div.comiis_postli_top                   — 帖主信息区
     *        a.postli_top_tximg img.top_tximg      — 头像
     *        a.top_user.f_b                        — 用户名（href 含 uid=）
     *        a.top_lev.bg_a.f_f                    — 等级
     *        i.comiis_font.top_gender.bg_boy/girl  — 性别
     *        a.followmod                           — 关注按钮
     *        div.comiis_postli_time span.kmtime    — 发布时间
     *        div.comiis_postli_time code.comiis_iplocality — 地点（楼主）
     *      div.comiis_message                      — 正文区
     *        div.comiis_a.comiis_message_table.cl  — 正文HTML
     *        div.comiis_quote.bg_h.f_c             — 隐藏内容提示
     *        div.comiis_rate h2.rate_btn           — 赞赏按钮
     *   后续 div.comiis_postli（除第一个）          — 各回复
     *      div.comiis_postli_top
     *        span.f_d.y                            — 楼层标签（沙发/椅子/凉席/报纸/地板/N#）
     *        a.top_user.f_b                        — 用户名
     *        a.top_lev.bg_a.f_f                    — 等级
     *        i.comiis_font.top_gender              — 性别
     *        span.top_lev.bg_c.f_f                 — "楼主"标识（仅楼主回复显示）
     *      div.comiis_message div.comiis_a.comiis_message_table.cl — 回复内容
     *      div.comiis_postli_times                 — 回复时间地点（底栏）
     *        span.f_d.comiis_tm                    — 时间
     *        code.comiis_iplocality                — 地点
     *
     * @param html 帖子详情页 HTML
     * @return PostDetail 对象，包含帖主信息、正文、回复列表等
     */
    /**
     * 代码块标准化:将 Comiis/Discuz 移动版代码块转换为统一的 <pre> 结构。
     *
     * 真实页面存在两种结构:
     *   1. 移动版:<div class="comiis_blockcode"><div><ol><li>行1</li><li>行2</li></ol></div></div>
     *   2. 桌面版/标准:<pre>...</pre>
     *
     * 转换结果:<pre class="comiis_blockcode">行号+空格+内容<br>...</pre>
     * - 保留每行缩进:普通空格转为 &amp;nbsp; 避免 Html 渲染时被合并
     * - 行间用 &lt;br&gt; 换行,配合客户端 BBCodeUtil 的 TagHandler 渲染等宽字体+背景
     */
    public static String normalizeCodeBlocks(String html) {
        if (html == null || html.isEmpty()) return html;
        if (html.indexOf("comiis_blockcode") < 0 && html.indexOf("<pre") < 0
                && html.indexOf("blockcode") < 0) {
            return html;
        }
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            // 1. 裸 <pre>:保留结构,仅转义空格
            for (Element pre : doc.select("pre")) {
                escapeSpacesInTree(pre);
            }
            // 2. div.comiis_blockcode:提取行并重建为 <pre>
            for (Element code : doc.select("div.comiis_blockcode")) {
                Element target = code;
                List<String> lines = new ArrayList<>();
                // 优先 ol>li(移动版,每行一个 li)
                Elements lis = code.select("ol > li");
                if (!lis.isEmpty()) {
                    int lineNo = 1;
                    for (Element li : lis) {
                        String t = collectRawText(li).replace('\u00a0', ' ');
                        if (t.trim().isEmpty()) { lineNo++; continue; }
                        lines.add(lineNo + " " + t);
                        lineNo++;
                    }
                } else {
                    // 无 li:从叶子文本+br 拆行
                    Element inner = code.selectFirst("pre");
                    if (inner != null) target = inner;
                    List<String> raw = splitLinesByBr(target);
                    int lineNo = 1;
                    for (String s : raw) {
                        String t = s.replace('\u00a0', ' ');
                        if (t.trim().isEmpty()) { lineNo++; continue; }
                        lines.add(lineNo + " " + t);
                        lineNo++;
                    }
                }
                StringBuilder sb = new StringBuilder("<pre class=\"comiis_blockcode\">");
                for (String line : lines) {
                    sb.append(escapeNbsp(line)).append("<br>");
                }
                sb.append("</pre>");
                Element preNew = Jsoup.parseBodyFragment(sb.toString()).body().child(0);
                code.replaceWith(preNew);
            }
            return doc.body().html();
        } catch (Exception e) {
            // 标准化失败时返回原文,不阻断正文显示
            return html;
        }
    }

    /** 收集元素下所有文本节点原文(保留 \u00a0、连续空格,不做空白规范化) */
    private static String collectRawText(Element el) {
        StringBuilder sb = new StringBuilder();
        collectRawTextNode(el, sb);
        return sb.toString();
    }

    private static void collectRawTextNode(org.jsoup.nodes.Node node, StringBuilder sb) {
        if (node instanceof org.jsoup.nodes.TextNode) {
            sb.append(((org.jsoup.nodes.TextNode) node).getWholeText());
        } else if (node instanceof Element) {
            for (org.jsoup.nodes.Node child : node.childNodes()) {
                collectRawTextNode(child, sb);
            }
        }
    }

    /** 将节点树内所有文本节点的普通空格转为 &amp;nbsp;,防止 Html 渲染时被合并 */
    private static void escapeSpacesInTree(Element root) {
        for (org.jsoup.nodes.Node node : root.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode) {
                org.jsoup.nodes.TextNode tn = (org.jsoup.nodes.TextNode) node;
                String text = tn.getWholeText();
                if (text.indexOf(' ') >= 0) {
                    tn.text(text.replace(" ", "\u00a0"));
                }
            } else if (node instanceof Element) {
                escapeSpacesInTree((Element) node);
            }
        }
    }

    /** 按 <br> 拆分元素文本为多行(保留子树文本) */
    private static List<String> splitLinesByBr(Element root) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (org.jsoup.nodes.Node node : root.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode) {
                cur.append(((org.jsoup.nodes.TextNode) node).getWholeText());
            } else if (node.nodeName().equals("br")) {
                lines.add(cur.toString());
                cur.setLength(0);
            } else if (node instanceof Element) {
                cur.append(((Element) node).text());
            }
        }
        lines.add(cur.toString());
        return lines;
    }

    /** 普通空格转 &amp;nbsp;,保留   原样 */
    private static String escapeNbsp(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') sb.append("&nbsp;");
            else sb.append(ch);
        }
        return sb.toString();
    }

    public static PostDetail parseThreadDetail(String html) {
        PostDetail detail = new PostDetail();

        // 某些帖子请求可能返回空响应（网络断开、服务端临时无响应或被重定向）。
        // 不能把 null 直接交给 Jsoup/Pattern.matcher，否则 Android 会抛出
        // "s == null" 这类不具备可读性的 NPE，详情页就会完全空白。
        if (TextUtils.isEmpty(html)) {
            detail.setReplies(new ArrayList<ReplyItem>());
            detail.setImageUrls(new ArrayList<String>());
            detail.setCurrentPage(1);
            detail.setTotalPages(1);
            return detail;
        }

        Document doc = Jsoup.parse(html);

        // === 1. 版块信息 ===
        Element forumLink = doc.select("div.comiis_head a.kmtit[href*=forum-]").first();
        if (forumLink != null) {
            detail.setForumName(forumLink.ownText().trim());
            String href = forumLink.attr("href");
            Matcher m = Pattern.compile("forum-(\\d+)-").matcher(href);
            if (m.find()) detail.setForumFid(m.group(1));
        }

        // === 2. 帖子标题 ===
        Element titleEl = doc.select("div.comiis_viewtit h2 div.km_tits").first();
        if (titleEl != null) {
            detail.setTitle(titleEl.text().trim());
        }

        // === 3. formhash & tid ===
        Element fhInput = doc.select("input[name=formhash]").first();
        if (fhInput != null) {
            detail.setFormhash(fhInput.attr("value"));
        }
        if (detail.getFormhash() == null || detail.getFormhash().isEmpty()) {
            Matcher fhM = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
            if (fhM.find()) detail.setFormhash(fhM.group(1));
        }
        // === 3.1.1 noticeauthor(用于回复时 @通知对方) ===
        Element naInput = doc.select("input[name=noticeauthor]").first();
        if (naInput != null) {
            detail.setNoticeauthor(naInput.attr("value"));
        }
        Matcher tidM = Pattern.compile("tid\\s*=\\s*['\"](\\d+)['\"]").matcher(html);
        if (tidM.find()) detail.setTid(tidM.group(1));

        // === 3.1 当前用户的点赞/收藏状态（必须以网页端状态为准） ===
        parseCurrentActionStates(doc, html, detail);

// === 4. 所有 postli 元素 ===
        Elements allPostlis = doc.select("div.comiis_postli");
        // 非标准模板：只把真正包含正文容器的帖子外壳当作 postli，
        // 避免把导航栏/按钮等普通 div[class*=post] 误当成楼主帖子。
        if (allPostlis.isEmpty()) {
            allPostlis = doc.select(
                    "article:has(div.comiis_message), article:has(td.t_f), "
                            + "div[class*=post]:has(div.comiis_message), "
                            + "div[class*=post]:has(td.t_f), "
                            + "div[class*=thread]:has(div.comiis_message), "
                            + "div[class*=thread]:has(td.t_f)");
        }
        if (allPostlis.isEmpty()) {
            // 页面可能没有 postli 外壳，但仍然包含正文容器。此时不能直接返回空 detail，
            // 否则客户端会把一个实际有内容的帖子显示成空白。
            Element fallbackContent = doc.select(
                    "div.comiis_message, td.t_f, div.message, div.postbody, article").first();
            if (fallbackContent != null) {
                Element clone = fallbackContent.clone();
                clone.select("script, style, div.comiis_favshare, div.comiis_postli_time, a.followmod")
                        .remove();
                String fallbackHtml = clone.html().trim();
                if (!TextUtils.isEmpty(fallbackHtml)) {
                    detail.setContentHtml(fallbackHtml);
                }
            }
            detail.setReplies(new ArrayList<ReplyItem>());
            detail.setImageUrls(new ArrayList<String>());
            detail.setCurrentPage(1);
            detail.setTotalPages(1);
            return detail;
        }

        // 第一个 postli = 楼主帖子
        Element opPostli = allPostlis.first();
        // Discuz! 的赞赏接口不是只按 tid 操作，必须传入具体正文 pid。
        // Comiis 模板把它放在楼主 postli 的 id="pidxxxx" 中。
        String opPid = opPostli.id();
        if (!TextUtils.isEmpty(opPid) && opPid.startsWith("pid")) {
            detail.setPostPid(opPid.substring(3));
        }

        // === 5. 帖主（楼主）信息 ===
        Element opTop = opPostli.select("div.comiis_postli_top").first();
        if (opTop != null) {
            // 头像
            Element avatarImg = opTop.select("a.postli_top_tximg img.top_tximg").first();
            if (avatarImg != null) {
                String src = avatarImg.attr("src");
                if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                detail.setAvatarUrl(src);
            }
            // 用户名 & UID
            Element authorEl = opTop.select("a.top_user.f_b").first();
            if (authorEl != null) {
                detail.setAuthor(authorEl.text().trim());
                Matcher m = Pattern.compile("uid=(\\d+)").matcher(authorEl.attr("href"));
                if (m.find()) detail.setAuthorUid(m.group(1));
            }
            // 等级
            Element levelEl = opTop.select("a.top_lev.bg_a").first();
            if (levelEl != null) detail.setAuthorLevel(levelEl.text().trim());
            // 性别
            Element genderEl = opTop.select("i.comiis_font.top_gender").first();
            if (genderEl != null) {
                String cls = genderEl.className();
                if (cls.contains("bg_boy")) detail.setGender("boy");
                else if (cls.contains("bg_girl")) detail.setGender("girl");
            }
            // 关注状态
            Element followEl = opTop.select("a.followmod").first();
            if (followEl != null) {
                detail.setFollowed(followEl.text().trim().contains("已关注"));
            }
            // 楼主时间 & 地点
            Element opTimeArea = opTop.select("div.comiis_postli_time").first();
            if (opTimeArea != null) {
                Element timeEl = opTimeArea.select("span.kmtime").first();
                if (timeEl != null) detail.setPublishTime(timeEl.text().trim());
                Element locCode = opTimeArea.select("code.comiis_iplocality").first();
                if (locCode != null) detail.setLocation(locCode.text().trim());
            }
        }

        // === 6. 帖子正文 + 附件图片 ===
        Element opMsg = opPostli.select("div.comiis_message").first();
        if (opMsg != null) {
            // ★ 尝试多种选择器提取正文内容（优先精确选择器，兜底取整个消息区）
            Element contentDiv = opMsg.select("div.comiis_a.comiis_message_table.cl").first();
            if (contentDiv == null) {
                contentDiv = opMsg.select("div.comiis_a").first();
            }
            if (contentDiv == null) {
                contentDiv = opMsg.select("div.comiis_message_table").first();
            }
            if (contentDiv == null && !TextUtils.isEmpty(opMsg.html())) {
                // ★ 最终兜底：移除回复按钮等干扰元素后直接取内容
                Element msgClone = opMsg.clone();
                msgClone.select("div.comiis_favshare").remove();
                msgClone.select("a.followmod").remove();
                msgClone.select("div.comiis_postli_time").remove();
                msgClone.select("div.comiis_message_table.cl").remove();
                String remaining = msgClone.html().trim();
                if (!TextUtils.isEmpty(remaining) && remaining.length() > 50) {
                    contentDiv = opMsg; // 使用opMsg自身作为内容容器
                }
            }
            if (contentDiv != null) {
                String content = contentDiv.html().trim();
                // 如果内容太短可能只是干扰文本，尝试更精确提取
                if (content.length() < 10 && opMsg.select("td.t_f").first() != null) {
                    content = opMsg.select("td.t_f").first().html().trim();
                }
                detail.setContentHtml(normalizeCodeBlocks(content));
            } else {
                // 兜底：直接取整个消息区，并移除操作区域
                Element msgClone = opMsg.clone();
                msgClone.select("div.comiis_favshare, a.followmod, div.comiis_postli_time").remove();
                String fallbackHtml = msgClone.html().trim();
                if (fallbackHtml.length() > 30) {
                    detail.setContentHtml(normalizeCodeBlocks(fallbackHtml));
                }
            }

            // 无论正文使用哪一个选择器，都必须提取正文中的懒加载图片。
            // 旧代码把这段逻辑放在 contentDiv == null 分支中，导致大多数正常帖子
            // 虽然 HTML 中有图片，但 imageUrls 始终为空。
            Element messagesDiv = opMsg.select("div.comiis_messages").first();
            if (messagesDiv == null) messagesDiv = opMsg;
            List<String> attachImageUrls = new ArrayList<>();
            for (Element img : messagesDiv.select("img")) {
                String realSrc = firstNonEmptyAttr(img,
                        "comiis_loadimages", "file", "data-original", "data-src",
                        "data-file", "data-lazy-src", "src");
                String fullUrl = resolveAttachmentUrl(realSrc);
                if (isPostImageUrl(fullUrl) && !attachImageUrls.contains(fullUrl)) {
                    attachImageUrls.add(fullUrl);
                }
            }
            detail.setImageUrls(attachImageUrls);

            // 隐藏内容检测
            Element quoteDiv = opMsg.select("div.comiis_quote").first();
            if (quoteDiv != null) {
                detail.setHasHiddenContent(true);
                detail.setHiddenContentHtml(normalizeCodeBlocks(quoteDiv.html().trim()));
            }
            // 点赞数(优先从推荐数标签解析,fallback到列表项数)
            Element recommendNum = opMsg.selectFirst("em.comiis_recommend_num");
            if (recommendNum != null) {
                detail.setLikeCount(parseIntFromText(recommendNum.text()));
            } else {
                Elements recommendLis = opMsg.select("ul.comiis_recommend_list_a li");
                detail.setLikeCount(recommendLis.size());
            }
            // === 点赞人列表(登录态渲染: ul.comiis_recommend_list_a > li#comiis_recommend_list_a{uid} 头像;
            //     用户名版 ul.comiis_recommend_list_t > span#comiis_recommend_list_t{uid} > a) ===
            List<String> likeUids = new ArrayList<>();
            List<String> likeAvatars = new ArrayList<>();
            List<String> likeNames = new ArrayList<>();
            Elements avatarLis = opMsg.select("ul.comiis_recommend_list_a li");
            for (Element li : avatarLis) {
                String liId = li.id();
                String uid = null;
                if (liId != null && liId.startsWith("comiis_recommend_list_a")) {
                    uid = liId.substring("comiis_recommend_list_a".length());
                }
                Element img = li.selectFirst("img");
                String avatar = img != null ? img.absUrl("src") : "";
                if (avatar.isEmpty()) {
                    avatar = img != null ? img.attr("src") : "";
                }
                // 头像 url 相对路径补全
                if (!avatar.isEmpty() && avatar.startsWith("/") && !avatar.startsWith("//")) {
                    avatar = BASE_DOMAIN + avatar;
                }
                if (uid == null || uid.isEmpty()) {
                    // 无 id 时从链接 uid=N 抓
                    Element link = li.selectFirst("a[href*=uid=]");
                    java.util.regex.Matcher um = java.util.regex.Pattern.compile("uid=(\\d+)").matcher(link != null ? link.attr("href") : "");
                    if (um.find()) uid = um.group(1);
                }
                if (uid != null && !uid.isEmpty()) {
                    likeUids.add(uid);
                    likeAvatars.add(avatar);
                }
            }
            // 用户名版(comiis_recommend_list_t)
            Elements nameSpans = opMsg.select("ul.comiis_recommend_list_t span[id^=comiis_recommend_list_t]");
            if (nameSpans.isEmpty()) {
                nameSpans = doc.select("ul.comiis_recommend_list_t span[id^=comiis_recommend_list_t]");
            }
            for (Element sp : nameSpans) {
                Element a = sp.selectFirst("a");
                if (a != null && !a.text().trim().isEmpty()) {
                    likeNames.add(a.text().trim());
                }
            }
            if (!likeUids.isEmpty()) {
                detail.setLikeUserUids(likeUids);
                detail.setLikeUserAvatars(likeAvatars);
            }
            if (!likeNames.isEmpty()) {
                detail.setLikeUserNames(likeNames);
            }
        }

        // 收藏数(帖子头部操作栏 #comiis_favorite_a 内,与 opMsg 同级在 doc 下,
        // 不能用 opMsg.selectFirst 否则永远 null)
        Element favoriteNum = doc.selectFirst("#comiis_favorite_a span.comiis_favorite_a_num");
        if (favoriteNum != null) {
            detail.setFavoriteCount(parseIntFromText(favoriteNum.text()));
        }
        // 兜底:部分模板收藏数只带 class 不带 id
        if (detail.getFavoriteCount() <= 0) {
            Element favAlt = doc.selectFirst("span.comiis_favorite_a_num");
            if (favAlt != null) {
                detail.setFavoriteCount(parseIntFromText(favAlt.text()));
            }
        }

        // === 7. 回复标题栏:评论总数 ===
        Element pltit = doc.select("div.comiis_pltit").first();
        if (pltit != null) {
            Element countSpan = pltit.select("span.f_d").first();
            if (countSpan != null) {
                detail.setReplyCount(parseIntFromText(countSpan.text()));
            }
        }

        // === 8. 回复列表(跳过第一个楼主 postli) ===
        List<ReplyItem> replies = new ArrayList<>();
        Pattern uidPtn = Pattern.compile("uid=(\\d+)");
        for (int i = 1; i < allPostlis.size(); i++) {
            try {
                Element rp = allPostlis.get(i);
                ReplyItem reply = new ReplyItem();

                // pid
                String pidAttr = rp.id();
                if (pidAttr != null && pidAttr.startsWith("pid")) {
                    reply.setPid(pidAttr.substring(3));
                }

                Element rTop = rp.select("div.comiis_postli_top").first();
                if (rTop == null) continue;

                // 楼层号
                Element floorEl = rTop.select("span.f_d.y").first();
                if (floorEl != null) reply.setFloorLabel(floorEl.text().trim());

                // 用户名 & UID
                Element userEl = rTop.select("a.top_user.f_b").first();
                if (userEl != null) {
                    reply.setAuthor(userEl.text().trim());
                    Matcher m = uidPtn.matcher(userEl.attr("href"));
                    if (m.find()) reply.setAuthorUid(m.group(1));
                }
                // 等级
                Element levEl = rTop.select("a.top_lev.bg_a").first();
                if (levEl != null) reply.setAuthorLevel(levEl.text().trim());
                // 性别
                Element gEl = rTop.select("i.comiis_font.top_gender").first();
                if (gEl != null) {
                    String cls = gEl.className();
                    if (cls.contains("bg_boy")) reply.setGender("boy");
                    else if (cls.contains("bg_girl")) reply.setGender("girl");
                }
                // 楼主标识
                Element opTag = rTop.select("span.top_lev.bg_c.f_f").first();
                if (opTag != null && opTag.text().contains("楼主")) {
                    reply.setOP(true);
                }
                // 头像
                Element rAvatar = rTop.select("a.postli_top_tximg img.top_tximg").first();
                if (rAvatar != null) {
                    String src = rAvatar.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    reply.setAvatarUrl(src);
                }

                // 回复内容（★ 添加兜底选择器）
                Element rContent = rp.select("div.comiis_message div.comiis_a.comiis_message_table.cl").first();
                if (rContent == null) {
                    rContent = rp.select("div.comiis_message div.comiis_a").first();
                }
                if (rContent == null) {
                    rContent = rp.select("div.comiis_message").first();
                }
                if (rContent != null) {
                    // Discuz 回复内容中通常包含 div.comiis_quote 引用块。
                    // 引用与当前回复必须拆开保存，否则客户端会把引用文本和新回复连成一段。
                    Element quote = rContent.select("div.comiis_quote, blockquote, .quote").first();
                    if (quote != null) {
                        reply.setQuotedContentHtml(quote.html().trim());
                        reply.setQuotedContentText(quote.text().replace('\u00a0', ' ').trim());
                        quote.remove();
                    }
                    reply.setContentHtml(normalizeCodeBlocks(rContent.html().trim()));
                    reply.setContentText(rContent.text().replace('\u00a0', ' ').trim());
                }

                // 回复时间 & 地点（底栏 div.comiis_postli_times）
                Element rTimes = rp.select("div.comiis_postli_times").first();
                if (rTimes != null) {
                    Element timeEl = rTimes.select("span.comiis_tm").first();
                    if (timeEl != null) reply.setTime(timeEl.text().trim());
                    Element locCode = rTimes.select("code.comiis_iplocality").first();
                    if (locCode != null) reply.setLocation(locCode.text().trim());
                }

                replies.add(reply);
            } catch (Exception ignored) {}
        }
        detail.setReplies(replies);

        // === 9. 分页信息 ===
        Element pageInput = doc.select("input[name=page]").first();
        if (pageInput != null) {
            int curPage = parseIntFromText(pageInput.attr("value"));
            detail.setCurrentPage(curPage > 0 ? curPage : 1);
        } else {
            detail.setCurrentPage(1);
        }
        // 从分页组件中提取最大页码
        // MT论坛移动版分页：<div class="pg"><strong>1</strong><a href="...thread-xxx-2-1.html">2</a>...<span title="共 N 页"> / N 页</span></div>
        int maxPage = 1;
        // 方式1: 从 <span title="共 N 页"> 提取
        Element pgDiv = doc.select("div.pg").first();
        if (pgDiv != null) {
            Element span = pgDiv.select("span[title*=共]").first();
            if (span != null) {
                String title = span.attr("title");
                Matcher titleM = Pattern.compile("共\\s*(\\d+)\\s*页").matcher(title);
                if (titleM.find()) {
                    maxPage = parseIntFromText(titleM.group(1));
                } else {
                    // 从文本 " / N 页" 提取
                    Matcher textM = Pattern.compile("/?\\s*(\\d+)\\s*页").matcher(span.text());
                    if (textM.find()) {
                        maxPage = parseIntFromText(textM.group(1));
                    }
                }
            }
            // 方式2: 从分页数字链接 thread-{tid}-{page}-1.html 提取
            if (maxPage <= 1) {
                Elements numLinks = pgDiv.select("a[href*=-1.html]");
                Pattern tidPagePtn = Pattern.compile("thread-\\d+-(\\d+)-1\\.html");
                for (Element pl : numLinks) {
                    Matcher pm = tidPagePtn.matcher(pl.attr("href"));
                    if (pm.find()) {
                        try {
                            int p = Integer.parseInt(pm.group(1));
                            if (p > maxPage) maxPage = p;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        // 方式3: 兜底，从 a[href*=page=] 提取（兼容桌面版）
        if (maxPage <= 1) {
            Elements pageLinks = doc.select("a[href*=page=]");
            Pattern pagePtn = Pattern.compile("[&?]page=(\\d+)");
            for (Element pl : pageLinks) {
                Matcher pm = pagePtn.matcher(pl.attr("href"));
                if (pm.find()) {
                    try {
                        int p = Integer.parseInt(pm.group(1));
                        if (p > maxPage) maxPage = p;
                    } catch (Exception ignored) {}
                }
            }
        }
detail.setTotalPages(maxPage);

          // === 10. 赞赏与好评统计（如有） ===
          // Comiis 移动端结构：rate_tip 同时包含“X人打赏”和“Y好评”，
          // 下方 ul li 为打赏头像列表；rate_tip 中的链接指向全部打赏明细。
          Element rateDiv = doc.select("div.comiis_rate").first();
          List<String> rewardAvatars = new ArrayList<>();
          List<String> goodReviewAvatars = new ArrayList<>();
          if (rateDiv != null) {
              Element rateTip = rateDiv.select("p.rate_tip").first();
              String rateTipText = rateTip != null ? rateTip.text().trim() : rateDiv.text().trim();
              Matcher rewardCountMatcher = Pattern.compile("(\\d+)\\s*人?打赏").matcher(rateTipText);
              if (rewardCountMatcher.find()) {
                  detail.setRewardCount(parseIntFromText(rewardCountMatcher.group(1)));
              } else {
                  Element rateBtn = rateDiv.select("h2.rate_btn").first();
                  if (rateBtn != null && rateBtn.text().contains("赞赏")) {
                      detail.setRewardCount(parseIntFromText(rateBtn.text()));
                  }
              }

              Matcher goodCountMatcher = Pattern.compile("(\\d+)\\s*好评").matcher(rateTipText);
              if (goodCountMatcher.find()) {
                  detail.setGoodReviewCount(parseIntFromText(goodCountMatcher.group(1)));
              }

              if (rateTip != null) {
                  Element rewardLink = rateTip.select("a[href*=viewratings]").first();
                  if (rewardLink != null) {
                      String href = rewardLink.attr("href");
                      detail.setRewardDetailUrl(resolveAttachmentUrl(href));
                  }
              }

              for (Element img : rateDiv.select("ul > li img")) {
                  String avatar = resolveAvatarUrl(firstNonEmptyAttr(img,
                          "src", "data-src", "data-original", "data-lazy-src"));
                  if (!TextUtils.isEmpty(avatar) && !rewardAvatars.contains(avatar)) {
                      rewardAvatars.add(avatar);
                  }
              }
          }
          detail.setRewardUserAvatars(rewardAvatars);
          detail.setGoodReviewUserAvatars(goodReviewAvatars);

          return detail;
    }

    /**
     * 解析“查看全部打赏”页面中的打赏金币总数。
     * 页面格式通常为：总计：金币 +4，好评 +2。
     */
    public static int parseRewardCoins(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        String text = Jsoup.parse(html).text();
        Matcher matcher = Pattern.compile("金币\\s*\\+\\s*(\\d+)").matcher(text);
        if (matcher.find()) return parseIntFromText(matcher.group(1));
        return 0;
    }
    /**
     * 从桌面版评分表提取真正获得“好评”的用户头像。
     * 移动版只展示打赏头像，不包含好评用户明细，因此由详情页加载时补充。
     */
    public static List<String> parseGoodReviewAvatarUrls(String html) {
        List<String> avatars = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return avatars;
        Document doc = Jsoup.parse(html);
        for (Element row : doc.select("tr[id^=rate_]")) {
            Elements cells = row.select("> td");
            if (cells.size() >= 2 && cells.get(1).text().contains("+")) {
                Element img = row.select("td:first-child img").first();
                String avatar = resolveAvatarUrl(firstNonEmptyAttr(img,
                        "src", "data-src", "data-original", "data-lazy-src"));
                if (!TextUtils.isEmpty(avatar) && !avatars.contains(avatar)) {
                    avatars.add(avatar);
                }
            }
        }
        return avatars;
    }
/**
     * 从 Comiis 详情页解析当前登录用户的点赞/收藏状态。
     * uid 非 0 且页面存在对应操作控件时标记状态已知。
     */
    private static void parseCurrentActionStates(Document doc, String html, PostDetail detail) {
        if (doc == null || detail == null || TextUtils.isEmpty(html)) return;

        Matcher uidMatcher = Pattern.compile("(?:var\\s+)?uid\\s*=\\s*['\"](\\d+)['\"]",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (!uidMatcher.find() || "0".equals(uidMatcher.group(1))) return;

        Element recommend = doc.select(
                "a.comiis_recommend_addkey, a.comiis_recommend_new, "
                        + ".comiis_recommend_addkey").first();
        if (recommend != null) {
            Element icon = recommend.select("i.comiis_recommend_color").first();
            String classes = recommend.className() + " " +
                    (icon != null ? icon.className() : "");
            String iconHtml = icon != null ? icon.html() : "";
            boolean liked = classes.contains("f_a") || iconHtml.contains("e654");
            detail.setLiked(liked);
            detail.setLikedStateKnown(true);
        }

        Element favoriteIcon = doc.select("#comiis_favorite_a i.comiis_favorite_a_color").first();
        if (favoriteIcon != null) {
            String classes = favoriteIcon.className();
            String iconHtml = favoriteIcon.html();
            boolean favorited = classes.contains("f_a") || iconHtml.contains("e64c");
            detail.setFavorited(favorited);
            detail.setFavoritedStateKnown(true);
        }
    }


    /**
     * 提取 formhash（发帖/回复需要）
     * 优先从 input[name=formhash] 提取，再尝试 JS 变量，最后尝试收藏对话框表单。
     */
    public static String parseFormhash(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Document doc = Jsoup.parse(html);
        Element input = doc.select("input[name=formhash]").first();
        if (input != null) return input.attr("value");

        // 尝试从 JavaScript 变量中提取
        Matcher m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
        if (m.find()) return m.group(1);

        return null;
    }

    /**
     * 从收藏页面 HTML 中提取删除/添加收藏对话框里的 formhash。
     * Comiis 模板的收藏对话框表单 id 为 favoriteform_{favid}，
     * 内部包含隐藏的 input[name=formhash]。
     */
    public static String parseFavoriteFormhash(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Document doc = Jsoup.parse(html);
        // 收藏对话框表单 id 包含 "favoriteform"
        Element form = doc.select("form[id*=favoriteform]").first();
        if (form != null) {
            Element input = form.select("input[name=formhash]").first();
            if (input != null) {
                String val = input.attr("value");
                if (!TextUtils.isEmpty(val)) return val;
            }
        }
        // 兜底：尝试从页面中任意 form 的 input[name=formhash] 提取
        Element fallback = doc.select("input[name=formhash]").first();
        if (fallback != null) {
            String val = fallback.attr("value");
            if (!TextUtils.isEmpty(val)) return val;
        }
        // 最后尝试从 JS 变量提取
        Matcher m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 从 URL 中提取参数
     */
    private static String extractParam(String url, String param) {
        if (url == null || url.isEmpty()) return null;
        try {
            Pattern p = Pattern.compile(param + "=(\\d+)");
            Matcher m = p.matcher(url);
            if (m.find()) return m.group(1);

            // 尝试更通用的匹配（非纯数字参数）
            Pattern p2 = Pattern.compile(param + "=([^&]+)");
            Matcher m2 = p2.matcher(url);
            if (m2.find()) return m2.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    /** Extract user UIDs from a following-list page. */
    public static Set<String> extractFollowingUids(String html) {
        Set<String> uids = new HashSet<>();
        if (TextUtils.isEmpty(html)) return uids;
        Document doc = Jsoup.parse(html);
        Elements links = doc.select("div.comiis_userlist01 > li.b_t > p.tit > a[href*=uid=], li.b_t p.tit a[href*=uid=]");
        if (links.isEmpty()) links = doc.select("a[href*=home.php?mod=space&uid=]");
        for (Element link : links) {
            String uid = extractParam(link.attr("href"), "uid");
            if (!TextUtils.isEmpty(uid)) uids.add(uid);
        }
        return uids;
    }


    /** 判断关注列表是否还有下一页。 */
    public static boolean hasUserListPageAfter(String html, int currentPage) {
        if (TextUtils.isEmpty(html)) return false;
        Document doc = Jsoup.parse(html);
        for (Element link : doc.select("a[href*=page=]")) {
            Matcher matcher = Pattern.compile("(?:^|[?&])page=(\\d+)")
                    .matcher(link.attr("href"));
            if (matcher.find()) {
                try {
                    if (Integer.parseInt(matcher.group(1)) > currentPage) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 解析好友/关注/粉丝列表
     * 适配 Comiis App 模板。
     * 基于真实登录页面分析（2026-06-13 实机验证），采用精确选择器：
     *
     * 真实 DOM 结构（好友页）：
     *   div.comiis_friend_boxs
     *     div.mt10.bg_f.b_b.cl
     *       div.comiis_userlist01.cl
     *         li.b_t
     *           p.ytit.f_d ── 操作链接（删除/分组/取消/打招呼/发消息）
     *           a.list01_limg > img[src*=avatar] ── 头像
     *           p.tit > a[href*="home.php?mod=space&uid="] ── 用户名（唯一可靠标识）
     *           p.txt > font ── 等级/积分
     *
     * 关注/粉丝页结构与好友页类似，用户条目位于相同 li.b_t 容器内。
     *
     * ★ 关键修复：不再使用宽泛的 a[href*=uid=]（会误匹配选项卡、删除按钮等），
     *   改为精确的：div.comiis_userlist01 > li.b_t > p.tit > a[href*=uid=]
     *
     * @param html 好友/关注/粉丝页 HTML
     * @return 好友/用户列表
     */
    public static List<Friend> parseFriendList(String html) {
        List<Friend> friends = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return friends;
        Document doc = Jsoup.parse(html);

        // ★ 精确策略：先找用户条目容器 li.b_t，再从中提取用户名链接
        //   div.comiis_userlist01 是 Comiis 模板的用户列表容器
        Elements userItems = doc.select("div.comiis_userlist01 > li.b_t");
        if (userItems.isEmpty()) {
            // 兜底：直接找 li.b_t（某些页面结构略有不同）
            userItems = doc.select("li.b_t");
        }

        for (Element li : userItems) {
            try {
                // 用户名链接：唯一可靠的是 p.tit 内的 a[href*=uid=]
                Element usernameLink = li.select("p.tit > a[href*=uid=]").first();
                if (usernameLink == null) {
                    // 兜底：直接的 a[href*=uid=]（关注页结构可能略有不同）
                    usernameLink = li.select("a[href*=uid=]").first();
                }
                if (usernameLink == null) continue;

                String username = usernameLink.text().trim();
                if (TextUtils.isEmpty(username)) continue;

                String href = usernameLink.attr("href");
                Matcher m = Pattern.compile("uid=(\\d+)").matcher(href);
                if (!m.find()) continue;
                String uid = m.group(1);

                // 去重
                boolean duplicate = false;
                for (Friend existing : friends) {
                    if (uid.equals(existing.getUid())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                Friend f = new Friend();
                f.setUid(uid);
                f.setUsername(username);

                // 头像
                Element avatarImg = li.select("a.list01_limg img[src*=avatar], img[src*=avatar]").first();
                if (avatarImg != null) {
                    String src = avatarImg.attr("src");
                    if (!src.startsWith("http")) src = BASE_DOMAIN + src;
                    f.setAvatarUrl(src);
                }

                // 等级（从 p.txt 或 span.kmlevs 中提取）
                Element levelEl = li.select(".kmlevs, .kmlv, span:contains(Lv)").first();
                if (levelEl == null) {
                    // 从 p.txt > font 中提取（好友页结构）
                    Elements txtFonts = li.select("p.txt > font");
                    for (Element font : txtFonts) {
                        String t = font.text().trim();
                        if (t.contains("Lv") || t.contains("硕士") || t.contains("博士")
                                || t.contains("大学") || t.contains("高中") || t.contains("初中")
                                || t.contains("小学") || t.contains("学前")) {
                            if (t.contains("积分")) {
                                t = t.replaceAll("积分.*", "").trim();
                            }
                            f.setLevel(t);
                            break;
                        }
                    }
                } else {
                    f.setLevel(levelEl.text().trim());
                }

                // 积分/用户组（从 p.txt 中提取）
                Element creditsEl = li.select("p.txt > font:contains(积分)").first();
                if (creditsEl != null) {
                    f.setCredits(creditsEl.text().trim());
                }

                friends.add(f);
            } catch (Exception ignored) {}
        }

        return friends;
    }

    /**
     * 解析收藏列表(home.php?mod=space&do=favorite&mobile=2)
     * Comiis 模板结构: 包含 收藏标题链接 的条目
     */
    public static List<Thread> parseFavoriteList(String html) {
        List<Thread> favorites = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return favorites;
        Document doc = Jsoup.parse(html);

        // 收藏页不同 UA/模板可能使用 thread-xxx.html、thread.php?tid=xxx，
        // 或 forum.php?mod=viewthread&tid=xxx，不能只依赖一种 URL。
        Elements links = doc.select("a[href*=thread-], a[href*=thread.php], a[href*=viewthread]");
        Pattern tidPretty = Pattern.compile("(?:^|/)thread-(\\d+)(?:-[^./?#]+)*\\.html", Pattern.CASE_INSENSITIVE);
        Pattern tidQuery = Pattern.compile("[?&](?:tid|threadid)=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern favidPattern = Pattern.compile("(?:favid|fav_id)\\s*[=:/]\\s*['\"]?(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern favidHrefPattern = Pattern.compile("[?&](?:favid|fav_id)=(\\d+)", Pattern.CASE_INSENSITIVE);
        java.util.HashSet<String> seen = new java.util.HashSet<>();

        for (Element link : links) {
            try {
                String href = link.attr("href");
                Matcher m = tidPretty.matcher(href);
                String tid = m.find() ? m.group(1) : "";
                if (TextUtils.isEmpty(tid)) {
                    m = tidQuery.matcher(href);
                    if (m.find()) tid = m.group(1);
                }
                if (TextUtils.isEmpty(tid) || seen.contains(tid)) continue;

                String title = link.attr("title").trim();
                if (TextUtils.isEmpty(title)) title = link.text().trim();
                if (TextUtils.isEmpty(title)) continue;

                Thread t = new Thread();
                t.setTid(tid);
                t.setTitle(title);

                String favid = firstNonEmpty(link.attr("data-favid"), link.attr("data-fav-id"),
                        link.attr("favid"), link.attr("data-id"));
                Matcher hrefFav = favidHrefPattern.matcher(href);
                if (TextUtils.isEmpty(favid) && hrefFav.find()) favid = hrefFav.group(1);

                // 删除链接通常与标题同处于 li，也可能放在 div/表格行的上层容器。
                Element parent = link;
                for (int depth = 0; parent != null && depth < 6 && TextUtils.isEmpty(favid); depth++) {
                    Elements deleteLinks = parent.select(
                            "a[href*=spacecp][href*=favorite], "
                                    + "a[href*=favid], a[data-favid], a[data-fav-id]");
                    for (Element deleteLink : deleteLinks) {
                        String deleteHref = deleteLink.attr("href");
                        Matcher dm = favidHrefPattern.matcher(deleteHref);
                        if (dm.find()) { favid = dm.group(1); break; }
                        favid = firstNonEmpty(deleteLink.attr("data-favid"),
                                deleteLink.attr("data-fav-id"), deleteLink.attr("favid"));
                        if (!TextUtils.isEmpty(favid)) break;
                    }
                    if (TextUtils.isEmpty(favid)) {
                        Matcher fm = favidPattern.matcher(parent.outerHtml());
                        if (fm.find()) favid = fm.group(1);
                    }
                    parent = parent.parent();
                }

                t.setFavid(favid);
                seen.add(tid);
                favorites.add(t);
            } catch (Exception ignored) {}
        }
        return favorites;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    /**
     * 解析积分详情（home.php?mod=space&do=profile&mobile=2）
     * 返回积分、金币、好评、信誉等键值对
     */
    public static Map<String, String> parseCreditDetails(String html) {
        Map<String, String> credits = new HashMap<>();
        if (TextUtils.isEmpty(html)) return credits;
        Document doc = Jsoup.parse(html);
        Elements jfLis = doc.select(".comiis_space_profilejf ul li");
        for (int i = 0; i < jfLis.size(); i++) {
            try {
                Element li = jfLis.get(i);
                Element valEl = li.select(".f_0, span").first();
                String value = valEl != null ? valEl.text().trim() : li.text().trim();
                // 提取标签名（去掉数字值）
                String label = li.ownText().trim();
                if (label.isEmpty()) label = "项目" + (i + 1);
                credits.put(label, value);
            } catch (Exception ignored) {}
        }
        // 再从 comiis_space_profile 中提取详细资料
        Elements profileLis = doc.select(".comiis_space_profile li");
        for (Element li : profileLis) {
            try {
                String label = li.select("span").first() != null ? li.select("span").first().text().trim() : "";
                String value = li.select(".profile_rs").first() != null ? li.select(".profile_rs").first().text().trim() : "";
                if (!label.isEmpty() && !value.isEmpty()) {
                    credits.put(label, value);
                }
            } catch (Exception ignored) {}
        }
        return credits;
    }

    /**
     * 解析用户资料数据中的可编辑字段（供编辑资料页使用）
     */
    public static Map<String, String> parseEditableProfile(String html) {
        Map<String, String> fields = new HashMap<>();
        if (TextUtils.isEmpty(html)) return fields;
        Document doc = Jsoup.parse(html);
        // 查找所有表单输入字段
        Elements inputs = doc.select("input[type=text], input[type=email], input[type=url], textarea, select");
        for (Element input : inputs) {
            try {
                String name = input.attr("name");
                String value = input.val().trim();
                if (!name.isEmpty()) {
                    fields.put(name, value);
                }
            } catch (Exception ignored) {}
        }
        return fields;
    }

    private static int parseIntFromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            Matcher m = Pattern.compile("(\\d+)").matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    private static String firstNonEmptyAttr(Element element, String... names) {
        if (element == null || names == null) return null;
        for (String name : names) {
            if (name != null && element.hasAttr(name)) {
                String value = element.attr(name);
                if (!TextUtils.isEmpty(value)) return value.trim();
            }
        }
        return null;
    }

    private static String resolveAvatarUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return BASE_DOMAIN + url.substring(1);
        return BASE_DOMAIN + url;
    }

    private static boolean isPostImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
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
                && !lower.contains("avatar.php");
    }

    /**
     * 补全附件图片URL（处理相对路径、懒加载路径等）
     * MT论坛移动版使用 comiis_loadimages 属性存储真实URL
     */
    private static String resolveAttachmentUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        // 如果已经是完整URL
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return BASE_DOMAIN + url.substring(1);
        }
        // 纯相对路径
        return BASE_DOMAIN + url;
    }

    /**
     * 提取列表卡片中的多张帖子图片，供所有帖子列表页面统一使用。
     */
    private static void populateThreadImages(Element item, Thread thread) {
        List<String> imageUrls = new ArrayList<>();
        Elements imageElements = item.select(
                ".comiis_pyqlist_imgs img, .comiis_pyqlist_img img");
        for (Element image : imageElements) {
            String src = firstNonEmptyAttr(image,
                    "comiis_loadimages", "data-original", "data-src",
                    "data-file", "file", "data-lazy-src", "src");
            String fullUrl = resolveAttachmentUrl(src);
            if (isPostImageUrl(fullUrl) && !imageUrls.contains(fullUrl)) {
                imageUrls.add(fullUrl);
                if (imageUrls.size() >= 4) break;
            }
        }

        // 某些空间帖子页没有图片容器，使用卡片正文区域中的图片作为兜底。
        if (imageUrls.isEmpty()) {
            Elements fallbackImages = item.select(".mmlist_li_box img");
            for (Element image : fallbackImages) {
                String src = firstNonEmptyAttr(image,
                        "comiis_loadimages", "data-original", "data-src",
                        "data-file", "file", "data-lazy-src", "src");
                String fullUrl = resolveAttachmentUrl(src);
                if (isPostImageUrl(fullUrl) && !imageUrls.contains(fullUrl)) {
                    imageUrls.add(fullUrl);
                    if (imageUrls.size() >= 4) break;
                }
            }
        }

        thread.setImageUrls(imageUrls);
        if (!imageUrls.isEmpty()) {
            thread.setHasImage(true);
            if (TextUtils.isEmpty(thread.getThumbnailUrl())) {
                thread.setThumbnailUrl(imageUrls.get(0));
            }
        }
    }

    /**
     * 获取移动端帖子列表URL
     */
    public static String getThreadListUrl(String fid, int page) {
        return BASE_DOMAIN + "forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page + "&mobile=2";
    }

    /**
     * 获取版块帖子列表URL(桌面版,不带 mobile=2 参数)
     * 用于 mobile=2 页面返回登录提示页时的回退请求:桌面版未登录也可查看帖子列表
     */
    public static String getThreadListUrlDesktop(String fid, int page) {
        return BASE_DOMAIN + "forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page;
    }

    /**
     * 获取首页帖子流URL
     */
    public static String getHomeUrl(int page) {
        return BASE_DOMAIN + "forum.php?mod=guide&view=newthread&page=" + page + "&mobile=2";
    }

    /**
     * 获取导读URL
     */
    public static String getGuideUrl(String view, int page) {
        return BASE_DOMAIN + "forum.php?mod=guide&view=" + view + "&page=" + page + "&mobile=2";
    }

    /**
     * 获取登录URL
     */
    public static String getLoginUrl() {
        return BASE_DOMAIN + "member.php?mod=logging&action=login&mobile=2";
    }

/**
     * 获取登录提交URL
     * ★ 修复：从登录页面HTML中提取 form 的 action 属性作为登录提交地址
     * 不再手动拼接，因为 loginhash 和 formhash 是不同的值
     */
    public static String getLoginPostUrl() {
        return BASE_DOMAIN + "member.php?mod=logging&action=login&loginsubmit=yes&loginhash=" + "&mobile=2";
    }

    /**
     * 从登录页面HTML中提取正确的登录提交URL（含 loginhash）
     */
    public static String extractLoginPostUrl(String loginPageHtml) {
        if (TextUtils.isEmpty(loginPageHtml)) return getLoginPostUrl();
        Document doc = Jsoup.parse(loginPageHtml);
        Element form = doc.select("form[id=loginform]").first();
        if (form == null) {
            form = doc.select("form[method=post][action*=login]").first();
        }
        if (form != null) {
            String action = form.attr("action");
            if (!TextUtils.isEmpty(action)) {
                if (action.startsWith("http")) return action;
                return BASE_DOMAIN + action;
            }
        }
        return getLoginPostUrl();
    }

    /**
     * 获取用户空间URL
     */
    public static String getUserSpaceUrl(String uid) {
        return BASE_DOMAIN + "home.php?mod=space&uid=" + uid + "&do=profile&mobile=2";
    }

    /**
     * 获取消息列表URL
     */
    public static String getMessageUrl() {
        return BASE_DOMAIN + "home.php?mod=space&do=pm";
    }

    /**
     * 获取帖子详情URL。
     * 本论坛 Comiis 模板的评论排序参数实际为：
     *   ordertype=1：倒序（最新评论在前）
     *   不传 ordertype：正序（最早评论在前）
     */
    public static String getThreadDetailUrl(String tid) {
        return BASE_DOMAIN + "forum.php?mod=viewthread&tid=" + tid + "&mobile=2";
    }

    public static String getThreadDetailUrl(String tid, String order) {
        StringBuilder url = new StringBuilder(BASE_DOMAIN)
                .append("forum.php?mod=viewthread&tid=").append(tid)
                .append("&mobile=2");
        if ("desc".equalsIgnoreCase(order)) {
            url.append("&ordertype=1");
        }
        return url.toString();
    }

    /** 获取指定评论页，并沿用论坛实际的 ordertype 排序参数。 */
    public static String getThreadDetailUrl(String tid, int page, String order) {
        StringBuilder url = new StringBuilder(BASE_DOMAIN)
                .append("forum.php?mod=viewthread&tid=").append(tid)
                .append("&page=").append(page)
                .append("&mobile=2");
        if ("desc".equalsIgnoreCase(order)) {
            url.append("&ordertype=1");
        }
        return url.toString();
    }


    /**
     * 获取帖子详情URL（桌面版）
     */
    public static String getThreadDesktopDetailUrl(String tid) {
        return BASE_DOMAIN + "forum.php?mod=viewthread&tid=" + tid;
    }

    /**
     * 获取发帖页面URL
     */
    public static String getNewThreadUrl(String fid) {
        return BASE_DOMAIN + "forum.php?mod=post&action=newthread&fid=" + fid + "&mobile=2";
    }

    /**
     * 构建 Discuz! 搜索排序参数
     * @param orderby lastpost(最新回复)/dateline(最新发布)/replies(最多回复)
     * @return 排序参数字符串，含前导 &
     */
    public static String buildSortQuery(String orderby) {
        if ("replies".equals(orderby)) {
            return "&orderby=replies&ascdesc=desc";
        } else if ("dateline".equals(orderby)) {
            return "&orderby=dateline&ascdesc=desc";
        }
        return "&orderby=lastpost&ascdesc=desc"; // 默认：最新回复
    }

    /**
     * 获取搜索URL（强制 mobile=2，否则返回桌面版HTML无法解析forumlist_li）
     * @param keyword 搜索关键词
     * @param page 页码，从1开始
     * @param orderby 排序方式：lastpost/dateline/replies
     */
    public static String getSearchUrl(String keyword, int page, String orderby) {
        try {
            String encoded = URLEncoder.encode(keyword, "UTF-8");
            String url = BASE_DOMAIN + "search.php?mod=forum&searchsubmit=yes&srchtxt=" + encoded
                    + buildSortQuery(orderby) + "&mobile=2";
            if (page > 1) {
                url += "&page=" + page;
            }
            return url;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            String url = BASE_DOMAIN + "search.php?mod=forum&searchsubmit=yes&mobile=2";
            if (page > 1) {
                url += "&page=" + page;
            }
            return url;
        }
    }

    /**
     * 获取搜索URL（默认按最新回复排序）
     * @param keyword 搜索关键词
     * @param page 页码，从1开始
     */
    public static String getSearchUrl(String keyword, int page) {
        return getSearchUrl(keyword, page, "lastpost");
    }

    /**
     * 从搜索结果HTML中提取分页信息
     * Discuz! 搜索分页依赖 searchid（搜索会话ID），
     * 分页链接格式为 search.php?mod=forum&searchid=XXX&page=N
     * @return 最大页码，如果只有一页或无分页则返回1
     */
    public static int parseSearchTotalPages(String html) {
        if (TextUtils.isEmpty(html)) return 1;
        Document doc = Jsoup.parse(html);
        // 搜索分页链接: <a href="search.php?mod=forum&searchid=XXX&page=N"
        Elements pageLinks = doc.select("a[href*=searchid]");
        if (pageLinks.isEmpty()) {
            // 兜底：查找任何带 page= 的链接
            pageLinks = doc.select("a[href*=page=]");
        }
        int maxPage = 1;
        Pattern pagePtn = Pattern.compile("[&?]page=(\\d+)");
        for (Element pl : pageLinks) {
            Matcher pm = pagePtn.matcher(pl.attr("href"));
            if (pm.find()) {
                try {
                    int p = Integer.parseInt(pm.group(1));
                    if (p > maxPage) maxPage = p;
                } catch (Exception ignored) {}
            }
        }
        return maxPage;
    }

    /**
     * 从搜索结果第一页的HTML中提取 searchid（Discuz! 搜索会话ID）
     * @return searchid 字符串，如果没有找到则返回 null
     */
    public static String extractSearchId(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Matcher m = Pattern.compile("searchid=(\\d+)").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
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
    public static String getSearchPageUrl(String searchId, int page, String orderby) {
        return BASE_DOMAIN + "search.php?mod=forum&searchid=" + searchId
                + buildSortQuery(orderby) + "&searchsubmit=yes&page=" + page + "&mobile=2";
    }

    /**
     * 获取搜索分页URL（默认按最新回复排序）
     * @param searchId 搜索会话ID
     * @param page 页码
     * @return 分页URL
     */
    public static String getSearchPageUrl(String searchId, int page) {
        return getSearchPageUrl(searchId, page, "lastpost");
    }

    /**
     * 获取社区页面URL（版块列表+签到入口+统计数据）
     * forumlist=1 返回版块列表页面，包含签到入口和统计信息
     */
    public static String getForumlistMobileUrl() {
        return BASE_DOMAIN + "forum.php?forumlist=1&mobile=2";
    }

    /**
     * 解析社区页面（forum.php?forumlist=1&mobile=2）
     * 提取：签到入口文本、formhash、登录状态、4项统计数据、全量版块列表
     *
     * @param html 社区页面 HTML
     * @return CommunityPageData 对象
     */
    public static CommunityPageData parseCommunityPage(String html) {
        CommunityPageData data = new CommunityPageData();
        Document doc = Jsoup.parse(html);
        
        // === 1. 提取 formhash ===
        // 方式1：从 input[name=formhash] 提取
        Element fhInput = doc.select("input[name=formhash]").first();
        if (fhInput != null) {
            String fh = fhInput.attr("value");
            if (!fh.isEmpty()) {
                data.setFormhash(fh);
            }
        }
        // 方式2：从 JavaScript 中 formhash=xxx 提取（MT论坛 k_misign 插件）
        if (data.getFormhash() == null || data.getFormhash().isEmpty()) {
            Matcher fhMatcher = Pattern.compile("formhash\\s*=\\s*['\"]?([a-f0-9]{8})['\"]?").matcher(html);
            if (fhMatcher.find()) {
                data.setFormhash(fhMatcher.group(1));
            }
        }
        
        // === 2. 解析签到入口（适配 MT 论坛 k_misign 签到插件） ===
        // MT论坛使用科站网签到插件 k_misign，签到按钮结构：
        // <a class="signBtn" href="member.php?mod=logging&action=login&mobile=2">  （未登录时指向登录页）
        // JS代码: $.ajax({type:'POST', url:'plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=xxx'})
        
        // 2a. 查找 signBtn class 的签到按钮
        Element signBtn = doc.select("a.signBtn").first();
        if (signBtn != null) {
            String signHref = signBtn.attr("href");
            data.setSignInText(signBtn.text().trim());
            if (!signHref.isEmpty()) {
                if (!signHref.startsWith("http")) {
                    signHref = BASE_DOMAIN + signHref;
                }
                data.setSignInUrl(signHref);
            }
            // 检查签到链接是否指向登录页 → 当前未登录
            if (signHref.contains("member.php?mod=logging")) {
                data.setLoginRequired(true);
            }
        } else {
            // 2b. 兜底：查找包含 "签到" 文字的元素
            Element signInLink = doc.select("a:containsOwn(签到)").first();
            if (signInLink != null) {
                String signText = signInLink.text().trim();
                data.setSignInText(signText);
                String signHref = signInLink.attr("href");
                if (!signHref.isEmpty()) {
                    if (!signHref.startsWith("http")) {
                        signHref = BASE_DOMAIN + signHref;
                    }
                    data.setSignInUrl(signHref);
                }
                if (signHref.contains("member.php?mod=logging")) {
                    data.setLoginRequired(true);
                }
            } else {
                // 2c. 兜底：查找包含 "签到" 图标文字的元素
                Element signIcon = doc.select("i:contains(签到), span:contains(签到), em:contains(签到)").first();
                if (signIcon != null) {
                    data.setSignInText(signIcon.text().trim());
                }
            }
        }
        
        // 2d. 提取真实的签到 AJAX URL（k_misign 插件）
        Matcher signUrlMatcher = Pattern.compile("(plugin\\.php\\?id=k_misign[^'\"\\s]*)").matcher(html);
        if (signUrlMatcher.find()) {
            String ajaxUrl = signUrlMatcher.group(1);
            if (!ajaxUrl.startsWith("http")) {
                ajaxUrl = BASE_DOMAIN + ajaxUrl;
            }
            // 替换 formhash 占位符为实际值
            String fh = data.getFormhash();
            if (fh != null && !fh.isEmpty()) {
                ajaxUrl = ajaxUrl.replaceAll("formhash=[a-f0-9]*", "formhash=" + fh);
            }
            data.setSignInUrl(ajaxUrl);
        }
        
        // === 3. 解析4项统计数据 ===
        // 统计数据通常位于页面顶部或版块列表区域上方，格式如 "今日23 昨日4584 帖子4474223 会员345343"
        // 尝试在页面中查找包含这些关键词的文本
        String pageText = doc.body() != null ? doc.body().text() : "";
        
        // 方法1：查找包含 "今日" "昨日" "帖子" "会员" 的统计信息块
        // 常用结构：span.stats_num 或特定 class 的容器
        // 尝试匹配 "今日23" 这种模式（中文+数字连写）
        
        // 今日
        Pattern todayPtn = Pattern.compile("今日[：:\\s]*([0-9,，]+)");
        Matcher m = todayPtn.matcher(pageText);
        if (m.find()) {
            data.setTodayPosts(parseIntFromText(m.group(1)));
        }
        
        // 昨日
        Pattern yesterdayPtn = Pattern.compile("昨日[：:\\s]*([0-9,，]+)");
        m = yesterdayPtn.matcher(pageText);
        if (m.find()) {
            data.setYesterdayPosts(parseIntFromText(m.group(1)));
        }
        
        // 帖子总数
        Pattern postsPtn = Pattern.compile("帖子[：:\\s]*([0-9,，]+)");
        m = postsPtn.matcher(pageText);
        if (m.find()) {
            data.setTotalPosts(parseIntFromText(m.group(1)));
        }
        
        // 会员总数
        Pattern membersPtn = Pattern.compile("会员[：:\\s]*([0-9,，]+)");
        m = membersPtn.matcher(pageText);
        if (m.find()) {
            data.setTotalMembers(parseIntFromText(m.group(1)));
        }
        
        // 方法2：如果上述正则没匹配到，尝试从特定 CSS 选择器中提取
        if (data.getTodayPosts() == 0 && data.getYesterdayPosts() == 0) {
            // Comiis 模板中统计信息可能位于 div.comiis_stats 或类似容器中
            Element statsEl = doc.select("div.comiis_stats, div.stats, .forum_stats, .statistic").first();
            if (statsEl != null) {
                String statsText = statsEl.text();
                m = todayPtn.matcher(statsText);
                if (m.find()) data.setTodayPosts(parseIntFromText(m.group(1)));
                m = yesterdayPtn.matcher(statsText);
                if (m.find()) data.setYesterdayPosts(parseIntFromText(m.group(1)));
                m = postsPtn.matcher(statsText);
                if (m.find()) data.setTotalPosts(parseIntFromText(m.group(1)));
                m = membersPtn.matcher(statsText);
                if (m.find()) data.setTotalMembers(parseIntFromText(m.group(1)));
            }
        }
        
        // === 3. 解析全量版块列表 ===
        // 复用 parseForumCategories 的解析逻辑，将结果扁平化为 Forum 列表
        List<ForumCategory> categories = parseForumCategories(html);
        List<ForumCategory.Forum> allForums = new ArrayList<>();
        if (categories != null) {
            for (ForumCategory cat : categories) {
                if (cat.getForums() != null) {
                    for (ForumCategory.Forum forum : cat.getForums()) {
                        allForums.add(forum);
                    }
                }
            }
        }
        data.setForums(allForums);
        data.setCategories(categories);
        
        return data;
    }

    /**
     * 解析桌面版论坛列表页(forum.php?forumlist=1&mobile=no)各版块的统计:
     * 主题数、总帖数、今日新帖。
     * 结构:<dl><dt><a href="...forum-41-1.html">版块名</a><em title="今日"> (860)</em></dt>
     *       <dd class="kmlineheight"><em>主题: 145</em>, <em>帖数: <span title="12714">1万</span></em></dd></dl>
     *
     * @return Map<fid, long[]> 其中 long[0]=主题数, long[1]=总帖数, long[2]=今日新帖
     */
    public static Map<String, long[]> parseDesktopForumStats(String html) {
        Map<String, long[]> result = new HashMap<>();
        if (TextUtils.isEmpty(html)) return result;
        try {
            Document doc = Jsoup.parse(html);
            Elements dts = doc.select("dl dt");
            Pattern fidPtn = Pattern.compile("forum-(\\d+)-1\\.html");
            for (Element dt : dts) {
                Element a = dt.select("a[href*=forum-]").first();
                if (a == null) continue;
                Matcher m = fidPtn.matcher(a.attr("href"));
                if (!m.find()) continue;
                String fid = m.group(1);
                long[] stats = new long[3];
                // 今日新帖:<em title="今日"> (860)</em>
                Element todayEm = dt.select("em[title=今日]").first();
                if (todayEm != null) {
                    String tText = todayEm.text().replaceAll("[^0-9]", "").trim();
                    if (!tText.isEmpty()) stats[2] = Long.parseLong(tText);
                }
                // 主题/帖数:从 dt 所在 dl 的第一个 dd 中取
                Element dlEl = dt.parent() != null ? dt.parent().parent() : null;
                Element dd = null;
                if (dlEl != null) dd = dlEl.select("dd").first();
                if (dd != null) {
                    Matcher mt = Pattern.compile("主题[::\\s]*([0-9,,]+)").matcher(dd.text());
                    if (mt.find()) stats[0] = parseLongFromText(mt.group(1));
                    // 帖数优先取 <span title="12714"> 的 title 精确值
                    Element postSpan = dd.select("span[title]").first();
                    if (postSpan != null && !postSpan.attr("title").isEmpty()) {
                        stats[1] = parseLongFromText(postSpan.attr("title"));
                    } else {
                        Matcher mp = Pattern.compile("帖[数子][::\\s]*([0-9,,]+)").matcher(dd.text());
                        if (mp.find()) stats[1] = parseLongFromText(mp.group(1));
                    }
                }
                result.put(fid, stats);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * 解析版块首页头部信息(forum-XX-1.html):
     * <h2 class="f_f">逆向交流</h2>
     * <p class="f_f comiis_tm8">今日 862&nbsp;&nbsp;帖子 2221581&nbsp;&nbsp;关注 5358</p>
     * <p class="f_f comiis_tm8">逆向技术交流分享,求助帖请发到其它版块~</p>
     *
     * @return String[] {描述, 今日数, 总帖数},缺失字段为空/0
     */
    public static String[] parseForumHeaderInfo(String html) {
        String[] info = new String[3];
        if (TextUtils.isEmpty(html)) return info;
        try {
            Document doc = Jsoup.parse(html);
            String statsLine = "";

            // === 方案1:定位第一个包含"今日"统计的 p.comiis_tm8(Comiis 版块头) ===
            Element statsP = null;
            for (Element el : doc.getAllElements()) {
                if (el.tagName().equals("p") && el.hasClass("comiis_tm8")) {
                    String t = el.text();
                    if (t.contains("今日") || t.contains("帖子")) {
                        statsP = el;
                        break;
                    }
                }
            }

            if (statsP != null) {
                statsLine = statsP.text().trim();
                // 紧随其后的下一个 p 即版块描述
                Element next = statsP.nextElementSibling();
                while (next != null && !next.tagName().equals("p")) {
                    next = next.nextElementSibling();
                }
                if (next != null) {
                    String d = next.text().trim();
                    if (!d.isEmpty()) info[0] = d;
                }
            }

            // === 方案2(兜底):h2.f_f 容器内的 p 组合 ===
            if (info[0].isEmpty()) {
                Element h2 = doc.select("h2.f_f").first();
                if (h2 != null && h2.parent() != null) {
                    Elements ps = h2.parent().select(":scope > p");
                    for (Element p : ps) {
                        String t = p.ownText().trim();
                        if (t.isEmpty()) continue;
                        if (statsLine.isEmpty() && (t.contains("今日") || t.contains("帖子"))) {
                            statsLine = t;
                        } else if (info[0].isEmpty()) {
                            info[0] = t;
                        }
                    }
                }
            }

            // === 提取统计数值 ===
            if (!statsLine.isEmpty()) {
                Matcher m = Pattern.compile("今日[\\s\\u00A0]*([0-9,,]+)").matcher(statsLine);
                if (m.find()) info[1] = String.valueOf(parseLongFromText(m.group(1)));
                m = Pattern.compile("帖子[\\s\\u00A0]*([0-9,,]+)").matcher(statsLine);
                if (m.find()) info[2] = String.valueOf(parseLongFromText(m.group(1)));
            }
        } catch (Exception ignored) {}
        return info;
    }

    /**
     * 从文本中解析长整型数值(兼容千分位逗号)
     */
    private static long parseLongFromText(String text) {
        if (text == null) return 0;
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) return 0;
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 社区页面数据容器（内部静态类，用于封装 parseCommunityPage 的返回值）
     */
    public static class CommunityPageData {
        private String signInText = "";
        private String signInUrl = "";
        private String formhash = "";
        private boolean alreadySignedIn = false;
        private boolean loginRequired = false;
        private int todayPosts = 0;
        private int yesterdayPosts = 0;
        private int totalPosts = 0;
        private int totalMembers = 0;
        private List<ForumCategory.Forum> forums = new ArrayList<>();
        private List<ForumCategory> categories = new ArrayList<>();
        
        public String getSignInText() { return signInText; }
        public void setSignInText(String signInText) { this.signInText = signInText; }
        
        public String getSignInUrl() { return signInUrl; }
        public void setSignInUrl(String signInUrl) { this.signInUrl = signInUrl; }
        
        public String getFormhash() { return formhash; }
        public void setFormhash(String formhash) { this.formhash = formhash; }
        
        public boolean isAlreadySignedIn() { return alreadySignedIn; }
        public void setAlreadySignedIn(boolean alreadySignedIn) { this.alreadySignedIn = alreadySignedIn; }
        
        public boolean isLoginRequired() { return loginRequired; }
        public void setLoginRequired(boolean loginRequired) { this.loginRequired = loginRequired; }
        
        public int getTodayPosts() { return todayPosts; }
        public void setTodayPosts(int todayPosts) { this.todayPosts = todayPosts; }
        
        public int getYesterdayPosts() { return yesterdayPosts; }
        public void setYesterdayPosts(int yesterdayPosts) { this.yesterdayPosts = yesterdayPosts; }
        
        public int getTotalPosts() { return totalPosts; }
        public void setTotalPosts(int totalPosts) { this.totalPosts = totalPosts; }
        
        public int getTotalMembers() { return totalMembers; }
        public void setTotalMembers(int totalMembers) { this.totalMembers = totalMembers; }
        
        public List<ForumCategory.Forum> getForums() { return forums; }
        public void setForums(List<ForumCategory.Forum> forums) { this.forums = forums; }
        
        public List<ForumCategory> getCategories() { return categories; }
        public void setCategories(List<ForumCategory> categories) { this.categories = categories; }
    }
}