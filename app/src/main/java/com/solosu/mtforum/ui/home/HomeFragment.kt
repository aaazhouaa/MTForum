package com.solosu.mtforum.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.ThreadAdapter
import com.solosu.mtforum.databinding.FragmentHomeBinding
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.util.NavigationHelper
import com.solosu.mtforum.ui.space.UserProfileActivity
import com.solosu.mtforum.ui.search.SearchActivity
import com.solosu.mtforum.ai.AiChatActivity
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import com.solosu.mtforum.ui.widget.NavBarAutoHideHelper

/**
 * 首页 Fragment
 * 展示最新帖子列表,支持下拉刷新、翻页加载、热板推荐、搜索跳转
 */
class HomeFragment : Fragment() {

    private var binding: FragmentHomeBinding? = null
    private lateinit var httpClient: HttpClient
    private var threadAdapter: ThreadAdapter? = null
    private var currentPage = 1
    private var isFetchingNetwork = false
    private var hasMore = true
    private val pendingBuffer: MutableList<Thread> = ArrayList()

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?, @Nullable savedInstanceState: Bundle?): View? {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        httpClient = HttpClient.getInstance()

        // 搜索图标点击 -> 打开搜索页面
        binding!!.ivSearch.setOnClickListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }
        // 深浅色主题手动切换 (太阳/月亮)
        updateThemeToggleIcon()
        binding!!.ivThemeToggle.setOnClickListener {
            it.animate()
                .rotationBy(360f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(180)
                .withEndAction {
                    it.scaleX = 1.0f
                    it.scaleY = 1.0f
                    val isDarkNow = com.solosu.mtforum.util.ThemeManager.toggleNightMode(requireActivity())
                    updateThemeToggleIcon()
                    com.solosu.mtforum.util.ToastUtil.makeText(
                        requireContext(),
                        if (isDarkNow) "已切换为暗色主题" else "已切换为亮色主题",
                        com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
                    ).show()
                }
                .start()
        }



        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachRecyclerView(binding!!.layoutTitleBar, binding!!.recyclerView)
        com.solosu.mtforum.util.ScrollToTopHelper.attachRecyclerView(binding!!.tvAppTitle, binding!!.recyclerView)

        // RecyclerView + ThreadAdapter
        threadAdapter = ThreadAdapter(requireContext())
        threadAdapter!!.setOnItemClickListener(object : ThreadAdapter.OnItemClickListener {
            override fun onItemClick(thread: Thread?, position: Int) {
                NavigationHelper.openThread(requireContext(), thread)
            }
        })

        threadAdapter!!.setOnUserClickListener(object : ThreadAdapter.OnUserClickListener {
            override fun onUserClick(thread: Thread?) {
                if (thread == null || TextUtils.isEmpty(thread.authorUid)) return
                val intent = Intent(requireContext(), UserProfileActivity::class.java)
                intent.putExtra("uid", thread.authorUid)
                intent.putExtra("username", thread.author)
                startActivity(intent)
            }
        })

        binding!!.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding!!.recyclerView.adapter = threadAdapter

        // 滚动监听实现翻页加载（每次加载10条，滑到第6条时自动往后预载）
        binding!!.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // build71: 滚动方向 -> 底部导航栏自动隐藏/出现
                NavBarAutoHideHelper.onScrolled(getActivity(), dy)
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisiblePosition = lm.findLastVisibleItemPosition()
                val totalItemCount = lm.itemCount
                // 当滑到新刷出的第 6 条（即离当前末尾只剩 4 条以内），自动追加后 10 条
                if (totalItemCount > 0 && lastVisiblePosition >= totalItemCount - PRELOAD_THRESHOLD) {
                    checkAndTriggerNextBatch()
                }
            }
        })

        // 下拉刷新
        binding!!.swipeRefresh.setOnRefreshListener { refreshThreads() }
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(requireContext())
        binding!!.swipeRefresh.setColorSchemeColors(themeColor)



        // 加载热板推荐
        loadHotBoards()

        // 首次加载
        refreshThreads()
    }

    /**
     * 加载热帖排行列表
     */
    private fun loadHotBoards() {
        java.lang.Thread({
            try {
                val url = "https://bbs.binmt.cc/forum.php?mod=guide&view=hot&mobile=2"
                val html = httpClient.get(url)
                if (!isAdded() || android.text.TextUtils.isEmpty(html)) return@Thread

                // 从 HTML 中提取所有带排名序号的热帖
                // 匹配: <li class="b_t"><a href="...thread-数字-1-1.html" title="标题"><em class="...">排名</em>标题</a></li>
                val list = java.util.ArrayList<Array<String>>()
                var searchFrom = 0
                while (list.size < 10) {
                    val tidx = html.indexOf("thread-", searchFrom)
                    if (tidx < 0) break
                    val endIdx = html.indexOf("-1-1.html", tidx)
                    if (endIdx < 0) { searchFrom = tidx + 7; continue }
                    val tid = html.substring(tidx + 7, endIdx)

                    // 检查这个链接后面是否有 <em>数字</em>(排名序号),有则说明是热帖排行
                    val afterHref = html.indexOf(">", endIdx + 9)
                    if (afterHref < 0) { searchFrom = endIdx + 9; continue }
                    val emStart = html.indexOf("<em", afterHref)
                    if (emStart < 0 || emStart > afterHref + 200) { searchFrom = endIdx + 9; continue }
                    val emContentStart = html.indexOf(">", emStart)
                    if (emContentStart < 0) { searchFrom = endIdx + 9; continue }
                    val emContentEnd = html.indexOf("</em>", emContentStart)
                    if (emContentEnd < 0) { searchFrom = endIdx + 9; continue }
                    val rankStr = html.substring(emContentStart + 1, emContentEnd)
                    // 排名序号必须是纯数字
                    if (!rankStr.matches(Regex("\\d+"))) { searchFrom = endIdx + 9; continue }

                    // 提取 title 属性(在 href 后面查找)
                    val titleStart = html.indexOf("title=\"", endIdx)
                    if (titleStart < 0 || titleStart > endIdx + 300) { searchFrom = endIdx + 9; continue }
                    val titleEnd = html.indexOf("\"", titleStart + 7)
                    if (titleEnd < 0) { searchFrom = endIdx + 9; continue }
                    val title = html.substring(titleStart + 7, titleEnd)

                    list.add(arrayOf(tid, title))
                    searchFrom = endIdx + 9
                }
                if (list.isEmpty()) return@Thread

                val topThreads = java.util.ArrayList(list)
                requireActivity().runOnUiThread {
                    if (!isAdded() || binding == null || threadAdapter == null) return@runOnUiThread

                    val density = requireContext().resources.displayMetrics.density
                    val headerContainer = LinearLayout(requireContext())
                    headerContainer.orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins((6*density).toInt(), 0, (6*density).toInt(), (5*density).toInt())
                    headerContainer.layoutParams = lp
                    headerContainer.setPadding((6*density).toInt(), (10*density).toInt(), (10*density).toInt(), (10*density).toInt())
                    headerContainer.background =
                            com.solosu.mtforum.ui.widget.FrostedGlassDrawable.create(requireContext(), 14f)
                    headerContainer.clipToOutline = true

                    // 标题行: 热帖排行 + 查看更多
                    val headerRow = LinearLayout(requireContext())
                    headerRow.orientation = LinearLayout.HORIZONTAL
                    headerRow.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                    val tvHeader = TextView(requireContext())
                    tvHeader.text = "热帖排行"
                    tvHeader.setTextSize(19f)
                    tvHeader.setTypeface(null, android.graphics.Typeface.BOLD)
                    tvHeader.setTextColor(requireContext().getColor(R.color.text_primary))
                    tvHeader.layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                    headerRow.addView(tvHeader)
                    headerContainer.addView(headerRow)

                    for (i in 0 until topThreads.size) {
                        val item = topThreads[i]
                        val row = LinearLayout(requireContext())
                        row.orientation = LinearLayout.HORIZONTAL
                        row.gravity = android.view.Gravity.BOTTOM
                        val rank = i + 1

                        // 排名序号圆角背景
                        val tvRank = TextView(requireContext())
                        tvRank.text = rank.toString()
                        tvRank.setTextSize(11f)
                        tvRank.setTextColor(0xFFFFFFFF.toInt())
                        tvRank.gravity = android.view.Gravity.CENTER
                        val rankSize = (20*density).toInt()
                        val rlp = LinearLayout.LayoutParams(rankSize, rankSize)
                        rlp.topMargin = (8*density).toInt()
                        tvRank.layoutParams = rlp
                        // 不同排名不同颜色
                        val bgColor: Int
                        if (rank == 1) bgColor = 0xFFFF705E.toInt()
                        else if (rank == 2) bgColor = 0xFFFFB900.toInt()
                        else if (rank == 3) bgColor = 0xFFA8C500.toInt()
                        else bgColor = 0xFFCCCCCC.toInt()
                        val bg = android.graphics.drawable.GradientDrawable()
                        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
                        bg.setCornerRadius(10f)
                        bg.setColor(bgColor)
                        tvRank.background = bg

                        // 标题
                        val tvTitle = TextView(requireContext())
                        tvTitle.text = item[1]
                        tvTitle.setTextSize(13f)
                        tvTitle.setTextColor(requireContext().getColor(R.color.text_primary))
                        tvTitle.setSingleLine(true)
                        tvTitle.ellipsize = android.text.TextUtils.TruncateAt.END
                        tvTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        tvTitle.setPadding((8*density).toInt(), (8*density).toInt(), 0, (8*density).toInt())

                        row.addView(tvRank)
                        row.addView(tvTitle)
                        row.tag = item[0]
                        row.isClickable = true
                        row.isFocusable = true
                        // 透明波纹点击效果
                        val rippleVal = android.util.TypedValue()
                        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleVal, true)
                        row.setBackgroundResource(rippleVal.resourceId)
                        val tid = item[0]
                        row.setOnClickListener {
                            NavigationHelper.openThread(requireContext(), tid)
                        }

                        headerContainer.addView(row)
                        if (i < topThreads.size - 1) {
                            val divider = View(requireContext())
                            divider.layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 1)
                            divider.setBackgroundColor(0x1A000000)
                            divider.setPadding((28*density).toInt(), 0, 0, 0)
                            headerContainer.addView(divider)
                        }
                    }

                    // 底部隔离线
                    val bottomDivider = View(requireContext())
                    bottomDivider.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0)
                    bottomDivider.setBackgroundColor(0x00000000)
                    headerContainer.addView(bottomDivider)

                    // 设置为 RecyclerView 头部
                    threadAdapter!!.setHeaderView(headerContainer)
                }
            } catch (ignored: Exception) {
                // 热帖加载失败不影响主列表
            }
        }).start()
    }

    private fun refreshThreads() {
        currentPage = 1
        hasMore = true
        pendingBuffer.clear()
        fetchNetworkPage(currentPage, true)
    }

    private fun checkAndTriggerNextBatch() {
        if (isFetchingNetwork) return
        if (pendingBuffer.size >= BATCH_STEP) {
            dispatchNextBatch()
        } else if (hasMore) {
            currentPage++
            fetchNetworkPage(currentPage, false)
        } else if (pendingBuffer.isNotEmpty()) {
            dispatchNextBatch()
        }
    }

    private fun dispatchNextBatch() {
        if (pendingBuffer.isEmpty()) return
        val countToTake = minOf(BATCH_STEP, pendingBuffer.size)
        val batch = ArrayList(pendingBuffer.subList(0, countToTake))
        for (i in 0 until countToTake) {
            pendingBuffer.removeAt(0)
        }
        if (!isAdded) return
        threadAdapter?.addThreads(batch)
    }

    private fun fetchNetworkPage(page: Int, isRefresh: Boolean) {
        if (isFetchingNetwork) return
        isFetchingNetwork = true
        if (isRefresh) {
            binding?.swipeRefresh?.isRefreshing = true
        }

        java.lang.Thread {
            try {
                val url = ForumParser.getHomeUrl(page)
                val html = httpClient.get(url)
                val threads: MutableList<Thread>? = ForumParser.parseThreadList(html)

                if (!isAdded) return@Thread
                // 黑名单过滤:拉黑作者的帖子直接不进列表
                if (threads != null) {
                    val black = com.solosu.mtforum.session.BlacklistManager.uidSet(requireContext())
                    if (!black.isEmpty()) {
                        val it = threads.iterator()
                        while (it.hasNext()) {
                            val t = it.next()
                            if (t != null && t.authorUid != null && black.contains(t.authorUid)) it.remove()
                        }
                    }
                }
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (threads != null && !threads.isEmpty()) {
                        // 只要服务器返回了帖子就继续允许翻页加载下一页，仅当返回空列表时判定无更多
                        hasMore = true
                        pendingBuffer.addAll(threads)
                        if (isRefresh) {
                            val countToTake = minOf(BATCH_STEP, pendingBuffer.size)
                            val firstBatch = ArrayList(pendingBuffer.subList(0, countToTake))
                            for (i in 0 until countToTake) {
                                pendingBuffer.removeAt(0)
                            }
                            threadAdapter?.setThreadList(firstBatch)
                        } else {
                            dispatchNextBatch()
                        }
                    } else {
                        hasMore = false
                        if (isRefresh) {
                            threadAdapter?.setThreadList(null)
                        } else {
                            dispatchNextBatch()
                        }
                    }
                    isFetchingNetwork = false
                    binding?.swipeRefresh?.isRefreshing = false
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    isFetchingNetwork = false
                    binding?.swipeRefresh?.isRefreshing = false
                    if (isRefresh && (threadAdapter == null || threadAdapter!!.itemCount == 0)) {
                        com.solosu.mtforum.util.ToastUtil.show(requireContext(), "加载失败: " + e.message)
                    }
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateThemeToggleIcon()
        if (threadAdapter != null && threadAdapter!!.itemCount == 0) {
            refreshThreads()
        } else if (threadAdapter != null) {
            // build80: 从详情页返回时, 用点赞缓存刷新卡片, 让点赞数及时更新
            applyCachedLikes()
        }
    }

    /** build80: 用详情页点赞缓存回填列表卡片点赞数 */
    private fun applyCachedLikes() {
        if (threadAdapter == null) return
        try {
            val n = threadAdapter!!.itemCount
            var changed = false
            for (i in 0 until n) {
                val t = threadAdapter!!.getItem(i)
                if (t == null || TextUtils.isEmpty(t.tid)) continue
                val likes = com.solosu.mtforum.session.PostCountsCache.getLikes(t.tid)
                if (likes != null && likes != t.likes) {
                    t.likes = likes
                    changed = true
                }
            }
            if (changed) threadAdapter!!.notifyDataSetChanged()
        } catch (ignore: Exception) { }
    }

    private fun updateThemeToggleIcon() {
        val b = binding ?: return
        val isDark = com.solosu.mtforum.util.ThemeManager.isDarkMode(requireContext())
        b.ivThemeToggle.setImageResource(if (isDark) R.drawable.ic_sun else R.drawable.ic_moon)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val BATCH_STEP = 10
        private const val PRELOAD_THRESHOLD = 4
    }
}
