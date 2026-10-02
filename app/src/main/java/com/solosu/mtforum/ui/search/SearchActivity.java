package com.solosu.mtforum.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;
import com.solosu.mtforum.ui.space.UserProfileActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果页面（一次性加载全部结果跨页合并显示，支持排序切换）
 */
public class SearchActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private EditText etSearch;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvError;
    private ThreadAdapter threadAdapter;

    // 排序相关
    private LinearLayout layoutSortBar;
    private TextView btnSortLastpost, btnSortDateline, btnSortReplies;
    private String currentSortBy = "lastpost"; // lastpost / dateline / replies
    private String pendingKeyword = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        toolbar = findViewById(R.id.toolbar);
        etSearch = findViewById(R.id.et_search);
        progressBar = findViewById(R.id.progress_bar);
        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty = findViewById(R.id.tv_empty);
        tvError = findViewById(R.id.tv_error);
        layoutSortBar = findViewById(R.id.layout_sort_bar);
        btnSortLastpost = findViewById(R.id.btn_sort_lastpost);
        btnSortDateline = findViewById(R.id.btn_sort_dateline);
        btnSortReplies = findViewById(R.id.btn_sort_replies);

        // === Toolbar ===
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        // === RecyclerView ===
        threadAdapter = new ThreadAdapter(this);
        threadAdapter.setOnItemClickListener((thread, position) -> {
            Intent intent = new Intent(this, ThreadDetailActivity.class);
            intent.putExtra("tid", thread.getTid());
            startActivity(intent);
        });
        threadAdapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(this, UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(threadAdapter);

        // === 搜索按钮 ===
        findViewById(R.id.btn_search).setOnClickListener(v -> performSearch());

        // === 键盘搜索动作 ===
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        // === 排序切换 ===
        btnSortLastpost.setOnClickListener(v -> switchSort("lastpost"));
        btnSortDateline.setOnClickListener(v -> switchSort("dateline"));
        btnSortReplies.setOnClickListener(v -> switchSort("replies"));

        // === Intent 携带关键词自动搜索 ===
        String keyword = getIntent().getStringExtra("keyword");
        if (!TextUtils.isEmpty(keyword)) {
            etSearch.setText(keyword);
            performSearch();
        }
    }

    /**
     * 切换排序方式
     */
    private void switchSort(String orderby) {
        if (orderby.equals(currentSortBy)) return;
        currentSortBy = orderby;
        updateSortChips();
        if (!TextUtils.isEmpty(pendingKeyword)) {
            fetchAllResults(pendingKeyword, currentSortBy);
        }
    }

    /**
     * 更新排序按钮的激活/非激活样式
     */
    private void updateSortChips() {
        btnSortLastpost.setBackgroundResource(
                "lastpost".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortLastpost.setTextColor(ContextCompat.getColor(this,
                "lastpost".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));

        btnSortDateline.setBackgroundResource(
                "dateline".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortDateline.setTextColor(ContextCompat.getColor(this,
                "dateline".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));

        btnSortReplies.setBackgroundResource(
                "replies".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortReplies.setTextColor(ContextCompat.getColor(this,
                "replies".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));
    }

    private void performSearch() {
        String keyword = etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            etSearch.setError(getString(R.string.search_hint));
            return;
        }
        threadAdapter.setThreadList(null);
        currentSortBy = "lastpost";
        updateSortChips();
        layoutSortBar.setVisibility(View.VISIBLE);
        fetchAllResults(keyword, currentSortBy);
    }

    /**
     * 一次性加载全部搜索结果
     * @param keyword 搜索关键词
     * @param orderby 排序方式：lastpost/dateline/replies
     */
    private void fetchAllResults(String keyword, String orderby) {
        pendingKeyword = keyword;
        if (!HttpClient.getInstance().isLoggedIn()) {
            tvError.setText(R.string.search_login_required);
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);
        tvError.setVisibility(View.GONE);

        new java.lang.Thread(() -> {
            try {
                // === 1. 抓第1页，提取 searchid & totalPages ===
                String htmlP1 = HttpClient.getInstance().get(ForumParser.getSearchUrl(keyword, 1, orderby));
                List<Thread> allResults = ForumParser.parseSearchResults(htmlP1);
                if (allResults == null) allResults = new ArrayList<>();

                String searchId = ForumParser.extractSearchId(htmlP1);
                int totalPages = ForumParser.parseSearchTotalPages(htmlP1);

                // === 2. 如果有 searchid 且不止一页，并发抓取剩余页 ===
                if (searchId != null && totalPages > 1) {
                    List<java.util.concurrent.Future<List<Thread>>> futures = new ArrayList<>();
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);

                    for (int p = 2; p <= totalPages; p++) {
                        final int page = p;
                        futures.add(executor.submit(() -> {
                            try {
                                String html = HttpClient.getInstance().get(
                                        ForumParser.getSearchPageUrl(searchId, page, orderby));
                                return ForumParser.parseSearchResults(html);
                            } catch (Exception ignored) {
                                return new ArrayList<>();
                            }
                        }));
                    }

                    for (java.util.concurrent.Future<List<Thread>> f : futures) {
                        List<Thread> pageResults = f.get();
                        if (pageResults != null) {
                            for (Thread t : pageResults) {
                                boolean dup = false;
                                for (Thread existing : allResults) {
                                    if (existing.getTid() != null && existing.getTid().equals(t.getTid())) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (!dup) allResults.add(t);
                            }
                        }
                    }

                    executor.shutdown();
                }

                // === 3. 一次性显示全部结果 ===
                final List<Thread> finalResults = allResults;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (!finalResults.isEmpty()) {
                        threadAdapter.setThreadList(finalResults);
                        recyclerView.setVisibility(View.VISIBLE);
                    } else {
                        tvEmpty.setText(R.string.search_no_results);
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvError.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }
}