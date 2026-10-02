package com.solosu.mtforum.ui.forum

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide
import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.ThreadAdapter
import com.solosu.mtforum.databinding.ActivityForumDetailBinding
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.ui.widget.FrostedGlassHelper
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.util.NavigationHelper
import com.solosu.mtforum.ui.space.UserProfileActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

import java.util.ArrayList

/**
 * 版块详情页 — 展示指定版块的帖子列表
 * 支持4种排序：全部、最新发表、热门动态、精华
 */
class ForumDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForumDetailBinding
    private lateinit var httpClient: HttpClient
    private lateinit var threadAdapter: ThreadAdapter
    private val allThreads: MutableList<Thread> = ArrayList()

    // 从Intent获取的版块信息
    private var fid: String? = null
    private var forumName: String? = null
    private var description: String? = null
    private var iconUrl: String? = null
    private var totalPosts = 0
    private var totalThreads = 0

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private val pendingBuffer: MutableList<Thread> = ArrayList()

    companion object {
        private const val BATCH_STEP = 10
        private const val PRELOAD_THRESHOLD = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityForumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FrostedGlassHelper.applyToCardViews(binding.root, this)

        httpClient = HttpClient.getInstance()

        // 从Intent读取数据
        fid = intent.getStringExtra("fid")
        forumName = intent.getStringExtra("forumName")
        description = intent.getStringExtra("description")
        iconUrl = intent.getStringExtra("iconUrl")
        totalPosts = intent.getIntExtra("totalPosts", 0)
        totalThreads = intent.getIntExtra("totalThreads", 0)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setDisplayShowTitleEnabled(true)
            if (forumName != null && !forumName!!.isEmpty()) {
                supportActionBar!!.title = forumName
            }
        }

        // 版块头部信息
        binding.tvForumName.text = forumName ?: ""
        binding.tvForumDesc.text = if (description != null && !description!!.isEmpty()) description else "暂无描述"

        // 加载图标
        if (iconUrl != null && !iconUrl!!.isEmpty()) {
            Glide.with(this)
                .load(iconUrl)
                .placeholder(R.drawable.ic_circle)
                .circleCrop()
                .into(binding.ivForumIcon)
        } else {
            binding.ivForumIcon.setImageResource(R.drawable.ic_circle)
        }

        // 加入按钮切换
        binding.btnJoin.setOnClickListener {
            val btn: MaterialButton = binding.btnJoin
            if (btn.text.toString() == getString(R.string.forum_join)) {
                btn.setText(R.string.forum_joined)
            } else {
                btn.setText(R.string.forum_join)
            }
        }

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachRecyclerView(binding.toolbar, binding.recyclerView)

        // 设置帖子列表
        val layoutManager = LinearLayoutManager(this)
        binding.recyclerView.layoutManager = layoutManager

        threadAdapter = ThreadAdapter(this)
        threadAdapter.setOnItemClickListener(object : ThreadAdapter.OnItemClickListener {
            override fun onItemClick(thread: Thread?, position: Int) {
                NavigationHelper.openThread(this@ForumDetailActivity, thread)
            }
        })
        threadAdapter.setOnUserClickListener(object : ThreadAdapter.OnUserClickListener {
            override fun onUserClick(thread: Thread?) {
                if (thread == null || TextUtils.isEmpty(thread.authorUid)) return
                val intent = Intent(this@ForumDetailActivity, UserProfileActivity::class.java)
                intent.putExtra("uid", thread.authorUid)
                intent.putExtra("username", thread.author)
                startActivity(intent)
            }
        })
        binding.recyclerView.adapter = threadAdapter


        // RecyclerView 滚动监听实现 10 条批次自动预载（滑到第 6 条时触发下一批）
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisiblePosition = lm.findLastVisibleItemPosition()
                val totalItemCount = lm.itemCount
                if (totalItemCount > 0 && lastVisiblePosition >= totalItemCount - PRELOAD_THRESHOLD) {
                    checkAndTriggerNextBatch()
                }
            }
        })

        // TabLayout 切换
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                refreshThreads()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                refreshThreads()
            }
        })

        // 初始加载
        refreshThreads()
    }

    private fun refreshThreads() {
        currentPage = 1
        hasMore = true
        pendingBuffer.clear()
        allThreads.clear()
        threadAdapter.setThreadList(ArrayList())
        fetchNetworkPage(currentPage, true)
    }

    private fun checkAndTriggerNextBatch() {
        if (isLoading) return
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
        allThreads.addAll(batch)
        threadAdapter.addThreads(batch)
    }

    /**
     * 根据当前选中的Tab加载帖子
     * Tab 0: 全部 (默认排序)
     * Tab 1: 最新发表 (orderby=dateline)
     * Tab 2: 热门动态 (order=hot)
     * Tab 3: 精华 (filter=digest&digest=1, 服务端过滤)
     */
    private fun fetchNetworkPage(page: Int, isRefresh: Boolean) {
        if (isLoading) return
        isLoading = true
        if (isRefresh) {
            binding.tvEmpty.visibility = View.GONE
        }

        val tabPosition = binding.tabLayout.selectedTabPosition

        java.lang.Thread {
            try {
                // 根据Tab位置拼接排序参数(对齐版块页真实排序链接)
                var sortParam = ""
                if (tabPosition == 3) {
                    // 精华:filter=digest&digest=1,由服务端过滤
                    sortParam = "&filter=digest&digest=1"
                } else if (tabPosition == 1) {
                    // 最新发表:filter=lastpost&orderby=lastpost
                    sortParam = "&filter=lastpost&orderby=lastpost"
                } else if (tabPosition == 2) {
                    // 热门动态:Discuz 原生热度排序
                    sortParam = "&filter=heat&orderby=heats"
                }

                val url = ForumParser.getThreadListUrl(fid + sortParam, page)
                val html = httpClient.get(url)
                val threads: MutableList<Thread> = ForumParser.parseForumThreadList(html)
                // 黑名单过滤:拉黑作者的帖子直接不进列表
                val black = com.solosu.mtforum.session.BlacklistManager.uidSet(this@ForumDetailActivity)
                if (black.isNotEmpty()) {
                    val it = threads.iterator()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t != null && t.authorUid != null && black.contains(t.authorUid)) it.remove()
                    }
                }
                val resultThreads = threads

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isLoading = false

                    if (resultThreads.isEmpty()) {
                        hasMore = false
                        if (pendingBuffer.isNotEmpty()) {
                            dispatchNextBatch()
                        } else if (isRefresh && allThreads.isEmpty()) {
                            threadAdapter.setThreadList(ArrayList())
                            binding.tvEmpty.setText(R.string.forum_empty)
                            binding.tvEmpty.visibility = View.VISIBLE
                        }
                        return@runOnUiThread
                    }

                    // 只要本页有数据，就认为可能还有下一页（兼容不同版块10条/15条/20条不同分页限制）
                    hasMore = true
                    pendingBuffer.addAll(resultThreads)

                    if (isRefresh) {
                        val countToTake = minOf(BATCH_STEP, pendingBuffer.size)
                        val firstBatch = ArrayList(pendingBuffer.subList(0, countToTake))
                        for (i in 0 until countToTake) {
                            pendingBuffer.removeAt(0)
                        }
                        allThreads.clear()
                        allThreads.addAll(firstBatch)
                        threadAdapter.setThreadList(firstBatch)
                        binding.tvEmpty.visibility = View.GONE
                    } else {
                        dispatchNextBatch()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isLoading = false
                    if (isRefresh && allThreads.isEmpty() && pendingBuffer.isEmpty()) {
                        binding.tvEmpty.setText(R.string.forum_empty)
                        binding.tvEmpty.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
