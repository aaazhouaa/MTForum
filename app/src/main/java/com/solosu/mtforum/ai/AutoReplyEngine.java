package com.solosu.mtforum.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动回复引擎。
 *
 * 两种工作模式（由 AiConfigManager.isUnlockMode 决定）：
 *
 *  A. 解锁模式（默认）：
 *      扫描最新帖子 → 找出含「回复可见」隐藏内容的帖子 → 发一条贴合正文的回复 → 解锁并回读
 *
 *  B. 评论模式（旧逻辑）：
 *      拉取自己的帖子列表 → 逐个读详情 → 找出「别人的新回复」→ 生成回复并发出
 *
 * 已处理过的 pid / tid 记入内存集合，避免重复处理。
 */
public final class AutoReplyEngine {

    private static final String TAG = "AutoReplyEngine";

    /** 结果回调，运行在主线程 */
    public interface Callback {
        void onFinished(int replied, int skipped, String detail);
    }

    /** 单条待回复任务 */
    private static class Job {
        String tid;
        String title;
        String body;
        String replyAuthor;
        String replyContent;
        String pid;
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 已处理过的回复 pid，防止重复回复 */
    private static final Set<String> HANDLED_PIDS = new HashSet<>();
    /** 已尝试解锁过的 tid */
    private static final Set<String> HANDLED_TIDS = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 论坛防洪限制：两次发帖间隔不得少于该毫秒数（Discuz 默认 15 秒，取 16 秒留余量） */
    private static final long MIN_POST_INTERVAL_MS = 16000L;
    /** 上一次成功/尝试发帖的时间戳，用于全局节流 */
    private static final java.util.concurrent.atomic.AtomicLong LAST_POST_AT =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** build61: 发帖节流串行锁——连开多帖时保证严格 16 秒排队,不并发发帖 */
    private static final Object POST_THROTTLE_LOCK = new Object();

    /**
     * 全局节流：确保任意两次发帖间隔不小于 MIN_POST_INTERVAL_MS。
     * 论坛会拦「两次发表间隔少于 15 秒」，这里主动等待，避免白跑一次被拒。
     */
    private static void waitPostThrottle() {
        synchronized (POST_THROTTLE_LOCK) {
            long last = LAST_POST_AT.get();
            long wait = (last <= 0) ? 0
                    : MIN_POST_INTERVAL_MS - (System.currentTimeMillis() - last);
            if (wait > 0) {
                AiLog.i("auto-unlock", "节流：距上次发帖不足 " + (MIN_POST_INTERVAL_MS / 1000)
                        + " 秒，等待 " + (wait / 1000 + 1) + " 秒");
                try { Thread.sleep(wait + 500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            // build61: 出锁前即占用时间槽——并发线程依次排队,第二个会算到 wait>0
            LAST_POST_AT.set(System.currentTimeMillis());
        }
    }

    /** 判断响应是否为论坛「发帖太频繁」拦截 */
    private static boolean isFloodControl(String resp) {
        if (TextUtils.isEmpty(resp)) return false;
        return resp.contains("两次发表间隔") || resp.contains("请稍候再发表")
                || resp.contains("floodctrl");
    }

    private AutoReplyEngine() {}

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * 只解锁某个帖子（供详情页「进入即解锁」调用）。在当前线程阻塞执行。
     *
     * @return true 表示已解锁（或本来就没锁 / 已解锁）
     */
    public static boolean unlockSingleThread(Context context, String tid) {
        if (TextUtils.isEmpty(tid)) return false;
        try {
            HttpClient client = HttpClient.getInstance();
            if (!client.isLoggedIn()) client.syncFromCookieManager();
            if (!client.isLoggedIn()) {
                AiLog.e("auto-unlock", "未登录，放弃解锁 tid=" + tid);
                return false;
            }

            String html = client.get(ForumParser.getThreadDetailUrl(tid)
                    + "&_unlock_now=" + System.currentTimeMillis());
            if (html == null || html.isEmpty()) {
                AiLog.e("auto-unlock", "拉取详情为空 tid=" + tid);
                return false;
            }
            if (ForumParser.isLoginPage(html)) {
                AiLog.e("auto-unlock", "详情返回登录页（登录态失效）tid=" + tid);
                return false;
            }
            PostDetail d = ForumParser.parseThreadDetail(html);
            if (d == null) {
                AiLog.e("auto-unlock", "解析详情失败 tid=" + tid);
                return false;
            }
            if (!d.isHasHiddenContent()) {
                AiLog.i("auto-unlock", "无隐藏内容，跳过 tid=" + tid);
                return false;
            }
            if (!isLockedHidden(d.getHiddenContentHtml())) {
                AiLog.i("auto-unlock", "已是解锁状态 tid=" + tid);
                return true;
            }

            AiLog.i("auto-unlock", "确认锁定，准备回复解锁 tid=" + tid
                    + " fid=" + safe(d.getForumFid())
                    + " formhash=" + (TextUtils.isEmpty(d.getFormhash()) ? "无" : "有"));

            // 原子认领：详情页有两条解锁链路（maybeAutoUnlock / tryUnlockOnOpen）
            // 会在同一次加载里各起一个线程，没有认领时同一帖会被回两条。
            // 与 tryUnlockOnOpen 共用 claimTid，谁先到谁发，另一个直接退出。
            if (!claimTid(tid)) {
                AiLog.i("auto-unlock", "已有解锁在进行或已完成，跳过 tid=" + tid);
                return false;
            }

            // 解锁回复本地生成，不依赖 AI：避免模型「拒绝协助」或乱答导致解锁失败
            String text = buildUnlockText(context, d);
            text = cleanup(text);
            if (TextUtils.isEmpty(text)) {
                releaseTid(tid);   // 文案为空，允许下次重试
                return false;
            }
            boolean ok = sendUnlockReply(client, d, html, tid, text);
            if (ok) markHandledTid(tid);
            // 失败时**不**释放认领：sendUnlockReply 返回 false 有可能是「回复已提交
            // 但回读未确认解锁」（风控/审核）。此时若释放，下次进帖会再回一条，
            // 反而造成重复回帖。宁可漏回也不可重发。
            AiLog.i("auto-unlock", "tid=" + tid + " 结果=" + ok + " 回复=" + text);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "unlockSingleThread failed", e);
            return false;
        }
    }

    /**
     * 执行一轮自动回复。必须在后台线程调用，或直接调用本方法（内部自建线程）。
     */
    public static void runOnce(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) MAIN.post(() -> callback.onFinished(0, 0, "已有任务在执行"));
            return;
        }

        new Thread(() -> {
            int replied = 0;
            int skipped = 0;
            StringBuilder detail = new StringBuilder();
            boolean unlockMode = AiConfigManager.isUnlockMode(app);
            AiLog.i("auto-reply", "开始一轮自动回复"
                    + (unlockMode ? "（解锁隐藏内容模式）" : "（评论回复模式）")
                    + (AiConfigManager.isDryRun(app) ? "（演练模式）" : ""));
            try {
                if (unlockMode) {
                    // build61: 解锁模式改为「点开帖才触发」，后台不再自动扫全站。
                    // 用户打开帖子时由 ThreadDetailActivity 调 tryUnlockOnOpen()。
                    AiLog.i("auto-reply", "解锁模式已改为进帖触发，后台跳过本轮扫描");
                    finalize(app, callback, 0, 0, "解锁模式已改为进帖触发(后台不再扫全站)");
                    return;
                }

                List<Job> jobs = collectJobs(app, detail);
                if (jobs.isEmpty()) {
                    finalize(app, callback, 0, 0, detail.length() == 0
                            ? "没有发现需要回复的新评论" : detail.toString());
                    return;
                }

                int maxPerRun = AiConfigManager.getMaxReplyPerRun(app);
                boolean dryRun = AiConfigManager.isDryRun(app);

                for (Job job : jobs) {
                    if (replied >= maxPerRun) {
                        detail.append("；达到单轮上限 ").append(maxPerRun).append(" 条");
                        break;
                    }

                    String text = generateReply(app, job);
                    if (TextUtils.isEmpty(text) || text.contains("[[SKIP]]")) {
                        skipped++;
                        markHandled(job.pid);
                        detail.append("；跳过 ").append(job.pid).append("(模型放弃)");
                        continue;
                    }
                    text = cleanup(text);
                    if (text.length() < AiConfigManager.getMinReplyLength(app)) {
                        skipped++;
                        markHandled(job.pid);
                        detail.append("；跳过 ").append(job.pid).append("(太短)");
                        continue;
                    }

                    if (dryRun) {
                        replied++;
                        markHandled(job.pid);
                        detail.append("；[演练] ").append(text);
                        continue;
                    }

                    boolean ok = sendReply(app, job, text);
                    if (ok) {
                        replied++;
                        markHandled(job.pid);
                        detail.append("；已回复 ").append(job.pid);
                        Log.i(TAG, "replied to " + job.pid + ": " + text);
                    } else {
                        detail.append("；发送失败 ").append(job.pid);
                    }

                    // 控制频率，避免触发风控
                    try { Thread.sleep(3000 + (long) (Math.random() * 4000)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }

                finalize(app, callback, replied, skipped, detail.toString());
            } catch (Exception e) {
                Log.w(TAG, "runOnce failed", e);
                finalize(app, callback, replied, skipped,
                        "异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                RUNNING.set(false);
            }
        }, "auto-reply").start();
    }

    private static void finalize(Context app, Callback cb, int replied, int skipped, String detail) {
        if (!TextUtils.isEmpty(detail)) {
            AiLog.i("auto-reply", "本轮完成：回复 " + replied + " 条，跳过 " + skipped + " 条"
                    + (detail.isEmpty() ? "" : "｜" + detail));
        }
        if (cb == null) return;
        final int r = replied, s = skipped;
        final String d = detail == null ? "" : detail;
        MAIN.post(() -> cb.onFinished(r, s, d));
    }

    // ==================== 解锁模式 ====================

    /**
     * build61: 进帖触发式解锁。
     * 用户打开帖子后由 ThreadDetailActivity 在已加载的页面上判断：
     * 有隐藏内容 && 未解锁 -> 自动回帖解锁 -> 由调用方刷新页面。
     * 全程复用已有的 isLockedHidden / buildUnlockText / sendUnlockReply。
     *
     * @return true=本次确实回帖(或演练)且成功; false=不需要解锁/失败
     */
    public static boolean tryUnlockOnOpen(Context context, PostDetail detail, String pageHtml) {
        if (context == null || detail == null) return false;
        Context app = context.getApplicationContext();
        String tid = detail.getTid();
        if (TextUtils.isEmpty(tid)) return false;
        if (TextUtils.isEmpty(pageHtml)) return false;
        HttpClient client = HttpClient.getInstance();
        if (!client.isLoggedIn()) client.syncFromCookieManager();
        if (!client.isLoggedIn()) {
            AiLog.e("auto-unlock", "进帖解锁：未登录，跳过 tid=" + tid);
            return false;
        }
        if (!detail.isHasHiddenContent()) return false;   // 没有隐藏块，啥也不干
        String hidden = detail.getHiddenContentHtml();
        if (TextUtils.isEmpty(hidden) || !isLockedHidden(hidden)) return false; // 已解锁
        // build61: 原子认领——连开多帖/重复进入同一帖只回一次
        if (!claimTid(tid)) return false;
        String text = buildUnlockText(app, detail);
        if (TextUtils.isEmpty(text)) { releaseTid(tid); return false; }
        boolean dryRun = AiConfigManager.isDryRun(app);
        if (dryRun) {
            AiLog.i("auto-unlock", "进帖解锁(演练) tid=" + tid + " 回复=" + text);
            return true;
        }
        boolean ok = sendUnlockReply(client, detail, pageHtml, tid, text);
        AiLog.i("auto-unlock", "进帖解锁" + (ok ? "成功" : "失败") + " tid=" + tid
                + " 回复=" + text);
        return ok;
    }
    /** build61: 原子认领 tid(已认领返回 false,防并发重复回帖) */
    private static boolean claimTid(String tid) {
        synchronized (HANDLED_TIDS) {
            if (HANDLED_TIDS.contains(tid)) return false;
            HANDLED_TIDS.add(tid);
            return true;
        }
    }
    /** build61: 释放认领(回帖文本为空时允许下次重试) */
    private static void releaseTid(String tid) {
        synchronized (HANDLED_TIDS) { HANDLED_TIDS.remove(tid); }
    }

    /**
     * 「回复可见」门控文案的唯一判定入口。
     *
     * 此前全项目有三份独立实现（AutoReplyEngine / ForumTools / AiSummarizeActivity），
     * 词表各不相同，改一处必然漏两处。现统一到这里，三处共用。
     *
     * 只认真正的门控文案，不把正文里偶然出现的「隐藏内容」字样当成锁定信号，
     * 否则一条已解锁的帖子会被反复判为未解锁、反复回复。
     */
    public static boolean containsGateWord(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String t = html.replaceAll("<[^>]+>", " ");
        return t.contains("如果您要查看") || t.contains("隐藏内容请")
                || t.contains("查看本帖隐藏内容请")
                || t.contains("请回复")
                || t.contains("回复可见") || t.contains("需要回复");
    }

    /**
     * 隐藏内容是否仍未解锁。
     * 注意：空内容视为「锁定」——调用方依赖该语义表示「没拿到隐藏块正文」。
     */
    public static boolean isLockedHidden(String hiddenHtml) {
        if (TextUtils.isEmpty(hiddenHtml)) return true;
        return containsGateWord(hiddenHtml);
    }

    /**
     * 生成一条用于解锁的回复。
     * 本地模板生成，不调用 AI —— 解锁只需要一条普通回帖，
     * 而模型可能因为帖子涉及第三方资源而「拒绝协助」，导致解锁直接失败。
     *
     * 公开给 ForumTools / AiSummarizeActivity 共用，是解锁文案的唯一来源。
     */
    public static String buildUnlockText(Context ctx, PostDetail d) {
        if (d == null) return "";
        String kw = safe(d.getTitle());
        kw = kw.replaceAll("[\\s\\p{Punct}（）【】「」《》，。！？、~·:：]", "");
        if (kw.length() > 10) kw = kw.substring(0, 10);

        // 优先用用户自定义模板：{title} 替换为帖子标题关键词
        String custom = AiConfigManager.getUnlockReplyTemplate(ctx);
        if (!TextUtils.isEmpty(custom)) {
            return custom.replace("{title}", kw);
        }

        String[] pool;
        if (TextUtils.isEmpty(kw)) {
            pool = new String[]{
                    "感谢分享，内容看着不错，回复支持一下",
                    "谢谢分享，正需要这个，先回复看看",
                    "支持一下，感谢分享好资源",
                    "感谢楼主分享，回复支持"
            };
        } else {
            pool = new String[]{
                    "感谢分享「" + kw + "」，正需要这个，回复支持一下",
                    "「" + kw + "」看着不错，谢谢分享，下来试试",
                    "支持「" + kw + "」，感谢分享，先回复看看",
                    "感谢分享「" + kw + "」，正好用得上",
                    "「" + kw + "」不错，感谢分享，先收下了"
            };
        }
        return pool[(int) (Math.random() * pool.length)];
    }

    /** 发出解锁回复，并回读确认 */
    private static boolean sendUnlockReply(HttpClient client, PostDetail d, String pageHtml,
                                           String tid, String message) {
        try {
            String formhash = d.getFormhash();
            if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(pageHtml);
            if (TextUtils.isEmpty(formhash)) {
                AiLog.e("auto-unlock", "拿不到 formhash，无法回复 tid=" + tid);
                return false;
            }

            String fid = safe(d.getForumFid());
            if (TextUtils.isEmpty(fid)) {
                // 兜底：从页面里的版块链接或隐藏块链接里抠 fid
                fid = extractFid(pageHtml);
            }
            if (TextUtils.isEmpty(fid)) {
                AiLog.e("auto-unlock", "拿不到 fid，无法回复 tid=" + tid);
                return false;
            }
            Map<String, String> params = new HashMap<>();
            params.put("formhash", formhash);
            params.put("message", message);
            params.put("replysubmit", "yes");
            params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));
            if (!TextUtils.isEmpty(d.getNoticeauthor())) {
                params.put("noticeauthor", d.getNoticeauthor());
            }

            String url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply"
                    + "&fid=" + fid + "&tid=" + tid
                    + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";
            // 带 Referer：Discuz 回复接口会校验来源页
            String referer = ForumParser.getThreadDetailUrl(tid);

            // 发帖前主动节流，避免撞上「两次发表间隔少于 15 秒」
            waitPostThrottle();

            String result = client.postWithReferer(url, params, referer);
            LAST_POST_AT.set(System.currentTimeMillis());
            AiLog.i("auto-unlock", "回复已提交 tid=" + tid + " fid=" + fid
                    + " 回复内容=" + message
                    + "\n响应=" + AiLog.clip(result, 400));

            // 被防洪限制拦下：等够时间后自动重试一次
            if (isFloodControl(result)) {
                AiLog.i("auto-unlock", "被「发帖间隔」拦截，等待 16 秒后重试 tid=" + tid);
                try { Thread.sleep(16000L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                result = client.postWithReferer(url, params, referer);
                LAST_POST_AT.set(System.currentTimeMillis());
                AiLog.i("auto-unlock", "重试结果 tid=" + tid
                        + " 响应=" + AiLog.clip(result, 300));
            }

            if (TextUtils.isEmpty(result)) {
                AiLog.e("auto-unlock", "回复响应为空 tid=" + tid);
                return false;
            }
            String lower = result.toLowerCase();
            if (isFloodControl(result)) {
                AiLog.e("auto-unlock", "回复被拒：发帖过于频繁 tid=" + tid);
                return false;
            }
            if (lower.contains("请先登录") || lower.contains("没有权限")
                    || lower.contains("非法操作") || lower.contains("回复失败")) {
                AiLog.e("auto-unlock", "回复被拒 tid=" + tid);
                return false;
            }

            // 回读确认解锁
            String html2 = client.get(ForumParser.getThreadDetailUrl(tid)
                    + "&_unlock=" + System.currentTimeMillis());
            if (html2 == null || html2.isEmpty()) {
                boolean ok = lower.contains("成功") || lower.contains("success");
                AiLog.i("auto-unlock", "回读为空，按响应推断结果=" + ok + " tid=" + tid);
                return ok;
            }
            PostDetail d2 = ForumParser.parseThreadDetail(html2);
            if (d2 == null || !d2.isHasHiddenContent()) return true;
            boolean unlocked = !isLockedHidden(d2.getHiddenContentHtml());
            AiLog.i("auto-unlock", "回读解锁状态=" + unlocked + " tid=" + tid);
            return unlocked;
        } catch (Exception e) {
            Log.w(TAG, "sendUnlockReply failed", e);
            AiLog.e("auto-unlock", "发送回复异常 tid=" + tid + " " + e);
            return false;
        }
    }

    /** 从页面 HTML 里抠出版块 fid（隐藏块链接里常带 fid=xx） */
    private static String extractFid(String html) {
        if (TextUtils.isEmpty(html)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("action=reply&(?:amp;)?fid=(\\d+)").matcher(html);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("forum-(\\d+)-1\\.html").matcher(html);
        if (m.find()) return m.group(1);
        return "";
    }

    public static void markHandledTid(String tid) {
        if (TextUtils.isEmpty(tid)) return;
        synchronized (HANDLED_TIDS) {
            HANDLED_TIDS.add(tid);
            if (HANDLED_TIDS.size() > 2000) HANDLED_TIDS.clear();
        }
    }

    // ==================== 收集待回复任务 ====================

    private static List<Job> collectJobs(Context context, StringBuilder detail) throws Exception {
        List<Job> jobs = new ArrayList<>();
        HttpClient client = HttpClient.getInstance();
        if (!client.isLoggedIn()) client.syncFromCookieManager();
        if (!client.isLoggedIn()) {
            detail.append("未登录");
            return jobs;
        }

        String selfUid = UserSessionManager.getInstance().getUid(context);
        if (TextUtils.isEmpty(selfUid)) {
            detail.append("未获取到 UID");
            return jobs;
        }

        // 1. 找到自己的帖子
        List<String> tids = findOwnThreadTids(context, client, selfUid, detail);
        if (tids.isEmpty()) {
            detail.append("未找到自己的帖子");
            return jobs;
        }

        boolean onlyOwn = AiConfigManager.isOnlyReplyOwnThreads(context);
        int scanned = 0;

        for (String tid : tids) {
            if (jobs.size() >= 8) break;              // 单轮最多扫 8 个帖子
            scanned++;
            try {
                // 用倒序拿最新回复
                String url = ForumParser.getThreadDetailUrl(tid, 1, "desc");
                String html = client.get(url);
                if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) continue;

                PostDetail d = ForumParser.parseThreadDetail(html);
                if (d == null || d.getReplies() == null) continue;

                // 若只回复自己帖子，校验楼主是否为本人
                if (onlyOwn && !selfUid.equals(d.getAuthorUid())) continue;

                String body = ForumTools.htmlToText(d.getContentHtml());
                List<ReplyItem> replies = d.getReplies();

                // 倒序遍历，找最新的、非本人的、未处理过的回复
                for (int i = replies.size() - 1; i >= 0; i--) {
                    ReplyItem r = replies.get(i);
                    if (r == null) continue;
                    if (TextUtils.isEmpty(r.getPid())) continue;
                    if (selfUid.equals(r.getAuthorUid())) continue;   // 自己发的不回
                    if (isHandled(r.getPid())) continue;

                    String content = r.getContentText();
                    if (TextUtils.isEmpty(content)) content = ForumTools.stripTags(r.getContentHtml());
                    if (TextUtils.isEmpty(content) || content.trim().length() < 2) continue;

                    Job job = new Job();
                    job.tid = tid;
                    job.title = d.getTitle();
                    job.body = body;
                    job.replyAuthor = r.getAuthor();
                    job.replyContent = content;
                    job.pid = r.getPid();
                    jobs.add(job);

                    if (jobs.size() >= 8) break;
                }
            } catch (Exception e) {
                Log.w(TAG, "scan thread " + tid + " failed", e);
            }
        }

        if (detail.length() > 0) detail.append("；");
        detail.append("扫描 ").append(scanned).append(" 个帖子，待回复 ").append(jobs.size()).append(" 条");
        return jobs;
    }

    /** 取自己的帖子 tid 列表：先用空间页，失败则回退到搜索自己用户名 */
    private static List<String> findOwnThreadTids(Context context, HttpClient client,
                                                   String selfUid, StringBuilder detail) {
        List<String> tids = new ArrayList<>();
        try {
            String url = HttpClient.BASE_URL + "home.php?mod=space&uid=" + selfUid
                    + "&do=thread&view=me&mobile=2";
            String html = client.get(url);
            if (html != null && !html.isEmpty() && !ForumParser.isLoginPage(html)) {
                List<com.solosu.mtforum.model.Thread> list = ForumParser.parseThreadList(html);
                if (list.isEmpty()) list = ForumParser.parseForumThreadList(html);
                for (com.solosu.mtforum.model.Thread t : list) {
                    if (!TextUtils.isEmpty(t.getTid())) tids.add(t.getTid());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findOwnThreadTids failed", e);
        }
        return tids;
    }

    // ==================== 生成回复 ====================

    private static String generateReply(Context context, Job job) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【帖子标题】").append(safe(job.title)).append("\n\n");

        if (!TextUtils.isEmpty(job.body)) {
            String body = job.body.length() > 3000 ? job.body.substring(0, 3000) : job.body;
            prompt.append("【帖子正文】\n").append(body).append("\n\n");
        }

        prompt.append("【用户 ").append(safe(job.replyAuthor)).append(" 的评论】\n")
                .append(safe(job.replyContent)).append("\n\n");
        prompt.append("请针对这条评论写一条回复。");

        return AiClient.simpleChat(context, AiConfigManager.getReplyPrompt(context),
                prompt.toString());
    }

    /** 去掉模型可能带上的包裹符号 */
    private static String cleanup(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 去掉成对引号
        if (t.length() > 1 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("「") && t.endsWith("」"))
                || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        // 去掉「回复：」这类前缀
        t = t.replaceFirst("^(回复|回答|评论)[:：]\\s*", "");
        // 中文内容里的 markdown 粗体星号去掉
        t = t.replace("**", "");
        return t.trim();
    }

    // ==================== 发送回复 ====================

    private static boolean sendReply(Context context, Job job, String message) {
        try {
            HttpClient client = HttpClient.getInstance();

            String detailUrl = ForumParser.getThreadDetailUrl(job.tid);
            String html = client.get(detailUrl);
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) return false;

            PostDetail d = ForumParser.parseThreadDetail(html);
            String formhash = d != null ? d.getFormhash() : null;
            if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html);
            if (TextUtils.isEmpty(formhash)) return false;

            String fid = d != null ? safe(d.getForumFid()) : "";

            Map<String, String> params = new HashMap<>();
            params.put("formhash", formhash);
            params.put("message", message);
            params.put("replysubmit", "yes");
            params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));

            // 作为对该评论的回复，带上引用信息
            if (!TextUtils.isEmpty(job.pid)) {
                params.put("reppid", job.pid);
                params.put("reppost", job.pid);
                params.put("addfeed", "1");
                params.put("noticeauthormsg", message);
            }

            String url = HttpClient.BASE_URL + "forum.php?mod=post&action=reply"
                    + "&fid=" + fid + "&tid=" + job.tid
                    + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";

            String result = client.post(url, params);
            if (TextUtils.isEmpty(result)) return false;
            String lower = result.toLowerCase();
            if (lower.contains("请先登录") || lower.contains("没有权限")
                    || lower.contains("非法操作") || lower.contains("回复失败")) {
                return false;
            }
            boolean responseReportsSuccess = lower.contains("回复成功")
                    || lower.contains("succeedhandle") || lower.contains("showmessage")
                    || lower.contains("success");
            // 服务端偶尔返回模糊提示，这里按原帖回复的方式回读核对一遍
            if (verifyPublished(context, job.tid, message)) return true;
            return responseReportsSuccess;
        } catch (Exception e) {
            Log.w(TAG, "sendReply failed", e);
            return false;
        }
    }

    /** 回读最新回复列表，确认自己的 UID 下确实出现了这条内容 */
    private static boolean verifyPublished(Context context, String tid, String message) {
        try {
            String currentUid = UserSessionManager.getInstance().getUid(context);
            if (TextUtils.isEmpty(currentUid) || TextUtils.isEmpty(message)) return false;
            String expected = normalizeReplyText(message);
            if (expected.length() > 20) expected = expected.substring(0, 20);
            if (expected.isEmpty()) return false;

            HttpClient client = HttpClient.getInstance();
            for (int page = 1; page <= 2; page++) {
                String url = ForumParser.getThreadDetailUrl(tid, page, "desc")
                        + "&_reply_check=" + System.currentTimeMillis();
                PostDetail latest = ForumParser.parseThreadDetail(client.get(url));
                if (latest == null || latest.getReplies() == null) continue;
                List<ReplyItem> replies = latest.getReplies();
                for (int k = replies.size() - 1; k >= 0; k--) {
                    ReplyItem item = replies.get(k);
                    if (item == null) continue;
                    if (!currentUid.equals(item.getAuthorUid())) continue;
                    if (normalizeReplyText(item.getContentText()).contains(expected)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "verifyPublished failed", e);
        }
        return false;
    }

    private static String normalizeReplyText(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replaceAll("(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]", "")
                .replaceAll("\\s+", " ").trim();
    }

    // ==================== 历史记录 ====================

    private static boolean isHandled(String pid) {
        synchronized (HANDLED_PIDS) {
            return HANDLED_PIDS.contains(pid);
        }
    }

    private static void markHandled(String pid) {
        if (TextUtils.isEmpty(pid)) return;
        synchronized (HANDLED_PIDS) {
            HANDLED_PIDS.add(pid);
            if (HANDLED_PIDS.size() > 2000) {
                HANDLED_PIDS.clear();   // 简单防涨，实际场景够用
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}