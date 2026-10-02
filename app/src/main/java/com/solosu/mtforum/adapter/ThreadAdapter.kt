package com.solosu.mtforum.adapter

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.solosu.mtforum.R
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.session.FollowStateManager
import com.solosu.mtforum.ui.space.UserProfileActivity
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import java.util.ArrayList
import java.util.List

/**
 * 帖子列表 RecyclerView Adapter
 */
class ThreadAdapter(private val context: Context) : RecyclerView.Adapter<ThreadAdapter.ViewHolder>() {

    private var threadList: MutableList<Thread> = ArrayList()
    private var headerView: View? = null
    private val VIEW_TYPE_HEADER = -1
    private var listener: OnItemClickListener? = null
    private var userClickListener: OnUserClickListener? = null

    @Volatile
    private var serverFollowingUids: MutableSet<String>? = null

    @Volatile
    private var serverFollowingLoaded: Boolean = false
    private var followStateLoading: Boolean = false
    private val followStateLock = Any()

    interface OnItemClickListener {
        fun onItemClick(thread: Thread?, position: Int)
    }

    interface OnUserClickListener {
        fun onUserClick(thread: Thread?)
    }

    private var lastAnimatedPosition = -1

    fun setThreadList(list: MutableList<Thread>?) {
        this.threadList = if (list != null) list else ArrayList()
        serverFollowingUids = null
        serverFollowingLoaded = false
        followStateLoading = false
        lastAnimatedPosition = -1
        notifyDataSetChanged()
    }

    /** 按 tid 定位刷新卡片(收藏数预取完成后回调) */
    fun notifyItemChangedByTid(tid: String?) {
        if (tid == null) return
        for (i in threadList.indices) {
            val t = threadList[i]
            if (t != null && tid == t.tid) {
                notifyItemChanged(i + (if (headerView != null) 1 else 0))
                return
            }
        }
    }

    fun addThreads(list: MutableList<Thread>?) {
        if (list != null) {
            val start = threadList.size
            threadList.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }
    }

    fun setHeaderView(view: View?) {
        this.headerView = view
        notifyItemInserted(0)
    }

    fun getHeaderView(): View? {
        return headerView
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setOnUserClickListener(listener: OnUserClickListener?) {
        this.userClickListener = listener
    }

    fun getItem(position: Int): Thread? {
        val offset = if (headerView != null) position - 1 else position
        if (offset >= 0 && offset < threadList.size) return threadList[offset]
        return null
    }

    fun removeItem(position: Int) {
        val dataPos = if (headerView != null) position - 1 else position
        if (dataPos >= 0 && dataPos < threadList.size) {
            threadList.removeAt(dataPos)
            notifyItemRemoved(position)
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (headerView != null && position == 0) return VIEW_TYPE_HEADER
        return super.getItemViewType(position)
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == VIEW_TYPE_HEADER) {
            return ViewHolder(headerView!!)
        }
        val view = LayoutInflater.from(context).inflate(R.layout.item_thread, parent, false)
        // ThreadAdapter 被首页、版块、搜索、个人帖子等所有帖子列表复用，统一挂载磨砂玻璃背景（卡片容器）。
        val cardView = view.findViewById<View>(R.id.thread_card)
        if (cardView != null) {
            cardView.setBackground(FrostedGlassDrawable.create(context, 14f))
        }
        return ViewHolder(view)
    }

    override fun onViewAttachedToWindow(@NonNull holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.cardView != null && holder.cardView!!.getBackground() is FrostedGlassDrawable) {
            holder.cardView!!.getBackground().setVisible(true, false)
        }
    }

    override fun onViewDetachedFromWindow(@NonNull holder: ViewHolder) {
        if (holder.cardView != null && holder.cardView!!.getBackground() is FrostedGlassDrawable) {
            holder.cardView!!.getBackground().setVisible(false, false)
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(@NonNull holder: ViewHolder) {
        if (holder.cardView != null && holder.cardView!!.getBackground() is FrostedGlassDrawable) {
            holder.cardView!!.getBackground().setVisible(false, false)
        }
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        if (headerView != null && position == 0) return
        val dataPos = if (headerView != null) position - 1 else position
        val thread = threadList[dataPos]
        holder.tvTitle!!.setText(thread.title)
        holder.tvAuthor!!.setText(thread.author)
        holder.tvTime!!.setText(thread.publishTime)
        val forumName = thread.forumName
        if (forumName != null && !forumName.isEmpty()) {
            holder.tvForum!!.setVisibility(View.VISIBLE)
            holder.tvForum!!.setText(forumName)
        } else {
            holder.tvForum!!.setVisibility(View.GONE)
        }

        // 摘要
        val summary = thread.summary
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary!!.setVisibility(View.VISIBLE)
            holder.tvSummary!!.setText(summary)
        } else {
            holder.tvSummary!!.setVisibility(View.GONE)
        }

        // 等级
        val level = thread.authorLevel
        if (level != null && !level.isEmpty()) {
            holder.tvLevel!!.setVisibility(View.VISIBLE)
            holder.tvLevel!!.setText(level)
        } else {
            holder.tvLevel!!.setVisibility(View.GONE)
        }

        // 统计信息
        holder.tvViews!!.setText(formatCount(thread.views))
        holder.tvReplies!!.setText(formatCount(thread.replies))
        holder.tvLikes!!.setText(formatCount(thread.likes))

    // 关注状态统一从服务端关注列表恢复；网络确认失败时才使用已有本地状态。
         if (!TextUtils.isEmpty(thread.authorUid)) {
             val serverSet = serverFollowingUids
             if (serverSet != null) {
                 thread.followed = serverSet.contains(thread.authorUid!!)
             } else {
                 thread.followed = FollowStateManager.resolve(context, thread.authorUid, thread.followed)
                 loadServerFollowingIfNeeded()
             }
         }
         holder.btnFollow!!.setText(if (thread.followed) "已关注" else "关注")
        // AI 一键总结:拉帖+评论区,有隐藏先固定模板回复解锁再总结
        holder.btnAiSummarize!!.setOnClickListener { v ->
            val it = Intent(context, com.solosu.mtforum.ai.AiSummarizeActivity::class.java)
            it.putExtra("tid", thread.tid)
            it.putExtra("title", thread.title)
            if (context !is android.app.Activity) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(it)
        }
        holder.btnFollow!!.setOnClickListener { v ->
            if (!FollowStateManager.isLoggedIn(context)) {
                if (context is android.app.Activity) {
                    com.solosu.mtforum.ui.login.LoginBottomSheet.show(
                            context, null)
                } else {
                    Toast.makeText(context, "请先登录后再关注", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val uid = thread.authorUid
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(context, "无法获取用户ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targetState = !thread.followed
            holder.btnFollow!!.setEnabled(false)
        java.lang.Thread({
            val success = FollowStateManager.syncFollow(context, uid, targetState)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.post {
                holder.btnFollow!!.setEnabled(true)
                if (success) {
                    thread.followed = targetState
                    holder.btnFollow!!.setText(if (targetState) "已关注" else "关注")
                    Toast.makeText(context, if (targetState)
                            R.string.action_follow_success
                            else R.string.action_unfollow_success, Toast.LENGTH_SHORT).show()
                    val current = serverFollowingUids
                    if (current != null) {
                        val updated = java.util.HashSet(current)
                        if (targetState) updated.add(uid) else updated.remove(uid)
                        serverFollowingUids = updated
                    }
                } else {
                    Toast.makeText(context, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }).start()
        }

        // 置顶标记
        if (thread.isSticky) {
            holder.tvSticky!!.setVisibility(View.VISIBLE)
        } else {
            holder.tvSticky!!.setVisibility(View.GONE)
        }

        // 头像加载
        val avatarUrl = thread.avatarUrl
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(context)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(holder.ivAvatar!!)
        } else {
            holder.ivAvatar!!.setImageResource(R.drawable.ic_account)
        }
        // 帖子封面图：所有页面统一使用最多四张图片的2列网格样式；没有图片列表时回退到单图封面
        holder.ivThumbnail!!.setVisibility(View.GONE)
        holder.llThreadImages!!.setVisibility(View.GONE)
        holder.llThreadImages!!.removeAllViews()
        Glide.with(context).clear(holder.ivThumbnail!!)

        val imageUrls = thread.imageUrls
        if (imageUrls != null && !imageUrls.isEmpty()) {
            val count = Math.min(4, imageUrls.size)
            holder.llThreadImages!!.setVisibility(View.VISIBLE)
            for (i in 0 until count) {
                val imageView = ImageView(context)
                val gap = dp(3)
                val itemHeight = dp(104)
                val params = GridLayout.LayoutParams()
                params.width = 0
                params.height = itemHeight
                params.columnSpec = GridLayout.spec(i % 2, 1f)
                params.rowSpec = GridLayout.spec(i / 2)
                params.setMargins(if (i % 2 == 0) 0 else gap, if (i / 2 == 0) 0 else gap,
                        if (i % 2 == 1) 0 else gap, if (i / 2 == 1) 0 else gap)
                imageView.setLayoutParams(params)
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
                imageView.setBackgroundResource(R.drawable.thread_image_bg)
                imageView.setClipToOutline(true)
                Glide.with(context)
                        .load(imageUrls[i])
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(imageView)
                holder.llThreadImages!!.addView(imageView)
            }
        } else {
            val thumbnailUrl = thread.thumbnailUrl
            if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                holder.ivThumbnail!!.setVisibility(View.VISIBLE)
                Glide.with(context)
                        .load(thumbnailUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(holder.ivThumbnail!!)
            }
        }


        holder.ivAvatar!!.setOnClickListener { v ->
            if (userClickListener != null && !TextUtils.isEmpty(thread.authorUid)) {
                userClickListener!!.onUserClick(thread)
            }
        }
        holder.tvAuthor!!.setOnClickListener { v ->
            if (userClickListener != null && !TextUtils.isEmpty(thread.authorUid)) {
                userClickListener!!.onUserClick(thread)
            }
        }

        holder.itemView.setOnClickListener { v ->
            v.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(80)
                .withEndAction {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(120)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.0f))
                        .start()
                    if (listener != null) {
                        listener!!.onItemClick(thread, position)
                    }
                }
                .start()
        }

        // 现代列表入场微动效：淡入 + 微上浮
        if (position > lastAnimatedPosition) {
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 24f * context.resources.displayMetrics.density
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
            lastAnimatedPosition = position
        }

        // 长按帖子卡片 = 拉黑作者(个人小黑屋)
        holder.itemView.setOnLongClickListener { v ->
            val uid = thread.authorUid
            val name = thread.author
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(context, "无法拉黑：缺少作者 UID", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            android.app.AlertDialog.Builder(context)
                    .setTitle("拉黑作者")
                    .setMessage("将「" + name + "」加入个人小黑屋？\n其发布的帖子和回帖都会隐藏。")
                    .setPositiveButton("拉黑") { d, w ->
                        com.solosu.mtforum.session.BlacklistManager.addLocal(context, uid, name)
                        removeThreadsByUid(uid)
                        Toast.makeText(context, "已拉黑「" + name + "」，其帖子已隐藏", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            true
        }
    }

    /** 拉黑后即时从数据集移除该作者全部帖子(不需要刷新页面) */
    fun removeThreadsByUid(uid: String?) {
        if (uid == null || uid.isEmpty()) return
        val keep = ArrayList<Thread>()
        for (t in threadList) {
            if (t.authorUid == null || uid != t.authorUid) keep.add(t)
        }
        if (keep.size != threadList.size) {
            threadList.clear()
            threadList.addAll(keep)
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return threadList.size + (if (headerView != null) 1 else 0)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var cardView: View?
        var ivAvatar: ImageView?
        var ivThumbnail: ImageView?
        var llThreadImages: GridLayout?
        var btnFollow: TextView?
        var tvTitle: TextView?
        var tvAuthor: TextView?
        var tvLevel: TextView?
        var tvTime: TextView?
        var tvForum: TextView?
        var tvSummary: TextView?
        var tvViews: TextView?
        var tvReplies: TextView?
        var tvLikes: TextView?
        var tvSticky: TextView?
        var btnAiSummarize: TextView?

        init {
            cardView = itemView.findViewById<View>(R.id.thread_card)
            ivAvatar = itemView.findViewById<ImageView>(R.id.iv_avatar)
            ivThumbnail = itemView.findViewById<ImageView>(R.id.iv_thumbnail)
            llThreadImages = itemView.findViewById<GridLayout>(R.id.ll_thread_images)
            btnFollow = itemView.findViewById<TextView>(R.id.btn_thread_follow)
            tvTitle = itemView.findViewById<TextView>(R.id.tv_title)
            tvAuthor = itemView.findViewById<TextView>(R.id.tv_author)
            tvLevel = itemView.findViewById<TextView>(R.id.tv_level)
            tvTime = itemView.findViewById<TextView>(R.id.tv_time)
            tvForum = itemView.findViewById<TextView>(R.id.tv_forum)
            tvSummary = itemView.findViewById<TextView>(R.id.tv_summary)
            tvViews = itemView.findViewById<TextView>(R.id.tv_views)
            tvReplies = itemView.findViewById<TextView>(R.id.tv_replies)
            tvLikes = itemView.findViewById<TextView>(R.id.tv_likes)
            tvSticky = itemView.findViewById<TextView>(R.id.tv_sticky)
            btnAiSummarize = itemView.findViewById<TextView>(R.id.btn_ai_summary)
        }
    }

    private fun openUserProfile(thread: Thread?) {
        if (thread == null || TextUtils.isEmpty(thread.authorUid)) return
        if (userClickListener != null) {
            userClickListener!!.onUserClick(thread)
            return
        }
        val intent = Intent(context, UserProfileActivity::class.java)
        intent.putExtra("uid", thread.authorUid)
        intent.putExtra("username", thread.author)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun loadServerFollowingIfNeeded() {
        if (serverFollowingUids != null) return
        if (serverFollowingLoaded || followStateLoading
                || !FollowStateManager.isLoggedIn(context)) return
        synchronized(followStateLock) {
            if (serverFollowingUids != null || serverFollowingLoaded || followStateLoading) return
            followStateLoading = true
        }
        java.lang.Thread({
            val result = FollowStateManager.queryServerFollowingUids(context)
            serverFollowingUids = result
            serverFollowingLoaded = true
            followStateLoading = false
            android.os.Handler(android.os.Looper.getMainLooper()).post(this::notifyDataSetChanged)
        }).start()
    }

    private fun dp(value: Int): Int {
        return Math.round(value * context.getResources().getDisplayMetrics().density)
    }

    private fun formatCount(count: Int): String {
        if (count >= 10000) {
            return String.format("%.1fw", count / 10000.0)
        } else if (count >= 1000) {
            return String.format("%.1fk", count / 1000.0)
        }
        return count.toString()
    }
}
