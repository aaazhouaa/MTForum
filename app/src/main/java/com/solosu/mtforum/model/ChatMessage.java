package com.solosu.mtforum.model;

/** 原生私信会话中的单条聊天消息。 */
public class ChatMessage {
    private String id;
    private String author;
    private String authorUid;
    private String avatarUrl;
    private String content;
    private String time;
    private String date;
    private boolean outgoing;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public boolean isOutgoing() { return outgoing; }
    public void setOutgoing(boolean outgoing) { this.outgoing = outgoing; }
}
