package com.solosu.mtforum.model

/**
 * 帖子详情数据模型
 * 包含帖主信息、帖子正文、回复列表等完整数据
 *
 * 注：
 *  - `isFollowed` / `likedStateKnown` / `isLiked` / `isFavorited` /
 *    `favoritedStateKnown` 中，以 is 开头的属性 Kotlin 会自动生成 isXxx()，
 *    其余需 @get:JvmName 固定方法名以匹配既有 Java 调用点。
 *  - List 属性保持可空：既有代码多处按 `getReplies() == null` 判断
 *    （如帖子回复相关逻辑），改成非空默认空列表会改变语义。
 */
class PostDetail {
    var tid: String? = null
    var title: String? = null
    var forumName: String? = null
    var forumFid: String? = null

    // ===== 帖主（楼主）信息 =====
    var author: String? = null           // 用户名
    var authorUid: String? = null        // 用户UID
    var authorLevel: String? = null      // 等级（如 "Lv.6 硕士生"）
    var avatarUrl: String? = null        // 头像URL
    var gender: String? = null           // 性别："boy" 或 "girl"，可能为空
    var publishTime: String? = null      // 发布时间（如 "1 小时前"）
    var location: String? = null         // 地点（如 "来自 江苏"）
    var isFollowed: Boolean = false      // 当前用户是否已关注帖主

    // ===== 帖子正文 =====
    var contentHtml: String? = null      // 原始正文HTML（含格式化标签）

    @get:JvmName("isHasHiddenContent")
    @set:JvmName("setHasHiddenContent")
    var hasHiddenContent: Boolean = false // 是否包含隐藏内容

    var hiddenContentHtml: String? = null // 隐藏内容的HTML（已登录可见时）

    // ===== 统计信息 =====
    var replyCount: Int = 0              // 回复总数
    var likeCount: Int = 0               // 点赞/推荐数
    var favoriteCount: Int = 0           // 收藏数
    var rewardCount: Int = 0             // 赞赏次数
    var goodReviewCount: Int = 0         // 好评次数
    var rewardCoins: Int = 0             // 打赏获得的金币总数
    var rewardDetailUrl: String? = null  // 打赏详情页URL
    var rewardUserAvatars: MutableList<String>? = null    // 打赏用户头像URL
    var goodReviewUserAvatars: MutableList<String>? = null // 好评用户头像URL
    var likeUserAvatars: MutableList<String>? = null   // 点赞用户头像URL(登录态 recommend_list_a)
    var likeUserUids: MutableList<String>? = null     // 点赞用户UID列表
    var likeUserNames: MutableList<String>? = null    // 点赞用户名列表(登录态 recommend_list_t)

    var isLiked: Boolean = false         // 当前用户是否已点赞

    @get:JvmName("isLikedStateKnown")
    @set:JvmName("setLikedStateKnown")
    var likedStateKnown: Boolean = false // HTML中能确认当前用户点赞状态

    var isFavorited: Boolean = false     // 当前用户是否已收藏

    @get:JvmName("isFavoritedStateKnown")
    @set:JvmName("setFavoritedStateKnown")
    var favoritedStateKnown: Boolean = false // 收藏页能确认当前状态

    // ===== 回复列表 =====
    var replies: MutableList<ReplyItem>? = null // 当前页的回复列表

    // ===== 分页/表单信息 =====
    var formhash: String? = null         // 当前页面的formhash（用于回复/操作）
    var noticeauthor: String? = null     // 页面中的noticeauthor(用于回复时 @通知对方)
    var postPid: String? = null          // 楼主正文的 pid（赞赏接口必须使用 pid）
    var currentPage: Int = 0             // 当前页码
    var totalPages: Int = 0              // 总页数
    var nextPageUrl: String? = null      // 下一页URL

    // ===== 图片资源 =====
    var imageUrls: MutableList<String>? = null  // 正文中包含的图片URL列表
}
