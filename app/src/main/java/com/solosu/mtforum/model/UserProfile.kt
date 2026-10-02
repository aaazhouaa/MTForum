package com.solosu.mtforum.model

/**
 * 用户个人信息模型
 *
 * 注：`isOnline` 以 is 开头 → Kotlin 自动生成 isOnline()，但其 setter 为
 * setOnline()（去掉 is 前缀），与 Java 调用点一致，无需注解。
 * `followed` / `followStateKnown` 的访问器是 isFollowed() / isFollowStateKnown()，
 * 需 @get:JvmName 固定方法名。
 */
class UserProfile {
    var uid: String? = null            // UID
    var username: String? = null       // 用户名
    var avatarUrl: String? = null      // 头像URL
    var level: String? = null          // 等级 (如 Lv.6)
    var groupName: String? = null      // 用户组 (如 "金牌会员")
    var credits: Int = 0               // 积分
    var gold: Int = 0                  // 金币
    var threads: Int = 0               // 主题数
    var posts: Int = 0                 // 帖子数
    var friends: Int = 0               // 好友数
    var followers: Int = 0             // 粉丝数
    var following: Int = 0             // 关注数
    var views: Int = 0                 // 人气
    var regDate: String? = null        // 注册时间
    var lastVisit: String? = null      // 最后访问
    var signature: String? = null      // 个人签名
    var onlineTime: String? = null     // 在线时间
    var isOnline: Boolean = false      // 是否在线
    var gender: String? = null         // 性别 ("boy"/"girl")

    @get:JvmName("isFollowed")
    @set:JvmName("setFollowed")
    var followed: Boolean = false      // 当前登录用户是否已关注该用户

    @get:JvmName("isFollowStateKnown")
    @set:JvmName("setFollowStateKnown")
    var followStateKnown: Boolean = false // 服务端是否明确返回了关注状态
}
