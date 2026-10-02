package com.solosu.mtforum.ui.widget;

import android.app.Activity;

/**
 * build71: 滚动方向 -> 底部导航栏自动隐藏/出现。
 * 各页面(首页 RecyclerView / 版块 NestedScrollView / 我的 ScrollView)
 * 在滚动回调里把 dy 转发进来,统一由 MainActivity 做动画,
 * 避免每个页面各写一套。
 */
public final class NavBarAutoHideHelper {

    private NavBarAutoHideHelper() {
    }

    /**
     * @param dy 本次滚动增量,正值=手指上滑(内容往上走),负值=手指下滑
     */
    public static void onScrolled(Activity activity, int dy) {
        if (activity instanceof com.solosu.mtforum.MainActivity) {
            ((com.solosu.mtforum.MainActivity) activity).onNavScroll(dy);
        }
    }
}
