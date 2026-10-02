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
import com.solosu.mtforum.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.Holder> {
    private final Context context;
    private final List<ChatMessage> items = new ArrayList<>();
    public ChatMessageAdapter(Context context) { this.context = context; }
    public void setItems(List<ChatMessage> list) { items.clear(); if (list != null) items.addAll(list); notifyDataSetChanged(); }
    public void addItem(ChatMessage item) { items.add(item); notifyItemInserted(items.size() - 1); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_chat_message, p, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = items.get(position);
        boolean out = m.isOutgoing();
        h.left.setVisibility(out ? View.GONE : View.VISIBLE);
        h.right.setVisibility(out ? View.VISIBLE : View.GONE);
        TextView text = out ? h.rightText : h.leftText;
        TextView date = out ? h.rightDate : h.leftDate;
        TextView time = out ? h.rightTime : h.leftTime;
        ImageView avatar = out ? h.rightAvatar : h.leftAvatar;
        text.setText(m.getContent());
        date.setText(m.getDate() == null ? "" : m.getDate());
        date.setVisibility(m.getDate() == null || m.getDate().isEmpty() ? View.GONE : View.VISIBLE);
        time.setText(m.getTime() == null ? "" : m.getTime());
        if (m.getAvatarUrl() != null && !m.getAvatarUrl().isEmpty()) Glide.with(context).load(m.getAvatarUrl()).circleCrop().into(avatar);
        else avatar.setImageResource(R.drawable.ic_account);
    }
    @Override public int getItemCount() { return items.size(); }
    static class Holder extends RecyclerView.ViewHolder {
        View left, right; TextView leftText, rightText, leftDate, rightDate, leftTime, rightTime; ImageView leftAvatar, rightAvatar;
        Holder(View v) { super(v); left=v.findViewById(R.id.chat_left); right=v.findViewById(R.id.chat_right); leftText=v.findViewById(R.id.tv_chat_left); rightText=v.findViewById(R.id.tv_chat_right); leftDate=v.findViewById(R.id.tv_chat_left_date); rightDate=v.findViewById(R.id.tv_chat_right_date); leftTime=v.findViewById(R.id.tv_chat_left_time); rightTime=v.findViewById(R.id.tv_chat_right_time); leftAvatar=v.findViewById(R.id.iv_chat_left_avatar); rightAvatar=v.findViewById(R.id.iv_chat_right_avatar); }
    }
}