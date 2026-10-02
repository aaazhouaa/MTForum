package com.solosu.mtforum

import android.app.Application

import com.solosu.mtforum.ai.AiLog
import com.solosu.mtforum.ai.AutoReplyScheduler
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.util.CrashHandler

/**
 * 全局 Application 类
 * 在所有 Activity 启动前完成初始化,确保登录态 Cookie 从磁盘恢复
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 恢复持久化的 Cookie —— 在任何 Activity 启动前执行
        // 防止从最近任务直接恢复 SearchActivity 等非 MainActivity 时登录态丢失
        HttpClient.getInstance().init(this)

        // 运行日志落盘，App 被杀后仍可回看
        AiLog.attach(this)
        // 一次性迁移：v1.2 起关闭旧的「演练模式」默认值，避免自动回复一直只生成不发送
        com.solosu.mtforum.ai.AiConfigManager.migrateDefaults(this)
        // 启动标记：同时验证日志已开始镜像到公共 Download
        AiLog.i("app", "应用已启动，运行日志开始记录（含进帖自动解锁）")

        // 初始化全局崩溃日志收集
        CrashHandler.getInstance().init(this)

        // 启动自动回复调度（开关未打开时调度器会空转，不会发请求）
        AutoReplyScheduler.start(this)
    }
}
