package com.solosu.mtforum.ui.message;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.MessageAdapter;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.network.NoticeBadgeManager;
import com.solosu.mtforum.session.DiscuzUserActionManager;

import java.util.List;
import com.solosu.mtforum.ui.widget.DialogHelper;

/**
 * 消息详情页（原生 RecyclerView 列表）
 * 支持6个分类：我的消息(pm)、我的粉丝(follower)、我的帖子(mypost)、
 * 坛友互动(interactive)、系统提醒(system)、应用提醒(app)
 * 支持删除和屏蔽操作
 */
public class NoticeDetailActivity extends AppCompatActivity {

    public static final String EXTRA_VIEW_TYPE = "view_type";
    public static final String EXTRA_TITLE = "title";
    public static final String RESULT_CLEARED = "cleared";
    public static final String RESULT_VIEW_TYPE = "result_view_type";

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private ImageView ivBack;
    private TextView tvTitle, tvEmptyHint;
    private MessageAdapter adapter;
    private String viewType;
    private String cachedFormhash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice_detail);

        String url = getIntent().getStringExtra("url");
        viewType = getIntent().getStringExtra(EXTRA_VIEW_TYPE);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        tvTitle = findViewById(R.id.tv_title);
        ivBack = findViewById(R.id.iv_back);
        progressBar = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);
        tvEmptyHint = findViewById(R.id.tv_empty_hint);

        if (title != null) tvTitle.setText(title);

        // 返回按钮
        ivBack.setOnClickListener(v -> {
            setResult(RESULT_OK, getIntent().putExtra(RESULT_CLEARED, true)
                    .putExtra(RESULT_VIEW_TYPE, viewType != null ? viewType : ""));
            finish();
        });

        // 设置 RecyclerView
        adapter = new MessageAdapter(this);
        adapter.setOnItemClickListener((message, position) -> {
            // 私信必须优先进入原生聊天页，不能因为摘要中偶然包含 tid= 而跳到帖子页。
            if ("pm".equals(viewType)) {
                String pmid = message.getPmid();
                if (pmid != null && !pmid.isEmpty()) {
                    Intent chatIntent = new Intent(this, ChatActivity.class);
                    chatIntent.putExtra(ChatActivity.EXTRA_PMID, pmid);
                    chatIntent.putExtra(ChatActivity.EXTRA_UID, message.getAuthorUid());
                    chatIntent.putExtra(ChatActivity.EXTRA_NAME, message.getAuthor());
                    chatIntent.putExtra(ChatActivity.EXTRA_AVATAR, message.getAvatarUrl());
                    startActivity(chatIntent);
                } else {
                    Toast.makeText(this, "无法打开私信：缺少会话ID", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            String summary = message.getSummary();
            if (summary != null && !summary.isEmpty()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("tid=(\\d+)").matcher(summary);
                if (m.find()) {
                    Intent intent = new Intent(this, com.solosu.mtforum.ui.detail.ThreadDetailActivity.class);
                    intent.putExtra("tid", m.group(1));
                    startActivity(intent);
                }
            }
        });

        // ★ 删除/屏蔽按钮回调
        adapter.setOnActionClickListener(new MessageAdapter.OnActionClickListener() {
            @Override
            public void onDelete(Message message, int position) {
                showDeleteConfirmDialog(message, position);
            }

            @Override
            public void onBlock(Message message, int position) {
                showBlockConfirmDialog(message, position);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 加载数据
        if (url != null) {
            loadData(url);
        } else {
            showEmpty("参数错误");
        }
    }

    private void loadData(String url) {
        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        tvEmptyHint.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                HttpClient httpClient = HttpClient.getInstance();
                httpClient.syncFromCookieManager();

                String html;
                // 统一使用移动端页面请求:私信(fm)/粉丝/通知(我的帖子/互动/系统/应用)
                // 移动版 li.b_b.bg_f.cl 结构可提取帖子 tid,实现整行点击跳转帖子
                html = httpClient.get(url);

                // 检测是否为登录页
                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        showEmpty("登录已过期，请重新登录");
                    });
                    return;
                }

                // 缓存 formhash（后续删除/屏蔽需要）
                cachedFormhash = ForumParser.parseFormhash(html);

                List<Message> items;
                if ("pm".equals(viewType)) {
                    items = ForumParser.parsePmList(html);
                } else if ("follower".equals(viewType)) {
                    items = ForumParser.parseFollowerList(html);
                } else {
                    items = ForumParser.parseNoticeList(html);
                }

                List<Message> finalItems = items;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    // 详情页已成功加载当前分类内容，确认该分类已查看。
                    NoticeBadgeManager.markViewed(this, viewType);
                    if (finalItems != null && !finalItems.isEmpty()) {
                        adapter.setMessageList(finalItems);
                        recyclerView.setVisibility(View.VISIBLE);
                        tvEmptyHint.setVisibility(View.GONE);
                    } else {
                        showEmpty("暂无消息内容");
                    }
                });
            } catch (Exception e) {
                com.solosu.mtforum.util.CrashHandler.saveErrorLog(
                        "NoticeDetail", "加载通知列表失败: " + url, e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showEmpty("加载失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showEmpty(String hint) {
        recyclerView.setVisibility(View.GONE);
        tvEmptyHint.setVisibility(View.VISIBLE);
        tvEmptyHint.setText(hint);
    }

    // ========== 删除功能 ==========

    private void showDeleteConfirmDialog(Message message, int position) {
        android.app.Dialog alertDialog1 = new AlertDialog.Builder(this)
                .setTitle("删除提醒")
                .setMessage("确定要删除这条提醒吗？")
                .setPositiveButton("删除", (dialog, which) -> executeDelete(message, position))
                .show();
                DialogHelper.applyToAlertDialog(alertDialog1, this);
    }

    private void executeDelete(Message message, int position) {
        String uid = message.getAuthorUid();
        if ("pm".equals(viewType)) {
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(this, "无法删除：缺少会话用户ID", Toast.LENGTH_SHORT).show();
                return;
            }

            progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                boolean success = DiscuzUserActionManager.deletePrivateMessage(
                        this, uid, cachedFormhash, message.getDeleteUrl());
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (success) {
                        adapter.removeItem(position);
                        Toast.makeText(NoticeDetailActivity.this, "删除成功",
                                Toast.LENGTH_SHORT).show();
                        if (adapter.getItemCount() == 0) showEmpty("暂无消息内容");
                    } else {
                        Toast.makeText(NoticeDetailActivity.this,
                                "删除失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
            return;
        }

        executeNoticeDelete(message, position);
    }

    /** 非私信通知仍使用 Discuz! 的 delnotice 接口。 */
    private void executeNoticeDelete(Message message, int position) {
        String noticeId = message.getPmid();
        if (TextUtils.isEmpty(noticeId)) {
            Toast.makeText(this, "无法删除：缺少通知ID", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                HttpClient httpClient = HttpClient.getInstance();
                httpClient.syncFromCookieManager();
                String deleteUrl = HttpClient.BASE_URL
                        + "home.php?mod=misc&ac=ajax&op=delnotice&inajax=1&id=" + noticeId;
                if (!TextUtils.isEmpty(cachedFormhash)) {
                    deleteUrl += "&formhash=" + cachedFormhash;
                }
                String result = httpClient.get(deleteUrl);
                boolean success = result != null
                        && (result.contains("succeed") || result.contains("成功"));
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (success) {
                        adapter.removeItem(position);
                        Toast.makeText(NoticeDetailActivity.this, "删除成功",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(NoticeDetailActivity.this, "删除失败",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(NoticeDetailActivity.this,
                            "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ========== 屏蔽功能 ==========

    private void showBlockConfirmDialog(Message message, int position) {
        String authorName = message.getAuthor() != null ? message.getAuthor() : "该用户";
        android.app.Dialog alertDialog2 = new AlertDialog.Builder(this)
                .setTitle("屏蔽用户")
                .setMessage("确定要屏蔽 " + authorName + " 吗？屏蔽后将不再收到该用户的通知。")
                .setPositiveButton("屏蔽", (dialog, which) -> executeBlock(message, position))
                .show();
        DialogHelper.applyToAlertDialog(alertDialog2, this);
    }

    private void executeBlock(Message message, int position) {
        String uid = message.getAuthorUid();
        if (uid == null || uid.isEmpty()) {
            Toast.makeText(this, "无法屏蔽：缺少用户信息", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                HttpClient httpClient = HttpClient.getInstance();
                httpClient.syncFromCookieManager();

                // Discuz! 屏蔽用户通知接口
                String blockUrl = HttpClient.BASE_URL
                        + "home.php?mod=spacecp&ac=common&op=ignore&authorid=" + uid
                        + "&type=post&handlekey=noticeignore";
                if (cachedFormhash != null && !cachedFormhash.isEmpty()) {
                    blockUrl += "&formhash=" + cachedFormhash;
                }

                String result = httpClient.get(blockUrl);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (result != null && !result.contains("error")) {
                        adapter.removeItem(position);
                        Toast.makeText(NoticeDetailActivity.this, "已屏蔽该用户", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(NoticeDetailActivity.this, "屏蔽失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(NoticeDetailActivity.this, "屏蔽失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}