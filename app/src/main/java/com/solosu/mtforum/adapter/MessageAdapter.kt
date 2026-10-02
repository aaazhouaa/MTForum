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
import com.solosu.mtforum.model.Message
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import java.util.ArrayList
import java.util.List

/**
 * 消息/私信列表 RecyclerView Adapter
 * 支持删除和屏蔽按钮回调
 */
class MessageAdapter(private val context: Context) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private var messageList: MutableList<Message> = ArrayList()
    private var listener: OnItemClickListener? = null
    private var actionListener: OnActionClickListener? = null

    interface OnItemClickListener {
        fun onItemClick(message: Message?, position: Int)
    }

    /** 删除/屏蔽按钮回调 */
    interface OnActionClickListener {
        fun onDelete(message: Message?, position: Int)
        fun onBlock(message: Message?, position: Int)
    }

    fun setMessageList(list: MutableList<Message>?) {
        this.messageList = if (list != null) list else ArrayList()
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setOnActionClickListener(listener: OnActionClickListener?) {
        this.actionListener = listener
    }

    fun removeItem(position: Int) {
        if (position >= 0 && position < messageList.size) {
            messageList.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)
        val density = context.getResources().getDisplayMetrics().density
        view.setBackground(FrostedGlassDrawable.create(context, 8f))
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        val msg = messageList[position]
        holder.tvAuthor.setText(msg.author)
        holder.tvTitle.setText(msg.title)
        holder.tvTime.setText(msg.time)

        val summary = msg.summary
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary.setVisibility(View.VISIBLE)
            holder.tvSummary.setText(summary)
        } else {
            holder.tvSummary.setVisibility(View.GONE)
        }

        // 已读/未读状态
        if (msg.isRead) {
            holder.tvUnread.setVisibility(View.GONE)
            holder.tvTitle.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary))
        } else {
            holder.tvUnread.setVisibility(View.VISIBLE)
            holder.tvTitle.setTextColor(holder.itemView.getContext().getColor(R.color.text_primary))
        }

        // 消息类型
        if (msg.type == 1) {
            holder.tvType.setVisibility(View.VISIBLE)
            holder.tvType.setText("系统")
        } else if (msg.type == 2) {
            holder.tvType.setVisibility(View.VISIBLE)
            holder.tvType.setText("回复")
        } else {
            holder.tvType.setVisibility(View.GONE)
        }

        // 显示删除/屏蔽按钮（仅通知类有pmid时显示）
        val pmid = msg.pmid
        val hasNoticeId = pmid != null && !pmid.isEmpty()
        val authorUid = msg.authorUid
        val hasUid = authorUid != null && !authorUid.isEmpty()
        holder.ivDelete.setVisibility(if (hasNoticeId) View.VISIBLE else View.GONE)
        holder.ivBlock.setVisibility(if (hasUid) View.VISIBLE else View.GONE)

        // 头像
        val avatarUrl = msg.avatarUrl
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(context)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_account)
        }

        holder.itemView.setOnClickListener { v ->
            if (listener != null) {
                listener!!.onItemClick(msg, position)
            }
        }

        // 删除按钮点击
        holder.ivDelete.setOnClickListener { v ->
            if (actionListener != null) {
                actionListener!!.onDelete(msg, position)
            }
        }

        // 屏蔽按钮点击
        holder.ivBlock.setOnClickListener { v ->
            if (actionListener != null) {
                actionListener!!.onBlock(msg, position)
            }
        }
    }

    override fun getItemCount(): Int {
        return messageList.size
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
        val ivAvatar: ImageView
        val ivDelete: ImageView
        val ivBlock: ImageView
        val tvAuthor: TextView
        val tvTitle: TextView
        val tvSummary: TextView
        val tvTime: TextView
        val tvUnread: TextView
        val tvType: TextView

        init {
            ivAvatar = itemView.findViewById(R.id.iv_avatar)
            tvAuthor = itemView.findViewById(R.id.tv_author)
            tvTitle = itemView.findViewById(R.id.tv_title)
            tvSummary = itemView.findViewById(R.id.tv_summary)
            tvTime = itemView.findViewById(R.id.tv_time)
            tvUnread = itemView.findViewById(R.id.tv_unread)
            tvType = itemView.findViewById(R.id.tv_type)
            ivDelete = itemView.findViewById(R.id.iv_delete)
            ivBlock = itemView.findViewById(R.id.iv_block)
        }
    }
}
