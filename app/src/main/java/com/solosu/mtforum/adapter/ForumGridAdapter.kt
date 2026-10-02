package com.solosu.mtforum.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.solosu.mtforum.R
import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import java.util.ArrayList
import java.util.List

/**
 * 版块网格适配器 — 2列网格展示所有子版块
 */
class ForumGridAdapter(private val context: Context) : RecyclerView.Adapter<ForumGridAdapter.ViewHolder>() {

    private val forumList: MutableList<ForumCategory.Forum> = ArrayList()
    private var listener: OnForumClickListener? = null

    interface OnForumClickListener {
        fun onForumClick(forum: ForumCategory.Forum?, position: Int)
    }

    fun getForumList(): MutableList<ForumCategory.Forum> {
        return forumList
    }

    fun setForumList(list: List<ForumCategory.Forum>?) {
        forumList.clear()
        if (list != null) forumList.addAll(list)
        notifyDataSetChanged()
    }

    fun setOnForumClickListener(listener: OnForumClickListener?) {
        this.listener = listener
    }

    /**
     * 局部更新单个版块数据(异步补齐描述/统计后刷新对应卡片)
     */
    fun updateForumAt(position: Int, forum: ForumCategory.Forum?) {
        if (forum == null || position < 0 || position >= forumList.size) return
        forumList.set(position, forum)
        notifyItemChanged(position)
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_forum_grid, parent, false)
        val density = context.getResources().getDisplayMetrics().density
        view.setBackground(FrostedGlassDrawable.create(context, 14f))
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        val forum = forumList[position]
        holder.tvName.setText(forum.name)

        // 描述:按需求隐藏,不展示版块描述文本
        holder.tvDesc.setVisibility(View.GONE)

        // 按图片1的“热度 + 新帖”信息排布展示；总帖子数作为热度，今日帖子数作为新帖数。
        val heat = if (forum.totalPosts > 0) forum.totalPosts else forum.totalThreads
        val newPosts = forum.todayPosts
        holder.tvPosts.setText(formatForumCount(heat) + "热度  " + newPosts + "新帖")

        // 图标
        val iconUrl = forum.iconUrl
        if (iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(context)
                    .load(iconUrl)
                    .placeholder(R.drawable.ic_circle)
                    .circleCrop()
                    .into(holder.ivIcon)
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_circle)
        }

        // 点击事件
        holder.itemView.setOnClickListener { v ->
            if (listener != null) {
                listener!!.onForumClick(forum, position)
            }
        }
    }

    private fun formatForumCount(count: Int): String {
        if (count >= 10000) {
            return String.format(java.util.Locale.getDefault(), "%.2f万", count / 10000.0)
        }
        return count.toString()
    }

    override fun getItemCount(): Int {
        return forumList.size
    }

    override fun onViewAttachedToWindow(@NonNull holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.itemView.getBackground().setVisible(true, false)
    }

    override fun onViewDetachedFromWindow(@NonNull holder: ViewHolder) {
        holder.itemView.getBackground().setVisible(false, false)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(@NonNull holder: ViewHolder) {
        holder.itemView.getBackground().setVisible(false, false)
        super.onViewRecycled(holder)
    }



    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView
        val tvName: TextView
        val tvDesc: TextView
        val tvPosts: TextView

        init {
            ivIcon = itemView.findViewById(R.id.iv_forum_icon)
            tvName = itemView.findViewById(R.id.tv_forum_name)
            tvDesc = itemView.findViewById(R.id.tv_forum_desc)
            tvPosts = itemView.findViewById(R.id.tv_forum_posts)
        }
    }
}
