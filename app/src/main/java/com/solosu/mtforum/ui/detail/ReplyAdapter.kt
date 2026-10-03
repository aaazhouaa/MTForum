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
        rebuildDisplayList()
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
        private val tvTime: TextView = itemView.findViewById(R.id.tv_reply_time)
        private val tvReplyEditFooter: TextView? = itemView.findViewById(R.id.tv_reply_edit_footer)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_reply_content)
        private val layoutReplyQuote: LinearLayout = itemView.findViewById(R.id.layout_reply_quote)
        private val tvReplyQuote: TextView = itemView.findViewById(R.id.tv_reply_quote)
        private val llReplyImages: LinearLayout = itemView.findViewById(R.id.ll_reply_images)
        private val btnReplyTo: TextView = itemView.findViewById(R.id.btn_reply_to)
        private val ivReplyMore: ImageView? = itemView.findViewById(R.id.iv_reply_more)
        private val layoutCollapsedHint: View? = itemView.findViewById(R.id.layout_collapsed_hint)
        private val ivCollapsedIcon: ImageView? = itemView.findViewById(R.id.iv_collapsed_icon)
        private val tvCollapsedText: TextView? = itemView.findViewById(R.id.tv_collapsed_text)

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

            // 等级/称号（灰白字体）
            val level = item.authorLevel
            if (!TextUtils.isEmpty(level)) {
                tvLevel.visibility = View.VISIBLE
                tvLevel.text = level
                tvLevel.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.text_secondary))
            } else {
                tvLevel.visibility = View.GONE
            }

            // 时间
            val time = item.time
            if (!TextUtils.isEmpty(time)) {
                tvTime.visibility = View.VISIBLE
                tvTime.text = time
            } else {
                tvTime.visibility = View.GONE
            }

            // 评论区按参考样式仅显示时间，不显示回复项地点，避免与回复按钮并列出现重复灰色定位文字。

            // 回复按钮 + 更多(⋮)
            val author = item.author
            if (!TextUtils.isEmpty(author)) {
                btnReplyTo.visibility = View.VISIBLE
                btnReplyTo.setOnClickListener {
                    if (replyClickListener != null) {
                        replyClickListener!!.onReplyClick(item, bindingAdapterPosition)
                    }
                }
                if (ivReplyMore != null) {
                    ivReplyMore.visibility = View.VISIBLE
                    ivReplyMore.setOnClickListener {
                        if (replyLongClickListener != null) {
                            replyLongClickListener!!.onReplyLongClick(item, bindingAdapterPosition)
                        }
                    }
                }
            } else {
                btnReplyTo.visibility = View.GONE
                if (ivReplyMore != null) ivReplyMore.visibility = View.GONE
            }

            // 内容 - 优先显示纯文本

            // 引用内容单独显示，模拟网页端的浅黄色引用框；当前回复保持深色正文。
            val quotedText = item.quotedContentText
            if (!TextUtils.isEmpty(quotedText)) {
                layoutReplyQuote.visibility = View.VISIBLE
                val cleanQuote = BBCodeUtil.stripHtmlColors(quotedText)
                val spannedQuote = Html.fromHtml(
                    cleanQuote, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvReplyQuote),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                tvReplyQuote.text = BBCodeUtil.stripForegroundColorSpans(spannedQuote)
                attachCopyOnLongClick(tvReplyQuote)
            } else {
                layoutReplyQuote.visibility = View.GONE
                tvReplyQuote.text = ""
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

            var remainingSource = sourceHtml
            var editFooterText: String? = null
            val editPattern = Pattern.compile("(?is)(?:<(?:i|span|font|div|p|em)\\b[^>]*>|\\s)*本[帖贴]最后由[\\s\\S]*?编辑(?:\\s*</(?:i|span|font|div|p|em)>)*")
            val editMatcher = editPattern.matcher(sourceHtml)
            if (editMatcher.find()) {
                val matched = editMatcher.group(0) ?: ""
                var pureText = Regex("<[^>]+>").replace(matched, "")
                pureText = Regex("&nbsp;").replace(pureText, " ").trim()
                editFooterText = pureText
                remainingSource = editMatcher.replaceFirst("").trim()
            }

            if (!editFooterText.isNullOrEmpty()) {
                tvReplyEditFooter?.visibility = View.VISIBLE
                tvReplyEditFooter?.text = editFooterText
            } else {
                tvReplyEditFooter?.visibility = View.GONE
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
                tvContent.text = BBCodeUtil.stripForegroundColorSpans(spannedSource)
                setupClickableLinks(tvContent)
                attachCopyOnLongClick(tvContent)
            } else {
                tvContent.visibility = View.GONE
            }

            // 评论区图片展示（支持加载真实附件大图与点击预览）
            if (replyImageUrls.isNotEmpty()) {
                llReplyImages.removeAllViews()
                llReplyImages.visibility = View.VISIBLE
                val maxImgWidth = getMaxImageWidth(itemView.context)
                for (imgUrl in replyImageUrls) {
                    val imageView = ImageView(itemView.context)
                    imageView.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    imageView.adjustViewBounds = true
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.setBackgroundColor(itemView.context.getColor(R.color.background_secondary))
                    imageView.maxWidth = maxImgWidth
                    imageView.maxHeight = (maxImgWidth * 1.5f).toInt()
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

            // 用户要求：废弃原卡片内的折叠胶囊，统一折叠到顶部标题栏
            layoutCollapsedHint?.visibility = View.GONE
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
            FixNestedScrollLinkMovementMethod.matcherLinkify(
                spannable,
                urlPattern,
                { url -> url },  // URL处理器：直接返回原始URL
                { url -> openContentLink(url, textView.context) }  // 点击处理器：打开链接
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
                            ds.color = 0xFF1976D2.toInt()
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

        private fun extractImagesFromHtml(html: String?, outImageUrls: MutableList<String>): String {
            if (TextUtils.isEmpty(html)) {
                return ""
            }
            val imgPattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE)
            val attrPattern = Pattern.compile(
                "(?:zoomfile|file|comiis_loadimages|data-original|data-src|data-file|data-lazy-src|src)\\s*=\\s*['\"]([^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = imgPattern.matcher(html!!)
            val sb = StringBuffer()
            while (matcher.find()) {
                val tag = matcher.group(0)
                val attrMatcher = attrPattern.matcher(tag)
                var chosenUrl: String? = null
                while (attrMatcher.find()) {
                    val candidate = attrMatcher.group(1)
                    val fullCandidate = normalizeImageUrl(candidate)
                    if (fullCandidate != null && !fullCandidate.contains("none.gif") && !fullCandidate.contains("blank.gif")) {
                        chosenUrl = fullCandidate
                        break
                    }
                }
                if (chosenUrl != null) {
                    if (isSmileyOrIcon(chosenUrl)) {
                        matcher.appendReplacement(sb, Matcher.quoteReplacement("<img src=\"$chosenUrl\">"))
                        continue
                    }
                    if (chosenUrl.startsWith("http://") || chosenUrl.startsWith("https://")) {
                        if (!outImageUrls.contains(chosenUrl)) {
                            outImageUrls.add(chosenUrl)
                        }
                    }
                }
                matcher.appendReplacement(sb, "")
            }
            matcher.appendTail(sb)
            return sb.toString()
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
                imageView.setBackgroundColor(context.getColor(R.color.background_secondary))
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
         * 获取评论图片的最大显示宽度（屏幕宽度的80%，最多不超过360dp）
         */
        private fun getMaxImageWidth(context: Context): Int {
            val screenWidth = Resources.getSystem().displayMetrics.widthPixels
            val maxDp = 360
            val maxPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
            val width80 = (screenWidth * 0.8f).toInt()
            return Math.min(width80, maxPx)
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

        private fun dpToPx(context: Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }
    }
}
