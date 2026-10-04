package com.solosu.mtforum.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.TextUtils

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.List
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 内存运行日志。
 * 网络请求（WAF / 限流）、签到等结果都往这里塞一份，侧边栏「运行日志」里查看。
 * 同时把日志追加写到外部文件，App 被杀后仍可回看，便于排查静默失败。
 */
object AiLog {

    private const val MAX_ENTRIES = 300
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val ENTRIES: MutableList<String> = ArrayList()
    private val FMT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 追加写日志的后台线程，避免在 UI 线程做 IO */
    private val IO: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var mediaFile: File? = null

    @Volatile
    private var appContext: Context? = null

    /** 指定日志落盘位置（一般在 Application 启动时调用一次） */
    @JvmStatic
    fun attach(c: Context) {
        try {
            appContext = c.applicationContext
            val dir = c.getExternalFilesDir(null)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                logFile = File(dir, "ai_run.log")
            }
            // 备用落点：/sdcard/Android/media/<pkg>/ （Android 11+ 免权限，adb/其它应用可读）
            // 默认关闭：该目录的日志连 AI 完整请求/响应体都包含，对其它应用可读属于隐私泄露面。
            // 应用内「运行日志」读的是上面的私有目录，不依赖此处镜像。需要取证时改 true。
            if (MEDIA_MIRROR_ENABLED) {
                try {
                    val media = File(
                        Environment.getExternalStorageDirectory(),
                        "Android/media/" + c.packageName
                    )
                    if (!media.exists()) media.mkdirs()
                    if (media.exists()) mediaFile = File(media, "ai_run.log")
                } catch (ignore: Throwable) {
                    // 拿不到就不落这个点
                }
            }
            // 清掉上次可能残留的待发布镜像项，避免 MediaStore 里挂着一个不可见记录
            try {
                clearMirror(c.contentResolver)
            } catch (ignore: Throwable) {
            }
        } catch (ignore: Throwable) {
            // 拿不到外部目录也不影响内存日志
        }
    }

    /** 删除历史镜像项（含 IS_PENDING 残留），让下次写入干净重建 */
    private fun clearMirror(cr: ContentResolver) {
        try {
            val selection = MediaStore.Downloads.DISPLAY_NAME + "=?"
            cr.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection,
                arrayOf(MIRROR_NAME)
            )
        } catch (ignore: Throwable) {
        }
    }

    /** 落盘日志的绝对路径，没落盘时返回空串 */
    @JvmStatic
    fun filePath(): String {
        val f = logFile
        return if (f == null) "" else f.absolutePath
    }

    @JvmStatic
    fun i(tag: String?, msg: String?) {
        add(tag, msg)
    }

    @JvmStatic
    fun e(tag: String?, msg: String?) {
        add(tag, msg)
    }

    private fun add(tag: String?, msg: String?) {
        val line = FMT.format(Date()) + " [" + (if (tag == null) "-" else tag) + "] " +
                (if (msg == null) "" else msg)
        synchronized(ENTRIES) {
            ENTRIES.add(line)
            while (ENTRIES.size > MAX_ENTRIES) ENTRIES.removeAt(0)
        }
        val f = logFile
        val mf = mediaFile
        val ctx = appContext
        try {
            IO.execute {
                if (f != null) writeLine(f, line)
                if (mf != null) writeLine(mf, line)
                if (ctx != null && MIRROR_ENABLED) writeToPublicDownload(ctx, line)
            }
        } catch (ignore: Throwable) {
            // 线程池已关闭等极端情况，忽略即可
        }
    }

    /** 公共 Download 镜像是否开启。
     *  注意：部分机型上 MediaStore 写入会不断产生「xxx (1).txt」副本，故默认关闭；
     *  日志另有两个可靠落点：应用私有目录 + /sdcard/Android/media/包名/ 。 */
    private const val MIRROR_ENABLED = false

    /**
     * 是否把运行日志镜像到 /sdcard/Android/media/<pkg>/。
     * 默认关闭：该目录对其它应用可读，而日志含请求/响应细节。
     */
    private const val MEDIA_MIRROR_ENABLED = false

    /** 公共 Download 镜像文件名，任何文件管理器 / 电脑都能直接拷出来 */
    private const val MIRROR_NAME = "mtforum_ai_run.log"
    private const val MIRROR_DIR = "MtForumLog"

    /**
     * 把一行日志镜像到公共 Download/&lt;MIRROR_DIR&gt;/&lt;MIRROR_NAME&gt;。
     * Android 10+ 用 MediaStore 写公共媒体库不需要任何存储权限，
     * 这样即便 App 自己的 Android/data 目录读不到，也能把日志取出来。
     */
    private fun writeToPublicDownload(ctx: Context, line: String) {
        try {
            val cr = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val id = findMirrorId(cr, collection)
            val fileUri: Uri
            if (id < 0) {
                val cv = ContentValues()
                cv.put(MediaStore.Downloads.DISPLAY_NAME, MIRROR_NAME)
                cv.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + MIRROR_DIR
                )
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                val uri = cr.insert(collection, cv)
                if (uri == null) return
                fileUri = uri
            } else {
                fileUri = ContentUris.withAppendedId(collection, id)
            }
            // 读旧内容 + 追加 + 整体重写。
            // （不用 "wa" / IS_PENDING：部分机型上待发布态会让文件在文件系统里不可见）
            var all = readAll(cr, fileUri) + line + "\n"
            // 只保留末尾若干行，避免镜像文件无限膨胀
            val lines = all.split("\n".toRegex(), 0).toTypedArray()
            if (lines.size > 400) {
                val sb = StringBuilder()
                for (i in lines.size - 300 until lines.size) {
                    sb.append(lines[i]).append('\n')
                }
                all = sb.toString()
            }
            var os: OutputStream? = null
            try {
                os = cr.openOutputStream(fileUri, "wt")
            } catch (t: Throwable) {
                // 个别机型不支持 "wt"，退到 "w"
            }
            if (os == null) {
                try {
                    os = cr.openOutputStream(fileUri, "w")
                } catch (t: Throwable) {
                    return
                }
            }
            val out = os ?: return
            out.write(all.toByteArray(charset("UTF-8")))
            out.flush()
            out.close()
        } catch (ignore: Throwable) {
            // 镜像失败不影响主流程
        }
    }

    /** 读取 MediaStore 项的全部文本；失败返回空串 */
    private fun readAll(cr: ContentResolver, uri: Uri): String {
        var `is`: java.io.InputStream? = null
        try {
            `is` = cr.openInputStream(uri)
            if (`is` == null) return ""
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            n = `is`.read(buf)
            while (n > 0) {
                bos.write(buf, 0, n)
                n = `is`.read(buf)
            }
            return String(bos.toByteArray(), charset("UTF-8"))
        } catch (t: Throwable) {
            return ""
        } finally {
            if (`is` != null) {
                try {
                    `is`.close()
                } catch (ignore: Throwable) {
                }
            }
        }
    }

    /** 在 MediaStore 里找已存在的镜像文件；找不到返回 -1 */
    private fun findMirrorId(cr: ContentResolver, collection: Uri): Long {
        var c: Cursor? = null
        try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = MediaStore.Downloads.DISPLAY_NAME + "=?" +
                    " AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?"
            val path = Environment.DIRECTORY_DOWNLOADS + "/" + MIRROR_DIR + "%"
            c = cr.query(
                collection, projection, selection,
                arrayOf(MIRROR_NAME, path), null
            )
            if (c != null && c.moveToFirst()) {
                return c.getLong(0)
            }
        } catch (ignore: Throwable) {
        } finally {
            if (c != null) {
                try {
                    c.close()
                } catch (ignore: Throwable) {
                }
            }
        }
        return -1
    }

    private fun writeLine(f: File, line: String) {
        var fos: FileOutputStream? = null
        try {
            if (f.length() > MAX_FILE_BYTES) {
                // 超限就整体截断，保留后面的新日志
                if (!f.delete()) {
                    // 删不掉就放弃本次落盘，避免文件无限膨胀
                    return
                }
            }
            fos = FileOutputStream(f, true)
            fos.write((line + "\n").toByteArray(charset("UTF-8")))
            fos.flush()
        } catch (ignore: Throwable) {
            // 落盘失败不影响主流程
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (ignore: Throwable) {
                }
            }
        }
    }

    /** 返回倒序拼接的完整日志文本（最新在最上面） */
    @JvmStatic
    fun dump(): String {
        val sb = StringBuilder()
        synchronized(ENTRIES) {
            if (ENTRIES.isEmpty()) return "暂无日志"
            for (i in ENTRIES.size - 1 downTo 0) {
                sb.append(ENTRIES[i]).append('\n')
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun size(): Int {
        synchronized(ENTRIES) {
            return ENTRIES.size
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(ENTRIES) {
            ENTRIES.clear()
        }
    }

    @JvmStatic
    fun isEmpty(): Boolean {
        synchronized(ENTRIES) {
            return ENTRIES.isEmpty()
        }
    }

    /** 便捷：截断长文本 */
    @JvmStatic
    fun clip(s: String?, max: Int): String {
        if (TextUtils.isEmpty(s)) return ""
        val t = s!!.replace("\\s+".toRegex(), " ").trim()
        return if (t.length > max) t.substring(0, max) + "…" else t
    }
}
