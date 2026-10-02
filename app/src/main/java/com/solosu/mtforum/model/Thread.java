package com.solosu.mtforum.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 帖子数据模型
 */
public class Thread {
    private String tid;          // 帖子ID
    private String title;        // 标题
    private String author;       // 作者
    private String authorUid;    // 作者UID
    private String authorLevel;  // 作者等级 (如 Lv.3)
    private String avatarUrl;    // 头像URL
    private String forumName;    // 所属版块名称
    private String forumFid;     // 所属版块ID
    private String summary;      // 内容摘要
    private String publishTime;  // 发布时间 (如 "6小时前")
    private int views;           // 阅读数
    private int replies;         // 回复数
    private int likes;           // 点赞数
    private int favorites;       // 收藏数（仅详情页可获取，列表页无此数据）
    private boolean hasImage;    // 是否有图片
    private boolean isSticky;    // 是否置顶
    private boolean hasHiddenContent; // 是否有隐藏内容
    private String thumbnailUrl; // 帖子封面图URL
    private List<String> imageUrls = new ArrayList<>(); // 帖子图片，最多展示4张
    private boolean followed;          // 当前用户是否已关注作者
    private String favid;              // Discuz! 收藏记录ID（仅收藏列表使用）

    public Thread() {}

    // Getters and Setters
    public String getTid() { return tid; }
    public void setTid(String tid) { this.tid = tid; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }

    public String getAuthorLevel() { return authorLevel; }
    public void setAuthorLevel(String authorLevel) { this.authorLevel = authorLevel; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getForumName() { return forumName; }
    public void setForumName(String forumName) { this.forumName = forumName; }

    public String getForumFid() { return forumFid; }
    public void setForumFid(String forumFid) { this.forumFid = forumFid; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getPublishTime() { return publishTime; }
    public void setPublishTime(String publishTime) { this.publishTime = publishTime; }

    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }

    public int getReplies() { return replies; }
    public void setReplies(int replies) { this.replies = replies; }

    public int getLikes() { return likes; }
    public void setLikes(int likes) { this.likes = likes; }
    public int getFavorites() { return favorites; }
    public void setFavorites(int favorites) { this.favorites = favorites; }

    public boolean isHasImage() { return hasImage; }
    public void setHasImage(boolean hasImage) { this.hasImage = hasImage; }

    public boolean isSticky() { return isSticky; }
    public void setSticky(boolean sticky) { isSticky = sticky; }

    public boolean isHasHiddenContent() { return hasHiddenContent; }
    public void setHasHiddenContent(boolean hasHiddenContent) { this.hasHiddenContent = hasHiddenContent; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
    }

    public boolean isFollowed() { return followed; }
    public void setFollowed(boolean followed) { this.followed = followed; }

    public String getFavid() { return favid; }
    public void setFavid(String favid) { this.favid = favid; }
}