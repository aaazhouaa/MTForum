package com.solosu.mtforum.model

/**
 * 论坛版块分类模型
 */
class ForumCategory {

    var name: String? = null            // 分类名称 (如 "MT专区")
    var forums: MutableList<Forum>? = null // 子版块列表

    constructor()

    constructor(name: String?, forums: MutableList<Forum>?) {
        this.name = name
        this.forums = forums
    }

    /**
     * 单个版块
     */
    class Forum {

        var fid: String? = null          // 版块ID
        var name: String? = null         // 版块名称
        var description: String? = null  // 版块描述
        var todayPosts: Int = 0          // 今日发帖数
        var totalPosts: Int = 0          // 总帖子数
        var totalThreads: Int = 0        // 总主题数
        var iconUrl: String? = null      // 版块图标URL

        constructor()

        constructor(fid: String?, name: String?) {
            this.fid = fid
            this.name = name
        }
    }
}
