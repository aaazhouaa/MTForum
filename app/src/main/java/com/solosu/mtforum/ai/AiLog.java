package com.solosu.mtforum.ai;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内存运行日志。
 * 自动回复 / 签到 / AI 调用的结果都往这里塞一份，侧边栏「运行日志」里查看。
 * 同时把日志追加写到外部文件，App 被杀后仍可回看，便于排查静默失败。
 */
public final class AiLog {

    private static final int MAX_ENTRIES = 300;
    private static final long MAX_FILE_BYTES = 512 * 1024L;
    private static final List<String> ENTRIES = new ArrayList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    /** 追加写日志的后台线程，避免在 UI 线程做 IO */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile File logFile;
    private static volatile File mediaFile;
    private static volatile Context appContext;

    private AiLog() {}

    /** 指定日志落盘位置（一般在 Application 启动时调用一次） */
    public static void attach(Context c) {
        try {
            appContext = c.getApplicationContext();
            File dir = c.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                logFile = new File(dir, "ai_run.log");
            }
            // 备用落点：/sdcard/Android/media/<pkg>/ （Android 11+ 免权限，adb/其它应用可读）
            // 默认关闭：该目录的日志连 AI 完整请求/响应体都包含，对其它应用可读属于隐私泄露面。
            // 应用内「运行日志」读的是上面的私有目录，不依赖此处镜像。需要取证时改 true。
            if (MEDIA_MIRROR_ENABLED) {
                try {
                    File media = new File(Environment.getExternalStorageDirectory(),
                            "Android/media/" + c.getPackageName());
                    if (!media.exists()) media.mkdirs();
                    if (media.exists()) mediaFile = new File(media, "ai_run.log");
                } catch (Throwable ignore) {
                    // 拿不到就不落这个点
                }
            }
            // 清掉上次可能残留的待发布镜像项，避免 MediaStore 里挂着一个不可见记录
            try { clearMirror(c.getContentResolver()); } catch (Throwable ignore) { }
        } catch (Throwable ignore) {
            // 拿不到外部目录也不影响内存日志
        }
    }

    /** 删除历史镜像项（含 IS_PENDING 残留），让下次写入干净重建 */
    private static void clearMirror(ContentResolver cr) {
        try {
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
            cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection,
                    new String[]{ MIRROR_NAME });
        } catch (Throwable ignore) {
        }
    }

    /** 落盘日志的绝对路径，没落盘时返回空串 */
    public static String filePath() {
        File f = logFile;
        return f == null ? "" : f.getAbsolutePath();
    }

    public static void i(String tag, String msg) {
        add(tag, msg);
    }

    public static void e(String tag, String msg) {
        add(tag, msg);
    }

    private static void add(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + (tag == null ? "-" : tag) + "] "
                + (msg == null ? "" : msg);
        synchronized (ENTRIES) {
            ENTRIES.add(line);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
        }
        final File f = logFile;
        final File mf = mediaFile;
        final Context ctx = appContext;
        try {
            IO.execute(() -> {
                if (f != null) writeLine(f, line);
                if (mf != null) writeLine(mf, line);
                if (ctx != null && MIRROR_ENABLED) writeToPublicDownload(ctx, line);
            });
        } catch (Throwable ignore) {
            // 线程池已关闭等极端情况，忽略即可
        }
    }

    /** 公共 Download 镜像是否开启。
     *  注意：部分机型上 MediaStore 写入会不断产生「xxx (1).txt」副本，故默认关闭；
     *  日志另有两个可靠落点：应用私有目录 + /sdcard/Android/media/包名/ 。 */
    private static final boolean MIRROR_ENABLED = false;

    /**
     * 是否把运行日志镜像到 /sdcard/Android/media/<pkg>/。
     * 默认关闭：该目录对其它应用可读，而日志含 AI 请求/响应全文。
     */
    private static final boolean MEDIA_MIRROR_ENABLED = false;
    /** 公共 Download 镜像文件名，任何文件管理器 / 电脑都能直接拷出来 */
    private static final String MIRROR_NAME = "mtforum_ai_run.log";
    private static final String MIRROR_DIR = "MtForumLog";

    /**
     * 把一行日志镜像到公共 Download/&lt;MIRROR_DIR&gt;/&lt;MIRROR_NAME&gt;。
     * Android 10+ 用 MediaStore 写公共媒体库不需要任何存储权限，
     * 这样即便 App 自己的 Android/data 目录读不到，也能把日志取出来。
     */
    private static void writeToPublicDownload(Context ctx, String line) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            long id = findMirrorId(cr, collection);
            Uri fileUri;
            if (id < 0) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, MIRROR_NAME);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + MIRROR_DIR);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                Uri uri = cr.insert(collection, cv);
                if (uri == null) return;
                fileUri = uri;
            } else {
                fileUri = ContentUris.withAppendedId(collection, id);
            }
            // 读旧内容 + 追加 + 整体重写。
            // （不用 "wa" / IS_PENDING：部分机型上待发布态会让文件在文件系统里不可见）
            String all = readAll(cr, fileUri) + line + "\n";
            // 只保留末尾若干行，避免镜像文件无限膨胀
            String[] lines = all.split("\n", -1);
            if (lines.length > 400) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 300; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
                all = sb.toString();
            }
            OutputStream os = null;
            try {
                os = cr.openOutputStream(fileUri, "wt");
            } catch (Throwable t) {
                // 个别机型不支持 "wt"，退到 "w"
            }
            if (os == null) {
                try {
                    os = cr.openOutputStream(fileUri, "w");
                } catch (Throwable t) {
                    return;
                }
            }
            if (os == null) return;
            os.write(all.getBytes("UTF-8"));
            os.flush();
            os.close();
        } catch (Throwable ignore) {
            // 镜像失败不影响主流程
        }
    }

    /** 读取 MediaStore 项的全部文本；失败返回空串 */
    private static String readAll(ContentResolver cr, Uri uri) {
        java.io.InputStream is = null;
        try {
            is = cr.openInputStream(uri);
            if (is == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (is != null) {
                try { is.close(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 在 MediaStore 里找已存在的镜像文件；找不到返回 -1 */
    private static long findMirrorId(ContentResolver cr, Uri collection) {
        Cursor c = null;
        try {
            String[] projection = { MediaStore.Downloads._ID };
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=?"
                    + " AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String path = Environment.DIRECTORY_DOWNLOADS + "/" + MIRROR_DIR + "%";
            c = cr.query(collection, projection, selection,
                    new String[]{ MIRROR_NAME, path }, null);
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignore) { } }
        }
        return -1;
    }

    private static void writeLine(File f, String line) {
        FileOutputStream fos = null;
        try {
            if (f.length() > MAX_FILE_BYTES) {
                // 超限就整体截断，保留后面的新日志
                if (!f.delete()) {
                    // 删不掉就放弃本次落盘，避免文件无限膨胀
                    return;
                }
            }
            fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignore) {
            // 落盘失败不影响主流程
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 返回倒序拼接的完整日志文本（最新在最上面） */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) return "暂无日志";
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                sb.append(ENTRIES.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    public static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    public static boolean isEmpty() {
        synchronized (ENTRIES) {
            return ENTRIES.isEmpty();
        }
    }

    /** 便捷：截断长文本 */
    public static String clip(String s, int max) {
        if (TextUtils.isEmpty(s)) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}