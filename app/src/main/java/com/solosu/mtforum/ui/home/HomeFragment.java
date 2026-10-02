package com.solosu.mtforum.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.databinding.FragmentHomeBinding;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.search.SearchActivity;
import com.solosu.mtforum.ai.AiChatActivity;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;
import com.solosu.mtforum.ui.widget.NavBarAutoHideHelper;

import java.util.List;

/**
 * 首页 Fragment
 * 展示最新帖子列表,支持下拉刷新、翻页加载、热板推荐、搜索跳转
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private HttpClient httpClient;
    private ThreadAdapter threadAdapter;
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    private static final int PAGE_SIZE = 20;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        httpClient = HttpClient.getInstance();

        // 搜索图标点击 -> 打开搜索页面
        binding.ivAi.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AiChatActivity.class)));

        binding.ivSearch.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SearchActivity.class);
            startActivity(intent);
        });
        // 搜索图标毛玻璃背景
        binding.ivAi.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));
        binding.ivSearch.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));

        // RecyclerView + ThreadAdapter
        threadAdapter = new ThreadAdapter(requireContext());
        threadAdapter.setOnItemClickListener((thread, position) -> {
            NavigationHelper.openThread(requireContext(), thread);
        });

        threadAdapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(requireContext(), UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(threadAdapter);

        // 滚动监听实现翻页加载
        binding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // build71: 滚动方向 -> 底部导航栏自动隐藏/出现
                NavBarAutoHideHelper.onScrolled(getActivity(), dy);
                if (dy <= 0 || isLoading || !hasMore) return;
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int firstVisibleItemPosition = lm.findFirstVisibleItemPosition();
                    if (visibleItemCount + firstVisibleItemPosition >= totalItemCount - 2) {
                        loadMoreThreads();
                    }
                }
            }
        });

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener(this::refreshThreads);
        binding.swipeRefresh.setColorSchemeResources(
                com.google.android.material.R.color.design_default_color_primary,
                android.R.color.holo_orange_light,
                android.R.color.holo_green_light
        );

        // 加载热板推荐
        loadHotBoards();

        // 首次加载
        refreshThreads();
    }

    /**
     * 加载热帖排行列表
     */
    private void loadHotBoards() {
        new java.lang.Thread(() -> {
            try {
                String url = "https://bbs.binmt.cc/forum.php?mod=guide&view=hot&mobile=2";
                String html = httpClient.get(url);
                if (!isAdded() || android.text.TextUtils.isEmpty(html)) return;

                // 从 HTML 中提取所有带排名序号的热帖
                // 匹配: <li class="b_t"><a href="...thread-数字-1-1.html" title="标题"><em class="...">排名</em>标题</a></li>
                java.util.ArrayList<String[]> list = new java.util.ArrayList<>();
                int searchFrom = 0;
                while (list.size() < 10) {
                    int tidx = html.indexOf("thread-", searchFrom);
                    if (tidx < 0) break;
                    int endIdx = html.indexOf("-1-1.html", tidx);
                    if (endIdx < 0) { searchFrom = tidx + 7; continue; }
                    String tid = html.substring(tidx + 7, endIdx);
                    
                    // 检查这个链接后面是否有 <em>数字</em>(排名序号),有则说明是热帖排行
                    int afterHref = html.indexOf(">", endIdx + 9);
                    if (afterHref < 0) { searchFrom = endIdx + 9; continue; }
                    int emStart = html.indexOf("<em", afterHref);
                    if (emStart < 0 || emStart > afterHref + 200) { searchFrom = endIdx + 9; continue; }
                    int emContentStart = html.indexOf(">", emStart);
                    if (emContentStart < 0) { searchFrom = endIdx + 9; continue; }
                    int emContentEnd = html.indexOf("</em>", emContentStart);
                    if (emContentEnd < 0) { searchFrom = endIdx + 9; continue; }
                    String rankStr = html.substring(emContentStart + 1, emContentEnd);
                    // 排名序号必须是纯数字
                    if (!rankStr.matches("\\d+")) { searchFrom = endIdx + 9; continue; }
                    
                                        // 提取 title 属性(在 href 后面查找)
                    int titleStart = html.indexOf("title=\"", endIdx);
                    if (titleStart < 0 || titleStart > endIdx + 300) { searchFrom = endIdx + 9; continue; }
                    int titleEnd = html.indexOf("\"", titleStart + 7);
                    if (titleEnd < 0) { searchFrom = endIdx + 9; continue; }
                    String title = html.substring(titleStart + 7, titleEnd);
                    
                    list.add(new String[]{tid, title});
                    searchFrom = endIdx + 9;
                }
                if (list.isEmpty()) return;

                final java.util.ArrayList<String[]> topThreads = new java.util.ArrayList<>(list);
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null || threadAdapter == null) return;

                    float density = requireContext().getResources().getDisplayMetrics().density;
                    LinearLayout headerContainer = new LinearLayout(requireContext());
                    headerContainer.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins((int)(12*density), (int)(8*density), (int)(12*density), (int)(10*density));
                    headerContainer.setLayoutParams(lp);
                    headerContainer.setPadding((int)(6*density), (int)(10*density), (int)(10*density), (int)(10*density));
                    headerContainer.setBackground(
                            com.solosu.mtforum.ui.widget.FrostedGlassDrawable.create(requireContext(), 14f));
                    headerContainer.setClipToOutline(true);

                    // 标题行: 热帖排行 + 查看更多
                    LinearLayout headerRow = new LinearLayout(requireContext());
                    headerRow.setOrientation(LinearLayout.HORIZONTAL);
                    headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView tvHeader = new TextView(requireContext());
                    tvHeader.setText("热帖排行");
                    tvHeader.setTextSize(19f);
                    tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvHeader.setTextColor(requireContext().getColor(R.color.text_primary));
                    tvHeader.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    headerRow.addView(tvHeader);
                    headerContainer.addView(headerRow);

                    for (int i = 0; i < topThreads.size(); i++) {
                        String[] item = topThreads.get(i);
                        LinearLayout row = new LinearLayout(requireContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.BOTTOM);
                        int rank = i + 1;

                        // 排名序号圆角背景
                        TextView tvRank = new TextView(requireContext());
                        tvRank.setText(String.valueOf(rank));
                        tvRank.setTextSize(11f);
                        tvRank.setTextColor(0xFFFFFFFF);
                        tvRank.setGravity(android.view.Gravity.CENTER);
                        int rankSize = (int)(20*density);
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rankSize, rankSize);
                        rlp.topMargin = (int)(8*density);
                        tvRank.setLayoutParams(rlp);
                        // 不同排名不同颜色
                        int bgColor;
                        if (rank == 1) bgColor = 0xFFFF705E;
                        else if (rank == 2) bgColor = 0xFFFFB900;
                        else if (rank == 3) bgColor = 0xFFA8C500;
                        else bgColor = 0xFFCCCCCC;
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                        bg.setCornerRadius(10);
                        bg.setColor(bgColor);
                        tvRank.setBackground(bg);

                        // 标题
                        TextView tvTitle = new TextView(requireContext());
                        tvTitle.setText(item[1]);
                        tvTitle.setTextSize(13f);
                        tvTitle.setTextColor(requireContext().getColor(R.color.text_primary));
                        tvTitle.setSingleLine(true);
                        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        tvTitle.setPadding((int)(8*density), (int)(8*density), 0, (int)(8*density));

                        row.addView(tvRank);
                        row.addView(tvTitle);
                        row.setTag(item[0]);
                        row.setClickable(true);
                        row.setFocusable(true);
                        // 透明波纹点击效果
                        android.util.TypedValue rippleVal = new android.util.TypedValue();
                        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleVal, true);
                        row.setBackgroundResource(rippleVal.resourceId);
                        final String tid = item[0];
                        row.setOnClickListener(v -> {
                            NavigationHelper.openThread(requireContext(), tid);
                        });

                        headerContainer.addView(row);
                        if (i < topThreads.size() - 1) {
                            View divider = new View(requireContext());
                            divider.setLayoutParams(new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
                            divider.setBackgroundColor(0x1A000000);
                            divider.setPadding((int)(28*density), 0, 0, 0);
                            headerContainer.addView(divider);
                        }
                    }

                    // 底部隔离线
                    View bottomDivider = new View(requireContext());
                    bottomDivider.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0));
                    bottomDivider.setBackgroundColor(0x00000000);
                    headerContainer.addView(bottomDivider);

                    // 设置为 RecyclerView 头部
                    threadAdapter.setHeaderView(headerContainer);
                });
            } catch (Exception ignored) {
                // 热帖加载失败不影响主列表
            }
        }).start();
    }

    private void refreshThreads() {
        currentPage = 1;
        hasMore = true;
        loadThreads(currentPage, true);
    }

    private void loadMoreThreads() {
        if (isLoading || !hasMore) return;
        currentPage++;
        loadThreads(currentPage, false);
    }

    private void loadThreads(int page, boolean isRefresh) {
        isLoading = true;
        binding.swipeRefresh.setRefreshing(true);

        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = ForumParser.getHomeUrl(page);
                    String html = httpClient.get(url);
                    List<Thread> threads = ForumParser.parseThreadList(html);

                    if (!isAdded()) return;
                    // 黑名单过滤:拉黑作者的帖子直接不进列表
                    if (threads != null) {
                        java.util.Set<String> black = com.solosu.mtforum.session.BlacklistManager.uidSet(requireContext());
                        if (!black.isEmpty()) {
                            java.util.Iterator<Thread> it = threads.iterator();
                            while (it.hasNext()) {
                                Thread t = it.next();
                                if (t != null && t.getAuthorUid() != null && black.contains(t.getAuthorUid())) it.remove();
                            }
                        }
                    }
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (threads != null && !threads.isEmpty()) {
                            if (isRefresh) {
                                threadAdapter.setThreadList(threads);
                            } else {
                                threadAdapter.addThreads(threads);
                            }
                            hasMore = threads.size() >= PAGE_SIZE;
                            // build63: 取消列表页收藏数预取(每页20发->0),改为进详情页时回填缓存
                        } else {
                            hasMore = false;
                            if (isRefresh) {
                                threadAdapter.setThreadList(null);
                            }
                        }
                        isLoading = false;
                        binding.swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        isLoading = false;
                        binding.swipeRefresh.setRefreshing(false);
                        if (isRefresh && threadAdapter.getItemCount() == 0) {
                            android.widget.Toast.makeText(requireContext(),
                                    "加载失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (threadAdapter != null && threadAdapter.getItemCount() == 0) {
            refreshThreads();
        } else if (threadAdapter != null) {
            // build80: 从详情页返回时, 用点赞缓存刷新卡片, 让点赞数及时更新
            applyCachedLikes();
        }
    }

    /** build80: 用详情页点赞缓存回填列表卡片点赞数 */
    private void applyCachedLikes() {
        if (threadAdapter == null) return;
        try {
            int n = threadAdapter.getItemCount();
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                Thread t = threadAdapter.getItem(i);
                if (t == null || TextUtils.isEmpty(t.getTid())) continue;
                Integer likes = com.solosu.mtforum.session.PostCountsCache.getLikes(t.getTid());
                if (likes != null && likes.intValue() != t.getLikes()) {
                    t.setLikes(likes.intValue());
                    changed = true;
                }
            }
            if (changed) threadAdapter.notifyDataSetChanged();
        } catch (Exception ignore) { }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
