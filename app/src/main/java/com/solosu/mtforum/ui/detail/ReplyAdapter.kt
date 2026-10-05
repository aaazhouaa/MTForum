package com.solosu.mtforum.ui.detail

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
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

            // 引用块里带明确 pid 时，就说明消息指向的是「某一条具体楼层」。
            // 但 pid 可能缺失（模板不输出引用链接）或指向未加载的楼层，
            // 此时必须降级推断（见下方 matchByQuotedContent 的优先级说明）。
            val quotedPid = item.quotedPid
            if (!quotedPid.isNullOrEmpty()) {
                val cand = pidMap[quotedPid]
                if (cand != null && cand !== item) target = cand
            }
            if (target == null) {
                // pid 缺失（或该楼层不在已加载范围内）时只能降级推断，顺序按证据强度，
                // 不能颠倒：昵称是最弱的证据——回复自己时，自己的每条楼层都同名，
                // 只按「昵称 + 最近一条」猜，必然挂到别的同名楼层上。
                // 被引用楼层必须排在引用者之前（orderIndex 约束）。
                target = matchByQuotedContent(item, all)
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
                        // 子回复有自己的宿主楼层，把它当父楼层会让这条回复
                        // 显示成「回复 xx：」的二级回复，语义完全错位。
                        target = nameMap[name]?.lastOrNull {
                            it !== item && !it.isSubReply && it.orderIndex < item.orderIndex
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

    /**
     * pid 缺失时的降级匹配：拿引用块里的正文去比对各楼层的正文。
     *
     * 昵称与 uid 都区分不了「同一个人发的多条楼层」，而引用块天然携带被引用楼的原话，
     * 内容匹配是这里唯一能精确到楼层的证据。用最长公共子串而非前缀比较：
     * 引用块开头还挂着「回复 X 发表于 …」这段 meta，前缀比对上必然对不齐。
     */
    private fun matchByQuotedContent(item: ReplyItem, all: List<ReplyItem>): ReplyItem? {
        val quoted = normalizeForMatch(item.quotedContentText ?: return null)
        // 太短的引用（纯表情/单个词）区分不了楼层，宁可交给后面的弱证据
        if (quoted.length < MATCH_MIN_CHARS) return null
        val qa = quoted.toCharArray()
        var best: ReplyItem? = null
        var bestScore = 0
        for (cand in all) {
            if (cand === item || cand.orderIndex >= item.orderIndex) continue
            val body = normalizeForMatch(cand.contentText)
            if (body.isEmpty()) continue
            val score = longestCommonSubstring(qa, body.toCharArray())
            if (score > bestScore) {
                bestScore = score
                best = cand
            }
        }
        if (best == null) return null
        // 引文是被引楼正文的连续片段，命中时重合度应接近整段引文；
        // 只重合寥寥几字更像巧合同词，放弃比挂错好。
        return if (bestScore >= MATCH_MIN_CHARS && bestScore >= quoted.length / 3) best else null
    }

    /** 提取可比较的纯文本：把引文里残留的标签与 BBCode 都清掉，再压缩空白。 */
    private fun normalizeForMatch(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = Regex("(?s)<[^>]+>").replace(raw, "")
        s = Regex("(?i)\\[/?(?:quote|free|hide|code|color|url|size|b|i|u|font|align)[^\\]]*\\]").replace(s, "")
        return Regex("[\\s\\u00A0]").replace(s, "")
    }

    /** 两个字符序列的最长公共子串长度（滚动数组，空间 O(m)）。 */
    private fun longestCommonSubstring(a: CharArray, b: CharArray): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.size + 1)
        var cur = IntArray(b.size + 1)
        var best = 0
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            val swap = prev
            prev = cur
            cur = swap
            java.util.Arrays.fill(cur, 0)
        }
        return best
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
     *
     * 注意：本站 comiis 移动版引用块不带 pid/uid 链接，昵称是唯一可用的定位线索；
     * 因此不能排除“名字等于自己”的情况——用户引用自己更早的楼层很常见，
     * 一旦排除，这类回复就永远归不上楼中楼（父子方向由 orderIndex 约束保证）。
     */
    private fun extractQuotedNameFromQuote(item: ReplyItem): String? {
        val quote = item.quotedContentText ?: return null
        // “回复 xxx 发表于” / “xxx 发表于”
        Regex("(?:回复\\s*)?([^\\n]{1,24}?)\\s*发表于").find(quote)?.let {
            val name = it.groupValues[1].trim()
                .removePrefix("回复").trim()
                .removePrefix("@").trim()
            if (name.isNotEmpty()) return name
        }
        // “回复 xxx” 且没有“发表于”（部分手机版只带这句）
        Regex("回复\\s*@?([^\\s，。,:：]{1,24})").find(quote)?.let {
            val name = it.groupValues[1].trim()
            if (name.isNotEmpty()) return name
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

            // 编辑标记：剥离「本帖最后由 xxx 于 时间 编辑」整句，只把时间跟到用户名同行显示
            val (remainingSource, editTime) = splitEditMarker(sourceHtml)
            if (!editTime.isNullOrEmpty()) {
                tvReplyEditTime?.visibility = View.VISIBLE
                tvReplyEditTime?.text = "$editTime 编辑"
            } else {
                tvReplyEditTime?.visibility = View.GONE
            }

            if (remainingSource.isNotEmpty()) {
                tvContent.visibility = View.VISIBLE
                // 附件图已被 extractImagesFromHtml 抽到下方独立图片区，正文里留下它们原本占位的
                // 连续 <br> 与空块；不压缩就会在文字与图片之间撑出一大块空白。
                val collapsedSource = collapseReplyHtml(remainingSource)
                // 用户要求：去除彩色字体，恢复正常文本颜色
                val cleanSource = BBCodeUtil.stripHtmlColors(collapsedSource)
                val spannedSource = Html.fromHtml(
                    cleanSource, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvContent),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                val uncolored = BBCodeUtil.stripForegroundColorSpans(spannedSource)
                tvContent.text = trimSpanned(uncolored ?: spannedSource)
                setupClickableLinks(tvContent)
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
                    imageView.isLongClickable = true
                    imageView.setOnLongClickListener {
                        replyLongClickListener?.onReplyLongClick(item, bindingAdapterPosition)
                        true
                    }
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

            // 长按该条评论占用的任意区域(整项/正文/引用块) -> 统一弹出操作菜单。
            // 必须放在 setupClickableLinks 之后：那里为了不拦截链接点击会把 TextView 设回
            // 不可长按，先挂就会被失效。
            val longPress = View.OnLongClickListener {
                replyLongClickListener?.onReplyLongClick(currentItem, bindingAdapterPosition)
                true
            }
            itemView.isLongClickable = true
            itemView.setOnLongClickListener(longPress)
            tvContent.isLongClickable = true
            tvContent.setOnLongClickListener(longPress)
            if (layoutReplyQuote.visibility == View.VISIBLE) {
                tvReplyQuote.isLongClickable = true
                tvReplyQuote.setOnLongClickListener(longPress)
            }
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
                val tvSubEditTime = subView.findViewById<TextView>(R.id.tv_sub_edit_time)
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
                // 楼中楼同样剥离「本帖最后由 xxx 于 时间 编辑」整句，时间单独显示在用户名行末
                val subEditSplit = if (TextUtils.isEmpty(subHtml)) null else splitEditMarker(subHtml)
                // 子回复的 HTML 未经过主帖那套归一化：附件图真实地址在 file/zoomfile 上（src 只是 none.gif 占位），
                // 且可能残留连续换行空段落；先恢复真实地址并压缩空白再交给 fromHtml
                val subSource = if (subEditSplit != null) {
                    collapseReplyHtml(restoreInlineImageSources(subEditSplit.first))
                } else {
                    ""
                }
                val subBody: CharSequence? = if (subEditSplit != null) {
                    val rendered = Html.fromHtml(
                        BBCodeUtil.stripHtmlColors(subSource),
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
                    // 「回复 用户名：」弱化为小一号斜体前缀，颜色仍跟随正文
                    val prefix = "回复 $inReplyTo："
                    subSb.append(prefix)
                    subSb.setSpan(
                        RelativeSizeSpan(0.85f), 0, prefix.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    subSb.setSpan(
                        StyleSpan(Typeface.ITALIC), 0, prefix.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (!TextUtils.isEmpty(subBody)) {
                    subSb.append(trimSpanned(subBody!!))
                }
                tvSubContent.text = subSb
                // 子回复正文里的附件配图是内联 ImageSpan，默认不可点击，这里挂上点击放大
                attachInlineImageClick(tvSubContent)
                val subEditTime = subEditSplit?.second
                if (!subEditTime.isNullOrEmpty()) {
                    tvSubEditTime.visibility = View.VISIBLE
                    tvSubEditTime.text = "$subEditTime 编辑"
                } else {
                    tvSubEditTime.visibility = View.GONE
                }
                tvSubContent.setOnLongClickListener {
                    replyLongClickListener?.onReplyLongClick(subItem, bindingAdapterPosition)
                    true
                }
                tvSubAuthor.setOnLongClickListener {
                    replyLongClickListener?.onReplyLongClick(subItem, bindingAdapterPosition)
                    true
                }
                ivSubAvatar.setOnLongClickListener {
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

        /** 归并时内容匹配的最短重合字符数；低于此值视为巧合同词。 */
        private const val MATCH_MIN_CHARS = 6

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

        /**
         * 楼中楼正文里的图片是 Html.ImageGetter 生成的内联 ImageSpan，自身不响应点击，
         * 这里按点击坐标命中 ImageSpan 时打开全屏预览。不设置 movementMethod，
         * 避免抢占长按菜单与列表滚动。
         */
        private fun attachInlineImageClick(textView: TextView) {
            val slop = android.view.ViewConfiguration.get(textView.context).scaledTouchSlop
            textView.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var downTime = 0L

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downTime = System.currentTimeMillis()
                        }
                        MotionEvent.ACTION_UP -> {
                            val moved = Math.abs(event.x - downX) > slop ||
                                    Math.abs(event.y - downY) > slop
                            val longPress = System.currentTimeMillis() - downTime > 400
                            if (!moved && !longPress) {
                                val url = findInlineImageUrlAt(textView, event.x, event.y)
                                if (url != null) {
                                    val intent = Intent(v.context, ImagePreviewActivity::class.java)
                                    intent.putExtra("image_url", url)
                                    v.context.startActivity(intent)
                                    return true
                                }
                            }
                        }
                    }
                    return false
                }
            })
        }

        /** 返回坐标处的正文内联图片地址；表情不参与点击。 */
        private fun findInlineImageUrlAt(textView: TextView, x: Float, y: Float): String? {
            val layout = textView.layout ?: return null
            val text = textView.text as? Spanned ?: return null
            for (span in text.getSpans(0, text.length, ImageSpan::class.java)) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                if (start < 0 || end < 0) continue
                val top = layout.getLineTop(layout.getLineForOffset(start))
                val bottom = layout.getLineBottom(layout.getLineForOffset(end))
                val left = layout.getPrimaryHorizontal(start)
                val right = layout.getPrimaryHorizontal(end)
                if (x in left..right && y >= top && y <= bottom) {
                    val url = normalizeImageUrl(span.source) ?: continue
                    if (isSmileyOrIcon(url)) continue
                    return url
                }
            }
            return null
        }

        /**
         * 楼中楼正文里 Discuz 附件图的真实地址存在 file/zoomfile 等属性上，
         * 而 src 往往是 static/image/common/none.gif 占位；Html.ImageGetter 只能拿到 src，
         * 直接渲染会去加载占位图并留下一块空白。这里把真实地址写回 src。
         */
        private fun restoreInlineImageSources(html: String?): String {
            if (html.isNullOrEmpty() || !html.contains("<img", ignoreCase = true)) return html ?: ""
            return try {
                val doc = org.jsoup.Jsoup.parseBodyFragment(html)
                for (ignoreOp in doc.select("ignore_js_op")) {
                    ignoreOp.unwrap()
                }
                for (img in doc.select("img")) {
                    val realUrl = firstNonEmptyAttr(
                        img,
                        "zoomfile", "file", "comiis_loadimages", "data-original",
                        "data-src", "data-file", "data-lazy-src", "src"
                    )
                    val fullUrl = normalizeImageUrl(realUrl)
                    if (fullUrl.isNullOrEmpty()) {
                        img.remove()
                    } else {
                        img.attr("src", fullUrl)
                    }
                }
                doc.body().html()
            } catch (e: Exception) {
                html
            }
        }

        /** 压缩楼中楼正文的连续换行与空段落（主帖正文在 renderContentSections 里做过同样处理）。 */
        /**
         * 压缩正文 HTML 里的连续换行与空块。
         * 主评论与楼中楼子回复共用：图片抽走后残留的占位换行、模板自带的空段落
         * 都会渲染成一片空白，必须统一压掉。
         */
        private fun collapseReplyHtml(html: String): String {
            if (html.isEmpty()) return html
            return Regex("(?i)(?:<br\\s*/?>\\s*){2,}").replace(html, "<br>")
                .replace(Regex("(?i)<p\\s*>\\s*(?:&nbsp;|&#160;|\\s)*</p>"), "")
                .replace(Regex("(?i)<div[^>]*>\\s*(?:&nbsp;|&#160;|<br\\s*/?>|\\s)*</div>"), "")
                .replace(Regex("(?i)(?:\\r?\\n\\s*){3,}"), "\n\n")
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
                            // 站内表情的声明尺寸只有 20x20 像素，若按原始像素显示会远小于 dp 目标值，
                            // 这里对表情一律等比缩放到 emotSize；大图仍只限宽
                            if (w <= dpToPx(tv.context, 32)) {
                                val r = emotSize.toFloat() / Math.max(w, h)
                                w = Math.max(1, (w * r).toInt())
                                h = Math.max(1, (h * r).toInt())
                            } else if (w > maxSize) {
                                h = (h.toLong() * maxSize / Math.max(1, w)).toInt()
                                w = maxSize
                            }
                            resource.setBounds(0, 0, w, h)
                            // item 内联图：只回填，不 setText 重排（避免回收复用时乱跳）
                            placeholder.setRealNoRelayout(resource)
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            // 加载失败时把占位塌缩为零尺寸并请求重排，避免行内留下一块透明空白
                            placeholder.setBounds(0, 0, 0, 0)
                            tv.requestLayout()
                            tv.postInvalidate()
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

        /** 正文里的「本帖最后由 xxx 于 <时间> 编辑」整句（含包裹标签与前后空白）。 */
        private val P_EDIT_MARKER = Pattern.compile(
            "(?is)(?:<(?:i|span|font|div|p|em)\\b[^>]*>|\\s)*本[帖贴]最后由[\\s\\S]*?编辑(?:\\s*</(?:i|span|font|div|p|em)>)*"
        )

        /**
         * 剥离正文中的「本帖最后由 xxx 于 <时间> 编辑」整句。
         * 返回 (剩余正文 HTML, 时间文本)；未出现该标记时时间返回 null。
         */
        private fun splitEditMarker(html: String?): Pair<String, String?> {
            if (html.isNullOrEmpty()) return (html ?: "") to null
            val m = P_EDIT_MARKER.matcher(html)
            if (!m.find()) return stripLeadingHtmlBreak(html) to null
            var pureText = Regex("<[^>]+>").replace(m.group(0) ?: "", "")
            pureText = Regex("&nbsp;").replace(pureText, " ").trim()
            return stripLeadingHtmlBreak(m.replaceFirst("")) to extractEditTime(pureText)
        }

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
