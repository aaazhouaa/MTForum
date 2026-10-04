package com.solosu.mtforum

import android.app.Application

import com.solosu.mtforum.util.AiLog
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.util.CrashHandler

/**
 * 全局 Application 类
 * 在所有 Activity 启动前完成初始化,确保登录态 Cookie 从磁盘恢复
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化主题与夜间模式设置
        com.solosu.mtforum.util.ThemeManager.init(this)

        // 自动管理当前前台 Activity（用于展示零 Logo 的纯净现代 Toast）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                com.solosu.mtforum.util.ToastUtil.setTopActivity(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.solosu.mtforum.util.ToastUtil.setTopActivity(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                com.solosu.mtforum.util.ToastUtil.setTopActivity(activity)
            }
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                com.solosu.mtforum.util.ToastUtil.clearIfCurrent(activity)
            }
        })

        // 恢复持久化的 Cookie —— 在任何 Activity 启动前执行
        // 防止从最近任务直接恢复 SearchActivity 等非 MainActivity 时登录态丢失
        HttpClient.getInstance().init(this)

        // 运行日志落盘，App 被杀后仍可回看
        AiLog.attach(this)
        // 启动标记：记录本次启动
        AiLog.i("app", "应用已启动，运行日志开始记录")

        // 初始化全局崩溃日志收集
        CrashHandler.getInstance().init(this)
    }
}
