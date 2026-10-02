package com.solosu.mtforum.model;

/**
 * 回复列表项数据模型
 */
public class ReplyItem {
    private String pid;             // 回复ID
    private int floorNumber;        // 楼层号（1=沙发，2=椅子，3=地毯...）
    private String floorLabel;      // 原始楼层标签（"沙发"、"椅子"、"14#"等）

    private String author;          // 用户名
    private String authorUid;       // 用户UID
    private String authorLevel;     // 等级（如 "Lv.7 博士生"）
    private String avatarUrl;       // 头像URL
    private String gender;          // 性别："boy" / "girl"

    private boolean isOP;           // 是否为楼主（帖主本人回复）
    private String contentHtml;     // 回复内容HTML（不含引用块）
    private String contentText;     // 纯文本内容（不含引用块）
    private String quotedContentHtml; // 被回复内容HTML
    private String quotedContentText; // 被回复内容纯文本

    private String time;            // 时间（如 "半小时前"）
    private String location;        // 地点（如 "来自 广东"）

    public ReplyItem() {}

    // ===== Getters & Setters =====

    public String getPid() { return pid; }
    public void setPid(String pid) { this.pid = pid; }

    public int getFloorNumber() { return floorNumber; }
    public void setFloorNumber(int floorNumber) { this.floorNumber = floorNumber; }

    public String getFloorLabel() { return floorLabel; }
    public void setFloorLabel(String floorLabel) { this.floorLabel = floorLabel; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }

    public String getAuthorLevel() { return authorLevel; }
    public void setAuthorLevel(String authorLevel) { this.authorLevel = authorLevel; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public boolean isOP() { return isOP; }
    public void setOP(boolean OP) { isOP = OP; }

    public String getContentHtml() { return contentHtml; }
    public void setContentHtml(String contentHtml) { this.contentHtml = contentHtml; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public String getQuotedContentHtml() { return quotedContentHtml; }
    public void setQuotedContentHtml(String quotedContentHtml) { this.quotedContentHtml = quotedContentHtml; }

    public String getQuotedContentText() { return quotedContentText; }
    public void setQuotedContentText(String quotedContentText) { this.quotedContentText = quotedContentText; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}