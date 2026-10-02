package com.solosu.mtforum.model

/**
 * 帖子数据模型
 *
 * 注：Kotlin 只对 `is` 开头的属性名生成 `isXxx()` 访问器，其余 Boolean 生成
 * `getXxx()`。此处 Java 侧调用的是 `isHasImage()` / `isFollowed()`，
 * 故用 @get:JvmName 固定方法名，保持与既有 Java 调用点兼容。
 */
class Thread {
    var tid: String? = null          // 帖子ID
    var title: String? = null        // 标题
    var author: String? = null       // 作者
    var authorUid: String? = null    // 作者UID
    var authorLevel: String? = null  // 作者等级 (如 Lv.3)
    var avatarUrl: String? = null    // 头像URL
    var forumName: String? = null    // 所属版块名称
    var forumFid: String? = null     // 所属版块ID
    var summary: String? = null      // 内容摘要
    var publishTime: String? = null  // 发布时间 (如 "6小时前")
    var views: Int = 0               // 阅读数
    var replies: Int = 0             // 回复数
    var likes: Int = 0               // 点赞数
    var favorites: Int = 0           // 收藏数（仅详情页可获取，列表页无此数据）

    @get:JvmName("isHasImage")
    @set:JvmName("setHasImage")
    var hasImage: Boolean = false    // 是否有图片

    var isSticky: Boolean = false    // 是否置顶

    @get:JvmName("isHasHiddenContent")
    @set:JvmName("setHasHiddenContent")
    var hasHiddenContent: Boolean = false // 是否有隐藏内容

    var thumbnailUrl: String? = null // 帖子封面图URL
    var imageUrls: MutableList<String> = ArrayList() // 帖子图片，最多展示4张

    @get:JvmName("isFollowed")
    @set:JvmName("setFollowed")
    var followed: Boolean = false    // 当前用户是否已关注作者

    var favid: String? = null        // Discuz! 收藏记录ID（仅收藏列表使用）
}
