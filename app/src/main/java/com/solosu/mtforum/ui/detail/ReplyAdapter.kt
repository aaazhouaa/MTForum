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
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 回复列表适配器
 * 支持 Glide 加载头像、楼层标签、楼主标识、等级、时间、地点等完整信息
 */
class ReplyAdapter(rawReplies: List<ReplyItem>?) :
    RecyclerView.Adapter<ReplyAdapter.ViewHolder>() {

    data class DisplayRow(
        val item: ReplyItem,
        val foldedCount: Int = 0,
        val isExpanded: Boolean = false,
        val groupKey: String = ""
    )

    private var rawReplyList: List<ReplyItem> = rawReplies ?: ArrayList()
    private val expandedKeys: MutableSet<String> = HashSet()
    private var displayList: MutableList<DisplayRow> = ArrayList()

    private var replyClickListener: OnReplyClickListener? = null
    private var userClickListener: OnUserClickListener? = null
    private var replyLongClickListener: OnReplyLongClickListener? = null // build73: 长按出操作菜单
    var onPreloadListener: (() -> Unit)? = null

    init {
        rebuildDisplayList()
    }

    private fun rebuildDisplayList() {
        displayList.clear()
        if (rawReplyList.isEmpty()) return

        val grouped = LinkedHashMap<String, MutableList<ReplyItem>>()
        for (item in rawReplyList) {
            val rawText = item.contentText
            val text = if (!rawText.isNullOrEmpty()) {
                rawText.trim()
            } else {
                val html = item.contentHtml ?: ""
                if (html.length < 300) html.replace(Regex("<[^>]*>"), "").trim() else ""
            }
            val hasImages = item.contentHtml?.contains("<img", ignoreCase = true) == true
            // 纯文本相同（且无复杂图片、长度小于 100 字）的简短灌水回复进行折叠归并
            val key = if (!hasImages && text.isNotEmpty() && text.length <= 100) text else "unique_${item.pid ?: System.identityHashCode(item)}"
            grouped.getOrPut(key) { ArrayList() }.add(item)
        }

        for ((key, items) in grouped) {
            if (items.size == 1) {
                displayList.add(DisplayRow(items[0]))
            } else {
                val isExpanded = expandedKeys.contains(key)
                val foldedCount = items.size - 1
                if (isExpanded) {
                    for (i in items.indices) {
                        displayList.add(
                            DisplayRow(
                                item = items[i],
                                foldedCount = if (i == 0) foldedCount else 0,
                                isExpanded = true,
                                groupKey = key
                            )
                        )
                    }
                } else {
                    // 折叠态：仅展示首条，标注折叠数
                    displayList.add(
                        DisplayRow(
                            item = items[0],
                            foldedCount = foldedCount,
                            isExpanded = false,
                            groupKey = key
                        )
                    )
                }
            }
        }
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

    fun updateData(newList: List<ReplyItem>?) {
        this.rawReplyList = newList ?: ArrayList()
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reply, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = displayList[position]
        holder.bind(row)
        val totalCount = displayList.size
        // 浏览到接近列表末尾（剩余 4 条或总数较少时最后 1~2 条）触发预加载后续页面
        val threshold = if (totalCount <= 15) maxOf(0, totalCount - 2) else totalCount - 5
        if (position >= threshold) {
            onPreloadListener?.invoke()
        }
    }

    override fun getItemCount(): Int {
        return displayList.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivAvatar: ImageView = itemView.findViewById(R.id.iv_reply_avatar)
        private val tvFloorLabel: TextView = itemView.findViewById(R.id.tv_floor_label)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tv_reply_author)
        private val tvOpBadge: TextView = itemView.findViewById(R.id.tv_op_badge)
        private val tvLevel: TextView = itemView.findViewById(R.id.tv_reply_level)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_reply_time)
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

            // 等级
            val level = item.authorLevel
            if (!TextUtils.isEmpty(level)) {
                tvLevel.visibility = View.VISIBLE
                tvLevel.text = level
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
                tvReplyQuote.text = Html.fromHtml(
                    quotedText, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvReplyQuote),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                attachCopyOnLongClick(tvReplyQuote)
            } else {
                layoutReplyQuote.visibility = View.GONE
                tvReplyQuote.text = ""
            }

            val contentText = item.contentText
            val htmlContent = item.contentHtml
            val hasCodeBlock = htmlContent != null &&
                    (htmlContent.contains("comiis_blockcode") || htmlContent.contains("<pre"))

            if (!TextUtils.isEmpty(contentText) && !hasCodeBlock) {
                tvContent.visibility = View.VISIBLE
                // ★ 修复1：使用 ImageGetter 渲染内联图片（表情等），否则 <img> 标签被静默丢弃
                tvContent.text = Html.fromHtml(
                    contentText, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvContent),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                // ★ 修复2：先设置链接，再配置其他属性（避免 setText 覆盖文本）
                setupClickableLinks(tvContent)
                attachCopyOnLongClick(tvContent)
                // 回复内容中的大图处理
                if (!TextUtils.isEmpty(htmlContent)) {
                    loadReplyImages(itemView.context, htmlContent, llReplyImages)
                } else {
                    llReplyImages.visibility = View.GONE
                }
            } else if (!TextUtils.isEmpty(htmlContent)) {
                tvContent.visibility = View.VISIBLE
                // 提取图片URL，移除img标签后渲染文本（也保留内联表情）
                val replyImageUrls = ArrayList<String>()
                val cleanHtml = extractImagesFromHtml(htmlContent, replyImageUrls)
                // ★ 修复2：用 ImageGetter 渲染剩下的内联图片
                tvContent.text = Html.fromHtml(
                    cleanHtml, Html.FROM_HTML_MODE_COMPACT,
                    createInlineImageGetter(tvContent),
                    BBCodeUtil.createTagHandler(itemView.context)
                )
                // ★ 修复2：先设置链接，再配置其他属性（避免 setText 覆盖文本）
                setupClickableLinks(tvContent)
                attachCopyOnLongClick(tvContent)
                // 加载图片
                if (replyImageUrls.isNotEmpty()) {
                    llReplyImages.removeAllViews()
                    llReplyImages.visibility = View.VISIBLE
                    for (imgUrl in replyImageUrls) {
                        val imageView = ImageView(itemView.context)
                        imageView.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        imageView.adjustViewBounds = true
                        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                        imageView.setBackgroundColor(itemView.context.getColor(R.color.background_secondary))
                        // 限制图片最大尺寸，防止撑爆屏幕
                        val maxImgWidth = getMaxImageWidth(itemView.context)
                        imageView.maxWidth = maxImgWidth
                        imageView.maxHeight = (maxImgWidth * 1.2f).toInt()
                        // ★ 添加点击预览
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
                    llReplyImages.visibility = View.GONE
                }
            } else {
                tvContent.visibility = View.GONE
                llReplyImages.visibility = View.GONE
            }

            // 相同内容折叠条展示与交互
            if (row.foldedCount > 0 && layoutCollapsedHint != null && tvCollapsedText != null) {
                layoutCollapsedHint.visibility = View.VISIBLE
                if (row.isExpanded) {
                    tvCollapsedText.text = "已展开 ${row.foldedCount} 条相同回复 · 点击折叠"
                    ivCollapsedIcon?.rotation = 270f
                } else {
                    tvCollapsedText.text = "相同内容已折叠 ${row.foldedCount} 条 · 点击展开"
                    ivCollapsedIcon?.rotation = 90f
                }
                layoutCollapsedHint.setOnClickListener {
                    if (row.isExpanded) {
                        expandedKeys.remove(row.groupKey)
                    } else {
                        expandedKeys.add(row.groupKey)
                    }
                    rebuildDisplayList()
                    notifyDataSetChanged()
                }
            } else {
                layoutCollapsedHint?.visibility = View.GONE
            }
        }
    }

    companion object {

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
        private fun extractImagesFromHtml(html: String?, outImageUrls: MutableList<String>): String {
            if (TextUtils.isEmpty(html)) {
                return ""
            }
            val pattern = Pattern.compile(
                "<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = pattern.matcher(html!!)
            val sb = StringBuffer()
            while (matcher.find()) {
                val url = matcher.group(1)
                // ★ 修复3：补全所有相对路径，否则图片全部丢失
                var fullUrl = normalizeImageUrl(url)
                if (fullUrl == null) fullUrl = url

                // 过滤内联小图/表情，保留在文本中
                if (fullUrl.contains("smiley") || fullUrl.contains("emoticon")
                    || fullUrl.contains("face") || fullUrl.contains("/static/image/")
                    || fullUrl.contains("stamp") || fullUrl.contains("magic")
                    || fullUrl.contains("mini") || fullUrl.contains("icon")
                ) {
                    // 将原img标签中的src替换为补全后的完整URL
                    val origTag = matcher.group(0)
                    val newTag = origTag.replaceFirst(
                        "src\\s*=\\s*['\"][^'\"]*['\"]".toRegex(),
                        "src=\"$fullUrl\""
                    )
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(newTag))
                    continue
                }
                // 大图：只接受 http/https
                if (fullUrl.startsWith("http://") || fullUrl.startsWith("https://")) {
                    outImageUrls.add(fullUrl)
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
                            if (w <= 0) {
                                w = emotSize
                            }
                            if (h <= 0) {
                                h = emotSize
                            }
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
                            placeholder.setReal(resource, tv)
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
