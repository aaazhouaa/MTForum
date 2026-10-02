package com.solosu.mtforum.ai;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 会话列表页（Codex 风格）。
 *
 * 列出全部会话，支持新建 / 点击续聊 / 长按删除。
 * 布局代码化构建，不依赖新增 XML 资源。
 */
public class AiSessionListActivity extends AppCompatActivity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        sv.addView(root);
        setContentView(sv);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#FFFFFF"));
        bar.setElevation(dp(2));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(Color.parseColor("#1C1E21"));
        back.setPadding(dp(12), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("历史会话");
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#1C1E21"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tlp);

        TextView newBtn = new TextView(this);
        newBtn.setText("新建会话");
        newBtn.setTextSize(14);
        newBtn.setTextColor(Color.parseColor("#1A73E8"));
        newBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        newBtn.setOnClickListener(v -> {
            String id = AiSessionStore.createSession(this);
            openChat(id, true);
        });
        bar.addView(newBtn);

        root.addView(bar);

        // 列表容器
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(16));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<AiSessionStore.Session> sessions = AiSessionStore.listSessions(this);
        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有会话。点右上角「新建会话」开始。\n也可以在聊天页里直接开聊，会自动保存。");
            empty.setTextSize(14);
            empty.setTextColor(Color.parseColor("#9CA3AF"));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(40), dp(24), dp(40));
            list.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (AiSessionStore.Session s : sessions) {
            list.addView(buildItem(s, fmt));
        }
    }

    private View buildItem(AiSessionStore.Session s, SimpleDateFormat fmt) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#FFFFFF"));
        card.setElevation(dp(1));
        int pad = dp(14);
        card.setPadding(pad, dp(12), pad, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        // 标题行
        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(s.title) ? "新会话" : s.title);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1C1E21"));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        // 副信息行：消息数 + 时间
        TextView meta = new TextView(this);
        meta.setText(s.messageCount + " 条消息 · " + fmt.format(new Date(s.updatedAt)));
        meta.setTextSize(12);
        meta.setTextColor(Color.parseColor("#9CA3AF"));
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        card.setOnClickListener(v -> openChat(s.id, false));
        card.setOnLongClickListener(v -> {
            confirmDelete(s);
            return true;
        });
        return card;
    }

    private void confirmDelete(final AiSessionStore.Session s) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("删除会话");
        b.setMessage("删除「" + s.title + "」？对话将无法恢复。");
        b.setNegativeButton("取消", null);
        b.setPositiveButton("删除", (d, w) -> {
            AiSessionStore.deleteSession(this, s.id);
            toast("已删除");
            refresh();
        });
        b.show();
    }

    private void openChat(String id, boolean newSession) {
        Intent it = new Intent(this, AiChatActivity.class);
        it.putExtra("session_id", id);
        it.putExtra("session_new", newSession);
        startActivity(it);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
