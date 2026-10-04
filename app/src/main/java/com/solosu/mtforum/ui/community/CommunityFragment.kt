package com.solosu.mtforum.ui.community

import android.content.Intent
import android.text.TextUtils
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.ForumGridAdapter
import com.solosu.mtforum.databinding.FragmentCommunityBinding
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import com.solosu.mtforum.ui.widget.FrostedGlassHelper
import com.solosu.mtforum.ui.widget.NavBarAutoHideHelper
import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.UserSessionManager
import com.solosu.mtforum.ui.forum.ForumDetailActivity

import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CommunityFragment : Fragment() {

    private var binding: FragmentCommunityBinding? = null
    private lateinit var httpClient: HttpClient
    private lateinit var forumGridAdapter: ForumGridAdapter
    private var currentFormhash: String? = null

    @Nullable
    override fun onCreateView(
        @NonNull inflater: LayoutInflater,
        @Nullable container: ViewGroup?,
        @Nullable savedInstanceState: Bundle?
    ): View {
        binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        FrostedGlassHelper.applyToCardViews(view, requireContext())

        // 数据统计:4 个单元格各自使用玻璃卡片背景
        val statsGlass = FrostedGlassDrawable.create(requireContext(), 12f)
        binding!!.llStats1.background = statsGlass
        binding!!.llStats2.background = statsGlass
        binding!!.llStats3.background = statsGlass
        binding!!.llStats4.background = statsGlass

        httpClient = HttpClient.getInstance()
        // 版块页面不启用下拉刷新，避免普通滑动被误触发。

        // Setup forum grid (2 columns, nested scrolling disabled)
        forumGridAdapter = ForumGridAdapter(requireContext())
        binding!!.rvForumGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        binding!!.rvForumGrid.adapter = forumGridAdapter
        // build71: 滚动方向 -> 底部导航栏自动隐藏/出现
        val navScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.nav_scroll_community)
        if (navScroll != null) {
            // 显式指定 View.OnScrollChangeListener,避免与 NestedScrollView 自身重载歧义
            navScroll.setOnScrollChangeListener { v, sx, sy, osx, osy ->
                NavBarAutoHideHelper.onScrolled(activity, sy - osy)
            }
        }
        forumGridAdapter.setOnForumClickListener(object : ForumGridAdapter.OnForumClickListener {
            override fun onForumClick(forum: ForumCategory.Forum?, position: Int) {
                val intent = Intent(requireContext(), ForumDetailActivity::class.java)
                intent.putExtra("fid", forum!!.fid)
                intent.putExtra("forumName", forum.name)
                intent.putExtra("description", forum.description)
                intent.putExtra("iconUrl", forum.iconUrl)
                intent.putExtra("totalPosts", forum.totalPosts)
                intent.putExtra("totalThreads", forum.totalThreads)
                startActivity(intent)
            }
        })

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachNestedScrollView(binding!!.layoutTitleBar, binding!!.navScrollCommunity)
        com.solosu.mtforum.util.ScrollToTopHelper.attachNestedScrollView(binding!!.tvTitleDiscover, binding!!.navScrollCommunity)

        // Setup sign-in button click
        binding!!.btnSignIn.setOnClickListener {
            // 检查登录状态
            if (!httpClient.isLoggedIn()) {
                Toast.makeText(requireContext(), R.string.login_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 自动签到成功后可能仍停留在社区页，先读取本地签到状态，避免重复请求和误播放音效。
            if (UserSessionManager.getInstance().isSignedInToday(requireContext())) {
                showAlreadySignedIn()
                return@setOnClickListener
            }
            if (currentFormhash == null || currentFormhash!!.isEmpty()) {
                Toast.makeText(requireContext(), "formhash 获取失败，请刷新页面", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 禁用按钮防止重复点击
            binding!!.btnSignIn.isEnabled = false
            binding!!.btnSignIn.text = "签到中..."
            performSignIn()
        }

        // Initial data load
        loadCommunityData()
    }

    /**
     * Load community page data: sign-in info, stats, and forum grid.
     * Uses ForumParser.getForumlistMobileUrl() as data source.
     */
    /**
     * 刷新签到状态(由外部如自动签到完成时调用)
     */
    private fun loadCommunityData() {
        // NestedScrollView 不显示刷新指示器，直接加载数据。

        java.lang.Thread {
            try {
                val html = httpClient.get(ForumParser.getForumlistMobileUrl())
                val data = ForumParser.parseCommunityPage(html)

                requireActivity().runOnUiThread {
                    if (binding == null) return@runOnUiThread

                    // 0. 提取 formhash
                    currentFormhash = data.formhash
                    if (currentFormhash != null && !currentFormhash!!.isEmpty()) {
                        binding!!.btnSignIn.isEnabled = true
                    }

                    // 1. Sign-in card — 优先检查本地持久化签到状态
                    val alreadySignedIn = UserSessionManager.getInstance()
                        .isSignedInToday(requireContext())
                    if (alreadySignedIn) {
                        showAlreadySignedIn()
                    } else if (data.signInText != null && !data.signInText.isEmpty()) {
                        val isSigned = data.signInText.contains("已")
                        val cleanText = if (isSigned) "已签到" else "签到"
                        binding!!.tvSignInStatus.text = cleanText
                        binding!!.btnSignIn.text = cleanText
                        // If text contains "已签到" or "已", treat as already signed in
                        if (isSigned) {
                            binding!!.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_gray)
                            binding!!.btnSignIn.isEnabled = false
                            // 同步到本地持久化
                            UserSessionManager.getInstance().saveSignInDate(requireContext())
                        }
                    }

                    // 2. Stats cards
                    binding!!.tvStatsValue1.text = data.todayPosts.toString()
                    binding!!.tvStatsValue2.text = data.yesterdayPosts.toString()
                    binding!!.tvStatsValue3.text = data.totalPosts.toString()
                    binding!!.tvStatsValue4.text = data.totalMembers.toString()

                    // 3. Forum grid — deduplicate by fid
                    val dedupMap = LinkedHashMap<String, ForumCategory.Forum>()
                    val forums = data.forums
                    if (forums != null && !forums.isEmpty()) {
                        for (f in forums) {
                            dedupMap[f.fid!!] = f
                        }
                    } else {
                        // Fallback: use categories to flatten forums
                        val categories = data.categories
                        if (categories != null) {
                            for (cat in categories) {
                                if (cat.forums != null) {
                                    for (f in cat.forums!!) {
                                        dedupMap[f.fid!!] = f
                                    }
                                }
                            }
                        }
                    }
                    forumGridAdapter.setForumList(ArrayList<ForumCategory.Forum>(dedupMap.values))

                    // ★ 完善数据:桌面版 forumlist 统计(主题/总帖数)+ 版块页描述/精确热度
                    val gridForums = forumGridAdapter.getForumList()
                    if (gridForums != null && !gridForums.isEmpty()) {
                        enrichForumsWithStatsAndDescription(gridForums)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                requireActivity().runOnUiThread {
                    if (binding != null) {
                        binding!!.tvSignInStatus.setText(R.string.network_error)
                    }
                }
            }
        }.start()
    }

    /**
     * 从服务器响应中提取纯文本消息（去除 XML/CDATA 包裹）
     * 例如: <root><![CDATA[今日已签]]></root> → "今日已签"
     */
    /**
     * 完善版块卡片数据:
     * 桌面版 forumlist 页 → 主题数/总帖数(热度)/今日新帖
     * build68: 已移除"逐版块抓头部"的第二步(每进一次社区页 12 发, 且版块描述字段
     *          UI 已 GONE 不展示, 纯浪费)。卡片"热度/新帖"仍来自桌面版统计, 外观不变。
     */
    private fun enrichForumsWithStatsAndDescription(forums: MutableList<ForumCategory.Forum>) {
        java.lang.Thread {
            try {
                // ---- 第一步:桌面版 forumlist 统计 ----
                val desktopHtml = httpClient.get(ForumParser.getBaseDomain() + "forum.php?forumlist=1&mobile=no")
                val statsMap = ForumParser.parseDesktopForumStats(desktopHtml)
                if (statsMap != null && !statsMap.isEmpty()) {
                    for (i in forums.indices) {
                        val f = forums[i]
                        val st = statsMap[f.fid]
                        if (st == null) continue
                        if (st[1] > 0) f.totalPosts = Math.min(st[1], Int.MAX_VALUE.toLong()).toInt()
                        if (st[0] > 0) f.totalThreads = Math.min(st[0], Int.MAX_VALUE.toLong()).toInt()
                        if (st[2] > 0) f.todayPosts = Math.min(st[2], Int.MAX_VALUE.toLong()).toInt()
                    }
                    val snapshot = ArrayList<ForumCategory.Forum>(forums)
                    requireActivity().runOnUiThread {
                        if (binding != null && forumGridAdapter != null) {
                            forumGridAdapter.setForumList(snapshot)
                        }
                    }
                }
            } catch (ignored: Exception) {
                // 桌面统计失败不影响后续描述补充
            }
        }.start()
    }

    private fun extractSignMessage(raw: String?): String {
        if (raw == null || raw.isEmpty()) return ""
        // 尝试提取 <![CDATA[...]]> 中的内容
        var matcher = java.util.regex.Pattern.compile(
            "<!\\[CDATA\\[(.*?)\\]\\]>",
            java.util.regex.Pattern.DOTALL
        ).matcher(raw)
        if (matcher.find()) {
            return matcher.group(1).trim()
        }
        // 尝试提取 <root>...</root> 中的内容（非CDATA情况）
        matcher = java.util.regex.Pattern.compile(
            "<root>(.*?)</root>",
            java.util.regex.Pattern.DOTALL
        ).matcher(raw)
        if (matcher.find()) {
            return matcher.group(1).trim()
        }
        // 去掉可能的XML标签
        val cleaned = raw.replace(Regex("<[^>]+>"), "").trim()
        if (!cleaned.isEmpty()) return cleaned
        return raw.trim()
    }

    private fun showAlreadySignedIn() {
        if (binding == null) return
        binding!!.tvSignInStatus.text = "已签到"
        binding!!.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_gray)
        binding!!.btnSignIn.text = "已签到"
        binding!!.btnSignIn.isEnabled = false
    }

    /**
     * 执行签到请求
     */
    private fun performSignIn() {
        java.lang.Thread {
            try {
                val signUrl = HttpClient.BASE_URL + "plugin.php?id=k_misign:sign&operation=qiandao&format=text"
                val params = HashMap<String, String>()
                params["formhash"] = currentFormhash!!
                val result = httpClient.post(signUrl, params)

                requireActivity().runOnUiThread {
                    if (binding == null) return@runOnUiThread

                    // 提取纯文本消息
                    val msg = if (result != null) extractSignMessage(result) else ""
                    // 服务器明确返回“已签到”时只更新状态；其余只要不是明确失败，就视为本次手动签到成功。
                    val alreadySigned = isAlreadySignedMessage(msg)
                    val isSuccess = !alreadySigned && isSuccessfulSignResponse(msg, result)

                    if (isSuccess || alreadySigned) {
                        val displayMsg = if (msg.isEmpty()) (if (alreadySigned) "今日已签到" else "签到成功") else msg
                        Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_SHORT).show()
                        showAlreadySignedIn()
                        if (isSuccess) {
                            playRandomSignInSound()
                        }
                        // 持久化签到状态
                        UserSessionManager.getInstance().saveSignInDate(requireContext())
                    } else {
                        val displayMsg = if (!msg.isEmpty()) msg else (if (result != null) result.trim() else "签到失败")
                        Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_SHORT).show()
                        binding!!.btnSignIn.isEnabled = true
                        binding!!.btnSignIn.setText(R.string.action_sign_in)
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    if (binding == null) return@runOnUiThread
                    Toast.makeText(requireContext(), "签到失败: " + e.message, Toast.LENGTH_SHORT).show()
                    binding!!.btnSignIn.isEnabled = true
                    binding!!.btnSignIn.setText(R.string.action_sign_in)
                }
            }
        }.start()
    }

    private fun isAlreadySignedMessage(text: String?): Boolean {
        if (text == null) return false
        return text.contains("今日已签") || text.contains("已签到")
                || text.contains("已经签到") || text.contains("已签")
    }

    private fun isSuccessfulSignResponse(message: String?, rawResult: String?): Boolean {
        val text = if (message == null) "" else message.trim()
        val raw = if (rawResult == null) "" else rawResult.trim()
        val lower = text.lowercase()
        if (text.isEmpty() && raw.isEmpty()) return false
        return !(text.contains("失败") || text.contains("错误") || text.contains("异常")
                || text.contains("请先登录") || text.contains("没有权限") || text.contains("非法操作")
                || lower.contains("fail") || lower.contains("error"))
    }

    private fun playRandomSignInSound() {
        if (isAdded && context != null) {
        }
    }

    /**
     * 页面已取消下拉刷新，仅保留首次进入时的自动加载。
     */

    override fun onResume() {
        super.onResume()
        currentInstance = this
    }

    override fun onPause() {
        super.onPause()
        currentInstance = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {

        private var currentInstance: CommunityFragment? = null

        /**
         * 刷新签到状态(由外部如自动签到完成时调用)
         */
        @JvmStatic
        fun refreshSignIn() {
            val instance = currentInstance ?: return
            if (instance.binding == null) return
            instance.requireActivity().runOnUiThread {
                if (instance.binding == null) return@runOnUiThread
                if (UserSessionManager.getInstance().isSignedInToday(instance.requireContext())) {
                    instance.showAlreadySignedIn()
                } else {
                    instance.binding!!.btnSignIn.isEnabled = true
                    instance.binding!!.btnSignIn.setText(R.string.action_sign_in)
                    instance.binding!!.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_primary)
                }
            }
        }
    }
}
