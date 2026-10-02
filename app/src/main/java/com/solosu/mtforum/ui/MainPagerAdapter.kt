package com.solosu.mtforum.ui

import androidx.annotation.NonNull
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

import com.solosu.mtforum.ui.community.CommunityFragment
import com.solosu.mtforum.ui.home.HomeFragment
import com.solosu.mtforum.ui.message.NoticeFragment
import com.solosu.mtforum.ui.profile.ProfileFragment

/**
 * 主界面 ViewPager2 适配器:
 * 0-首页  1-版块  2-消息  3-我的
 * 与底部悬浮导航栏联动,支持左右滑动快速切换。
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    @NonNull
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            PAGE_COMMUNITY -> CommunityFragment()
            PAGE_MESSAGE -> NoticeFragment()
            PAGE_PROFILE -> ProfileFragment()
            PAGE_HOME -> HomeFragment()
            else -> HomeFragment()
        }
    }

    override fun getItemCount(): Int {
        return PAGE_COUNT
    }

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_COMMUNITY = 1
        const val PAGE_MESSAGE = 2
        const val PAGE_PROFILE = 3
        const val PAGE_COUNT = 4
    }
}
