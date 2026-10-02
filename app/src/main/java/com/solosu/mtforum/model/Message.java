package com.solosu.mtforum.model;

/**
 * 私信/消息数据模型
 */
public class Message {
    private String pmid;          // 消息ID
    private String author;        // 发送者
    private String authorUid;     // 发送者UID
    private String avatarUrl;     // 发送者头像
    private String title;         // 消息标题
    private String summary;       // 消息摘要
    private String time;        // 时间
    private String deleteUrl;   // 私信列表中网页端生成的真实删除地址
    private boolean isRead;       // 是否已读
    private int type;             // 0=私信, 1=系统通知, 2=回复提醒

    public Message() {}

    public String getPmid() { return pmid; }
    public void setPmid(String pmid) { this.pmid = pmid; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getDeleteUrl() { return deleteUrl; }
    public void setDeleteUrl(String deleteUrl) { this.deleteUrl = deleteUrl; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
}