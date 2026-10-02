package com.solosu.mtforum;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.solosu.mtforum.ai.AiChatActivity;
import com.solosu.mtforum.ai.AiConfigActivity;
import com.solosu.mtforum.ai.AiConfigManager;
import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.ai.AutoReplyEngine;
import com.solosu.mtforum.ai.AutoReplyScheduler;
import com.solosu.mtforum.databinding.ActivityMainBinding;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.network.NoticeBadgeManager;
import com.solosu.mtforum.ui.MainPagerAdapter;
import com.solosu.mtforum.ui.post.PostActivity;
import com.solosu.mtforum.session.AutoSignInManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** * 主活动 — 胶囊导航栏（首页/版块/凸起发布/消息/我的） */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ViewPager2 mainPager;
    private MainPagerAdapter pagerAdapter;

    // ==================== build71: 底部导航栏滚动自动隐藏 ====================
    private static final long NAV_HIDE_ANIM_MS = 200;
    private boolean navHidden = false;
    private boolean autoHideNavEnabled = true; // build72: 侧边栏开关,关闭则底部栏常驻
    private long lastNavScrollAt = 0L;
    private int navScrollAccum = 0;

    // 导航项（5项）
    private View navHome, navCommunity, navPost, navMessage, navProfile;
    // 图标（含凸起发布按钮图标）
    private ImageView ivHomeIcon, ivCommunityIcon, ivPostIcon, ivMessageIcon, ivProfileIcon;
    // 文字
    private TextView tvHomeText, tvCommunityText, tvMessageText, tvProfileText;

    // ★ 消息角标
    private TextView tvMessageBadge;
    private Handler mainHandler;
    private ExecutorService executor;
    private static final long BADGE_REFRESH_INTERVAL_MS = 60000; // 60秒刷新一次(降频防ESA 403,6类并行=6发/分钟)
    private static final long BADGE_RESUME_THROTTLE_MS = 60000; // build68: onResume 即时刷新节流(防频繁返回重复拉6类)
    private final java.util.Map<String, Integer> previousUnreadCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> previousUnreadFingerprints = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean badgeRefreshInFlight = new AtomicBoolean(false);
    private long lastBadgeRefreshAt = 0L; // build68: 上次角标刷新时间戳(节流用)
    private boolean unreadBaselineReady = false;
    /** build83: 分类被查看时本地即时清零对应红点(零额外请求) */
    private static final String[] BADGE_TYPES = {"pm", "follower", "mypost", "interactive", "system", "app"};
    private static MainActivity sInstance;

    // 双击返回退出
    private static final long EXIT_INTERVAL_MS = 2000;
    private long lastBackPressTime = 0;
    private FrostedGlassDrawable frostedNavBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 底部导航栏统一使用彩色流水灯边缘，并保留胶囊形状。
        float density = getResources().getDisplayMetrics().density;
        frostedNavBackground = FrostedGlassDrawable.create(this, 24f);
        binding.bottomNavContainer.setBackground(frostedNavBackground);

        // 初始化 ViewPager2：首页/版块/消息/我的 四页快速切换
        mainPager = binding.mainPager;
        pagerAdapter = new MainPagerAdapter(this);
        mainPager.setAdapter(pagerAdapter);
        mainPager.setOffscreenPageLimit(MainPagerAdapter.PAGE_COUNT - 1); // 全页保活，切换不重建
        mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateNavSelectionByPosition(position);
                // build71: 切页时底部栏立即恢复显示(用户刚切换,需要看到导航)
                setBottomNavVisible(true);
            }
        });

        // 恢复持久化的 Cookie
        HttpClient.getInstance().init(this);

        // ★ 消息角标初始化
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newFixedThreadPool(7);
        // build83: 注册分类查看回调, 进入消息分类后本地即时清红点
        sInstance = this;
        NoticeBadgeManager.setOnViewedListener(this::onNoticeViewed);
        tvMessageBadge = findViewById(R.id.nav_message_badge);
        if (tvMessageBadge != null) {
            tvMessageBadge.setVisibility(View.GONE);
            refreshMessageBadge();
        }

        // 初始化导航视图引用（含凸起发布按钮）
        initNavigationBar();

        // 初始化侧边栏
        initDrawer();
    }

    // ==================== 侧边栏 ====================

    private DrawerLayout drawerLayout;
    private View drawerPanel;
    private SwitchMaterial swAutoReply;
    private SwitchMaterial swSilent;
    private SwitchMaterial swSignIn;
    private SwitchMaterial swUnlock;
    private SwitchMaterial swDryRun;
    private SwitchMaterial swAutoHideNav;
    private TextView tvDrawerName;
    private TextView tvDrawerSubtitle;
    private TextView tvAiDesc;
    private ImageView ivDrawerAvatar;

    /** 侧边栏开关的读写/跳转 */
    private void initDrawer() {
        View root = findViewById(R.id.main_drawer);
        if (!(root instanceof DrawerLayout)) {
            android.util.Log.e("MainActivity", "DrawerLayout not found");
            return;
        }
        drawerLayout = (DrawerLayout) root;
        // 遮罩加深，抽屉打开时右侧主内容不会透出文字
        drawerLayout.setScrimColor(0xC0000000);

        // 打开侧边栏时藏掉底部悬浮导航栏，否则两边的文字会叠在一起
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View dv) {
                // build71: 改走统一入口,保证 navHidden 标记与实际可见性永远一致
                setBottomNavVisible(false);
            }

            @Override
            public void onDrawerClosed(View dv) {
                // build71: 抽屉关闭强制恢复(navHidden 为 true 时不会被短路判断挡掉)
                setBottomNavVisible(true);
            }
        });

        drawerPanel = findViewById(R.id.drawer_panel);
        swAutoReply = findViewById(R.id.drawer_switch_auto_reply);
        swSilent = findViewById(R.id.drawer_switch_silent);
        swSignIn = findViewById(R.id.drawer_switch_sign_in);
        swUnlock = findViewById(R.id.drawer_switch_unlock);
        swDryRun = findViewById(R.id.drawer_switch_dry_run);
        swAutoHideNav = findViewById(R.id.drawer_switch_auto_hide_nav);
        tvDrawerName = findViewById(R.id.drawer_username);
        tvDrawerSubtitle = findViewById(R.id.drawer_subtitle);
        tvAiDesc = findViewById(R.id.drawer_ai_desc);
        ivDrawerAvatar = findViewById(R.id.drawer_avatar);
        // build57: 侧边栏头部(头像/用户名/UID行)点击进自己主页
        View.OnClickListener ownProfile = v -> openOwnProfile();
        ivDrawerAvatar.setOnClickListener(ownProfile);
        if (tvDrawerName != null) tvDrawerName.setOnClickListener(ownProfile);
        if (tvDrawerSubtitle != null) tvDrawerSubtitle.setOnClickListener(ownProfile);

        // 左上角入口打开侧边栏
        View openBtn = findViewById(R.id.btn_open_drawer);
        if (openBtn != null) {
            openBtn.setOnClickListener(v -> drawerLayout.openDrawer(drawerPanel));
        }

        bindSwitchRow(R.id.drawer_auto_reply_row, swAutoReply);
        bindSwitchRow(R.id.drawer_silent_row, swSilent);
        bindSwitchRow(R.id.drawer_sign_in_row, swSignIn);

        // 自动回复开关
        if (swAutoReply != null) {
            swAutoReply.setChecked(AiConfigManager.isAutoReplyEnabled(this));
            swAutoReply.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setAutoReplyEnabled(this, checked);
                AutoReplyScheduler.reschedule(this);
                AiLog.i("drawer", "自动回复 " + (checked ? "开启" : "关闭"));
                if (checked) {
                    Toast.makeText(this, "自动回复已开启（后台静默运行）", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 静默模式开关
        if (swSilent != null) {
            swSilent.setChecked(AiConfigManager.isSilentMode(this));
            swSilent.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setSilentMode(this, checked);
                AiLog.i("drawer", "隐藏运行 " + (checked ? "开启" : "关闭"));
            });
        }

        // 自动签到开关：复用既有 AutoSignInManager
        if (swSignIn != null) {
            swSignIn.setChecked(AutoSignInManager.isEnabled(this));
            swSignIn.setOnCheckedChangeListener((v, checked) -> {
                AutoSignInManager.setEnabled(this, checked);
                AiLog.i("drawer", "自动签到 " + (checked ? "开启" : "关闭"));
                if (checked) {
                    doSignInNow();
                }
            });
        }

        // 自动解锁隐藏内容：一键控制「解锁模式 + 进帖自动解锁」
        bindSwitchRow(R.id.drawer_unlock_row, swUnlock);
        if (swUnlock != null) {
            swUnlock.setChecked(AiConfigManager.isUnlockMode(this));
            updateUnlockDesc();
            swUnlock.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setUnlockMode(this, checked);
                AiConfigManager.setUnlockOnView(this, checked);
                updateUnlockDesc();
                AiLog.i("drawer", "自动解锁隐藏内容 " + (checked ? "开启" : "关闭"));
                Toast.makeText(this, checked
                        ? "已开启：进含「回复可见」的帖子会自动回帖解锁"
                        : "已关闭自动解锁", Toast.LENGTH_SHORT).show();
            });
        }

        // 演练模式：自动回复只生成不发送（不影响进帖解锁）
        bindSwitchRow(R.id.drawer_dry_run_row, swDryRun);
        if (swDryRun != null) {
            swDryRun.setChecked(AiConfigManager.isDryRun(this));
            swDryRun.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setDryRun(this, checked);
                AiLog.i("drawer", "演练模式 " + (checked ? "开启" : "关闭"));
                Toast.makeText(this, checked
                        ? "演练模式：自动回复只生成不发送"
                        : "已关闭演练模式，自动回复将真实发送", Toast.LENGTH_SHORT).show();
            });
        }

        // build72: 底部栏滚动自动隐藏开关
        bindSwitchRow(R.id.drawer_auto_hide_nav_row, swAutoHideNav);
        if (swAutoHideNav != null) {
            autoHideNavEnabled = AiConfigManager.isAutoHideNav(this);
            swAutoHideNav.setChecked(autoHideNavEnabled);
            if (!autoHideNavEnabled) {
                setBottomNavVisible(true);
            }
            swAutoHideNav.setOnCheckedChangeListener((v, checked) -> {
                autoHideNavEnabled = checked;
                AiConfigManager.setAutoHideNav(this, checked);
                if (checked) {
                    setBottomNavVisible(true);
                    Toast.makeText(this, "已开启：刷帖时底部栏自动隐藏", Toast.LENGTH_SHORT).show();
                } else {
                    setBottomNavVisible(true);
                    Toast.makeText(this, "已关闭：底部栏常驻显示", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 解锁回复内容自定义：点击弹对话框编辑模板
        View unlockTextRow = findViewById(R.id.drawer_unlock_text_row);
        if (unlockTextRow != null) {
            updateUnlockTextDesc();
            unlockTextRow.setOnClickListener(v -> showUnlockTextDialog());
        }

        // 立即执行一轮自动回复
        View runReply = findViewById(R.id.drawer_run_reply);
        if (runReply != null) {
            runReply.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                Toast.makeText(this, "开始执行一轮自动回复…", Toast.LENGTH_SHORT).show();
                AutoReplyEngine.runOnce(this, (replied, skipped, detail) ->
                        Toast.makeText(this,
                                "本轮：回复 " + replied + " 条，跳过 " + skipped + " 条\n" + detail,
                                Toast.LENGTH_LONG).show());
            });
        }

        // 立即签到
        View runSignIn = findViewById(R.id.drawer_run_sign_in);
        if (runSignIn != null) {
            runSignIn.setOnClickListener(v -> doSignInNow());
        }

        // AI 配置
        View aiConfig = findViewById(R.id.drawer_ai_config);
        if (aiConfig != null) {
            aiConfig.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this, AiConfigActivity.class));
            });
        }

        // AI 助手
        View aiChat = findViewById(R.id.drawer_ai_chat);
        if (aiChat != null) {
            aiChat.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this, AiChatActivity.class));
            });
        }

        // 切换账号
        View accountsRow = findViewById(R.id.drawer_accounts);
        if (accountsRow != null) {
            accountsRow.setOnClickListener(v -> showAccountSwitcher());
        }

        // 个人小黑屋
        View blacklistRow = findViewById(R.id.drawer_blacklist);
        if (blacklistRow != null) {
            blacklistRow.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this,
                        com.solosu.mtforum.ui.BlacklistActivity.class));
            });
        }

        // 设置
        View settings = findViewById(R.id.drawer_settings);
        if (settings != null) {
            settings.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this,
                        com.solosu.mtforum.ui.space.SettingsActivity.class));
            });
        }

        // 运行日志
        View logView = findViewById(R.id.drawer_log);
        if (logView != null) {
            logView.setOnClickListener(v -> showRunLog());
        }

        refreshDrawerHeader();
    }

    /** 整行点击等于切换开关 */
    private void bindSwitchRow(int rowId, SwitchMaterial sw) {
        if (sw == null) return;
        View row = findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    }

    /** 侧边栏「自动解锁」副标题：按当前模式显示正在干什么 */
    private void updateUnlockDesc() {
        TextView desc = findViewById(R.id.drawer_unlock_desc);
        if (desc == null) return;
        desc.setText(AiConfigManager.isUnlockMode(this)
                ? "进帖遇「回复可见」自动回帖解锁"
                : "已关闭，不自动解锁");
    }

    /** 侧边栏「解锁回复内容」副标题：显示当前是自定义还是默认模板 */
    private void updateUnlockTextDesc() {
        TextView desc = findViewById(R.id.drawer_unlock_text_desc);
        if (desc == null) return;
        String custom = AiConfigManager.getUnlockReplyTemplate(this);
        desc.setText(android.text.TextUtils.isEmpty(custom)
                ? "默认模板（点击自定义）"
                : "自定义：" + (custom.length() > 20 ? custom.substring(0, 20) + "…" : custom));
    }

    /** 弹出对话框编辑解锁回复模板，{title} 会被替换为帖子标题关键词 */
    private void showUnlockTextDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(AiConfigManager.getUnlockReplyTemplate(this));
        et.setHint("例如：感谢分享「{title}」，正需要这个！");
        et.setMinLines(2);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("解锁回复内容")
                .setMessage("自定义自动解锁时发送的回复。\n留空则用内置模板池随机选一条。\n用 {title} 插入帖子标题关键词。")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    AiConfigManager.setUnlockReplyTemplate(this, v);
                    updateUnlockTextDesc();
                    AiLog.i("drawer", "解锁回复模板已更新：" + (v.isEmpty() ? "（恢复默认）" : v));
                    Toast.makeText(this, v.isEmpty() ? "已恢复默认模板" : "已保存自定义回复", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 账号切换弹窗:当前账号高亮,点选切换,支持登录新账号/删除 */
    private void showAccountSwitcher() {
        java.util.List<com.solosu.mtforum.session.AccountManager.Account> accounts =
                com.solosu.mtforum.session.AccountManager.list(this);
        String activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this);
        String curName = UserSessionManager.getInstance().getUsername(this);
        String curUid = UserSessionManager.getInstance().getUid(this);
        boolean curLogged = UserSessionManager.getInstance().isLoggedIn(this);

        // 若当前登录账号未入库(比如老用户),先补存
        if (curLogged && !android.text.TextUtils.isEmpty(curUid)) {
            com.solosu.mtforum.session.AccountManager.saveCurrent(this, curUid, curName,
                    UserSessionManager.getInstance().getAvatarUrl(this),
                    UserSessionManager.getInstance().getLevel(this));
            accounts = com.solosu.mtforum.session.AccountManager.list(this);
            activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this);
        }
        final java.util.List<com.solosu.mtforum.session.AccountManager.Account> fAccounts = accounts;
        final String fActiveUid = activeUid;

        java.util.List<String> labels = new java.util.ArrayList<>();
        for (com.solosu.mtforum.session.AccountManager.Account a : fAccounts) {
            String mark = (fActiveUid != null && fActiveUid.equals(a.uid)) ? "  [当前]" : "";
            labels.add(a.username + mark);
        }
        labels.add("＋ 登录新账号");

        String[] arr = labels.toArray(new String[0]);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("切换账号")
                .setItems(arr, (d, which) -> {
                    if (which == fAccounts.size()) {
                        // 登录新账号:先保存当前,再弹登录
                        drawerLayout.closeDrawer(drawerPanel);
                        com.solosu.mtforum.ui.login.LoginBottomSheet.show(this, () -> {
                            // 登录成功后入库并刷新
                            String n = UserSessionManager.getInstance().getUsername(this);
                            String u = UserSessionManager.getInstance().getUid(this);
                            if (!android.text.TextUtils.isEmpty(u)) {
                                com.solosu.mtforum.session.AccountManager.saveCurrent(this, u, n,
                                        UserSessionManager.getInstance().getAvatarUrl(this),
                                        UserSessionManager.getInstance().getLevel(this));
                            }
                            refreshDrawerHeader();
                        });
                        return;
                    }
                    final com.solosu.mtforum.session.AccountManager.Account target = fAccounts.get(which);
                    if (target.uid.equals(curUid) && curLogged) {
                        Toast.makeText(this, "已是当前账号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 切换:cookie 快照回灌 + 更新 UserSessionManager 展示层
                    boolean ok = com.solosu.mtforum.session.AccountManager.switchTo(this, target.uid);
                    if (ok) {
                        java.util.Map<String, String> info = new java.util.HashMap<>();
                        info.put("username", target.username);
                        info.put("uid", target.uid);
                        info.put("avatarUrl", target.avatar != null ? target.avatar : "");
                        info.put("level", target.level != null ? target.level : "");
                        UserSessionManager.getInstance().saveLoginInfo(this, info);
                        Toast.makeText(this, "已切换到 " + target.username, Toast.LENGTH_SHORT).show();
                        refreshDrawerHeader();
                    } else {
                        Toast.makeText(this, "切换失败，请重新登录该账号", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openOwnProfile() {
        UserSessionManager session = UserSessionManager.getInstance();
        if (!session.isLoggedIn(this)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        String uid = session.getUid(this);
        if (uid == null || uid.isEmpty()) {
            Toast.makeText(this, "缺少UID", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.solosu.mtforum.ui.space.UserProfileActivity.class);
        it.putExtra("uid", uid);
        it.putExtra("username", session.getUsername(this));
        if (drawerLayout != null && drawerPanel != null) {
            drawerLayout.closeDrawer(drawerPanel);
        }
        startActivity(it);
    }

    private void refreshDrawerHeader() {
        if (tvDrawerName == null) return;
        UserSessionManager session = UserSessionManager.getInstance();
        boolean logged = session.isLoggedIn(this);
        String name = session.getUsername(this);
        tvDrawerName.setText(!logged || android.text.TextUtils.isEmpty(name) ? "未登录" : name);

        if (tvDrawerSubtitle != null) {
            String uid = session.getUid(this);
            if (logged && !android.text.TextUtils.isEmpty(uid)) {
                String shield = session.getLevel(this);
                // build57: level 存储值可能自带 Lv 前缀(ForumParser 抓整行文本), 剥掉防 Lv.Lv.
                String lv = shield == null ? "" : shield.trim();
                if (lv.length() >= 2 && (lv.charAt(0) == 'L' || lv.charAt(0) == 'l')
                        && (lv.charAt(1) == 'V' || lv.charAt(1) == 'v')) {
                    lv = lv.substring(2);
                    while (lv.startsWith(".") || lv.startsWith(".")) lv = lv.substring(1);
                    lv = lv.trim();
                }
                tvDrawerSubtitle.setText(android.text.TextUtils.isEmpty(lv)
                        ? "UID " + uid : "UID " + uid + " · Lv." + lv);
            } else {
                tvDrawerSubtitle.setText("点击侧边栏开启自动化");
            }
        }

        if (tvAiDesc != null) {
            tvAiDesc.setText(AiConfigManager.isConfigured(this)
                    ? AiConfigManager.getModel(this) : "未配置模型");
        }

        if (ivDrawerAvatar != null) {
            String avatar = session.getAvatarUrl(this);
            if (logged && !android.text.TextUtils.isEmpty(avatar)) {
                com.bumptech.glide.Glide.with(this)
                        .load(avatar)
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .circleCrop()
                        .into(ivDrawerAvatar);
            }
        }
    }

    /** 立即签到一次（无论开关状态） */
    private void doSignInNow() {
        drawerLayout.closeDrawer(drawerPanel);
        AutoSignInManager.checkAndSignIn(this, (success, performed, message) -> {
            if (isFinishing() || isDestroyed()) return;
            AiLog.i("sign-in", "success=" + success + " performed=" + performed + " " + message);
            Toast.makeText(this, message == null ? "签到完成" : message, Toast.LENGTH_SHORT).show();
            if (success) {
                com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
            }
        });
    }

    /** 弹出运行日志 */
    private void showRunLog() {
        String text = AiLog.dump();
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);

        String path = AiLog.filePath();
        String hint = android.text.TextUtils.isEmpty(path)
                ? "" : "\n\n日志文件：\n" + path;

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("运行日志（" + AiLog.size() + " 条）")
                .setMessage(hint.trim().isEmpty() ? null : hint)
                .setView(sv)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "mtforum-log", text + hint));
                        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("清空", (d, w) -> {
                    AiLog.clear();
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 初始化悬浮胶囊导航栏（5项：首页/版块/凸起发布/消息/我的）
     */
    private void initNavigationBar() {
        navHome = findViewById(R.id.nav_home_tab);
        navCommunity = findViewById(R.id.nav_community_tab);
        navPost = findViewById(R.id.nav_post_tab);
        navMessage = findViewById(R.id.nav_message_tab);
        navProfile = findViewById(R.id.nav_profile_tab);

        ivHomeIcon = findViewById(R.id.nav_home_icon);
        ivCommunityIcon = findViewById(R.id.nav_community_icon);
        ivPostIcon = findViewById(R.id.nav_post_icon);
        ivMessageIcon = findViewById(R.id.nav_message_icon);
        ivProfileIcon = findViewById(R.id.nav_profile_icon);

        tvHomeText = findViewById(R.id.nav_home_text);
        tvCommunityText = findViewById(R.id.nav_community_text);
        tvMessageText = findViewById(R.id.nav_message_text);
        tvProfileText = findViewById(R.id.nav_profile_text);

        // 空指针保护
        if (navHome == null || navCommunity == null || navPost == null || navMessage == null || navProfile == null) {
            android.util.Log.e("MainActivity", "Navigation bar views are null");
            return;
        }
        if (ivHomeIcon == null || ivCommunityIcon == null || ivPostIcon == null || ivMessageIcon == null || ivProfileIcon == null) {
            android.util.Log.e("MainActivity", "Navigation bar icons are null");
            return;
        }
        if (tvHomeText == null || tvCommunityText == null || tvMessageText == null || tvProfileText == null) {
            android.util.Log.e("MainActivity", "Navigation bar texts are null");
            return;
        }

        // 前4项 — 首页/版块/我的使用NavController导航；消息直接启动NoticeActivity
        navHome.setOnClickListener(v -> switchPage(MainPagerAdapter.PAGE_HOME));
        navCommunity.setOnClickListener(v -> switchPage(MainPagerAdapter.PAGE_COMMUNITY));
        navMessage.setOnClickListener(v -> switchPage(MainPagerAdapter.PAGE_MESSAGE));
        navProfile.setOnClickListener(v -> switchPage(MainPagerAdapter.PAGE_PROFILE));

        // 中间凸起发布按钮 — 启动 PostActivity
        navPost.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PostActivity.class);
            startActivity(intent);
        });

        // 初始选中状态
        updateNavSelectionByPosition(MainPagerAdapter.PAGE_HOME);
    }

    /**
     * 导航到指定目的地（仅用于导航栏非发布项）
     */
    private void switchPage(int position) {
        if (mainPager != null) {
            mainPager.setCurrentItem(position, true);
        }
        updateNavSelectionByPosition(position);
        // build71: 用户主动点标签 -> 底部栏必须显示
        setBottomNavVisible(true);
    }

    // ==================== build71: 底部导航栏滚动自动隐藏 ====================

    /** 显示/隐藏底部导航栏(带动画) */
    private void setBottomNavVisible(boolean show) {
        View nav = findViewById(R.id.bottom_nav_container);
        if (nav == null) return;
        if (show) {
            if (!navHidden && nav.getVisibility() == View.VISIBLE) return;
            navHidden = false;
            nav.setVisibility(View.VISIBLE);
            nav.animate().translationY(0f).setDuration(NAV_HIDE_ANIM_MS)
                    .setListener(null).start();
        } else {
            if (navHidden) return;
            navHidden = true;
            float hidden = nav.getHeight() > 0 ? nav.getHeight() + dp(24f) : dp(92f);
            nav.animate().translationY(hidden).setDuration(NAV_HIDE_ANIM_MS)
                    .withEndAction(() -> {
                        if (navHidden) nav.setVisibility(View.GONE);
                    }).start();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * 滚动方向判定(供各页 Fragment 通过 NavBarAutoHideHelper 转发进来)。
     * 手指上滑(dy>0): 用户在往下看内容 -> 藏;
     * 手指下滑(dy<0): 用户想回上面或找导航 -> 露。
     * 停顿超过 400ms 重新累计,避免来回抖动。
     */
    public void onNavScroll(int dy) {
        // build72: 侧边栏开关关闭 -> 底部栏常驻,不参与自动隐藏
        if (!autoHideNavEnabled) return;
        long now = System.currentTimeMillis();
        if (now - lastNavScrollAt > 400L) {
            navScrollAccum = 0;
        }
        lastNavScrollAt = now;
        if (dy > 0) {
            if (navScrollAccum < 0) navScrollAccum = 0;
            navScrollAccum += dy;
            if (navScrollAccum > 24) {
                setBottomNavVisible(false);
            }
        } else if (dy < 0) {
            if (navScrollAccum > 0) navScrollAccum = 0;
            navScrollAccum += dy;
            if (navScrollAccum < -8) {
                setBottomNavVisible(true);
            }
        }
    }

    /**
     * 更新导航栏选中状态
     */
    private void updateNavSelectionByPosition(int position) {
        resetAllSelection();

        switch (position) {
            case MainPagerAdapter.PAGE_HOME:
                setItemActive(ivHomeIcon, tvHomeText);
                break;
            case MainPagerAdapter.PAGE_COMMUNITY:
                setItemActive(ivCommunityIcon, tvCommunityText);
                break;
            case MainPagerAdapter.PAGE_MESSAGE:
                setItemActive(ivMessageIcon, tvMessageText);
                break;
            case MainPagerAdapter.PAGE_PROFILE:
                setItemActive(ivProfileIcon, tvProfileText);
                break;
            default:
                break;
        }
    }

    /**
     * 重置所有导航项为未选中状态
     */
    private void resetAllSelection() {
        setItemInactive(ivHomeIcon, tvHomeText);
        setItemInactive(ivCommunityIcon, tvCommunityText);
        setItemInactive(ivMessageIcon, tvMessageText);
        setItemInactive(ivProfileIcon, tvProfileText);
    }

    private void setItemActive(ImageView icon, TextView text) {
        if (icon == null || text == null) return;
        icon.setImageResource(getActiveIconRes(icon.getId()));
        icon.clearAnimation();
        Animation anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        anim.setDuration(150);
        icon.startAnimation(anim);
        text.setTextColor(getResources().getColor(R.color.nav_text_active, null));
    }

    private void setItemInactive(ImageView icon, TextView text) {
        if (icon == null || text == null) return;
        icon.setImageResource(getInactiveIconRes(icon.getId()));
        text.setTextColor(getResources().getColor(R.color.nav_text_default, null));
    }

    private int getActiveIconRes(int viewId) {
        if (viewId == R.id.nav_home_icon) {
            return R.drawable.ic_home;
        } else if (viewId == R.id.nav_community_icon) {
            return R.drawable.ic_discover;
        } else if (viewId == R.id.nav_message_icon) {
            return R.drawable.ic_message;
        } else if (viewId == R.id.nav_profile_icon) {
            return R.drawable.ic_profile;
        }
        return 0;
    }

    private int getInactiveIconRes(int viewId) {
        return getActiveIconRes(viewId);
    }

    // ==================== 消息角标 ====================

    /**
     * 刷新消息角标（获取未读PM数）
     */
    private void refreshMessageBadge() {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        if (!HttpClient.getInstance().isLoggedIn()) {
            badgeRefreshInFlight.set(false);
            if (tvMessageBadge != null) tvMessageBadge.setVisibility(View.GONE);
            scheduleNextBadgeRefresh();
            return;
        }
        // 防止上一次网络刷新尚未结束时重复提交任务。
        if (!badgeRefreshInFlight.compareAndSet(false, true)) return;
        lastBadgeRefreshAt = System.currentTimeMillis(); // build68: 记录本次刷新时刻
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            badgeRefreshInFlight.set(false);
            scheduleNextBadgeRefresh();
            return;
        }

        // 六类列表并行请求；原来单线程逐个请求，尤其 PM 还会请求会话详情，导致角标延迟明显。
        try {
            executor.execute(() -> {
                java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
                java.util.Map<String, String> fingerprints = new java.util.concurrent.ConcurrentHashMap<>();
                String[] types = {"pm", "follower", "mypost", "interactive", "system", "app"};
                try {
                    HttpClient.getInstance().syncFromCookieManager();
                    CountDownLatch latch = new CountDownLatch(types.length);
                    for (String type : types) {
                        final String viewType = type;
                        final String url;
                        if ("pm".equals(viewType)) {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2";
                        } else if ("follower".equals(viewType)) {
                            url = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid="
                                    + getCurrentUid() + "&mobile=2";
                        } else {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + viewType;
                        }
                        try {
                            executor.execute(() -> {
                                try {
                                    putUnreadData(counts, fingerprints, viewType, url);
                                } finally {
                                    latch.countDown();
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException rejected) {
                            latch.countDown();
                        }
                    }
                    latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                        applyUnreadCounts(counts, fingerprints);
                    });
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (!isFinishing()) scheduleNextBadgeRefresh();
                    });
                } catch (Exception ignored) {
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (!isFinishing()) scheduleNextBadgeRefresh();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            badgeRefreshInFlight.set(false);
            scheduleNextBadgeRefresh();
        }
    }

    private String getCurrentUid() {
        String uid = com.solosu.mtforum.session.UserSessionManager
                .getInstance().getUid(this);
        return uid == null || uid.isEmpty() ? "0" : uid;
    }

    private int fetchUnreadCount(String url, String viewType) {
        try {
            String html = ("pm".equals(viewType) || "follower".equals(viewType))
                    ? HttpClient.getInstance().get(url)
                    : HttpClient.getInstance().getDesktop(url);
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) return 0;
            List<com.solosu.mtforum.model.Message> items;
            if ("pm".equals(viewType)) items = ForumParser.parsePmList(html);
            else if ("follower".equals(viewType)) items = ForumParser.parseFollowerList(html);
            else items = ForumParser.parseNoticeList(html);
            int count = 0;
            if (items != null) for (com.solosu.mtforum.model.Message item : items) {
                if (!item.isRead()) count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void putUnreadData(java.util.Map<String, Integer> counts,
                                 java.util.Map<String, String> fingerprints,
                                 String viewType, String url) {
        try {
            String html = ("pm".equals(viewType) || "follower".equals(viewType))
                    ? HttpClient.getInstance().get(url)
                    : HttpClient.getInstance().getDesktop(url);
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) {
                counts.put(viewType, 0);
                fingerprints.put(viewType, "");
                return;
            }

            // 与 NoticeFragment 使用同一套“当前列表 - 已查看快照”算法。
            String snapshot = NoticeBadgeManager.buildSnapshot(
                    viewType, html, HttpClient.getInstance());
            int count = NoticeBadgeManager.saveCurrentAndGetNewCount(
                    MainActivity.this, viewType, snapshot);
            counts.put(viewType, count);
            fingerprints.put(viewType, snapshot);
        } catch (Exception ignored) {
            counts.put(viewType, 0);
            fingerprints.put(viewType, "");
        }
    }

    private void applyUnreadCounts(java.util.Map<String, Integer> counts,
                                   java.util.Map<String, String> fingerprints) {
        // 底部导航栏显示所有消息分类的新增总数，不能只显示私信数量。
        int totalCount = 0;
        String[] allTypes = {"pm", "follower", "mypost", "interactive", "system", "app"};
        for (String type : allTypes) {
            totalCount += getCount(counts, type);
        }
        updateBadgeDisplay(totalCount);

        if (unreadBaselineReady) {
            boolean hasNewPrivateChat = hasNewUnread("pm", counts, fingerprints);
            boolean hasNewOtherNotice = false;
            String[] otherTypes = {"follower", "mypost", "interactive", "system", "app"};
            for (String type : otherTypes) {
                if (hasNewUnread(type, counts, fingerprints)) {
                    hasNewOtherNotice = true;
                    break;
                }
            }
            // 音频已移除
        }

        previousUnreadCounts.clear();
        previousUnreadCounts.putAll(counts);
        previousUnreadFingerprints.clear();
        previousUnreadFingerprints.putAll(fingerprints);
        unreadBaselineReady = true;
        scheduleNextBadgeRefresh();
    }

    private boolean hasNewUnread(String type, java.util.Map<String, Integer> counts,
                                 java.util.Map<String, String> fingerprints) {
        int currentCount = getCount(counts, type);
        int oldCount = getCount(previousUnreadCounts, type);
        String currentFingerprint = fingerprints.get(type);
        String oldFingerprint = previousUnreadFingerprints.get(type);
        return currentCount > oldCount
                || (currentCount > 0 && !java.util.Objects.equals(currentFingerprint, oldFingerprint));
    }

    private int getCount(java.util.Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    private void updateBadgeDisplay(int count) {
        renderBadge(count);
        scheduleNextBadgeRefresh();
    }

    /** build83: 只渲染红点, 不重置 60s 轮询计时 */
    private void renderBadge(int count) {
        if (tvMessageBadge == null) return;
        if (count > 0) {
            tvMessageBadge.setVisibility(View.VISIBLE);
            tvMessageBadge.setText(count > 99 ? "99+" : String.valueOf(count));
        } else {
            tvMessageBadge.setVisibility(View.GONE);
        }
    }

    /** build83: 消息分类被查看 → 本地即时清零该分类计数并重算总数, 红点立即消失, 不发任何请求 */
    private void onNoticeViewed(String viewType) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final String t = viewType;
            runOnUiThread(() -> onNoticeViewed(t));
            return;
        }
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        if ("*".equals(viewType)) {
            for (String type : BADGE_TYPES) previousUnreadCounts.put(type, 0);
        } else if (viewType != null && !viewType.isEmpty()) {
            previousUnreadCounts.put(viewType, 0);
        } else {
            return;
        }
        int total = 0;
        for (String type : BADGE_TYPES) total += getCount(previousUnreadCounts, type);
        renderBadge(total);
    }

    private void scheduleNextBadgeRefresh() {
        mainHandler.removeCallbacks(badgeRunnable);
        mainHandler.postDelayed(badgeRunnable, BADGE_REFRESH_INTERVAL_MS);
    }

    private final Runnable badgeRunnable = this::refreshMessageBadge;

    @Override
    protected void onStart() {
        super.onStart();
        AutoSignInManager.checkAndSignIn(this, (success, performed, message) -> {
            if (success || "今日已签到".equals(message)) {
                // 通知社区页刷新签到按钮状态
                com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
            }
            if (performed && success && !isFinishing()) {
                Toast.makeText(this, "自动签到成功", Toast.LENGTH_SHORT).show();
            } else if (!performed && !isFinishing() && "请先登录".equals(message)) {
                // 未登录时静默等待,不弹 Toast 打扰用户
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // build68: 从消息页/后台返回时刷新，但加 60 秒节流——频繁返回不再重复全量拉 6 类(防 ESA 403)。
        // 角标本身已有 60 秒定时轮询兜底, 这里的即时刷新只是锦上添花。
        if (mainHandler != null && tvMessageBadge != null) {
            long now = System.currentTimeMillis();
            if (now - lastBadgeRefreshAt >= BADGE_RESUME_THROTTLE_MS) {
                mainHandler.removeCallbacks(badgeRunnable);
                refreshMessageBadge();
            }
        }
        // 登录状态 / AI 配置可能已变化，刷新侧边栏头部
        refreshDrawerHeader();
    }

    @Override
    protected void onStop() {
        if (mainHandler != null) mainHandler.removeCallbacks(badgeRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (frostedNavBackground != null) {
            frostedNavBackground.setVisible(false, false);
        }
        if (sInstance == this) {
            sInstance = null;
            NoticeBadgeManager.setOnViewedListener(null);
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 侧边栏开着时，返回键先收起侧边栏
        if (drawerLayout != null && drawerPanel != null
                && drawerLayout.isDrawerOpen(drawerPanel)) {
            drawerLayout.closeDrawer(drawerPanel);
            return;
        }
        // 首次返回提示，连续第二次返回才退出
        long now = System.currentTimeMillis();
        if (lastBackPressTime > 0 && now - lastBackPressTime < EXIT_INTERVAL_MS) {
            finishAffinity(); // 直接退出
            super.onBackPressed();
            return;
        }
        lastBackPressTime = now;
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
    }

}