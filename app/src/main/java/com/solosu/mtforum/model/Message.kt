package com.solosu.mtforum.model

/**
 * 私信/消息数据模型
 *
 * 注：`isRead` 以 is 开头，Kotlin 会自动生成 isRead()，与 Java 调用点一致；
 * 但其 setter 默认是 setRead()，与 Java 期望的 setRead() 也一致，无需注解。
 */
class Message {
    var pmid: String? = null          // 消息ID
    var author: String? = null        // 发送者
    var authorUid: String? = null     // 发送者UID
    var avatarUrl: String? = null     // 发送者头像
    var title: String? = null         // 消息标题
    var summary: String? = null       // 消息摘要
    var time: String? = null          // 时间
    var deleteUrl: String? = null     // 私信列表中网页端生成的真实删除地址
    var isRead: Boolean = false       // 是否已读
    var type: Int = 0                 // 0=私信, 1=系统通知, 2=回复提醒
}
