package com.solosu.mtforum.model

/**
 * 原生私信会话中的单条聊天消息。
 *
 * 注：Java 侧用的是 isOutgoing()/setOutgoing()，而 Kotlin 属性名 `outgoing`
 * 只会生成 getOutgoing()，故用 @get:JvmName 固定方法名。
 */
class ChatMessage {
    var id: String? = null
    var author: String? = null
    var authorUid: String? = null
    var avatarUrl: String? = null
    var content: String? = null
    var time: String? = null
    var date: String? = null

    @get:JvmName("isOutgoing")
    @set:JvmName("setOutgoing")
    var outgoing: Boolean = false
}
