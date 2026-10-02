package com.solosu.mtforum.ui.message;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 消息占位 Fragment — 仅作为 Navigation 占位符
 * 实际消息入口在 MainActivity 中直接启动 NoticeActivity
 */
public class MessagePlaceholderFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 返回不可见的占位视图
        return new View(requireContext());
    }
}