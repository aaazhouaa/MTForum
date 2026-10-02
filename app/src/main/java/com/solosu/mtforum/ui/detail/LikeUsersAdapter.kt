package com.solosu.mtforum.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide
import com.solosu.mtforum.R

import java.util.ArrayList

/**
 * 点赞人头像横排适配器
 * 数据源: PostDetail.likeUserUids/likeUserAvatars(登录态 ul.comiis_recommend_list_a)
 */
class LikeUsersAdapter(private val listener: OnUserClickListener?) : RecyclerView.Adapter<LikeUsersAdapter.VH>() {

    interface OnUserClickListener {
        fun onUserClick(uid: String?, name: String?)
    }

    private val uids: MutableList<String> = ArrayList()
    private val avatars: MutableList<String> = ArrayList()
    private val names: MutableList<String> = ArrayList()

    fun setData(uids: MutableList<String>?, avatars: MutableList<String>?, names: MutableList<String>?) {
        this.uids.clear()
        this.avatars.clear()
        this.names.clear()
        if (uids != null) this.uids.addAll(uids)
        if (avatars != null) this.avatars.addAll(avatars)
        if (names != null) this.names.addAll(names)
        notifyDataSetChanged()
    }

    fun getUidAt(pos: Int): String? {
        return if (pos >= 0 && pos < uids.size) uids[pos] else null
    }

    fun getNameAt(pos: Int): String? {
        return if (pos >= 0 && pos < names.size) names[pos] else null
    }

    fun getCount(): Int {
        return uids.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v: View = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_like_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val avatar: String? = if (position < avatars.size) avatars[position] else null
        if (avatar != null && !avatar.isEmpty()) {
            Glide.with(holder.itemView.context)
                    .load(avatar)
                    .circleCrop()
                    .placeholder(android.graphics.drawable.ColorDrawable(0xFFE0E0E0.toInt()))
                    .error(android.graphics.drawable.ColorDrawable(0xFFBDBDBD.toInt()))
                    .into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher)
        }
        holder.itemView.setOnClickListener { v ->
            if (listener != null) {
                listener.onUserClick(getUidAt(position), getNameAt(position))
            }
        }
    }

    override fun getItemCount(): Int {
        return uids.size
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAvatar: ImageView = itemView.findViewById(R.id.iv_like_user_avatar)
    }
}
