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
import com.solosu.mtforum.model.Friend;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

public class FriendAdapter extends RecyclerView.Adapter<FriendAdapter.ViewHolder> {

    private List<Friend> friendList = new ArrayList<>();
    private Context context;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(Friend friend, int position);
    }

    public FriendAdapter(Context context) {
        this.context = context;
    }

    public void setFriendList(List<Friend> list) {
        this.friendList = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_friend, parent, false);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackground(FrostedGlassDrawable.create(context, 8f));
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Friend friend = friendList.get(position);
        holder.tvUsername.setText(friend.getUsername());

        String level = friend.getLevel();
        if (level != null && !level.isEmpty()) {
            holder.tvLevel.setVisibility(View.VISIBLE);
            holder.tvLevel.setText(level);
        } else {
            holder.tvLevel.setVisibility(View.GONE);
        }

        String group = friend.getGroupName();
        if (group != null && !group.isEmpty()) {
            holder.tvGroup.setVisibility(View.VISIBLE);
            holder.tvGroup.setText(group);
        } else {
            holder.tvGroup.setVisibility(View.GONE);
        }

        String avatarUrl = friend.getAvatarUrl();
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
                listener.onItemClick(friend, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return friendList.size();
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
        ImageView ivAvatar;
        TextView tvUsername, tvLevel, tvGroup;

        ViewHolder(View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            tvUsername = itemView.findViewById(R.id.tv_username);
            tvLevel = itemView.findViewById(R.id.tv_level);
            tvGroup = itemView.findViewById(R.id.tv_group);
        }
    }
}