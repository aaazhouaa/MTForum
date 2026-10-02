package com.solosu.mtforum.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动回复调度器。
 *
 * 职责：按用户配置的间隔周期性触发 AutoReplyEngine，同时负责 自动签到 的串联。
 * 在 Application 启动时 start()，用户关掉开关后引擎会自行空转返回。
 */
public final class AutoReplyScheduler {

    private static final String TAG = "AutoReplyScheduler";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static Context appContext;

    private AutoReplyScheduler() {}

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            try {
                tickOnce();
            } catch (Exception e) {
                Log.w(TAG, "tick failed", e);
                AiLog.e("scheduler", "调度异常：" + e.getMessage());
            } finally {
                scheduleNext();
            }
        }
    };

    private static void tickOnce() {
        if (appContext == null) return;

        // 未登录 / 未配置模型 时不空转
        if (!AiConfigManager.isAutoReplyEnabled(appContext)) return;
        if (!AiConfigManager.isConfigured(appContext)) {
            AiLog.e("scheduler", "自动回复已开启，但 AI 配置不完整，跳过");
            return;
        }
        if (!com.solosu.mtforum.network.HttpClient.getInstance().isLoggedIn()) {
            com.solosu.mtforum.network.HttpClient.getInstance().syncFromCookieManager();
        }
        if (!com.solosu.mtforum.network.HttpClient.getInstance().isLoggedIn()) {
            AiLog.e("scheduler", "未登录，跳过本轮");
            return;
        }
        if (AutoReplyEngine.isRunning()) return;

        AutoReplyEngine.runOnce(appContext, (replied, skipped, detail) -> {
            if (replied > 0 || skipped > 0) {
                Log.i(TAG, "replied=" + replied + " skipped=" + skipped + " " + detail);
            }
        });
    }

    private static void scheduleNext() {
        if (appContext == null) return;
        int interval = AiConfigManager.getReplyInterval(appContext);
        if (interval < 30) interval = 30;
        HANDLER.removeCallbacks(TICK);
        HANDLER.postDelayed(TICK, interval * 1000L);
    }

    /** 启动调度循环。重复调用无副作用。 */
    public static void start(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (!STARTED.compareAndSet(false, true)) {
            // 已在跑，仅刷新下一轮时间
            scheduleNext();
            return;
        }
        AiLog.i("scheduler", "调度器已启动，间隔 " + AiConfigManager.getReplyInterval(appContext) + " 秒");
        // Application 启动阶段不要立刻打网络请求，延后一轮
        HANDLER.postDelayed(TICK, AiConfigManager.getReplyInterval(appContext) * 1000L);
    }

    public static void stop() {
        STARTED.set(false);
        HANDLER.removeCallbacks(TICK);
        AiLog.i("scheduler", "调度器已停止");
    }

    public static boolean isStarted() {
        return STARTED.get();
    }

    /** 开关或间隔变更后调用，让新配置立即生效 */
    public static void reschedule(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        if (STARTED.get()) scheduleNext();
    }
}