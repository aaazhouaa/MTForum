package com.solosu.mtforum.ui.detail;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 点赞人头像横排适配器
 * 数据源: PostDetail.likeUserUids/likeUserAvatars(登录态 ul.comiis_recommend_list_a)
 */
public class LikeUsersAdapter extends RecyclerView.Adapter<LikeUsersAdapter.VH> {

    public interface OnUserClickListener {
        void onUserClick(String uid, String name);
    }

    private final List<String> uids = new ArrayList<>();
    private final List<String> avatars = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final OnUserClickListener listener;

    public LikeUsersAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<String> uids, List<String> avatars, List<String> names) {
        this.uids.clear();
        this.avatars.clear();
        this.names.clear();
        if (uids != null) this.uids.addAll(uids);
        if (avatars != null) this.avatars.addAll(avatars);
        if (names != null) this.names.addAll(names);
        notifyDataSetChanged();
    }

    public String getUidAt(int pos) {
        return pos >= 0 && pos < uids.size() ? uids.get(pos) : null;
    }

    public String getNameAt(int pos) {
        return pos >= 0 && pos < names.size() ? names.get(pos) : null;
    }

    public int getCount() {
        return uids.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_like_user, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String avatar = position < avatars.size() ? avatars.get(position) : null;
        if (avatar != null && !avatar.isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .load(avatar)
                    .circleCrop()
                    .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE0E0E0))
                    .error(new android.graphics.drawable.ColorDrawable(0xFFBDBDBD))
                    .into(holder.ivAvatar);
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onUserClick(getUidAt(position), getNameAt(position));
            }
        });
    }

    @Override
    public int getItemCount() {
        return uids.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivAvatar;
        VH(View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_like_user_avatar);
        }
    }
}
