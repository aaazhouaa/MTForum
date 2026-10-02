package com.solosu.mtforum.model;

/**
 * 好友/粉丝数据模型
 */
public class Friend {
    private String uid;
    private String username;
    private String avatarUrl;
    private String level;
    private String groupName;
    private String credits;

    public Friend() {}

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getCredits() { return credits; }
    public void setCredits(String credits) { this.credits = credits; }
}
