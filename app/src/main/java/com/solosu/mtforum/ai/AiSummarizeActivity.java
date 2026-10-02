package com.solosu.mtforum.ai;

import android.graphics.Color;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.solosu.mtforum.R;
import org.json.JSONObject;

/**
 * AI 一键总结页：帖子内容 + 评论区 → AI 总结。
 *
 * 流程：
 *  1. 拉取帖子详情（含正文与隐藏内容）
 *  2. 检测隐藏内容是否处于锁定态：
 *     - 锁定 → 用用户自定义/内置固定模板回帖解锁 → 回读确认 → 拉全内容
 *     - 已解锁/无隐藏 → 直接用
 *  3. 拉取评论（最多 80 条，预算内）
 *  4. 组包交给 AI 总结（simpleChat，无工具调用，纯一次生成）
 */
public class AiSummarizeActivity extends AppCompatActivity {

    private String tid;
    private String title;
    private TextView tvStatus;
    private TextView tvSummary;
    private ScrollView scroll;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tid = getIntent().getStringExtra("tid");
        title = getIntent().getStringExtra("title");
        buildUi();
        if (TextUtils.isEmpty(tid)) {
            tvStatus.setText("缺少帖子 ID，无法总结");
            return;
        }
        startSummarize();
    }

    /** 纯代码 UI：顶栏（返回+标题）+ 状态行 + 总结内容区 */
    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F7F8FA"));

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView btnBack = new TextView(this);
        btnBack.setText("←");
        btnBack.setTextColor(0xFF202124);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), 0, dp(8), 0);
        btnBack.setOnClickListener(v -> finish());
        bar.addView(btnBack);
        TextView tvTitle = new TextView(this);
        tvTitle.setText(TextUtils.isEmpty(title) ? "AI 总结" : title);
        tvTitle.setTextColor(0xFF202124);
        tvTitle.setTextSize(16);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(tvTitle, tp);
        // build70: 复制按钮
        TextView btnCopy = new TextView(this);
        btnCopy.setText("复制");
        btnCopy.setTextColor(0xFF1A73E8);
        btnCopy.setTextSize(14);
        btnCopy.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnCopy.setOnClickListener(v -> copySummary());
        bar.addView(btnCopy);
        root.addView(bar);

        // 状态行（显示进行中的步骤）
        tvStatus = new TextView(this);
        tvStatus.setText("正在拉取帖子…");
        tvStatus.setTextColor(0xFFD81B60);
        tvStatus.setTextSize(13);
        tvStatus.setPadding(dp(16), dp(6), dp(16), dp(6));
        root.addView(tvStatus);

        // 总结内容区
        scroll = new ScrollView(this);
        LinearLayout contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(dp(16), dp(4), dp(16), dp(24));
        tvSummary = new TextView(this);
        tvSummary.setText("");
        tvSummary.setTextIsSelectable(true);
        tvSummary.setTextColor(0xFF333333);
        tvSummary.setTextSize(15);
        tvSummary.setLineSpacing(dp(2), 1f);
        contentBox.addView(tvSummary);
        scroll.addView(contentBox);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp);

        setContentView(root);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void postStatus(final String s) {
        runOnUiThread(() -> tvStatus.setText(s));
    }

    private void appendSummary(final String s) {
        runOnUiThread(() -> {
            tvSummary.append(s);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void startSummarize() {
        new java.lang.Thread(() -> {
            try {
                doSummarize();
            } catch (Exception e) {
                postStatus("总结失败：" + e.getClass().getSimpleName());
                com.solosu.mtforum.ai.AiLog.e("ai-summarize", "failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "ai-summarize").start();
    }

    private void doSummarize() throws Exception {
        org.json.JSONObject args = new org.json.JSONObject();
        args.put("tid", tid);
        args.put("fetch_all", true);
        args.put("max_replies", 80);
        // 第 1 步：拉帖（若隐藏内容已解锁，返回体里已带 hidden_content）
        postStatus("正在拉取帖子…");
        String raw = ForumTools.execute(this, "get_thread", args);
        JSONObject thread = new JSONObject(raw);
        if (!thread.optBoolean("success", true) && thread.has("error")) {
            postStatus("拉取失败：" + thread.optString("error", "未知错误"));
            return;
        }
        String content = thread.optString("content", "");
        String hidden = thread.optString("hidden_content", "");
        boolean hasHidden = thread.has("hidden_content");
        // 第 2 步：隐藏内容锁定检测（复用统一门控判定）
        boolean hiddenLocked = false;
        if (hasHidden && !TextUtils.isEmpty(hidden)
                && AutoReplyEngine.containsGateWord(hidden)) {
            hiddenLocked = true;
        }
        if (!hasHidden && AutoReplyEngine.containsGateWord(content)) {
            hiddenLocked = true;
        }
        // 第 3 步：锁定则回帖解锁（unlock_hidden 内部用用户自定义模板或内置本地模板，不调 AI）
        String hiddenResolved = hidden;
        if (hiddenLocked) {
            postStatus("检测到隐藏内容，正在回帖解锁…");
            org.json.JSONObject unlockArgs = new org.json.JSONObject();
            unlockArgs.put("tid", tid);
            // 不传 message:让 unlockHidden 走用户自定义模板(AiConfigManager)或内置本地模板
            String unlockRaw = ForumTools.execute(this, "unlock_hidden", unlockArgs);
            JSONObject unlockResult = new JSONObject(unlockRaw);
            com.solosu.mtforum.ai.AiLog.i("ai-summarize", "unlock_hidden -> " + unlockRaw);
            if (unlockResult.optBoolean("success") && unlockResult.has("content")) {
                hiddenResolved = unlockResult.optString("content", "");
                postStatus("解锁成功，正在整理…");
            } else if (unlockResult.optBoolean("hidden", false) == false) {
                // 无隐藏内容的分支
                postStatus("继续总结…");
            } else {
                postStatus("回复已发出但未能确认解锁，按已获取内容总结");
            }
            // 解锁或回复后重新拉一次，确保拿到最新正文（可能解锁改变正文可见性）
            try {
                String raw2 = ForumTools.execute(this, "get_thread", args);
                JSONObject thread2 = new JSONObject(raw2);
                String h2 = thread2.optString("hidden_content", "");
                if (!TextUtils.isEmpty(h2) && !AutoReplyEngine.isLockedHidden(h2)) {
                    hiddenResolved = h2;
                }
            } catch (Exception ignore) { }
        }
        // 第 4 步：组包交给 AI
        postStatus("正在 AI 总结（内容+评论区）…");
        StringBuilder prompt = new StringBuilder();
        prompt.append("【帖子标题】").append(title == null ? safe(thread.optString("title")) : title).append("\n\n");
        prompt.append("【帖子正文】\n").append(clip(content, 6000));
        if (!TextUtils.isEmpty(hiddenResolved)) {
            prompt.append("\n\n【隐藏内容(已解锁)】\n").append(clip(hiddenResolved, 3000));
        }
        // 评论:直接用第 1 步 get_thread 返回的 replies 数组
        String repliesStr = thread.optString("replies", "[]");
        org.json.JSONArray replies = new org.json.JSONArray(repliesStr);
        if (replies.length() > 0) {
            prompt.append("\n\n【评论区(").append(replies.length()).append("条)");
            int budget = 12000;
            for (int i = 0; i < replies.length(); i++) {
                JSONObject r = replies.optJSONObject(i);
                if (r == null) continue;
                String line = r.optString("author", "?") + ": " + r.optString("content", "").trim();
                if (line.length() > 300) line = line.substring(0, 300) + "…";
                if (budget - line.length() < 0) break;
                budget -= line.length();
                prompt.append("\n").append("-").append(line);
            }
            prompt.append("\n");
        }
        String sys = "你是论坛帖子总结助手。基于给定的帖子正文与评论区，用中文写一份结构化总结，格式：\n"
                + "【一句话总结】…\n【要点】- …\n【隐藏内容】…(如有)\n【评论区氛围/高赞观点】…\n"
                + "只依据给定材料，不要编造；材料不足的部分直接说明。";
        String result = com.solosu.mtforum.ai.AiClient.simpleChat(this, sys, prompt.toString());
        if (TextUtils.isEmpty(result)) {
            postStatus("AI 总结返回为空，稍后重试");
            return;
        }
        // 第 5 步：显示（整段替换状态行）
        postStatus("总结完成");
        appendSummary(result.trim());
    }

    /** build70: 复制总结全文到剪贴板 */
    private void copySummary() {
        String txt = tvSummary.getText() == null ? "" : tvSummary.getText().toString();
        if (TextUtils.isEmpty(txt.trim())) {
            Toast.makeText(this, "暂无内容可复制", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AI总结", txt));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private String safe(String s) { return s == null ? "" : s; }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** build70: 从 AI 输出中剥离【小节】标题与 Markdown 记号, 得到可直接发帖的纯文本 */
    public static String extractText(String ai) {
        if (ai == null) return "";
        String t = ai.trim();
        t = t.replaceAll("【[^】]{1,30}】", "");
        t = t.replaceAll("(?m)^#{1,6}\\s*", "");
        t = t.replaceAll("\\*\\*?", "");
        t = t.replaceAll("`{1,3}", "");
        t = t.replaceAll("(?m)^\\s*[-*·]\\s+", "");
        t = t.replaceAll("\\n{3,}", "\\n\\n");
        return t.trim();
    }
}
