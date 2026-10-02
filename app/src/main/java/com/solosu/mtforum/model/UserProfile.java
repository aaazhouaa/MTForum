package com.solosu.mtforum.model;

/**
 * 用户个人信息模型
 */
public class UserProfile {
    private String uid;            // UID
    private String username;       // 用户名
    private String avatarUrl;      // 头像URL
    private String level;          // 等级 (如 Lv.6)
    private String groupName;      // 用户组 (如 "金牌会员")
    private int credits;           // 积分
    private int gold;              // 金币
    private int threads;           // 主题数
    private int posts;             // 帖子数
    private int friends;           // 好友数
    private int followers;         // 粉丝数
    private int following;         // 关注数
    private int views;             // 人气
    private String regDate;        // 注册时间
    private String lastVisit;      // 最后访问
    private String signature;      // 个人签名
    private String onlineTime;     // 在线时间
    private boolean isOnline;      // 是否在线
    private String gender;         // 性别 ("boy"/"girl")
    private boolean followed;      // 当前登录用户是否已关注该用户
    private boolean followStateKnown; // 服务端是否明确返回了关注状态

    public UserProfile() {}

    // Getters and Setters
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

    public int getCredits() { return credits; }
    public void setCredits(int credits) { this.credits = credits; }

    public int getGold() { return gold; }
    public void setGold(int gold) { this.gold = gold; }

    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }

    public int getPosts() { return posts; }
    public void setPosts(int posts) { this.posts = posts; }

    public int getFriends() { return friends; }
    public void setFriends(int friends) { this.friends = friends; }

    public int getFollowers() { return followers; }
    public void setFollowers(int followers) { this.followers = followers; }

    public int getFollowing() { return following; }
    public void setFollowing(int following) { this.following = following; }

    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }

    public String getRegDate() { return regDate; }
    public void setRegDate(String regDate) { this.regDate = regDate; }

    public String getLastVisit() { return lastVisit; }
    public void setLastVisit(String lastVisit) { this.lastVisit = lastVisit; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getOnlineTime() { return onlineTime; }
    public void setOnlineTime(String onlineTime) { this.onlineTime = onlineTime; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public boolean isFollowed() { return followed; }
    public void setFollowed(boolean followed) { this.followed = followed; }

    public boolean isFollowStateKnown() { return followStateKnown; }
    public void setFollowStateKnown(boolean followStateKnown) { this.followStateKnown = followStateKnown; }

}