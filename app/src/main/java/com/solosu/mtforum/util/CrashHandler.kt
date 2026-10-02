package com.solosu.mtforum.util

import android.content.Context
import android.os.Environment
import android.util.Log

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误/崩溃日志收集器
 * 1. 全局未捕获异常自动写入 crash_*.log
 * 2. 任意位置可通过 CrashHandler.saveErrorLog(...) 记录错误,自动写入 error_*.log
 * 日志存放于外部存储私有目录,避免 Android 11+ 权限限制
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val crashInfo = collectCrashInfo(thread, throwable)
        saveCrashLog(crashInfo)
        Log.e(TAG, crashInfo)
        sDefaultHandler!!.uncaughtException(thread, throwable)
    }

    fun init(context: Context) {
        // 使用 getExternalFilesDir 避免 Android 11+ 对 Android/data 的路径访问限制
        var logDir = context.getExternalFilesDir("crash")
        if (logDir == null) {
            // 兜底:使用内部缓存目录
            logDir = File(context.cacheDir, "crash")
        }
        sLogDirPath = logDir.absolutePath
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        // build79: 额外镜像到 Android/media/<pkg>/, adb/其它应用可直读。
        // 默认关闭：崩溃日志可能含 Cookie/接口返回片段，对其它应用可读属于泄露面。
        // 需要现场取证时改 CRASH_MEDIA_MIRROR 为 true。
        if (CRASH_MEDIA_MIRROR) {
            try {
                val media = File(
                    Environment.getExternalStorageDirectory(),
                    "Android/media/" + context.packageName
                )
                if (!media.exists()) media.mkdirs()
                if (media.exists()) sMediaDirPath = media.absolutePath
            } catch (ignore: Throwable) {
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.d(TAG, "CrashHandler initialized, log dir: " + sLogDirPath)
    }

    private fun collectCrashInfo(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.append("=== 崩溃日志 ===\n")
        sb.append("时间: ").append(sdf.format(Date())).append("\n")
        sb.append("线程: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
        sb.append("线程组: ").append(if (thread.threadGroup != null) thread.threadGroup.name else "null").append("\n")
        sb.append("\n--- 异常信息 ---\n")
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        sb.append(sw)
        sb.append("\n--- 设备信息 ---\n")
        sb.append("设备: ").append(android.os.Build.MODEL).append("\n")
        sb.append("厂商: ").append(android.os.Build.MANUFACTURER).append("\n")
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n")
        sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n")
        return sb.toString()
    }

    private fun saveCrashLog(content: String) {
        saveLogFile("crash", content)
    }

    companion object {

        private const val TAG = "CrashHandler"
        private val sDefaultHandler: Thread.UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()

        private var sInstance: CrashHandler? = null
        private var sLogDirPath: String? = null
        /** build79: 崩溃日志镜像目录(Android/media/<pkg>/), 免权限可被外部读取 */
        private var sMediaDirPath: String? = null

        /**
         * 是否把崩溃/错误日志镜像到 /sdcard/Android/media/<pkg>/。
         * 默认关闭：该目录对其它应用可读。应用内「错误日志」读的是私有目录，不受影响。
         */
        private const val CRASH_MEDIA_MIRROR = false

        @JvmStatic
        fun getInstance(): CrashHandler {
            if (sInstance == null) {
                sInstance = CrashHandler()
            }
            return sInstance!!
        }

        /**
         * 错误日志收集:应用内任意位置捕获到异常时调用,
         * 有错误就自动写入日志目录(error_yyyyMMdd_HHmmss.log)。
         *
         * @param tag       来源标签,如 "ThreadDetail"
         * @param message   错误描述(可为 null)
         * @param throwable 异常对象(可为 null,纯消息也可记录)
         */
        @JvmStatic
        fun saveErrorLog(tag: String?, message: String?, throwable: Throwable?) {
            try {
                val sb = StringBuilder()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sb.append("=== 错误日志 ===\n")
                sb.append("时间: ").append(sdf.format(Date())).append("\n")
                sb.append("来源: ").append(if (tag == null) "unknown" else tag).append("\n")
                if (message != null && !message.isEmpty()) {
                    sb.append("描述: ").append(message).append("\n")
                }
                if (throwable != null) {
                    sb.append("\n--- 异常信息 ---\n")
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    pw.flush()
                    sb.append(sw)
                }
                sb.append("\n--- 设备信息 ---\n")
                sb.append("设备: ").append(android.os.Build.MODEL).append("\n")
                sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n")
                sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n")
                saveLogFile("error", sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "写入错误日志失败: " + e.message)
            }
        }

        /** 按类型写文件:type 为 crash/error */
        private fun saveLogFile(type: String, content: String) {
            val logDirPath = sLogDirPath ?: return
            try {
                val dir = File(logDirPath)
                if (!dir.exists()) dir.mkdirs()
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val fileName = type + "_" + sdf.format(Date()) + ".log"
                val file = File(dir, fileName)
                val writer = FileWriter(file)
                writer.write(content)
                writer.close()
                Log.d(TAG, type + "日志已保存: " + file.absolutePath)
                // build79: 镜像一份到 Android/media/<pkg>/<type>_latest.log
                val mediaDirPath = sMediaDirPath
                if (mediaDirPath != null) {
                    try {
                        val mirror = File(mediaDirPath, type + "_latest.log")
                        val mw = FileWriter(mirror)
                        mw.write(content)
                        mw.close()
                    } catch (ignore: Throwable) {
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存" + type + "日志失败: " + e.message)
            }
        }

        /**
         * 获取日志目录路径
         */
        @JvmStatic
        fun getLogDirPath(): String {
            return if (sLogDirPath != null) sLogDirPath!! else ""
        }

        /**
         * 获取所有崩溃日志文件列表
         */
        @JvmStatic
        fun getCrashLogFiles(): Array<File> {
            val logDirPath = sLogDirPath ?: return arrayOf()
            val dir = File(logDirPath)
            if (!dir.exists()) return arrayOf()
            val files = dir.listFiles { _, name -> name.endsWith(".log") }
            return if (files != null) files else arrayOf()
        }

        /**
         * 读取日志文件内容
         */
        @JvmStatic
        fun readLogFile(file: File): String {
            try {
                val br = BufferedReader(FileReader(file))
                val sb = StringBuilder()
                var line = br.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = br.readLine()
                }
                br.close()
                return sb.toString()
            } catch (e: Exception) {
                return "读取失败: " + e.message
            }
        }

        /**
         * 清除所有日志
         */
        @JvmStatic
        fun clearAllLogs() {
            val files = getCrashLogFiles()
            for (f in files) {
                f.delete()
            }
        }
    }
}
