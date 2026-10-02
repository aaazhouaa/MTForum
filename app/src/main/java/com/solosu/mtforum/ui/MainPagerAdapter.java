package com.solosu.mtforum.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.solosu.mtforum.ui.community.CommunityFragment;
import com.solosu.mtforum.ui.home.HomeFragment;
import com.solosu.mtforum.ui.message.NoticeFragment;
import com.solosu.mtforum.ui.profile.ProfileFragment;

/**
 * 主界面 ViewPager2 适配器:
 * 0-首页  1-版块  2-消息  3-我的
 * 与底部悬浮导航栏联动,支持左右滑动快速切换。
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_COMMUNITY = 1;
    public static final int PAGE_MESSAGE = 2;
    public static final int PAGE_PROFILE = 3;
    public static final int PAGE_COUNT = 4;

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_COMMUNITY:
                return new CommunityFragment();
            case PAGE_MESSAGE:
                return new NoticeFragment();
            case PAGE_PROFILE:
                return new ProfileFragment();
            case PAGE_HOME:
            default:
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
