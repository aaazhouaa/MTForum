package com.solosu.mtforum.model;

import java.util.List;

/**
 * 帖子详情数据模型
 * 包含帖主信息、帖子正文、回复列表等完整数据
 */
public class PostDetail {
    private String tid;
    private String title;
    private String forumName;
    private String forumFid;

    // ===== 帖主（楼主）信息 =====
    private String author;           // 用户名
    private String authorUid;        // 用户UID
    private String authorLevel;      // 等级（如 "Lv.6 硕士生"）
    private String avatarUrl;        // 头像URL
    private String gender;           // 性别："boy" 或 "girl"，可能为空
    private String publishTime;      // 发布时间（如 "1 小时前"）
    private String location;         // 地点（如 "来自 江苏"）
    private boolean isFollowed;      // 当前用户是否已关注帖主

    // ===== 帖子正文 =====
    private String contentHtml;      // 原始正文HTML（含格式化标签）
    private boolean hasHiddenContent; // 是否包含隐藏内容
    private String hiddenContentHtml; // 隐藏内容的HTML（已登录可见时）

    // ===== 统计信息 =====
    private int replyCount;          // 回复总数
    private int likeCount;           // 点赞/推荐数
    private int favoriteCount;       // 收藏数
    private int rewardCount;         // 赞赏次数
    private int goodReviewCount;     // 好评次数
    private int rewardCoins;         // 打赏获得的金币总数
    private String rewardDetailUrl;  // 打赏详情页URL
    private List<String> rewardUserAvatars;    // 打赏用户头像URL
    private List<String> goodReviewUserAvatars; // 好评用户头像URL
    private List<String> likeUserAvatars;   // 点赞用户头像URL(登录态 recommend_list_a)
    private List<String> likeUserUids;     // 点赞用户UID列表
    private List<String> likeUserNames;    // 点赞用户名列表(登录态 recommend_list_t)

    private boolean isLiked;         // 当前用户是否已点赞
    private boolean likedStateKnown; // HTML中能确认当前用户点赞状态
    private boolean isFavorited;     // 当前用户是否已收藏
    private boolean favoritedStateKnown; // 收藏页能确认当前状态

    // ===== 回复列表 =====
    private List<ReplyItem> replies; // 当前页的回复列表

    // ===== 分页/表单信息 =====
    private String formhash;         // 当前页面的formhash（用于回复/操作）
    private String noticeauthor;      // 页面中的noticeauthor(用于回复时 @通知对方)
    private String postPid;           // 楼主正文的 pid（赞赏接口必须使用 pid）
    private int currentPage;         // 当前页码
    private int totalPages;          // 总页数
    private String nextPageUrl;      // 下一页URL

    // ===== 图片资源 =====
    private List<String> imageUrls;  // 正文中包含的图片URL列表

    public PostDetail() {}

    // ===== Getters & Setters =====

    public String getTid() { return tid; }
    public void setTid(String tid) { this.tid = tid; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getForumName() { return forumName; }
    public void setForumName(String forumName) { this.forumName = forumName; }

    public String getForumFid() { return forumFid; }
    public void setForumFid(String forumFid) { this.forumFid = forumFid; }

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

    public String getPublishTime() { return publishTime; }
    public void setPublishTime(String publishTime) { this.publishTime = publishTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isFollowed() { return isFollowed; }
    public void setFollowed(boolean followed) { isFollowed = followed; }

    public String getContentHtml() { return contentHtml; }
    public void setContentHtml(String contentHtml) { this.contentHtml = contentHtml; }

    public boolean isHasHiddenContent() { return hasHiddenContent; }
    public void setHasHiddenContent(boolean hasHiddenContent) { this.hasHiddenContent = hasHiddenContent; }

    public String getHiddenContentHtml() { return hiddenContentHtml; }
    public void setHiddenContentHtml(String hiddenContentHtml) { this.hiddenContentHtml = hiddenContentHtml; }

    public int getReplyCount() { return replyCount; }
    public void setReplyCount(int replyCount) { this.replyCount = replyCount; }

    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getFavoriteCount() { return favoriteCount; }
    public void setFavoriteCount(int favoriteCount) { this.favoriteCount = favoriteCount; }

    public int getRewardCount() { return rewardCount; }
    public void setRewardCount(int rewardCount) { this.rewardCount = rewardCount; }

    public int getGoodReviewCount() { return goodReviewCount; }
    public void setGoodReviewCount(int goodReviewCount) { this.goodReviewCount = goodReviewCount; }

    public int getRewardCoins() { return rewardCoins; }
    public void setRewardCoins(int rewardCoins) { this.rewardCoins = rewardCoins; }

    public String getRewardDetailUrl() { return rewardDetailUrl; }
    public void setRewardDetailUrl(String rewardDetailUrl) { this.rewardDetailUrl = rewardDetailUrl; }

    public List<String> getRewardUserAvatars() { return rewardUserAvatars; }
    public void setRewardUserAvatars(List<String> avatars) { this.rewardUserAvatars = avatars; }

    public List<String> getGoodReviewUserAvatars() { return goodReviewUserAvatars; }
    public void setGoodReviewUserAvatars(List<String> avatars) { this.goodReviewUserAvatars = avatars; }
    public List<String> getLikeUserAvatars() { return likeUserAvatars; }
    public void setLikeUserAvatars(List<String> avatars) { this.likeUserAvatars = avatars; }
    public List<String> getLikeUserUids() { return likeUserUids; }
    public void setLikeUserUids(List<String> uids) { this.likeUserUids = uids; }
    public List<String> getLikeUserNames() { return likeUserNames; }
    public void setLikeUserNames(List<String> names) { this.likeUserNames = names; }

    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) { isLiked = liked; }
    public boolean isLikedStateKnown() { return likedStateKnown; }
    public void setLikedStateKnown(boolean known) { likedStateKnown = known; }

    public boolean isFavorited() { return isFavorited; }
    public void setFavorited(boolean favorited) { isFavorited = favorited; }
    public boolean isFavoritedStateKnown() { return favoritedStateKnown; }
    public void setFavoritedStateKnown(boolean known) { favoritedStateKnown = known; }

    public List<ReplyItem> getReplies() { return replies; }
    public void setReplies(List<ReplyItem> replies) { this.replies = replies; }

    public String getFormhash() { return formhash; }
    public void setFormhash(String formhash) { this.formhash = formhash; }

    public String getNoticeauthor() { return noticeauthor; }
    public void setNoticeauthor(String noticeauthor) { this.noticeauthor = noticeauthor; }

    public String getPostPid() { return postPid; }
    public void setPostPid(String postPid) { this.postPid = postPid; }

    public int getCurrentPage() { return currentPage; }
    public void setCurrentPage(int currentPage) { this.currentPage = currentPage; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public String getNextPageUrl() { return nextPageUrl; }
    public void setNextPageUrl(String nextPageUrl) { this.nextPageUrl = nextPageUrl; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
}