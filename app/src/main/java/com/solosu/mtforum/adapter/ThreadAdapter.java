package com.solosu.mtforum.adapter;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 帖子列表 RecyclerView Adapter
 */
public class ThreadAdapter extends RecyclerView.Adapter<ThreadAdapter.ViewHolder> {

    private List<Thread> threadList = new ArrayList<>();
    private View headerView;
    private static final int VIEW_TYPE_HEADER = -1;
    private Context context;
    private OnItemClickListener listener;
    private OnUserClickListener userClickListener;
    private volatile java.util.Set<String> serverFollowingUids;
    private volatile boolean serverFollowingLoaded;
    private boolean followStateLoading;
    private final Object followStateLock = new Object();

    public interface OnItemClickListener {
        void onItemClick(Thread thread, int position);
    }

    public interface OnUserClickListener {
        void onUserClick(Thread thread);
    }

    public ThreadAdapter(Context context) {
        this.context = context;
    }

    public void setThreadList(List<Thread> list) {
        this.threadList = list != null ? list : new ArrayList<>();
        serverFollowingUids = null;
        serverFollowingLoaded = false;
        followStateLoading = false;
        notifyDataSetChanged();
    }

    /** 按 tid 定位刷新卡片(收藏数预取完成后回调) */
    public void notifyItemChangedByTid(String tid) {
        if (tid == null) return;
        for (int i = 0; i < threadList.size(); i++) {
            Thread t = threadList.get(i);
            if (t != null && tid.equals(t.getTid())) {
                notifyItemChanged(i + (headerView != null ? 1 : 0));
                return;
            }
        }
    }

    public void addThreads(List<Thread> list) {
        if (list != null) {
            int start = threadList.size();
            threadList.addAll(list);
            notifyItemRangeInserted(start, list.size());
        }
    }

    public void setHeaderView(View view) {
        this.headerView = view;
        notifyItemInserted(0);
    }

    public View getHeaderView() {
        return headerView;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        this.userClickListener = listener;
    }

    public Thread getItem(int position) {
        int offset = headerView != null ? position - 1 : position;
        if (offset >= 0 && offset < threadList.size()) return threadList.get(offset);
        return null;
    }

    public void removeItem(int position) {
        int dataPos = headerView != null ? position - 1 : position;
        if (dataPos >= 0 && dataPos < threadList.size()) {
            threadList.remove(dataPos);
            notifyItemRemoved(position);
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (headerView != null && position == 0) return VIEW_TYPE_HEADER;
        return super.getItemViewType(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            return new ViewHolder(headerView);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_thread, parent, false);
        // ThreadAdapter 被首页、版块、搜索、个人帖子等所有帖子列表复用，统一挂载磨砂玻璃背景（卡片容器）。
        View cardView = view.findViewById(R.id.thread_card);
        if (cardView != null) {
            cardView.setBackground(FrostedGlassDrawable.create(context, 14f));
        }
        return new ViewHolder(view);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(true, false);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(false, false);
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(false, false);
        }
        super.onViewRecycled(holder);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (headerView != null && position == 0) return;
        int dataPos = headerView != null ? position - 1 : position;
        Thread thread = threadList.get(dataPos);
        holder.tvTitle.setText(thread.getTitle());
        holder.tvAuthor.setText(thread.getAuthor());
        holder.tvTime.setText(thread.getPublishTime());
        String forumName = thread.getForumName();
        if (forumName != null && !forumName.isEmpty()) {
            holder.tvForum.setVisibility(View.VISIBLE);
            holder.tvForum.setText(forumName);
        } else {
            holder.tvForum.setVisibility(View.GONE);
        }

        // 摘要
        String summary = thread.getSummary();
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary.setVisibility(View.VISIBLE);
            holder.tvSummary.setText(summary);
        } else {
            holder.tvSummary.setVisibility(View.GONE);
        }

        // 等级
        String level = thread.getAuthorLevel();
        if (level != null && !level.isEmpty()) {
            holder.tvLevel.setVisibility(View.VISIBLE);
            holder.tvLevel.setText(level);
        } else {
            holder.tvLevel.setVisibility(View.GONE);
        }

        // 统计信息
        holder.tvViews.setText(formatCount(thread.getViews()));
        holder.tvReplies.setText(formatCount(thread.getReplies()));
        holder.tvLikes.setText(formatCount(thread.getLikes()));

    // 关注状态统一从服务端关注列表恢复；网络确认失败时才使用已有本地状态。
         if (!TextUtils.isEmpty(thread.getAuthorUid())) {
             java.util.Set<String> serverSet = serverFollowingUids;
             if (serverSet != null) {
                 thread.setFollowed(serverSet.contains(thread.getAuthorUid()));
             } else {
                 thread.setFollowed(FollowStateManager.resolve(context, thread.getAuthorUid(), thread.isFollowed()));
                 loadServerFollowingIfNeeded();
             }
         }
         holder.btnFollow.setText(thread.isFollowed() ? "已关注" : "关注");
        // AI 一键总结:拉帖+评论区,有隐藏先固定模板回复解锁再总结
        holder.btnAiSummarize.setOnClickListener(v -> {
            Intent it = new Intent(context, com.solosu.mtforum.ai.AiSummarizeActivity.class);
            it.putExtra("tid", thread.getTid());
            it.putExtra("title", thread.getTitle());
            if (!(context instanceof android.app.Activity)) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(it);
        });
        holder.btnFollow.setOnClickListener(v -> {
            if (!FollowStateManager.isLoggedIn(context)) {
                if (context instanceof android.app.Activity) {
                    com.solosu.mtforum.ui.login.LoginBottomSheet.show(
                            (android.app.Activity) context, null);
                } else {
                    Toast.makeText(context, "请先登录后再关注", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            String uid = thread.getAuthorUid();
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(context, "无法获取用户ID", Toast.LENGTH_SHORT).show();
                return;
            }
            final boolean targetState = !thread.isFollowed();
            holder.btnFollow.setEnabled(false);
        new java.lang.Thread(() -> {
            boolean success = FollowStateManager.syncFollow(context, uid, targetState);
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                holder.btnFollow.setEnabled(true);
                if (success) {
                    thread.setFollowed(targetState);
                    holder.btnFollow.setText(targetState ? "已关注" : "关注");
                    Toast.makeText(context, targetState
                            ? R.string.action_follow_success
                            : R.string.action_unfollow_success, Toast.LENGTH_SHORT).show();
                    java.util.Set<String> current = serverFollowingUids;
                    if (current != null) {
                        java.util.Set<String> updated = new java.util.HashSet<>(current);
                        if (targetState) updated.add(uid); else updated.remove(uid);
                        serverFollowingUids = updated;
                    }
                } else {
                    Toast.makeText(context, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
        });

        // 置顶标记
        if (thread.isSticky()) {
            holder.tvSticky.setVisibility(View.VISIBLE);
        } else {
            holder.tvSticky.setVisibility(View.GONE);
        }

        // 头像加载
        String avatarUrl = thread.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(context)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(holder.ivAvatar);
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_account);
        }
        // 帖子封面图：所有页面统一使用最多四张图片的2列网格样式；没有图片列表时回退到单图封面
        holder.ivThumbnail.setVisibility(View.GONE);
        holder.llThreadImages.setVisibility(View.GONE);
        holder.llThreadImages.removeAllViews();
        Glide.with(context).clear(holder.ivThumbnail);

        List<String> imageUrls = thread.getImageUrls();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            int count = Math.min(4, imageUrls.size());
            holder.llThreadImages.setVisibility(View.VISIBLE);
            for (int i = 0; i < count; i++) {
                ImageView imageView = new ImageView(context);
                int gap = dp(3);
                int itemHeight = dp(104);
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = 0;
                params.height = itemHeight;
                params.columnSpec = GridLayout.spec(i % 2, 1f);
                params.rowSpec = GridLayout.spec(i / 2);
                params.setMargins(i % 2 == 0 ? 0 : gap, i / 2 == 0 ? 0 : gap,
                        i % 2 == 1 ? 0 : gap, i / 2 == 1 ? 0 : gap);
                imageView.setLayoutParams(params);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setBackgroundResource(R.drawable.thread_image_bg);
                imageView.setClipToOutline(true);
                Glide.with(context)
                        .load(imageUrls.get(i))
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(imageView);
                holder.llThreadImages.addView(imageView);
            }
        } else {
            String thumbnailUrl = thread.getThumbnailUrl();
            if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                holder.ivThumbnail.setVisibility(View.VISIBLE);
                Glide.with(context)
                        .load(thumbnailUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(holder.ivThumbnail);
            }
        }


        holder.ivAvatar.setOnClickListener(v -> {
            if (userClickListener != null && !TextUtils.isEmpty(thread.getAuthorUid())) {
                userClickListener.onUserClick(thread);
            }
        });
        holder.tvAuthor.setOnClickListener(v -> {
            if (userClickListener != null && !TextUtils.isEmpty(thread.getAuthorUid())) {
                userClickListener.onUserClick(thread);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(thread, position);
            }
        });

        // 长按帖子卡片 = 拉黑作者(个人小黑屋)
        holder.itemView.setOnLongClickListener(v -> {
            final String uid = thread.getAuthorUid();
            final String name = thread.getAuthor();
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(context, "无法拉黑：缺少作者 UID", Toast.LENGTH_SHORT).show();
                return true;
            }
            new android.app.AlertDialog.Builder(context)
                    .setTitle("拉黑作者")
                    .setMessage("将「" + name + "」加入个人小黑屋？\n其发布的帖子和回帖都会隐藏。")
                    .setPositiveButton("拉黑", (d, w) -> {
                        com.solosu.mtforum.session.BlacklistManager.addLocal(context, uid, name);
                        removeThreadsByUid(uid);
                        Toast.makeText(context, "已拉黑「" + name + "」，其帖子已隐藏", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
    }

    /** 拉黑后即时从数据集移除该作者全部帖子(不需要刷新页面) */
    public void removeThreadsByUid(String uid) {
        if (uid == null || uid.isEmpty()) return;
        List<Thread> keep = new ArrayList<>();
        for (Thread t : threadList) {
            if (t.getAuthorUid() == null || !uid.equals(t.getAuthorUid())) keep.add(t);
        }
        if (keep.size() != threadList.size()) {
            threadList.clear();
            threadList.addAll(keep);
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return threadList.size() + (headerView != null ? 1 : 0);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View cardView;
        ImageView ivAvatar;
        ImageView ivThumbnail;
        GridLayout llThreadImages;
        TextView btnFollow;
        TextView tvTitle, tvAuthor, tvLevel, tvTime, tvForum, tvSummary;
        TextView tvViews, tvReplies, tvLikes, tvSticky;
        TextView btnAiSummarize;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.thread_card);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            llThreadImages = itemView.findViewById(R.id.ll_thread_images);
            btnFollow = itemView.findViewById(R.id.btn_thread_follow);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvLevel = itemView.findViewById(R.id.tv_level);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvForum = itemView.findViewById(R.id.tv_forum);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvViews = itemView.findViewById(R.id.tv_views);
            tvReplies = itemView.findViewById(R.id.tv_replies);
            tvLikes = itemView.findViewById(R.id.tv_likes);
            tvSticky = itemView.findViewById(R.id.tv_sticky);
            btnAiSummarize = itemView.findViewById(R.id.btn_ai_summary);
        }
    }

    private void openUserProfile(Thread thread) {
        if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
        if (userClickListener != null) {
            userClickListener.onUserClick(thread);
            return;
        }
        Intent intent = new Intent(context, UserProfileActivity.class);
        intent.putExtra("uid", thread.getAuthorUid());
        intent.putExtra("username", thread.getAuthor());
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private void loadServerFollowingIfNeeded() {
        if (serverFollowingUids != null) return;
        if (serverFollowingLoaded || followStateLoading
                || !FollowStateManager.isLoggedIn(context)) return;
        synchronized (followStateLock) {
            if (serverFollowingUids != null || serverFollowingLoaded || followStateLoading) return;
            followStateLoading = true;
        }
        new java.lang.Thread(() -> {
            java.util.Set<String> result = FollowStateManager.queryServerFollowingUids(context);
            serverFollowingUids = result;
            serverFollowingLoaded = true;
            followStateLoading = false;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(this::notifyDataSetChanged);
        }).start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private String formatCount(int count) {
        if (count >= 10000) {
            return String.format("%.1fw", count / 10000.0);
        } else if (count >= 1000) {
            return String.format("%.1fk", count / 1000.0);
        }
        return String.valueOf(count);
    }
}