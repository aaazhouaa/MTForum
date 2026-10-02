package com.solosu.mtforum.ui.detail;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import androidx.annotation.NonNull;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.graphics.Typeface;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ThreadDetailActivityBinding;
import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.detail.ReplyAdapter;
import com.solosu.mtforum.ui.message.ChatActivity;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.login.LoginBottomSheet;
import com.solosu.mtforum.util.BBCodeUtil;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/* JADX INFO: loaded from: classes10.dex */
public class ThreadDetailActivity extends AppCompatActivity {
    private static final String HIDDEN_QUOTE_PLACEHOLDER = "\ue000\ue001\ue002\ue003";
    private static final String KEY_FAVORITED_PREFIX = "fav_";
    private static final String KEY_LIKED_PREFIX = "liked_";
    private static final String PREF_LIKE_FAV = "thread_like_fav_state";
    private ThreadDetailActivityBinding binding;
    private HttpClient httpClient;
    private BottomSheetDialog mBottomSheetDialog;
    private PostDetail postDetail;
    private ReplyAdapter replyAdapter;
    private String tid;
    private boolean onlyOpReplies = false;
    private boolean repliesDescending = true;
    private List<ReplyItem> displayedReplies = new ArrayList();
    private boolean isLiked = false;
    private int likeCount = 0;
    private LikeUsersAdapter likeUsersAdapter;
    private boolean isFavorited = false;
    private int favoriteCount = 0;   // 真实收藏数(来自 ForumParser #comiis_favorite_a)
    private String currentReplyTarget = "";
    private boolean isLoadingMore = false;
    private String currentReplyPid = "";
    private String currentLoginUid = null; // build73: 当前登录 uid(判定是否本人)
    // build74b: 打赏目标(空=主楼;有值=评论楼层)
    private String rewardTargetPid = "";
    private String rewardTargetName = "";
    private String rewardTargetAvatar = "";
    private Uri pendingImageUri = null;
    private final List<String> pendingUploadAids = new ArrayList();
    private boolean imageUploadInProgress = false;
    // build71: 多选图片排队,避免上一张上传中时后续图片被静默丢弃
    private final java.util.List<android.net.Uri> imageUploadPendingQueue = new java.util.ArrayList<>();
    private final java.util.List<android.net.Uri> pendingImageUris = new java.util.ArrayList<>();
    private final java.util.Map<android.net.Uri, String> uploadedAidMap = new java.util.HashMap<>();
    private static final int REQUEST_IMAGE_PICK = 1002;
    private static final int REQUEST_EDIT_THREAD = 1003; // build73: 编辑帖子

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = ThreadDetailActivityBinding.inflate(getLayoutInflater());
        setContentView(this.binding.getRoot());
        this.httpClient = HttpClient.getInstance();
        this.tid = getIntent().getStringExtra("tid");
        if (this.tid == null) {
            finish();
            return;
        }
        this.binding.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$0(view);
            }
        });
        this.binding.swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override // androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
            public final void onRefresh() {
                ThreadDetailActivity.this.refreshPostDetail();
            }
        });
        setupRecyclerView();
        this.binding.etReply.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$1(view);
            }
        });
        this.binding.etReply.setFocusable(false);
        this.binding.etReply.setCursorVisible(false);
        this.binding.btnComments.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$3(view);
            }
        });
        ImageButton btnPickImageInline = findViewById(R.id.btn_pick_image_inline);
        if (btnPickImageInline != null) {
            btnPickImageInline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        }
        this.binding.btnLike.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$4(view);
            }
        });
        this.binding.btnFavorite.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$5(view);
            }
        });
        this.binding.btnShare.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$6(view);
            }
        });
        this.binding.btnViewHidden.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$7(view);
            }
        });
        this.binding.btnLoadMore.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$8(view);
            }
        });
        this.binding.nestedScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override // android.view.View.OnScrollChangeListener
            public final void onScrollChange(View view, int i, int i2, int i3, int i4) {
                ThreadDetailActivity.this.lambda$onCreate$9(view, i, i2, i3, i4);
            }
        });
                this.binding.btnOnlyOp.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$11(view);
            }
        });
        this.binding.btnReplyOrder.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$12(view);
            }
        });
        this.binding.btnReward.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$13(view);
            }
        });
        this.binding.btnKick.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$14(view);
            }
        });
        // build64: AI 总结(详情页入口,与列表卡片行为一致)
        // 注意: 本文件是反编译产物,手写 lambda$onCreate$N 合成方法名,
        // 新代码不能用 lambda(会撞名),必须用匿名内部类
        this.binding.btnAiSummary.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                Intent it = new Intent(ThreadDetailActivity.this,
                        com.solosu.mtforum.ai.AiSummarizeActivity.class);
                it.putExtra("tid", ThreadDetailActivity.this.tid);
                it.putExtra("title", ThreadDetailActivity.this.binding.tvThreadTitle.getText() != null
                        ? ThreadDetailActivity.this.binding.tvThreadTitle.getText().toString() : "");
                startActivity(it);
            }
        });
        loadPostDetail();
    }

    private void lambda$onCreate$0(View v) {
        finish();
    }

    private void lambda$onCreate$1(View v) {
        showReplyBottomSheet(this.currentReplyTarget);
    }

    private void lambda$onCreate$3(View v) {
        this.binding.recyclerReplies.setVisibility(0);
        this.binding.nestedScroll.post(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$onCreate$2();
            }
        });
    }

    private void lambda$onCreate$2() {
        this.binding.nestedScroll.smoothScrollTo(0, this.binding.recyclerReplies.getTop());
    }

    private void lambda$onCreate$4(View v) {
        toggleLike();
    }

    private void lambda$onCreate$5(View v) {
        toggleFavorite();
    }

    private void lambda$onCreate$6(View v) {
        shareThread();
    }

    private void lambda$onCreate$7(View v) {
        viewHiddenContent();
    }

    private void lambda$onCreate$8(View v) {
        loadMoreReplies();
    }

    private void lambda$onCreate$9(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
        NestedScrollView scrollView = (NestedScrollView) v;
        View child = scrollView.getChildAt(0);
        if (child != null) {
            int contentHeight = child.getHeight() - scrollView.getHeight();
            boolean isAtBottom = scrollY >= contentHeight + (-200);
            boolean isNearBottom = scrollY >= contentHeight + (-400);
            if (isNearBottom && this.binding.btnLoadMore.getVisibility() == 8 && this.postDetail != null) {
                int currentPage = this.postDetail.getCurrentPage();
                int totalPages = this.postDetail.getTotalPages();
                if (currentPage < totalPages && !this.isLoadingMore) {
                    loadMoreReplies();
                }
            }

        }
    }

    private void lambda$onCreate$10(View v) {
        this.binding.nestedScroll.smoothScrollTo(0, this.binding.nestedScroll.getChildAt(0).getHeight());
    }

    private void lambda$onCreate$11(View v) {
        this.onlyOpReplies = !this.onlyOpReplies;
        updateReplyFilterAndOrder();
    }

    private void lambda$onCreate$12(View v) {
        this.repliesDescending = !this.repliesDescending;
        refreshPostDetail();
    }

    private void lambda$onCreate$13(View v) {
        showRewardDialog();
    }

    private void lambda$onCreate$14(View v) {
        showKickDialog();
    }

    private void setupRecyclerView() {
        this.replyAdapter = new ReplyAdapter(new ArrayList());
        // build73: 评论长按 -> 回复 / 举报 / (本人)删除
        this.replyAdapter.setOnReplyLongClickListener(new ReplyAdapter.OnReplyLongClickListener() {
            @Override
            public void onReplyLongClick(ReplyItem replyItem, int i) {
                showReplyActionMenu(replyItem);
            }
        });
        this.replyAdapter.setOnReplyClickListener(new ReplyAdapter.OnReplyClickListener() {
            @Override // com.solosu.mtforum.ui.detail.ReplyAdapter.OnReplyClickListener
            public final void onReplyClick(ReplyItem replyItem, int i) {
                ThreadDetailActivity.this.lambda$setupRecyclerView$15(replyItem, i);
            }
        });
        this.replyAdapter.setOnUserClickListener(new ReplyAdapter.OnUserClickListener() {
            @Override // com.solosu.mtforum.ui.detail.ReplyAdapter.OnUserClickListener
            public final void onUserClick(ReplyItem replyItem, int i) {
                ThreadDetailActivity.this.lambda$setupRecyclerView$16(replyItem, i);
            }
        });
        this.binding.recyclerReplies.setLayoutManager(new LinearLayoutManager(this));
        this.binding.recyclerReplies.setAdapter(this.replyAdapter);
    }

    private void lambda$setupRecyclerView$15(ReplyItem item, int position) {
        String author = item != null ? item.getAuthor() : "";
        if (!TextUtils.isEmpty(author)) {
            this.currentReplyPid = item != null ? item.getPid() : "";
            // build71: 不再把"回复 xx:"当预填文本塞进输入框(它会被一起发出去)
            this.currentReplyTarget = "";
        } else {
            this.currentReplyPid = "";
            this.currentReplyTarget = "";
        }
        showReplyBottomSheet(this.currentReplyTarget);
    }

    private void lambda$setupRecyclerView$16(ReplyItem item, int position) {
        String uid = item.getAuthorUid();
        if (!TextUtils.isEmpty(uid)) {
            Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
            intent.putExtra(ChatActivity.EXTRA_UID, uid);
            intent.putExtra("username", item.getAuthor());
            startActivity(intent);
        }
    }

    private void loadPostDetail() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.progressBar.setVisibility(0);
        this.binding.swipeRefresh.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$loadPostDetail$19();
            }
        }).start();
    }

    private void lambda$loadPostDetail$19() {
        try {
            String detailUrl = ForumParser.getThreadDetailUrl(this.tid, getReplyOrder()) + "&_load=" + System.currentTimeMillis();
            this.httpClient.syncFromCookieManager();
            String html = this.httpClient.get(detailUrl);
            if (TextUtils.isEmpty(html)) {
                throw new IllegalStateException("服务器返回空页面，请检查网络后重试");
            }
            final PostDetail detail = ForumParser.parseThreadDetail(html);
            if (detail == null) {
                throw new IllegalStateException("帖子内容解析失败");
            }
            enrichGoodReviewAvatars(detail);
            refreshServerActionState(detail);
            // build61: 进帖触发解锁——只记录页面,渲染后在后台线程执行(不阻塞首屏)
            this.pendingUnlockHtml = html;
            if (!TextUtils.isEmpty(detail.getAuthorUid())) {
                detail.setFollowed(FollowStateManager.resolve(this, detail.getAuthorUid(), detail.isFollowed()));
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadPostDetail$17(detail);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadPostDetail$18(e);
                }
            });
        }
    }

    private void lambda$loadPostDetail$17(PostDetail detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        bindData(detail, true);
        // build61: 渲染完成后,后台线程执行解锁检测→回帖→成功再刷新
        runUnlockInBackground(detail);
    }
    /* build61: 待解锁页面快照(加载线程写,渲染后读一次即清) */
    private String pendingUnlockHtml;
    /** build61: 渲染后异步解锁——16 秒节流在后台线程里等,不卡首屏 */
    private void runUnlockInBackground(final PostDetail detail) {
        if (detail == null || !detail.isHasHiddenContent()) return;
        // 进帖自动解锁开关：此前只有 maybeAutoUnlock 检查它，而真正干活的这条链路不检查，
        // 导致用户在设置里关掉开关后，进帖依旧会自动回帖解锁。
        if (!com.solosu.mtforum.ai.AiConfigManager.isUnlockOnView(this)) {
            AiLog.i("auto-unlock", "跳过：进帖自动解锁开关关闭");
            return;
        }
        final String pageHtml = this.pendingUnlockHtml;
        this.pendingUnlockHtml = null;
        if (TextUtils.isEmpty(pageHtml)) return;
        // 注意: 本文件 import 了 model.Thread, 必须写全限定名 java.lang.Thread
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                boolean ok = com.solosu.mtforum.ai.AutoReplyEngine
                        .tryUnlockOnOpen(ThreadDetailActivity.this, detail, pageHtml);
                if (ok) {
                    runOnUiThread(new Runnable() {
                        @Override // java.lang.Runnable
                        public final void run() {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThreadDetailActivity.this,
                                    "已自动回帖解锁，正在刷新…", 0).show();
                            refreshPostDetail();
                        }
                    });
                }
            }
        }, "unlock-on-open").start();
    }

    private void lambda$loadPostDetail$18(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.progressBar.setVisibility(8);
        this.binding.swipeRefresh.setEnabled(true);
        String message = TextUtils.isEmpty(e.getMessage()) ? "网络异常，请下拉刷新重试" : e.getMessage();
        Toast.makeText(this, "加载失败: " + message, 0).show();
    }

    private String getReplyOrderUrl() {
        return ForumParser.getThreadDetailUrl(this.tid, getReplyOrder());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshPostDetail() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$refreshPostDetail$22();
            }
        }).start();
    }

    private void lambda$refreshPostDetail$22() {
        try {
            String detailUrl = ForumParser.getThreadDetailUrl(this.tid, getReplyOrder()) + "&_refresh=" + System.currentTimeMillis();
            this.httpClient.syncFromCookieManager();
            String html = this.httpClient.get(detailUrl);
            if (TextUtils.isEmpty(html)) {
                throw new IllegalStateException("服务器返回空页面，请检查网络后重试");
            }
            final PostDetail detail = ForumParser.parseThreadDetail(html);
            if (detail == null) {
                throw new IllegalStateException("帖子内容解析失败");
            }
            enrichGoodReviewAvatars(detail);
            refreshServerActionState(detail);
            // build61: 下拉刷新链同样只记录页面,渲染后异步解锁(同加载链)
            this.pendingUnlockHtml = html;
            if (!TextUtils.isEmpty(detail.getAuthorUid())) {
                detail.setFollowed(FollowStateManager.resolve(this, detail.getAuthorUid(), detail.isFollowed()));
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$refreshPostDetail$20(detail);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$refreshPostDetail$21(e);
                }
            });
        }
    }

    private void lambda$refreshPostDetail$20(PostDetail postDetail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.swipeRefresh.setRefreshing(false);
        applyServerActionState(postDetail);
        // build61: 渲染完成后,后台线程执行解锁检测(同加载链)
        runUnlockInBackground(postDetail);
        this.likeCount = Math.max(0, postDetail.getLikeCount());
        updateLikeIcon();
        updateFavoriteIcon();
        this.favoriteCount = Math.max(0, postDetail.getFavoriteCount());
        updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
        bindData(postDetail, false);
    }

    private void lambda$refreshPostDetail$21(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.swipeRefresh.setRefreshing(false);
        String message = TextUtils.isEmpty(e.getMessage()) ? "网络异常，请稍后重试" : e.getMessage();
        Toast.makeText(this, "刷新失败: " + message, 0).show();
    }

    private void bindData(final PostDetail postDetail, boolean z) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (postDetail == null) {
            this.binding.progressBar.setVisibility(8);
            this.binding.swipeRefresh.setEnabled(true);
            Toast.makeText(this, "帖子内容为空，请下拉刷新重试", 0).show();
            return;
        }
        this.postDetail = postDetail;
        this.binding.progressBar.setVisibility(8);
        this.binding.swipeRefresh.setEnabled(true);
        if (!TextUtils.isEmpty(postDetail.getForumName())) {
            this.binding.tvForumName.setVisibility(0);
            this.binding.tvForumName.setText(postDetail.getForumName());
        } else {
            this.binding.tvForumName.setVisibility(8);
        }
        this.binding.tvThreadTitle.setText(!TextUtils.isEmpty(postDetail.getTitle()) ? postDetail.getTitle() : "");
        // build75: 长按标题 -> 举报帖子
        this.binding.tvThreadTitle.setLongClickable(true);
        this.binding.tvThreadTitle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                reportPost(null);
                return true;
            }
        });
        String avatarUrl = postDetail.getAvatarUrl();
        if (!TextUtils.isEmpty(avatarUrl)) {
            Glide.with((FragmentActivity) this).load(avatarUrl).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(this.binding.ivAuthorAvatar);
        } else {
            this.binding.ivAuthorAvatar.setImageResource(R.drawable.ic_account);
        }
        this.binding.tvAuthorName.setText(!TextUtils.isEmpty(postDetail.getAuthor()) ? postDetail.getAuthor() : "匿名");
        final String authorUid = postDetail.getAuthorUid();
        if (!TextUtils.isEmpty(authorUid)) {
            this.binding.ivAuthorAvatar.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$bindData$23(authorUid, postDetail, view);
                }
            });
            this.binding.tvAuthorName.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$bindData$24(authorUid, postDetail, view);
                }
            });
        }
        if (!TextUtils.isEmpty(postDetail.getAuthorLevel())) {
            this.binding.tvAuthorLevel.setVisibility(0);
            this.binding.tvAuthorLevel.setText(postDetail.getAuthorLevel());
        } else {
            this.binding.tvAuthorLevel.setVisibility(8);
        }
        this.binding.tvPublishTime.setText(!TextUtils.isEmpty(postDetail.getPublishTime()) ? postDetail.getPublishTime() : "");
        this.binding.tvLocation.setVisibility(8);
        // 收藏数回填缓存:详情页拿到数字后存进 FavoritesCache,列表卡片第四格就能显示
        if (postDetail.getFavoriteCount() > 0) {
            com.solosu.mtforum.session.FavoritesCache.put(this, postDetail.getTid(), postDetail.getFavoriteCount());
        }
        if (this.httpClient.isLoggedIn() && !TextUtils.isEmpty(postDetail.getAuthor())) {
            this.binding.btnFollow.setVisibility(0);
            if (isOwnThread(postDetail)) {
                // build73: 自己的帖子 -> 右上角是「编辑」(不是关注)
                this.binding.btnFollow.setText("编辑");
                this.binding.btnFollow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        openEditThread();
                    }
                });
            } else {
                this.binding.btnFollow.setText(getString(postDetail.isFollowed() ? R.string.action_followed : R.string.action_follow));
                this.binding.btnFollow.setOnClickListener(new View.OnClickListener() {
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        ThreadDetailActivity.this.lambda$bindData$25(view);
                    }
                });
            }
        } else {
            this.binding.btnFollow.setVisibility(8);
        }
        String contentHtml = postDetail.getContentHtml();
        String[] strArrReplaceHiddenQuoteWithPlaceholder = {null, ""};
        if (!TextUtils.isEmpty(contentHtml)) {
            String strConvertBBCodeToHtml = BBCodeUtil.convertBBCodeToHtml(contentHtml);
            this.binding.tvContent.setVisibility(0);
            ArrayList arrayList = new ArrayList();
            String[] strArrSplitEditFooter = splitEditFooter(strConvertBBCodeToHtml);
            String strExtractAndSeparateImages = extractAndSeparateImages(strArrSplitEditFooter[0], arrayList);
            List<String> imageUrls = postDetail.getImageUrls();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (String str : imageUrls) {
                    if (!arrayList.contains(str)) {
                        arrayList.add(str);
                    }
                }
            }
            if (!TextUtils.isEmpty(strArrSplitEditFooter[1])) {
                this.binding.layoutEditFooter.setVisibility(0);
                this.binding.tvEditFooter.setText(strArrSplitEditFooter[1]);
                this.binding.viewContentTopDivider.setVisibility(8);
                adjustEditFooterDividerWidth();
                LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) this.binding.frameContent.getLayoutParams();
                layoutParams.topMargin = 0;
                this.binding.frameContent.setLayoutParams(layoutParams);
            } else {
                this.binding.layoutEditFooter.setVisibility(8);
                this.binding.viewContentTopDivider.setVisibility(0);
                LinearLayout.LayoutParams layoutParams2 = (LinearLayout.LayoutParams) this.binding.frameContent.getLayoutParams();
                layoutParams2.topMargin = dpToPx(12);
                this.binding.frameContent.setLayoutParams(layoutParams2);
            }
            // 收集当前帖全部图片供全屏翻页
            this.currentImageList = new ArrayList<>(arrayList);
            strArrReplaceHiddenQuoteWithPlaceholder = replaceHiddenQuoteWithPlaceholder(strExtractAndSeparateImages);
            boolean z2 = true;
            this.binding.tvContent.setText(safeFromHtml(strArrReplaceHiddenQuoteWithPlaceholder[0], createInlineImageGetter(this.binding.tvContent), com.solosu.mtforum.util.BBCodeUtil.createTagHandler(this)));
            boolean unlocked = postDetail.isHasHiddenContent() && this.httpClient.isLoggedIn()
                    && !TextUtils.isEmpty(postDetail.getHiddenContentHtml())
                    && !com.solosu.mtforum.ai.AutoReplyEngine.isLockedHidden(postDetail.getHiddenContentHtml());
            String hiddenNotice = unlocked ? "隐藏内容(已解锁)"
                    : strArrReplaceHiddenQuoteWithPlaceholder[1];
            applyHiddenNoticeHighlight(this.binding.tvContent.getText(), hiddenNotice);
            setupClickableLinks(this.binding.tvContent);
            if (!arrayList.isEmpty()) {
                this.binding.cardImageGallery.setVisibility(0);
                this.binding.hsvImageGallery.setVisibility(0);
                this.binding.llImageGallery.removeAllViews();
                FrostedGlassHelper.applyToCardViews(this.binding.cardImageGallery, this);
                int iDpToPx = dpToPx(ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION);
                int iDpToPx2 = dpToPx(4);
                for (final String str2 : (java.util.List<String>) arrayList) {
                    ImageView imageView = new ImageView(this);
                    imageView.setLayoutParams(new LinearLayout.LayoutParams(-2, iDpToPx));
                    imageView.setAdjustViewBounds(z2);
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    ((LinearLayout.LayoutParams) imageView.getLayoutParams()).setMargins(iDpToPx2, 0, iDpToPx2, 0);
                    imageView.setOnClickListener(new View.OnClickListener() {
                        @Override // android.view.View.OnClickListener
                        public final void onClick(View view) {
                            ThreadDetailActivity.this.lambda$bindData$26(str2, view);
                        }
                    });
                    Glide.with((FragmentActivity) this).load(str2).placeholder(new ColorDrawable(getColor(R.color.background_secondary))).error((Drawable) new ColorDrawable(getColor(R.color.divider))).into(imageView);
                    this.binding.llImageGallery.addView(imageView);
                    iDpToPx = iDpToPx;
                    z2 = true;
                }
                this.binding.btnCollapseImages.setOnClickListener(new View.OnClickListener() {
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        ThreadDetailActivity.this.lambda$bindData$28(view);
                    }
                });
            } else {
                this.binding.cardImageGallery.setVisibility(8);
            }
        } else {
            this.binding.tvContent.setVisibility(8);
            this.binding.cardImageGallery.setVisibility(8);
            this.binding.tvContent.setVisibility(0);
            this.binding.tvContent.setText("[内容加载中，请刷新重试]");
            this.binding.tvContent.setTextColor(getColor(R.color.text_hint));
            this.binding.tvContent.setTextSize(14.0f);
            this.binding.tvContent.setGravity(17);
        }
        boolean z3 = true;
        boolean hasHidden = postDetail.isHasHiddenContent();
        boolean hiddenUnlocked = hasHidden && this.httpClient.isLoggedIn()
                && !TextUtils.isEmpty(postDetail.getHiddenContentHtml())
                && !com.solosu.mtforum.ai.AutoReplyEngine.isLockedHidden(postDetail.getHiddenContentHtml());
        if (hiddenUnlocked) {
            // 已登录且可获取隐藏内容:正文中的胶囊只显示短提示,下方直接展示完整内容
            this.binding.layoutHiddenContent.setVisibility(0);
            this.binding.tvHiddenContentHint.setVisibility(8);
            this.binding.btnViewHidden.setVisibility(8);
            renderHiddenContent(postDetail.getHiddenContentHtml());
        } else if (hasHidden) {
            // 未登录或暂无内容:显示按钮引导查看(点击会提示登录或重新加载)
            this.binding.layoutHiddenContent.setVisibility(0);
            this.binding.tvHiddenContentHint.setVisibility(0);
            this.binding.btnViewHidden.setVisibility(0);
            this.binding.tvHiddenContent.setVisibility(8);
            // 自动解锁：进入帖子发现是「回复可见」时，后台直接回复解锁
            maybeAutoUnlock();
        } else {
            this.binding.layoutHiddenContent.setVisibility(8);
        }
        if (postDetail.isLikedStateKnown()) {
            this.isLiked = postDetail.isLiked();
            saveLikedState(this.isLiked);
        } else {
            this.isLiked = restoreLikedState();
        }
        this.likeCount = Math.max(0, postDetail.getLikeCount());
        updateLikeIcon();
        updateCountBadge(this.binding.tvCommentsBadge, postDetail.getReplyCount());
        updateCountBadge(this.binding.tvLikeBadge, this.likeCount);
        // === 点赞人头像行 ===
        bindLikeUsers(postDetail);
        if (postDetail.isFavoritedStateKnown()) {
            this.isFavorited = postDetail.isFavorited();
            saveFavoritedState(this.isFavorited);
        } else {
            this.isFavorited = restoreFavoritedState();
        }
        updateFavoriteIcon();
        this.favoriteCount = Math.max(0, postDetail.getFavoriteCount());
        updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
        List<ReplyItem> replies = postDetail.getReplies();
        if (replies == null) {
            replies = new ArrayList();
        }
        // 黑名单过滤:拉黑作者的回帖直接不展示
        java.util.Set<String> bl = com.solosu.mtforum.session.BlacklistManager.uidSet(this);
        if (!bl.isEmpty()) {
            java.util.Iterator<ReplyItem> itr = replies.iterator();
            while (itr.hasNext()) {
                ReplyItem r = itr.next();
                if (r != null && r.getAuthorUid() != null && bl.contains(r.getAuthorUid())) itr.remove();
            }
        }
        this.displayedReplies = new ArrayList(replies);
        updateReplyFilterAndOrder();
        int replyCount = postDetail.getReplyCount();
        if (replyCount > 0) {
            this.binding.tvReplyCount.setVisibility(0);
            this.binding.tvReplyCount.setText("(" + replyCount + ")");
        } else {
            this.binding.tvReplyCount.setVisibility(8);
        }
        if (replies == null || replies.isEmpty() || postDetail.getCurrentPage() < postDetail.getTotalPages() || !TextUtils.isEmpty(postDetail.getNextPageUrl())) {
        }
        if (replyCount > (replies != null ? replies.size() : 0)) {
        }
        this.binding.btnLoadMore.setVisibility(8);
        this.binding.layoutReply.setVisibility(this.httpClient.isLoggedIn() ? 0 : 8);
        this.binding.layoutThreadActions.setVisibility(this.httpClient.isLoggedIn() ? 0 : 8);
        int rewardCount = postDetail.getRewardCount();
        int goodReviewCount = postDetail.getGoodReviewCount();
        int rewardCoins = postDetail.getRewardCoins();
        List<String> rewardUserAvatars = postDetail.getRewardUserAvatars();
        List<String> goodReviewUserAvatars = postDetail.getGoodReviewUserAvatars();
        if (rewardCount <= 0 && goodReviewCount <= 0 && ((rewardUserAvatars == null || rewardUserAvatars.isEmpty()) && (goodReviewUserAvatars == null || goodReviewUserAvatars.isEmpty()))) {
            z3 = false;
        }
        if (this.httpClient.isLoggedIn() && z3) {
            this.binding.layoutRewardReviewStats.setVisibility(0);
            this.binding.tvRewardCount.setText(String.valueOf(rewardCount));
            this.binding.tvRewardCoins.setText("共计 " + rewardCoins + " 金币");
            this.binding.tvGoodReviewCount.setText(String.valueOf(goodReviewCount));
            bindAvatarStrip(this.binding.llRewardAvatars, rewardUserAvatars);
            bindAvatarStrip(this.binding.llGoodReviewAvatars, goodReviewUserAvatars);
        } else {
            this.binding.layoutRewardReviewStats.setVisibility(8);
        }
        if (z) {
            this.binding.nestedScroll.scrollTo(0, 0);
        }
    }

    private void lambda$bindData$23(String authorUid, PostDetail detail, View v) {
        Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
        intent.putExtra(ChatActivity.EXTRA_UID, authorUid);
        intent.putExtra("username", detail.getAuthor());
        startActivity(intent);
    }

    private void lambda$bindData$24(String authorUid, PostDetail detail, View v) {
        Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
        intent.putExtra(ChatActivity.EXTRA_UID, authorUid);
        intent.putExtra("username", detail.getAuthor());
        startActivity(intent);
    }

    private void lambda$bindData$25(View v) {
        toggleFollow();
    }

    private void lambda$bindData$26(String imgUrl, View v) {
        openImagePreview(imgUrl);
    }

    private void lambda$bindData$28(View v) {
        boolean isCollapsed = this.binding.hsvImageGallery.getVisibility() == 8;
        if (isCollapsed) {
            this.binding.hsvImageGallery.setVisibility(0);
            this.binding.hsvImageGallery.setAlpha(0.0f);
            this.binding.hsvImageGallery.animate().alpha(1.0f).setDuration(300L).start();
            this.binding.btnCollapseImages.animate().rotation(90.0f).setDuration(200L).start();
            return;
        }
        this.binding.hsvImageGallery.animate().alpha(0.0f).setDuration(200L).withEndAction(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$bindData$27();
            }
        }).start();
        this.binding.btnCollapseImages.animate().rotation(-90.0f).setDuration(200L).start();
    }

    private void lambda$bindData$27() {
        this.binding.hsvImageGallery.setVisibility(8);
    }

    private String getReplyOrder() {
        return this.repliesDescending ? "desc" : "asc";
    }

    private void updateReplyFilterAndOrder() {
        List<ReplyItem> source = this.displayedReplies == null ? new ArrayList<>() : this.displayedReplies;
        List<ReplyItem> result = new ArrayList<>();
        String opUid = this.postDetail != null ? this.postDetail.getAuthorUid() : "";
        String opName = this.postDetail != null ? this.postDetail.getAuthor() : "";
        for (ReplyItem item : source) {
            if (item != null) {
                if (this.onlyOpReplies) {
                    boolean isOp = item.isOP();
                    if (!isOp && !TextUtils.isEmpty(opUid)) {
                        isOp = opUid.equals(item.getAuthorUid());
                    }
                    if (!isOp && !TextUtils.isEmpty(opName)) {
                        isOp = opName.equals(item.getAuthor());
                    }
                    if (!isOp) {
                        // build71: 之前这里是空块,过滤动作被挖空,导致"只看楼主"点了没效果
                        continue;
                    }
                }
                result.add(item);
            }
        }
        this.replyAdapter.updateData(result);
        this.binding.btnOnlyOp.setText(this.onlyOpReplies ? R.string.reply_all_users : R.string.reply_only_op);
        this.binding.btnOnlyOp.setTextColor(getColor(this.onlyOpReplies ? R.color.primary : R.color.text_secondary));
        this.binding.btnReplyOrder.setText(this.repliesDescending ? R.string.reply_order_desc : R.string.reply_order_asc);
        this.binding.btnReplyOrder.setTextColor(getColor(this.repliesDescending ? R.color.primary : R.color.text_secondary));
        if (result.isEmpty()) {
            this.binding.recyclerReplies.setVisibility(8);
            this.binding.tvEmptyReplies.setVisibility(0);
        } else {
            this.binding.recyclerReplies.setVisibility(0);
            this.binding.tvEmptyReplies.setVisibility(8);
        }
    }

    private void enrichGoodReviewAvatars(PostDetail detail) {
        if (detail == null) {
            return;
        }
        try {
            if (detail.getGoodReviewCount() > 0) {
                String desktopHtml = this.httpClient.getDesktop(ForumParser.getThreadDesktopDetailUrl(this.tid));
                List<String> avatars = ForumParser.parseGoodReviewAvatarUrls(desktopHtml);
                if (avatars != null && !avatars.isEmpty()) {
                    detail.setGoodReviewUserAvatars(avatars);
                }
            }
            String desktopHtml2 = detail.getRewardDetailUrl();
            if (!TextUtils.isEmpty(desktopHtml2)) {
                String rewardHtml = this.httpClient.get(detail.getRewardDetailUrl());
                detail.setRewardCoins(ForumParser.parseRewardCoins(rewardHtml));
            }
        } catch (Exception e) {
        }
    }

    private void bindAvatarStrip(LinearLayout linearLayout, List<String> avatarUrls) {
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        if (avatarUrls == null || avatarUrls.isEmpty()) {
            return;
        }
        int maxVisible = Math.min(6, avatarUrls.size());
        int size = dpToPx(32);
        int overlap = dpToPx(8);
        for (int i = 0; i < maxVisible; i++) {
            ImageView avatar = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            if (i > 0) {
                params.leftMargin = -overlap;
            }
            avatar.setLayoutParams(params);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setPadding(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1));
            avatar.setBackgroundResource(R.drawable.circle_avatar_bg);
            String url = avatarUrls.get(i);
            Glide.with((FragmentActivity) this).load(url).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(avatar);
            linearLayout.addView(avatar);
        }
        int i2 = avatarUrls.size();
        if (i2 > 6) {
            TextView more = new TextView(this);
            LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32));
            params2.leftMargin = -overlap;
            more.setLayoutParams(params2);
            more.setGravity(17);
            more.setText("+" + (avatarUrls.size() - 6));
            more.setTextSize(10.0f);
            more.setTextColor(-1);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(1);
            bg.setColor(-1728053248);
            bg.setStroke(dpToPx(1), -1);
            more.setBackground(bg);
            linearLayout.addView(more);
        }
    }

    private void showReplyBottomSheet(String prefillText) {
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_reply_bottom_sheet, (ViewGroup) null);
        MaterialCardView dialogCard = (MaterialCardView) dialogView.findViewById(R.id.dialog_card);
        final TextInputEditText etReplyDialog = (TextInputEditText) dialogView.findViewById(R.id.et_reply_dialog);
        MaterialButton btnSend = (MaterialButton) dialogView.findViewById(R.id.btn_send_reply);
        TextView tvTarget = (TextView) dialogView.findViewById(R.id.tv_reply_target);
        if (!TextUtils.isEmpty(prefillText)) {
            etReplyDialog.setText(prefillText);
            etReplyDialog.setSelection(prefillText.length());
            tvTarget.setText(prefillText);
            tvTarget.setVisibility(0);
        }
        FrostedGlassHelper.applyToCardViews(dialogCard, this);
        this.mBottomSheetDialog = new BottomSheetDialog(this);
        this.mBottomSheetDialog.setContentView(dialogView);
        DialogHelper.applyToBottomSheet(this.mBottomSheetDialog, dialogView, this);
        this.mBottomSheetDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$29(dialogInterface);
            }
        });
        this.mBottomSheetDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override // android.content.DialogInterface.OnShowListener
            public final void onShow(DialogInterface dialogInterface) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$30(dialogView, dialogInterface);
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$31(etReplyDialog, view);
            }
        });
        ImageButton btnPickImage = dialogView.findViewById(R.id.btn_pick_image);
        if (btnPickImage != null) {
            btnPickImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        }
        if (!pendingImageUris.isEmpty()) {
            updateDialogImagePreview();
        }
        this.mBottomSheetDialog.show();
    }

    private void lambda$showReplyBottomSheet$29(DialogInterface d) {
        this.currentReplyPid = "";
        this.currentReplyTarget = "";
    }

    private void lambda$showReplyBottomSheet$30(View dialogView, DialogInterface d) {
        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
            int peekHeight = (int) (((double) getResources().getDisplayMetrics().heightPixels) * 0.5d);
            behavior.setPeekHeight(peekHeight);
        }
    }

    private void lambda$showReplyBottomSheet$31(TextInputEditText etReplyDialog, View v) {
        String text = etReplyDialog.getText().toString().trim();
        if (TextUtils.isEmpty(text) && pendingImageUris.isEmpty()) {
            etReplyDialog.setError(getString(R.string.reply_hint_empty));
        } else {
            etReplyDialog.setError(null);
            String attachTags = buildAttachTags();
            String finalText = attachTags + text;
            attemptReply(finalText, etReplyDialog);
        }
    }

    /** 未登录操作统一弹出登录底部弹窗(与回复弹窗同风格) */
    private void promptLogin() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        LoginBottomSheet.show(this, null);
    }

    private void attemptReply(final String replyText, TextInputEditText etReplyInput) {
        if (!this.httpClient.isLoggedIn()) {
            this.httpClient.syncFromCookieManager();
        }
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
        } else {
            if (TextUtils.isEmpty(replyText)) {
                this.binding.tilReply.setError(getString(R.string.reply_hint_empty));
                return;
            }
            this.binding.tilReply.setError(null);
            final String formhash = this.postDetail != null ? this.postDetail.getFormhash() : null;
            new java.lang.Thread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$attemptReply$32(formhash, replyText);
                }
            }).start();
        }
    }

    private void lambda$attemptReply$32(String formhash, String replyText) {
        String fh = formhash;
        try {
            if (TextUtils.isEmpty(fh)) {
                String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                fh = ForumParser.parseFormhash(html);
            }
            if (TextUtils.isEmpty(fh)) {
                showReplyFailure("获取回复验证失败，请刷新页面后重试");
                return;
            }
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("message", replyText);
            params.put("replysubmit", "yes");
            // build71: 上传得到的 aid 必须随回复一起提交 attachnew,否则附件不会被关联
            //        (发帖页 attemptPost 有这一步,回复页之前漏了 -> "图片传上去了但发不出去")
            synchronized (pendingUploadAids) {
                for (String aid : pendingUploadAids) {
                    if (!TextUtils.isEmpty(aid)) {
                        params.put("attachnew[" + aid + "][description]", "");
                    }
                }
            }
            if (!TextUtils.isEmpty(this.currentReplyPid)) {
                params.put("reppid", this.currentReplyPid);
                params.put("reppost", this.currentReplyPid);
                params.put("addfeed", "1");
                String trimStr = buildReplyQuote(this.currentReplyPid);
                if (!TextUtils.isEmpty(trimStr)) {
                    params.put("noticetrimstr", trimStr);
                }
                params.put("noticeauthormsg", replyText);
            }
            if (this.postDetail != null && !TextUtils.isEmpty(this.postDetail.getNoticeauthor())) {
                params.put("noticeauthor", this.postDetail.getNoticeauthor());
            }
            params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));
            String replyUrl = "https://bbs.binmt.cc/forum.php?mod=post&action=reply&fid=" + (this.postDetail != null ? this.postDetail.getForumFid() : "") + "&tid=" + this.tid + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";
            String result = this.httpClient.post(replyUrl, params);
            boolean responseReportsSuccess = isReplyResponseSuccessful(result);
            if (!responseReportsSuccess && !wasReplyPublished(replyText)) {
                showReplyFailure(extractReplyError(result));
            } else {
                completeReplyPublished(null);
            }
        } catch (Exception e) {
            showReplyFailure("回复失败：" + (TextUtils.isEmpty(e.getMessage()) ? "网络异常，请稍后重试" : e.getMessage()));
        }
    }

    private boolean wasReplyPublished(String replyText) {
        try {
            String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            if (TextUtils.isEmpty(currentUid)) {
                return false;
            }
            int lastPage = this.postDetail != null ? Math.max(1, this.postDetail.getTotalPages()) : 1;
            String expectedText = normalizeReplyText(replyText);
            for (int page = lastPage; page <= lastPage + 1; page++) {
                String url = ForumParser.getThreadDetailUrl(this.tid, page, getReplyOrder()) + "&_reply_check=" + System.currentTimeMillis();
                PostDetail latest = ForumParser.parseThreadDetail(this.httpClient.get(url));
                if (latest != null && latest.getReplies() != null) {
                    for (int k = latest.getReplies().size() - 1; k >= 0; k--) {
                        ReplyItem item = latest.getReplies().get(k);
                        if (item != null && currentUid.equals(item.getAuthorUid()) && !TextUtils.isEmpty(expectedText) && normalizeReplyText(item.getContentText()).contains(expectedText)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    private String normalizeReplyText(String text) {
        return TextUtils.isEmpty(text) ? "" : text.replaceAll("(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]", "").replaceAll("\\s+", " ").trim();
    }

    private void completeReplyPublished(List<String> dummy) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$completeReplyPublished$33();
            }
        });
    }

    private void lambda$completeReplyPublished$33() {
        this.currentReplyPid = "";
        this.currentReplyTarget = "";
        this.binding.tilReply.setError(null);
        // build80: 回复已发出 -> 清空待发送图片队列与残留的 [attachimg] 标签,
        //          否则图片会一直留在回复栏里(已提交成功却看着像没发出去)
        this.pendingImageUris.clear();
        this.uploadedAidMap.clear();
        synchronized (this.pendingUploadAids) {
            this.pendingUploadAids.clear();
        }
        synchronized (this.imageUploadPendingQueue) {
            this.imageUploadPendingQueue.clear();
        }
        if (this.binding != null && this.binding.etReply != null) {
            this.binding.etReply.setText("");
        }
        refreshAllImagePreviews();
        Toast.makeText(this, R.string.reply_success, 0).show();
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        refreshPostDetail();
    }

    private String buildReplyQuote(String pid) {
        if (TextUtils.isEmpty(pid) || this.displayedReplies == null) {
            return null;
        }
        for (ReplyItem item : this.displayedReplies) {
            if (item != null && pid.equals(item.getPid())) {
                String author = !TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor() : "匿名";
                String time = !TextUtils.isEmpty(item.getTime()) ? item.getTime() : "";
                String content = TextUtils.isEmpty(item.getContentText()) ? "" : item.getContentText();
                return "[quote][color=#999999]" + author + " 发表于 " + time + "[/color]\n" + content + "[/quote]";
            }
        }
        return null;
    }

    private String extractRecommendActionUrl(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Element link = doc.select("a.comiis_recommend_addkey, a.comiis_recommend_new").first();
            if (link == null) {
                return null;
            }
            String href = link.attr("href");
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1);
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href;
                }
                return href;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String appendQuery(String url, String query) {
        if (TextUtils.isEmpty(url) || url.contains("inajax=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private boolean containsAny(String text, String... values) {
        if (TextUtils.isEmpty(text) || values == null) {
            return false;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value) && text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikeAddResponseSuccessful(String result) {
        if (TextUtils.isEmpty(result) || ForumParser.isLoginPage(result) || containsAny(result, "没有点赞权限", "不能点赞", "今日评价机会已用完", "关闭的主题无法执行", "请先登录", "formhash错误", "非法操作")) {
            return false;
        }
        return containsAny(result, "recommendv", "recommendc", "点赞成功", "推荐成功", "succeedhandle_recommend", "评价成功");
    }

    private void updateLikeIcon() {
        ImageButton imageButton = this.binding.btnLike;
        // build66: 改用与列表页一致的拇指标(原 forum_like 是心形),用颜色区分已赞/未赞
        imageButton.setImageResource(R.drawable.ic_like_detail);
        imageButton.setColorFilter(getColor(this.isLiked ? R.color.primary : R.color.icon_secondary));
        updateCountBadge(this.binding.tvLikeBadge, Math.max(0, this.likeCount));
        // build80: 点赞数回写缓存, 返回列表页时按 tid 回填, 及时同步
        if (!TextUtils.isEmpty(this.tid)) {
            com.solosu.mtforum.session.PostCountsCache.setLikes(this.tid, Math.max(0, this.likeCount));
        }
    }

    private void updateFavoriteIcon() {
        int i;
        ImageButton imageButton = this.binding.btnFavorite;
        if (this.isFavorited) {
            i = R.drawable.forum_favorite_on;
        } else {
            i = R.drawable.forum_favorite_off;
        }
        imageButton.setImageResource(i);
    }

    private void updateCountBadge(TextView badge, int count) {
        if (badge == null) {
            return;
        }
        if (count > 0) {
            // build65: 帖子内角标显示真实数字(原 99+ 截断),超大值才用 999+
            badge.setText(count > 999 ? "999+" : String.valueOf(count));
            badge.setVisibility(0);
        } else {
            badge.setVisibility(8);
        }
    }

    private void insertAtMention() {
        int start = Math.max(0, this.binding.etReply.getSelectionStart());
        String text = this.binding.etReply.getText() == null ? "" : this.binding.etReply.getText().toString();
        this.binding.etReply.setText(text.substring(0, Math.min(start, text.length())) + "@" + text.substring(Math.min(start, text.length())));
        this.binding.etReply.setSelection(Math.min(start, text.length()) + "@".length());
        this.binding.etReply.requestFocus();
    }

    private void toggleFollow() {
        if (this.postDetail == null || TextUtils.isEmpty(this.postDetail.getAuthorUid())) {
            return;
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            promptLogin();
            return;
        }
        final boolean targetState = !this.postDetail.isFollowed();
        this.binding.btnFollow.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFollow$35(targetState);
            }
        }).start();
    }

    private void lambda$toggleFollow$35(final boolean targetState) {
        final boolean success = FollowStateManager.syncFollow(this, this.postDetail.getAuthorUid(), targetState);
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFollow$34(success, targetState);
            }
        });
    }

    private void lambda$toggleFollow$34(boolean success, boolean targetState) {
        int i;
        this.binding.btnFollow.setEnabled(true);
        if (!success) {
            Toast.makeText(this, "关注操作失败，请稍后重试", 0).show();
            return;
        }
        this.postDetail.setFollowed(targetState);
        this.binding.btnFollow.setText(targetState ? R.string.action_followed : R.string.action_follow);
        if (targetState) {
            i = R.string.action_follow_success;
        } else {
            i = R.string.action_unfollow_success;
        }
        Toast.makeText(this, i, 0).show();
    }

    private void toggleFavorite() {
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(this.tid) || !this.binding.btnFavorite.isEnabled()) {
            return;
        }
        final boolean targetState = !this.isFavorited;
        final boolean oldState = this.isFavorited;
        this.binding.btnFavorite.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFavorite$37(targetState, oldState);
            }
        }).start();
    }

    private void lambda$toggleFavorite$37(final boolean targetState, final boolean oldState) {
        boolean success = false;
        String errorMessage = null;
        try {
            this.httpClient.syncFromCookieManager();
            boolean z = true;
            if (targetState) {
                String pageHtml = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_refresh=" + System.currentTimeMillis());
                String actionUrl = extractFavoriteActionUrl(pageHtml);
                if (TextUtils.isEmpty(actionUrl)) {
                    throw new IllegalStateException("无法获取收藏操作地址");
                }
                String result = this.httpClient.get(appendQuery(actionUrl, "inajax=1"));
                if (!ForumParser.isLoginPage(result) && !containsAny(result, "请先登录", "没有权限", "非法操作", "formhash错误")) {
                    String favoritePostUrl = extractFavoriteFormAction(result, actionUrl);
                    if (!TextUtils.isEmpty(favoritePostUrl)) {
                        Map<String, String> formParams = extractFavoriteFormParams(result);
                        formParams.put("favoritesubmit", "true");
                        formParams.put("favoritesubmit_btn", "确定");
                        if (!formParams.containsKey("description")) {
                            formParams.put("description", "手机收藏");
                        }
                        String postResult = this.httpClient.post(favoritePostUrl, formParams);
                        success = isFavoriteMutationResponseSuccessful(postResult, true);
                    } else {
                        success = isFavoriteMutationResponseSuccessful(result, true);
                    }
                }
            } else {
                success = requestRemoveFavoriteFromServer();
            }
            Boolean serverState = queryServerFavoriteStateWithRetry(targetState);
            if (serverState != null) {
                if (serverState.booleanValue() != targetState) {
                    z = false;
                }
                success = z;
            }
            if (!success) {
                errorMessage = "网页端未确认收藏状态已更新";
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }
        final boolean finalSuccess = success;
        final String finalError = errorMessage;
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFavorite$36(finalSuccess, targetState, oldState, finalError);
            }
        });
    }

    private void lambda$toggleFavorite$36(boolean z, boolean z2, boolean z3, String str) {
        this.binding.btnFavorite.setEnabled(true);
        if (z) {
            this.isFavorited = z2;
            saveFavoritedState(z2);
            if (this.postDetail != null) {
                this.postDetail.setFavorited(z2);
                this.postDetail.setFavoritedStateKnown(true);
            }
            updateFavoriteIcon();
            this.favoriteCount = Math.max(0, this.favoriteCount + (z2 ? 1 : -1));
            updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
            Toast.makeText(this, z2 ? "已收藏" : "已取消收藏", 0).show();
            return;
        }
        this.isFavorited = z3;
        updateFavoriteIcon();
        Toast.makeText(this, TextUtils.isEmpty(str) ? "收藏操作失败，请稍后重试" : str, 0).show();
    }

    private String extractFavoriteActionUrl(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Element link = doc.select("#comiis_favorite_a").first();
            if (link == null) {
                return null;
            }
            String href = link.attr("href");
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1);
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href;
                }
                return href;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFavoriteFormAction(String response, String fallbackUrl) {
        if (TextUtils.isEmpty(response)) {
            return null;
        }
        try {
            String html = extractCdata(response);
            Document doc = Jsoup.parse(html);
            Element form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first();
            if (form == null) {
                return null;
            }
            String action = form.attr("action");
            if (TextUtils.isEmpty(action)) {
                action = fallbackUrl;
            }
            return normalizeForumUrl(action);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> extractFavoriteFormParams(String response) {
        Map<String, String> params = new HashMap<>();
        if (TextUtils.isEmpty(response)) {
            return params;
        }
        try {
            Document doc = Jsoup.parse(extractCdata(response));
            Element form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first();
            if (form == null) {
                return params;
            }
            for (Element input : form.select("input[name], textarea[name], select[name]")) {
                String name = input.attr(ChatActivity.EXTRA_NAME);
                if (!TextUtils.isEmpty(name)) {
                    String value = input.tagName().equalsIgnoreCase("textarea") ? input.text() : input.attr("value");
                    params.put(name, value == null ? "" : value);
                }
            }
        } catch (Exception e) {
        }
        return params;
    }

    private String extractCdata(String response) {
        if (TextUtils.isEmpty(response)) {
            return "";
        }
        int start = response.indexOf("<![CDATA[");
        if (start < 0) {
            start = response.indexOf("<![cdata[");
        }
        if (start < 0) {
            return response;
        }
        int start2 = start + 9;
        int end = response.indexOf("]]>", start2);
        return end >= 0 ? response.substring(start2, end) : response.substring(start2);
    }

    private String normalizeForumUrl(String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return url.startsWith("/") ? HttpClient.BASE_URL + url.substring(1) : HttpClient.BASE_URL + url;
    }

    private boolean isFavoriteMutationResponseSuccessful(String response, boolean targetState) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response) || containsAny(response, "请先登录", "没有权限", "非法操作", "formhash错误", "收藏失败", "取消收藏失败")) {
            return false;
        }
        if (targetState) {
            return containsAny(response, "succeedhandle_favorite_add", "succeedhandle_favorite_thread", "收藏成功", "信息收藏成功", "已收藏", "重复收藏");
        }
        return containsAny(response, "succeedhandle_favorite_del", "succeedhandle_favorite_thread", "取消收藏成功", "已取消收藏", "删除成功", "取消成功");
    }

    private boolean requestRemoveFavoriteFromServer() throws Exception {
        String page = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove=" + System.currentTimeMillis());
        if (ForumParser.isLoginPage(page)) {
            return false;
        }
        String favid = "";
        List<Thread> favorites = ForumParser.parseFavoriteList(page);
        if (favorites != null) {
            Iterator<Thread> it = favorites.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                Thread item = it.next();
                if (item != null && this.tid.equals(item.getTid())) {
                    favid = item.getFavid();
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            Matcher matcher = Pattern.compile("(?:favid|fav_id)=(\\d+)[^<>]{0,300}(?:tid=" + Pattern.quote(this.tid) + "|thread-" + Pattern.quote(this.tid) + "-)", 34).matcher(page);
            if (matcher.find()) {
                favid = matcher.group(1);
            }
            if (TextUtils.isEmpty(favid)) {
                Matcher matcher2 = Pattern.compile("(?:tid=" + Pattern.quote(this.tid) + "|thread-" + Pattern.quote(this.tid) + "-)[^<>]{0,300}(?:favid|fav_id)=(\\d+)", 34).matcher(page);
                if (matcher2.find()) {
                    favid = matcher2.group(1);
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            return false;
        }
        String formhash = ForumParser.parseFormhash(page);
        if (TextUtils.isEmpty(formhash)) {
            String detailHtml = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_remove_hash=" + System.currentTimeMillis());
            formhash = ForumParser.parseFormhash(detailHtml);
        }
        String deleteUrl = "https://bbs.binmt.cc/home.php?mod=spacecp&ac=favorite&op=delete&favid=" + favid + "&type=all&mobile=2";
        Map<String, String> params = new HashMap<>();
        params.put("referer", "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2");
        params.put("deletesubmit", "true");
        if (!TextUtils.isEmpty(formhash)) {
            params.put("formhash", formhash);
        }
        params.put("handlekey", "comiis");
        String response = this.httpClient.post(deleteUrl, params);
        String verifyHtml = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove_verify=" + System.currentTimeMillis());
        boolean stillExists = containsFavoriteTid(verifyHtml, this.tid);
        return !stillExists || containsAny(response, "succeedhandle_favorite_del", "删除成功", "取消收藏成功", "取消成功");
    }

    private boolean containsFavoriteTid(String html, String targetTid) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(targetTid)) {
            return false;
        }
        List<Thread> favorites = ForumParser.parseFavoriteList(html);
        if (favorites != null) {
            for (Thread item : favorites) {
                if (item != null && targetTid.equals(item.getTid())) {
                    return true;
                }
            }
        }
        return Pattern.compile("(?:[?&]tid=" + Pattern.quote(targetTid) + "(?:&|\\\"|')|thread-" + Pattern.quote(targetTid) + "(?:-|\\.))", 2).matcher(html).find();
    }

    private Boolean queryServerFavoriteStateWithRetry(boolean targetState) {
        Boolean lastState = null;
        long[] delays = {0, 250, 600, 1200, 2000};
        for (long delay : delays) {
            if (delay > 0) {
                try {
                    java.lang.Thread.sleep(delay);
                } catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                }
            }
            Boolean detailState = queryServerFavoriteDetailState();
            if (detailState != null) {
                lastState = detailState;
                if (detailState.booleanValue() == targetState) {
                    return detailState;
                }
            }
            Boolean listState = queryServerFavoriteListState();
            if (listState != null) {
                lastState = listState;
                if (listState.booleanValue() == targetState) {
                    return listState;
                }
            }
        }
        return lastState;
    }

    private Boolean queryServerFavoriteDetailState() {
        PostDetail server;
        try {
            String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_verify=" + System.currentTimeMillis());
            if (!ForumParser.isLoginPage(html) && (server = ForumParser.parseThreadDetail(html)) != null && server.isFavoritedStateKnown()) {
                return Boolean.valueOf(server.isFavorited());
            }
        } catch (Exception e) {
        }
        return null;
    }

    private Boolean queryServerFavoriteListState() {
        try {
            String html = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_verify=" + System.currentTimeMillis());
            if (ForumParser.isLoginPage(html)) {
                return null;
            }
            List<Thread> favorites = ForumParser.parseFavoriteList(html);
            boolean z = true;
            if (favorites != null) {
                for (Thread item : favorites) {
                    if (item != null && this.tid.equals(item.getTid())) {
                        return true;
                    }
                }
            }
            if (TextUtils.isEmpty(this.tid) || !Pattern.compile("thread-" + Pattern.quote(this.tid) + "(?:-|\\\\.)", 2).matcher(html).find()) {
                z = false;
            }
            return Boolean.valueOf(z);
        } catch (Exception e) {
            return null;
        }
    }

    private String[] replaceHiddenQuoteWithPlaceholder(String html) {
        int lt;
        int gt;
        if (html == null) {
            return new String[]{"", ""};
        }
        Pattern openP = Pattern.compile("<div\\s+class=\"(?:comiis_quote|locked)[^\"]*\"", 2);
        Matcher m = openP.matcher(html);
        if (!m.find()) {
            return new String[]{html, ""};
        }
        int openQuote = m.start();
        int depth = 0;
        int i = openQuote;
        int end = html.length();
        while (true) {
            if (i >= html.length() || (lt = html.indexOf(60, i)) < 0 || (gt = html.indexOf(62, lt)) < 0) {
                break;
            }
            String tag = html.substring(lt + 1, gt).trim().toLowerCase();
            if (tag.startsWith("/")) {
                if (tag.startsWith("/div") && depth - 1 <= 0) {
                    end = gt + 1;
                    break;
                }
            } else if (tag.startsWith("div")) {
                depth++;
            }
            i = gt + 1;
        }
        if (end >= html.length()) {
            return new String[]{html, ""};
        }
        String block = html.substring(openQuote, end);
        String text = block.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return new String[]{html, ""};
        }
        // 摘要过长时截断,避免胶囊占满整行
        if (text.length() > 28) {
            text = text.substring(0, 28) + "...";
        }
        String clean = html.substring(0, openQuote) + HIDDEN_QUOTE_PLACEHOLDER + html.substring(end);
        return new String[]{clean, text};
    }

    private void applyHiddenNoticeHighlight(CharSequence text, String notice) {
        if ((text instanceof Spannable) && !TextUtils.isEmpty(notice)) {
            Spannable sp = (Spannable) text;
            int idx = sp.toString().indexOf(HIDDEN_QUOTE_PLACEHOLDER);
            if (idx < 0) {
                return;
            }
            sp.setSpan(new HiddenNoticeSpan(notice, dpToPx(9), dpToPx(12), dpToPx(11), -854017, -14721112), idx, HIDDEN_QUOTE_PLACEHOLDER.length() + idx, 33);
        }
    }

    private static class HiddenNoticeSpan extends ReplacementSpan {
        private final int bgColor;
        private final float paddingPx;
        private final float radiusPx;
        private final String text;
        private final int textColor;
        private final float textSizePx;

        HiddenNoticeSpan(String text, float radiusPx, float paddingPx, float textSizePx, int bgColor, int textColor) {
            this.text = text;
            this.radiusPx = radiusPx;
            this.paddingPx = paddingPx;
            this.textSizePx = textSizePx;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }

        @Override // android.text.style.ReplacementSpan
        public int getSize(Paint paint, CharSequence cs, int start, int end, Paint.FontMetricsInt fm) {
            paint.setTextSize(this.textSizePx);
            float w = paint.measureText(this.text);
            if (fm != null) {
                Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
                fm.ascent = fmi.ascent;
                fm.descent = fmi.descent;
                fm.top = fmi.top;
                fm.bottom = fmi.bottom;
            }
            return Math.round((this.paddingPx * 2.0f) + w);
        }

        @Override // android.text.style.ReplacementSpan
        public void draw(Canvas canvas, CharSequence cs, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            paint.setAntiAlias(true);
            int oldColor = paint.getColor();
            paint.setTextSize(this.textSizePx);
            Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
            float textW = paint.measureText(this.text);
            float left = x + 1.0f;
            float right = ((x + textW) + (this.paddingPx * 2.0f)) - 1.0f;
            float rectTop = top + 3.0f;
            float rectBottom = bottom - 3.0f;
            Paint bg = new Paint(1);
            bg.setColor(this.bgColor);
            canvas.drawRoundRect(new RectF(left, rectTop, right, rectBottom), this.radiusPx, this.radiusPx, bg);
            paint.setColor(this.textColor);
            float baseline = (((top + bottom) - fmi.ascent) - fmi.descent) / 2.0f;
            canvas.drawText(this.text, this.paddingPx + left, baseline, paint);
            paint.setColor(oldColor);
        }
    }

    private void adjustEditFooterDividerWidth() {
        this.binding.tvEditFooter.post(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$adjustEditFooterDividerWidth$38();
            }
        });
    }

    private void lambda$adjustEditFooterDividerWidth$38() {
        if (this.binding.tvEditFooter.getVisibility() != 0) {
            return;
        }
        String text = this.binding.tvEditFooter.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        float textW = this.binding.tvEditFooter.getPaint().measureText(text);
        View divider = this.binding.viewEditDivider;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        int parentW = divider.getMeasuredWidth();
        if (parentW <= 0) {
            return;
        }
        int w = (int) Math.min(dpToPx(2) + textW, parentW);
        lp.width = w;
        lp.gravity = 1;
        divider.setLayoutParams(lp);
        View dividerTop = this.binding.viewEditDividerTop;
        LinearLayout.LayoutParams lpTop = (LinearLayout.LayoutParams) dividerTop.getLayoutParams();
        lpTop.width = w;
        lpTop.gravity = 1;
        dividerTop.setLayoutParams(lpTop);
    }

    private String[] splitEditFooter(String html) {
        int endIdx;
        int lt;
        int gt;
        if (html == null) {
            return new String[]{"", ""};
        }
        String marker = "本帖最后由";
        int idx = html.indexOf("本帖最后由");
        if (idx < 0) {
            marker = "本贴最后由";
            idx = html.indexOf("本贴最后由");
        }
        if (idx >= 0 && (endIdx = html.indexOf("编辑", marker.length() + idx)) >= 0) {
            int start = idx;
            while (true) {
                int lt2 = html.lastIndexOf(60, start - 1);
                if (lt2 < 0 || (gt = html.indexOf(62, lt2)) < 0 || gt > start || !html.substring(gt + 1, start).trim().isEmpty()) {
                    break;
                }
                String tag = html.substring(lt2 + 1, gt).trim();
                if (!tag.startsWith("span") && !tag.startsWith("b") && !tag.startsWith("br") && !tag.startsWith("font") && !tag.startsWith("i") && !tag.startsWith("em") && !tag.startsWith("strong")) {
                    break;
                }
                start = lt2;
            }
            int editEnd = endIdx + 2;
            int end = editEnd;
            while (true) {
                int gt2 = html.indexOf(62, end);
                if (gt2 < 0 || (lt = html.lastIndexOf(60, gt2)) < end || !html.substring(end, lt).trim().isEmpty()) {
                    break;
                }
                String tag2 = html.substring(lt + 1, gt2).trim();
                if (!tag2.startsWith("/span") && !tag2.startsWith("/b") && !tag2.startsWith("/font") && !tag2.startsWith("/i") && !tag2.startsWith("/em") && !tag2.startsWith("/strong") && !tag2.startsWith("br") && !tag2.endsWith("/")) {
                    break;
                }
                end = gt2 + 1;
            }
            String footer = html.substring(idx, editEnd).replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
            String clean = html.substring(0, start) + html.substring(end);
            return new String[]{clean, footer};
        }
        return new String[]{html, ""};
    }

    private void renderHiddenContent(String hiddenHtml) {
        if (this.binding == null || TextUtils.isEmpty(hiddenHtml)) {
            return;
        }
        this.binding.tvHiddenContent.setVisibility(0);
        String bbcodeConverted = BBCodeUtil.convertBBCodeToHtml(hiddenHtml);
        List<String> hiddenImageUrls = new ArrayList<>();
        String cleanHiddenHtml = extractAndSeparateImages(bbcodeConverted, hiddenImageUrls);
        this.binding.tvHiddenContent.setText(Html.fromHtml(cleanHiddenHtml, 63, createInlineImageGetter(this.binding.tvHiddenContent), com.solosu.mtforum.util.BBCodeUtil.createTagHandler(this)));
        setupClickableLinks(this.binding.tvHiddenContent);
        for (final String imgUrl : hiddenImageUrls) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-2, dpToPx(ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION)));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ((LinearLayout.LayoutParams) imageView.getLayoutParams()).setMargins(dpToPx(4), 0, dpToPx(4), 0);
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$renderHiddenContent$39(imgUrl, view);
                }
            });
            Glide.with((FragmentActivity) this).load(imgUrl).placeholder(new ColorDrawable(getColor(R.color.background_secondary))).error((Drawable) new ColorDrawable(getColor(R.color.divider))).into(imageView);
            this.binding.llImageGallery.addView(imageView);
        }
        this.binding.cardImageGallery.setVisibility(0);
        FrostedGlassHelper.applyToCardViews(this.binding.cardImageGallery, this);
    }

    private void lambda$renderHiddenContent$39(String imgUrl, View v) {
        openImagePreview(imgUrl);
    }
private void viewHiddenContent() {
        if (this.postDetail == null || !this.postDetail.isHasHiddenContent()) {
            return;
        }
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        this.binding.tvHiddenContentHint.setVisibility(8);
        this.binding.btnViewHidden.setVisibility(8);
        if (!TextUtils.isEmpty(this.postDetail.getHiddenContentHtml())) {
            renderHiddenContent(this.postDetail.getHiddenContentHtml());
        } else {
            Toast.makeText(this, R.string.hidden_content_prompt, 0).show();
        }
    }

    /** 已自动解锁过的 tid，避免同一页面反复触发 */
    private final java.util.Set<String> autoUnlockTried = new java.util.HashSet<>();
    private boolean autoUnlocking = false;

    /**
     * 进入帖子发现是「回复可见」时，后台自动回复一次以解锁，成功后刷新隐藏内容区。
     * 受 AiConfigManager.isUnlockOnView 开关控制，且同一帖子只尝试一次。
     */
    private void maybeAutoUnlock() {
        if (this.postDetail == null) {
            AiLog.i("auto-unlock", "跳过：详情未就绪");
            return;
        }
        final String tid = this.tid;
        if (!com.solosu.mtforum.ai.AiConfigManager.isUnlockOnView(this)) {
            AiLog.i("auto-unlock", "跳过：进帖自动解锁开关关闭 tid=" + tid);
            return;
        }
        if (TextUtils.isEmpty(tid)) {
            AiLog.i("auto-unlock", "跳过：tid 为空");
            return;
        }
        if (autoUnlocking) {
            AiLog.i("auto-unlock", "跳过：本轮正在解锁中 tid=" + tid);
            return;
        }
        if (autoUnlockTried.contains(tid)) {
            AiLog.i("auto-unlock", "跳过：本页已尝试过 tid=" + tid);
            return;
        }
        if (!this.httpClient.isLoggedIn()) {
            this.httpClient.syncFromCookieManager();
            if (!this.httpClient.isLoggedIn()) {
                AiLog.i("auto-unlock", "跳过：未登录 tid=" + tid);
                return;
            }
        }
        // 注意：进帖自动解锁是用户明确开启的动作，不能再被「演练模式」拦掉，
        // 否则会出现「开关明明开着、却一直不解锁」的错觉。
        if (com.solosu.mtforum.ai.AiConfigManager.isDryRun(this)) {
            AiLog.i("auto-unlock", "提示：演练模式开着，但进帖解锁不受它影响，继续执行 tid=" + tid);
        }
        autoUnlocking = true;
        autoUnlockTried.add(tid);
        AiLog.i("auto-unlock", "→ 进入帖子发现隐藏内容，尝试自动回复解锁 tid=" + tid);

        new java.lang.Thread(() -> {
            boolean ok = false;
            try {
                ok = com.solosu.mtforum.ai.AutoReplyEngine.unlockSingleThread(
                        ThreadDetailActivity.this, tid);
            } catch (Exception e) {
                android.util.Log.w("ThreadDetail", "auto unlock failed", e);
                AiLog.e("auto-unlock", "解锁异常 tid=" + tid + " " + e);
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                autoUnlocking = false;
                AiLog.i("auto-unlock", "本轮结束 tid=" + tid + " 成功=" + success);
                if (success) {
                    Toast.makeText(ThreadDetailActivity.this,
                            "已自动回复并解锁隐藏内容", Toast.LENGTH_SHORT).show();
                    refreshPostDetail();
                }
            });
        }, "auto-unlock").start();
    }


    private void loadMoreReplies() {
        if (this.postDetail == null || this.isLoadingMore) {
            return;
        }
        this.isLoadingMore = true;
        this.binding.btnLoadMore.setEnabled(false);
        this.binding.btnLoadMore.setText(R.string.loading);
        this.binding.loadingMore.setVisibility(View.VISIBLE);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$loadMoreReplies$42();
            }
        }).start();
    }

    private void lambda$loadMoreReplies$42() {
        try {
            int totalPages = this.postDetail.getTotalPages();
            final List<ReplyItem> allNewReplies = new ArrayList<>();
            for (int page = this.postDetail.getCurrentPage() + 1; allNewReplies.size() < 10 && page <= totalPages; page++) {
                String pageUrl = ForumParser.getThreadDetailUrl(this.tid, page, getReplyOrder());
                String html = this.httpClient.get(pageUrl);
                PostDetail pageDetail = ForumParser.parseThreadDetail(html);
                List<ReplyItem> pageReplies = pageDetail.getReplies();
                if (pageReplies != null && !pageReplies.isEmpty()) {
                    allNewReplies.addAll(pageReplies);
                }
                this.postDetail.setCurrentPage(pageDetail.getCurrentPage());
                if (pageDetail.getTotalPages() > totalPages) {
                    int totalPages2 = pageDetail.getTotalPages();
                    this.postDetail.setTotalPages(totalPages2);
                    totalPages = totalPages2;
                }
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadMoreReplies$40(allNewReplies);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadMoreReplies$41(e);
                }
            });
        }
    }

    private void lambda$loadMoreReplies$40(List allNewReplies) {
        this.isLoadingMore = false;
        this.binding.btnLoadMore.setEnabled(true);
        this.binding.btnLoadMore.setText(R.string.load_more_replies);
        this.binding.loadingMore.setVisibility(View.GONE);
        if (!allNewReplies.isEmpty()) {
            List<ReplyItem> merged = new ArrayList<>(this.postDetail.getReplies());
            merged.addAll(allNewReplies);
            this.postDetail.setReplies(merged);
            this.displayedReplies = new ArrayList(merged);
            updateReplyFilterAndOrder();
        } else {
            Toast.makeText(this, R.string.no_more_replies, 0).show();
        }
        this.binding.btnLoadMore.setVisibility(8);
    }

    private void lambda$loadMoreReplies$41(Exception e) {
        this.binding.btnLoadMore.setEnabled(true);
        this.binding.btnLoadMore.setText(R.string.load_more_replies);
        this.binding.loadingMore.setVisibility(View.GONE);
        Toast.makeText(this, "加载失败: " + e.getMessage(), 0).show();
    }


    private void lambda$new$43(java.util.List<android.net.Uri> uris) {
        if (uris != null && !uris.isEmpty()) {
            addPendingImages(uris);
        }
    }

    private boolean restoreLikedState() {
        if (TextUtils.isEmpty(this.tid)) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_LIKE_FAV, 0);
        return prefs.getBoolean(KEY_LIKED_PREFIX + this.tid, false);
    }

    private void saveLikedState(boolean liked) {
        if (TextUtils.isEmpty(this.tid)) {
            return;
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit().putBoolean(KEY_LIKED_PREFIX + this.tid, liked).apply();
    }

    private boolean restoreFavoritedState() {
        if (TextUtils.isEmpty(this.tid)) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_LIKE_FAV, 0);
        return prefs.getBoolean(KEY_FAVORITED_PREFIX + this.tid, false);
    }

    private void saveFavoritedState(boolean favorited) {
        if (TextUtils.isEmpty(this.tid)) {
            return;
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit().putBoolean(KEY_FAVORITED_PREFIX + this.tid, favorited).apply();
    }

    private boolean isReplyResponseSuccessful(String response) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response)) {
            return false;
        }
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(response, "请先登录", "formhash错误", "非法操作", "没有权限", "回复失败", "附件上传失败", "附件不存在", "上传图片失败")) {
            return false;
        }
        if (lower.contains("succeedhandle_reply") || lower.contains("succeedhandle_post") || lower.contains("succeedhandle_fastpost") || lower.contains("succeedhandle_fastposts") || lower.contains("reply_success") || lower.contains("回复发布成功") || lower.contains("发布成功")) {
            return true;
        }
        return Pattern.compile("[?&](?:tid|pid)=\\d+", 2).matcher(response).find();
    }

    private String extractReplyError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "回复失败,服务器未返回结果";
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录")) {
            return "登录状态已失效,请重新登录";
        }
        if (containsAny(response, "formhash错误", "非法操作")) {
            return "验证已失效,请刷新页面后重试";
        }
        if (containsAny(response, "附件上传失败", "附件不存在", "上传图片失败")) {
            return "图片附件关联失败,请重新上传后再发送";
        }
        return "回复发布失败,请稍后重试";
    }

    private void showReplyFailure(final String message) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$showReplyFailure$44(message);
            }
        });
    }

    private void lambda$showReplyFailure$44(String message) {
        Toast.makeText(this, message, 0).show();
    }

    private void showKickDialog() {
        performKick();
    }

    private void toggleLike() {
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(this.tid) || !this.binding.btnLike.isEnabled()) {
            return;
        }
        String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
        String authorUid = this.postDetail != null ? this.postDetail.getAuthorUid() : null;
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
            Toast.makeText(this, "不能点赞自己的帖子", 0).show();
            return;
        }
        final boolean targetState = !this.isLiked;
        final boolean oldState = this.isLiked;
        final int oldCount = this.likeCount;
        this.isLiked = targetState;
        this.likeCount = Math.max(0, this.likeCount + (targetState ? 1 : -1));
        updateLikeIcon();
        this.binding.btnLike.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleLike$46(targetState, oldState, oldCount);
            }
        }).start();
    }

    private void lambda$toggleLike$46(final boolean targetState, final boolean oldState, final int oldCount) {
        boolean success;
        String errorMessage;
        boolean success2 = false;
        try {
            this.httpClient.syncFromCookieManager();
            String pageHtml = this.httpClient.getDesktop(ForumParser.getThreadDetailUrl(this.tid) + "&_action_refresh=" + System.currentTimeMillis());
            String formhash = this.postDetail != null ? this.postDetail.getFormhash() : null;
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(pageHtml);
            }
            String recommendUrl = extractRecommendActionUrl(pageHtml);
            if (TextUtils.isEmpty(recommendUrl)) {
                if (!TextUtils.isEmpty(formhash)) {
                    recommendUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=recommend&handlekey=recommend_add&do=add&tid=" + this.tid + "&hash=" + formhash;
                } else {
                    throw new IllegalStateException("无法获取formhash");
                }
            }
            String result = this.httpClient.get(appendQuery(recommendUrl, "inajax=1"));
            if (!targetState) {
                boolean alreadyLiked = containsAny(result, "您已评价过本主题", "您已经评价过本主题", "已经评价过本主题");
                if (alreadyLiked || isLikeAddResponseSuccessful(result)) {
                    String cancelUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=re_recommend&tid=" + this.tid + "&inajax=1";
                    String cancelResult = this.httpClient.get(cancelUrl);
                    success2 = (ForumParser.isLoginPage(cancelResult) || containsAny(cancelResult, "没有权限", "操作失败", "非法操作", "请先登录")) ? false : true;
                }
            } else {
                success2 = isLikeAddResponseSuccessful(result);
            }
            Boolean serverState = queryServerLikeState();
            if (serverState != null) {
                success2 = serverState.booleanValue() == targetState;
            }
            String errorMessage2 = success2 ? null : "网页端未确认点赞状态已更新";
            success = success2;
            errorMessage = errorMessage2;
        } catch (Exception e) {
            String errorMessage3 = e.getMessage();
            success = false;
            errorMessage = errorMessage3;
        }
        final boolean success3 = success;
        final String finalError = errorMessage;
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleLike$45(success3, targetState, oldState, oldCount, finalError);
            }
        });
    }

    private void lambda$toggleLike$45(boolean finalSuccess, boolean targetState, boolean oldState, int oldCount, String finalError) {
        this.binding.btnLike.setEnabled(true);
        if (finalSuccess) {
            saveLikedState(targetState);
            if (this.postDetail != null) {
                this.postDetail.setLiked(targetState);
                this.postDetail.setLikedStateKnown(true);
            }
            Toast.makeText(this, targetState ? "已点赞" : "已取消点赞", 0).show();
            return;
        }
        this.isLiked = oldState;
        this.likeCount = oldCount;
        updateLikeIcon();
        Toast.makeText(this, TextUtils.isEmpty(finalError) ? "点赞操作失败，请稍后重试" : finalError, 0).show();
    }

    private Boolean queryServerLikeState() {
        try {
            String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_action_refresh=" + System.currentTimeMillis());
            PostDetail server = ForumParser.parseThreadDetail(html);
            if (server == null || !server.isLikedStateKnown()) {
                return null;
            }
            return Boolean.valueOf(server.isLiked());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractAndSeparateImages(String html, List<String> imageUrls) {
        String fullUrl;
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        try {
            Document doc = Jsoup.parse(html);
            doc.select("script").remove();
            doc.select("style").remove();
            doc.select("ignore_js_op").remove();
            doc.select("*:matchesOwn(^border\\s*=\\s*[\"']?\\d)").remove();
            Elements imgs = doc.select("img");
            for (Element img : imgs) {
                String realUrl = null;
                if (img.hasAttr("file") && !TextUtils.isEmpty(img.attr("file"))) {
                    realUrl = img.attr("file");
                } else if (img.hasAttr("comiis_loadimages") && !TextUtils.isEmpty(img.attr("comiis_loadimages"))) {
                    realUrl = img.attr("comiis_loadimages");
                } else if (img.hasAttr("data-original") && !TextUtils.isEmpty(img.attr("data-original"))) {
                    realUrl = img.attr("data-original");
                } else if (img.hasAttr("data-src") && !TextUtils.isEmpty(img.attr("data-src"))) {
                    realUrl = img.attr("data-src");
                } else if (img.hasAttr("data-file") && !TextUtils.isEmpty(img.attr("data-file"))) {
                    realUrl = img.attr("data-file");
                } else if (img.hasAttr("src") && !TextUtils.isEmpty(img.attr("src"))) {
                    realUrl = img.attr("src");
                }
                if (realUrl != null && !realUrl.isEmpty() && (fullUrl = normalizeImageUrl(realUrl)) != null && !fullUrl.contains("smiley") && !fullUrl.contains("emoticon") && !fullUrl.contains("face") && !fullUrl.contains("/static/image/smiley") && !fullUrl.contains("stamp") && !fullUrl.contains("magic") && !fullUrl.contains("mini") && !fullUrl.contains("icon") && !fullUrl.contains("none.gif") && !fullUrl.contains("common_") && !imageUrls.contains(fullUrl)) {
                    imageUrls.add(fullUrl);
                }
            }
            doc.select("img").remove();
            String cleanedText = doc.body().html();
            return cleanedText.replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*'[^']*'", "").replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*\"[^\"]*\"", "").replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*[^;\\s<]+", "").replaceAll("\\s*border\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("\\s*alt\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("\\s*title\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("<[^>]*>\\s*<", "<");
        } catch (Exception e) {
            return fallbackExtractImages(html, imageUrls);
        }
    }

    private String fallbackExtractImages(String html, List<String> imageUrls) {
        String cleaned = html.replaceAll("(?i)<script[^>]*>.*?</script>", "").replaceAll("(?i)<style[^>]*>.*?</style>", "");
        Pattern imgPattern = Pattern.compile("<img[^>]*(?:file|comiis_loadimages|data-original|data-src|data-file|src)=[\"']([^\"']+)[\"']", 2);
        Matcher matcher = imgPattern.matcher(cleaned);
        while (matcher.find()) {
            String url = matcher.group(1);
            String fullUrl = normalizeImageUrl(url);
            if (fullUrl != null && !fullUrl.contains("smiley") && !fullUrl.contains("face") && !fullUrl.contains("emoticon") && !fullUrl.contains("icon") && !fullUrl.contains("none.gif") && !fullUrl.contains("common_") && !imageUrls.contains(fullUrl)) {
                imageUrls.add(fullUrl);
            }
        }
        return cleaned.replaceAll("(?i)<img[^>]*>", "");
    }

    private static String normalizeImageUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1);
        }
        if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return HttpClient.BASE_URL + url;
    }

    private void setupClickableLinks(TextView textView) {
        Spannable spannable;
        if (textView == null) {
            return;
        }
        textView.setTextIsSelectable(true);
        textView.setFocusable(true);
        textView.setClickable(true);
        textView.setLongClickable(true);
        textView.setHighlightColor(857839347);
        CharSequence value = textView.getText();
        if (value instanceof Spannable) {
            spannable = (Spannable) value;
        } else {
            spannable = new SpannableString(value == null ? "" : value);
            textView.setText(spannable, TextView.BufferType.SPANNABLE);
        }
        final int linkColor = getColor(R.color.link_color);
        Pattern urlPattern = Pattern.compile("(?<!\\w)(?:https?://[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+|www\\.[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+)(?<![,.;:!?)>])", 34);
        FixNestedScrollLinkMovementMethod.matcherLinkify(spannable, urlPattern, new Function<String, String>() {
            @Override // java.util.function.Function
            public final String apply(String obj) {
                return ThreadDetailActivity.this.lambda$setupClickableLinks$47(obj);
            }
        }, new Consumer<String>() {
            @Override // java.util.function.Consumer
            public final void accept(String obj) {
                ThreadDetailActivity.this.lambda$setupClickableLinks$48(obj);
            }
        });
        URLSpan[] urlSpans = (URLSpan[]) spannable.getSpans(0, spannable.length(), URLSpan.class);
        for (URLSpan oldSpan : urlSpans) {
            final String targetUrl = lambda$setupClickableLinks$47(oldSpan.getURL());
            int start = spannable.getSpanStart(oldSpan);
            int end = spannable.getSpanEnd(oldSpan);
            int flags = spannable.getSpanFlags(oldSpan);
            spannable.removeSpan(oldSpan);
            if (start >= 0 && end > start && !TextUtils.isEmpty(targetUrl)) {
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        ThreadDetailActivity.this.lambda$setupClickableLinks$48(targetUrl);
                    }
                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(linkColor);
                        ds.setUnderlineText(true);
                    }
                }, start, end, flags);
            }
        }
        textView.setMovementMethod(new FixNestedScrollLinkMovementMethod());
        textView.setAutoLinkMask(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String lambda$setupClickableLinks$47(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1);
        }
        if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("www.")) {
            // 用 https：本应用已移除 usesCleartextTraffic，明文请求会被系统直接拦掉
            return "https://" + url;
        }
        return HttpClient.BASE_URL + url;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void lambda$setupClickableLinks$48(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        try {
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            boolean isForumLink = lower.contains("bbs.binmt.cc");

            if (isForumLink) {
                // 论坛帖子链接: thread-{tid}-1-1.html 或 forum.php?mod=viewthread&tid={tid}
                Matcher threadMatcher = Pattern.compile("thread[-=]?(\\d+)").matcher(lower);
                if (threadMatcher.find()) {
                    String tid = threadMatcher.group(1);
                    NavigationHelper.openThread(this, tid);
                    return;
                }

                // 论坛分区链接: forum-{fid}-1.html 或 forum.php?mod=forumdisplay&fid={fid}
                Matcher forumMatcher = Pattern.compile("(?:forum-|(?<=[?&])fid=)(\\d+)").matcher(lower);
                if (forumMatcher.find()) {
                    Toast.makeText(this, "论坛分区链接", Toast.LENGTH_SHORT).show();
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browser);
                    return;
                }

                // 用户空间链接: space-uid-{uid}.html 或 home.php?mod=space&uid={uid} 或 space-username-{username}.html
                Matcher uidMatcher = Pattern.compile("(?:uid[-=]|(?<=[?&])uid=)(\\d+)").matcher(lower);
                if (uidMatcher.find()) {
                    String uid = uidMatcher.group(1);
                    Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                    intent.putExtra("uid", uid);
                    startActivity(intent);
                    return;
                }
                Matcher usernameMatcher = Pattern.compile("space-username-([^./?&]+)").matcher(lower);
                if (usernameMatcher.find()) {
                    String username = usernameMatcher.group(1);
                    Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                    intent.putExtra("username", username);
                    startActivity(intent);
                    return;
                }
            }

            // 非论坛链接或无法识别的论坛链接,用浏览器打开
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browser);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isValidUploadUid(String uid) {
        if (TextUtils.isEmpty(uid)) {
            return false;
        }
        try {
            return Long.parseLong(uid) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void applyServerActionState(PostDetail detail) {
        if (detail == null) {
            return;
        }
        if (detail.isLikedStateKnown()) {
            this.isLiked = detail.isLiked();
            saveLikedState(this.isLiked);
        } else {
            this.isLiked = restoreLikedState();
        }
        if (detail.isFavoritedStateKnown()) {
            this.isFavorited = detail.isFavorited();
            saveFavoritedState(this.isFavorited);
        } else {
            this.isFavorited = restoreFavoritedState();
        }
    }

    private void syncFavoriteStateFromServer(PostDetail detail) {
        if (detail == null || TextUtils.isEmpty(this.tid) || !this.httpClient.isLoggedIn()) {
            return;
        }
        try {
            String html = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&mobile=2&_refresh=" + System.currentTimeMillis());
            if (ForumParser.isLoginPage(html)) {
                return;
            }
            List<Thread> favorites = ForumParser.parseFavoriteList(html);
            boolean found = false;
            if (favorites != null) {
                Iterator<Thread> it = favorites.iterator();
                while (true) {
                    if (!it.hasNext()) {
                        break;
                    }
                    Thread item = it.next();
                    if (item != null && this.tid.equals(item.getTid())) {
                        found = true;
                        break;
                    }
                }
            }
            detail.setFavorited(found);
            detail.setFavoritedStateKnown(true);
        } catch (Exception e) {
        }
    }

    private void refreshServerActionState(PostDetail detail) {
        if (detail == null) {
            return;
        }
        applyServerActionState(detail);
        syncFavoriteStateFromServer(detail);
        applyServerActionState(detail);
    }

    /** 绑定点赞人头像行(登录态才有数据;未登录/无数据时隐藏) */
    private void bindLikeUsers(PostDetail postDetail) {
        if (likeUsersAdapter == null) {
            likeUsersAdapter = new LikeUsersAdapter((uid, name) -> {
                Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                intent.putExtra(ChatActivity.EXTRA_UID, uid);
                intent.putExtra("username", name != null ? name : "");
                startActivity(intent);
            });
            this.binding.rvLikeUsers.setLayoutManager(
                    new androidx.recyclerview.widget.LinearLayoutManager(
                            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
            this.binding.rvLikeUsers.setAdapter(likeUsersAdapter);
            this.binding.tvLikeMore.setOnClickListener(v -> showLikeUsersSheet(postDetail));
            this.binding.layoutLikeUsers.setOnClickListener(v -> showLikeUsersSheet(postDetail));
        }
        List<String> uids = postDetail.getLikeUserUids();
        List<String> avatars = postDetail.getLikeUserAvatars();
        List<String> names = postDetail.getLikeUserNames();
        if (uids != null && !uids.isEmpty()) {
            likeUsersAdapter.setData(uids, avatars, names);
            this.binding.layoutLikeUsers.setVisibility(0);
        } else {
            this.binding.layoutLikeUsers.setVisibility(8);
        }
    }

    /** 底部弹层:全部点赞人(头像+用户名,可滚动) */
    private void showLikeUsersSheet(PostDetail postDetail) {
        List<String> uids = postDetail.getLikeUserUids();
        List<String> avatars = postDetail.getLikeUserAvatars();
        List<String> names = postDetail.getLikeUserNames();
        if (uids == null || uids.isEmpty()) {
            Toast.makeText(this, "暂无点赞数据", Toast.LENGTH_SHORT).show();
            return;
        }
        // 防空兜底: PostDetail 三字段默认 null(ForumParser 只在非空时才 set),
        // 不兜底时 names.size()/avatars.size() 会 NPE, 表现为"查看全部"闪退回主页。
        if (names == null) names = new java.util.ArrayList<>();
        if (avatars == null) avatars = new java.util.ArrayList<>();
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);
        // 标题
        TextView title = new TextView(this);
        title.setText("赞过此帖的人 (" + uids.size() + ")");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dpToPx(12));
        root.addView(title);
        // 滚动容器
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        ScrollView.LayoutParams sp = new ScrollView.LayoutParams(-1, -1);
        scroll.setLayoutParams(sp);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        // build67: 收集每行引用, 供接口回填真实昵称/头像
        java.util.List<TextView> nameViews = new java.util.ArrayList<>();
        java.util.List<com.google.android.material.imageview.ShapeableImageView> imgViews = new java.util.ArrayList<>();
        java.util.List<LinearLayout> rowViews = new java.util.ArrayList<>();
        for (int i = 0; i < uids.size(); i++) {
            final String uid = uids.get(i);
            String name = i < names.size() && names.get(i) != null && !names.get(i).isEmpty()
                    ? names.get(i) : "用户" + uid;
            final String avatar = i < avatars.size() ? avatars.get(i) : null;
            // build65: 每行 = 头像 + 名字(原来只有 TextView,头像数据白拿)
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));
            com.google.android.material.imageview.ShapeableImageView iv =
                    new com.google.android.material.imageview.ShapeableImageView(this);
            int av = dpToPx(36);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(av, av);
            ivLp.setMarginEnd(dpToPx(10));
            iv.setLayoutParams(ivLp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(R.drawable.ic_account);
            if (!TextUtils.isEmpty(avatar)) {
                com.bumptech.glide.Glide.with(this).load(avatar).circleCrop()
                        .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE0E0E0))
                        .error(new android.graphics.drawable.ColorDrawable(0xFFBDBDBD))
                        .into(iv);
            }
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(15);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(iv);
            row.addView(tv);
            row.setOnClickListener(v -> {
                sheet.dismiss();
                Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                intent.putExtra(ChatActivity.EXTRA_UID, uid);
                intent.putExtra("username", name);
                startActivity(intent);
            });
            list.addView(row);
            rowViews.add(row);
            imgViews.add(iv);
            nameViews.add(tv);
        }
        sheet.setContentView(root);
        sheet.show();
        // build67: 弹窗先用详情页数据即时渲染, 后台拉独立接口补"真实昵称+头像"
        // (接口免登录、一次性返回全部点赞人, 只在用户点"查看全部"时发 1 发)
        final String tidForLikers = this.tid;
        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<com.solosu.mtforum.ui.detail.LikeUserFetcher.Item> items =
                        com.solosu.mtforum.ui.detail.LikeUserFetcher.fetch(tidForLikers);
                if (items == null || items.isEmpty()) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        for (int k = 0; k < rowViews.size() && k < items.size(); k++) {
                            com.solosu.mtforum.ui.detail.LikeUserFetcher.Item it = items.get(k);
                            nameViews.get(k).setText(it.name);
                            if (!TextUtils.isEmpty(it.avatar)) {
                                com.bumptech.glide.Glide.with(ThreadDetailActivity.this)
                                        .load(it.avatar).circleCrop()
                                        .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE0E0E0))
                                        .error(new android.graphics.drawable.ColorDrawable(0xFFBDBDBD))
                                        .into(imgViews.get(k));
                            }
                        }
                    }
                });
            }
        }, "like-user-fetch").start();
    }

    /** 当前帖全部图片(正文+附件+隐藏区,按 bindData 收集顺序) */
    private java.util.List<String> currentImageList = new ArrayList<>();

    private void openImagePreview(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        Intent intent = new Intent(this, (Class<?>) ImagePreviewActivity.class);
        // 多图: 传整个图组+当前图位置,可左右翻页
        java.util.List<String> list = new ArrayList<>(this.currentImageList);
        if (list.isEmpty() || !list.contains(url)) {
            list.add(url);
        }
        if (list.size() > 1) {
            intent.putStringArrayListExtra("image_urls", new ArrayList<>(list));
            intent.putExtra("image_index", list.indexOf(url));
        } else {
            intent.putExtra("image_url", url);
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片", 0).show();
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setData(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }
    /** 添加待发送图片到列表,显示预览,并开始逐张上传 */
    private void addPendingImages(java.util.List<android.net.Uri> uris) {
        for (android.net.Uri uri : uris) {
            if (!pendingImageUris.contains(uri)) {
                pendingImageUris.add(uri);
                uploadPendingImage(uri);
            }
        }
        refreshAllImagePreviews();
    }

    /** 删除待发送图片,移除对应的 [attachimg] 标签 */
    private void removePendingImage(android.net.Uri uri) {
        pendingImageUris.remove(uri);
        String aid = uploadedAidMap.remove(uri);
        if (aid != null) {
            String tag = "[attachimg]" + aid + "[/attachimg]";
            if (binding != null) {
                String cur = binding.etReply.getText().toString();
                cur = cur.replace(tag, "");
                binding.etReply.setText(cur);
                binding.etReply.setSelection(binding.etReply.length());
            }
            synchronized (pendingUploadAids) {
                pendingUploadAids.remove(aid);
            }
        }
        refreshAllImagePreviews();
    }

    /** 刷新所有图片预览(底部回复栏 + 弹窗) */
    private void refreshAllImagePreviews() {
        updateInlineImagePreview();
        if (mBottomSheetDialog != null && mBottomSheetDialog.isShowing()) {
            updateDialogImagePreview();
        }
    }

    /** 更新底部回复栏的图片预览 */
    private void updateInlineImagePreview() {
        if (binding == null) return;
        android.widget.HorizontalScrollView hsv = binding.getRoot().findViewById(R.id.hsv_inline_image_preview);
        android.widget.LinearLayout ll = binding.getRoot().findViewById(R.id.ll_inline_image_preview);
        if (hsv == null || ll == null) return;
        if (pendingImageUris.isEmpty()) {
            hsv.setVisibility(android.view.View.GONE);
            return;
        }
        hsv.setVisibility(android.view.View.VISIBLE);
        ll.removeAllViews();
        for (android.net.Uri uri : pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri));
        }
    }

    /** 更新底部弹窗的图片预览 */
    private void updateDialogImagePreview() {
        if (mBottomSheetDialog == null) return;
        android.widget.HorizontalScrollView hsv = mBottomSheetDialog.findViewById(R.id.hsv_image_preview);
        android.widget.LinearLayout ll = mBottomSheetDialog.findViewById(R.id.ll_image_preview);
        if (hsv == null || ll == null) return;
        if (pendingImageUris.isEmpty()) {
            hsv.setVisibility(android.view.View.GONE);
            return;
        }
        hsv.setVisibility(android.view.View.VISIBLE);
        ll.removeAllViews();
        for (android.net.Uri uri : pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri));
        }
    }

    /** 构建单个图片缩略图(含右上角删除按钮) */
    private android.view.View buildPreviewThumbnail(android.net.Uri uri) {
        int size = dpToPx(72);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        frame.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));

        com.google.android.material.imageview.ShapeableImageView iv = new com.google.android.material.imageview.ShapeableImageView(this);
        iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size - dpToPx(4), size - dpToPx(4)));
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setShapeAppearanceModel(com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dpToPx(6))
                .build());
        com.bumptech.glide.Glide.with(this).load(uri).centerCrop().into(iv);
        frame.addView(iv);

        int btnSize = dpToPx(22);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(btnSize, btnSize);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        android.widget.ImageButton btnDelete = new android.widget.ImageButton(this);
        btnDelete.setLayoutParams(lp);
        btnDelete.setImageResource(R.drawable.ic_cross);
        btnDelete.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        btnDelete.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        btnDelete.setColorFilter(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setSize(btnSize, btnSize);
        btnDelete.setBackground(bg);
        btnDelete.setOnClickListener(v -> removePendingImage(uri));
        frame.addView(btnDelete);

        return frame;
    }

    /** 上传单张待发送图片,上传成功后 aid 存入 uploadedAidMap */
    private void uploadPendingImage(android.net.Uri uri) {
        if (imageUploadInProgress) {
            synchronized (imageUploadPendingQueue) {
                if (!imageUploadPendingQueue.contains(uri)) imageUploadPendingQueue.add(uri);
            }
            return;
        }
        imageUploadInProgress = true;
        new java.lang.Thread(() -> {
            try {
                java.io.File file = transcodeReplyImageToJpeg(uri);
                if (file == null || !file.exists() || file.length() == 0) {
                    runOnUiThread(() -> {
                        imageUploadInProgress = false;
                        drainUploadQueue();
                        Toast.makeText(ThreadDetailActivity.this, "图片读取失败,请换一张试试", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                if (!httpClient.isLoggedIn()) {
                    httpClient.syncFromCookieManager();
                    if (!httpClient.isLoggedIn()) {
                        runOnUiThread(() -> { imageUploadInProgress = false; });
                        return;
                    }
                }
                String detailHtml = httpClient.getDesktop(com.solosu.mtforum.network.ForumParser.getThreadDetailUrl(tid));
                String uid = extractUploadValue(detailHtml, "discuz_uid");
                String hash = extractUploadValue(detailHtml, "hash");
                if (!isValidUploadUid(uid)) uid = null;
                if (android.text.TextUtils.isEmpty(uid) || android.text.TextUtils.isEmpty(hash)) {
                    String fid = extractForumFid(detailHtml);
                    if (android.text.TextUtils.isEmpty(fid)) fid = "39";
                    String postHtml = httpClient.getDesktop(com.solosu.mtforum.network.HttpClient.BASE_URL
                            + "forum.php?mod=post&action=newthread&fid=" + fid);
                    if (android.text.TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid");
                    if (!isValidUploadUid(uid)) uid = null;
                    if (android.text.TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash");
                }
                if (!isValidUploadUid(uid) || android.text.TextUtils.isEmpty(hash)) {
                    runOnUiThread(() -> {
                        imageUploadInProgress = false;
                        drainUploadQueue();
                        Toast.makeText(ThreadDetailActivity.this, "图片上传授权失败,请重新登录后重试", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                java.util.Map<String, String> extra = new java.util.HashMap<>();
                extra.put("uid", uid);
                extra.put("hash", hash);
                String url = com.solosu.mtforum.network.HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload"
                        + "&type=image&inajax=yes&infloat=yes&simple=2";
                String result = httpClient.uploadFileWithUserAgent(url, file, "Filedata", extra,
                        com.solosu.mtforum.network.HttpClient.DESKTOP_USER_AGENT, "image/jpeg");
                String aid = parseUploadAid(result);
                if (!android.text.TextUtils.isEmpty(aid)) {
                    synchronized (pendingUploadAids) {
                        if (!pendingUploadAids.contains(aid)) pendingUploadAids.add(aid);
                    }
                    uploadedAidMap.put(uri, aid);
                    String tag = "\n[attachimg]" + aid + "[/attachimg]";
                    runOnUiThread(() -> {
                        if (binding != null) {
                            binding.etReply.append(tag);
                        }
                    });
                }
            } catch (Exception e) {
            } finally {
                runOnUiThread(() -> {
                    imageUploadInProgress = false;
                    drainUploadQueue();
                });
            }
        }).start();
    }

    /** build71: 上一张传完后,从排队队列里取下一张继续上传 */
    private void drainUploadQueue() {
        android.net.Uri next;
        synchronized (imageUploadPendingQueue) {
            if (imageUploadInProgress || imageUploadPendingQueue.isEmpty()) return;
            next = imageUploadPendingQueue.remove(0);
        }
        uploadPendingImage(next);
    }

    /** 构建回复文本中的 [attachimg] 标签前缀 */
    private String buildAttachTags() {
        StringBuilder sb = new StringBuilder();
        synchronized (pendingUploadAids) {
            for (String aid : pendingUploadAids) {
                sb.append("[attachimg]").append(aid).append("[/attachimg]\n");
            }
        }
        return sb.toString();
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT_THREAD && resultCode == RESULT_OK) {
            // build73: 编辑保存成功 -> 重新拉一次帖子
            refreshPostDetail();
            return;
        }
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            java.util.List<android.net.Uri> imageUris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) imageUris.add(uri);
                }
            } else if (data.getData() != null) {
                imageUris.add(data.getData());
            }
            if (!imageUris.isEmpty()) {
                addPendingImages(imageUris);
            }
        }
    }



    /* JADX WARN: Removed duplicated region for block: B:36:0x0054  */
    /* JADX WARN: Removed duplicated region for block: B:43:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
        private String getDisplayNameFromUri(Uri uri) {
        if (uri == null) return null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getLastPathSegment();
        return TextUtils.isEmpty(path) ? null : path;
    }

    private String buildUploadFileName(String sourceName, String mimeType) {
        String name = TextUtils.isEmpty(sourceName) ? "" : sourceName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || !name.matches("(?i).*\\.[a-z0-9]{2,5}$")) {
            String extension = extensionFromMimeType(mimeType);
            return "reply_" + System.currentTimeMillis() + extension;
        }
        return name;
    }

    private String extensionFromMimeType(String mimeType) {
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        if ("image/gif".equalsIgnoreCase(mimeType)) {
            return ".gif";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return ".webp";
        }
        if ("image/bmp".equalsIgnoreCase(mimeType)) {
            return ".bmp";
        }
        if ("image/heic".equalsIgnoreCase(mimeType) || "image/heif".equalsIgnoreCase(mimeType)) {
            return ".heic";
        }
        return ".jpg";
    }

        private void uploadAndAttachImage(final Uri imageUri) {
        if (imageUploadInProgress) {
            Toast.makeText(this, "已有图片正在上传,请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingImageUri = imageUri;
        imageUploadInProgress = true;
        Toast.makeText(this, "正在上传图片...", Toast.LENGTH_SHORT).show();
        new java.lang.Thread(() -> {
            lambda$uploadAndAttachImage$51(imageUri);
        }).start();
    }

    private void lambda$uploadAndAttachImage$51(Uri imageUri) {
        File file = null;
        try {
            if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager();
            if (!httpClient.isLoggedIn()) {
                runOnUiThread(() -> promptLogin());
                return;
            }
            file = transcodeReplyImageToJpeg(imageUri);
            if (file == null || !file.exists() || file.length() == 0) {
                showUploadError("无法读取或转换图片文件");
                return;
            }
            String detailHtml = httpClient.getDesktop(ForumParser.getThreadDetailUrl(tid));
            String uid = extractUploadValue(detailHtml, "discuz_uid");
            String hash = extractUploadValue(detailHtml, "hash");
            if (!isValidUploadUid(uid)) uid = null;
            if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(hash)) {
                String fid = extractForumFid(detailHtml);
                if (TextUtils.isEmpty(fid)) fid = "39";
                String postHtml = httpClient.getDesktop(HttpClient.BASE_URL
                        + "forum.php?mod=post&action=newthread&fid=" + fid);
                if (TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid");
                if (!isValidUploadUid(uid)) uid = null;
                if (TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash");
            }
            if (!isValidUploadUid(uid) || TextUtils.isEmpty(hash)) {
                showUploadError("获取图片上传授权失败,请重新登录后重试");
                return;
            }
            Map<String, String> extra = new HashMap<>();
            extra.put("uid", uid);
            extra.put("hash", hash);
            String url = HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload"
                    + "&type=image&inajax=yes&infloat=yes&simple=2";
            String result = httpClient.uploadFileWithUserAgent(url, file, "Filedata", extra,
                    HttpClient.DESKTOP_USER_AGENT, "image/jpeg");
            String aid = parseUploadAid(result);
            if (TextUtils.isEmpty(aid)) {
                showUploadError(extractUploadError(result));
                return;
            }
            final String finalAid = aid;
            synchronized (pendingUploadAids) {
                if (!pendingUploadAids.contains(finalAid)) pendingUploadAids.add(finalAid);
            }
            runOnUiThread(() -> {
                binding.etReply.append("\n[attachimg]" + finalAid + "[/attachimg]");
                Toast.makeText(this, "图片已上传,发送评论后才会正式关联", Toast.LENGTH_SHORT).show();
                imageUploadInProgress = false;
            });
        } catch (Exception e) {
            showUploadError("图片上传失败:" + (TextUtils.isEmpty(e.getMessage())
                    ? "网络异常,请稍后重试" : e.getMessage()));
        } finally {
            if (file != null) file.delete();
            runOnUiThread(() -> { imageUploadInProgress = false; });
        }
    }


    private String extractUploadError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "图片上传失败，服务器未返回结果";
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录", "登录")) {
            return "登录状态已失效，请重新登录";
        }
        String text = response.replaceAll("(?s)<[^>]+>", " ").trim();
        if (text.startsWith("DISCUZUPLOAD|")) {
            String[] parts = text.split("\\|", -1);
            if (parts.length > 3) {
                String error = parts[parts.length - 1].trim();
                if (!TextUtils.isEmpty(error) && !error.matches("\\d+")) {
                    return "图片上传失败：" + error;
                }
                return "图片上传失败，请检查图片格式、大小和登录状态";
            }
            return "图片上传失败，请检查图片格式、大小和登录状态";
        }
        return "图片上传失败，请检查图片格式、大小和登录状态";
    }

    private void lambda$showUploadError$52(String message) {
        Toast.makeText(this, message, 0).show();
    }

    private void showUploadError(final String message) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$showUploadError$52(message);
            }
        });
    }

    private String extractForumFid(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        Matcher m = Pattern.compile("(?:forum-|[?&]fid=)(\\d+)").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String extractUploadValue(String html, String key) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        String quotedKey = Pattern.quote(key);
        Matcher m = Pattern.compile("(?:var\\s+|\\b)" + quotedKey + "\\s*(?:=|:)\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        Matcher m2 = Pattern.compile("\\\"" + quotedKey + "\\\"\\s*:\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m2.find()) {
            return m2.group(1);
        }
        Matcher m3 = Pattern.compile("<input[^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"][^>]+value\\s*=\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m3.find()) {
            return m3.group(1);
        }
        Matcher m4 = Pattern.compile("<input[^>]+value\\s*=\\s*['\"]([^'\"]+)['\"][^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"]", 2).matcher(html);
        if (m4.find()) {
            return m4.group(1);
        }
        Matcher m5 = Pattern.compile("[?&]" + quotedKey + "=([^&\"'<>\\s]+)", 2).matcher(html);
        if (m5.find()) {
            return m5.group(1);
        }
        return null;
    }

    private String parseUploadAid(String response) {
        if (TextUtils.isEmpty(response)) {
            return null;
        }
        String text = response.trim();
        if (text.isEmpty()) {
            return null;
        }
        String text2 = text.replaceAll("(?s)<[^>]+>", "").trim();
        if (text2.toUpperCase(java.util.Locale.ROOT).startsWith("DISCUZUPLOAD|")) {
            String[] parts = text2.split("\\|", -1);
            if (parts.length >= 4 && "0".equals(parts[2]) && parts[3].matches("\\d+")) {
                return parts[3];
            }
            if (parts.length >= 3 && "0".equals(parts[1]) && parts[2].matches("\\d+")) {
                return parts[2];
            }
            Matcher mPipe = Pattern.compile("(?i)DISCUZUPLOAD\\|[^|]*\\|0\\|([^|]+)").matcher(text2);
            if (mPipe.find() && mPipe.group(1).matches("\\d+")) {
                return mPipe.group(1);
            }
        }
        Matcher m = Pattern.compile("(?:aid|attach)(?:Id)?[\\s:='\"]+(\\d+)", 2).matcher(text2);
        if (m.find()) {
            return m.group(1);
        }
        Matcher jsonLike = Pattern.compile("\\\"(?:aid|attach)(?:Id)?\\\"\\s*:\\s*(\\d+)", 2).matcher(text2);
        if (jsonLike.find()) {
            return jsonLike.group(1);
        }
        return null;
    }

    private File transcodeReplyImageToJpeg(Uri uri) throws Exception {
        if (uri == null) {
            return null;
        }
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap decoded = ImageDecoder.decodeBitmap(source, new ImageDecoder.OnHeaderDecodedListener() {
            @Override // android.graphics.ImageDecoder.OnHeaderDecodedListener
            public final void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source2) {
                ThreadDetailActivity.lambda$transcodeReplyImageToJpeg$53(imageDecoder, imageInfo, source2);
            }
        });
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            return null;
        }
        Bitmap flattened = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(-1);
        canvas.drawBitmap(decoded, 0.0f, 0.0f, (Paint) null);
        if (flattened != decoded) {
            decoded.recycle();
        }
        byte[] jpeg = null;
        for (int quality = 88; quality >= 58; quality -= 6) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            flattened.compress(Bitmap.CompressFormat.JPEG, quality, output);
            jpeg = output.toByteArray();
            if (jpeg.length <= 2097152 || quality == 58) {
                break;
            }
        }
        flattened.recycle();
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        File dir = new File(getCacheDir(), "reply_uploads");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, "reply_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream output2 = new FileOutputStream(target);
        try {
            output2.write(jpeg);
            output2.flush();
            output2.close();
            return target;
        } catch (Throwable th) {
            try {
                output2.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    static /* synthetic */ void lambda$transcodeReplyImageToJpeg$53(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source ignoredSource) {
        int width = info.getSize().getWidth();
        int height = info.getSize().getHeight();
        int largest = Math.max(width, height);
        if (largest > 1920) {
            float scale = 1920.0f / largest;
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
        }
        decoder.setAllocator(1);
    }

    private File copyUriToTempFile(Uri uri, String fileName) throws Exception {
        File dir = new File(getCacheDir(), "reply_uploads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, fileName);
        InputStream in = getContentResolver().openInputStream(uri);
        try {
            FileOutputStream out = new FileOutputStream(file);
            if (in != null) {
                try {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        int len = in.read(buffer);
                        if (len == -1) {
                            break;
                        }
                        out.write(buffer, 0, len);
                    }
                    out.close();
                    if (in != null) {
                        in.close();
                    }
                    return file;
                } finally {
                }
            }
            out.close();
            if (in != null) {
                in.close();
                return null;
            }
            return null;
        } catch (Throwable th) {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void shareThread() {
        if (this.postDetail == null) {
            return;
        }
        String shareText = this.postDetail.getTitle() + "\n" + HttpClient.BASE_URL + "thread-" + this.tid + "-1-1.html";
        Intent shareIntent = new Intent("android.intent.action.SEND");
        // 原为 androidx.webkit.internal.AssetHelper.DEFAULT_MIME_TYPE，
        // 该值就是 "text/plain"；为一个字符串常量引入整个 androidx.webkit 依赖不划算，
        // 且 AssetHelper 在 internal 包下，非公开 API，库升级可能直接删掉。
        shareIntent.setType("text/plain");
        shareIntent.putExtra("android.intent.extra.TEXT", shareText);
        startActivity(Intent.createChooser(shareIntent, "分享帖子"));
    }

    private void showRewardDialog() {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        // build74b: 目标可以是主楼(空)或某条评论(评论菜单已排除本人,无需再判)
        final boolean isReplyTarget = !TextUtils.isEmpty(rewardTargetPid);
        if (!isReplyTarget) {
            String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            String authorUid = postDetail != null ? postDetail.getAuthorUid() : null;
            if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
                Toast.makeText(this, "不能给自己打赏", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_reward, null);
        dialog.setContentView(view);
        MaterialCardView cardReward = view.findViewById(R.id.card_reward);
        if (cardReward != null) FrostedGlassHelper.applyToCardViews(cardReward, this);
        dialog.setOnShowListener(d -> {
            View parent = (View) view.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                behavior.setPeekHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.5));
            }
        });
        ImageView ivAvatar = view.findViewById(R.id.iv_reward_author_avatar);
        TextView tvAuthorName = view.findViewById(R.id.tv_reward_author_name);
        TextView tvHint = view.findViewById(R.id.tv_reward_hint);
        final Spinner spinnerAmount = view.findViewById(R.id.spinner_reward_amount);
        final SwitchCompat switchNotify = view.findViewById(R.id.switch_notify_author);
        Button btnSubmit = view.findViewById(R.id.btn_submit_reward);
        String displayName = !TextUtils.isEmpty(rewardTargetName)
                ? rewardTargetName
                : (postDetail != null ? postDetail.getAuthor() : "");
        String displayAvatar = !TextUtils.isEmpty(rewardTargetAvatar)
                ? rewardTargetAvatar
                : (postDetail != null ? postDetail.getAvatarUrl() : null);
        if (!TextUtils.isEmpty(displayName)) {
            tvAuthorName.setText(displayName);
            tvHint.setText("给 " + displayName + " 打赏鼓励吧");
            if (!TextUtils.isEmpty(displayAvatar)) {
                Glide.with(this).load(displayAvatar).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account);
            }
        }
        final String[] amounts = {"1", "5", "10", "50", "100"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, amounts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAmount.setAdapter(adapter);
        final EditText etRewardMessage = view.findViewById(R.id.et_reward_message);
        btnSubmit.setOnClickListener(v -> {
            int selectedAmount = Integer.parseInt(amounts[spinnerAmount.getSelectedItemPosition()]);
            boolean notifyAuthor = switchNotify.isChecked();
            String message = etRewardMessage.getText().toString().trim();
            dialog.dismiss();
            performReward(selectedAmount, notifyAuthor, "", message);
        });
        dialog.show();
    }

    // ==================== build73: 编辑 / 举报 / 删除 ====================

    /** 当前登录 uid(带缓存) */
    private String loginUid() {
        if (currentLoginUid == null) {
            currentLoginUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            if (TextUtils.isEmpty(currentLoginUid)) currentLoginUid = "";
        }
        return currentLoginUid;
    }

    /** 是否本人在看自己的帖子 */
    private boolean isOwnThread(PostDetail d) {
        if (d == null) return false;
        String me = loginUid();
        if (TextUtils.isEmpty(me)) return false;
        if (!TextUtils.isEmpty(d.getAuthorUid()) && me.equals(d.getAuthorUid())) return true;
        return false;
    }

    /** 打开编辑页(复用发帖页,编辑模式) */
    private void openEditThread() {
        if (postDetail == null) return;
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(postDetail.getPostPid())) {
            Toast.makeText(this, "缺少帖子编号,无法编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.solosu.mtforum.ui.post.PostActivity.class);
        it.putExtra("edit_tid", tid);
        it.putExtra("edit_pid", postDetail.getPostPid());
        it.putExtra("edit_fid", postDetail.getForumFid());
        it.putExtra("edit_forum_name", postDetail.getForumName());
        it.putExtra("edit_title", postDetail.getTitle());
        it.putExtra("edit_message", stripContentHtml(postDetail.getContentHtml()));
        startActivityForResult(it, REQUEST_EDIT_THREAD);
    }

    /** 正文 HTML -> 可编辑的 BBCode/纯文本(交给 BBCodeUtil 反向处理,失败则剥标签) */
    private String stripContentHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        // build73c: BBCodeUtil 没有 html->bbcode 的反解能力,改为剥标签但保留附件标记
        String attachmentMarks = "";
        try {
            java.util.regex.Matcher am = java.util.regex.Pattern.compile(
                    "(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]").matcher(html);
            StringBuilder amsb = new StringBuilder();
            while (am.find()) amsb.append("\n").append(am.group());
            attachmentMarks = amsb.toString();
        } catch (Exception ignored) {
        }
        String t = html.replaceAll("(?is)<br\\s*/?>", "\n");
        t = t.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        t = t.replaceAll("(?is)<[^>]+>", "");
        t = android.text.Html.fromHtml(t).toString().trim();
        return (t + attachmentMarks).trim();
    }

    /** build77: 举报入口 -> 弹理由输入窗(常用理由下拉 + 补充说明) */
    private void reportPost(final String pid) {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        final boolean isThread = TextUtils.isEmpty(pid);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_report, null);
        dialog.setContentView(content);
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            win.setAttributes(lp);
        }
        TextView tvTarget = content.findViewById(R.id.tv_report_target);
        tvTarget.setText(isThread ? "举报对象：本帖" : "举报对象：该评论");
        final com.google.android.material.chip.ChipGroup cgReason = content.findViewById(R.id.cg_report_reason);
        content.findViewById(R.id.btn_close_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        final EditText etMessage = content.findViewById(R.id.et_report_message);
        content.findViewById(R.id.btn_cancel_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        content.findViewById(R.id.btn_submit_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String reason = "其他";
                int checkedId = cgReason.getCheckedChipId();
                View checkedView = checkedId != View.NO_ID ? cgReason.findViewById(checkedId) : null;
                if (checkedView instanceof com.google.android.material.chip.Chip) {
                    reason = ((com.google.android.material.chip.Chip) checkedView).getText().toString();
                }
                String msg = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
                dialog.dismiss();
                submitReport(pid, reason, msg);
            }
        });
        dialog.show();
    }

    /** build77: 举报提交(理由/说明由弹窗传入) */
    private void submitReport(final String pid, final String reason, final String userMessage) {
        final String rtype = TextUtils.isEmpty(pid) ? "thread" : "post";
        final String rid = TextUtils.isEmpty(pid) ? tid : pid;
        final String finalPid = pid;
        final String finalReason = TextUtils.isEmpty(reason) ? "其他" : reason;
        final String finalMessage = TextUtils.isEmpty(userMessage) ? "该内容涉嫌违规，请核实处理。" : userMessage;
        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (TextUtils.isEmpty(tid)) throw new IllegalStateException("缺少帖子编号");
                    String fid = postDetail != null && !TextUtils.isEmpty(postDetail.getForumFid())
                            ? postDetail.getForumFid() : "39";
                    String formUrl = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype
                            + "&rid=" + rid + "&tid=" + tid + "&fid=" + fid + "&inajax=1&mobile=2";
                    String form = httpClient.get(formUrl);
                    String fh = ForumParser.parseFormhash(form);
                    if (TextUtils.isEmpty(fh) && postDetail != null) fh = postDetail.getFormhash();
                    if (TextUtils.isEmpty(fh)) throw new IllegalStateException("获取操作验证失败");

                    Map<String, String> params = new HashMap<>();
                    params.put("formhash", fh);
                    if ("thread".equals(rtype)) {
                        params.put("tid", tid);
                    } else {
                        params.put("tid", tid);
                        params.put("pid", finalPid);
                    }
                    params.put("fid", fid);
                    params.put("rtype", rtype);
                    params.put("rid", rid);
                    params.put("reportsubmit", "yes");
                    params.put("reason", finalReason);
                    params.put("message", finalMessage);
                    String url = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype
                            + "&rid=" + rid + "&tid=" + tid + "&fid=" + fid + "&reportsubmit=yes&inajax=1&mobile=2";
                    String resp = httpClient.post(url, params);

                    boolean ok = resp != null && !ForumParser.isLoginPage(resp)
                            && !resp.contains("举报理由") && !resp.contains("action=login");
                    final String msg = ok ? "举报已提交，感谢反馈"
                            : "举报未成功，请稍后重试";
                    runOnUiThread(() -> Toast.makeText(ThreadDetailActivity.this, msg, Toast.LENGTH_SHORT).show());
                } catch (final Exception e) {
                    runOnUiThread(() -> Toast.makeText(ThreadDetailActivity.this,
                            "举报失败：" + (TextUtils.isEmpty(e.getMessage()) ? "网络异常" : e.getMessage()),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    /** 删除自己的回复 */
    private void deleteReply(final ReplyItem item) {
        if (item == null || TextUtils.isEmpty(item.getPid())) return;
        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String pid = item.getPid();
                    String formUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&tid=" + tid + "&pid=" + pid + "&mobile=2";
                    String form = httpClient.get(formUrl);
                    String fh = ForumParser.parseFormhash(form);
                    if (TextUtils.isEmpty(fh) && postDetail != null) fh = postDetail.getFormhash();
                    // build73c: 编辑页 HTML 里带该楼专用删除校验哈希,优先用它
                    String delHash = extractDeleteHash(form, pid);
                    if (!TextUtils.isEmpty(delHash)) fh = delHash;
                    if (TextUtils.isEmpty(fh)) throw new IllegalStateException("获取操作验证失败");

                    Map<String, String> params = new HashMap<>();
                    params.put("formhash", fh);
                    params.put("delete", "1");
                    params.put("pid", pid);
                    params.put("tid", tid);
                    String url = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&tid=" + tid
                            + "&pid=" + pid + "&delete=1&deletesubmit=yes&mobile=2";
                    String resp = httpClient.post(url, params);
                    runOnUiThread(() -> {
                        Toast.makeText(ThreadDetailActivity.this, "已删除该回复", Toast.LENGTH_SHORT).show();
                        refreshPostDetail();
                    });
                } catch (final Exception e) {
                    runOnUiThread(() -> Toast.makeText(ThreadDetailActivity.this,
                            "删除失败：" + (TextUtils.isEmpty(e.getMessage()) ? "网络异常" : e.getMessage()),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    /** build73c: 从编辑页 HTML 抽取该楼删除用的校验哈希(找不到返回 null,退回通用 formhash) */
    private String extractDeleteHash(String html, String pid) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(pid)) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "formhash=([0-9a-zA-Z]+)[^\"'<>]{0,200}?pid=" + pid).matcher(html);
            if (m.find()) return m.group(1);
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                    "pid=" + pid + "[^\"'<>]{0,200}?formhash=([0-9a-zA-Z]+)").matcher(html);
            if (m2.find()) return m2.group(1);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 评论长按菜单 */
    /** build75: 评论操作菜单(自定义卡片弹窗:图标+文字行) */
    private void showReplyActionMenu(final ReplyItem item) {
        if (item == null) return;
        final String author = TextUtils.isEmpty(item.getAuthor()) ? "匿名" : item.getAuthor();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_action_menu, null);
        dialog.setContentView(content);
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            win.setAttributes(lp);
        }
        TextView tvMenuTitle = content.findViewById(R.id.tv_menu_title);
        tvMenuTitle.setText(author + " · 评论操作");
        LinearLayout container = content.findViewById(R.id.ll_menu_items);

        addActionRow(container, R.drawable.ic_reply, "回复", false, new Runnable() {
            @Override public void run() {
                currentReplyPid = item.getPid();
                currentReplyTarget = "回复 " + author + "：";
                showReplyBottomSheet("");
            }
        }, dialog);
        if (!isOwnReply(item)) {
            addActionRow(container, R.drawable.ic_reward, "打赏", false, new Runnable() {
                @Override public void run() {
                    rewardTargetPid = item.getPid();
                    rewardTargetName = author;
                    rewardTargetAvatar = item.getAvatarUrl();
                    showRewardDialog();
                }
            }, dialog);
            addActionRow(container, R.drawable.ic_flag, "举报", false, new Runnable() {
                @Override public void run() {
                    reportPost(item.getPid());
                }
            }, dialog);
        } else {
            addActionRow(container, R.drawable.ic_delete, "删除", true, new Runnable() {
                @Override public void run() {
                    confirmDeleteReply(item);
                }
            }, dialog);
        }
        addActionRow(container, 0, "取消", false, null, dialog);
        dialog.show();
    }

    /** build75: 菜单行(图标+文字),action==null 视为取消 */
    private void addActionRow(LinearLayout container, int iconRes, final String label,
                              boolean danger, final Runnable action, final Dialog dialog) {
        View row = getLayoutInflater().inflate(R.layout.item_action_menu, container, false);
        ImageView iv = row.findViewById(R.id.iv_action_icon);
        TextView tv = row.findViewById(R.id.tv_action_label);
        if (iconRes != 0) {
            iv.setImageResource(iconRes);
            if (danger) iv.setColorFilter(0xFFE53935);
        } else {
            iv.setVisibility(View.GONE);
        }
        tv.setText(label);
        if (danger) tv.setTextColor(0xFFE53935);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                if (action != null) action.run();
            }
        });
        container.addView(row);
    }

    private boolean isOwnReply(ReplyItem item) {
        if (item == null) return false;
        String me = loginUid();
        if (TextUtils.isEmpty(me)) return false;
        return me.equals(item.getAuthorUid());
    }

    private void confirmDeleteReply(final ReplyItem item) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除回复")
                .setMessage("确定要删除这条回复吗？删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> deleteReply(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void performReward(final int amount, boolean notifyAuthor, final String goodReview, final String message) {
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    ThreadDetailActivity.this.lambda$performReward$57(amount, message, goodReview);
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void lambda$performReward$57(final int amount, String message, String goodReview) throws Exception {
        try {
            if (this.postDetail == null) {
                throw new IllegalStateException("帖子数据为空");
            }
            String pid = TextUtils.isEmpty(rewardTargetPid) ? this.postDetail.getPostPid() : rewardTargetPid;
            // 用掉即清(下次默认主楼)
            rewardTargetPid = "";
            rewardTargetName = "";
            rewardTargetAvatar = "";
            if (TextUtils.isEmpty(pid)) {
                String page = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                PostDetail latest = ForumParser.parseThreadDetail(page);
                if (latest != null) {
                    pid = latest.getPostPid();
                    if (!TextUtils.isEmpty(latest.getFormhash())) {
                        this.postDetail.setFormhash(latest.getFormhash());
                    }
                }
            }
            if (TextUtils.isEmpty(pid)) {
                throw new IllegalStateException("无法获取帖子正文编号");
            }
            String rateForm = "";
            try {
                String rateUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + this.tid + "&pid=" + pid + "&showratetip=1&inajax=1&mobile=2";
                rateForm = this.httpClient.get(rateUrl);
            } catch (Exception e) {
            }
            String fh = ForumParser.parseFormhash(rateForm);
            if (TextUtils.isEmpty(fh)) {
                fh = this.postDetail.getFormhash();
            }
            if (TextUtils.isEmpty(fh)) {
                String page2 = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                fh = ForumParser.parseFormhash(page2);
            }
            if (TextUtils.isEmpty(fh)) {
                throw new IllegalStateException("无法获取操作验证");
            }
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("tid", this.tid);
            params.put("pid", pid);
            params.put("ratesubmit", "yes");
            params.put("referer", ForumParser.getThreadDetailUrl(this.tid));
            params.put("score1", "1");
            params.put("score2", String.valueOf(amount));
            String reason = message;
            if (TextUtils.isEmpty(reason)) {
                reason = goodReview;
            } else if (!TextUtils.isEmpty(goodReview)) {
                reason = goodReview + "：" + reason;
            }
            if (!TextUtils.isEmpty(reason)) {
                params.put("reason", reason);
            }
            String rewardUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + this.tid + "&pid=" + pid + "&ratesubmit=yes&inajax=1&mobile=2";
            String result = this.httpClient.post(rewardUrl, params);
            final boolean success = isForumActionResponseSuccessful(result);
            final String rewardError = success ? "" : extractRewardError(result);
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$performReward$55(success, amount, rewardError);
                }
            });
        } catch (Exception e2) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$performReward$56();
                }
            });
        }
    }

    private void lambda$performReward$55(boolean success, int amount, String rewardError) {
        String string;
        if (success) {
            Toast.makeText(this, getString(R.string.reward_success, new Object[]{Integer.valueOf(amount)}), 0).show();
            refreshPostDetail();
        } else {
            if (TextUtils.isEmpty(rewardError)) {
                string = getString(R.string.reward_failed);
            } else {
                string = rewardError;
            }
            Toast.makeText(this, string, 1).show();
        }
    }

    private void lambda$performReward$56() {
        Toast.makeText(this, R.string.reward_failed, 0).show();
    }

    private boolean isForumActionResponseSuccessful(String response) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response) || isRewardLimitOrFailure(response)) {
            return false;
        }
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("succeedhandle_rate") || lower.contains("rate_success") || containsAny(response, "评分成功", "打赏成功", "您已成功评分", "评价成功");
    }

    private boolean isRewardLimitOrFailure(String response) {
        return containsAny(response, "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次", "每 24 小时只能评分一次", "24小时内已经评分", "24 小时内已经评分", "您已评价过本主题", "您已经评价过本主题", "您已评分过本主题", "您已经评分过本主题", "已经评分过", "已经评价过", "重复评分", "评分过本主题", "thread_rate_duplicate", "rate_duplicate", "评分失败", "评分范围错误", "thread_rate_range_invalid", "credit_limit_invalid", "积分不足", "余额不足", "提交频率过快", "操作频繁", "暂不支持高级操作", "formhash错误", "非法操作", "没有权限", "请先登录", "未定义操作", "undefined action", "操作失败");
    }

    private String extractRewardError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "打赏失败，服务器未返回结果";
        }
        if (!ForumParser.isLoginPage(response) && !containsAny(response, "请先登录")) {
            if (!containsAny(response, "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次", "每 24 小时只能评分一次", "24小时内已经评分", "24 小时内已经评分", "重复评分", "已评价过本主题", "已评分过本主题", "已经评分过", "已经评价过", "评分过本主题")) {
                if (!containsAny(response, "积分不足", "余额不足", "credit_limit_invalid")) {
                    if (!containsAny(response, "formhash错误", "非法操作")) {
                        if (!containsAny(response, "没有权限", "暂不支持高级操作")) {
                            if (containsAny(response, "提交频率过快", "操作频繁")) {
                                return "操作过于频繁，请稍后再试";
                            }
                            return "打赏失败，请重试";
                        }
                        return "当前账号没有评分权限";
                    }
                    return "验证已失效，请刷新页面后重试";
                }
                return "积分余额不足，无法完成打赏";
            }
            return "24小时内只能对同一帖子评分一次，请稍后再试";
        }
        return "登录状态已失效，请重新登录";
    }

    private void submitKickRequest(final String reason) {
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    ThreadDetailActivity.this.lambda$submitKickRequest$60(reason);
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void lambda$submitKickRequest$60(String reason) {
        try {
            String page = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
            String fh = ForumParser.parseFormhash(page);
            if (TextUtils.isEmpty(fh) && this.postDetail != null) {
                fh = this.postDetail.getFormhash();
            }
            if (TextUtils.isEmpty(fh)) {
                throw new IllegalStateException("无法获取操作验证");
            }
            String kickUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=kick&tid=" + this.tid + "&formhash=" + fh + "&inajax=1&mobile=2";
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("tid", this.tid);
            params.put("kick_submit", "yes");
            params.put("inajax", "1");
            if (!TextUtils.isEmpty(reason)) {
                params.put("kick_reason", reason);
            }
            String result = this.httpClient.post(kickUrl, params);
            final boolean success = isForumActionResponseSuccessful(result);
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$submitKickRequest$58(success);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$submitKickRequest$59();
                }
            });
        }
    }

    private void lambda$submitKickRequest$58(boolean success) {
        if (success) {
            Toast.makeText(this, R.string.kick_success, 0).show();
            refreshPostDetail();
        } else {
            Toast.makeText(this, R.string.kick_failed, 0).show();
        }
    }

    private void lambda$submitKickRequest$59() {
        Toast.makeText(this, R.string.kick_failed, 0).show();
    }

    private void performKick() {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
        String authorUid = postDetail != null ? postDetail.getAuthorUid() : null;
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
            Toast.makeText(this, "不能踢自己的帖子", Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_kick, null);
        dialog.setContentView(view);
        dialog.setCancelable(true);
        MaterialCardView cardKick = view.findViewById(R.id.card_kick);
        if (cardKick != null) FrostedGlassHelper.applyToCardViews(cardKick, this);
        dialog.setOnShowListener(d -> {
            View parent = (View) view.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                behavior.setPeekHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.5));
            }
        });
        final EditText etKickReason = view.findViewById(R.id.et_kick_reason);
        Button btnCloseKick = view.findViewById(R.id.btn_close_kick);
        Button btnSubmitKick = view.findViewById(R.id.btn_submit_kick);
        btnCloseKick.setOnClickListener(v -> dialog.dismiss());
        btnSubmitKick.setOnClickListener(v -> {
            String reason = etKickReason.getText().toString().trim();
            if (TextUtils.isEmpty(reason)) {
                etKickReason.setError("请输入踢帖理由");
            } else {
                dialog.dismiss();
                submitKickRequest(reason);
            }
        });
        dialog.show();
    }

    private void showEmojiPanel() {
        // 自绘制快速回复图标,替代 emoji
        int[] iconIds = {
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        };
        String[] labels = {"微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶"};
        int dp4 = dpToPx(4);
        int dp8 = dpToPx(8);
        RecyclerView rvEmoji = new RecyclerView(this);
        rvEmoji.setLayoutManager(new GridLayoutManager(this, 5));
        rvEmoji.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LinearLayout ll = new LinearLayout(parent.getContext());
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER);
                ll.setPadding(dp8, dp8, dp8, dp8);
                int size = dpToPx(48);
                ll.setLayoutParams(new RecyclerView.LayoutParams(size, size));

                ImageView iv = new ImageView(parent.getContext());
                iv.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setId(View.generateViewId());
                ll.addView(iv);

                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(9f);
                tv.setTextColor(0xFF9CA3AF);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setId(View.generateViewId());
                ll.addView(tv);

                return new RecyclerView.ViewHolder(ll) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                LinearLayout ll = (LinearLayout) holder.itemView;
                ImageView iv = (ImageView) ll.getChildAt(0);
                TextView tv = (TextView) ll.getChildAt(1);
                iv.setImageResource(iconIds[position]);
                tv.setText(labels[position]);
                ll.setOnClickListener(v -> {
                    int pos = binding.etReply.getSelectionStart();
                    String text = binding.etReply.getText().toString();
                    String tag = "[" + labels[position] + "]";
                    binding.etReply.setText(text.substring(0, pos) + tag + text.substring(pos));
                    binding.etReply.setSelection(pos + tag.length());
                });
            }

            @Override
            public int getItemCount() {
                return iconIds.length;
            }
        });
        PopupWindow popup = new PopupWindow(rvEmoji, -1, dpToPx(160), true);
        popup.setBackgroundDrawable(new ColorDrawable(getColor(R.color.background_primary)));
        popup.setOutsideTouchable(true);
        popup.setElevation(dpToPx(8));
        popup.showAtLocation(binding.getRoot(), Gravity.BOTTOM, 0, 0);
    }

    /** 内嵌图片 getter：UrlDrawable 占位 + Glide 异步回填（修复旧空壳实现图片不显示问题） */
    private Html.ImageGetter createInlineImageGetter(TextView textView) {
        return source -> {
            String imgUrl = normalizeImageUrl(source);
            if (imgUrl == null) {
                imgUrl = source;
            }
            final TextView tv = textView;
            final int maxW = Math.max(dpToPx(200),
                    (int) (tv.getWidth() > 0 ? tv.getWidth() * 0.92f
                            : getResources().getDisplayMetrics().widthPixels * 0.92f));
            final com.solosu.mtforum.util.UrlDrawable placeholder =
                    new com.solosu.mtforum.util.UrlDrawable(tv, dpToPx(120));
            com.bumptech.glide.Glide.with(this)
                    .load(imgUrl)
                    .into(new com.bumptech.glide.request.target.CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(Drawable resource,
                                com.bumptech.glide.request.transition.Transition<? super Drawable> transition) {
                            int w = resource.getIntrinsicWidth();
                            int h = resource.getIntrinsicHeight();
                            if (w <= 0) {
                                w = maxW;
                            }
                            if (h <= 0) {
                                h = maxW;
                            }
                            if (w > maxW) {
                                h = (int) ((long) h * maxW / Math.max(1, w));
                                w = maxW;
                            }
                            resource.setBounds(0, 0, w, h);
                            placeholder.setReal(resource, tv);
                        }

                        @Override
                        public void onLoadCleared(Drawable ph) {
                        }
                    });
            return placeholder;
        };
    }


    /** 安全解析 HTML,捕获 SpannableStringBuilder 的 PARAGRAPH 边界崩溃 */
    private android.text.Spanned safeFromHtml(String html, android.text.Html.ImageGetter imageGetter, android.text.Html.TagHandler tagHandler) {
        if (android.text.TextUtils.isEmpty(html)) {
            return android.text.SpannedString.valueOf("");
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
        } catch (Exception e) {
            android.util.Log.w("ThreadDetail", "Html.fromHtml failed, retrying with COMPACT mode", e);
            try {
                return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler);
            } catch (Exception e2) {
                android.util.Log.w("ThreadDetail", "Html.fromHtml COMPACT also failed, stripping paragraph tags", e2);
                // 移除可能导致段落边界问题的标签
                String stripped = html
                        .replaceAll("<div[^>]*>", "")
                        .replaceAll("</div>", "<br>")
                        .replaceAll("<p[^>]*>", "")
                        .replaceAll("</p>", "<br>")
                        .replaceAll("<li[^>]*>", "• ")
                        .replaceAll("</li>", "<br>")
                        .replaceAll("<ol[^>]*>", "")
                        .replaceAll("</ol>", "")
                        .replaceAll("<ul[^>]*>", "")
                        .replaceAll("</ul>", "");
                try {
                    return Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
                } catch (Exception e3) {
                    android.util.Log.e("ThreadDetail", "All Html.fromHtml attempts failed", e3);
                    return android.text.SpannedString.valueOf(android.text.Html.fromHtml(android.text.TextUtils.htmlEncode(html)));
                }
            }
        }
    }
    private int dpToPx(int dp) {
        return (int) ((dp * getResources().getDisplayMetrics().density) + 0.5f);
    }
}
