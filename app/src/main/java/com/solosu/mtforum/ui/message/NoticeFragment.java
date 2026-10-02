package com.solosu.mtforum.ui.message;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.FragmentNoticeBinding;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.network.NoticeBadgeManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.space.FriendListActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 消息页(ViewPager 页)。
 * “消息”是底部导航页;“我的消息”是本页面中的私信分类,二者不能混淆。
 * 所有分类通过 HttpClient 请求网页端数据接口/HTML,再由原生布局渲染,绝不使用 WebView 套壳。
 */
public class NoticeFragment extends Fragment {
    private static final int REQUEST_CODE_DETAIL = 1001;

    private FragmentNoticeBinding binding;
    private TextView badgeMessages, badgeFans, badgePosts, badgeInteractive, badgeSystem, badgeApp;
    private TextView tvClearAll;
    private HttpClient httpClient;
    private Handler mainHandler;
    private ExecutorService executor;
    private static final long BADGE_RESUME_THROTTLE_MS = 60000; // build68: 六类角标 onResume 拉取节流
    private long lastBadgeLoadAt = 0L; // build68: 上次六类角标拉取时间戳(节流用)

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNoticeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        httpClient = HttpClient.getInstance();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newFixedThreadPool(4);

        initViews();
        setupClickListeners();
        // 每次进入消息页都以服务器当前数据为准,不能使用本地 all_read 状态跳过请求。
        loadAllBadgeCounts();
    }

    @Override
    public void onResume() {
        super.onResume();
        // build68: 加 60 秒节流——频繁返回不再重复全量拉 6 类(防 ESA 403)。
        long now = System.currentTimeMillis();
        if (now - lastBadgeLoadAt < BADGE_RESUME_THROTTLE_MS) {
            return;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (executor != null && !executor.isShutdown()) {
            loadAllBadgeCounts();
        }
    }

    private void initViews() {
        badgeMessages = binding.badgeMessages;
        badgeFans = binding.badgeFans;
        badgePosts = binding.badgePosts;
        badgeInteractive = binding.badgeInteractive;
        badgeSystem = binding.badgeSystem;
        badgeApp = binding.badgeApp;
        tvClearAll = binding.tvClearAll;

        // 为图标设置毛玻璃背景
        applyFrostedGlassToIcon(binding.ivEmojiMessages);
        applyFrostedGlassToIcon(binding.ivEmojiFans);
        applyFrostedGlassToIcon(binding.ivEmojiPosts);
        applyFrostedGlassToIcon(binding.ivEmojiInteractive);
        applyFrostedGlassToIcon(binding.ivEmojiSystem);
        applyFrostedGlassToIcon(binding.ivEmojiApp);
    }

    private void applyFrostedGlassToIcon(TextView icon) {
        if (icon != null) {
            icon.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));
        }
    }

    private void setupClickListeners() {
        binding.llMyMessages.setOnClickListener(v -> openNativeDetail("pm", "我的消息"));
        // 进入分类即视为查看当前内容,随后新增内容仍会重新产生角标。
        binding.llMyFans.setOnClickListener(v -> {
            NoticeBadgeManager.markViewed(requireContext(), "follower");
            Intent intent = new Intent(requireContext(), FriendListActivity.class);
            intent.putExtra("mode", "followers");
            startActivity(intent);
        });
        binding.llMyPosts.setOnClickListener(v -> openNativeDetail("mypost", "我的帖子"));
        binding.llInteractive.setOnClickListener(v -> openNativeDetail("interactive", "坛友互动"));
        binding.llSystem.setOnClickListener(v -> openNativeDetail("system", "系统提醒"));
        binding.llApp.setOnClickListener(v -> openNativeDetail("app", "应用提醒"));
        // 全部已读:按压缩放反馈 + 点击淡入动效,与主界面导航栏选中动效一致
        tvClearAll.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false; // 不消费事件,交给 OnClickListener
        });
        tvClearAll.setOnClickListener(v -> {
            Animation anim = AnimationUtils.loadAnimation(requireContext(), android.R.anim.fade_in);
            anim.setDuration(150);
            v.startAnimation(anim);
            clearAllBadges();
        });
    }

    /** 进入分类时立即标记当前快照为已查看;详情页只负责展示内容。 */
    private void openNativeDetail(String view, String title) {
        NoticeBadgeManager.markViewed(requireContext(), view);
        String url;
        if ("pm".equals(view)) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2";
        } else if ("follower".equals(view)) {
            url = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid=" + getUid() + "&mobile=2";
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + view;
        }
        Intent intent = new Intent(requireContext(), NoticeDetailActivity.class);
        intent.putExtra("url", url);
        intent.putExtra(NoticeDetailActivity.EXTRA_VIEW_TYPE, view);
        intent.putExtra(NoticeDetailActivity.EXTRA_TITLE, title);
        requireActivity().startActivityForResult(intent, REQUEST_CODE_DETAIL);
    }

    private String getUid() {
        String uid = UserSessionManager.getInstance().getUid(requireContext());
        return TextUtils.isEmpty(uid) ? "0" : uid;
    }

    private void loadAllBadgeCounts() {
        lastBadgeLoadAt = System.currentTimeMillis(); // build68: 记录本次拉取时刻
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2", badgeMessages, "pm");
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid=" + getUid() + "&mobile=2", badgeFans, "follower");
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=mypost", badgePosts, "mypost");
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=interactive", badgeInteractive, "interactive");
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=system", badgeSystem, "system");
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=app", badgeApp, "app");
    }

    /**
     * 通过当前列表内容和本地已查看快照计算角标,不依赖网页 unread class。
     */
    private void loadCountBySnapshot(String url, TextView badgeView, String viewType) {
        if (badgeView == null) return;
        executor.execute(() -> {
            try {
                httpClient.syncFromCookieManager();
                String html = ("pm".equals(viewType) || "follower".equals(viewType))
                        ? httpClient.get(url) : httpClient.getDesktop(url);
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)) {
                    mainHandler.post(() -> updateBadge(badgeView, 0));
                    return;
                }
                String snapshot = NoticeBadgeManager.buildSnapshot(viewType, html, httpClient);
                int count = NoticeBadgeManager.saveCurrentAndGetNewCount(
                        requireContext(), viewType, snapshot);
                mainHandler.post(() -> updateBadge(badgeView, count));
            } catch (Exception ignored) {
                mainHandler.post(() -> updateBadge(badgeView, 0));
            }
        });
    }

    private void updateBadge(TextView badgeView, int count) {
        if (badgeView == null) return;
        if (count > 0) {
            badgeView.setText(count > 99 ? "99+" : String.valueOf(count));
            badgeView.setVisibility(View.VISIBLE);
        } else badgeView.setVisibility(View.GONE);
    }

    private void hideAllBadges() {
        updateBadge(badgeMessages, 0); updateBadge(badgeFans, 0); updateBadge(badgePosts, 0);
        updateBadge(badgeInteractive, 0); updateBadge(badgeSystem, 0); updateBadge(badgeApp, 0);
    }

    private void clearAllBadges() {
        hideAllBadges();
        NoticeBadgeManager.markAllViewed(requireContext());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_DETAIL || resultCode != getActivity().RESULT_OK || data == null) return;
        if (!data.getBooleanExtra(NoticeDetailActivity.RESULT_CLEARED, false)) return;
        String type = data.getStringExtra(NoticeDetailActivity.RESULT_VIEW_TYPE);
        if (!TextUtils.isEmpty(type)) NoticeBadgeManager.markViewed(requireContext(), type);
        if ("pm".equals(type)) updateBadge(badgeMessages, 0);
        else if ("follower".equals(type)) updateBadge(badgeFans, 0);
        else if ("mypost".equals(type)) updateBadge(badgePosts, 0);
        else if ("interactive".equals(type)) updateBadge(badgeInteractive, 0);
        else if ("system".equals(type)) updateBadge(badgeSystem, 0);
        else if ("app".equals(type)) updateBadge(badgeApp, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null) executor.shutdownNow();
        executor = null;
        binding = null;
    }
}
