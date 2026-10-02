package com.solosu.mtforum.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 版块网格适配器 — 2列网格展示所有子版块
 */
public class ForumGridAdapter extends RecyclerView.Adapter<ForumGridAdapter.ViewHolder> {

    private final List<ForumCategory.Forum> forumList = new ArrayList<>();
    private final Context context;
    private OnForumClickListener listener;

    public interface OnForumClickListener {
        void onForumClick(ForumCategory.Forum forum, int position);
    }

    public ForumGridAdapter(Context context) {
        this.context = context;
    }

    public List<ForumCategory.Forum> getForumList() {
        return forumList;
    }

    public void setForumList(List<ForumCategory.Forum> list) {
        forumList.clear();
        if (list != null) forumList.addAll(list);
        notifyDataSetChanged();
    }

    public void setOnForumClickListener(OnForumClickListener listener) {
        this.listener = listener;
    }

    /**
     * 局部更新单个版块数据(异步补齐描述/统计后刷新对应卡片)
     */
    public void updateForumAt(int position, ForumCategory.Forum forum) {
        if (forum == null || position < 0 || position >= forumList.size()) return;
        forumList.set(position, forum);
        notifyItemChanged(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_forum_grid, parent, false);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackground(FrostedGlassDrawable.create(context, 14f));
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ForumCategory.Forum forum = forumList.get(position);
        holder.tvName.setText(forum.getName());

        // 描述:按需求隐藏,不展示版块描述文本
        holder.tvDesc.setVisibility(View.GONE);

        // 按图片1的“热度 + 新帖”信息排布展示；总帖子数作为热度，今日帖子数作为新帖数。
        int heat = forum.getTotalPosts() > 0 ? forum.getTotalPosts() : forum.getTotalThreads();
        int newPosts = forum.getTodayPosts();
        holder.tvPosts.setText(formatForumCount(heat) + "热度  " + newPosts + "新帖");

        // 图标
        String iconUrl = forum.getIconUrl();
        if (iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(context)
                    .load(iconUrl)
                    .placeholder(R.drawable.ic_circle)
                    .circleCrop()
                    .into(holder.ivIcon);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_circle);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onForumClick(forum, position);
            }
        });
    }

    private String formatForumCount(int count) {
        if (count >= 10000) {
            return String.format(java.util.Locale.getDefault(), "%.2f万", count / 10000.0);
        }
        return String.valueOf(count);
    }

    @Override
    public int getItemCount() {
        return forumList.size();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.itemView.getBackground().setVisible(true, false);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewRecycled(holder);
    }



    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvName;
        final TextView tvDesc;
        final TextView tvPosts;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_forum_icon);
            tvName = itemView.findViewById(R.id.tv_forum_name);
            tvDesc = itemView.findViewById(R.id.tv_forum_desc);
            tvPosts = itemView.findViewById(R.id.tv_forum_posts);
        }
    }
}
