package com.solosu.mtforum

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.widget.ViewPager2

import com.google.android.material.switchmaterial.SwitchMaterial
import com.solosu.mtforum.ai.AiChatActivity
import com.solosu.mtforum.ai.AiConfigActivity
import com.solosu.mtforum.ai.AiConfigManager
import com.solosu.mtforum.ai.AiLog
import com.solosu.mtforum.ai.AutoReplyEngine
import com.solosu.mtforum.ai.AutoReplyScheduler
import com.solosu.mtforum.databinding.ActivityMainBinding
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.network.NoticeBadgeManager
import com.solosu.mtforum.ui.MainPagerAdapter
import com.solosu.mtforum.ui.post.PostActivity
import com.solosu.mtforum.session.AutoSignInManager
import com.solosu.mtforum.session.UserSessionManager
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** * 主活动 — 胶囊导航栏（首页/版块/凸起发布/消息/我的） */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mainPager: ViewPager2? = null
    private var pagerAdapter: MainPagerAdapter? = null

    // ==================== build71: 底部导航栏滚动自动隐藏 ====================
    private var navHidden = false
    private var autoHideNavEnabled = true // build72: 侧边栏开关,关闭则底部栏常驻
    private var lastNavScrollAt = 0L
    private var navScrollAccum = 0

    // 导航项（5项）
    private var navHome: View? = null
    private var navCommunity: View? = null
    private var navPost: View? = null
    private var navMessage: View? = null
    private var navProfile: View? = null

    // 图标（含凸起发布按钮图标）
    private var ivHomeIcon: ImageView? = null
    private var ivCommunityIcon: ImageView? = null
    private var ivPostIcon: ImageView? = null
    private var ivMessageIcon: ImageView? = null
    private var ivProfileIcon: ImageView? = null

    // 文字
    private var tvHomeText: TextView? = null
    private var tvCommunityText: TextView? = null
    private var tvMessageText: TextView? = null
    private var tvProfileText: TextView? = null

    // ★ 消息角标
    private var tvMessageBadge: TextView? = null
    private var mainHandler: Handler? = null
    private var executor: ExecutorService? = null

    private val previousUnreadCounts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
    private val previousUnreadFingerprints: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val badgeRefreshInFlight = AtomicBoolean(false)
    private var lastBadgeRefreshAt = 0L // build68: 上次角标刷新时间戳(节流用)
    private var unreadBaselineReady = false

    // 双击返回退出
    private var lastBackPressTime = 0L
    private var frostedNavBackground: FrostedGlassDrawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mainDrawer.setStatusBarBackgroundColor(ContextCompat.getColor(this, R.color.background))
        com.solosu.mtforum.util.ThemeManager.setupWindow(this)

        // 底部导航栏统一使用纯净圆润大胶囊
        frostedNavBackground = FrostedGlassDrawable.create(this, 32f)
        binding.bottomNavContainer.background = frostedNavBackground

        // 初始化 ViewPager2：首页/版块/消息/我的 四页快速切换
        mainPager = binding.mainPager
        pagerAdapter = MainPagerAdapter(this)
        mainPager!!.adapter = pagerAdapter
        mainPager!!.offscreenPageLimit = MainPagerAdapter.PAGE_COUNT - 1 // 全页保活，切换不重建
        // 降低 ViewPager2 左右滑动灵敏度，保证列表上下滑动极其丝滑不被截胡
        reduceViewPager2Sensitivity(mainPager!!, 3.2f)
        mainPager!!.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavSelectionByPosition(position)
                // build71: 切页时底部栏立即恢复显示(用户刚切换,需要看到导航)
                setBottomNavVisible(true)
            }
        })

        // 恢复持久化的 Cookie
        HttpClient.getInstance().init(this)

        // ★ 消息角标初始化
        mainHandler = Handler(Looper.getMainLooper())
        executor = Executors.newFixedThreadPool(7)
        // build83: 注册分类查看回调, 进入消息分类后本地即时清红点
        sInstance = this
        NoticeBadgeManager.setOnViewedListener(object : NoticeBadgeManager.OnViewedListener {
            override fun onViewed(viewType: String?) {
                onNoticeViewed(viewType)
            }
        })
        tvMessageBadge = findViewById(R.id.nav_message_badge)
        if (tvMessageBadge != null) {
            tvMessageBadge!!.visibility = View.GONE
            refreshMessageBadge()
        }

        // 初始化导航视图引用（含凸起发布按钮）
        initNavigationBar()

        // 初始化侧边栏
        initDrawer()
    }

    // ==================== 侧边栏 ====================

    private var drawerLayout: DrawerLayout? = null
    private var drawerPanel: View? = null
    private var swSilent: SwitchMaterial? = null
    private var swSignIn: SwitchMaterial? = null
    private var swUnlock: SwitchMaterial? = null
    private var swDryRun: SwitchMaterial? = null
    private var swAutoHideNav: SwitchMaterial? = null
    private var tvDrawerName: TextView? = null
    private var tvDrawerSubtitle: TextView? = null
    private var ivDrawerAvatar: ImageView? = null

    /** 侧边栏开关的读写/跳转 */
    private fun initDrawer() {
        val root = findViewById<View>(R.id.main_drawer)
        if (root !is DrawerLayout) {
            android.util.Log.e("MainActivity", "DrawerLayout not found")
            return
        }
        drawerLayout = root
        // 遮罩加深，抽屉打开时右侧主内容不会透出文字
        drawerLayout!!.setScrimColor(0xC0000000.toInt())
        enhanceDrawerEdgeSwipe(drawerLayout!!)

        // 打开侧边栏时藏掉底部悬浮导航栏，否则两边的文字会叠在一起
        drawerLayout!!.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(dv: View) {
                // build71: 改走统一入口,保证 navHidden 标记与实际可见性永远一致
                setBottomNavVisible(false)
            }

            override fun onDrawerClosed(dv: View) {
                // build71: 抽屉关闭强制恢复(navHidden 为 true 时不会被短路判断挡掉)
                setBottomNavVisible(true)
            }
        })

        drawerPanel = findViewById(R.id.drawer_panel)
        swSilent = findViewById(R.id.drawer_switch_silent)
        swSignIn = findViewById(R.id.drawer_switch_sign_in)
        swUnlock = findViewById(R.id.drawer_switch_unlock)
        swDryRun = findViewById(R.id.drawer_switch_dry_run)
        swAutoHideNav = findViewById(R.id.drawer_switch_auto_hide_nav)
        tvDrawerName = findViewById(R.id.drawer_username)
        tvDrawerSubtitle = findViewById(R.id.drawer_subtitle)
        ivDrawerAvatar = findViewById(R.id.drawer_avatar)
        // build57: 侧边栏头部(头像/用户名/UID行)点击进自己主页
        val ownProfile = View.OnClickListener { openOwnProfile() }
        ivDrawerAvatar!!.setOnClickListener(ownProfile)
        if (tvDrawerName != null) tvDrawerName!!.setOnClickListener(ownProfile)
        if (tvDrawerSubtitle != null) tvDrawerSubtitle!!.setOnClickListener(ownProfile)

        // 左上角入口打开侧边栏
        val openBtn = findViewById<View>(R.id.btn_open_drawer)
        if (openBtn != null) {
            openBtn.setOnClickListener { drawerLayout!!.openDrawer(drawerPanel!!) }
        }

        bindSwitchRow(R.id.drawer_silent_row, swSilent)
        bindSwitchRow(R.id.drawer_sign_in_row, swSignIn)

        // 静默模式开关
        if (swSilent != null) {
            swSilent!!.isChecked = AiConfigManager.isSilentMode(this)
            swSilent!!.setOnCheckedChangeListener { v, checked ->
                AiConfigManager.setSilentMode(this, checked)
                AiLog.i("drawer", "隐藏运行 " + (if (checked) "开启" else "关闭"))
            }
        }

        // 自动签到开关：复用既有 AutoSignInManager
        if (swSignIn != null) {
            swSignIn!!.isChecked = AutoSignInManager.isEnabled(this)
            swSignIn!!.setOnCheckedChangeListener { v, checked ->
                AutoSignInManager.setEnabled(this, checked)
                AiLog.i("drawer", "自动签到 " + (if (checked) "开启" else "关闭"))
                if (checked) {
                    doSignInNow()
                }
            }
        }

        // 自动解锁隐藏内容：一键控制「解锁模式 + 进帖自动解锁」
        bindSwitchRow(R.id.drawer_unlock_row, swUnlock)
        if (swUnlock != null) {
            swUnlock!!.isChecked = AiConfigManager.isUnlockMode(this)
            updateUnlockDesc()
            swUnlock!!.setOnCheckedChangeListener { v, checked ->
                AiConfigManager.setUnlockMode(this, checked)
                AiConfigManager.setUnlockOnView(this, checked)
                updateUnlockDesc()
                AiLog.i("drawer", "自动解锁隐藏内容 " + (if (checked) "开启" else "关闭"))
                Toast.makeText(
                    this, if (checked)
                        "已开启：进含「回复可见」的帖子会自动回帖解锁"
                    else
                        "已关闭自动解锁", Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 演练模式：自动回复只生成不发送（不影响进帖解锁）
        bindSwitchRow(R.id.drawer_dry_run_row, swDryRun)
        if (swDryRun != null) {
            swDryRun!!.isChecked = AiConfigManager.isDryRun(this)
            swDryRun!!.setOnCheckedChangeListener { v, checked ->
                AiConfigManager.setDryRun(this, checked)
                AiLog.i("drawer", "演练模式 " + (if (checked) "开启" else "关闭"))
                Toast.makeText(
                    this, if (checked)
                        "演练模式：自动回复只生成不发送"
                    else
                        "已关闭演练模式，自动回复将真实发送", Toast.LENGTH_SHORT
                ).show()
            }
        }

        // build72: 底部栏滚动自动隐藏开关
        bindSwitchRow(R.id.drawer_auto_hide_nav_row, swAutoHideNav)
        if (swAutoHideNav != null) {
            autoHideNavEnabled = AiConfigManager.isAutoHideNav(this)
            swAutoHideNav!!.isChecked = autoHideNavEnabled
            if (!autoHideNavEnabled) {
                setBottomNavVisible(true)
            }
            swAutoHideNav!!.setOnCheckedChangeListener { v, checked ->
                autoHideNavEnabled = checked
                AiConfigManager.setAutoHideNav(this, checked)
                if (checked) {
                    setBottomNavVisible(true)
                    Toast.makeText(this, "已开启：刷帖时底部栏自动隐藏", Toast.LENGTH_SHORT).show()
                } else {
                    setBottomNavVisible(true)
                    Toast.makeText(this, "已关闭：底部栏常驻显示", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 解锁回复内容自定义：点击弹对话框编辑模板
        val unlockTextRow = findViewById<View>(R.id.drawer_unlock_text_row)
        if (unlockTextRow != null) {
            updateUnlockTextDesc()
            unlockTextRow.setOnClickListener { showUnlockTextDialog() }
        }

        // 立即执行一轮自动回复
        val runReply = findViewById<View>(R.id.drawer_run_reply)
        if (runReply != null) {
            runReply.setOnClickListener {
                drawerLayout!!.closeDrawer(drawerPanel!!)
                Toast.makeText(this, "开始执行一轮自动回复…", Toast.LENGTH_SHORT).show()
                AutoReplyEngine.runOnce(this, object : AutoReplyEngine.Callback {
                    override fun onFinished(replied: Int, skipped: Int, detail: String?) {
                        Toast.makeText(
                            this@MainActivity,
                            "本轮：回复 " + replied + " 条，跳过 " + skipped + " 条\n" + detail,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
        }

        // 立即签到
        val runSignIn = findViewById<View>(R.id.drawer_run_sign_in)
        if (runSignIn != null) {
            runSignIn.setOnClickListener { doSignInNow() }
        }

        // 切换账号
        val accountsRow = findViewById<View>(R.id.drawer_accounts)
        if (accountsRow != null) {
            accountsRow.setOnClickListener { showAccountSwitcher() }
        }

        // 个人小黑屋
        val blacklistRow = findViewById<View>(R.id.drawer_blacklist)
        if (blacklistRow != null) {
            blacklistRow.setOnClickListener {
                drawerLayout!!.closeDrawer(drawerPanel!!)
                startActivity(
                    Intent(
                        this@MainActivity,
                        com.solosu.mtforum.ui.BlacklistActivity::class.java
                    )
                )
            }
        }

        // 主题色彩切换
        val themeColorRow = findViewById<View>(R.id.drawer_theme_color)
        val tvThemeColorDesc = findViewById<TextView>(R.id.drawer_theme_color_desc)
        if (themeColorRow != null) {
            val cur = com.solosu.mtforum.util.ThemeManager.getCurrentThemeColor(this)
            tvThemeColorDesc?.text = "当前：${cur.name}"
            themeColorRow.setOnClickListener {
                drawerLayout!!.closeDrawer(drawerPanel!!)
                com.solosu.mtforum.util.ThemeManager.showColorPickerDialog(this)
            }
        }

        // 设置
        val settings = findViewById<View>(R.id.drawer_settings)
        if (settings != null) {
            settings.setOnClickListener {
                drawerLayout!!.closeDrawer(drawerPanel!!)
                startActivity(
                    Intent(
                        this@MainActivity,
                        com.solosu.mtforum.ui.space.SettingsActivity::class.java
                    )
                )
            }
        }

        // 运行日志
        val logView = findViewById<View>(R.id.drawer_log)
        if (logView != null) {
            logView.setOnClickListener { showRunLog() }
        }

        refreshDrawerHeader()
    }

    /** 整行点击等于切换开关 */
    private fun bindSwitchRow(rowId: Int, sw: SwitchMaterial?) {
        if (sw == null) return
        val row = findViewById<View>(rowId)
        if (row == null) return
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    /** 侧边栏「自动解锁」副标题：按当前模式显示正在干什么 */
    private fun updateUnlockDesc() {
        val desc = findViewById<TextView>(R.id.drawer_unlock_desc)
        if (desc == null) return
        desc.text = if (AiConfigManager.isUnlockMode(this))
            "进帖遇「回复可见」自动回帖解锁"
        else
            "已关闭，不自动解锁"
    }

    /** 侧边栏「解锁回复内容」副标题：显示当前是自定义还是默认模板 */
    private fun updateUnlockTextDesc() {
        val desc = findViewById<TextView>(R.id.drawer_unlock_text_desc)
        if (desc == null) return
        val custom = AiConfigManager.getUnlockReplyTemplate(this)
        desc.text = if (android.text.TextUtils.isEmpty(custom))
            "默认模板（点击自定义）"
        else
            "自定义：" + (if (custom.length > 20) custom.substring(0, 20) + "…" else custom)
    }

    /** 弹出对话框编辑解锁回复模板，{title} 会被替换为帖子标题关键词 */
    private fun showUnlockTextDialog() {
        val et = android.widget.EditText(this)
        et.setText(AiConfigManager.getUnlockReplyTemplate(this))
        et.setHint("例如：感谢分享「{title}」，正需要这个！")
        et.setMinLines(2)
        et.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        val pad = (16 * resources.displayMetrics.density).toInt()
        et.setPadding(pad, pad, pad, pad)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("解锁回复内容")
            .setMessage("自定义自动解锁时发送的回复。\n留空则用内置模板池随机选一条。\n用 {title} 插入帖子标题关键词。")
            .setView(et)
            .setPositiveButton("保存") { d, w ->
                val v = et.text.toString().trim()
                AiConfigManager.setUnlockReplyTemplate(this, v)
                updateUnlockTextDesc()
                AiLog.i("drawer", "解锁回复模板已更新：" + (if (v.isEmpty()) "（恢复默认）" else v))
                Toast.makeText(
                    this,
                    if (v.isEmpty()) "已恢复默认模板" else "已保存自定义回复",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 账号切换弹窗:当前账号高亮,点选切换,支持登录新账号/删除 */
    private fun showAccountSwitcher() {
        var accounts: List<com.solosu.mtforum.session.AccountManager.Account> =
            com.solosu.mtforum.session.AccountManager.list(this)
        var activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this)
        val curName = UserSessionManager.getInstance().getUsername(this)
        val curUid = UserSessionManager.getInstance().getUid(this)
        val curLogged = UserSessionManager.getInstance().isLoggedIn(this)

        // 若当前登录账号未入库(比如老用户),先补存
        if (curLogged && !android.text.TextUtils.isEmpty(curUid)) {
            com.solosu.mtforum.session.AccountManager.saveCurrent(
                this, curUid, curName,
                UserSessionManager.getInstance().getAvatarUrl(this),
                UserSessionManager.getInstance().getLevel(this)
            )
            accounts = com.solosu.mtforum.session.AccountManager.list(this)
            activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this)
        }
        val fAccounts = accounts
        val fActiveUid = activeUid

        val labels = ArrayList<String>()
        for (a in fAccounts) {
            val mark = if (fActiveUid != null && fActiveUid == a.uid) "  [当前]" else ""
            labels.add(a.username + mark)
        }
        labels.add("＋ 登录新账号")

        val arr = labels.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换账号")
            .setItems(arr) { d, which ->
                if (which == fAccounts.size) {
                    // 登录新账号:先保存当前,再弹登录
                    drawerLayout!!.closeDrawer(drawerPanel!!)
                    com.solosu.mtforum.ui.login.LoginBottomSheet.show(this) {
                        // 登录成功后入库并刷新
                        val n = UserSessionManager.getInstance().getUsername(this)
                        val u = UserSessionManager.getInstance().getUid(this)
                        if (!android.text.TextUtils.isEmpty(u)) {
                            com.solosu.mtforum.session.AccountManager.saveCurrent(
                                this, u, n,
                                UserSessionManager.getInstance().getAvatarUrl(this),
                                UserSessionManager.getInstance().getLevel(this)
                            )
                        }
                        refreshDrawerHeader()
                    }
                    return@setItems
                }
                val target = fAccounts[which]
                if (target.uid == curUid && curLogged) {
                    Toast.makeText(this, "已是当前账号", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                // 切换:cookie 快照回灌 + 更新 UserSessionManager 展示层
                val ok = com.solosu.mtforum.session.AccountManager.switchTo(this, target.uid)
                if (ok) {
                    val info = HashMap<String, String>()
                    info["username"] = target.username!!
                    info["uid"] = target.uid!!
                    info["avatarUrl"] = if (target.avatar != null) target.avatar!! else ""
                    info["level"] = if (target.level != null) target.level!! else ""
                    UserSessionManager.getInstance().saveLoginInfo(this, info)
                    Toast.makeText(this, "已切换到 " + target.username, Toast.LENGTH_SHORT).show()
                    refreshDrawerHeader()
                } else {
                    Toast.makeText(this, "切换失败，请重新登录该账号", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openOwnProfile() {
        val session = UserSessionManager.getInstance()
        if (!session.isLoggedIn(this)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = session.getUid(this)
        if (uid == null || uid.isEmpty()) {
            Toast.makeText(this, "缺少UID", Toast.LENGTH_SHORT).show()
            return
        }
        val it = Intent(this, com.solosu.mtforum.ui.space.UserProfileActivity::class.java)
        it.putExtra("uid", uid)
        it.putExtra("username", session.getUsername(this))
        if (drawerLayout != null && drawerPanel != null) {
            drawerLayout!!.closeDrawer(drawerPanel!!)
        }
        startActivity(it)
    }

    private fun refreshDrawerHeader() {
        if (tvDrawerName == null) return
        val session = UserSessionManager.getInstance()
        val logged = session.isLoggedIn(this)
        val name = session.getUsername(this)
        tvDrawerName!!.text = if (!logged || android.text.TextUtils.isEmpty(name)) "未登录" else name

        if (tvDrawerSubtitle != null) {
            val uid = session.getUid(this)
            if (logged && !android.text.TextUtils.isEmpty(uid)) {
                val shield = session.getLevel(this)
                // build57: level 存储值可能自带 Lv 前缀(ForumParser 抓整行文本), 剥掉防 Lv.Lv.
                var lv = if (shield == null) "" else shield.trim()
                if (lv.length >= 2 && (lv[0] == 'L' || lv[0] == 'l')
                    && (lv[1] == 'V' || lv[1] == 'v')
                ) {
                    lv = lv.substring(2)
                    while (lv.startsWith(".") || lv.startsWith(".")) lv = lv.substring(1)
                    lv = lv.trim()
                }
                tvDrawerSubtitle!!.text = if (android.text.TextUtils.isEmpty(lv))
                    "UID " + uid else "UID " + uid + " · Lv." + lv
            } else {
                tvDrawerSubtitle!!.text = "点击侧边栏开启自动化"
            }
        }


        if (ivDrawerAvatar != null) {
            val avatar = session.getAvatarUrl(this)
            if (logged && !android.text.TextUtils.isEmpty(avatar)) {
                com.bumptech.glide.Glide.with(this)
                    .load(avatar)
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(ivDrawerAvatar!!)
            }
        }
    }

    /** 立即签到一次（无论开关状态） */
    private fun doSignInNow() {
        drawerLayout!!.closeDrawer(drawerPanel!!)
        AutoSignInManager.checkAndSignIn(this, object : AutoSignInManager.Callback {
            override fun onFinished(success: Boolean, performed: Boolean, message: String) {
                if (isFinishing() || isDestroyed()) return
                AiLog.i("sign-in", "success=" + success + " performed=" + performed + " " + message)
                Toast.makeText(this@MainActivity, if (message == null) "签到完成" else message, Toast.LENGTH_SHORT).show()
                if (success) {
                    com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn()
                }
            }
        })
    }

    /** 弹出运行日志 */
    private fun showRunLog() {
        val text = AiLog.dump()
        val sv = android.widget.ScrollView(this)
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(11f)
        tv.setTextIsSelectable(true)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        val pad = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        sv.addView(tv)

        val path = AiLog.filePath()
        val hint = if (android.text.TextUtils.isEmpty(path))
            "" else "\n\n日志文件：\n" + path

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("运行日志（" + AiLog.size() + " 条）")
            .setMessage(if (hint.trim().isEmpty()) null else hint)
            .setView(sv)
            .setNeutralButton("复制") { d, w ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "mtforum-log", text + hint
                        )
                    )
                    Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("清空") { d, w ->
                AiLog.clear()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 初始化悬浮胶囊导航栏（5项：首页/版块/凸起发布/消息/我的）
     */
    private fun initNavigationBar() {
        navHome = findViewById(R.id.nav_home_tab)
        navCommunity = findViewById(R.id.nav_community_tab)
        navPost = findViewById(R.id.nav_post_tab)
        navMessage = findViewById(R.id.nav_message_tab)
        navProfile = findViewById(R.id.nav_profile_tab)

        ivHomeIcon = findViewById(R.id.nav_home_icon)
        ivCommunityIcon = findViewById(R.id.nav_community_icon)
        ivPostIcon = findViewById(R.id.nav_post_icon)
        ivMessageIcon = findViewById(R.id.nav_message_icon)
        ivProfileIcon = findViewById(R.id.nav_profile_icon)

        tvHomeText = findViewById(R.id.nav_home_text)
        tvCommunityText = findViewById(R.id.nav_community_text)
        tvMessageText = findViewById(R.id.nav_message_text)
        tvProfileText = findViewById(R.id.nav_profile_text)

        // 空指针保护
        if (navHome == null || navCommunity == null || navPost == null || navMessage == null || navProfile == null) {
            android.util.Log.e("MainActivity", "Navigation bar views are null")
            return
        }
        if (ivHomeIcon == null || ivCommunityIcon == null || ivPostIcon == null || ivMessageIcon == null || ivProfileIcon == null) {
            android.util.Log.e("MainActivity", "Navigation bar icons are null")
            return
        }
        if (tvHomeText == null || tvCommunityText == null || tvMessageText == null || tvProfileText == null) {
            android.util.Log.e("MainActivity", "Navigation bar texts are null")
            return
        }

        // 前4项 — 首页/版块/我的使用NavController导航；带现代回弹微动效
        val applyTabClick: (View, Int) -> Unit = { view, pos ->
            view.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(90)
                .withEndAction {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                    switchPage(pos)
                }
                .start()
        }

        navHome!!.setOnClickListener { applyTabClick(it, MainPagerAdapter.PAGE_HOME) }
        navCommunity!!.setOnClickListener { applyTabClick(it, MainPagerAdapter.PAGE_COMMUNITY) }
        navMessage!!.setOnClickListener { applyTabClick(it, MainPagerAdapter.PAGE_MESSAGE) }
        navProfile!!.setOnClickListener { applyTabClick(it, MainPagerAdapter.PAGE_PROFILE) }

        // 中间凸起发布按钮 — 带旋转回弹微动效
        navPost!!.setOnClickListener {
            navPost!!.animate()
                .scaleX(0.86f)
                .scaleY(0.86f)
                .rotation(45f)
                .setDuration(110)
                .withEndAction {
                    navPost!!.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotation(0f)
                        .setDuration(200)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                    val intent = Intent(this@MainActivity, PostActivity::class.java)
                    startActivity(intent)
                }
                .start()
        }

        // 中间发布按钮动态应用当前主题色微渐变
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        val themeLight = com.solosu.mtforum.util.ThemeManager.getThemeLightColor(this)
        val centerBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(themeColor, themeLight)
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        ivPostIcon?.background = centerBg

        // 初始选中状态
        updateNavSelectionByPosition(MainPagerAdapter.PAGE_HOME)
        setupDrawerSwipeConflict()
    }

    /**
     * 导航到指定目的地（仅用于导航栏非发布项）
     */
    private fun switchPage(position: Int) {
        if (mainPager != null) {
            mainPager!!.setCurrentItem(position, true)
        }
        updateNavSelectionByPosition(position)
        // build71: 用户主动点标签 -> 底部栏必须显示
        setBottomNavVisible(true)
    }

    /**
     * 扩大 DrawerLayout 的边缘触摸滑动呼出范围，让右滑呼出侧边栏更灵敏
     */
    private fun enhanceDrawerEdgeSwipe(drawer: androidx.drawerlayout.widget.DrawerLayout) {
        try {
            val leftDraggerField = androidx.drawerlayout.widget.DrawerLayout::class.java.getDeclaredField("mLeftDragger")
            leftDraggerField.isAccessible = true
            val leftDragger = leftDraggerField.get(drawer) as? androidx.customview.widget.ViewDragHelper ?: return

            val edgeSizeField = leftDragger.javaClass.getDeclaredField("mEdgeSize")
            edgeSizeField.isAccessible = true
            val defaultEdge = edgeSizeField.getInt(leftDragger)

            val density = resources.displayMetrics.density
            val newEdge = (72 * density).toInt().coerceAtLeast(defaultEdge)
            edgeSizeField.setInt(leftDragger, newEdge)

            try {
                val defaultEdgeSizeField = leftDragger.javaClass.getDeclaredField("mDefaultEdgeSize")
                defaultEdgeSizeField.isAccessible = true
                defaultEdgeSizeField.setInt(leftDragger, newEdge)
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "Failed to enhance drawer edge swipe: ${e.message}")
        }
    }

    /**
     * 解决 ViewPager2 与 DrawerLayout 右滑手势冲突：在首页左边缘向右滑时优先拉出侧边栏
     */
    private fun setupDrawerSwipeConflict() {
        val pager = mainPager ?: return
        val density = resources.displayMetrics.density
        val edgeThreshold = 80 * density
        var startX = 0f
        var startY = 0f

        val recyclerView = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX
                        startY = e.rawY
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - startX
                        val dy = e.rawY - startY
                        if (pager.currentItem == 0 && startX <= edgeThreshold && dx > 0 && Math.abs(dx) > Math.abs(dy)) {
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                    }
                }
                return false
            }
        })
    }

    /**
     * 降低 ViewPager2 左右滑动的灵敏度，避免手指轻微倾斜误触发横向切页导致上下滑动卡顿
     */
    private fun reduceViewPager2Sensitivity(viewPager: ViewPager2, multiplier: Float) {
        try {
            val recyclerView = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
            val touchSlopField = androidx.recyclerview.widget.RecyclerView::class.java.getDeclaredField("mTouchSlop")
            touchSlopField.isAccessible = true
            val currentTouchSlop = touchSlopField.getInt(recyclerView)
            touchSlopField.setInt(recyclerView, (currentTouchSlop * multiplier).toInt())
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "Failed to reduce ViewPager2 sensitivity: ${e.message}")
        }
    }

    // ==================== build71: 底部导航栏滚动自动隐藏 ====================

    /** 显示/隐藏底部导航栏(带动画) */
    private fun setBottomNavVisible(show: Boolean) {
        val nav = findViewById<View>(R.id.bottom_nav_container)
        if (nav == null) return
        if (show) {
            if (!navHidden && nav.visibility == View.VISIBLE) return
            navHidden = false
            nav.visibility = View.VISIBLE
            nav.animate().translationY(0f).setDuration(NAV_HIDE_ANIM_MS)
                .setListener(null).start()
        } else {
            if (navHidden) return
            navHidden = true
            val hidden = if (nav.height > 0) nav.height + dp(24f) else dp(92f)
            nav.animate().translationY(hidden).setDuration(NAV_HIDE_ANIM_MS)
                .withEndAction {
                    if (navHidden) nav.visibility = View.GONE
                }.start()
        }
    }

    private fun dp(v: Float): Float {
        return v * resources.displayMetrics.density
    }

    /**
     * 滚动方向判定(供各页 Fragment 通过 NavBarAutoHideHelper 转发进来)。
     * 手指上滑(dy>0): 用户在往下看内容 -> 藏;
     * 手指下滑(dy<0): 用户想回上面或找导航 -> 露。
     * 停顿超过 400ms 重新累计,避免来回抖动。
     */
    fun onNavScroll(dy: Int) {
        // build72: 侧边栏开关关闭 -> 底部栏常驻,不参与自动隐藏
        if (!autoHideNavEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastNavScrollAt > 400L) {
            navScrollAccum = 0
        }
        lastNavScrollAt = now
        if (dy > 0) {
            if (navScrollAccum < 0) navScrollAccum = 0
            navScrollAccum += dy
            if (navScrollAccum > 24) {
                setBottomNavVisible(false)
            }
        } else if (dy < 0) {
            if (navScrollAccum > 0) navScrollAccum = 0
            navScrollAccum += dy
            if (navScrollAccum < -8) {
                setBottomNavVisible(true)
            }
        }
    }

    /**
     * 更新导航栏选中状态
     */
    private fun updateNavSelectionByPosition(position: Int) {
        resetAllSelection()

        when (position) {
            MainPagerAdapter.PAGE_HOME -> setItemActive(ivHomeIcon, tvHomeText)
            MainPagerAdapter.PAGE_COMMUNITY -> setItemActive(ivCommunityIcon, tvCommunityText)
            MainPagerAdapter.PAGE_MESSAGE -> setItemActive(ivMessageIcon, tvMessageText)
            MainPagerAdapter.PAGE_PROFILE -> setItemActive(ivProfileIcon, tvProfileText)
            else -> {
            }
        }
    }

    /**
     * 重置所有导航项为未选中状态
     */
    private fun resetAllSelection() {
        setItemInactive(ivHomeIcon, tvHomeText)
        setItemInactive(ivCommunityIcon, tvCommunityText)
        setItemInactive(ivMessageIcon, tvMessageText)
        setItemInactive(ivProfileIcon, tvProfileText)
    }

    private fun setItemActive(icon: ImageView?, text: TextView?) {
        if (icon == null || text == null) return
        val activeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(activeColor))
        text.setTextColor(activeColor)
        text.setTypeface(null, Typeface.BOLD)

        // 现代弹性微缩放与微上浮动效
        icon.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .translationY(-dp(2f))
            .setDuration(190)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()

        text.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .translationY(-dp(1f))
            .setDuration(190)
            .start()
    }

    private fun setItemInactive(icon: ImageView?, text: TextView?) {
        if (icon == null || text == null) return
        val defaultColor = ContextCompat.getColor(this, R.color.nav_icon_default)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(defaultColor))
        text.setTextColor(ContextCompat.getColor(this, R.color.nav_text_default))
        text.setTypeface(null, Typeface.NORMAL)

        icon.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationY(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()

        text.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationY(0f)
            .setDuration(160)
            .start()
    }

    private fun getActiveIconRes(viewId: Int): Int {
        if (viewId == R.id.nav_home_icon) {
            return R.drawable.ic_home
        } else if (viewId == R.id.nav_community_icon) {
            return R.drawable.ic_discover
        } else if (viewId == R.id.nav_message_icon) {
            return R.drawable.ic_message
        } else if (viewId == R.id.nav_profile_icon) {
            return R.drawable.ic_profile
        }
        return 0
    }

    private fun getInactiveIconRes(viewId: Int): Int {
        return getActiveIconRes(viewId)
    }

    // ==================== 消息角标 ====================

    /**
     * 刷新消息角标（获取未读PM数）
     */
    private fun refreshMessageBadge() {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return
        if (!HttpClient.getInstance().isLoggedIn()) {
            badgeRefreshInFlight.set(false)
            if (tvMessageBadge != null) tvMessageBadge!!.visibility = View.GONE
            scheduleNextBadgeRefresh()
            return
        }
        // 防止上一次网络刷新尚未结束时重复提交任务。
        if (!badgeRefreshInFlight.compareAndSet(false, true)) return
        lastBadgeRefreshAt = System.currentTimeMillis() // build68: 记录本次刷新时刻
        if (executor == null || executor!!.isShutdown || executor!!.isTerminated) {
            badgeRefreshInFlight.set(false)
            scheduleNextBadgeRefresh()
            return
        }

        // 六类列表并行请求；原来单线程逐个请求，尤其 PM 还会请求会话详情，导致角标延迟明显。
        try {
            executor!!.execute {
                val counts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
                val fingerprints: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
                val types = arrayOf("pm", "follower", "mypost", "interactive", "system", "app")
                try {
                    HttpClient.getInstance().syncFromCookieManager()
                    val latch = CountDownLatch(types.size)
                    for (type in types) {
                        val viewType = type
                        val url: String
                        if ("pm" == viewType) {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2"
                        } else if ("follower" == viewType) {
                            url = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid=" +
                                    currentUid + "&mobile=2"
                        } else {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + viewType
                        }
                        try {
                            executor!!.execute {
                                try {
                                    putUnreadData(counts, fingerprints, viewType, url)
                                } finally {
                                    latch.countDown()
                                }
                            }
                        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                            latch.countDown()
                        }
                    }
                    latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
                    runOnUiThread {
                        badgeRefreshInFlight.set(false)
                        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return@runOnUiThread
                        applyUnreadCounts(counts, fingerprints)
                    }
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runOnUiThread {
                        badgeRefreshInFlight.set(false)
                        if (!isFinishing()) scheduleNextBadgeRefresh()
                    }
                } catch (ignored: Exception) {
                    runOnUiThread {
                        badgeRefreshInFlight.set(false)
                        if (!isFinishing()) scheduleNextBadgeRefresh()
                    }
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            badgeRefreshInFlight.set(false)
            scheduleNextBadgeRefresh()
        }
    }

    private val currentUid: String
        get() {
            val uid = com.solosu.mtforum.session.UserSessionManager
                .getInstance().getUid(this)
            return if (uid == null || uid.isEmpty()) "0" else uid
        }

    private fun fetchUnreadCount(url: String, viewType: String): Int {
        try {
            val html = if ("pm" == viewType || "follower" == viewType)
                HttpClient.getInstance().get(url)
            else
                HttpClient.getInstance().getDesktop(url)
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) return 0
            var items: MutableList<com.solosu.mtforum.model.Message>
            if ("pm" == viewType) items = ForumParser.parsePmList(html)
            else if ("follower" == viewType) items = ForumParser.parseFollowerList(html)
            else items = ForumParser.parseNoticeList(html)
            var count = 0
            for (item in items) {
                if (!item.isRead) count++
            }
            return count
        } catch (ignored: Exception) {
            return 0
        }
    }

    private fun putUnreadData(
        counts: MutableMap<String, Int>,
        fingerprints: MutableMap<String, String>,
        viewType: String, url: String
    ) {
        try {
            val html = if ("pm" == viewType || "follower" == viewType)
                HttpClient.getInstance().get(url)
            else
                HttpClient.getInstance().getDesktop(url)
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) {
                counts[viewType] = 0
                fingerprints[viewType] = ""
                return
            }

            // 与 NoticeFragment 使用同一套“当前列表 - 已查看快照”算法。
            val snapshot = NoticeBadgeManager.buildSnapshot(
                viewType, html, HttpClient.getInstance()
            )
            val count = NoticeBadgeManager.saveCurrentAndGetNewCount(
                this@MainActivity, viewType, snapshot
            )
            counts[viewType] = count
            fingerprints[viewType] = snapshot
        } catch (ignored: Exception) {
            counts[viewType] = 0
            fingerprints[viewType] = ""
        }
    }

    private fun applyUnreadCounts(
        counts: MutableMap<String, Int>,
        fingerprints: MutableMap<String, String>
    ) {
        // 底部导航栏显示所有消息分类的新增总数，不能只显示私信数量。
        var totalCount = 0
        val allTypes = arrayOf("pm", "follower", "mypost", "interactive", "system", "app")
        for (type in allTypes) {
            totalCount += getCount(counts, type)
        }
        updateBadgeDisplay(totalCount)

        if (unreadBaselineReady) {
            val hasNewPrivateChat = hasNewUnread("pm", counts, fingerprints)
            var hasNewOtherNotice = false
            val otherTypes = arrayOf("follower", "mypost", "interactive", "system", "app")
            for (type in otherTypes) {
                if (hasNewUnread(type, counts, fingerprints)) {
                    hasNewOtherNotice = true
                    break
                }
            }
            // 音频已移除
        }

        previousUnreadCounts.clear()
        previousUnreadCounts.putAll(counts)
        previousUnreadFingerprints.clear()
        previousUnreadFingerprints.putAll(fingerprints)
        unreadBaselineReady = true
        scheduleNextBadgeRefresh()
    }

    private fun hasNewUnread(
        type: String, counts: MutableMap<String, Int>,
        fingerprints: MutableMap<String, String>
    ): Boolean {
        val currentCount = getCount(counts, type)
        val oldCount = getCount(previousUnreadCounts, type)
        val currentFingerprint = fingerprints[type]
        val oldFingerprint = previousUnreadFingerprints[type]
        return currentCount > oldCount ||
                (currentCount > 0 && java.util.Objects.equals(currentFingerprint, oldFingerprint).not())
    }

    private fun getCount(counts: Map<String, Int>, key: String): Int {
        val value = counts[key]
        return value ?: 0
    }

    private fun updateBadgeDisplay(count: Int) {
        renderBadge(count)
        scheduleNextBadgeRefresh()
    }

    /** build83: 只渲染红点, 不重置 60s 轮询计时 */
    private fun renderBadge(count: Int) {
        if (tvMessageBadge == null) return
        if (count > 0) {
            tvMessageBadge!!.visibility = View.VISIBLE
            tvMessageBadge!!.text = if (count > 99) "99+" else count.toString()
        } else {
            tvMessageBadge!!.visibility = View.GONE
        }
    }

    /** build83: 消息分类被查看 → 本地即时清零该分类计数并重算总数, 红点立即消失, 不发任何请求 */
    private fun onNoticeViewed(viewType: String?) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            val t = viewType
            runOnUiThread { onNoticeViewed(t) }
            return
        }
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return
        if ("*" == viewType) {
            for (type in BADGE_TYPES) previousUnreadCounts[type] = 0
        } else if (viewType != null && !viewType.isEmpty()) {
            previousUnreadCounts[viewType] = 0
        } else {
            return
        }
        var total = 0
        for (type in BADGE_TYPES) total += getCount(previousUnreadCounts, type)
        renderBadge(total)
    }

    private fun scheduleNextBadgeRefresh() {
        mainHandler!!.removeCallbacks(badgeRunnable)
        mainHandler!!.postDelayed(badgeRunnable, BADGE_REFRESH_INTERVAL_MS)
    }

    private val badgeRunnable = Runnable { refreshMessageBadge() }

    override fun onStart() {
        super.onStart()
        AutoSignInManager.checkAndSignIn(this, object : AutoSignInManager.Callback {
            override fun onFinished(success: Boolean, performed: Boolean, message: String) {
                if (success || "今日已签到" == message) {
                    // 通知社区页刷新签到按钮状态
                    com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn()
                }
                if (performed && success && !isFinishing()) {
                    Toast.makeText(this@MainActivity, "自动签到成功", Toast.LENGTH_SHORT).show()
                } else if (!performed && !isFinishing() && "请先登录" == message) {
                    // 未登录时静默等待,不弹 Toast 打扰用户
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // build68: 从消息页/后台返回时刷新，但加 60 秒节流——频繁返回不再重复全量拉 6 类(防 ESA 403)。
        // 角标本身已有 60 秒定时轮询兜底, 这里的即时刷新只是锦上添花。
        if (mainHandler != null && tvMessageBadge != null) {
            val now = System.currentTimeMillis()
            if (now - lastBadgeRefreshAt >= BADGE_RESUME_THROTTLE_MS) {
                mainHandler!!.removeCallbacks(badgeRunnable)
                refreshMessageBadge()
            }
        }
        // 登录状态 / AI 配置可能已变化，刷新侧边栏头部
        refreshDrawerHeader()
    }

    override fun onStop() {
        if (mainHandler != null) mainHandler!!.removeCallbacks(badgeRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        if (frostedNavBackground != null) {
            frostedNavBackground!!.setVisible(false, false)
        }
        if (sInstance === this) {
            sInstance = null
            NoticeBadgeManager.setOnViewedListener(null)
        }
        if (mainHandler != null) {
            mainHandler!!.removeCallbacksAndMessages(null)
        }
        if (executor != null) {
            executor!!.shutdownNow()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 侧边栏开着时，返回键先收起侧边栏
        if (drawerLayout != null && drawerPanel != null
            && drawerLayout!!.isDrawerOpen(drawerPanel!!)
        ) {
            drawerLayout!!.closeDrawer(drawerPanel!!)
            return
        }
        // 首次返回提示，连续第二次返回才退出
        val now = System.currentTimeMillis()
        if (lastBackPressTime > 0 && now - lastBackPressTime < EXIT_INTERVAL_MS) {
            finishAffinity() // 直接退出
            super.onBackPressed()
            return
        }
        lastBackPressTime = now
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val NAV_HIDE_ANIM_MS = 200L

        private const val BADGE_REFRESH_INTERVAL_MS = 60000L // 60秒刷新一次(降频防ESA 403,6类并行=6发/分钟)
        private const val BADGE_RESUME_THROTTLE_MS = 60000L // build68: onResume 即时刷新节流(防频繁返回重复拉6类)

        /** build83: 分类被查看时本地即时清零对应红点(零额外请求) */
        private val BADGE_TYPES = arrayOf("pm", "follower", "mypost", "interactive", "system", "app")

        private var sInstance: MainActivity? = null

        // 双击返回退出
        private const val EXIT_INTERVAL_MS = 2000L
    }
}
