package com.solosu.mtforum.ai

import android.content.Context
import android.text.TextUtils

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * AI 会话存储（Codex 风格）。
 *
 * 每个会话一个 JSON 文件，目录结构：
 *   <externalFilesDir>/ai_sessions/
 *     ├── index.json           —— 会话索引（id、标题、创建时间、最后活跃时间、消息数）
 *     └── <id>.json            —— 完整消息列表（含 tool 往返）
 *
 * 写入策略：每轮对话结束后立即落盘；崩了最多丢当前正在生成的那一条。
 */
object AiSessionStore {

    /** 会话条目（索引行） */
    class Session {
        @JvmField
        var id: String? = null

        @JvmField
        var title: String? = null          // 默认取首条用户消息前 30 字

        @JvmField
        var createdAt: Long = 0

        @JvmField
        var updatedAt: Long = 0

        @JvmField
        var messageCount: Int = 0
    }

    /** 会话存储目录，与 ai_run.log 同区（app external files dir） */
    @JvmStatic
    fun sessionsDir(c: Context): File {
        var dir = c.getExternalFilesDir(null)
        if (dir == null) dir = c.getFilesDir()
        return File(dir, "ai_sessions")
    }

    // ==================== 索引 ====================

    /** 读取全部会话，按最后活跃时间倒序（最新的在前） */
    @JvmStatic
    fun listSessions(c: Context): MutableList<Session> {
        val out = ArrayList<Session>()
        try {
            val root = readJson(indexFile(c))
            val arr = root.optJSONArray("sessions")
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) continue
                val s = Session()
                s.id = o.optString("id")
                if (TextUtils.isEmpty(s.id)) continue
                s.title = o.optString("title", "新会话")
                s.createdAt = o.optLong("createdAt", 0)
                s.updatedAt = o.optLong("updatedAt", 0)
                s.messageCount = o.optInt("messageCount", 0)
                out.add(s)
            }
        } catch (ignore: Exception) {
        }
        out.sortWith(Comparator<Session> { a, b -> b.updatedAt.compareTo(a.updatedAt) })
        return out
    }

    /** 新建会话，返回会话 id（索引立即写入） */
    @JvmStatic
    fun createSession(c: Context): String {
        val id = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val s = Session()
        s.id = id
        s.title = "新会话"
        s.createdAt = System.currentTimeMillis()
        s.updatedAt = s.createdAt
        s.messageCount = 0
        val all = listSessions(c)
        all.add(0, s)
        writeIndex(c, all)
        return id
    }

    /** 删除会话（索引 + 会话文件） */
    @JvmStatic
    fun deleteSession(c: Context, id: String?) {
        if (TextUtils.isEmpty(id)) return
        val safe = id!!
        try {
            File(sessionsDir(c), safe + ".json").delete()
        } catch (ignore: Exception) {
        }
        val all = listSessions(c)
        val keep = ArrayList<Session>()
        for (s in all) {
            if (!safe.equals(s.id)) keep.add(s)
        }
        writeIndex(c, keep)
    }

    /** 重命名会话 */
    @JvmStatic
    fun renameSession(c: Context, id: String?, newTitle: String?) {
        if (TextUtils.isEmpty(newTitle)) return
        val safeId = id!!
        val all = listSessions(c)
        for (s in all) {
            if (safeId.equals(s.id)) { s.title = newTitle; break }
        }
        writeIndex(c, all)
    }

    // ==================== 消息存取 ====================

    /** 加载会话完整消息列表（含 tool 往返）；文件不存在或损坏返回空列表 */
    @JvmStatic
    fun loadMessages(c: Context, id: String?): MutableList<AiClient.Msg> {
        val out = ArrayList<AiClient.Msg>()
        if (TextUtils.isEmpty(id)) return out
        val safe = id!!
        try {
            val root = readJson(File(sessionsDir(c), safe + ".json"))
            val arr = root.optJSONArray("messages")
            return msgsFromJson(arr)
        } catch (ignore: Exception) {
        }
        return out
    }

    /** 全量落盘整个会话（history 全量写，工具往返原样保留） */
    @JvmStatic
    fun saveMessages(c: Context, id: String?, msgs: MutableList<AiClient.Msg>?, title: String?) {
        if (TextUtils.isEmpty(id)) return
        val safe = id!!
        try {
            val dir = sessionsDir(c)
            if (!dir.exists()) dir.mkdirs()
            val root = JSONObject()
            root.put("id", safe)
            root.put("updatedAt", System.currentTimeMillis())
            root.put("messages", msgsToJsonArray(msgs))
            writeText(File(dir, safe + ".json"), root.toString())

            // 同步索引：时间、消息数；标题只在会话还是默认名且新标题非空时替换
            val all = listSessions(c)
            var found = false
            for (s in all) {
                if (safe.equals(s.id)) {
                    s.updatedAt = System.currentTimeMillis()
                    s.messageCount = if (msgs == null) 0 else msgs.size
                    if (!TextUtils.isEmpty(title)) s.title = title
                    found = true
                    break
                }
            }
            if (!found) {
                val s = Session()
                s.id = safe
                s.title = if (TextUtils.isEmpty(title)) "新会话" else title
                s.createdAt = System.currentTimeMillis()
                s.updatedAt = s.createdAt
                s.messageCount = if (msgs == null) 0 else msgs.size
                all.add(0, s)
            }
            writeIndex(c, all)
        } catch (ignore: Exception) {
        }
    }

    /** 从消息列表里取会话标题：首条 user 消息前 30 字 */
    @JvmStatic
    fun titleFromMessages(msgs: MutableList<AiClient.Msg>?): String? {
        if (msgs == null) return null
        for (m in msgs) {
            if (m != null && "user" == m.role && !TextUtils.isEmpty(m.content)) {
                val t = m.content!!.trim()
                return if (t.length <= 30) t else t.substring(0, 30)
            }
        }
        return null
    }

    // ==================== 序列化 ====================

    private fun msgsToJsonArray(msgs: MutableList<AiClient.Msg>?): JSONArray {
        val arr = JSONArray()
        if (msgs == null) return arr
        for (m in msgs) {
            if (m == null) continue
            if (m.`internal`) continue // 双保险：兼容层内部轮消息（工具结果回灌/计划文本/重申协议）不落盘
            try {
                val o = JSONObject()
                o.put("role", m.role)
                o.put("content", if (m.content == null) "" else m.content)
                if (!TextUtils.isEmpty(m.toolCallId)) o.put("toolCallId", m.toolCallId)
                if (!TextUtils.isEmpty(m.toolName)) o.put("toolName", m.toolName)
                if (!TextUtils.isEmpty(m.toolCallsJson)) o.put("toolCallsJson", m.toolCallsJson)
                arr.put(o)
            } catch (ignore: Exception) {
            }
        }
        return arr
    }

    private fun msgsFromJson(arr: JSONArray?): MutableList<AiClient.Msg> {
        val out = ArrayList<AiClient.Msg>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) continue
            val role = o.optString("role", "")
            val content = o.optString("content", "")
            val m = AiClient.Msg(role, content)
            val tcId = o.optString("toolCallId", null)
            val tn = o.optString("toolName", null)
            val tcj = o.optString("toolCallsJson", null)
            m.toolCallId = if (tcId == null || tcId.isEmpty()) null else tcId
            m.toolName = if (tn == null || tn.isEmpty()) null else tn
            m.toolCallsJson = if (tcj == null || tcj.isEmpty()) null else tcj
            out.add(m)
        }
        return out
    }

    // ==================== 文件 IO ====================

    private fun indexFile(c: Context): File {
        return File(sessionsDir(c), "index.json")
    }

    private fun writeIndex(c: Context, sessions: MutableList<Session>) {
        try {
            val dir = sessionsDir(c)
            if (!dir.exists()) dir.mkdirs()
            val root = JSONObject()
            root.put("version", 1)
            val arr = JSONArray()
            for (s in sessions) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("title", if (s.title == null) "" else s.title)
                o.put("createdAt", s.createdAt)
                o.put("updatedAt", s.updatedAt)
                o.put("messageCount", s.messageCount)
                arr.put(o)
            }
            root.put("sessions", arr)
            writeText(indexFile(c), root.toString())
        } catch (ignore: Exception) {
        }
    }

    private fun readJson(f: File?): JSONObject {
        val s = readText(f)
        if (TextUtils.isEmpty(s)) return JSONObject()
        val safe = s!!
        return JSONObject(safe)
    }

    private fun readText(f: File?): String? {
        if (f == null || !f.exists()) return null
        return FileInputStream(f).use { input ->
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n > 0) {
                bos.write(buf, 0, n)
                n = input.read(buf)
            }
            String(bos.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun writeText(f: File, s: String) {
        FileOutputStream(f).use { out ->
            out.write(s.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }
}
