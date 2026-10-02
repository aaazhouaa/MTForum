package com.solosu.mtforum.ui.message

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment

import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.FragmentNoticeBinding
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.network.NoticeBadgeManager
import com.solosu.mtforum.session.UserSessionManager
import com.solosu.mtforum.ui.space.FriendListActivity

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 消息页(ViewPager 页)。
 * “消息”是底部导航页;“我的消息”是本页面中的私信分类,二者不能混淆。
 * 所有分类通过 HttpClient 请求网页端数据接口/HTML,再由原生布局渲染,绝不使用 WebView 套壳。
 */
class NoticeFragment : Fragment() {
    private var binding: FragmentNoticeBinding? = null
    private var badgeMessages: TextView? = null
    private var badgeFans: TextView? = null
    private var badgePosts: TextView? = null
    private var badgeInteractive: TextView? = null
    private var badgeSystem: TextView? = null
    private var badgeApp: TextView? = null
    private var tvClearAll: TextView? = null
    private var httpClient: HttpClient? = null
    private var mainHandler: Handler? = null
    private var executor: ExecutorService? = null
    private var lastBadgeLoadAt = 0L // build68: 上次六类角标拉取时间戳(节流用)

    @Nullable
    @Override
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNoticeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        httpClient = HttpClient.getInstance()
        mainHandler = Handler(Looper.getMainLooper())
        executor = Executors.newFixedThreadPool(4)

        initViews()
        setupClickListeners()
        // 每次进入消息页都以服务器当前数据为准,不能使用本地 all_read 状态跳过请求。
        loadAllBadgeCounts()
    }

    override fun onResume() {
        super.onResume()
        // build68: 加 60 秒节流——频繁返回不再重复全量拉 6 类(防 ESA 403)。
        val now = System.currentTimeMillis()
        if (now - lastBadgeLoadAt < BADGE_RESUME_THROTTLE_MS) {
            return
        }
        if (mainHandler != null) {
            mainHandler!!.removeCallbacksAndMessages(null)
        }
        if (executor != null && !executor!!.isShutdown) {
            loadAllBadgeCounts()
        }
    }

    private fun initViews() {
        val b = binding ?: return
        badgeMessages = b.badgeMessages
        badgeFans = b.badgeFans
        badgePosts = b.badgePosts
        badgeInteractive = b.badgeInteractive
        badgeSystem = b.badgeSystem
        badgeApp = b.badgeApp
        tvClearAll = b.tvClearAll

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachNestedScrollView(b.toolbar, b.scrollNotice)

        // 为图标设置毛玻璃背景
        applyFrostedGlassToIcon(b.ivEmojiMessages)
        applyFrostedGlassToIcon(b.ivEmojiFans)
        applyFrostedGlassToIcon(b.ivEmojiPosts)
        applyFrostedGlassToIcon(b.ivEmojiInteractive)
        applyFrostedGlassToIcon(b.ivEmojiSystem)
        applyFrostedGlassToIcon(b.ivEmojiApp)
    }

    private fun applyFrostedGlassToIcon(icon: ImageView?) {
        if (icon != null) {
            icon.background = FrostedGlassDrawable.createSubtle(requireContext(), 10f)
        }
    }

    private fun setupClickListeners() {
        val b = binding ?: return
        b.llMyMessages.setOnClickListener { openNativeDetail("pm", "我的消息") }
        // 进入分类即视为查看当前内容,随后新增内容仍会重新产生角标。
        b.llMyFans.setOnClickListener {
            NoticeBadgeManager.markViewed(requireContext(), "follower")
            val intent = Intent(requireContext(), FriendListActivity::class.java)
            intent.putExtra("mode", "followers")
            startActivity(intent)
        }
        b.llMyPosts.setOnClickListener { openNativeDetail("mypost", "我的帖子") }
        b.llInteractive.setOnClickListener { openNativeDetail("interactive", "坛友互动") }
        b.llSystem.setOnClickListener { openNativeDetail("system", "系统提醒") }
        b.llApp.setOnClickListener { openNativeDetail("app", "应用提醒") }
        // 全部已读:按压缩放反馈 + 点击淡入动效,与主界面导航栏选中动效一致
        tvClearAll!!.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(120).start()
                else -> {
                }
            }
            false // 不消费事件,交给 OnClickListener
        }
        tvClearAll!!.setOnClickListener { v ->
            val anim: Animation = AnimationUtils.loadAnimation(requireContext(), android.R.anim.fade_in)
            anim.duration = 150
            v.startAnimation(anim)
            clearAllBadges()
        }
    }

    /** 进入分类时立即标记当前快照为已查看;详情页只负责展示内容。 */
    private fun openNativeDetail(view: String, title: String) {
        NoticeBadgeManager.markViewed(requireContext(), view)
        val url: String
        if ("pm" == view) {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2"
        } else if ("follower" == view) {
            url = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid=" + uid + "&mobile=2"
        } else {
            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + view
        }
        val intent = Intent(requireContext(), NoticeDetailActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra(NoticeDetailActivity.EXTRA_VIEW_TYPE, view)
        intent.putExtra(NoticeDetailActivity.EXTRA_TITLE, title)
        requireActivity().startActivityForResult(intent, REQUEST_CODE_DETAIL)
    }

    private val uid: String
        get() {
            val uid = UserSessionManager.getInstance().getUid(requireContext())
            return if (TextUtils.isEmpty(uid)) "0" else uid!!
        }

    private fun loadAllBadgeCounts() {
        lastBadgeLoadAt = System.currentTimeMillis() // build68: 记录本次拉取时刻
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2", badgeMessages, "pm")
        loadCountBySnapshot(
            HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid=" + uid + "&mobile=2",
            badgeFans, "follower"
        )
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=mypost", badgePosts, "mypost")
        loadCountBySnapshot(
            HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=interactive",
            badgeInteractive, "interactive"
        )
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=system", badgeSystem, "system")
        loadCountBySnapshot(HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=app", badgeApp, "app")
    }

    /**
     * 通过当前列表内容和本地已查看快照计算角标,不依赖网页 unread class。
     */
    private fun loadCountBySnapshot(url: String, badgeView: TextView?, viewType: String) {
        if (badgeView == null) return
        executor!!.execute {
            try {
                httpClient!!.syncFromCookieManager()
                val html = if ("pm" == viewType || "follower" == viewType)
                    httpClient!!.get(url) else httpClient!!.getDesktop(url)
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)) {
                    mainHandler!!.post { updateBadge(badgeView, 0) }
                    return@execute
                }
                val snapshot = NoticeBadgeManager.buildSnapshot(viewType, html, httpClient)
                val count = NoticeBadgeManager.saveCurrentAndGetNewCount(
                    requireContext(), viewType, snapshot
                )
                mainHandler!!.post { updateBadge(badgeView, count) }
            } catch (ignored: Exception) {
                mainHandler!!.post { updateBadge(badgeView, 0) }
            }
        }
    }

    private fun updateBadge(badgeView: TextView?, count: Int) {
        if (badgeView == null) return
        if (count > 0) {
            badgeView.text = if (count > 99) "99+" else count.toString()
            badgeView.visibility = View.VISIBLE
        } else badgeView.visibility = View.GONE
    }

    private fun hideAllBadges() {
        updateBadge(badgeMessages, 0)
        updateBadge(badgeFans, 0)
        updateBadge(badgePosts, 0)
        updateBadge(badgeInteractive, 0)
        updateBadge(badgeSystem, 0)
        updateBadge(badgeApp, 0)
    }

    private fun clearAllBadges() {
        hideAllBadges()
        NoticeBadgeManager.markAllViewed(requireContext())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DETAIL || resultCode != android.app.Activity.RESULT_OK || data == null) return
        if (!data.getBooleanExtra(NoticeDetailActivity.RESULT_CLEARED, false)) return
        val type = data.getStringExtra(NoticeDetailActivity.RESULT_VIEW_TYPE)
        if (!TextUtils.isEmpty(type)) NoticeBadgeManager.markViewed(requireContext(), type)
        if ("pm" == type) updateBadge(badgeMessages, 0)
        else if ("follower" == type) updateBadge(badgeFans, 0)
        else if ("mypost" == type) updateBadge(badgePosts, 0)
        else if ("interactive" == type) updateBadge(badgeInteractive, 0)
        else if ("system" == type) updateBadge(badgeSystem, 0)
        else if ("app" == type) updateBadge(badgeApp, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (executor != null) executor!!.shutdownNow()
        executor = null
        binding = null
    }

    companion object {
        private const val REQUEST_CODE_DETAIL = 1001

        // build68: 六类角标 onResume 拉取节流
        private const val BADGE_RESUME_THROTTLE_MS = 60000L
    }
}
