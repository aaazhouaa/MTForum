package com.solosu.mtforum.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.ThreadAdapter
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.detail.ThreadDetailActivity
import com.solosu.mtforum.ui.space.UserProfileActivity

import java.util.ArrayList

/**
 * 搜索结果页面（一次性加载全部结果跨页合并显示，支持排序切换）
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvError: TextView
    private lateinit var threadAdapter: ThreadAdapter

    // 排序相关
    private lateinit var layoutSortBar: LinearLayout
    private lateinit var btnSortLastpost: TextView
    private lateinit var btnSortDateline: TextView
    private lateinit var btnSortReplies: TextView
    private var currentSortBy = "lastpost" // lastpost / dateline / replies
    private var pendingKeyword: String? = null
    private val pendingBuffer: MutableList<Thread> = ArrayList()
    private val displayedResults: MutableList<Thread> = ArrayList()

    companion object {
        private const val BATCH_STEP = 10
        private const val PRELOAD_THRESHOLD = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        toolbar = findViewById(R.id.toolbar)
        etSearch = findViewById(R.id.et_search)
        progressBar = findViewById(R.id.progress_bar)
        recyclerView = findViewById(R.id.recycler_view)
        tvEmpty = findViewById(R.id.tv_empty)
        tvError = findViewById(R.id.tv_error)
        layoutSortBar = findViewById(R.id.layout_sort_bar)
        btnSortLastpost = findViewById(R.id.btn_sort_lastpost)
        btnSortDateline = findViewById(R.id.btn_sort_dateline)
        btnSortReplies = findViewById(R.id.btn_sort_replies)

        // === Toolbar ===
        setSupportActionBar(toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setDisplayShowHomeEnabled(true)
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        toolbar.setNavigationOnClickListener { finish() }

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachRecyclerView(toolbar, recyclerView)

        // === RecyclerView ===
        threadAdapter = ThreadAdapter(this)
        threadAdapter.setOnItemClickListener(object : ThreadAdapter.OnItemClickListener {
            override fun onItemClick(thread: Thread?, position: Int) {
                val intent = Intent(this@SearchActivity, ThreadDetailActivity::class.java)
                intent.putExtra("tid", thread!!.tid)
                startActivity(intent)
            }
        })
        threadAdapter.setOnUserClickListener(object : ThreadAdapter.OnUserClickListener {
            override fun onUserClick(thread: Thread?) {
                if (thread == null || TextUtils.isEmpty(thread.authorUid)) return
                val intent = Intent(this@SearchActivity, UserProfileActivity::class.java)
                intent.putExtra("uid", thread.authorUid)
                intent.putExtra("username", thread.author)
                startActivity(intent)
            }
        })
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = threadAdapter

        // 滚动监听实现 10 条批次自动预载（滑到第 6 条时触发下一批 10 条）
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisiblePosition = lm.findLastVisibleItemPosition()
                val totalItemCount = lm.itemCount
                if (totalItemCount > 0 && lastVisiblePosition >= totalItemCount - PRELOAD_THRESHOLD) {
                    dispatchNextSearchBatch()
                }
            }
        })

        // === 搜索按钮 ===
        findViewById<View>(R.id.btn_search).setOnClickListener { performSearch() }

        // === 键盘搜索动作 ===
        etSearch.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                return@setOnEditorActionListener true
            }
            false
        }

        // === 排序切换 ===
        btnSortLastpost.setOnClickListener { switchSort("lastpost") }
        btnSortDateline.setOnClickListener { switchSort("dateline") }
        btnSortReplies.setOnClickListener { switchSort("replies") }

        // === Intent 携带关键词自动搜索 ===
        val keyword = intent.getStringExtra("keyword")
        if (!TextUtils.isEmpty(keyword)) {
            etSearch.setText(keyword)
            performSearch()
        }
    }

    /**
     * 切换排序方式
     */
    private fun switchSort(orderby: String) {
        if (orderby == currentSortBy) return
        currentSortBy = orderby
        updateSortChips()
        if (!TextUtils.isEmpty(pendingKeyword)) {
            fetchAllResults(pendingKeyword!!, currentSortBy)
        }
    }

    /**
     * 更新排序按钮的激活/非激活样式
     */
    private fun updateSortChips() {
        btnSortLastpost.setBackgroundResource(
            if ("lastpost" == currentSortBy) R.drawable.chip_active_bg else R.drawable.chip_inactive_bg
        )
        btnSortLastpost.setTextColor(
            ContextCompat.getColor(
                this,
                if ("lastpost" == currentSortBy) R.color.text_white else R.color.text_hint
            )
        )

        btnSortDateline.setBackgroundResource(
            if ("dateline" == currentSortBy) R.drawable.chip_active_bg else R.drawable.chip_inactive_bg
        )
        btnSortDateline.setTextColor(
            ContextCompat.getColor(
                this,
                if ("dateline" == currentSortBy) R.color.text_white else R.color.text_hint
            )
        )

        btnSortReplies.setBackgroundResource(
            if ("replies" == currentSortBy) R.drawable.chip_active_bg else R.drawable.chip_inactive_bg
        )
        btnSortReplies.setTextColor(
            ContextCompat.getColor(
                this,
                if ("replies" == currentSortBy) R.color.text_white else R.color.text_hint
            )
        )
    }

    private fun performSearch() {
        val keyword = etSearch.text.toString().trim()
        if (TextUtils.isEmpty(keyword)) {
            etSearch.error = getString(R.string.search_hint)
            return
        }
        threadAdapter.setThreadList(null)
        currentSortBy = "lastpost"
        updateSortChips()
        layoutSortBar.visibility = View.VISIBLE
        fetchAllResults(keyword, currentSortBy)
    }

    /**
     * 一次性加载全部搜索结果
     * @param keyword 搜索关键词
     * @param orderby 排序方式：lastpost/dateline/replies
     */
    private fun fetchAllResults(keyword: String, orderby: String) {
        pendingKeyword = keyword
        if (!HttpClient.getInstance().isLoggedIn()) {
            tvError.setText(R.string.search_login_required)
            tvError.visibility = View.VISIBLE
            return
        }

        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        tvError.visibility = View.GONE

        java.lang.Thread {
            try {
                // === 1. 抓第1页，提取 searchid & totalPages ===
                val htmlP1 = HttpClient.getInstance().get(ForumParser.getSearchUrl(keyword, 1, orderby))
                var allResults: MutableList<Thread> = ForumParser.parseSearchResults(htmlP1)

                val searchId = ForumParser.extractSearchId(htmlP1)
                val totalPages = ForumParser.parseSearchTotalPages(htmlP1)

                // === 2. 如果有 searchid 且不止一页，并发抓取剩余页 ===
                if (searchId != null && totalPages > 1) {
                    val futures = ArrayList<java.util.concurrent.Future<MutableList<Thread>?>>()
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)

                    for (p in 2..totalPages) {
                        val page = p
                        futures.add(executor.submit<MutableList<Thread>?> {
                            try {
                                val html = HttpClient.getInstance().get(
                                    ForumParser.getSearchPageUrl(searchId, page, orderby)
                                )
                                ForumParser.parseSearchResults(html)
                            } catch (ignored: Exception) {
                                ArrayList()
                            }
                        })
                    }

                    for (f in futures) {
                        val pageResults = f.get()
                        if (pageResults != null) {
                            for (t in pageResults) {
                                var dup = false
                                for (existing in allResults) {
                                    if (existing.tid != null && existing.tid == t.tid) {
                                        dup = true
                                        break
                                    }
                                }
                                if (!dup) allResults.add(t)
                            }
                        }
                    }

                    executor.shutdown()
                }

                // === 3. 初始只展示前 10 条，滑到第 6 条自动加载后续 10 条 ===
                val finalResults = allResults
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    pendingBuffer.clear()
                    displayedResults.clear()
                    if (finalResults.isNotEmpty()) {
                        pendingBuffer.addAll(finalResults)
                        val countToTake = minOf(BATCH_STEP, pendingBuffer.size)
                        val firstBatch = ArrayList(pendingBuffer.subList(0, countToTake))
                        for (i in 0 until countToTake) {
                            pendingBuffer.removeAt(0)
                        }
                        displayedResults.addAll(firstBatch)
                        threadAdapter.setThreadList(firstBatch)
                        recyclerView.visibility = View.VISIBLE
                    } else {
                        threadAdapter.setThreadList(ArrayList())
                        tvEmpty.setText(R.string.search_no_results)
                        tvEmpty.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    tvError.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun dispatchNextSearchBatch() {
        if (pendingBuffer.isEmpty()) return
        val countToTake = minOf(BATCH_STEP, pendingBuffer.size)
        val batch = ArrayList(pendingBuffer.subList(0, countToTake))
        for (i in 0 until countToTake) {
            pendingBuffer.removeAt(0)
        }
        displayedResults.addAll(batch)
        threadAdapter.addThreads(batch)
    }
}
