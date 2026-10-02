package com.solosu.mtforum.ai;

import android.content.Context;
import android.text.TextUtils;

import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 论坛接口的 AI 工具层。
 * 把论坛的读接口包装成可被大模型 function calling 调用的工具，
 * 让 AI 能主动查询、学习、总结帖子内容。
 *
 * 所有方法都是阻塞式的，必须在后台线程调用。
 */
public final class ForumTools {

    private ForumTools() {}

    // ==================== 工具定义 ====================

    /** 返回全部可供 AI 调用的工具定义 */
    public static List<AiClient.ToolDef> definitions() {
        List<AiClient.ToolDef> list = new ArrayList<>();

        list.add(new AiClient.ToolDef(
                "search_forum",
                "在 MT 论坛搜索帖子。返回匹配的帖子列表，含标题、作者、版块、回复数、链接和摘要。"
                + "适合查找某个技术话题的讨论，例如「脱壳」「smali 修改」「签名校验」。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"},"
                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始，默认1\"},"
                + "\"orderby\":{\"type\":\"string\",\"enum\":[\"lastpost\",\"dateline\",\"replies\"],"
                + "\"description\":\"排序：lastpost最新回复 / dateline最新发布 / replies最多回复\"}"
                + "},\"required\":[\"keyword\"]}"));

        list.add(new AiClient.ToolDef(
                "list_threads",
                "列出某个版块下的帖子。不传 fid 时返回论坛最新帖子。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"fid\":{\"type\":\"string\",\"description\":\"版块ID，例如 2。不传则取最新帖子\"},"
                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始\"}"
                + "},\"required\":[]}"));

        list.add(new AiClient.ToolDef(
                "list_forums",
                "获取论坛的全部版块列表（含版块ID，供 list_threads 使用）。",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));

        list.add(new AiClient.ToolDef(
                "get_thread",
                "获取帖子完整详情，包含楼主正文、隐藏内容、统计数据和回复列表。"
                + "默认会把该帖的回复一次性抓到底（跨所有分页），无需手动翻页。"
                + "这是学习帖子内容的主要接口，返回纯文本正文，便于阅读和总结。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"},"
                + "\"page\":{\"type\":\"integer\",\"description\":\"起始回复页码，从1开始\"},"
                + "\"max_replies\":{\"type\":\"integer\",\"description\":\"最多返回多少条回复，默认200\"},"
                + "\"fetch_all\":{\"type\":\"boolean\",\"description\":\"是否抓全所有分页的回复，默认 true\"}"
                + "},\"required\":[\"tid\"]}"));

        list.add(new AiClient.ToolDef(
                "get_replies",
                "获取帖子的回复列表。默认自动翻页、一次抓到底（跨所有分页），"
                + "返回带 total_pages / fetched 字段，可确认是否抓全。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"},"
                + "\"page\":{\"type\":\"integer\",\"description\":\"起始页码，从1开始\"},"
                + "\"max_replies\":{\"type\":\"integer\",\"description\":\"最多返回多少条，默认300\"},"
                + "\"fetch_all\":{\"type\":\"boolean\",\"description\":\"是否抓全所有分页，默认 true\"}"
                + "},\"required\":[\"tid\"]}"));

        list.add(new AiClient.ToolDef(
                "my_threads",
                "获取当前登录用户的帖子列表。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，从1开始\"}"
                + "},\"required\":[]}"));

        list.add(new AiClient.ToolDef(
                "get_notices",
                "获取当前用户的通知和私信列表，含未读状态。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"pm\",\"mypost\",\"interactive\",\"system\"],"
                + "\"description\":\"通知类型：pm私信 / mypost我的帖子 / interactive互动 / system系统\"}"
                + "},\"required\":[]}"));

        list.add(new AiClient.ToolDef(
                "get_user_profile",
                "获取指定用户的资料信息。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"uid\":{\"type\":\"string\",\"description\":\"用户UID\"}"
                + "},\"required\":[\"uid\"]}"));

        list.add(new AiClient.ToolDef(
                "post_reply",
                "在指定帖子下发表回复。这是有副作用的写操作，仅在明确需要回复时调用。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"},"
                + "\"message\":{\"type\":\"string\",\"description\":\"回复内容\"}"
                + "},\"required\":[\"tid\",\"message\"]}"));

        list.add(new AiClient.ToolDef(
                "unlock_hidden",
                "解锁帖子的隐藏内容：当帖子含「回复可见」的隐藏内容时，自动发表一条回复以解锁，"
                + "然后回读页面确认是否已解锁并返回隐藏内容。「回复可见」指帖子里显示"
                + "「游客，如果您要查看本帖隐藏内容请回复」。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"tid\":{\"type\":\"string\",\"description\":\"帖子ID\"},"
                + "\"message\":{\"type\":\"string\",\"description\":\"可选，自定义回复内容；不传则根据帖子内容自动生成\"}"
                + "},\"required\":[\"tid\"]}"));

        return list;
    }

    // ==================== 工具执行 ====================
    /**
     * 把工具清单渲染成纯文本说明，供不支持 function calling 的模型使用。
     * 模型按约定单独输出一行 {"tool":"名字","args":{...}} 即被本地执行。
     */
    public static String textToolCatalog() {
        StringBuilder sb = new StringBuilder();
        sb.append("你可以调用下列工具读取 MT 论坛的真实数据。\n");
        sb.append("需要调用工具时，只输出一行 JSON，不要任何额外文字、不要 markdown 代码块：\n");
        sb.append("{\"tool\":\"工具名\",\"args\":{参数}}\n");
        sb.append("要一次拿多个数据，就输出多行，每行一个调用。\n");
        sb.append("拿到工具结果后：还需要更多数据就继续输出调用行；");
        sb.append("信息已经够回答用户时，直接输出最终回答的正文，不要再输出任何 JSON。\n");
        sb.append("绝对不要只在脑子里计划要调用什么，要么真的输出调用行，要么直接回答。\n\n");
        sb.append("可用工具：\n");
        for (AiClient.ToolDef t : definitions()) {
            sb.append("- ").append(t.name).append("：").append(t.description).append('\n');
            sb.append("  参数：").append(t.parametersJson).append('\n');
        }
        return sb.toString();
    }

    /**
     * 执行一次工具调用。
     *
     * @return JSON 字符串结果，供回填给模型
     */
    public static String execute(Context context, String toolName, JSONObject args) {
        try {
            if (TextUtils.isEmpty(toolName)) return err("缺少工具名");
            switch (toolName) {
                case "search_forum":   return searchForum(context, args);
                case "list_threads":   return listThreads(context, args);
                case "list_forums":    return listForums(context);
                case "get_thread":     return getThread(context, args);
                case "get_replies":    return getReplies(context, args);
                case "my_threads":     return myThreads(context, args);
                case "get_notices":    return getNotices(context, args);
                case "get_user_profile": return getUserProfile(context, args);
                case "post_reply":     return postReply(context, args);
                case "unlock_hidden":  return unlockHidden(context, args);
                default:               return err("未知工具: " + toolName);
            }
        } catch (Exception e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== 读接口实现 ====================

    private static String searchForum(Context context, JSONObject args) throws Exception {
        String kw = args.optString("keyword", "").trim();
        if (TextUtils.isEmpty(kw)) return err("关键词为空");
        int page = Math.max(1, args.optInt("page", 1));
        String orderby = args.optString("orderby", "lastpost");

        HttpClient client = requireLogin(context);
        String url = ForumParser.getSearchUrl(kw, page, orderby);
        String html = client.get(url);
        if (isBad(html)) return err("搜索失败，登录态可能已失效");

        List<Thread> list = ForumParser.parseSearchResults(html);
        if (list.isEmpty()) list = ForumParser.parseThreadList(html);

        JSONObject out = new JSONObject();
        out.put("keyword", kw);
        out.put("page", page);
        out.put("total_pages", ForumParser.parseSearchTotalPages(html));
        out.put("count", list.size());
        out.put("threads", threadsToJson(list));
        return out.toString();
    }

    private static String listThreads(Context context, JSONObject args) throws Exception {
        String fid = args.optString("fid", "").trim();
        int page = Math.max(1, args.optInt("page", 1));

        HttpClient client = HttpClient.getInstance();
        String html;
        if (TextUtils.isEmpty(fid)) {
            html = client.get(ForumParser.getHomeUrl(page));
            if (isBad(html)) {
                html = client.get(ForumParser.getGuideUrl("newthread", page));
            }
        } else {
            html = client.get(ForumParser.getThreadListUrl(fid, page));
            if (isBad(html)) {
                html = client.getDesktop(ForumParser.getThreadListUrlDesktop(fid, page));
            }
        }
        if (isBad(html)) return err("帖子列表获取失败");

        List<Thread> list = TextUtils.isEmpty(fid)
                ? ForumParser.parseThreadList(html)
                : ForumParser.parseForumThreadList(html);

        JSONObject out = new JSONObject();
        out.put("fid", fid);
        out.put("page", page);
        out.put("count", list.size());
        out.put("threads", threadsToJson(list));
        return out.toString();
    }

    private static String listForums(Context context) throws Exception {
        HttpClient client = HttpClient.getInstance();
        String html = client.get(ForumParser.getForumlistMobileUrl());
        if (isBad(html)) html = client.getDesktop(
                HttpClient.BASE_URL + "forum.php?forumlist=1");
        if (isBad(html)) return err("版块列表获取失败");

        List<ForumCategory> cats = ForumParser.parseForumCategories(html);
        JSONArray arr = new JSONArray();
        if (cats != null) {
            for (ForumCategory c : cats) {
                JSONObject cat = new JSONObject();
                cat.put("category", safe(c.getName()));
                JSONArray subs = new JSONArray();
                List<ForumCategory.Forum> forums = c.getForums();
                if (forums != null) {
                    for (ForumCategory.Forum f : forums) {
                        JSONObject o = new JSONObject();
                        o.put("fid", safe(f.getFid()));
                        o.put("name", safe(f.getName()));
                        o.put("description", safe(f.getDescription()));
                        o.put("today_posts", f.getTodayPosts());
                        o.put("total_threads", f.getTotalThreads());
                        subs.put(o);
                    }
                }
                cat.put("forums", subs);
                arr.put(cat);
            }
        }
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("categories", arr);
        return out.toString();
    }

    private static String getThread(Context context, JSONObject args) throws Exception {
        String tid = args.optString("tid", "").trim();
        if (TextUtils.isEmpty(tid)) return err("tid 为空");
        int page = Math.max(1, args.optInt("page", 1));
        int maxReplies = args.optInt("max_replies", 200);
        if (maxReplies <= 0) maxReplies = 200;
        // 默认把回复一次性抓到底
        boolean fetchAll = !args.has("fetch_all") || args.optBoolean("fetch_all", true);

        HttpClient client = HttpClient.getInstance();
        String url = page > 1
                ? ForumParser.getThreadDetailUrl(tid, page, null)
                : ForumParser.getThreadDetailUrl(tid);
        String html = client.get(url);
        if (isBad(html)) return err("帖子读取失败，可能不存在或需要登录");

        PostDetail d = ForumParser.parseThreadDetail(html);
        if (d == null) return err("帖子解析失败");

        // 抓全回复：从当前页往后逐页拉取
        List<ReplyItem> allReplies = new ArrayList<>();
        if (d.getReplies() != null) allReplies.addAll(d.getReplies());
        int totalPages = Math.max(1, d.getTotalPages());
        boolean truncated = false;
        if (fetchAll) {
            int maxPages = Math.min(Math.max(page, totalPages), 60);
            for (int p = page + 1; p <= maxPages; p++) {
                if (allReplies.size() >= maxReplies) { truncated = true; break; }
                try {
                    String h2 = client.get(ForumParser.getThreadDetailUrl(tid, p, null));
                    if (isBad(h2)) break;
                    PostDetail d2 = ForumParser.parseThreadDetail(h2);
                    if (d2 == null) break;
                    if (d2.getTotalPages() > totalPages) totalPages = d2.getTotalPages();
                    if (d2.getReplies() != null && !d2.getReplies().isEmpty()) allReplies.addAll(d2.getReplies());
                } catch (Exception e) {
                    break;
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("tid", tid);
        out.put("title", safe(d.getTitle()));
        out.put("forum_name", safe(d.getForumName()));
        out.put("forum_fid", safe(d.getForumFid()));
        out.put("author", safe(d.getAuthor()));
        out.put("author_uid", safe(d.getAuthorUid()));
        out.put("author_level", safe(d.getAuthorLevel()));
        out.put("publish_time", safe(d.getPublishTime()));
        out.put("reply_count", d.getReplyCount());
        out.put("like_count", d.getLikeCount());
        out.put("view_page", d.getCurrentPage());
        out.put("total_pages", totalPages);
        out.put("replies_fetched", allReplies.size());
        out.put("replies_fetched_all", fetchAll && !truncated);

        String body = htmlToText(d.getContentHtml());
        out.put("content", clip(body, 12000));
        out.put("content_length", body.length());

        if (d.isHasHiddenContent() && !TextUtils.isEmpty(d.getHiddenContentHtml())) {
            out.put("hidden_content", clip(htmlToText(d.getHiddenContentHtml()), 4000));
        }

        List<String> imgs = d.getImageUrls();
        if (imgs != null && !imgs.isEmpty()) {
            JSONArray ia = new JSONArray();
            for (int i = 0; i < imgs.size() && i < 12; i++) ia.put(imgs.get(i));
            out.put("images", ia);
        }

        out.put("replies", repliesToJson(allReplies, maxReplies));
        return out.toString();
    }

    private static String getReplies(Context context, JSONObject args) throws Exception {
        String tid = args.optString("tid", "").trim();
        if (TextUtils.isEmpty(tid)) return err("tid 为空");
        int page = Math.max(1, args.optInt("page", 1));
        int max = args.optInt("max_replies", 300);
        if (max <= 0) max = 300;
        // 默认一次抓到最后一页
        boolean fetchAll = !args.has("fetch_all") || args.optBoolean("fetch_all", true);

        HttpClient client = HttpClient.getInstance();
        String html = client.get(ForumParser.getThreadDetailUrl(tid, page, null));
        if (isBad(html)) return err("回复读取失败");

        PostDetail d = ForumParser.parseThreadDetail(html);
        if (d == null) return err("回复解析失败");

        List<ReplyItem> all = new ArrayList<>();
        if (d.getReplies() != null) all.addAll(d.getReplies());
        int totalPages = Math.max(1, d.getTotalPages());
        int cur = Math.max(1, d.getCurrentPage());
        boolean truncated = false;

        if (fetchAll) {
            int maxPages = Math.max(cur, totalPages);
            if (maxPages > 60) maxPages = 60;   // 硬上限，防死循环
            for (int p = cur + 1; p <= maxPages; p++) {
                if (all.size() >= max) { truncated = true; break; }
                try {
                    String h2 = client.get(ForumParser.getThreadDetailUrl(tid, p, null));
                    if (isBad(h2)) break;
                    PostDetail d2 = ForumParser.parseThreadDetail(h2);
                    if (d2 == null) break;
                    if (d2.getTotalPages() > totalPages) totalPages = d2.getTotalPages();
                    if (d2.getReplies() != null && !d2.getReplies().isEmpty()) {
                        all.addAll(d2.getReplies());
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("tid", tid);
        out.put("start_page", cur);
        out.put("total_pages", totalPages);
        out.put("reply_count", d.getReplyCount());
        out.put("fetched", all.size());
        out.put("fetched_all", fetchAll && !truncated);
        if (truncated) out.put("note", "回复条数超过单次上限 " + max + "，已截断");
        out.put("replies", repliesToJson(all, max));
        return out.toString();
    }

    private static String myThreads(Context context, JSONObject args) throws Exception {
        HttpClient client = requireLogin(context);
        // 关键诊断：本地会话与 Cookie 都没有登录态时直接说明，别让上层以为是"没有帖子"
        if (!client.isLoggedIn() && TextUtils.isEmpty(UserSessionManager.getInstance().getUid(context))) {
            return err("当前未检测到登录态。请先在应用内登录 MT 论坛账号，再让 AI 读你的帖子。");
        }
        int page = Math.max(1, args.optInt("page", 1));
        String uid = UserSessionManager.getInstance().getUid(context);

        // 优先用 uid 定位个人空间；若本地没有 uid（会话信息缺失，只有 Cookie 登录态），
        // 退回 view=me —— 服务端会按当前 Cookie 识别本人，避免直接返回空。
        String url;
        if (TextUtils.isEmpty(uid)) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&page=" + page + "&mobile=2";
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&uid=" + uid
                    + "&do=thread&view=me&page=" + page + "&mobile=2";
        }

        String html = client.get(url);
        if (isBad(html) && !TextUtils.isEmpty(uid)) {
            // uid 路径失败时同样退回 view=me 再试一次
            html = client.get(HttpClient.BASE_URL
                    + "home.php?mod=space&do=thread&view=me&page=" + page + "&mobile=2");
        }
        if (isBad(html)) return err("我的帖子读取失败，可能是登录态已失效");

        List<Thread> list = ForumParser.parseForumThreadList(html);
        if (list.isEmpty()) list = ForumParser.parseThreadList(html);

        JSONObject out = new JSONObject();
        out.put("page", page);
        out.put("uid", safe(uid));
        out.put("count", list.size());
        if (list.isEmpty()) {
            out.put("hint", "没有解析到帖子。可能该账号确实没有主题帖，或页面结构与解析规则不符。");
        }
        out.put("threads", threadsToJson(list));
        return out.toString();
    }

    private static String getNotices(Context context, JSONObject args) throws Exception {
        HttpClient client = requireLogin(context);
        String type = args.optString("type", "pm");

        String url;
        if ("pm".equals(type)) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2";
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + type;
        }
        String html = ("pm".equals(type))
                ? client.get(url)
                : client.getDesktop(url);
        if (isBad(html)) return err("通知读取失败");

        List<com.solosu.mtforum.model.Message> items;
        if ("pm".equals(type)) items = ForumParser.parsePmList(html);
        else if ("follower".equals(type)) items = ForumParser.parseFollowerList(html);
        else items = ForumParser.parseNoticeList(html);

        JSONArray arr = new JSONArray();
        int unread = 0;
        for (com.solosu.mtforum.model.Message m : items) {
            JSONObject o = new JSONObject();
            o.put("author", safe(m.getAuthor()));
            o.put("author_uid", safe(m.getAuthorUid()));
            o.put("title", safe(m.getTitle()));
            o.put("summary", clip(safe(m.getSummary()), 300));
            o.put("time", safe(m.getTime()));
            o.put("read", m.isRead());
            arr.put(o);
            if (!m.isRead()) unread++;
        }
        JSONObject out = new JSONObject();
        out.put("type", type);
        out.put("unread", unread);
        out.put("count", arr.length());
        out.put("items", arr);
        return out.toString();
    }

    private static String getUserProfile(Context context, JSONObject args) throws Exception {
        String uid = args.optString("uid", "").trim();
        if (TextUtils.isEmpty(uid)) return err("uid 为空");

        HttpClient client = HttpClient.getInstance();
        String html = client.get(ForumParser.getUserSpaceUrl(uid));
        if (isBad(html)) return err("用户资料读取失败");

        com.solosu.mtforum.model.UserProfile p = ForumParser.parseUserProfile(html);
        if (p == null) return err("用户资料解析失败");

        JSONObject out = new JSONObject();
        out.put("uid", uid);
        out.put("username", safe(p.getUsername()));
        out.put("level", safe(p.getLevel()));
        out.put("group", safe(p.getGroupName()));
        out.put("signature", safe(p.getSignature()));
        out.put("credits", p.getCredits());
        out.put("gold", p.getGold());
        out.put("threads", p.getThreads());
        out.put("posts", p.getPosts());
        out.put("followers", p.getFollowers());
        out.put("following", p.getFollowing());
        out.put("reg_date", safe(p.getRegDate()));
        out.put("last_visit", safe(p.getLastVisit()));
        out.put("online", p.isOnline());
        return out.toString();
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
    private static String unlockHidden(Context context, JSONObject args) throws Exception {
        String tid = args.optString("tid", "").trim();
        if (TextUtils.isEmpty(tid)) return err("tid 为空");
        String message = args.optString("message", "").trim();

        HttpClient client = requireLogin(context);
        if (!client.isLoggedIn()) {
            client.syncFromCookieManager();
            if (!client.isLoggedIn()) return err("未登录，无法解锁");
        }

        // 1. 先看当前状态
        String html = client.get(ForumParser.getThreadDetailUrl(tid));
        if (isBad(html)) return err("获取帖子信息失败");
        PostDetail d = ForumParser.parseThreadDetail(html);
        if (d == null) return err("帖子解析失败");

        JSONObject out = new JSONObject();
        out.put("tid", tid);
        out.put("title", safe(d.getTitle()));

        if (!d.isHasHiddenContent()) {
            out.put("success", true);
            out.put("hidden", false);
            out.put("note", "该帖子没有隐藏内容，无需解锁");
            return out.toString();
        }

        String hiddenHtml = d.getHiddenContentHtml();
        if (!TextUtils.isEmpty(hiddenHtml)
                && !AutoReplyEngine.isLockedHidden(hiddenHtml)) {
            out.put("success", true);
            out.put("hidden", true);
            out.put("already_unlocked", true);
            out.put("content", clip(htmlToText(hiddenHtml), 6000));
            return out.toString();
        }

        // 2. 生成回复内容：优先用调用方/用户自定义文案，否则走共用的本地模板
        String text = cleanupReply(message);
        if (TextUtils.isEmpty(text)) {
            text = AutoReplyEngine.buildUnlockText(context, d);
        }
        if (TextUtils.isEmpty(text)) text = "感谢分享，回复支持一下。";
        text = cleanupReply(text);

        // 3. 发表回复
        String formhash = d.getFormhash();
        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html);
        if (TextUtils.isEmpty(formhash)) return err("无法获取 formhash，回复可能要登录态");

        String fid = safe(d.getForumFid());
        Map<String, String> params = new HashMap<>();
        params.put("formhash", formhash);
        params.put("message", text);
        params.put("replysubmit", "yes");
        params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));

        String url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply"
                + "&fid=" + fid + "&tid=" + tid
                + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";
        String result = client.post(url, params);
        if (TextUtils.isEmpty(result)) return err("回复请求无响应");
        String lower = result.toLowerCase();
        if (lower.contains("请先登录") || lower.contains("没有权限")
                || lower.contains("非法操作") || lower.contains("回复失败")) {
            out.put("success", false);
            out.put("error", clip(stripTags(result), 200));
            out.put("message_sent", text);
            return out.toString();
        }

        // 4. 回读确认是否解锁
        String html2 = client.get(ForumParser.getThreadDetailUrl(tid)
                + "&_unlock=" + System.currentTimeMillis());
        PostDetail d2 = isBad(html2) ? null : ForumParser.parseThreadDetail(html2);
        String hidden2 = d2 == null ? null : d2.getHiddenContentHtml();
        boolean ok = !TextUtils.isEmpty(hidden2)
                && !AutoReplyEngine.isLockedHidden(hidden2);

        out.put("success", ok);
        out.put("hidden", true);
        out.put("message_sent", text);
        if (ok) {
            out.put("content", clip(htmlToText(hidden2), 6000));
        } else {
            out.put("note", "回复已提交，但回读时仍是未解锁状态。可能被论坛风控拦截，或隐藏内容需要审核后才可见。");
        }
        return out.toString();
    }

    private static String cleanupReply(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() > 1 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("「") && t.endsWith("」"))
                || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        t = t.replaceFirst("^(回复|回答|评论)[:：]\\s*", "");
        t = t.replace("**", "").trim();
        if (t.length() > 200) t = t.substring(0, 200);
        return t;
    }

    private static String postReply(Context context, JSONObject args) throws Exception {
        String tid = args.optString("tid", "").trim();
        String message = args.optString("message", "").trim();
        if (TextUtils.isEmpty(tid)) return err("tid 为空");
        if (TextUtils.isEmpty(message)) return err("回复内容为空");

        HttpClient client = requireLogin(context);

        // 先取帖子详情，拿 formhash 和 fid
        String detailUrl = ForumParser.getThreadDetailUrl(tid);
        String html = client.get(detailUrl);
        if (isBad(html)) return err("获取帖子信息失败");

        PostDetail d = ForumParser.parseThreadDetail(html);
        String formhash = d != null ? d.getFormhash() : null;
        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html);
        if (TextUtils.isEmpty(formhash)) return err("无法获取 formhash");

        String fid = d != null ? safe(d.getForumFid()) : "";

        Map<String, String> params = new HashMap<>();
        params.put("formhash", formhash);
        params.put("message", message);
        params.put("replysubmit", "yes");
        params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));

        String url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply"
                + "&fid=" + fid + "&tid=" + tid
                + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";

        String result = client.post(url, params);
        boolean ok = isReplySuccess(result);

        JSONObject out = new JSONObject();
        out.put("success", ok);
        out.put("tid", tid);
        out.put("message", message);
        if (!ok) out.put("raw", clip(stripTags(result), 300));
        return out.toString();
    }

    // ==================== 辅助 ====================

    /** 需要登录的接口统一检查，未登录时尝试从 WebView 补同步一次 */
    private static HttpClient requireLogin(Context context) {
        HttpClient client = HttpClient.getInstance();
        if (!client.isLoggedIn()) client.syncFromCookieManager();
        return client;
    }

    private static boolean isBad(String html) {
        return html == null || html.trim().isEmpty() || ForumParser.isLoginPage(html);
    }

    private static boolean isReplySuccess(String result) {
        if (TextUtils.isEmpty(result)) return false;
        String lower = result.toLowerCase();
        if (lower.contains("请先登录") || lower.contains("formhash")
                || lower.contains("没有权限") || lower.contains("非法操作")
                || lower.contains("回复失败")) {
            return false;
        }
        return lower.contains("回复成功") || lower.contains("succeedhandle")
                || lower.contains("showmessage") || lower.contains("success");
    }

    private static JSONArray threadsToJson(List<Thread> list) throws Exception {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (Thread t : list) {
            JSONObject o = new JSONObject();
            o.put("tid", safe(t.getTid()));
            o.put("title", safe(t.getTitle()));
            o.put("author", safe(t.getAuthor()));
            o.put("author_uid", safe(t.getAuthorUid()));
            o.put("forum_name", safe(t.getForumName()));
            o.put("forum_fid", safe(t.getForumFid()));
            o.put("publish_time", safe(t.getPublishTime()));
            o.put("replies", t.getReplies());
            o.put("views", t.getViews());
            o.put("summary", clip(safe(t.getSummary()), 400));
            o.put("url", ForumParser.getThreadDetailUrl(safe(t.getTid())));
            arr.put(o);
        }
        return arr;
    }

    private static JSONArray repliesToJson(List<ReplyItem> list, int max) throws Exception {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        int n = 0;
        int budget = 80000;   // 总字符预算，防止抓全后撑爆模型上下文
        int used = 0;
        for (ReplyItem r : list) {
            if (n >= max) break;
            String content = safe(r.getContentText());
            if (content.length() > 1500) content = content.substring(0, 1500) + "…";
            if (used + content.length() > budget) break;
            used += content.length();
            JSONObject o = new JSONObject();
            o.put("pid", safe(r.getPid()));
            o.put("author", safe(r.getAuthor()));
            o.put("author_uid", safe(r.getAuthorUid()));
            o.put("time", safe(r.getTime()));
            o.put("content", content);
            o.put("is_op", r.isOP());
            arr.put(o);
            n++;
        }
        return arr;
    }

    /** HTML 转纯文本，保留段落换行，去掉脚本样式 */
    public static String htmlToText(String html) {
        if (TextUtils.isEmpty(html)) return "";
        try {
            Document doc = Jsoup.parse(html);
            doc.select("script,style,br").forEach(e -> {
                if ("br".equals(e.tagName())) e.after("\n");
            });
            String text = doc.text();
            return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        } catch (Exception e) {
            return stripTags(html);
        }
    }

    public static String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…[已截断]" : s;
    }

    private static String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"unknown\"}";
        }
    }
}