package com.solosu.mtforum.model;

import java.util.List;

/**
 * 论坛版块分类模型
 */
public class ForumCategory {
    private String name;            // 分类名称 (如 "MT专区")
    private List<Forum> forums;     // 子版块列表

    public ForumCategory() {}

    public ForumCategory(String name, List<Forum> forums) {
        this.name = name;
        this.forums = forums;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Forum> getForums() { return forums; }
    public void setForums(List<Forum> forums) { this.forums = forums; }

    /**
     * 单个版块
     */
    public static class Forum {
        private String fid;          // 版块ID
        private String name;         // 版块名称
        private String description;  // 版块描述
        private int todayPosts;      // 今日发帖数
        private int totalPosts;      // 总帖子数
        private int totalThreads;    // 总主题数
        private String iconUrl;      // 版块图标URL

        public Forum() {}

        public Forum(String fid, String name) {
            this.fid = fid;
            this.name = name;
        }

        public String getFid() { return fid; }
        public void setFid(String fid) { this.fid = fid; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public int getTodayPosts() { return todayPosts; }
        public void setTodayPosts(int todayPosts) { this.todayPosts = todayPosts; }

        public int getTotalPosts() { return totalPosts; }
        public void setTotalPosts(int totalPosts) { this.totalPosts = totalPosts; }

        public int getTotalThreads() { return totalThreads; }
        public void setTotalThreads(int totalThreads) { this.totalThreads = totalThreads; }

        public String getIconUrl() { return iconUrl; }
        public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    }
}