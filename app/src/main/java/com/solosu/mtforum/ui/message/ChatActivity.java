package com.solosu.mtforum.ui.message;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ChatMessageAdapter;
import com.solosu.mtforum.model.ChatMessage;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.DiscuzUserActionManager;
import com.solosu.mtforum.session.UserSessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原生私信聊天页。网络回调必须服从 Activity 生命周期，避免返回后继续更新已销毁页面。 */
public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_PMID = "pmid";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_AVATAR = "avatar";

    private String pmid, uid, name, avatar;
    private ChatMessageAdapter adapter;
    private RecyclerView recycler;
    private ProgressBar progress;
    private EditText input;
    private TextView status;
    private final AtomicBoolean loading = new AtomicBoolean(false);
    /** 已发送但服务端列表尚未返回的消息，避免刷新时短暂消失。仅在主线程访问。 */
    private final List<ChatMessage> pendingMessages = new ArrayList<>();
    private volatile boolean reloadAfterSend = false;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        pmid = getIntent().getStringExtra(EXTRA_PMID);
        uid = getIntent().getStringExtra(EXTRA_UID);
        name = getIntent().getStringExtra(EXTRA_NAME);
        avatar = getIntent().getStringExtra(EXTRA_AVATAR);

        TextView title = findViewById(R.id.tv_chat_title);
        status = findViewById(R.id.tv_chat_status);
        TextView back = findViewById(R.id.tv_chat_back);
        recycler = findViewById(R.id.chat_recycler);
        progress = findViewById(R.id.chat_progress);
        input = findViewById(R.id.chat_input);
        TextView send = findViewById(R.id.chat_send);

        title.setText(TextUtils.isEmpty(name) ? "消息" : name);
        status.setText("");
        back.setOnClickListener(v -> finish());

        adapter = new ChatMessageAdapter(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        send.setOnClickListener(v -> sendMessage());
        loadMessages();
    }

    private boolean canUpdateUi() {
        return !destroyed && !isFinishing()
                && (android.os.Build.VERSION.SDK_INT < 17 || !isDestroyed());
    }

    private void loadMessages() {
        if (TextUtils.isEmpty(pmid)) {
            Toast.makeText(this, "缺少会话ID", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            // 首次加载或其他刷新尚未完成，发送成功后必须在其结束时补刷新。
            reloadAfterSend = true;
            return;
        }
        reloadAfterSend = false;
        if (canUpdateUi()) progress.setVisibility(View.VISIBLE);

        final String currentUid = UserSessionManager.getInstance()
                .getUid(getApplicationContext());
        new Thread(() -> {
            try {
                HttpClient client = HttpClient.getInstance();
                client.syncFromCookieManager();
                String url = HttpClient.BASE_URL
                        + "home.php?mod=space&do=pm&subop=view&touid=" + pmid
                        + "&mobile=2&_ts=" + System.currentTimeMillis();
                String html = client.get(url);
                List<ChatMessage> messages = ForumParser.parseChatMessages(
                        html, currentUid, avatar);
                String parsedStatus = ForumParser.parseChatOnlineStatus(html);
                final String onlineText = parsedStatus == null ? "" : parsedStatus;

                runOnUiThread(() -> {
                    loading.set(false);
                    if (!canUpdateUi()) return;
                    progress.setVisibility(View.GONE);
                    status.setText(onlineText);
                    status.setTextColor(onlineText.contains("在线")
                            ? 0xFF35A853 : getColor(R.color.text_hint));
                    List<ChatMessage> visibleMessages = mergePendingMessages(messages);
                    adapter.setItems(visibleMessages);
                    if (visibleMessages != null && !visibleMessages.isEmpty()) {
                        recycler.scrollToPosition(visibleMessages.size() - 1);
                    }
                    // 发送请求可能与首次加载并发：如果发送完成时首次加载仍在进行，
                    // loadMessages() 会被 loading 标记拦截。这里在本次加载结束后补做一次刷新，
                    // 确保刚发送的消息不会因为竞态而只能在重新进入页面后出现。
                    if (reloadAfterSend) {
                        reloadAfterSend = false;
                        recycler.postDelayed(this::loadMessages, 120);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.set(false);
                    if (!canUpdateUi()) return;
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "聊天记录加载失败", Toast.LENGTH_SHORT).show();
                });
            }
        }, "chat-load").start();
    }

    private List<ChatMessage> mergePendingMessages(List<ChatMessage> messages) {
        List<ChatMessage> merged = new ArrayList<>();
        if (messages != null) merged.addAll(messages);

        for (int i = 0; i < pendingMessages.size(); ) {
            ChatMessage pending = pendingMessages.get(i);
            boolean alreadyLoaded = false;
            for (ChatMessage loaded : merged) {
                if (loaded != null && loaded.isOutgoing()
                        && TextUtils.equals(loaded.getContent(), pending.getContent())) {
                    alreadyLoaded = true;
                    break;
                }
            }
            if (alreadyLoaded) {
                pendingMessages.remove(i);
            } else {
                merged.add(pending);
                i++;
            }
        }
        return merged;
    }

    private ChatMessage createPendingMessage(String text) {
        Date now = new Date();
        ChatMessage message = new ChatMessage();
        message.setContent(text);
        message.setAvatarUrl(avatar);
        message.setAuthorUid(UserSessionManager.getInstance().getUid(getApplicationContext()));
        message.setDate(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now));
        message.setTime(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(now));
        message.setOutgoing(true);
        return message;
    }

    private void showPendingMessage(String text) {
        ChatMessage pending = createPendingMessage(text);
        pendingMessages.add(pending);
        // 只追加当前消息，不覆盖已经显示的历史消息。
        adapter.addItem(pending);
        recycler.post(() -> recycler.scrollToPosition(Math.max(0, adapter.getItemCount() - 1)));
    }

    private void sendMessage() {
        if (!canUpdateUi()) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (TextUtils.isEmpty(uid)) {
            Toast.makeText(this, "缺少对方UID，无法发送", Toast.LENGTH_SHORT).show();
            return;
        }

        input.setEnabled(false);
        new Thread(() -> {
            boolean success = DiscuzUserActionManager.sendPrivateMessage(
                    getApplicationContext(), uid, "", text);
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                input.setEnabled(true);
                if (success) {
                    input.setText("");
                    // 先把消息立即加入本地列表，避免等待服务端页面刷新期间界面没有任何反馈。
                    showPendingMessage(text);
                    // 随后从服务器重新拉取，服务端返回后会自动移除对应的临时消息，
                    // 防止同一条消息重复显示。
                    loadMessages();
                } else {
                    Toast.makeText(this, "发送失败，请检查登录状态",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "chat-send").start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loading.set(false);
        if (recycler != null) recycler.setAdapter(null);
        adapter = null;
        super.onDestroy();
    }
}