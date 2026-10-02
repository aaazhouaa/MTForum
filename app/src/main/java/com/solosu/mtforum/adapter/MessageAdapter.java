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
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息/私信列表 RecyclerView Adapter
 * 支持删除和屏蔽按钮回调
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    private List<Message> messageList = new ArrayList<>();
    private Context context;
    private OnItemClickListener listener;
    private OnActionClickListener actionListener;

    public interface OnItemClickListener {
        void onItemClick(Message message, int position);
    }

    /** 删除/屏蔽按钮回调 */
    public interface OnActionClickListener {
        void onDelete(Message message, int position);
        void onBlock(Message message, int position);
    }

    public MessageAdapter(Context context) {
        this.context = context;
    }

    public void setMessageList(List<Message> list) {
        this.messageList = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.actionListener = listener;
    }

    public void removeItem(int position) {
        if (position >= 0 && position < messageList.size()) {
            messageList.remove(position);
            notifyItemRemoved(position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackground(FrostedGlassDrawable.create(context, 8f));
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message msg = messageList.get(position);
        holder.tvAuthor.setText(msg.getAuthor());
        holder.tvTitle.setText(msg.getTitle());
        holder.tvTime.setText(msg.getTime());

        String summary = msg.getSummary();
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary.setVisibility(View.VISIBLE);
            holder.tvSummary.setText(summary);
        } else {
            holder.tvSummary.setVisibility(View.GONE);
        }

        // 已读/未读状态
        if (msg.isRead()) {
            holder.tvUnread.setVisibility(View.GONE);
            holder.tvTitle.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary));
        } else {
            holder.tvUnread.setVisibility(View.VISIBLE);
            holder.tvTitle.setTextColor(holder.itemView.getContext().getColor(R.color.text_primary));
        }

        // 消息类型
        if (msg.getType() == 1) {
            holder.tvType.setVisibility(View.VISIBLE);
            holder.tvType.setText("系统");
        } else if (msg.getType() == 2) {
            holder.tvType.setVisibility(View.VISIBLE);
            holder.tvType.setText("回复");
        } else {
            holder.tvType.setVisibility(View.GONE);
        }

        // 显示删除/屏蔽按钮（仅通知类有pmid时显示）
        boolean hasNoticeId = msg.getPmid() != null && !msg.getPmid().isEmpty();
        boolean hasUid = msg.getAuthorUid() != null && !msg.getAuthorUid().isEmpty();
        holder.ivDelete.setVisibility(hasNoticeId ? View.VISIBLE : View.GONE);
        holder.ivBlock.setVisibility(hasUid ? View.VISIBLE : View.GONE);

        // 头像
        String avatarUrl = msg.getAvatarUrl();
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

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(msg, position);
            }
        });

        // 删除按钮点击
        holder.ivDelete.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onDelete(msg, position);
            }
        });

        // 屏蔽按钮点击
        holder.ivBlock.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onBlock(msg, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return messageList.size();
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
        ImageView ivAvatar, ivDelete, ivBlock;
        TextView tvAuthor, tvTitle, tvSummary, tvTime, tvUnread, tvType;

        ViewHolder(View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvUnread = itemView.findViewById(R.id.tv_unread);
            tvType = itemView.findViewById(R.id.tv_type);
            ivDelete = itemView.findViewById(R.id.iv_delete);
            ivBlock = itemView.findViewById(R.id.iv_block);
        }
    }
}