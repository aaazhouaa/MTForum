package com.solosu.mtforum.ui.detail

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.solosu.mtforum.R
import com.solosu.mtforum.model.ReplyItem
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.space.UserProfileActivity
import com.solosu.mtforum.util.BBCodeUtil
import com.solosu.mtforum.util.NavigationHelper
import com.solosu.mtforum.util.UrlDrawable
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 回复列表适配器
 * 支持 Glide 加载头像、楼层标签、楼主标识、等级、时间、地点等完整信息
 */
class ReplyAdapter(rawReplies: List<ReplyItem>?) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 页首「帖子正文头」item：让整页只由一个 RecyclerView 滚动（恢复回收复用）。 */
    interface HeaderProvider {
        fun onCreateHeaderView(parent: ViewGroup): View
        fun onBindHeaderView(view: View)
    }

    private var headerProvider: HeaderProvider? = null

    data class DisplayRow(
        val item: ReplyItem,
        val foldedCount: Int = 0,
        val isExpanded: Boolean = false,
        val groupKey: String = "",
        /** 分组入口行显示的“名物”文案（配合展开/折叠状态拼句子）。 */
        val groupLabel: String? = null,
        /** 行身份，用于展开/折叠后把视口锚回同一行（否则点击后列表会跳位）。 */
        val identity: String = "r:" + (item.pid ?: System.identityHashCode(item).toString())
    )

    private var rawReplyList: List<ReplyItem> = rawReplies ?: ArrayList()

    /** 已展开的分组。默认全部折叠，只有用户点过「展开」的才在此集合里。 */
    /** 是否已展开折叠评论（默认全部折叠） */
    var isFoldExpanded: Boolean = false
        private set

    /** 当前被折叠的评论总数 */
    var foldedCount: Int = 0
        private set

    /** 折叠状态变更回调，用于通知外部更新顶部的折叠胶囊标签 */
    var onFoldStateChanged: ((count: Int, isExpanded: Boolean) -> Unit)? = null

    /** 承载本适配器的 RecyclerView */
    private var attachedRecycler: RecyclerView? = null
    private var displayList: MutableList<DisplayRow> = ArrayList()

    private var replyClickListener: OnReplyClickListener? = null
    private var userClickListener: OnUserClickListener? = null
    private var replyLongClickListener: OnReplyLongClickListener? = null

    init {
        organizeReplies()
        rebuildDisplayList()
    }

    private fun organizeReplies() {
        val all = rawReplyList
        if (all.isEmpty()) return

        // 先清空上一轮的归并结果：updateData 会被翻页反复调用，
        // subReplies 若不清理会越累越多，同一子回复重复渲染。
        val pidMap = HashMap<String, ReplyItem>()
        val nameMap = HashMap<String, MutableList<ReplyItem>>()
        for ((index, item) in all.withIndex()) {
            item.subReplies.clear()
            item.isSubReply = false
            item.inReplyToName = null
            item.orderIndex = index
            item.pid?.let { pidMap[it] = item }
            val author = item.author
            if (!author.isNullOrBlank()) {
                nameMap.getOrPut(author) { ArrayList() }.add(item)
            }
        }

        for (item in all) {
            // 被引用楼层：可能是顶层评论，也可能是一条子回复
            var target: ReplyItem? = null

            // 引用块里一旦带有明确的 pid，就说明消息指向的是「某一条具体楼层」。
            // 命中不了只有一种可能：那条被引用楼层不在当前已加载的楼层里，
            // 此时必须放弃归并 —— 绝不能退化成“按作者名找个人”，
            // 否则会把回复挂到该作者另一条无关楼层上，出现父子挂反。
            val quotedPid = item.quotedPid
            if (!quotedPid.isNullOrEmpty()) {
                val cand = pidMap[quotedPid]
                if (cand != null && cand !== item) target = cand
            } else {
                // 无 pid 的模板只能按 uid / 昵称匹配，
                // 并用「列表顺序号」作先后约束（被引用楼层必须排在引用者之前）。
                // 不再解析楼层文字（沙发/下水道/7# 等节点名无法穷举，
                // 一旦解析成 -1 会让约束失效，取到同名者最后一条而挂反）。
                if (target == null) {
                    val uid = item.quotedUid
                    if (!uid.isNullOrEmpty()) {
                        target = all.lastOrNull {
                            it !== item && it.authorUid == uid && it.orderIndex < item.orderIndex
                        }
                    }
                }

                if (target == null) {
                    val name = item.quotedAuthorName ?: extractQuotedNameFromQuote(item)
                    if (!name.isNullOrEmpty()) {
                        target = nameMap[name]?.lastOrNull {
                            it !== item && it.orderIndex < item.orderIndex
                        }
                    }
                }
            }

            if (target == null || target === item) continue

            // 压平到该子线程的顶层评论下：
            // 若被引用者本身就是子回复，则挂到它所属的顶层评论（同一子线程回复平铺）
            val root = if (target.isSubReply) topLevelOf(target, all) else target
            if (root == null || root === item) continue
            // 只允许「较晚的回复」挂到「较早的楼层」下，
            // 避免两条互相引用的回复因遍历顺序不同而把父子方向弄反。
            if (root.orderIndex >= item.orderIndex) continue

            item.isSubReply = true
            // 回复对象不是顶层评论时，子回复项要标明“回复 xx”（回复顶层评论不加，保持清爽）
            if (root !== target) {
                item.inReplyToName = target.author
            }
            root.subReplies.add(item)
        }
    }

    /** 向上找到某条回复所属的顶层评论（其 subReplies 中包含它的那条）。 */
    private fun topLevelOf(child: ReplyItem, all: List<ReplyItem>): ReplyItem? {
        return all.firstOrNull { !it.isSubReply && it.subReplies.contains(child) }
    }

    /**
     * 从引用正文里抽被引用者昵称。
     *
     * 引用块的文本形态在不同模板下有两种：文本在前（“回复 mt007 发表于 …”）
     * 或 meta 在尾部（“依旧给力… 回复 mt007 发表于 …”），两者都要能解析到。
     */
    private fun extractQuotedNameFromQuote(item: ReplyItem): String? {
        val quote = item.quotedContentText ?: return null
        // “回复 xxx 发表于” / “xxx 发表于”
        Regex("(?:回复\\s*)?([^\\n]{1,24}?)\\s*发表于").find(quote)?.let {
            val name = it.groupValues[1].trim()
                .removePrefix("回复").trim()
                .removePrefix("@").trim()
            if (name.isNotEmpty() && name != item.author) return name
        }
        // “回复 xxx” 且没有“发表于”（部分手机版只带这句）
        Regex("回复\\s*@?([^\\s，。,:：]{1,24})").find(quote)?.let {
            val name = it.groupValues[1].trim()
            if (name.isNotEmpty() && name != item.author) return name
        }
        return null
    }

    private fun isSequentialOrRepeatedDigits(s: String): Boolean {
        if (s.length < 2 || !s.all { it.isDigit() }) return false
        // 全部相同重复数字, 如 1111, 66666, 88888
        if (s.all { it == s[0] }) return true
        // 连续递增数字, 如 123456
        if (s.length >= 3) {
            var isInc = true
            for (i in 0 until s.length - 1) {
                if (s[i + 1] - s[i] != 1) {
                    isInc = false
                    break
                }
            }
            if (isInc) return true

            // 连续递减数字, 如 654321
            var isDec = true
            for (i in 0 until s.length - 1) {
                if (s[i] - s[i + 1] != 1) {
                    isDec = false
                    break
                }
            }
            if (isDec) return true
        }
        return false
    }

    private fun isWaterReply(item: ReplyItem): Boolean {
        val rawHtml = item.contentHtml ?: item.contentText ?: ""
        // 1. 提取实际纯文本字符（剔除所有 HTML 标签、实体和空白符）
        val textOnly = org.jsoup.Jsoup.parse(rawHtml).text().trim()

        // 剔除 Discuz 表情码（形如 {:4_111:} 或 [em:01:]）与空白
        var clean = textOnly.replace(Regex("\\{:\\d+_\\d+:\\}"), "")
            .replace(Regex("\\[em:\\d+\\]"), "")
            .replace(Regex("[\\s\\u00A0\\u3000]"), "")

        // 剔除 emoji 字符
        val emojiRegex = Regex("[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\u2300-\u23FF\u2B50\u2B55\u200D\uFE0F]")
        val textWithoutEmoji = clean.replace(emojiRegex, "")

        // 剔除标点符号后的纯文字内容
        val textWithoutPunct = textWithoutEmoji.replace(Regex("[\\p{P}\\p{S}，。！？!?,.~～、_—\\-+=\\[\\](){};；:：“”\"'/\\\\`]"), "")

        // 2. 关键词匹配：包含即折叠（如感谢、看看、隐藏、分享、学习学习、论坛有你更精彩等）
        val containKeywords = arrayOf(
            "感谢", "看看", "隐藏", "分享",
            "学习学习", "学习一下", "论坛有你更精彩", "支持一下", "66666"
        )
        for (kw in containKeywords) {
            if (textOnly.contains(kw, ignoreCase = true)) return true
        }

        // 3. 用户规则：纯文字字数 <= 2 个字的全部折叠
        if (clean.length <= 2) {
            return true
        }

        // 4. 用户规则：纯表情（仅表情图片/emoji或无实质汉字/字母/数字等有效文字内容）全部折叠
        if (textWithoutPunct.isEmpty()) {
            return true
        }

        // 5. 用户规则：完全匹配指定灌水短语（注意：要求完全匹配，不是包含）
        val exactMatchPhrases = hashSetOf(
            "拿走试试", "大佬牛逼", "拿走了", "查看内容", "这么牛逼啊",
            "大佬厉害了", "牛逼啊大佬", "我来了", "谢谢大佬", "我来试一试",
            "哇哇哇", "啊啊啊", "膜拜大佬", "严肃学习", "大佬牛批",
            "谢谢啦", "谢谢了", "来了来了", "回复一下表示支持",
            "谢谢楼主", "可以可以", "小飞机来了", "小飞机来喽", "小飞机来咯"
        )
        if (exactMatchPhrases.contains(textWithoutPunct) || exactMatchPhrases.contains(clean)) {
            return true
        }

        // 6. 用户规则：连续的数字、重复的数字、以及连续/重复数字+表情（无其它实质文字）全部折叠
        if (textWithoutPunct.isNotEmpty() && textWithoutPunct.all { it.isDigit() }) {
            if (isSequentialOrRepeatedDigits(textWithoutPunct) || textWithoutPunct.length >= 3) {
                return true
            }
        }

        return false
    }

    private fun rebuildDisplayList() {
        displayList.clear()
        val n = rawReplyList.size
        if (n == 0) {
            foldedCount = 0
            onFoldStateChanged?.invoke(0, isFoldExpanded)
            return
        }

        var count = 0
        for (item in rawReplyList) {
            if (item.isSubReply) {
                // 已归类为楼中楼子回复，在主评论下方嵌套展示，不再单独占一楼
                continue
            }
            val isWater = isWaterReply(item)
            if (isWater) {
                count++
                if (isFoldExpanded) {
                    displayList.add(DisplayRow(item = item))
                }
            } else {
                displayList.add(DisplayRow(item = item))
            }
        }
        foldedCount = count
        onFoldStateChanged?.invoke(count, isFoldExpanded)
    }

    /** 切换展开/折叠状态 */
    fun toggleFoldExpanded() {
        isFoldExpanded = !isFoldExpanded
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecycler === recyclerView) attachedRecycler = null
    }

    interface OnReplyClickListener {
        fun onReplyClick(item: ReplyItem?, position: Int)
    }

    interface OnUserClickListener {
        fun onUserClick(item: ReplyItem?, position: Int)
    }

    /** build73: 长按评论 -> 回复/举报/删除 菜单 */
    interface OnReplyLongClickListener {
        fun onReplyLongClick(item: ReplyItem?, position: Int)
    }

    fun setOnReplyLongClickListener(listener: OnReplyLongClickListener?) {
        this.replyLongClickListener = listener
    }

    fun setOnReplyClickListener(listener: OnReplyClickListener?) {
        this.replyClickListener = listener
    }

    fun setOnUserClickListener(listener: OnUserClickListener?) {
        this.userClickListener = listener
    }

    private fun hasHeader(): Boolean = headerProvider != null

    /** 底部状态行：自动加载中 / 失败重试 / 到底了。 */
    enum class FooterState { LOADING, RETRY, END }

    interface FooterActionListener {
        fun onRetry()
    }

    var footerState: FooterState? = null
        private set
    private var footerActionListener: FooterActionListener? = null

    fun setFooterActionListener(listener: FooterActionListener?) {
        footerActionListener = listener
    }

    /** 设置底部状态行；传 null 则移除该行。 */
    fun setFooterState(state: FooterState?) {
        if (footerState == state) return
        val had = footerState != null
        val has = state != null
        footerState = state
        val tail = displayList.size + (if (hasHeader()) 1 else 0)
        when {
            !had && has -> notifyItemInserted(tail)
            had && !has -> notifyItemRemoved(tail)
            else -> notifyItemChanged(tail)
        }
    }

    fun setHeaderProvider(provider: HeaderProvider?) {
        headerProvider = provider
        if (provider != null) notifyItemInserted(0) else if (itemCount > 0) notifyItemRemoved(0)
    }

    fun updateData(newList: List<ReplyItem>?) {
        this.rawReplyList = newList ?: ArrayList()
        organizeReplies()
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (hasHeader() && position == 0) return TYPE_HEADER
        if (footerState != null && position == itemCount - 1) return TYPE_FOOTER
        return TYPE_REPLY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val v = headerProvider!!.onCreateHeaderView(parent)
            return HeaderViewHolder(v)
        }
        if (viewType == TYPE_FOOTER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reply_footer, parent, false)
            return FooterViewHolder(v)
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reply, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            headerProvider?.onBindHeaderView(holder.itemView)
            return
        }
        if (holder is FooterViewHolder) {
            holder.bind(footerState ?: FooterState.END)
            return
        }
        // 预加载由外层 RecyclerView 的滚动监听驱动，绝不在 bind 里触发：
        // 否则「bind末尾→拉下一页→全量刷新→又bind末尾」会自激，进帖即把整帖拉完。
        (holder as ViewHolder).bind(displayList[replyIndex(position)])
    }

    /** 列表位置 -> 回复行下标（去掉 header 占位）。 */
    private fun replyIndex(position: Int): Int {
        return if (hasHeader()) position - 1 else position
    }

    override fun getItemCount(): Int {
        return displayList.size + (if (hasHeader()) 1 else 0) + (if (footerState != null) 1 else 0)
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val progress: View = itemView.findViewById(R.id.footer_progress)
        private val text: TextView = itemView.findViewById(R.id.footer_text)

        fun bind(state: FooterState) {
            when (state) {
                FooterState.LOADING -> {
                    progress.visibility = View.VISIBLE
                    text.text = "加载中…"
                    itemView.isClickable = false
                    itemView.setOnClickListener(null)
                }
                FooterState.RETRY -> {
                    progress.visibility = View.GONE
                    text.text = "加载失败，点击重试"
                    itemView.isClickable = true
                    itemView.setOnClickListener { footerActionListener?.onRetry() }
                }
                FooterState.END -> {
                    progress.visibility = View.GONE
                    text.text = "已经到底了"
                    itemView.isClickable = false
                    itemView.setOnClickListener(null)
                }
            }
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_reply_avatar)
        private val tvFloorLabel: TextView = itemView.findViewById(R.id.tv_floor_label)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tv_reply_author)
        private val tvOpBadge: TextView = itemView.findViewById(R.id.tv_op_badge)
        private val tvLevel: TextView = itemView.findViewById(R.id.tv_reply_level)
        private val tvReplyEditTime: TextView? = itemView.findViewById(R.id.tv_reply_edit_time)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_reply_content)
        private val layoutReplyQuote: LinearLayout = itemView.findViewById(R.id.layout_reply_quote)
        private val tvReplyQuoteTitle: TextView? = itemView.findViewById(R.id.tv_reply_quote_title)
        private val tvReplyQuote: TextView = itemView.findViewById(R.id.tv_reply_quote)
        private val llReplyImages: LinearLayout = itemView.findViewById(R.id.ll_reply_images)
        private val layoutCollapsedHint: View? = itemView.findViewById(R.id.layout_collapsed_hint)
        private val ivCollapsedIcon: ImageView? = itemView.findViewById(R.id.iv_collapsed_icon)
        private val tvCollapsedText: TextView? = itemView.findViewById(R.id.tv_collapsed_text)

        // 图六楼中楼与底栏新控件
        private val tvReplyBottomTime: TextView? = itemView.findViewById(R.id.tv_reply_bottom_time)
        private val btnToggleSubReplies: View? = itemView.findViewById(R.id.btn_toggle_sub_replies)
        private val ivToggleArrow: ImageView? = itemView.findViewById(R.id.iv_toggle_arrow)
        private val tvToggleText: TextView? = itemView.findViewById(R.id.tv_toggle_text)
        private val btnReplyText: ImageView? = itemView.findViewById(R.id.btn_reply_text)
        private val layoutSubRepliesContainer: LinearLayout? = itemView.findViewById(R.id.layout_sub_replies_container)
        private val llSubRepliesList: LinearLayout? = itemView.findViewById(R.id.ll_sub_replies_list)

        fun bind(row: DisplayRow) {
            // 普通回复行：先收起折叠提示，避免复用残留
            layoutCollapsedHint?.visibility = View.GONE
            itemView.setOnClickListener(null)
            ivAvatar.visibility = View.VISIBLE
            val item = row.item
            // 头像 - Glide 加载圆图
            val avatarUrl = item.avatarUrl
            if (!TextUtils.isEmpty(avatarUrl)) {
                Glide.with(ivAvatar.context)
                    .load(avatarUrl)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account)
            }

            // ★ 新增：头像和用户名点击 → 打开原生用户资料页
            val pos = bindingAdapterPosition
            val currentItem = item
            ivAvatar.setOnClickListener {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.authorUid)) {
                    userClickListener!!.onUserClick(currentItem, pos)
                }
            }
            tvAuthor.setOnClickListener {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.authorUid)) {
                    userClickListener!!.onUserClick(currentItem, pos)
                }
            }

            // build73: 整项长按 -> 操作菜单(回复/举报/删除)
            itemView.setOnLongClickListener {
                if (replyLongClickListener != null) {
                    replyLongClickListener!!.onReplyLongClick(currentItem, bindingAdapterPosition)
                    return@setOnLongClickListener true
                }
                false
            }

            // 楼层标签（沙发/椅子/地毯/报纸/N#）
            val floorLabel = item.floorLabel
            if (!TextUtils.isEmpty(floorLabel)) {
                tvFloorLabel.visibility = View.VISIBLE
                tvFloorLabel.text = floorLabel
            } else {
                tvFloorLabel.visibility = View.GONE
            }

            // 作者名
            tvAuthor.text = if (!TextUtils.isEmpty(item.author)) item.author else "匿名"

            // 楼主标识
            if (item.isOP) {
                tvOpBadge.visibility = View.VISIBLE
            } else {
                tvOpBadge.visibility = View.GONE
            }

            // 等级/称号：按用户要求在评论区完全隐藏，保持作者栏清爽统一
            tvLevel.visibility = View.GONE

            // 时间与回复图标已改由底栏呈现（原先头部行内的控件已删除）

            // 引用内容单独显示，模拟网页端的浅黄色引用框；当前回复保持深色正文。
            val quotedText = item.quotedContentText
            if (!TextUtils.isEmpty(quotedText)) {
                layoutReplyQuote.visibility = View.VISIBLE
                val rawQuote = quotedText!!.trim()
                var meta: String? = null
                var body: String = rawQuote

                val m1 = P_QUOTE_META.matcher(rawQuote)
                if (m1.find()) {
                    meta = m1.group(1)?.trim()
                    body = m1.group(2)?.trim() ?: ""
                } else {
                    val m2 = P_QUOTE_META_FALLBACK.matcher(rawQuote)
                    if (m2.find()) {
                        meta = m2.group(1)?.trim()
                        body = m2.group(2)?.trim() ?: ""
                    }
                }

                if (!meta.isNullOrEmpty()) {
                    tvReplyQuoteTitle?.visibility = View.VISIBLE
                    tvReplyQuoteTitle?.text = meta
                } else {
                    tvReplyQuoteTitle?.visibility = View.GONE
                }

                val cleanBody = stripLeadingHtmlBreak(BBCodeUtil.stripHtmlColors(body))
                val spannedQuote = Html.fromHtml(
                    cleanBody, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvReplyQuote),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                val processedQuote = BBCodeUtil.stripForegroundColorSpans(spannedQuote)
                val trimmedBody = trimSpanned(processedQuote ?: spannedQuote)

                val primaryColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(itemView.context)

                val label = "原文"
                val prefixSpan = SpannableString(if (trimmedBody.isNotEmpty()) "$label " else label)
                prefixSpan.setSpan(
                    android.text.style.ForegroundColorSpan(primaryColor),
                    0, label.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                prefixSpan.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, label.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                val fullQuote = android.text.SpannableStringBuilder()
                fullQuote.append(prefixSpan)
                fullQuote.append(trimmedBody)

                tvReplyQuote.text = fullQuote
                attachCopyOnLongClick(tvReplyQuote)
            } else {
                layoutReplyQuote.visibility = View.GONE
                tvReplyQuote.text = ""
                tvReplyQuoteTitle?.visibility = View.GONE
            }

            val htmlContent = item.contentHtml
            val contentText = item.contentText

            val replyImageUrls = ArrayList<String>()
            val sourceHtml = if (!TextUtils.isEmpty(htmlContent)) {
                extractImagesFromHtml(htmlContent, replyImageUrls)
            } else if (!TextUtils.isEmpty(contentText)) {
                contentText!!
            } else {
                ""
            }

            if (item.imageUrls.isNotEmpty()) {
                for (u in item.imageUrls) {
                    if (isPostImageUrl(u) && replyImageUrls.none { isSameImage(it, u) }) {
                        replyImageUrls.add(u)
                    }
                }
            }

            var remainingSource = sourceHtml
            var editFooterText: String? = null
            val editPattern = Pattern.compile("(?is)(?:<(?:i|span|font|div|p|em)\\b[^>]*>|\\s)*本[帖贴]最后由[\\s\\S]*?编辑(?:\\s*</(?:i|span|font|div|p|em)>)*")
            val editMatcher = editPattern.matcher(sourceHtml)
            if (editMatcher.find()) {
                val matched = editMatcher.group(0) ?: ""
                var pureText = Regex("<[^>]+>").replace(matched, "")
                pureText = Regex("&nbsp;").replace(pureText, " ").trim()
                editFooterText = pureText
                remainingSource = stripLeadingHtmlBreak(editMatcher.replaceFirst(""))
            } else {
                remainingSource = stripLeadingHtmlBreak(sourceHtml)
            }

            // 编辑标记：从「本帖最后由 xxx 于 时间 编辑」中只取时间，跟到用户名同行显示
            val editTime = extractEditTime(editFooterText)
            if (!editTime.isNullOrEmpty()) {
                tvReplyEditTime?.visibility = View.VISIBLE
                tvReplyEditTime?.text = "$editTime 编辑"
            } else {
                tvReplyEditTime?.visibility = View.GONE
            }

            if (remainingSource.isNotEmpty()) {
                tvContent.visibility = View.VISIBLE
                // 用户要求：去除彩色字体，恢复正常文本颜色
                val cleanSource = BBCodeUtil.stripHtmlColors(remainingSource)
                val spannedSource = Html.fromHtml(
                    cleanSource, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvContent),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                val uncolored = BBCodeUtil.stripForegroundColorSpans(spannedSource)
                tvContent.text = trimSpanned(uncolored ?: spannedSource)
                setupClickableLinks(tvContent)
                attachCopyOnLongClick(tvContent)
            } else {
                tvContent.visibility = View.GONE
            }

            // 评论区图片展示（支持加载真实附件大图与点击预览）
            if (replyImageUrls.isNotEmpty()) {
                llReplyImages.removeAllViews()
                llReplyImages.visibility = View.VISIBLE
                val cardWidth = dpToPx(itemView.context, 160)
                for (imgUrl in replyImageUrls) {
                    val imageView = ImageView(itemView.context)
                    val lp = LinearLayout.LayoutParams(
                        cardWidth,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(itemView.context, 5)
                        bottomMargin = dpToPx(itemView.context, 5)
                    }
                    imageView.layoutParams = lp
                    imageView.adjustViewBounds = true
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.maxHeight = (cardWidth * 1.6f).toInt()
                    imageView.setBackgroundResource(R.drawable.bg_post_image_rounded)
                    imageView.clipToOutline = true
                    imageView.isClickable = true
                    imageView.isFocusable = true
                    setImageClick(itemView.context, imageView, imgUrl)
                    Glide.with(itemView.context)
                        .load(imgUrl)
                        .placeholder(ColorDrawable(itemView.context.getColor(R.color.background_secondary)))
                        .error(ColorDrawable(itemView.context.getColor(R.color.divider)))
                        .into(imageView)
                    llReplyImages.addView(imageView)
                }
            } else {
                llReplyImages.removeAllViews()
                llReplyImages.visibility = View.GONE
            }

            // 隐藏旧版头像旁的时间与回复图标已删除，统一由底栏呈现

            // 图六底栏：左侧显示「时间 来自 属地」
            val timeStr = item.time ?: ""
            val locStr = item.location ?: ""
            val fullTimeLoc = when {
                timeStr.isNotEmpty() && locStr.isNotEmpty() -> "$timeStr  $locStr"
                timeStr.isNotEmpty() -> timeStr
                else -> locStr
            }
            tvReplyBottomTime?.text = fullTimeLoc
            tvReplyBottomTime?.visibility = if (fullTimeLoc.isNotEmpty()) View.VISIBLE else View.GONE

            // 图六底栏：回复图标
            val author = item.author
            if (!TextUtils.isEmpty(author)) {
                btnReplyText?.visibility = View.VISIBLE
                btnReplyText?.setOnClickListener {
                    replyClickListener?.onReplyClick(item, bindingAdapterPosition)
                }
            } else {
                btnReplyText?.visibility = View.GONE
            }

            // 楼中楼折叠与展开控制（箭头图标：折叠=向下，展开=向上；数量放 contentDescription）
            val subCount = item.subReplies.size
            if (subCount > 0) {
                btnToggleSubReplies?.visibility = View.VISIBLE
                updateToggleArrow(item.isSubRepliesExpanded, subCount)
                btnToggleSubReplies?.setOnClickListener {
                    item.isSubRepliesExpanded = !item.isSubRepliesExpanded
                    updateToggleArrow(item.isSubRepliesExpanded, subCount)
                    bindSubReplies(item)
                }
            } else {
                btnToggleSubReplies?.visibility = View.GONE
            }

            // 渲染楼中楼子回复
            bindSubReplies(item)

            // 用户要求：废弃原卡片内的折叠胶囊，统一折叠到顶部标题栏
            layoutCollapsedHint?.visibility = View.GONE
        }

        /** 收起/展开：折叠时箭头朝下 +「N 条回复」，展开时箭头朝上 +「收起回复」。
         *  ic_arrow_right 默认朝右，旋转 90° 朝下、270° 朝上。 */
        private fun updateToggleArrow(expanded: Boolean, subCount: Int) {
            ivToggleArrow?.rotation = if (expanded) 270f else 90f
            tvToggleText?.text = if (expanded) "收起回复" else "$subCount 条回复"
            btnToggleSubReplies?.contentDescription =
                if (expanded) "收起回复" else "展开 $subCount 条回复"
        }

        private fun bindSubReplies(item: ReplyItem) {
            val container = layoutSubRepliesContainer ?: return
            val listLayout = llSubRepliesList ?: return
            listLayout.removeAllViews()

            if (item.subReplies.isEmpty() || !item.isSubRepliesExpanded) {
                container.visibility = View.GONE
                return
            }

            container.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(itemView.context)

            for (subItem in item.subReplies) {
                val subView = inflater.inflate(R.layout.item_sub_reply, listLayout, false)
                val ivSubAvatar = subView.findViewById<ImageView>(R.id.iv_sub_avatar)
                val tvSubAuthor = subView.findViewById<TextView>(R.id.tv_sub_author)
                val tvSubOpBadge = subView.findViewById<TextView>(R.id.tv_sub_op_badge)
                val tvSubContent = subView.findViewById<TextView>(R.id.tv_sub_content)
                val tvSubTimeLoc = subView.findViewById<TextView>(R.id.tv_sub_time_location)
                val btnSubReply = subView.findViewById<android.view.View>(R.id.btn_sub_reply)

                if (!TextUtils.isEmpty(subItem.avatarUrl)) {
                    Glide.with(ivSubAvatar.context)
                        .load(subItem.avatarUrl)
                        .transform(CircleCrop())
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .into(ivSubAvatar)
                } else {
                    ivSubAvatar.setImageResource(R.drawable.ic_account)
                }

                ivSubAvatar.setOnClickListener {
                    if (!TextUtils.isEmpty(subItem.authorUid)) {
                        userClickListener?.onUserClick(subItem, bindingAdapterPosition)
                    }
                }
                tvSubAuthor.setOnClickListener {
                    if (!TextUtils.isEmpty(subItem.authorUid)) {
                        userClickListener?.onUserClick(subItem, bindingAdapterPosition)
                    }
                }

                tvSubAuthor.text = subItem.author ?: "匿名"
                tvSubOpBadge.visibility = if (subItem.isOP) View.VISIBLE else View.GONE

                // 子回复正文：优先按 HTML 富文本渲染（保留表情图等内联元素）。
                // 注意：纯表情/纯图片帖子的 contentText 会是「空串」而非 null，
                // 不能再用 ?: 兜底，否则内容会被判空而整段丢失。
                val subHtml = subItem.contentHtml
                val subBody: CharSequence? = if (!TextUtils.isEmpty(subHtml)) {
                    val rendered = Html.fromHtml(
                        BBCodeUtil.stripHtmlColors(subHtml),
                        Html.FROM_HTML_MODE_COMPACT,
                        createInlineImageGetter(tvSubContent),
                        BBCodeUtil.createTagHandler(itemView.context)
                    )
                    BBCodeUtil.stripForegroundColorSpans(rendered) ?: rendered
                } else {
                    subItem.contentText
                }

                val inReplyTo = subItem.inReplyToName
                val subSb = android.text.SpannableStringBuilder()
                if (!TextUtils.isEmpty(inReplyTo)) {
                    // 「回复 用户名：」保持常规正文颜色，不做主题色高亮
                    subSb.append("回复 $inReplyTo：")
                }
                if (!TextUtils.isEmpty(subBody)) {
                    subSb.append(trimSpanned(subBody!!))
                }
                tvSubContent.text = subSb
                tvSubContent.setOnLongClickListener {
                    replyLongClickListener?.onReplyLongClick(subItem, bindingAdapterPosition)
                    true
                }

                val sTime = subItem.time ?: ""
                val sLoc = subItem.location ?: ""
                val fullSubTime = when {
                    sTime.isNotEmpty() && sLoc.isNotEmpty() -> "$sTime  $sLoc"
                    sTime.isNotEmpty() -> sTime
                    else -> sLoc
                }
                tvSubTimeLoc.text = fullSubTime

                btnSubReply.setOnClickListener {
                    replyClickListener?.onReplyClick(subItem, bindingAdapterPosition)
                }

                subView.setOnLongClickListener {
                    replyLongClickListener?.onReplyLongClick(subItem, bindingAdapterPosition)
                    true
                }

                listLayout.addView(subView)
            }
        }
    }

    companion object {

        /** item 类型：页首正文头 / 普通回复 / 底部状态行 */
        private const val TYPE_HEADER = 0
        private const val TYPE_REPLY = 1
        private const val TYPE_FOOTER = 2

        /** 正文头在适配器里的身份标识（视口锚点用）。 */
        private const val IDENTITY_HEADER = "header"

        /** 正文头占据的 item 数（有 header 时为 1）。 */
        const val HEADER_ITEM_COUNT = 1

        /** 归一化后长度不超过此值的回复视为“无信息量短回复”，整批折叠。 */
        private const val BATCH_MAX_LEN = 6

        /** 短回复批量组的分组键前缀。 */
        private const val BATCH_PREFIX = "batch_"

        /** 纯表情批量组的后缀。 */
        private const val BATCH_EMOJI_SUFFIX = "emoji"

        /** HTML 扫描上限，避免超长内容做无谓的正则扫描。 */
        private const val MAX_HTML_SCAN = 4000

        /** 超过此长度的文本不作为分组入口展示（避免胶囊条文案过长）。 */
        private const val MAX_LABEL_TEXT_LEN = 60

        /** Discuz 内联表情的特征词。 */
        private val EXPRESSION_HINTS = listOf(
            "smiley", "emoticon", "/static/image/", "face", "stamp", "magic"
        )

        private val IMG_TAG_RE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val IMG_SRC_RE = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        /**
         * 归一化文本，用于“看着一样即归为一组”。
         * 先把全角字符折成半角（ＡＢＣ１２３、全角标点、全角空格），
         * 再保留字母/数字、其余一律丢弃，并统一小写。
         */
        private fun normalizeText(s: String): String {
            val sb = StringBuilder(s.length)
            for (raw in s) {
                val ch = when {
                    // 全角 ！~～（U+FF01..U+FF5E）整体平移到 ASCII
                    raw.code in 0xFF01..0xFF5E -> (raw.code - 0xFEE0).toChar()
                    // 全角空格
                    raw.code == 0x3000 -> ' '
                    else -> raw
                }
                if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            }
            return sb.toString()
        }

        private fun isBatchGroupKey(key: String): Boolean = key.startsWith(BATCH_PREFIX)

        private fun groupLabelOf(key: String): String {
            return when {
                key == BATCH_PREFIX + BATCH_EMOJI_SUFFIX -> "纯表情回复"
                isBatchGroupKey(key) -> "短回复"
                else -> "相同回复"
            }
        }

        /**
         * 为 TextView 设置可点击链接（蓝色高亮 + 可点击跳转）
         * ★ 统一方案：与 ThreadDetailActivity.setupClickableLinks 保持一致
         *
         * 注意：此方法不会覆盖已有文本，只会在现有 Spannable 上添加链接处理
         */
        /**
         * build71: 长按复制回复内容。
         * 之前 setupClickableLinks 里写死 setLongClickable(false)(为了不拦截链接点击),
         * 导致别人回复根本没法复制。长按和单击是两个事件,挂长按不影响链接跳转。
         */
        private fun attachCopyOnLongClick(textView: TextView?) {
            if (textView == null) return
            textView.isLongClickable = true
            textView.setOnLongClickListener {
                val cs = textView.text
                val text = cs?.toString()?.trim() ?: ""
                if (text.isEmpty()) return@setOnLongClickListener false
                try {
                    val cm = textView.context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("回复内容", text))
                        com.solosu.mtforum.util.ToastUtil.makeText(
                            textView.context, "已复制回复内容",
                            com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
                        ).show()
                        return@setOnLongClickListener true
                    }
                } catch (ignored: Exception) {
                }
                false
            }
        }

        private fun setupClickableLinks(textView: TextView?) {
            if (textView == null) return

            // ★ 关键：禁用文本选择和长按，避免拦截触摸事件
            textView.setTextIsSelectable(false)
            textView.isFocusable = false
            textView.isClickable = false
            textView.isLongClickable = false
            textView.highlightColor = 0x332196F3

            // 获取当前文本（可能已经是 Spannable）
            var value: CharSequence? = textView.text
            if (value !is Spannable) {
                // 如果不是 Spannable，创建一个并设置回 TextView
                value = SpannableString(value ?: "")
                textView.setText(value, TextView.BufferType.SPANNABLE)
            }

            // 强制重新获取 Spannable（确保是最新的）
            val spannable = textView.text as Spannable

            // 使用自定义正则识别URL，点击时打开链接
            val urlPattern = Pattern.compile(
                "(?<!\\w)" +  // 前面不是单词字符
                        "(?:" +
                        "https?://[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+" +  // http/https开头的URL
                        "|" +
                        "www\\.[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+" +      // www开头的URL
                        ")" +
                        "(?<![,.;:!?)>])",  // 后面不是标点符号
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(textView.context)

            FixNestedScrollLinkMovementMethod.matcherLinkify(
                spannable,
                urlPattern,
                { url -> url },  // URL处理器：直接返回原始URL
                { url -> openContentLink(url, textView.context) },  // 点击处理器：打开链接
                themeColor
            )

            // Html.fromHtml() 产生 URLSpan；统一替换为应用自己的 ClickableSpan
            val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
            for (oldSpan in urlSpans) {
                var targetUrl = oldSpan.url
                // 补全相对路径
                if (targetUrl.startsWith("//")) {
                    targetUrl = "https:$targetUrl"
                } else if (targetUrl.startsWith("/")) {
                    targetUrl = HttpClient.BASE_URL + targetUrl.substring(1)
                } else if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    targetUrl = HttpClient.BASE_URL + targetUrl
                }

                val start = spannable.getSpanStart(oldSpan)
                val end = spannable.getSpanEnd(oldSpan)
                val flags = spannable.getSpanFlags(oldSpan)
                spannable.removeSpan(oldSpan)
                if (start >= 0 && end > start && !TextUtils.isEmpty(targetUrl)) {
                    val finalUrl = targetUrl
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openContentLink(finalUrl, widget.context)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = themeColor
                            ds.isUnderlineText = true
                        }
                    }, start, end, flags)
                }
            }

            // ★ 关键修复：不要再次调用 setText()，直接更新 movementMethod
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.autoLinkMask = 0
        }

        /**
         * 打开链接：直接交给系统默认浏览器处理，不再进入应用内 WebView。
         */
        private fun openContentLink(url: String?, context: Context?) {
            if (TextUtils.isEmpty(url) || context == null) return
            try {
                val lower = url!!.lowercase(java.util.Locale.ROOT)
                val isForumLink = lower.contains("bbs.binmt.cc")

                if (isForumLink) {
                    // 论坛帖子链接
                    val threadMatcher = Pattern.compile("thread[-=]?(\\d+)").matcher(lower)
                    if (threadMatcher.find()) {
                        NavigationHelper.openThread(context, threadMatcher.group(1))
                        return
                    }
                    // 用户空间链接
                    val uidMatcher = Pattern.compile("(?:uid[-=]|(?<=[?&])uid=)(\\d+)").matcher(lower)
                    if (uidMatcher.find()) {
                        val intent = Intent(context, UserProfileActivity::class.java)
                        intent.putExtra("uid", uidMatcher.group(1))
                        context.startActivity(intent)
                        return
                    }
                    val usernameMatcher = Pattern.compile("space-username-([^./?&]+)").matcher(lower)
                    if (usernameMatcher.find()) {
                        val intent = Intent(context, UserProfileActivity::class.java)
                        intent.putExtra("username", usernameMatcher.group(1))
                        context.startActivity(intent)
                        return
                    }
                }
                // 非论坛链接,用浏览器打开
                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browserIntent)
            } catch (ignored: Exception) {
            }
        }

        /**
         * 为评论中的图片 ImageView 添加点击预览监听
         */
        private fun setImageClick(context: Context?, imageView: ImageView, imgUrl: String?) {
            imageView.setOnClickListener {
                if (context != null && !TextUtils.isEmpty(imgUrl)) {
                    val intent = Intent(context, ImagePreviewActivity::class.java)
                    intent.putExtra("image_url", imgUrl)
                    context.startActivity(intent)
                }
            }
        }

        /**
         * 从 HTML 内容中提取所有 img 标签的 src URL，并返回去掉 img 标签后的纯 HTML
         */
        private fun isSmileyOrIcon(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("smiley") || lower.contains("emoticon")
                    || lower.contains("face") || lower.contains("/static/image/smiley")
                    || lower.contains("stamp") || lower.contains("magic")
                    || lower.contains("mini") || lower.contains("icon")
                    || lower.contains("common_")
        }

        private fun isPostImageUrl(url: String?): Boolean {
            if (TextUtils.isEmpty(url)) return false
            val lower = url!!.lowercase(Locale.ROOT)
            if (lower.contains("none.gif") || lower.contains("none.png") || lower.contains("blank.gif")
                || lower.contains("loading") || lower.contains("avatar.php")
                || lower.contains("/static/image/common/") || lower.contains("/static/image/filetype/")
                || lower.contains("/static/image/smiley/")) {
                return false
            }
            if (lower.contains("smiley") || lower.contains("emoticon")) {
                return false
            }
            return true
        }

        private fun extractAidFromUrl(url: String): String {
            val m = Pattern.compile("(?i)[?&]aid=([^&#]+)").matcher(url)
            return if (m.find()) m.group(1) ?: "" else ""
        }

        private fun getImageCanonicalKey(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val clean = url.trim().lowercase(Locale.ROOT)
            val aid = extractAidFromUrl(clean)
            if (aid.isNotEmpty()) return "aid:$aid"
            val noQuery = clean.substringBefore("?").substringBefore("#")
            val stripped = noQuery
                .replace(".thumb.jpg", "")
                .replace(".thumb.png", "")
                .replace(".middle.jpg", "")
                .replace(".middle.png", "")
                .replace("_thumb.jpg", ".jpg")
                .replace("_thumb.png", ".png")
            val lastSlash = stripped.lastIndexOf('/')
            val filename = if (lastSlash >= 0) stripped.substring(lastSlash + 1) else stripped
            if (filename.endsWith(".php") || filename.isEmpty()) {
                return clean
            }
            return filename
        }

        private fun isSameImage(url1: String?, url2: String?): Boolean {
            if (url1.isNullOrBlank() || url2.isNullOrBlank()) return false
            if (url1.equals(url2, ignoreCase = true)) return true
            val key1 = getImageCanonicalKey(url1)
            val key2 = getImageCanonicalKey(url2)
            return key1.isNotEmpty() && key1 == key2
        }

        private fun firstNonEmptyAttr(element: org.jsoup.nodes.Element, vararg attrNames: String): String? {
            for (attr in attrNames) {
                if (element.hasAttr(attr)) {
                    val value = element.attr(attr).trim()
                    if (value.isNotEmpty()
                        && !value.contains("none.gif", ignoreCase = true)
                        && !value.contains("none.png", ignoreCase = true)
                        && !value.contains("blank.gif", ignoreCase = true)) {
                        return value
                    }
                }
            }
            return null
        }

        private fun extractImagesFromHtml(html: String?, outImageUrls: MutableList<String>): String {
            if (TextUtils.isEmpty(html)) {
                return ""
            }
            try {
                val doc = org.jsoup.Jsoup.parseBodyFragment(html!!)
                for (ignoreOp in doc.select("ignore_js_op")) {
                    ignoreOp.unwrap()
                }
                val imgs = doc.select("img")
                for (img in imgs) {
                    val realUrl = firstNonEmptyAttr(
                        img,
                        "zoomfile", "file", "comiis_loadimages", "data-original", "data-src",
                        "data-file", "data-lazy-src", "src"
                    )
                    val fullUrl = normalizeImageUrl(realUrl)
                    if (fullUrl != null && isPostImageUrl(fullUrl)) {
                        if (outImageUrls.none { isSameImage(it, fullUrl) }) {
                            outImageUrls.add(fullUrl)
                        }
                        img.remove()
                    } else if (fullUrl != null && isSmileyOrIcon(fullUrl)) {
                        img.attr("src", fullUrl)
                    } else {
                        img.remove()
                    }
                }
                return doc.body().html()
            } catch (e: Exception) {
                return html ?: ""
            }
        }

        /**
         * 补全图片URL：处理 //、/ 和 ./ 开头的相对路径
         */
        private fun normalizeImageUrl(url: String?): String? {
            if (TextUtils.isEmpty(url)) return null
            return if (url!!.startsWith("//")) {
                "https:$url"
            } else if (url.startsWith("/")) {
                HttpClient.BASE_URL + url.substring(1)
            } else if (url.startsWith("./")) {
                HttpClient.BASE_URL + url.substring(2)
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                // 其他情况（不含协议的相对路径如 "data/attachment/..."）
                HttpClient.BASE_URL + url
            }
        }

        /**
         * 从 HTML 内容中提取图片 URL 列表，并用 Glide 加载到 llReplyImages 布局中
         */
        private fun loadReplyImages(context: Context, html: String?, container: LinearLayout) {
            val urls = ArrayList<String>()
            extractImagesFromHtml(html, urls)
            if (urls.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            container.removeAllViews()
            container.visibility = View.VISIBLE
            val maxImgWidth = getMaxImageWidth(context)
            for (imgUrl in urls) {
                val imageView = ImageView(context)
                imageView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                imageView.adjustViewBounds = true
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                imageView.setBackgroundResource(R.drawable.bg_post_image_rounded)
                imageView.clipToOutline = true
                imageView.maxWidth = maxImgWidth
                imageView.maxHeight = (maxImgWidth * 1.2f).toInt()
                // ★ 添加点击预览
                imageView.isClickable = true
                imageView.isFocusable = true
                setImageClick(context, imageView, imgUrl)
                Glide.with(context)
                    .load(imgUrl)
                    .placeholder(ColorDrawable(context.getColor(R.color.background_secondary)))
                    .error(ColorDrawable(context.getColor(R.color.divider)))
                    .into(imageView)
                container.addView(imageView)
            }
        }

        /**
         * 获取评论图片的最大显示宽度（屏幕宽度的50%，最多不超过180dp）
         */
        private fun getMaxImageWidth(context: Context): Int {
            val screenWidth = Resources.getSystem().displayMetrics.widthPixels
            val maxDp = 180
            val maxPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
            val widthHalf = (screenWidth * 0.52f).toInt()
            return Math.min(widthHalf, maxPx)
        }

        // ==================== 内联图片渲染（表情等） ====================

        /**
         * 为 Html.fromHtml 提供 ImageGetter，用 Glide 异步加载内联图片（表情等小图）
         * 修复：旧实现 Glide 加载完成后未回填 ImageSpan 持有的占位 Drawable，导致内联图永远透明。
         * 现改用 UrlDrawable 占位 + setReal 回填 + 宿主 TextView 重排。表情限 24dp，大图限宽。
         */
        private fun createInlineImageGetter(targetView: TextView): Html.ImageGetter {
            return Html.ImageGetter { source ->
                var imgUrl = source
                if (imgUrl.startsWith("//")) {
                    imgUrl = "https:$imgUrl"
                } else if (imgUrl.startsWith("/")) {
                    imgUrl = HttpClient.BASE_URL + imgUrl.substring(1)
                } else if (!imgUrl.startsWith("http")) {
                    imgUrl = HttpClient.BASE_URL + imgUrl
                }

                val tv = targetView
                val placeholder = UrlDrawable(tv, dpToPx(tv.context, 24))
                val maxW = getMaxImageWidth(tv.context)

                Glide.with(tv.context)
                    .load(imgUrl)
                    .into(object : CustomTarget<Drawable>() {
                        override fun onResourceReady(
                            resource: Drawable,
                            transition: Transition<in Drawable>?
                        ) {
                            var w = resource.intrinsicWidth
                            var h = resource.intrinsicHeight
                            val emotSize = dpToPx(tv.context, 24)
                            val maxSize = if (maxW > 0) maxW else dpToPx(tv.context, 320)
                            if (w <= 0) w = emotSize
                            if (h <= 0) h = emotSize
                            // 表情类小图（≤32dp）保持原尺寸；大图限宽
                            if (w <= dpToPx(tv.context, 32)) {
                                if (w > emotSize || h > emotSize) {
                                    val r = emotSize.toFloat() / Math.max(w, h)
                                    w = (w * r).toInt()
                                    h = (h * r).toInt()
                                }
                            } else if (w > maxSize) {
                                h = (h.toLong() * maxSize / Math.max(1, w)).toInt()
                                w = maxSize
                            }
                            resource.setBounds(0, 0, w, h)
                            // item 内联图：只回填，不 setText 重排（避免回收复用时乱跳）
                            placeholder.setRealNoRelayout(resource)
                        }

                        override fun onLoadCleared(placeholderD: Drawable?) {
                        }
                    })
                placeholder
            }
        }

        private val P_QUOTE_META = Pattern.compile(
            "^((?:回复\\s+)?.+?\\s+发表于\\s+(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?|(?:\\d+\\s*(?:秒|分钟|小时|天)前)|(?:半小时前)|(?:昨天|前天)\\s+\\d{1,2}:\\d{2}(?::\\d{2})?))\\s*([\\s\\S]*)",
            Pattern.CASE_INSENSITIVE
        )
        private val P_QUOTE_META_FALLBACK = Pattern.compile(
            "^((?:回复\\s+)?.+?\\s+发表于[^\\r\\n]+)[\\r\\n]+\\s*([\\s\\S]*)",
            Pattern.CASE_INSENSITIVE
        )

        private fun stripLeadingHtmlBreak(html: String?): String {
            if (html.isNullOrEmpty()) return ""
            var s = html.trim()
            val leadPattern = Pattern.compile(
                "^(?:\\s|&nbsp;|<br\\s*/?>|<p>(?:\\s|&nbsp;|<br\\s*/?>)*</p>|<div>(?:\\s|&nbsp;|<br\\s*/?>)*</div>)+",
                Pattern.CASE_INSENSITIVE
            )
            while (true) {
                val m = leadPattern.matcher(s)
                if (m.find()) {
                    s = s.substring(m.end()).trim()
                } else {
                    break
                }
            }
            return s
        }

        private fun trimSpanned(spanned: CharSequence): CharSequence {
            var start = 0
            var end = spanned.length
            while (start < end && (spanned[start].isWhitespace() || spanned[start] == '\u00A0')) {
                start++
            }
            while (end > start && (spanned[end - 1].isWhitespace() || spanned[end - 1] == '\u00A0')) {
                end--
            }
            if (start == 0 && end == spanned.length) {
                return spanned
            }
            return spanned.subSequence(start, end)
        }

        private fun dpToPx(context: Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }

        /**
         * 从「本帖最后由 xxx 于 2026-10-4 21:45 编辑」里取出时间部分（含前后空白）。
         * 取不到时返回 null，避免把整句原样降级显示。
         */
        private fun extractEditTime(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val m = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?").find(text)
            return m?.value
        }
    }
}
