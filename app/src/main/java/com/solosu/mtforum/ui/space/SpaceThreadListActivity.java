package com.solosu.mtforum.ui.space;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.databinding.ActivitySpaceThreadListBinding;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.solosu.mtforum.ui.widget.DialogHelper;

/**
 * 个人空间帖子/收藏列表页（原生）
 * 支持 3 种模式：my_threads（我的帖子）、my_replies（我的回复）、favorites（我的收藏）
 */
public class SpaceThreadListActivity extends AppCompatActivity {

    private ActivitySpaceThreadListBinding binding;
    private HttpClient httpClient;
    private ThreadAdapter adapter;
    private String mode;
    private String listUrl;
    private String targetUid;
    private String currentFormhash;

    private static final String FAVORITE_STATE_PREFS = "favorite_local_state";
    private static final String KEY_REMOVED_FAVORITES = "removed_favorite_keys";

    private boolean initialLoadFinished;

    private Set<String> getRemovedFavoriteKeys() {
        SharedPreferences prefs = getSharedPreferences(FAVORITE_STATE_PREFS, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_REMOVED_FAVORITES, new HashSet<>()));
    }

    private Set<String> favoriteLocalKeys(Thread thread) {
        Set<String> keys = new HashSet<>();
        if (thread == null) return keys;
        if (!TextUtils.isEmpty(thread.getFavid())) keys.add("favid:" + thread.getFavid());
        if (!TextUtils.isEmpty(thread.getTid())) keys.add("tid:" + thread.getTid());
        return keys;
    }
    private String favoriteLocalKey(Thread thread) {
        if (thread == null) return "";
        // tid 稳定且始终存在，优先使用 tid；favid 只作为补充状态键。
        if (!TextUtils.isEmpty(thread.getTid())) return "tid:" + thread.getTid();
        if (!TextUtils.isEmpty(thread.getFavid())) return "favid:" + thread.getFavid();
        return "";
    }

    private boolean sharesFavoriteKey(Thread first, Thread second) {
        if (first == null || second == null) return false;
        Set<String> firstKeys = favoriteLocalKeys(first);
        firstKeys.retainAll(favoriteLocalKeys(second));
        return !firstKeys.isEmpty();
    }


    private void rememberRemovedFavorite(Thread thread) {
        String key = favoriteLocalKey(thread);
        if (TextUtils.isEmpty(key)) return;
        SharedPreferences prefs = getSharedPreferences(FAVORITE_STATE_PREFS, MODE_PRIVATE);
        Set<String> keys = getRemovedFavoriteKeys();
        keys.addAll(favoriteLocalKeys(thread));
        prefs.edit().putStringSet(KEY_REMOVED_FAVORITES, keys).apply();
    }

    private List<Thread> filterRemovedFavorites(List<Thread> source) {
        if (source == null || source.isEmpty()) return new ArrayList<>();
        Set<String> removed = getRemovedFavoriteKeys();
        List<Thread> result = new ArrayList<>();
        for (Thread thread : source) {
            boolean removedItem = false;
            for (String key : favoriteLocalKeys(thread)) {
                if (removed.contains(key)) {
                    removedItem = true;
                    break;
                }
            }
            if (!removedItem) result.add(thread);
        }
        return result;
    }

    private boolean isFavoriteDeleteResponseSuccessful(String result) {
        if (TextUtils.isEmpty(result) || ForumParser.isLoginPage(result)) return false;
        String text = result.trim().toLowerCase(java.util.Locale.ROOT);
        // Comiis 的 dialog 删除接口成功时常返回空响应或仅返回一段提示页；
        // 真正结果以删除后重新读取收藏列表为准，因此这里只拦截明确错误。
        if (text.contains("formhash错误") || text.contains("请先登录")
                || text.contains("没有权限") || text.contains("操作失败")
                || text.contains("删除失败") || text.contains("ajaxerror")
                || text.contains("error") || text.contains("非法操作")) return false;
        return text.contains("\"success\":true")
                || text.contains("\"status\":1")
                || text.contains("\"code\":0")
                || text.contains("success=1")
                || text.contains("succeed")
                || text.contains("删除成功")
                || text.contains("取消收藏成功");
    }

    private boolean containsFavorite(List<Thread> list, Thread target) {
        if (list == null || target == null) return false;
        String key = favoriteLocalKey(target);
        for (Thread item : list) {
            if (!TextUtils.isEmpty(key) && key.equals(favoriteLocalKey(item))) return true;
            // favid 可能因模板变化而解析不到，tid 作为最终兜底标识。
            if (!TextUtils.isEmpty(target.getTid())
                    && target.getTid().equals(item.getTid())) return true;
        }
        return false;
    }
    /**
     * 收藏页本身只返回“标题 + 删除链接”，不会返回 ThreadAdapter 所需的作者、头像、版块和统计数据。
     * 因此先解析收藏记录，再按 tid 请求帖子详情补齐卡片数据；单条详情失败时保留基础收藏项，
     * 避免收藏页面出现空白作者、空版块和全部统计为 0 的异常卡片。
     */
    private List<Thread> loadFavoriteThreads(String favoriteHtml) {
        List<Thread> basicList = ForumParser.parseFavoriteList(favoriteHtml);
        if (basicList == null || basicList.isEmpty()) return new ArrayList<>();

        List<Thread> result = new ArrayList<>();
        for (Thread basic : basicList) {
            if (basic == null || TextUtils.isEmpty(basic.getTid())) continue;
            Thread enriched = enrichFavoriteThread(basic);
            result.add(enriched != null ? enriched : basic);
        }
        return result;
    }

    private Thread enrichFavoriteThread(Thread basic) {
        try {
            String detailHtml = httpClient.get(ForumParser.getThreadDetailUrl(basic.getTid()));
            if (TextUtils.isEmpty(detailHtml) || ForumParser.isLoginPage(detailHtml)) return basic;

            com.solosu.mtforum.model.PostDetail detail =
                    ForumParser.parseThreadDetail(detailHtml);
            if (detail == null) return basic;

            Thread thread = new Thread();
            thread.setTid(basic.getTid());
            thread.setFavid(basic.getFavid());
            thread.setTitle(!TextUtils.isEmpty(detail.getTitle())
                    ? detail.getTitle() : basic.getTitle());
            thread.setAuthor(detail.getAuthor());
            thread.setAuthorUid(detail.getAuthorUid());
            thread.setAuthorLevel(detail.getAuthorLevel());
            thread.setAvatarUrl(detail.getAvatarUrl());
            thread.setForumName(detail.getForumName());
            thread.setForumFid(detail.getForumFid());
            thread.setPublishTime(detail.getPublishTime());
            thread.setReplies(detail.getReplyCount());
            thread.setLikes(detail.getLikeCount());
            thread.setImageUrls(detail.getImageUrls());
            thread.setHasImage(detail.getImageUrls() != null && !detail.getImageUrls().isEmpty());
            thread.setHasHiddenContent(detail.isHasHiddenContent());
            return thread;
        } catch (Exception ignored) {
            return basic;
        }
    }
    private List<Thread> fetchLatestFavorites() throws Exception {
        String verifyUrl = listUrl + (listUrl.contains("?") ? "&" : "?")
                + "_refresh=" + System.currentTimeMillis();
        String verifyHtml = httpClient.get(verifyUrl);
        return ForumParser.parseFavoriteList(verifyHtml);
    }

    /**
     * Discuz! 标准取消收藏接口。旧的 space&do=favorite&delfavorite 参数在部分模板中
     * 只会返回收藏页面，并不会真正执行删除，因此这里使用 spacecp/favorite/delete。
     */
    private String resolveFavoriteId(Thread target, String html) {
        if (target == null || TextUtils.isEmpty(html)) return "";
        List<Thread> parsed = ForumParser.parseFavoriteList(html);
        if (parsed != null) {
            for (Thread item : parsed) {
                if (item != null && !TextUtils.isEmpty(target.getTid())
                        && target.getTid().equals(item.getTid())
                        && !TextUtils.isEmpty(item.getFavid())) {
                    return item.getFavid();
                }
            }
        }
        return target.getFavid();
    }

    /**
     * 尝试从收藏页面 HTML 中提取收藏对话框表单里的 formhash 和 favid
     * Comiis 模板的删除对话框表单 id 为 favoriteform_{favid}，
     * 包含隐藏的 input[name=formhash]。
     */
    private String[] extractFavoriteFormInfo(String html) {
        if (TextUtils.isEmpty(html)) return null;
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            // 查找收藏对话框表单
            org.jsoup.nodes.Element form = doc.select("form[id*=favoriteform]").first();
            if (form != null) {
                // 从 action 中提取 favid
                String action = form.attr("action");
                String favid = "";
                java.util.regex.Matcher fm = java.util.regex.Pattern.compile("[?&]favid=(\\d+)").matcher(action);
                if (fm.find()) favid = fm.group(1);
                // 提取 formhash
                org.jsoup.nodes.Element fhInput = form.select("input[name=formhash]").first();
                String formhash = (fhInput != null) ? fhInput.attr("value") : "";
                return new String[]{favid, formhash};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean requestDeleteFavorite(Thread target) {
        try {
            // 收藏可能是在 WebView 中登录的；若 OkHttp 尚无有效会话，先同步 Cookie。
            if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager();
            String page = httpClient.get(listUrl + "&_refresh=" + System.currentTimeMillis());

            // ★ 优先从收藏对话框表单中提取 formhash 和 favid（Comiis 模板特有）
            String formhash = null;
            String favid = null;

            // 尝试从对话框中提取
            String[] formInfo = extractFavoriteFormInfo(page);
            if (formInfo != null && !TextUtils.isEmpty(formInfo[0]) && !TextUtils.isEmpty(formInfo[1])) {
                favid = formInfo[0];
                formhash = formInfo[1];
            } else {
                // 回退到旧的解析方式
                formhash = ForumParser.parseFormhash(page);
                if (TextUtils.isEmpty(formhash)) {
                    // ★ 收藏页本身没有全局 input[name=formhash]，尝试从论坛首页获取
                    formhash = ForumParser.parseFormhash(
                            httpClient.get(HttpClient.BASE_URL + "forum.php?mobile=2&_refresh="
                                    + System.currentTimeMillis()));
                }
                // 如果仍未获取到，尝试从详情页获取（桌面版更可靠）
                if (TextUtils.isEmpty(formhash) && !TextUtils.isEmpty(target.getTid())) {
                    String detailHtml = httpClient.getDesktop(HttpClient.BASE_URL
                            + "forum.php?mod=viewthread&tid=" + target.getTid());
                    formhash = ForumParser.parseFormhash(detailHtml);
                }
                favid = resolveFavoriteId(target, page);
            }
            currentFormhash = formhash;
            if (TextUtils.isEmpty(favid)) return false;

            // ★ 真实 Comiis 删除接口：POST 表单提交（与浏览器对话框行为一致）
            // 浏览器确认对话框实际提交的 POST 字段：
            //   referer=.../home.php?mod=space&do=favorite&mobile=2
            //   deletesubmit=true
            //   formhash=f485df32
            //   handlekey=comiis
            String deleteUrl = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=favorite&op=delete"
                    + "&favid=" + favid + "&type=all&mobile=2";

            Map<String, String> params = new HashMap<>();
            params.put("referer", listUrl);
            params.put("deletesubmit", "true");
            if (!TextUtils.isEmpty(formhash)) params.put("formhash", formhash);
            params.put("handlekey", "comiis");

            String postResult = httpClient.post(deleteUrl, params);
            List<Thread> latest = fetchLatestFavorites();
            if (!containsFavorite(latest, target)) return true;
            if (isFavoriteDeleteResponseSuccessful(postResult)) return true;

            // 备用：尝试 GET 方式（某些旧版模板）
            String getUrl = deleteUrl + "&formhash=" + (formhash == null ? "" : formhash) + "&inajax=1";
            String getResult = httpClient.get(getUrl);
            latest = fetchLatestFavorites();
            return !containsFavorite(latest, target)
                    || isFavoriteDeleteResponseSuccessful(getResult);
        } catch (Exception ignored) {
            return false;
        }
    }
    protected void onResume() {
        super.onResume();
        // 每次可见时都重新读取网页端收藏，不使用本地删除缓存覆盖服务器真实数据。
        if (initialLoadFinished && "favorites".equals(mode)) {
            loadData();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySpaceThreadListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        httpClient = HttpClient.getInstance();

        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "my_threads";
        targetUid = getIntent().getStringExtra("uid");

        String title;
        switch (mode) {
            case "favorites":
                title = "我的收藏";
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=favorite&mobile=2";
                break;
            case "uid_threads":
                String username = getIntent().getStringExtra("username");
                title = (username == null || username.isEmpty()) ? "用户的帖子" : username + "的帖子";
                if (targetUid == null || targetUid.isEmpty()) {
                    title = "用户的帖子";
                    listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2";
                } else {
                    listUrl = HttpClient.BASE_URL + "home.php?mod=space&uid=" + targetUid
                            + "&do=thread&view=me&mobile=2";
                }
                break;
            case "my_replies":
                title = "我的回复";
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2&type=reply";
                break;
            default:
                title = "我的帖子";
                listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=thread&view=me&mobile=2";
                break;
        }

        binding.toolbar.setTitle(title);
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new ThreadAdapter(this);
        adapter.setOnItemClickListener((thread, position) -> {
            if (thread != null && thread.getTid() != null) {
                Intent intent = new Intent(this, ThreadDetailActivity.class);
                intent.putExtra("tid", thread.getTid());
                startActivity(intent);
            }
        });
        adapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(this, UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        if ("favorites".equals(mode)) {
            ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                    ItemTouchHelper.LEFT) {
                @Override public boolean onMove(@androidx.annotation.NonNull RecyclerView recyclerView,
                                                  @androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder,
                                                  @androidx.annotation.NonNull RecyclerView.ViewHolder target) {
                    return false;
                }
                @Override public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder holder,
                                                int direction) {
                    int position = holder.getBindingAdapterPosition();
                    Thread thread = adapter.getItem(position);
                    if (thread == null) return;
                    confirmDeleteFavorite(thread, position);
                }
            });
            helper.attachToRecyclerView(binding.recyclerView);
            binding.recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                private final android.view.GestureDetector detector = new android.view.GestureDetector(
                        SpaceThreadListActivity.this,
                        new android.view.GestureDetector.SimpleOnGestureListener() {
                            @Override public void onLongPress(android.view.MotionEvent e) {
                                View child = binding.recyclerView.findChildViewUnder(e.getX(), e.getY());
                                if (child == null) return;
                                int position = binding.recyclerView.getChildAdapterPosition(child);
                                Thread thread = adapter.getItem(position);
                                if (thread != null) confirmDeleteFavorite(thread, position);
                            }
                            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                        });
                @Override public boolean onInterceptTouchEvent(@androidx.annotation.NonNull RecyclerView rv,
                                                               @androidx.annotation.NonNull android.view.MotionEvent e) {
                    detector.onTouchEvent(e);
                    return false;
                }
            });
        }

        binding.swipeRefresh.setOnRefreshListener(this::loadData);
        binding.swipeRefresh.setColorSchemeResources(R.color.primary);

        loadData();
    }

    private void confirmDeleteFavorite(Thread thread, int position) {
        android.app.Dialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("删除收藏")
                .setMessage("确定取消收藏“" + (thread.getTitle() == null ? "此帖子" : thread.getTitle()) + "”吗？")
                .setNegativeButton("取消", null)
                .show();
                DialogHelper.applyToAlertDialog(alertDialog, this);
    }

    private void deleteFavorite(Thread thread, int position) {
        if (thread == null) {
            Toast.makeText(this, "收藏项为空，无法删除", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(thread.getFavid()) && TextUtils.isEmpty(thread.getTid())) {
            Toast.makeText(this, "未获取到收藏记录ID或帖子ID，无法删除", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.progressBar.setVisibility(View.VISIBLE);
        new java.lang.Thread(() -> {
            boolean success = requestDeleteFavorite(thread);
            String message = success ? "已取消收藏" : "删除失败";
            final boolean ok = success;
            final String msg = message;
            runOnUiThread(() -> {
                binding.progressBar.setVisibility(View.GONE);
                if (ok) {
                    rememberRemovedFavorite(thread);
                    adapter.removeItem(position);
                    if (adapter.getItemCount() == 0) {
                        binding.recyclerView.setVisibility(View.GONE);
                        binding.tvEmpty.setVisibility(View.VISIBLE);
                    }
                } else {
                    adapter.notifyItemChanged(position);
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void loadData() {
        binding.progressBar.setVisibility(android.view.View.VISIBLE);
        binding.swipeRefresh.setEnabled(false);

        new java.lang.Thread(() -> {
            try {
httpClient.syncFromCookieManager();
                 String html = httpClient.get(listUrl);

                // 检测登录过期
                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread(() -> {
                        binding.progressBar.setVisibility(android.view.View.GONE);
                        binding.swipeRefresh.setRefreshing(false);
                        binding.swipeRefresh.setEnabled(true);
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                List<Thread> items = new ArrayList<>();
if ("favorites".equals(mode)) {
                     List<Thread> favList = loadFavoriteThreads(html);
                     if (favList != null) items.addAll(favList);
                 } else {
                    List<Thread> threadList = ForumParser.parseForumThreadList(html);
                    if (threadList != null) items.addAll(threadList);
                }

                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(android.view.View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);

                    if (items.isEmpty()) {
                        binding.recyclerView.setVisibility(android.view.View.GONE);
                        binding.tvEmpty.setVisibility(android.view.View.VISIBLE);
                    } else {
                        binding.recyclerView.setVisibility(android.view.View.VISIBLE);
                        binding.tvEmpty.setVisibility(android.view.View.GONE);
                        adapter.setThreadList(items);
                    }
                    initialLoadFinished = true;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(android.view.View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}