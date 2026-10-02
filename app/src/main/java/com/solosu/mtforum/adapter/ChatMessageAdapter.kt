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
import com.solosu.mtforum.model.ChatMessage
import java.util.ArrayList

class ChatMessageAdapter(private val context: Context) : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {
    private val items = ArrayList<ChatMessage>()

    fun setItems(list: List<ChatMessage>?) {
        items.clear()
        if (list != null) items.addAll(list)
        notifyDataSetChanged()
    }

    fun addItem(item: ChatMessage) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    @NonNull
    override fun onCreateViewHolder(p: ViewGroup, t: Int): Holder {
        return Holder(LayoutInflater.from(context).inflate(R.layout.item_chat_message, p, false))
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val m = items[position]
        val out = m.outgoing
        h.left.visibility = if (out) View.GONE else View.VISIBLE
        h.right.visibility = if (out) View.VISIBLE else View.GONE
        val text: TextView = if (out) h.rightText else h.leftText
        val date: TextView = if (out) h.rightDate else h.leftDate
        val time: TextView = if (out) h.rightTime else h.leftTime
        val avatar: ImageView = if (out) h.rightAvatar else h.leftAvatar
        text.text = m.content
        date.text = if (m.date == null) "" else m.date
        date.visibility = if (m.date == null || m.date!!.isEmpty()) View.GONE else View.VISIBLE
        time.text = if (m.time == null) "" else m.time
        if (m.avatarUrl != null && m.avatarUrl!!.isNotEmpty()) Glide.with(context).load(m.avatarUrl).circleCrop().into(avatar)
        else avatar.setImageResource(R.drawable.ic_account)
    }

    override fun getItemCount(): Int {
        return items.size
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val left: View = v.findViewById(R.id.chat_left)
        val right: View = v.findViewById(R.id.chat_right)
        val leftText: TextView = v.findViewById(R.id.tv_chat_left)
        val rightText: TextView = v.findViewById(R.id.tv_chat_right)
        val leftDate: TextView = v.findViewById(R.id.tv_chat_left_date)
        val rightDate: TextView = v.findViewById(R.id.tv_chat_right_date)
        val leftTime: TextView = v.findViewById(R.id.tv_chat_left_time)
        val rightTime: TextView = v.findViewById(R.id.tv_chat_right_time)
        val leftAvatar: ImageView = v.findViewById(R.id.iv_chat_left_avatar)
        val rightAvatar: ImageView = v.findViewById(R.id.iv_chat_right_avatar)
    }
}
