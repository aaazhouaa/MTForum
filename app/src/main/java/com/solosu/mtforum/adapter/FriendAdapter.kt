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
import com.solosu.mtforum.model.Friend
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import java.util.ArrayList
import java.util.List

class FriendAdapter(private val context: Context) : RecyclerView.Adapter<FriendAdapter.ViewHolder>() {

    private var friendList: MutableList<Friend> = ArrayList()
    private var listener: OnItemClickListener? = null

    interface OnItemClickListener {
        fun onItemClick(friend: Friend?, position: Int)
    }

    fun setFriendList(list: MutableList<Friend>?) {
        this.friendList = if (list != null) list else ArrayList()
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_friend, parent, false)
        val density = context.getResources().getDisplayMetrics().density
        view.setBackground(FrostedGlassDrawable.create(context, 8f))
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        val friend = friendList[position]
        holder.tvUsername.setText(friend.username)

        val level = friend.level
        if (level != null && !level.isEmpty()) {
            holder.tvLevel.setVisibility(View.VISIBLE)
            holder.tvLevel.setText(level)
        } else {
            holder.tvLevel.setVisibility(View.GONE)
        }

        val group = friend.groupName
        if (group != null && !group.isEmpty()) {
            holder.tvGroup.setVisibility(View.VISIBLE)
            holder.tvGroup.setText(group)
        } else {
            holder.tvGroup.setVisibility(View.GONE)
        }

        val avatarUrl = friend.avatarUrl
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
                listener!!.onItemClick(friend, position)
            }
        }
    }

    override fun getItemCount(): Int {
        return friendList.size
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
        val tvUsername: TextView
        val tvLevel: TextView
        val tvGroup: TextView

        init {
            ivAvatar = itemView.findViewById(R.id.iv_avatar)
            tvUsername = itemView.findViewById(R.id.tv_username)
            tvLevel = itemView.findViewById(R.id.tv_level)
            tvGroup = itemView.findViewById(R.id.tv_group)
        }
    }
}
