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
        com.solosu.mtforum.util.PerspectiveFoldScrollHelper.attach(binding.recyclerView)


        // RecyclerView 滚动到底部时加载更多
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0 || isLoading || !hasMore) return
                val lm = rv.layoutManager as LinearLayoutManager?
                if (lm != null && lm.findLastCompletelyVisibleItemPosition() >= lm.itemCount - 3) {
                    currentPage++
                    loadThreads(currentPage)
                }
            }
        })

        // TabLayout 切换
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentPage = 1
                hasMore = true
                loadThreads(currentPage)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                currentPage = 1
                hasMore = true
                loadThreads(currentPage)
            }
        })

        // 初始加载
        loadThreads(currentPage)
    }

    /**
     * 根据当前选中的Tab加载帖子
     * Tab 0: 全部 (默认排序)
     * Tab 1: 最新发表 (orderby=dateline)
     * Tab 2: 热门动态 (order=hot)
     * Tab 3: 精华 (filter=digest&digest=1, 服务端过滤)
     */
    private fun loadThreads(page: Int) {
        if (isLoading) return
        isLoading = true
        binding.tvEmpty.visibility = View.GONE

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
                var threads: MutableList<Thread> = ForumParser.parseForumThreadList(html)
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
                    isLoading = false

                    if (resultThreads.isEmpty()) {
                        hasMore = false
                        if (page == 1) {
                            allThreads.clear()
                            threadAdapter.setThreadList(ArrayList())
                            binding.tvEmpty.setText(R.string.forum_empty)
                            binding.tvEmpty.visibility = View.VISIBLE
                        }
                        return@runOnUiThread
                    }

                    if (page == 1) {
                        allThreads.clear()
                    }
                    // 置顶帖与普通帖统一显示在列表中
                    if (page == 1) {
                        threadAdapter.setThreadList(resultThreads)
                    } else {
                        threadAdapter.addThreads(resultThreads)
                    }
                    allThreads.addAll(resultThreads)
                    binding.tvEmpty.visibility = View.GONE
                    // build63: 取消列表页收藏数预取(每页20发->0),改为进详情页时回填缓存
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    isLoading = false
                    if (page == 1 && allThreads.isEmpty()) {
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
