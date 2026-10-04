package com.solosu.mtforum.model

/**
 * 回复列表项数据模型
 *
 * 注：原 Java 的 `isOP` 访问器为 isOP()/setOP()。Kotlin 属性名 `isOP`
 * 会自动生成 isOP()，但 setter 是 setOP()（去掉 is 前缀并首字母大写
 * ——`OP` 本身已大写，故为 setOP()），与 Java 调用点一致，无需注解。
 */
class ReplyItem {
    var pid: String? = null             // 回复ID
    var floorNumber: Int = 0            // 楼层号（1=沙发，2=椅子，3=地毯...）
    var floorLabel: String? = null      // 原始楼层标签（"沙发"、"椅子"、"14#"等）

    var author: String? = null          // 用户名
    var authorUid: String? = null       // 用户UID
    var authorLevel: String? = null     // 等级（如 "Lv.7 博士生"）
    var avatarUrl: String? = null       // 头像URL
    var gender: String? = null          // 性别："boy" / "girl"

    var isOP: Boolean = false           // 是否为楼主（帖主本人回复）
    var contentHtml: String? = null     // 回复内容HTML（不含引用块）
    var contentText: String? = null     // 纯文本内容（不含引用块）
    var quotedContentHtml: String? = null // 被回复内容HTML
    var quotedContentText: String? = null // 被回复内容纯文本

    var time: String? = null            // 时间（如 "半小时前"）
    var location: String? = null        // 地点（如 "来自 广东"）
    var imageUrls: ArrayList<String> = ArrayList() // 评论包含的附件大图列表

    // 楼中楼（二级回复/评论内嵌套回复）
    var subReplies: MutableList<ReplyItem> = ArrayList() // 挂在该回复下的子回复列表
    var isSubReply: Boolean = false                      // 是否已被归为子回复（不单独占楼）
    var isSubRepliesExpanded: Boolean = true             // 子回复是否展开（默认展开）
}
