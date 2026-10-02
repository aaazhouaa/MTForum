package com.solosu.mtforum.ui.forum;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.databinding.ActivityForumDetailBinding;
import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 版块详情页 — 展示指定版块的帖子列表
 * 支持4种排序：全部、最新发表、热门动态、精华
 */
public class ForumDetailActivity extends AppCompatActivity {

    private ActivityForumDetailBinding binding;
    private HttpClient httpClient;
    private ThreadAdapter threadAdapter;
    private final List<Thread> allThreads = new ArrayList<>();

    // 从Intent获取的版块信息
    private String fid;
    private String forumName;
    private String description;
    private String iconUrl;
    private int totalPosts;
    private int totalThreads;

    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityForumDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);

        httpClient = HttpClient.getInstance();

        // 从Intent读取数据
        fid = getIntent().getStringExtra("fid");
        forumName = getIntent().getStringExtra("forumName");
        description = getIntent().getStringExtra("description");
        iconUrl = getIntent().getStringExtra("iconUrl");
        totalPosts = getIntent().getIntExtra("totalPosts", 0);
        totalThreads = getIntent().getIntExtra("totalThreads", 0);

        // Toolbar
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            if (forumName != null && !forumName.isEmpty()) {
                getSupportActionBar().setTitle(forumName);
            }
        }

        // 版块头部信息
        binding.tvForumName.setText(forumName != null ? forumName : "");
        binding.tvForumDesc.setText(description != null && !description.isEmpty() ? description : "暂无描述");

        // 加载图标
        if (iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(this)
                    .load(iconUrl)
                    .placeholder(R.drawable.ic_circle)
                    .circleCrop()
                    .into(binding.ivForumIcon);
        } else {
            binding.ivForumIcon.setImageResource(R.drawable.ic_circle);
        }

        // 加入按钮切换
        binding.btnJoin.setOnClickListener(v -> {
            MaterialButton btn = binding.btnJoin;
            if (btn.getText().toString().equals(getString(R.string.forum_join))) {
                btn.setText(R.string.forum_joined);
            } else {
                btn.setText(R.string.forum_join);
            }
        });

        // 设置帖子列表
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.recyclerView.setLayoutManager(layoutManager);

        threadAdapter = new ThreadAdapter(this);
        threadAdapter.setOnItemClickListener((thread, position) -> NavigationHelper.openThread(ForumDetailActivity.this, thread));
        threadAdapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(this, UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });
        binding.recyclerView.setAdapter(threadAdapter);



        // RecyclerView 滚动到底部时加载更多
        binding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (dy <= 0 || isLoading || !hasMore) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastCompletelyVisibleItemPosition() >= lm.getItemCount() - 3) {
                    currentPage++;
                    loadThreads(currentPage);
                }
            }
        });

        // TabLayout 切换
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentPage = 1;
                hasMore = true;
                loadThreads(currentPage);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                currentPage = 1;
                hasMore = true;
                loadThreads(currentPage);
            }
        });

        // 初始加载
        loadThreads(currentPage);
    }

    /**
     * 根据当前选中的Tab加载帖子
     * Tab 0: 全部 (默认排序)
     * Tab 1: 最新发表 (orderby=dateline)
     * Tab 2: 热门动态 (order=hot)
     * Tab 3: 精华 (filter=digest&digest=1, 服务端过滤)
     */
    private void loadThreads(int page) {
        if (isLoading) return;
        isLoading = true;
        binding.tvEmpty.setVisibility(View.GONE);

        int tabPosition = binding.tabLayout.getSelectedTabPosition();

        new java.lang.Thread(() -> {
            try {
                // 根据Tab位置拼接排序参数(对齐版块页真实排序链接)
                String sortParam = "";
                if (tabPosition == 3) {
                    // 精华:filter=digest&digest=1,由服务端过滤
                    sortParam = "&filter=digest&digest=1";
                } else if (tabPosition == 1) {
                    // 最新发表:filter=lastpost&orderby=lastpost
                    sortParam = "&filter=lastpost&orderby=lastpost";
                } else if (tabPosition == 2) {
                    // 热门动态:Discuz 原生热度排序
                    sortParam = "&filter=heat&orderby=heats";
                }

                String url = ForumParser.getThreadListUrl(fid + sortParam, page);
                String html = httpClient.get(url);
                List<Thread> threads = ForumParser.parseForumThreadList(html);
                if (threads == null) threads = new java.util.ArrayList<>();
                // 黑名单过滤:拉黑作者的帖子直接不进列表
                java.util.Set<String> black = com.solosu.mtforum.session.BlacklistManager.uidSet(ForumDetailActivity.this);
                if (!black.isEmpty()) {
                    java.util.Iterator<Thread> it = threads.iterator();
                    while (it.hasNext()) {
                        Thread t = it.next();
                        if (t != null && t.getAuthorUid() != null && black.contains(t.getAuthorUid())) it.remove();
                    }
                }
                final List<Thread> resultThreads = threads;

                runOnUiThread(() -> {
                    isLoading = false;

                    if (resultThreads.isEmpty()) {
                        hasMore = false;
                        if (page == 1) {
                            allThreads.clear();
                            threadAdapter.setThreadList(new ArrayList<>());
                            binding.tvEmpty.setText(R.string.forum_empty);
                            binding.tvEmpty.setVisibility(View.VISIBLE);
                        }
                        return;
                    }

                    if (page == 1) {
                        allThreads.clear();
                    }
                    // 置顶帖与普通帖统一显示在列表中
                    if (page == 1) {
                        threadAdapter.setThreadList(resultThreads);
                    } else {
                        threadAdapter.addThreads(resultThreads);
                    }
                    allThreads.addAll(resultThreads);
                    binding.tvEmpty.setVisibility(View.GONE);
                    // build63: 取消列表页收藏数预取(每页20发->0),改为进详情页时回填缓存
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    isLoading = false;
                    if (page == 1 && allThreads.isEmpty()) {
                        binding.tvEmpty.setText(R.string.forum_empty);
                        binding.tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}