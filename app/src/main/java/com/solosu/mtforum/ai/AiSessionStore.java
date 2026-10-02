package com.solosu.mtforum.ai;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
public final class AiSessionStore {

    /** 会话条目（索引行） */
    public static class Session {
        public String id;
        public String title;          // 默认取首条用户消息前 30 字
        public long createdAt;
        public long updatedAt;
        public int messageCount;
    }

    private AiSessionStore() { }

    /** 会话存储目录，与 ai_run.log 同区（app external files dir） */
    public static File sessionsDir(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "ai_sessions");
    }

    // ==================== 索引 ====================

    /** 读取全部会话，按最后活跃时间倒序（最新的在前） */
    public static List<Session> listSessions(Context c) {
        List<Session> out = new ArrayList<>();
        try {
            JSONObject root = readJson(indexFile(c));
            JSONArray arr = root.optJSONArray("sessions");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Session s = new Session();
                s.id = o.optString("id");
                if (TextUtils.isEmpty(s.id)) continue;
                s.title = o.optString("title", "新会话");
                s.createdAt = o.optLong("createdAt", 0);
                s.updatedAt = o.optLong("updatedAt", 0);
                s.messageCount = o.optInt("messageCount", 0);
                out.add(s);
            }
        } catch (Exception ignore) { }
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    /** 新建会话，返回会话 id（索引立即写入） */
    public static String createSession(Context c) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Session s = new Session();
        s.id = id;
        s.title = "新会话";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        s.messageCount = 0;
        List<Session> all = listSessions(c);
        all.add(0, s);
        writeIndex(c, all);
        return id;
    }

    /** 删除会话（索引 + 会话文件） */
    public static void deleteSession(Context c, String id) {
        if (TextUtils.isEmpty(id)) return;
        try {
            new File(sessionsDir(c), id + ".json").delete();
        } catch (Exception ignore) { }
        List<Session> all = listSessions(c);
        List<Session> keep = new ArrayList<>();
        for (Session s : all) {
            if (!id.equals(s.id)) keep.add(s);
        }
        writeIndex(c, keep);
    }

    /** 重命名会话 */
    public static void renameSession(Context c, String id, String newTitle) {
        if (TextUtils.isEmpty(newTitle)) return;
        List<Session> all = listSessions(c);
        for (Session s : all) {
            if (id.equals(s.id)) { s.title = newTitle; break; }
        }
        writeIndex(c, all);
    }

    // ==================== 消息存取 ====================

    /** 加载会话完整消息列表（含 tool 往返）；文件不存在或损坏返回空列表 */
    public static List<AiClient.Msg> loadMessages(Context c, String id) {
        List<AiClient.Msg> out = new ArrayList<>();
        if (TextUtils.isEmpty(id)) return out;
        try {
            JSONObject root = readJson(new File(sessionsDir(c), id + ".json"));
            JSONArray arr = root.optJSONArray("messages");
            return msgsFromJson(arr);
        } catch (Exception ignore) { }
        return out;
    }

    /** 全量落盘整个会话（history 全量写，工具往返原样保留） */
    public static void saveMessages(Context c, String id, List<AiClient.Msg> msgs, String title) {
        if (TextUtils.isEmpty(id)) return;
        try {
            File dir = sessionsDir(c);
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            root.put("id", id);
            root.put("updatedAt", System.currentTimeMillis());
            root.put("messages", msgsToJsonArray(msgs));
            writeText(new File(dir, id + ".json"), root.toString());

            // 同步索引：时间、消息数；标题只在会话还是默认名且新标题非空时替换
            List<Session> all = listSessions(c);
            boolean found = false;
            for (Session s : all) {
                if (id.equals(s.id)) {
                    s.updatedAt = System.currentTimeMillis();
                    s.messageCount = msgs == null ? 0 : msgs.size();
                    if (!TextUtils.isEmpty(title)) s.title = title;
                    found = true;
                    break;
                }
            }
            if (!found) {
                Session s = new Session();
                s.id = id;
                s.title = TextUtils.isEmpty(title) ? "新会话" : title;
                s.createdAt = System.currentTimeMillis();
                s.updatedAt = s.createdAt;
                s.messageCount = msgs == null ? 0 : msgs.size();
                all.add(0, s);
            }
            writeIndex(c, all);
        } catch (Exception ignore) { }
    }

    /** 从消息列表里取会话标题：首条 user 消息前 30 字 */
    public static String titleFromMessages(List<AiClient.Msg> msgs) {
        if (msgs == null) return null;
        for (AiClient.Msg m : msgs) {
            if (m != null && "user".equals(m.role) && !TextUtils.isEmpty(m.content)) {
                String t = m.content.trim();
                return t.length() <= 30 ? t : t.substring(0, 30);
            }
        }
        return null;
    }

    // ==================== 序列化 ====================

    private static JSONArray msgsToJsonArray(List<AiClient.Msg> msgs) {
        JSONArray arr = new JSONArray();
        if (msgs == null) return arr;
        for (AiClient.Msg m : msgs) {
            if (m == null) continue;
            if (m.internal) continue; // 双保险：兼容层内部轮消息（工具结果回灌/计划文本/重申协议）不落盘
            try {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", m.content == null ? "" : m.content);
                if (!TextUtils.isEmpty(m.toolCallId)) o.put("toolCallId", m.toolCallId);
                if (!TextUtils.isEmpty(m.toolName)) o.put("toolName", m.toolName);
                if (!TextUtils.isEmpty(m.toolCallsJson)) o.put("toolCallsJson", m.toolCallsJson);
                arr.put(o);
            } catch (Exception ignore) { }
        }
        return arr;
    }

    private static List<AiClient.Msg> msgsFromJson(JSONArray arr) {
        List<AiClient.Msg> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role", "");
            String content = o.optString("content", "");
            AiClient.Msg m = new AiClient.Msg(role, content);
            String tcId = o.optString("toolCallId", null);
            String tn = o.optString("toolName", null);
            String tcj = o.optString("toolCallsJson", null);
            m.toolCallId = tcId == null || tcId.isEmpty() ? null : tcId;
            m.toolName = tn == null || tn.isEmpty() ? null : tn;
            m.toolCallsJson = tcj == null || tcj.isEmpty() ? null : tcj;
            out.add(m);
        }
        return out;
    }

    // ==================== 文件 IO ====================

    private static File indexFile(Context c) {
        return new File(sessionsDir(c), "index.json");
    }

    private static void writeIndex(Context c, List<Session> sessions) {
        try {
            File dir = sessionsDir(c);
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray arr = new JSONArray();
            for (Session s : sessions) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", s.title == null ? "" : s.title);
                o.put("createdAt", s.createdAt);
                o.put("updatedAt", s.updatedAt);
                o.put("messageCount", s.messageCount);
                arr.put(o);
            }
            root.put("sessions", arr);
            writeText(indexFile(c), root.toString());
        } catch (Exception ignore) { }
    }

    private static JSONObject readJson(File f) throws Exception {
        String s = readText(f);
        if (TextUtils.isEmpty(s)) return new JSONObject();
        return new JSONObject(s);
    }

    private static String readText(File f) throws Exception {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
