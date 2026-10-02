package com.solosu.mtforum.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 错误/崩溃日志收集器
 * 1. 全局未捕获异常自动写入 crash_*.log
 * 2. 任意位置可通过 CrashHandler.saveErrorLog(...) 记录错误,自动写入 error_*.log
 * 日志存放于外部存储私有目录,避免 Android 11+ 权限限制
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final Thread.UncaughtExceptionHandler sDefaultHandler =
            Thread.getDefaultUncaughtExceptionHandler();

    private static CrashHandler sInstance;
    private static String sLogDirPath;
    /** build79: 崩溃日志镜像目录(Android/media/<pkg>/), 免权限可被外部读取 */
    private static String sMediaDirPath;
    /**
     * 是否把崩溃/错误日志镜像到 /sdcard/Android/media/<pkg>/。
     * 默认关闭：该目录对其它应用可读。应用内「错误日志」读的是私有目录，不受影响。
     */
    private static final boolean CRASH_MEDIA_MIRROR = false;

    private CrashHandler() {}

    public static synchronized CrashHandler getInstance() {
        if (sInstance == null) {
            sInstance = new CrashHandler();
        }
        return sInstance;
    }

    public void init(Context context) {
        // 使用 getExternalFilesDir 避免 Android 11+ 对 Android/data 的路径访问限制
        File logDir = context.getExternalFilesDir("crash");
        if (logDir == null) {
            // 兜底:使用内部缓存目录
            logDir = new File(context.getCacheDir(), "crash");
        }
        sLogDirPath = logDir.getAbsolutePath();
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        // build79: 额外镜像到 Android/media/<pkg>/, adb/其它应用可直读。
        // 默认关闭：崩溃日志可能含 Cookie/接口返回片段，对其它应用可读属于泄露面。
        // 需要现场取证时改 CRASH_MEDIA_MIRROR 为 true。
        if (CRASH_MEDIA_MIRROR) {
            try {
                File media = new File(Environment.getExternalStorageDirectory(),
                        "Android/media/" + context.getPackageName());
                if (!media.exists()) media.mkdirs();
                if (media.exists()) sMediaDirPath = media.getAbsolutePath();
            } catch (Throwable ignore) {
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(this);
        Log.d(TAG, "CrashHandler initialized, log dir: " + sLogDirPath);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String crashInfo = collectCrashInfo(thread, throwable);
        saveCrashLog(crashInfo);
        Log.e(TAG, crashInfo);
        sDefaultHandler.uncaughtException(thread, throwable);
    }

    /**
     * 错误日志收集:应用内任意位置捕获到异常时调用,
     * 有错误就自动写入日志目录(error_yyyyMMdd_HHmmss.log)。
     *
     * @param tag       来源标签,如 "ThreadDetail"
     * @param message   错误描述(可为 null)
     * @param throwable 异常对象(可为 null,纯消息也可记录)
     */
    public static void saveErrorLog(String tag, String message, Throwable throwable) {
        try {
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            sb.append("=== 错误日志 ===\n");
            sb.append("时间: ").append(sdf.format(new Date())).append("\n");
            sb.append("来源: ").append(tag == null ? "unknown" : tag).append("\n");
            if (message != null && !message.isEmpty()) {
                sb.append("描述: ").append(message).append("\n");
            }
            if (throwable != null) {
                sb.append("\n--- 异常信息 ---\n");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                pw.flush();
                sb.append(sw);
            }
            sb.append("\n--- 设备信息 ---\n");
            sb.append("设备: ").append(android.os.Build.MODEL).append("\n");
            sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n");
            sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
            saveLogFile("error", sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "写入错误日志失败: " + e.getMessage());
        }
    }

    private String collectCrashInfo(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        sb.append("=== 崩溃日志 ===\n");
        sb.append("时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("线程: ").append(thread.getName()).append(" (id=").append(thread.getId()).append(")\n");
        sb.append("线程组: ").append(thread.getThreadGroup() != null ? thread.getThreadGroup().getName() : "null").append("\n");
        sb.append("\n--- 异常信息 ---\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        sb.append(sw);
        sb.append("\n--- 设备信息 ---\n");
        sb.append("设备: ").append(android.os.Build.MODEL).append("\n");
        sb.append("厂商: ").append(android.os.Build.MANUFACTURER).append("\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n");
        sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        return sb.toString();
    }

    private void saveCrashLog(String content) {
        saveLogFile("crash", content);
    }

    /** 按类型写文件:type 为 crash/error */
    private static void saveLogFile(String type, String content) {
        if (sLogDirPath == null) return;
        try {
            File dir = new File(sLogDirPath);
            if (!dir.exists()) dir.mkdirs();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String fileName = type + "_" + sdf.format(new Date()) + ".log";
            File file = new File(dir, fileName);
            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.close();
            Log.d(TAG, type + "日志已保存: " + file.getAbsolutePath());
            // build79: 镜像一份到 Android/media/<pkg>/<type>_latest.log
            if (sMediaDirPath != null) {
                try {
                    File mirror = new File(sMediaDirPath, type + "_latest.log");
                    FileWriter mw = new FileWriter(mirror);
                    mw.write(content);
                    mw.close();
                } catch (Throwable ignore) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "保存" + type + "日志失败: " + e.getMessage());
        }
    }

    /**
     * 获取日志目录路径
     */
    public static String getLogDirPath() {
        return sLogDirPath != null ? sLogDirPath : "";
    }

    /**
     * 获取所有崩溃日志文件列表
     */
    public static File[] getCrashLogFiles() {
        if (sLogDirPath == null) return new File[0];
        File dir = new File(sLogDirPath);
        if (!dir.exists()) return new File[0];
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        return files != null ? files : new File[0];
    }

    /**
     * 读取日志文件内容
     */
    public static String readLogFile(File file) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    /**
     * 清除所有日志
     */
    public static void clearAllLogs() {
        File[] files = getCrashLogFiles();
        for (File f : files) {
            f.delete();
        }
    }
}
