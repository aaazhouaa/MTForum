package com.solosu.mtforum.ui.detail

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText

import com.solosu.mtforum.R
import com.solosu.mtforum.ai.AiConfigManager
import com.solosu.mtforum.ai.AiLog
import com.solosu.mtforum.ai.AutoReplyEngine
import com.solosu.mtforum.databinding.ThreadDetailActivityBinding
import com.solosu.mtforum.model.PostDetail
import com.solosu.mtforum.model.ReplyItem
import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.BlacklistManager
import com.solosu.mtforum.session.FavoritesCache
import com.solosu.mtforum.session.FollowStateManager
import com.solosu.mtforum.session.PostCountsCache
import com.solosu.mtforum.session.UserSessionManager
import com.solosu.mtforum.ui.login.LoginBottomSheet
import com.solosu.mtforum.ui.message.ChatActivity
import com.solosu.mtforum.ui.space.UserProfileActivity
import com.solosu.mtforum.ui.widget.DialogHelper
import com.solosu.mtforum.ui.widget.FrostedGlassHelper
import com.solosu.mtforum.util.BBCodeUtil
import com.solosu.mtforum.util.NavigationHelper
import com.solosu.mtforum.util.UrlDrawable

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.regex.Pattern

/**
 * 帖子详情页。
 *
 * 由反编译产物迁移而来：原文件是 JADX 输出，含 117 个 `lambda$` 合成方法与
 * 大量匿名内部类。Kotlin 版本把这些合成方法内联回原地，逻辑与参数顺序保持一致。
 */
class ThreadDetailActivity : AppCompatActivity() {

    private lateinit var binding: ThreadDetailActivityBinding
    private lateinit var httpClient: HttpClient
    private var mBottomSheetDialog: BottomSheetDialog? = null
    private var mEtReplyDialog: com.solosu.mtforum.ui.widget.RichTextInputEditText? = null
    private var mTvReplyTarget: TextView? = null
    private var mBtnSendReply: MaterialButton? = null
    private var postDetail: PostDetail? = null
    private var replyAdapter: ReplyAdapter? = null
    private var tid: String? = null
    private var onlyOpReplies = false
    private var repliesDescending = true
    private var displayedReplies: MutableList<ReplyItem> = ArrayList()
    private var isLiked = false
    private var likeCount = 0
    private var likeUsersAdapter: LikeUsersAdapter? = null
    private var isFavorited = false
    private var favoriteCount = 0   // 真实收藏数(来自 ForumParser #comiis_favorite_a)
    private var currentReplyTarget = ""
    private var isLoadingMore = false
    private var currentReplyPid = ""
    private var currentLoginUid: String? = null // build73: 当前登录 uid(判定是否本人)
    // build74b: 打赏目标(空=主楼;有值=评论楼层)
    private var rewardTargetPid = ""
    private var rewardTargetName = ""
    private var rewardTargetAvatar = ""
    private var pendingImageUri: Uri? = null
    private val pendingUploadAids: MutableList<String> = ArrayList()
    private var imageUploadInProgress = false
    // build71: 多选图片排队,避免上一张上传中时后续图片被静默丢弃
    private val imageUploadPendingQueue: MutableList<Uri> = ArrayList()
    private val pendingImageUris: MutableList<Uri> = ArrayList()
    private val uploadedAidMap: MutableMap<Uri, String> = HashMap()

    /* build61: 待解锁页面快照(加载线程写,渲染后读一次即清) */
    private var pendingUnlockHtml: String? = null

    /** 已自动解锁过的 tid，避免同一页面反复触发 */
    private val autoUnlockTried: MutableSet<String> = HashSet()
    private var autoUnlocking = false

    /** 当前帖全部图片(正文+附件+隐藏区,按 bindData 收集顺序) */
    private var currentImageList: MutableList<String> = ArrayList()

    /** build70: 相册选图 launcher */
    private var imagePickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ThreadDetailActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        httpClient = HttpClient.getInstance()
        tid = intent.getStringExtra("tid")
        if (tid == null) {
            finish()
            return
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { refreshPostDetail() }
        setupRecyclerView()
        val onBottomReplyBarClick = View.OnClickListener { showReplyBottomSheet(currentReplyTarget) }
        binding.etReply.setOnClickListener(onBottomReplyBarClick)
        binding.tilReply.setOnClickListener(onBottomReplyBarClick)
        binding.layoutReply.setOnClickListener(onBottomReplyBarClick)
        binding.etReply.isFocusable = false
        binding.etReply.isCursorVisible = false
        val commentsClick = View.OnClickListener {
            binding.recyclerReplies.visibility = View.VISIBLE
            binding.nestedScroll.post {
                binding.nestedScroll.smoothScrollTo(0, binding.recyclerReplies.top)
            }
        }
        binding.btnComments.setOnClickListener(commentsClick)
        binding.layoutComments.setOnClickListener(commentsClick)

        val pickImageClick = View.OnClickListener { pickImage() }
        binding.btnPickImageInline.setOnClickListener(pickImageClick)
        binding.layoutPickImage.setOnClickListener(pickImageClick)

        binding.btnLike.setOnClickListener { toggleLike() }
        binding.layoutLike.setOnClickListener { toggleLike() }

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachNestedScrollView(binding.toolbar, binding.nestedScroll)

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.layoutFavorite.setOnClickListener { toggleFavorite() }
        binding.btnShare.setOnClickListener { shareThread() }
        binding.btnViewHidden.setOnClickListener { viewHiddenContent() }
        binding.btnLoadMore.setOnClickListener { loadMoreReplies() }
        binding.nestedScroll.setOnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            val scrollView = v as NestedScrollView
            if (scrollY > oldScrollY) {
                checkReplyPreload(scrollView, scrollY)
            }
        }
        binding.btnOnlyOp.setOnClickListener {
            onlyOpReplies = !onlyOpReplies
            lastPreloadTriggerCount = 0
            updateReplyFilterAndOrder()
        }
        binding.btnReplyOrder.setOnClickListener {
            repliesDescending = !repliesDescending
            refreshPostDetail()
        }
        binding.btnReward.setOnClickListener { showRewardDialog() }
        binding.btnKick.setOnClickListener { showKickDialog() }
        // build64: AI 总结(详情页入口,与列表卡片行为一致)
        binding.btnAiSummary.setOnClickListener {
            val it = Intent(this, com.solosu.mtforum.ai.AiSummarizeActivity::class.java)
            it.putExtra("tid", tid)
            it.putExtra(
                "title",
                if (binding.tvThreadTitle.text != null) binding.tvThreadTitle.text.toString() else ""
            )
            startActivity(it)
        }
        loadPostDetail()
    }

    private fun setupRecyclerView() {
        replyAdapter = ReplyAdapter(ArrayList())
        replyAdapter!!.onPreloadListener = { checkAndPreloadReplies() }
        // build73: 评论长按 -> 回复 / 举报 / (本人)删除
        replyAdapter!!.setOnReplyLongClickListener(object : ReplyAdapter.OnReplyLongClickListener {
            override fun onReplyLongClick(item: ReplyItem?, position: Int) {
                showReplyActionMenu(item)
            }
        })
        replyAdapter!!.setOnReplyClickListener(object : ReplyAdapter.OnReplyClickListener {
            override fun onReplyClick(item: ReplyItem?, position: Int) {
                val author = if (item != null) item.author else ""
                if (!TextUtils.isEmpty(author)) {
                    currentReplyPid = if (item != null) item.pid ?: "" else ""
                    // build71: 不再把"回复 xx:"当预填文本塞进输入框(它会被一起发出去)
                    currentReplyTarget = ""
                } else {
                    currentReplyPid = ""
                    currentReplyTarget = ""
                }
                showReplyBottomSheet(currentReplyTarget)
            }
        })
        replyAdapter!!.setOnUserClickListener(object : ReplyAdapter.OnUserClickListener {
            override fun onUserClick(item: ReplyItem?, position: Int) {
                val uid = item?.authorUid
                if (!TextUtils.isEmpty(uid)) {
                    val intent = Intent(this@ThreadDetailActivity, UserProfileActivity::class.java)
                    intent.putExtra(ChatActivity.EXTRA_UID, uid)
                    intent.putExtra("username", item!!.author)
                    startActivity(intent)
                }
            }
        })
        binding.recyclerReplies.layoutManager = LinearLayoutManager(this)
        binding.recyclerReplies.adapter = replyAdapter
    }

    private fun loadPostDetail() {
        if (isFinishing || isDestroyed) {
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = false
        java.lang.Thread {
            try {
                val detailUrl = ForumParser.getThreadDetailUrl(tid, getReplyOrder()) +
                        "&_load=" + System.currentTimeMillis()
                httpClient.syncFromCookieManager()
                val html = httpClient.get(detailUrl)
                if (TextUtils.isEmpty(html)) {
                    throw IllegalStateException("服务器返回空页面，请检查网络后重试")
                }
                val detail = ForumParser.parseThreadDetail(html)
                fetchRepliesUpTo(detail, 20)
                enrichGoodReviewAvatars(detail)
                refreshServerActionState(detail)
                // build61: 进帖触发解锁——只记录页面,渲染后在后台线程执行(不阻塞首屏)
                pendingUnlockHtml = html
                if (!TextUtils.isEmpty(detail.authorUid)) {
                    detail.isFollowed = FollowStateManager.resolve(this, detail.authorUid, detail.isFollowed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bindData(detail, true)
                    // build61: 渲染完成后,后台线程执行解锁检测→回帖→成功再刷新
                    runUnlockInBackground(detail)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isEnabled = true
                    val message = if (TextUtils.isEmpty(e.message)) "网络异常，请下拉刷新重试" else e.message
                    Toast.makeText(this, "加载失败: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** build61: 渲染后异步解锁——16 秒节流在后台线程里等,不卡首屏 */
    private fun runUnlockInBackground(detail: PostDetail?) {
        if (detail == null || !detail.hasHiddenContent) return
        // 进帖自动解锁开关：此前只有 maybeAutoUnlock 检查它，而真正干活的这条链路不检查，
        // 导致用户在设置里关掉开关后，进帖依旧会自动回帖解锁。
        if (!AiConfigManager.isUnlockOnView(this)) {
            AiLog.i("auto-unlock", "跳过：进帖自动解锁开关关闭")
            return
        }
        val pageHtml = pendingUnlockHtml
        pendingUnlockHtml = null
        if (TextUtils.isEmpty(pageHtml)) return
        java.lang.Thread({
            val ok = AutoReplyEngine.tryUnlockOnOpen(this, detail, pageHtml)
            if (ok) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, "已自动回帖解锁，正在刷新…", Toast.LENGTH_SHORT).show()
                    refreshPostDetail()
                }
            }
        }, "unlock-on-open").start()
    }

    private fun getReplyOrderUrl(): String {
        return ForumParser.getThreadDetailUrl(tid, getReplyOrder())
    }

    fun refreshPostDetail() {
        if (isFinishing || isDestroyed) {
            return
        }
        java.lang.Thread {
            try {
                val detailUrl = ForumParser.getThreadDetailUrl(tid, getReplyOrder()) +
                        "&_refresh=" + System.currentTimeMillis()
                httpClient.syncFromCookieManager()
                val html = httpClient.get(detailUrl)
                if (TextUtils.isEmpty(html)) {
                    throw IllegalStateException("服务器返回空页面，请检查网络后重试")
                }
                val detail = ForumParser.parseThreadDetail(html)
                fetchRepliesUpTo(detail, 20)
                enrichGoodReviewAvatars(detail)
                refreshServerActionState(detail)
                // build61: 下拉刷新链同样只记录页面,渲染后异步解锁(同加载链)
                pendingUnlockHtml = html
                if (!TextUtils.isEmpty(detail.authorUid)) {
                    detail.isFollowed = FollowStateManager.resolve(this, detail.authorUid, detail.isFollowed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.swipeRefresh.isRefreshing = false
                    applyServerActionState(detail)
                    // build61: 渲染完成后,后台线程执行解锁检测(同加载链)
                    runUnlockInBackground(detail)
                    likeCount = maxOf(0, detail.likeCount)
                    updateLikeIcon()
                    updateFavoriteIcon()
                    favoriteCount = maxOf(0, detail.favoriteCount)
                    updateCountBadge(binding.tvFavoriteBadge, favoriteCount)
                    bindData(detail, false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.swipeRefresh.isRefreshing = false
                    val message = if (TextUtils.isEmpty(e.message)) "网络异常，请稍后重试" else e.message
                    Toast.makeText(this, "刷新失败: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun bindData(postDetailArg: PostDetail?, scrollToTop: Boolean) {
        if (isFinishing || isDestroyed) {
            return
        }
        if (postDetailArg == null) {
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isEnabled = true
            Toast.makeText(this, "帖子内容为空，请下拉刷新重试", Toast.LENGTH_SHORT).show()
            return
        }
        val postDetail = postDetailArg
        this.postDetail = postDetail
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isEnabled = true
        if (!TextUtils.isEmpty(postDetail.forumName)) {
            binding.tvForumName.visibility = View.VISIBLE
            binding.tvForumName.text = postDetail.forumName
        } else {
            binding.tvForumName.visibility = View.GONE
        }
        binding.tvThreadTitle.text = if (!TextUtils.isEmpty(postDetail.title)) postDetail.title else ""
        // build75: 长按标题 -> 举报帖子
        binding.tvThreadTitle.isLongClickable = true
        binding.tvThreadTitle.setOnLongClickListener {
            reportPost(null)
            true
        }
        val avatarUrl = postDetail.avatarUrl
        if (!TextUtils.isEmpty(avatarUrl)) {
            Glide.with(this as FragmentActivity).load(avatarUrl).transform(CircleCrop())
                .placeholder(R.drawable.ic_account).error(R.drawable.ic_account)
                .into(binding.ivAuthorAvatar)
        } else {
            binding.ivAuthorAvatar.setImageResource(R.drawable.ic_account)
        }
        binding.tvAuthorName.text = if (!TextUtils.isEmpty(postDetail.author)) postDetail.author else "匿名"
        val authorUid = postDetail.authorUid
        if (!TextUtils.isEmpty(authorUid)) {
            binding.ivAuthorAvatar.setOnClickListener { openUserProfile(authorUid, postDetail.author) }
            binding.tvAuthorName.setOnClickListener { openUserProfile(authorUid, postDetail.author) }
        }
        if (!TextUtils.isEmpty(postDetail.authorLevel)) {
            binding.tvAuthorLevel.visibility = View.VISIBLE
            binding.tvAuthorLevel.text = postDetail.authorLevel
        } else {
            binding.tvAuthorLevel.visibility = View.GONE
        }
        binding.tvPublishTime.text = if (!TextUtils.isEmpty(postDetail.publishTime)) postDetail.publishTime else ""
        binding.tvLocation.visibility = View.GONE
        // 收藏数回填缓存:详情页拿到数字后存进 FavoritesCache,列表卡片第四格就能显示
        if (postDetail.favoriteCount > 0) {
            FavoritesCache.put(this, postDetail.tid, postDetail.favoriteCount)
        }
        if (httpClient.isLoggedIn() && !TextUtils.isEmpty(postDetail.author)) {
            binding.btnFollow.visibility = View.VISIBLE
            if (isOwnThread(postDetail)) {
                // build73: 自己的帖子 -> 右上角是「编辑」(不是关注)
                binding.btnFollow.text = "编辑"
                binding.btnFollow.setOnClickListener { openEditThread() }
            } else {
                binding.btnFollow.setText(
                    if (postDetail.isFollowed) R.string.action_followed else R.string.action_follow
                )
                binding.btnFollow.setOnClickListener { toggleFollow() }
            }
        } else {
            binding.btnFollow.visibility = View.GONE
        }
        val contentHtml = postDetail.contentHtml
        var hiddenNotice = ""
        if (!TextUtils.isEmpty(contentHtml)) {
            val converted = BBCodeUtil.convertBBCodeToHtml(contentHtml)
            binding.tvContent.visibility = View.VISIBLE
            val imageList = ArrayList<String>()
            val footerSplit = splitEditFooter(converted)
            val cleaned = extractAndSeparateImages(footerSplit[0], imageList)
            val imageUrls = postDetail.imageUrls
            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (str in imageUrls) {
                    if (!imageList.contains(str)) {
                        imageList.add(str)
                    }
                }
            }
            if (!TextUtils.isEmpty(footerSplit[1])) {
                binding.layoutEditFooter.visibility = View.VISIBLE
                binding.tvEditFooter.text = footerSplit[1]
                binding.viewContentTopDivider.visibility = View.GONE
                adjustEditFooterDividerWidth()
                val lp = binding.frameContent.layoutParams as LinearLayout.LayoutParams
                lp.topMargin = 0
                binding.frameContent.layoutParams = lp
            } else {
                binding.layoutEditFooter.visibility = View.GONE
                binding.viewContentTopDivider.visibility = View.VISIBLE
                val lp2 = binding.frameContent.layoutParams as LinearLayout.LayoutParams
                lp2.topMargin = dpToPx(12)
                binding.frameContent.layoutParams = lp2
            }
            // 收集当前帖全部图片供全屏翻页
            currentImageList = ArrayList(imageList)
            val placeholders = replaceHiddenQuoteWithPlaceholder(cleaned)
            val unlocked = postDetail.hasHiddenContent && httpClient.isLoggedIn()
                    && !TextUtils.isEmpty(postDetail.hiddenContentHtml)
                    && !AutoReplyEngine.isLockedHidden(postDetail.hiddenContentHtml)
            hiddenNotice = if (unlocked) "隐藏内容(已解锁)" else placeholders[1]
            binding.tvContent.text = safeFromHtml(
                placeholders[0],
                createInlineImageGetter(binding.tvContent),
                BBCodeUtil.createTagHandler(this)
            )
            applyHiddenNoticeHighlight(binding.tvContent.text, hiddenNotice)
            setupClickableLinks(binding.tvContent)
            if (!imageList.isEmpty()) {
                binding.cardImageGallery.visibility = View.VISIBLE
                binding.hsvImageGallery.visibility = View.VISIBLE
                binding.llImageGallery.removeAllViews()
                FrostedGlassHelper.applyToCardViews(binding.cardImageGallery, this)
                val height = dpToPx(ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION)
                val margin = dpToPx(4)
                for (str2 in imageList) {
                    val imageView = ImageView(this)
                    imageView.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, height
                    )
                    imageView.adjustViewBounds = true
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    (imageView.layoutParams as LinearLayout.LayoutParams).setMargins(margin, 0, margin, 0)
                    imageView.setOnClickListener { openImagePreview(str2) }
                    Glide.with(this as FragmentActivity).load(str2)
                        .placeholder(ColorDrawable(getColor(R.color.background_secondary)))
                        .error(ColorDrawable(getColor(R.color.divider)))
                        .into(imageView)
                    binding.llImageGallery.addView(imageView)
                }
                binding.btnCollapseImages.setOnClickListener { toggleImageGallery() }
            } else {
                binding.cardImageGallery.visibility = View.GONE
            }
        } else {
            binding.tvContent.visibility = View.VISIBLE
            binding.cardImageGallery.visibility = View.GONE
            binding.tvContent.text = "[内容加载中，请刷新重试]"
            binding.tvContent.setTextColor(getColor(R.color.text_hint))
            binding.tvContent.textSize = 14.0f
            binding.tvContent.gravity = Gravity.CENTER
        }
        val hasHidden = postDetail.hasHiddenContent
        val hiddenUnlocked = hasHidden && httpClient.isLoggedIn()
                && !TextUtils.isEmpty(postDetail.hiddenContentHtml)
                && !AutoReplyEngine.isLockedHidden(postDetail.hiddenContentHtml)
        if (hiddenUnlocked) {
            // 已登录且可获取隐藏内容:正文中的胶囊只显示短提示,下方直接展示完整内容
            binding.layoutHiddenContent.visibility = View.VISIBLE
            binding.tvHiddenContentHint.visibility = View.GONE
            binding.btnViewHidden.visibility = View.GONE
            renderHiddenContent(postDetail.hiddenContentHtml)
        } else if (hasHidden) {
            // 未登录或暂无内容:显示按钮引导查看(点击会提示登录或重新加载)
            binding.layoutHiddenContent.visibility = View.VISIBLE
            binding.tvHiddenContentHint.visibility = View.VISIBLE
            binding.btnViewHidden.visibility = View.VISIBLE
            binding.tvHiddenContent.visibility = View.GONE
            // 自动解锁：进入帖子发现是「回复可见」时，后台直接回复解锁
            maybeAutoUnlock()
        } else {
            binding.layoutHiddenContent.visibility = View.GONE
        }
        if (postDetail.likedStateKnown) {
            isLiked = postDetail.isLiked
            saveLikedState(isLiked)
        } else {
            isLiked = restoreLikedState()
        }
        likeCount = maxOf(0, postDetail.likeCount)
        updateLikeIcon()
        updateCountBadge(binding.tvCommentsBadge, postDetail.replyCount)
        updateCountBadge(binding.tvLikeBadge, likeCount)
        // === 点赞人头像行 ===
        bindLikeUsers(postDetail)
        if (postDetail.favoritedStateKnown) {
            isFavorited = postDetail.isFavorited
            saveFavoritedState(isFavorited)
        } else {
            isFavorited = restoreFavoritedState()
        }
        updateFavoriteIcon()
        favoriteCount = maxOf(0, postDetail.favoriteCount)
        updateCountBadge(binding.tvFavoriteBadge, favoriteCount)
        var replies = postDetail.replies
        if (replies == null) {
            replies = ArrayList()
        }
        // 黑名单过滤:拉黑作者的回帖直接不展示
        val bl = BlacklistManager.uidSet(this)
        if (!bl.isEmpty()) {
            val itr = replies.iterator()
            while (itr.hasNext()) {
                val r = itr.next()
                if (r != null && r.authorUid != null && bl.contains(r.authorUid)) itr.remove()
            }
        }
        displayedReplies = ArrayList(replies)
        lastPreloadTriggerCount = 0
        updateReplyFilterAndOrder()
        binding.btnLoadMore.visibility = if (postDetail.currentPage < postDetail.totalPages) View.VISIBLE else View.GONE
        val replyCount = postDetail.replyCount
        if (replyCount > 0) {
            binding.tvReplyCount.visibility = View.VISIBLE
            binding.tvReplyCount.text = "($replyCount)"
        } else {
            binding.tvReplyCount.visibility = View.GONE
        }
        binding.btnLoadMore.visibility = View.GONE
        binding.layoutReply.visibility = if (httpClient.isLoggedIn()) View.VISIBLE else View.GONE
        binding.layoutThreadActions.visibility = if (httpClient.isLoggedIn()) View.VISIBLE else View.GONE
        val rewardCount = postDetail.rewardCount
        val goodReviewCount = postDetail.goodReviewCount
        val rewardCoins = postDetail.rewardCoins
        val rewardUserAvatars = postDetail.rewardUserAvatars
        val goodReviewUserAvatars = postDetail.goodReviewUserAvatars
        var hasRewardStats = true
        if (rewardCount <= 0 && goodReviewCount <= 0 &&
            (rewardUserAvatars == null || rewardUserAvatars.isEmpty()) &&
            (goodReviewUserAvatars == null || goodReviewUserAvatars.isEmpty())
        ) {
            hasRewardStats = false
        }
        if (httpClient.isLoggedIn() && hasRewardStats) {
            binding.layoutRewardReviewStats.visibility = View.VISIBLE
            binding.tvRewardCount.text = rewardCount.toString()
            binding.tvRewardCoins.text = "共计 $rewardCoins 金币"
            binding.tvGoodReviewCount.text = goodReviewCount.toString()
            bindAvatarStrip(binding.llRewardAvatars, rewardUserAvatars)
            bindAvatarStrip(binding.llGoodReviewAvatars, goodReviewUserAvatars)
        } else {
            binding.layoutRewardReviewStats.visibility = View.GONE
        }
        if (scrollToTop && binding.nestedScroll.scrollY != 0) {
            binding.nestedScroll.scrollTo(0, 0)
        }
    }

    private fun openUserProfile(uid: String?, username: String?) {
        val intent = Intent(this, UserProfileActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_UID, uid)
        intent.putExtra("username", username)
        startActivity(intent)
    }

    private fun toggleImageGallery() {
        val isCollapsed = binding.hsvImageGallery.visibility == View.GONE
        if (isCollapsed) {
            binding.hsvImageGallery.visibility = View.VISIBLE
            binding.hsvImageGallery.alpha = 0.0f
            binding.hsvImageGallery.animate().alpha(1.0f).setDuration(300L).start()
            binding.btnCollapseImages.animate().rotation(90.0f).setDuration(200L).start()
            return
        }
        binding.hsvImageGallery.animate().alpha(0.0f).setDuration(200L)
            .withEndAction { binding.hsvImageGallery.visibility = View.GONE }
            .start()
        binding.btnCollapseImages.animate().rotation(-90.0f).setDuration(200L).start()
    }

    private fun getReplyOrder(): String {
        return if (repliesDescending) "desc" else "asc"
    }

    private fun updateReplyFilterAndOrder() {
        val source = displayedReplies
        val result = ArrayList<ReplyItem>()
        val opUid = postDetail?.authorUid ?: ""
        val opName = postDetail?.author ?: ""
        for (item in source) {
            if (item != null) {
                if (onlyOpReplies) {
                    var isOp = item.isOP
                    if (!isOp && !TextUtils.isEmpty(opUid)) {
                        isOp = opUid == item.authorUid
                    }
                    if (!isOp && !TextUtils.isEmpty(opName)) {
                        isOp = opName == item.author
                    }
                    if (!isOp) {
                        // build71: 之前这里是空块,过滤动作被挖空,导致"只看楼主"点了没效果
                        continue
                    }
                }
                result.add(item)
            }
        }
        replyAdapter!!.updateData(result)
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        binding.btnOnlyOp.setText(if (onlyOpReplies) R.string.reply_all_users else R.string.reply_only_op)
        binding.btnOnlyOp.setTextColor(if (onlyOpReplies) themeColor else getColor(R.color.text_secondary))
        binding.btnReplyOrder.setText(if (repliesDescending) R.string.reply_order_desc else R.string.reply_order_asc)
        binding.btnReplyOrder.setTextColor(if (repliesDescending) themeColor else getColor(R.color.text_secondary))
        if (result.isEmpty()) {
            binding.recyclerReplies.visibility = View.GONE
            binding.tvEmptyReplies.visibility = View.VISIBLE
        } else {
            binding.recyclerReplies.visibility = View.VISIBLE
            binding.tvEmptyReplies.visibility = View.GONE
        }
    }

    private fun enrichGoodReviewAvatars(detail: PostDetail?) {
        if (detail == null) {
            return
        }
        try {
            if (detail.goodReviewCount > 0) {
                val desktopHtml = httpClient.getDesktop(ForumParser.getThreadDesktopDetailUrl(tid))
                val avatars = ForumParser.parseGoodReviewAvatarUrls(desktopHtml)
                if (avatars != null && !avatars.isEmpty()) {
                    detail.goodReviewUserAvatars = avatars
                }
            }
            val rewardDetailUrl = detail.rewardDetailUrl
            if (!TextUtils.isEmpty(rewardDetailUrl)) {
                val rewardHtml = httpClient.get(rewardDetailUrl!!)
                detail.rewardCoins = ForumParser.parseRewardCoins(rewardHtml)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun bindAvatarStrip(linearLayout: LinearLayout?, avatarUrls: MutableList<String>?) {
        if (linearLayout == null) {
            return
        }
        linearLayout.removeAllViews()
        if (avatarUrls == null || avatarUrls.isEmpty()) {
            return
        }
        val maxVisible = minOf(6, avatarUrls.size)
        val size = dpToPx(32)
        val overlap = dpToPx(8)
        for (i in 0 until maxVisible) {
            val avatar = ImageView(this)
            val params = LinearLayout.LayoutParams(size, size)
            if (i > 0) {
                params.leftMargin = -overlap
            }
            avatar.layoutParams = params
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar.setPadding(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1))
            avatar.setBackgroundResource(R.drawable.circle_avatar_bg)
            val url = avatarUrls[i]
            Glide.with(this as FragmentActivity).load(url).transform(CircleCrop())
                .placeholder(R.drawable.ic_account).error(R.drawable.ic_account)
                .into(avatar)
            linearLayout.addView(avatar)
        }
        if (avatarUrls.size > 6) {
            val more = TextView(this)
            val params2 = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            params2.leftMargin = -overlap
            more.layoutParams = params2
            more.gravity = Gravity.CENTER
            more.text = "+" + (avatarUrls.size - 6)
            more.textSize = 10.0f
            more.setTextColor(-1)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(-1728053248)
            bg.setStroke(dpToPx(1), -1)
            more.background = bg
            linearLayout.addView(more)
        }
    }

    // ==================== 回复 ====================

    private fun ensureReplyBottomSheetDialog(): BottomSheetDialog {
        var dialog = mBottomSheetDialog
        if (dialog == null) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_reply_bottom_sheet, null)
            val etReplyDialog = dialogView.findViewById<com.solosu.mtforum.ui.widget.RichTextInputEditText>(R.id.et_reply_dialog)
            val btnSend = dialogView.findViewById<MaterialButton>(R.id.btn_send_reply)
            val tvTarget = dialogView.findViewById<TextView>(R.id.tv_reply_target)
            val btnPickImage = dialogView.findViewById<ImageButton>(R.id.btn_pick_image)

            mEtReplyDialog = etReplyDialog
            mTvReplyTarget = tvTarget
            mBtnSendReply = btnSend

            etReplyDialog.onImageReceivedListener = { uri ->
                addPendingImages(listOf(uri))
                Toast.makeText(this@ThreadDetailActivity, "已添加图片", Toast.LENGTH_SHORT).show()
                true
            }

            btnSend.setOnClickListener {
                val text = etReplyDialog.text?.toString()?.trim() ?: ""
                if (TextUtils.isEmpty(text) && pendingImageUris.isEmpty()) {
                    etReplyDialog.error = getString(R.string.reply_hint_empty)
                } else {
                    etReplyDialog.error = null
                    val attachTags = buildAttachTags()
                    attemptReply(attachTags + text, etReplyDialog)
                }
            }

            btnPickImage?.setOnClickListener { pickImage() }

            dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
            dialog.setContentView(dialogView)

            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            dialog.setOnDismissListener {
                currentReplyPid = ""
                currentReplyTarget = ""
            }

            dialog.setOnShowListener {
                val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val behavior = BottomSheetBehavior.from(sheet)
                    behavior.skipCollapsed = true
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                etReplyDialog.post {
                    etReplyDialog.isFocusable = true
                    etReplyDialog.isFocusableInTouchMode = true
                    etReplyDialog.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(etReplyDialog, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            mBottomSheetDialog = dialog
        }
        return dialog
    }

    private fun showReplyBottomSheet(prefillText: String?) {
        if (isFinishing || isDestroyed) return
        val dialog = ensureReplyBottomSheetDialog()
        val etReplyDialog = mEtReplyDialog
        val tvTarget = mTvReplyTarget

        if (!TextUtils.isEmpty(prefillText)) {
            if (prefillText!!.startsWith("回复 ") || prefillText.contains("：")) {
                tvTarget?.text = prefillText
                tvTarget?.visibility = View.VISIBLE
                etReplyDialog?.setText("")
            } else {
                etReplyDialog?.setText(prefillText)
                etReplyDialog?.setSelection(prefillText.length)
                tvTarget?.text = prefillText
                tvTarget?.visibility = View.VISIBLE
            }
        } else {
            tvTarget?.visibility = View.GONE
            etReplyDialog?.setText("")
        }
        etReplyDialog?.error = null

        if (!pendingImageUris.isEmpty()) {
            updateDialogImagePreview()
        }

        if (!dialog.isShowing) {
            dialog.show()
        } else {
            etReplyDialog?.post {
                etReplyDialog.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etReplyDialog, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** 未登录操作统一弹出登录底部弹窗(与回复弹窗同风格) */
    private fun promptLogin() {
        if (isFinishing || isDestroyed) {
            return
        }
        if (mBottomSheetDialog != null && mBottomSheetDialog!!.isShowing) {
            mBottomSheetDialog!!.dismiss()
        }
        LoginBottomSheet.show(this, null)
    }

    private fun attemptReply(replyText: String, etReplyInput: TextInputEditText?) {
        if (!httpClient.isLoggedIn()) {
            httpClient.syncFromCookieManager()
        }
        if (!httpClient.isLoggedIn()) {
            promptLogin()
        } else {
            if (TextUtils.isEmpty(replyText)) {
                binding.tilReply.error = getString(R.string.reply_hint_empty)
                return
            }
            binding.tilReply.error = null
            val formhash = postDetail?.formhash
            java.lang.Thread {
                var fh = formhash
                try {
                    if (TextUtils.isEmpty(fh)) {
                        val html = httpClient.get(ForumParser.getThreadDetailUrl(tid))
                        fh = ForumParser.parseFormhash(html)
                    }
                    if (TextUtils.isEmpty(fh)) {
                        showReplyFailure("获取回复验证失败，请刷新页面后重试")
                        return@Thread
                    }
                    val params = HashMap<String, String>()
                    params["formhash"] = fh!!
                    params["message"] = replyText
                    params["replysubmit"] = "yes"
                    // build71: 上传得到的 aid 必须随回复一起提交 attachnew,否则附件不会被关联
                    synchronized(pendingUploadAids) {
                        for (aid in pendingUploadAids) {
                            if (!TextUtils.isEmpty(aid)) {
                                params["attachnew[$aid][description]"] = ""
                            }
                        }
                    }
                    if (!TextUtils.isEmpty(currentReplyPid)) {
                        params["reppid"] = currentReplyPid
                        params["reppost"] = currentReplyPid
                        params["addfeed"] = "1"
                        val quote = buildReplyQuote(currentReplyPid)
                        if (!TextUtils.isEmpty(quote)) {
                            params["noticetrimstr"] = quote!!
                        }
                        params["noticeauthormsg"] = replyText
                    }
                    val noticeauthor = postDetail?.noticeauthor
                    if (!TextUtils.isEmpty(noticeauthor)) {
                        params["noticeauthor"] = noticeauthor!!
                    }
                    params["posttime"] = (System.currentTimeMillis() / 1000).toString()
                    val fid = postDetail?.forumFid ?: ""
                    val replyUrl = "https://bbs.binmt.cc/forum.php?mod=post&action=reply&fid=" + fid +
                            "&tid=" + tid + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1"
                    val result = httpClient.post(replyUrl, params)
                    if (!isReplyResponseSuccessful(result) && !wasReplyPublished(replyText)) {
                        showReplyFailure(extractReplyError(result))
                    } else {
                        completeReplyPublished()
                    }
                } catch (e: Exception) {
                    showReplyFailure("回复失败：" + if (TextUtils.isEmpty(e.message)) "网络异常，请稍后重试" else e.message)
                }
            }.start()
        }
    }

    private fun wasReplyPublished(replyText: String): Boolean {
        try {
            val currentUid = UserSessionManager.getInstance().getUid(applicationContext)
            if (TextUtils.isEmpty(currentUid)) {
                return false
            }
            val lastPage = postDetail?.let { maxOf(1, it.totalPages) } ?: 1
            val expectedText = normalizeReplyText(replyText)
            for (page in lastPage..(lastPage + 1)) {
                val url = ForumParser.getThreadDetailUrl(tid, page, getReplyOrder()) +
                        "&_reply_check=" + System.currentTimeMillis()
                val latest = ForumParser.parseThreadDetail(httpClient.get(url))
                val replies = latest.replies
                if (replies != null) {
                    for (k in replies.size - 1 downTo 0) {
                        val item = replies[k]
                        if (item != null && currentUid == item.authorUid && !TextUtils.isEmpty(expectedText)
                            && normalizeReplyText(item.contentText).contains(expectedText)
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return false
    }

    private fun normalizeReplyText(text: String?): String {
        if (TextUtils.isEmpty(text)) return ""
        return Regex("(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]").replace(text!!, "")
            .let { Regex("\\s+").replace(it, " ") }.trim()
    }

    private fun completeReplyPublished() {
        runOnUiThread {
            currentReplyPid = ""
            currentReplyTarget = ""
            binding.tilReply.error = null
            // build80: 回复已发出 -> 清空待发送图片队列与残留的 [attachimg] 标签,
            //          否则图片会一直留在回复栏里(已提交成功却看着像没发出去)
            pendingImageUris.clear()
            uploadedAidMap.clear()
            synchronized(pendingUploadAids) {
                pendingUploadAids.clear()
            }
            synchronized(imageUploadPendingQueue) {
                imageUploadPendingQueue.clear()
            }
            binding.etReply.setText("")
            refreshAllImagePreviews()
            Toast.makeText(this, R.string.reply_success, Toast.LENGTH_SHORT).show()
            if (mBottomSheetDialog != null && mBottomSheetDialog!!.isShowing) {
                mBottomSheetDialog!!.dismiss()
            }
            refreshPostDetail()
        }
    }

    private fun isReplyResponseSuccessful(response: String?): Boolean {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response)) {
            return false
        }
        val lower = response!!.lowercase(Locale.ROOT)
        if (containsAny(
                response, "请先登录", "formhash错误", "非法操作", "没有权限",
                "回复失败", "附件上传失败", "附件不存在", "上传图片失败"
            )
        ) {
            return false
        }
        if (lower.contains("succeedhandle_reply") || lower.contains("succeedhandle_post")
            || lower.contains("succeedhandle_fastpost") || lower.contains("succeedhandle_fastposts")
            || lower.contains("reply_success") || lower.contains("回复发布成功")
            || lower.contains("发布成功")
        ) {
            return true
        }
        return Pattern.compile("[?&](?:tid|pid)=\\d+", Pattern.CASE_INSENSITIVE).matcher(response).find()
    }

    private fun extractReplyError(response: String?): String {
        if (TextUtils.isEmpty(response)) {
            return "回复失败,服务器未返回结果"
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录")) {
            return "登录状态已失效,请重新登录"
        }
        if (containsAny(response, "formhash错误", "非法操作")) {
            return "验证已失效,请刷新页面后重试"
        }
        if (containsAny(response, "附件上传失败", "附件不存在", "上传图片失败")) {
            return "图片附件关联失败,请重新上传后再发送"
        }
        return "回复发布失败,请稍后重试"
    }

    private fun showReplyFailure(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun buildReplyQuote(pid: String?): String? {
        if (TextUtils.isEmpty(pid)) {
            return null
        }
        for (item in displayedReplies) {
            if (item != null && pid == item.pid) {
                val author = if (!TextUtils.isEmpty(item.author)) item.author else "匿名"
                val time = if (!TextUtils.isEmpty(item.time)) item.time else ""
                val content = if (TextUtils.isEmpty(item.contentText)) "" else item.contentText
                return "[quote][color=#999999]" + author + " 发表于 " + time + "[/color]\n" + content + "[/quote]"
            }
        }
        return null
    }

    // ==================== 点赞 / 收藏 ====================

    private fun extractRecommendActionUrl(html: String?): String? {
        if (TextUtils.isEmpty(html)) {
            return null
        }
        try {
            val doc = Jsoup.parse(html!!)
            val link = doc.select("a.comiis_recommend_addkey, a.comiis_recommend_new").first()
                ?: return null
            val href = link.attr("href")
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1)
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href
                }
                return href
            }
            return null
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun appendQuery(url: String?, query: String): String? {
        if (TextUtils.isEmpty(url) || url!!.contains("inajax=")) {
            return url
        }
        return url + (if (url.contains("?")) "&" else "?") + query
    }

    private fun containsAny(text: String?, vararg values: String?): Boolean {
        if (TextUtils.isEmpty(text)) {
            return false
        }
        for (value in values) {
            if (!TextUtils.isEmpty(value) && text!!.contains(value!!)) {
                return true
            }
        }
        return false
    }

    private fun isLikeAddResponseSuccessful(result: String?): Boolean {
        if (TextUtils.isEmpty(result) || ForumParser.isLoginPage(result) || containsAny(
                result, "没有点赞权限", "不能点赞", "今日评价机会已用完",
                "关闭的主题无法执行", "请先登录", "formhash错误", "非法操作"
            )
        ) {
            return false
        }
        return containsAny(
            result, "recommendv", "recommendc", "点赞成功", "推荐成功",
            "succeedhandle_recommend", "评价成功"
        )
    }

    private fun animateBounce(view: View) {
        view.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                    .start()
            }
            .start()
    }

    private fun updateLikeIcon() {
        val button = binding.btnLike
        // build66: 改用与列表页一致的拇指标(原 forum_like 是心形),用颜色区分已赞/未赞
        button.setImageResource(R.drawable.ic_like_detail)
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        button.setColorFilter(if (isLiked) themeColor else getColor(R.color.icon_secondary))
        updateCountBadge(binding.tvLikeBadge, maxOf(0, likeCount))
        // build80: 点赞数回写缓存, 返回列表页时按 tid 回填, 及时同步
        if (!TextUtils.isEmpty(tid)) {
            PostCountsCache.setLikes(tid, maxOf(0, likeCount))
        }
    }

    private fun updateFavoriteIcon() {
        val button = binding.btnFavorite
        val res = if (isFavorited) R.drawable.forum_favorite_on else R.drawable.forum_favorite_off
        button.setImageResource(res)
        if (isFavorited) {
            button.setColorFilter(0xFFF59E0B.toInt())
        } else {
            button.setColorFilter(getColor(R.color.icon_secondary))
        }
    }

    private fun updateCountBadge(badge: TextView?, count: Int) {
        if (badge == null) {
            return
        }
        if (count > 0) {
            // build65: 帖子内角标显示真实数字(原 99+ 截断),超大值才用 999+
            badge.text = if (count > 999) "999+" else count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun insertAtMention() {
        val start = maxOf(0, binding.etReply.selectionStart)
        val text = if (binding.etReply.text == null) "" else binding.etReply.text.toString()
        binding.etReply.setText(
            text.substring(0, minOf(start, text.length)) + "@" + text.substring(minOf(start, text.length))
        )
        binding.etReply.setSelection(minOf(start, text.length) + "@".length)
        binding.etReply.requestFocus()
    }

    private fun toggleFollow() {
        val detail = postDetail
        if (detail == null || TextUtils.isEmpty(detail.authorUid)) {
            return
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            promptLogin()
            return
        }
        val targetState = !detail.isFollowed
        binding.btnFollow.isEnabled = false
        java.lang.Thread {
            val success = FollowStateManager.syncFollow(this, detail.authorUid, targetState)
            runOnUiThread {
                binding.btnFollow.isEnabled = true
                if (!success) {
                    Toast.makeText(this, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                detail.isFollowed = targetState
                binding.btnFollow.setText(
                    if (targetState) R.string.action_followed else R.string.action_follow
                )
                val res = if (targetState) R.string.action_follow_success else R.string.action_unfollow_success
                Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleFavorite() {
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        if (TextUtils.isEmpty(tid) || !binding.btnFavorite.isEnabled) {
            return
        }
        val targetState = !isFavorited
        val oldState = isFavorited
        animateBounce(binding.btnFavorite)
        binding.btnFavorite.isEnabled = false
        java.lang.Thread {
            var success = false
            var errorMessage: String? = null
            try {
                httpClient.syncFromCookieManager()
                if (targetState) {
                    val pageHtml = httpClient.get(
                        ForumParser.getThreadDetailUrl(tid) + "&_favorite_refresh=" + System.currentTimeMillis()
                    )
                    val actionUrl = extractFavoriteActionUrl(pageHtml)
                    if (TextUtils.isEmpty(actionUrl)) {
                        throw IllegalStateException("无法获取收藏操作地址")
                    }
                    val result = httpClient.get(appendQuery(actionUrl, "inajax=1")!!)
                    if (!ForumParser.isLoginPage(result)
                        && !containsAny(result, "请先登录", "没有权限", "非法操作", "formhash错误")
                    ) {
                        val favoritePostUrl = extractFavoriteFormAction(result, actionUrl)
                        if (!TextUtils.isEmpty(favoritePostUrl)) {
                            val formParams = extractFavoriteFormParams(result)
                            formParams["favoritesubmit"] = "true"
                            formParams["favoritesubmit_btn"] = "确定"
                            if (!formParams.containsKey("description")) {
                                formParams["description"] = "手机收藏"
                            }
                            val postResult = httpClient.post(favoritePostUrl!!, formParams)
                            success = isFavoriteMutationResponseSuccessful(postResult, true)
                        } else {
                            success = isFavoriteMutationResponseSuccessful(result, true)
                        }
                    }
                } else {
                    success = requestRemoveFavoriteFromServer()
                }
                val serverState = queryServerFavoriteStateWithRetry(targetState)
                if (serverState != null) {
                    success = serverState == targetState
                }
                if (!success) {
                    errorMessage = "网页端未确认收藏状态已更新"
                }
            } catch (e: Exception) {
                errorMessage = e.message
            }
            val finalSuccess = success
            val finalError = errorMessage
            runOnUiThread {
                binding.btnFavorite.isEnabled = true
                if (finalSuccess) {
                    isFavorited = targetState
                    saveFavoritedState(targetState)
                    postDetail?.let {
                        it.isFavorited = targetState
                        it.favoritedStateKnown = true
                    }
                    updateFavoriteIcon()
                    favoriteCount = maxOf(0, favoriteCount + (if (targetState) 1 else -1))
                    updateCountBadge(binding.tvFavoriteBadge, favoriteCount)
                    Toast.makeText(this, if (targetState) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                isFavorited = oldState
                updateFavoriteIcon()
                Toast.makeText(
                    this,
                    if (TextUtils.isEmpty(finalError)) "收藏操作失败，请稍后重试" else finalError!!,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun extractFavoriteActionUrl(html: String?): String? {
        if (TextUtils.isEmpty(html)) {
            return null
        }
        try {
            val doc = Jsoup.parse(html!!)
            val link = doc.select("#comiis_favorite_a").first() ?: return null
            val href = link.attr("href")
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1)
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href
                }
                return href
            }
            return null
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun extractFavoriteFormAction(response: String?, fallbackUrl: String?): String? {
        if (TextUtils.isEmpty(response)) {
            return null
        }
        try {
            val html = extractCdata(response)
            val doc = Jsoup.parse(html)
            val form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first()
                ?: return null
            var action: String? = form.attr("action")
            if (TextUtils.isEmpty(action)) {
                action = fallbackUrl
            }
            return normalizeForumUrl(action)
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun extractFavoriteFormParams(response: String?): MutableMap<String, String> {
        val params = HashMap<String, String>()
        if (TextUtils.isEmpty(response)) {
            return params
        }
        try {
            val doc = Jsoup.parse(extractCdata(response))
            val form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first()
                ?: return params
            for (input in form.select("input[name], textarea[name], select[name]")) {
                val name = input.attr(ChatActivity.EXTRA_NAME)
                if (!TextUtils.isEmpty(name)) {
                    val value = if (input.tagName().equals("textarea", ignoreCase = true)) input.text()
                    else input.attr("value")
                    params[name] = value
                }
            }
        } catch (ignored: Exception) {
        }
        return params
    }

    private fun extractCdata(response: String?): String {
        if (TextUtils.isEmpty(response)) {
            return ""
        }
        var start = response!!.indexOf("<![CDATA[")
        if (start < 0) {
            start = response.indexOf("<![cdata[")
        }
        if (start < 0) {
            return response
        }
        val contentStart = start + 9
        val end = response.indexOf("]]>", contentStart)
        return if (end >= 0) response.substring(contentStart, end) else response.substring(contentStart)
    }

    private fun normalizeForumUrl(url: String?): String? {
        if (TextUtils.isEmpty(url) || url!!.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        return if (url.startsWith("/")) HttpClient.BASE_URL + url.substring(1) else HttpClient.BASE_URL + url
    }

    private fun isFavoriteMutationResponseSuccessful(response: String?, targetState: Boolean): Boolean {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response)
            || containsAny(
                response, "请先登录", "没有权限", "非法操作", "formhash错误",
                "收藏失败", "取消收藏失败"
            )
        ) {
            return false
        }
        if (targetState) {
            return containsAny(
                response, "succeedhandle_favorite_add", "succeedhandle_favorite_thread",
                "收藏成功", "信息收藏成功", "已收藏", "重复收藏"
            )
        }
        return containsAny(
            response, "succeedhandle_favorite_del", "succeedhandle_favorite_thread",
            "取消收藏成功", "已取消收藏", "删除成功", "取消成功"
        )
    }

    @Throws(Exception::class)
    private fun requestRemoveFavoriteFromServer(): Boolean {
        val page = httpClient.get(
            "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove=" +
                    System.currentTimeMillis()
        )
        if (ForumParser.isLoginPage(page)) {
            return false
        }
        var favid = ""
        val favorites = ForumParser.parseFavoriteList(page)
        if (favorites != null) {
            for (item in favorites) {
                if (item != null && tid == item.tid) {
                    favid = item.favid ?: ""
                    break
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            val matcher = Pattern.compile(
                "(?:favid|fav_id)=(\\d+)[^<>]{0,300}(?:tid=" + Pattern.quote(tid) +
                        "|thread-" + Pattern.quote(tid) + "-)",
                Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
            ).matcher(page)
            if (matcher.find()) {
                favid = matcher.group(1)
            }
            if (TextUtils.isEmpty(favid)) {
                val matcher2 = Pattern.compile(
                    "(?:tid=" + Pattern.quote(tid) + "|thread-" + Pattern.quote(tid) +
                            "-)[^<>]{0,300}(?:favid|fav_id)=(\\d+)",
                    Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
                ).matcher(page)
                if (matcher2.find()) {
                    favid = matcher2.group(1)
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            return false
        }
        var formhash = ForumParser.parseFormhash(page)
        if (TextUtils.isEmpty(formhash)) {
            val detailHtml = httpClient.get(
                ForumParser.getThreadDetailUrl(tid) + "&_favorite_remove_hash=" + System.currentTimeMillis()
            )
            formhash = ForumParser.parseFormhash(detailHtml)
        }
        val deleteUrl = "https://bbs.binmt.cc/home.php?mod=spacecp&ac=favorite&op=delete&favid=" +
                favid + "&type=all&mobile=2"
        val params = HashMap<String, String>()
        params["referer"] = "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2"
        params["deletesubmit"] = "true"
        if (!TextUtils.isEmpty(formhash)) {
            params["formhash"] = formhash!!
        }
        params["handlekey"] = "comiis"
        val response = httpClient.post(deleteUrl, params)
        val verifyHtml = httpClient.get(
            "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove_verify=" +
                    System.currentTimeMillis()
        )
        val stillExists = containsFavoriteTid(verifyHtml, tid)
        return !stillExists || containsAny(
            response, "succeedhandle_favorite_del", "删除成功", "取消收藏成功", "取消成功"
        )
    }

    private fun containsFavoriteTid(html: String?, targetTid: String?): Boolean {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(targetTid)) {
            return false
        }
        val favorites = ForumParser.parseFavoriteList(html)
        if (favorites != null) {
            for (item in favorites) {
                if (item != null && targetTid == item.tid) {
                    return true
                }
            }
        }
        return Pattern.compile(
            "(?:[?&]tid=" + Pattern.quote(targetTid) + "(?:&|\\\"|')|thread-" +
                    Pattern.quote(targetTid) + "(?:-|\\.))",
            Pattern.CASE_INSENSITIVE
        ).matcher(html!!).find()
    }

    private fun queryServerFavoriteStateWithRetry(targetState: Boolean): Boolean? {
        var lastState: Boolean? = null
        val delays = longArrayOf(0, 250, 600, 1200, 2000)
        for (delay in delays) {
            if (delay > 0) {
                try {
                    java.lang.Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
            val detailState = queryServerFavoriteDetailState()
            if (detailState != null) {
                lastState = detailState
                if (detailState == targetState) {
                    return detailState
                }
            }
            val listState = queryServerFavoriteListState()
            if (listState != null) {
                lastState = listState
                if (listState == targetState) {
                    return listState
                }
            }
        }
        return lastState
    }

    private fun queryServerFavoriteDetailState(): Boolean? {
        try {
            val html = httpClient.get(
                ForumParser.getThreadDetailUrl(tid) + "&_favorite_verify=" + System.currentTimeMillis()
            )
            if (!ForumParser.isLoginPage(html)) {
                val server = ForumParser.parseThreadDetail(html)
                if (server.favoritedStateKnown) {
                    return server.isFavorited
                }
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun queryServerFavoriteListState(): Boolean? {
        try {
            val html = httpClient.get(
                "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_verify=" +
                        System.currentTimeMillis()
            )
            if (ForumParser.isLoginPage(html)) {
                return null
            }
            val favorites = ForumParser.parseFavoriteList(html)
            if (favorites != null) {
                for (item in favorites) {
                    if (item != null && tid == item.tid) {
                        return true
                    }
                }
            }
            if (TextUtils.isEmpty(tid)
                || !Pattern.compile(
                    "thread-" + Pattern.quote(tid) + "(?:-|\\.)", Pattern.CASE_INSENSITIVE
                ).matcher(html).find()
            ) {
                return false
            }
            return true
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun toggleLike() {
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        if (TextUtils.isEmpty(tid) || !binding.btnLike.isEnabled) {
            return
        }
        val currentUid = UserSessionManager.getInstance().getUid(applicationContext)
        val authorUid = postDetail?.authorUid
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid == authorUid) {
            Toast.makeText(this, "不能点赞自己的帖子", Toast.LENGTH_SHORT).show()
            return
        }
        val targetState = !isLiked
        val oldState = isLiked
        val oldCount = likeCount
        isLiked = targetState
        likeCount = maxOf(0, likeCount + (if (targetState) 1 else -1))
        animateBounce(binding.btnLike)
        updateLikeIcon()
        binding.btnLike.isEnabled = false
        java.lang.Thread {
            var success = false
            var errorMessage: String? = null
            try {
                httpClient.syncFromCookieManager()
                val pageHtml = httpClient.getDesktop(
                    ForumParser.getThreadDetailUrl(tid) + "&_action_refresh=" + System.currentTimeMillis()
                )
                var formhash = postDetail?.formhash
                if (TextUtils.isEmpty(formhash)) {
                    formhash = ForumParser.parseFormhash(pageHtml)
                }
                var recommendUrl = extractRecommendActionUrl(pageHtml)
                if (TextUtils.isEmpty(recommendUrl)) {
                    if (!TextUtils.isEmpty(formhash)) {
                        recommendUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=recommend" +
                                "&handlekey=recommend_add&do=add&tid=" + tid + "&hash=" + formhash
                    } else {
                        throw IllegalStateException("无法获取formhash")
                    }
                }
                val result = httpClient.get(appendQuery(recommendUrl, "inajax=1")!!)
                if (!targetState) {
                    val alreadyLiked = containsAny(
                        result, "您已评价过本主题", "您已经评价过本主题", "已经评价过本主题"
                    )
                    if (alreadyLiked || isLikeAddResponseSuccessful(result)) {
                        val cancelUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=re_recommend&tid=" +
                                tid + "&inajax=1"
                        val cancelResult = httpClient.get(cancelUrl)
                        success = !(ForumParser.isLoginPage(cancelResult)
                                || containsAny(cancelResult, "没有权限", "操作失败", "非法操作", "请先登录"))
                    }
                } else {
                    success = isLikeAddResponseSuccessful(result)
                }
                val serverState = queryServerLikeState()
                if (serverState != null) {
                    success = serverState == targetState
                }
                if (!success) {
                    errorMessage = "网页端未确认点赞状态已更新"
                }
            } catch (e: Exception) {
                errorMessage = e.message
            }
            val finalSuccess = success
            val finalError = errorMessage
            runOnUiThread {
                binding.btnLike.isEnabled = true
                if (finalSuccess) {
                    saveLikedState(targetState)
                    postDetail?.let {
                        it.isLiked = targetState
                        it.likedStateKnown = true
                    }
                    Toast.makeText(this, if (targetState) "已点赞" else "已取消点赞", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                isLiked = oldState
                likeCount = oldCount
                updateLikeIcon()
                Toast.makeText(
                    this,
                    if (TextUtils.isEmpty(finalError)) "点赞操作失败，请稍后重试" else finalError!!,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun queryServerLikeState(): Boolean? {
        try {
            val html = httpClient.get(
                ForumParser.getThreadDetailUrl(tid) + "&_action_refresh=" + System.currentTimeMillis()
            )
            val server = ForumParser.parseThreadDetail(html)
            if (!server.likedStateKnown) {
                return null
            }
            return server.isLiked
        } catch (ignored: Exception) {
            return null
        }
    }

    // ==================== 正文与图片处理 ====================

    private fun replaceHiddenQuoteWithPlaceholder(html: String?): Array<String> {
        if (html == null) {
            return arrayOf("", "")
        }
        val openP = Pattern.compile("<div\\s+class=\"(?:comiis_quote|locked)[^\"]*\"", Pattern.CASE_INSENSITIVE)
        val m = openP.matcher(html)
        if (!m.find()) {
            return arrayOf(html, "")
        }
        val openQuote = m.start()
        var depth = 0
        var i = openQuote
        var end = html.length
        while (true) {
            if (i >= html.length) break
            val lt = html.indexOf(60.toChar(), i)
            if (lt < 0) break
            val gt = html.indexOf(62.toChar(), lt)
            if (gt < 0) break
            val tag = html.substring(lt + 1, gt).trim().lowercase()
            if (tag.startsWith("/")) {
                if (tag.startsWith("/div") && depth - 1 <= 0) {
                    end = gt + 1
                    break
                }
            } else if (tag.startsWith("div")) {
                depth++
            }
            i = gt + 1
        }
        if (end >= html.length) {
            return arrayOf(html, "")
        }
        val block = html.substring(openQuote, end)
        var text = Regex("<[^>]+>").replace(block, " ")
        text = Regex("&nbsp;").replace(text, " ")
        text = Regex("\\s+").replace(text, " ").trim()
        if (text.isEmpty()) {
            return arrayOf(html, "")
        }
        // 摘要过长时截断,避免胶囊占满整行
        if (text.length > 28) {
            text = text.substring(0, 28) + "..."
        }
        val clean = html.substring(0, openQuote) + HIDDEN_QUOTE_PLACEHOLDER + html.substring(end)
        return arrayOf(clean, text)
    }

    private fun applyHiddenNoticeHighlight(text: CharSequence?, notice: String?) {
        if (text is Spannable && !TextUtils.isEmpty(notice)) {
            val idx = text.toString().indexOf(HIDDEN_QUOTE_PLACEHOLDER)
            if (idx < 0) {
                return
            }
            text.setSpan(
                HiddenNoticeSpan(
                    notice!!, dpToPx(9).toFloat(), dpToPx(12).toFloat(),
                    dpToPx(11).toFloat(), -854017, -14721112
                ),
                idx, HIDDEN_QUOTE_PLACEHOLDER.length + idx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or 0x11
            )
        }
    }

    private class HiddenNoticeSpan(
        private val text: String,
        private val radiusPx: Float,
        private val paddingPx: Float,
        private val textSizePx: Float,
        private val bgColor: Int,
        private val textColor: Int
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
        ): Int {
            paint.setTextSize(textSizePx)
            val w = paint.measureText(this.text)
            if (fm != null) {
                val fmi = paint.fontMetricsInt
                fm.ascent = fmi.ascent
                fm.descent = fmi.descent
                fm.top = fmi.top
                fm.bottom = fmi.bottom
            }
            return Math.round(paddingPx * 2.0f + w)
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            paint.isAntiAlias = true
            val oldColor = paint.color
            paint.setTextSize(textSizePx)
            val fmi = paint.fontMetricsInt
            val textW = paint.measureText(this.text)
            val left = x + 1.0f
            val right = (x + textW + paddingPx * 2.0f) - 1.0f
            val rectTop = top + 3.0f
            val rectBottom = bottom - 3.0f
            val bg = Paint(1)
            bg.color = bgColor
            canvas.drawRoundRect(RectF(left, rectTop, right, rectBottom), radiusPx, radiusPx, bg)
            paint.color = textColor
            val baseline = ((top + bottom) - fmi.ascent - fmi.descent) / 2.0f
            canvas.drawText(this.text, paddingPx + left, baseline, paint)
            paint.color = oldColor
        }
    }

    private fun adjustEditFooterDividerWidth() {
        binding.tvEditFooter.post {
            if (binding.tvEditFooter.visibility != View.VISIBLE) {
                return@post
            }
            val text = binding.tvEditFooter.text.toString().trim()
            if (TextUtils.isEmpty(text)) {
                return@post
            }
            val textW = binding.tvEditFooter.paint.measureText(text)
            val divider = binding.viewEditDivider
            val lp = divider.layoutParams as LinearLayout.LayoutParams
            val parentW = divider.measuredWidth
            if (parentW <= 0) {
                return@post
            }
            val w = minOf(dpToPx(2) + textW.toInt(), parentW)
            lp.width = w
            lp.gravity = Gravity.CENTER
            divider.layoutParams = lp
            val dividerTop = binding.viewEditDividerTop
            val lpTop = dividerTop.layoutParams as LinearLayout.LayoutParams
            lpTop.width = w
            lpTop.gravity = Gravity.CENTER
            dividerTop.layoutParams = lpTop
        }
    }

    private fun splitEditFooter(html: String?): Array<String> {
        if (html == null) {
            return arrayOf("", "")
        }
        var marker = "本帖最后由"
        var idx = html.indexOf("本帖最后由")
        if (idx < 0) {
            marker = "本贴最后由"
            idx = html.indexOf("本贴最后由")
        }
        if (idx >= 0) {
            val endIdx = html.indexOf("编辑", marker.length + idx)
            if (endIdx >= 0) {
                var start = idx
                while (true) {
                    val lt2 = html.lastIndexOf(60.toChar(), start - 1)
                    if (lt2 < 0) break
                    val gt = html.indexOf(62.toChar(), lt2)
                    if (gt < 0 || gt > start) break
                    if (html.substring(gt + 1, start).trim().isNotEmpty()) break
                    val tag = html.substring(lt2 + 1, gt).trim()
                    if (!tag.startsWith("span") && !tag.startsWith("b") && !tag.startsWith("br")
                        && !tag.startsWith("font") && !tag.startsWith("i")
                        && !tag.startsWith("em") && !tag.startsWith("strong")
                    ) {
                        break
                    }
                    start = lt2
                }
                val editEnd = endIdx + 2
                var end = editEnd
                while (true) {
                    val gt2 = html.indexOf(62.toChar(), end)
                    if (gt2 < 0) break
                    val lt = html.lastIndexOf(60.toChar(), gt2)
                    if (lt < end) break
                    if (html.substring(end, lt).trim().isNotEmpty()) break
                    val tag2 = html.substring(lt + 1, gt2).trim()
                    if (!tag2.startsWith("/span") && !tag2.startsWith("/b") && !tag2.startsWith("/font")
                        && !tag2.startsWith("/i") && !tag2.startsWith("/em")
                        && !tag2.startsWith("/strong") && !tag2.startsWith("br") && !tag2.endsWith("/")
                    ) {
                        break
                    }
                    end = gt2 + 1
                }
                var footer = Regex("<[^>]+>").replace(html.substring(idx, editEnd), "")
                footer = Regex("&nbsp;").replace(footer, " ").trim()
                val clean = html.substring(0, start) + html.substring(end)
                return arrayOf(clean, footer)
            }
        }
        return arrayOf(html, "")
    }

    private fun renderHiddenContent(hiddenHtml: String?) {
        if (TextUtils.isEmpty(hiddenHtml)) {
            return
        }
        binding.tvHiddenContent.visibility = View.VISIBLE
        val bbcodeConverted = BBCodeUtil.convertBBCodeToHtml(hiddenHtml!!)
        val hiddenImageUrls = ArrayList<String>()
        val cleanHiddenHtml = extractAndSeparateImages(bbcodeConverted, hiddenImageUrls)
        binding.tvHiddenContent.text = Html.fromHtml(
            cleanHiddenHtml, Html.FROM_HTML_MODE_COMPACT,
            createInlineImageGetter(binding.tvHiddenContent),
            BBCodeUtil.createTagHandler(this)
        )
        setupClickableLinks(binding.tvHiddenContent)
        for (imgUrl in hiddenImageUrls) {
            val imageView = ImageView(this)
            imageView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION)
            )
            imageView.adjustViewBounds = true
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            (imageView.layoutParams as LinearLayout.LayoutParams).setMargins(dpToPx(4), 0, dpToPx(4), 0)
            imageView.setOnClickListener { openImagePreview(imgUrl) }
            Glide.with(this as FragmentActivity).load(imgUrl)
                .placeholder(ColorDrawable(getColor(R.color.background_secondary)))
                .error(ColorDrawable(getColor(R.color.divider)))
                .into(imageView)
            binding.llImageGallery.addView(imageView)
        }
        binding.cardImageGallery.visibility = View.VISIBLE
        FrostedGlassHelper.applyToCardViews(binding.cardImageGallery, this)
    }

    private fun viewHiddenContent() {
        val detail = postDetail
        if (detail == null || !detail.hasHiddenContent) {
            return
        }
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        binding.tvHiddenContentHint.visibility = View.GONE
        binding.btnViewHidden.visibility = View.GONE
        if (!TextUtils.isEmpty(detail.hiddenContentHtml)) {
            renderHiddenContent(detail.hiddenContentHtml)
        } else {
            Toast.makeText(this, R.string.hidden_content_prompt, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 进入帖子发现是「回复可见」时，后台自动回复一次以解锁，成功后刷新隐藏内容区。
     * 受 AiConfigManager.isUnlockOnView 开关控制，且同一帖子只尝试一次。
     */
    private fun maybeAutoUnlock() {
        if (postDetail == null) {
            AiLog.i("auto-unlock", "跳过：详情未就绪")
            return
        }
        val tid = this.tid
        if (!AiConfigManager.isUnlockOnView(this)) {
            AiLog.i("auto-unlock", "跳过：进帖自动解锁开关关闭 tid=$tid")
            return
        }
        if (TextUtils.isEmpty(tid)) {
            AiLog.i("auto-unlock", "跳过：tid 为空")
            return
        }
        if (autoUnlocking) {
            AiLog.i("auto-unlock", "跳过：本轮正在解锁中 tid=$tid")
            return
        }
        if (autoUnlockTried.contains(tid)) {
            AiLog.i("auto-unlock", "跳过：本页已尝试过 tid=$tid")
            return
        }
        if (!httpClient.isLoggedIn()) {
            httpClient.syncFromCookieManager()
            if (!httpClient.isLoggedIn()) {
                AiLog.i("auto-unlock", "跳过：未登录 tid=$tid")
                return
            }
        }
        // 注意：进帖自动解锁是用户明确开启的动作，不能再被「演练模式」拦掉，
        // 否则会出现「开关明明开着、却一直不解锁」的错觉。
        if (AiConfigManager.isDryRun(this)) {
            AiLog.i("auto-unlock", "提示：演练模式开着，但进帖解锁不受它影响，继续执行 tid=$tid")
        }
        autoUnlocking = true
        autoUnlockTried.add(tid!!)
        AiLog.i("auto-unlock", "→ 进入帖子发现隐藏内容，尝试自动回复解锁 tid=$tid")

        java.lang.Thread({
            var ok = false
            try {
                ok = AutoReplyEngine.unlockSingleThread(this, tid)
            } catch (e: Exception) {
                android.util.Log.w("ThreadDetail", "auto unlock failed", e)
                AiLog.e("auto-unlock", "解锁异常 tid=$tid $e")
            }
            val success = ok
            runOnUiThread {
                autoUnlocking = false
                AiLog.i("auto-unlock", "本轮结束 tid=$tid 成功=$success")
                if (success) {
                    Toast.makeText(this, "已自动回复并解锁隐藏内容", Toast.LENGTH_SHORT).show()
                    refreshPostDetail()
                }
            }
        }, "auto-unlock").start()
    }

    private var lastPreloadTriggerCount = 0

    /**
     * 顺序拉取后续页面，直到回复总数达到 targetCount 或无更多页
     */
    private fun fetchRepliesUpTo(detail: PostDetail, targetCount: Int) {
        var curTotalPages = detail.totalPages
        var nextPage = detail.currentPage + 1
        while ((detail.replies?.size ?: 0) < targetCount && nextPage <= curTotalPages) {
            try {
                val nextUrl = ForumParser.getThreadDetailUrl(tid, nextPage, getReplyOrder())
                val nextHtml = httpClient.get(nextUrl)
                if (!TextUtils.isEmpty(nextHtml)) {
                    val nextPageDetail = ForumParser.parseThreadDetail(nextHtml)
                    val nextReplies = nextPageDetail.replies
                    if (!nextReplies.isNullOrEmpty()) {
                        val currentReplies = detail.replies ?: ArrayList()
                        currentReplies.addAll(nextReplies)
                        detail.replies = currentReplies
                    }
                    detail.currentPage = maxOf(detail.currentPage, nextPageDetail.currentPage, nextPage)
                    if (nextPageDetail.totalPages > curTotalPages) {
                        curTotalPages = nextPageDetail.totalPages
                        detail.totalPages = curTotalPages
                    }
                } else {
                    detail.currentPage = maxOf(detail.currentPage, nextPage)
                }
            } catch (_: Exception) {
                break
            }
            nextPage++
        }
    }

    private fun checkAndPreloadReplies() {
        if (postDetail == null || isLoadingMore) return
        val detail = postDetail ?: return
        if (detail.currentPage >= detail.totalPages) return
        val totalCount = replyAdapter?.itemCount ?: 0
        if (totalCount != lastPreloadTriggerCount) {
            lastPreloadTriggerCount = totalCount
            loadMoreReplies()
        }
    }

    private fun checkReplyPreload(scrollView: NestedScrollView, scrollY: Int) {
        if (postDetail == null || isLoadingMore) return
        val detail = postDetail ?: return
        if (detail.currentPage >= detail.totalPages) return

        val totalCount = replyAdapter?.itemCount ?: 0
        if (totalCount == lastPreloadTriggerCount) return

        val lm = binding.recyclerReplies.layoutManager as? LinearLayoutManager
        val triggerIndex = if (totalCount <= 15) {
            maxOf(0, totalCount - 2)
        } else {
            maxOf(0, totalCount - 6)
        }
        val triggerView = lm?.findViewByPosition(triggerIndex)
        val viewportBottom = scrollY + scrollView.height
        var shouldTrigger = false

        if (triggerView != null) {
            val triggerTopInScroll = triggerView.top + binding.recyclerReplies.top
            if (viewportBottom >= triggerTopInScroll) {
                shouldTrigger = true
            }
        } else {
            val scrollContent = scrollView.getChildAt(0)
            if (scrollContent != null) {
                val contentHeight = scrollContent.height - scrollView.height
                if (scrollY >= contentHeight - 1000) {
                    shouldTrigger = true
                }
            }
        }

        if (shouldTrigger) {
            lastPreloadTriggerCount = totalCount
            loadMoreReplies()
        }
    }

    private fun loadMoreReplies() {
        if (postDetail == null || isLoadingMore) {
            return
        }
        val curDetail = postDetail!!
        if (curDetail.currentPage >= curDetail.totalPages) {
            return
        }
        isLoadingMore = true
        binding.btnLoadMore.isEnabled = false
        binding.btnLoadMore.setText(R.string.loading)
        binding.loadingMore.visibility = View.VISIBLE
        java.lang.Thread {
            try {
                var totalPages = curDetail.totalPages
                val allNewReplies = ArrayList<ReplyItem>()
                var page = curDetail.currentPage + 1
                while (allNewReplies.size < 20 && page <= totalPages) {
                    val pageUrl = ForumParser.getThreadDetailUrl(tid, page, getReplyOrder())
                    val html = httpClient.get(pageUrl)
                    if (!TextUtils.isEmpty(html)) {
                        val pageDetail = ForumParser.parseThreadDetail(html)
                        val pageReplies = pageDetail.replies
                        if (!pageReplies.isNullOrEmpty()) {
                            allNewReplies.addAll(pageReplies)
                        }
                        curDetail.currentPage = maxOf(curDetail.currentPage, pageDetail.currentPage, page)
                        if (pageDetail.totalPages > totalPages) {
                            totalPages = pageDetail.totalPages
                            curDetail.totalPages = totalPages
                        }
                    } else {
                        curDetail.currentPage = maxOf(curDetail.currentPage, page)
                    }
                    page++
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isLoadingMore = false
                    binding.btnLoadMore.isEnabled = true
                    binding.btnLoadMore.setText(R.string.load_more_replies)
                    binding.loadingMore.visibility = View.GONE
                    if (!allNewReplies.isEmpty()) {
                        val bl = BlacklistManager.uidSet(this)
                        if (!bl.isEmpty()) {
                            val itr = allNewReplies.iterator()
                            while (itr.hasNext()) {
                                val r = itr.next()
                                if (r != null && r.authorUid != null && bl.contains(r.authorUid)) itr.remove()
                            }
                        }
                        val merged = ArrayList<ReplyItem>(curDetail.replies ?: ArrayList())
                        merged.addAll(allNewReplies)
                        curDetail.replies = merged
                        displayedReplies = ArrayList(merged)
                        updateReplyFilterAndOrder()
                    } else {
                        Toast.makeText(this, R.string.no_more_replies, Toast.LENGTH_SHORT).show()
                    }
                binding.btnLoadMore.visibility = if (curDetail.currentPage < curDetail.totalPages) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isLoadingMore = false
                    binding.btnLoadMore.isEnabled = true
                    binding.btnLoadMore.setText(R.string.load_more_replies)
                    binding.loadingMore.visibility = View.GONE
                    Toast.makeText(this, "加载失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ==================== 本地状态缓存 ====================

    private fun restoreLikedState(): Boolean {
        if (TextUtils.isEmpty(tid)) {
            return false
        }
        return getSharedPreferences(PREF_LIKE_FAV, 0).getBoolean(KEY_LIKED_PREFIX + tid, false)
    }

    private fun saveLikedState(liked: Boolean) {
        if (TextUtils.isEmpty(tid)) {
            return
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit().putBoolean(KEY_LIKED_PREFIX + tid, liked).apply()
    }

    private fun restoreFavoritedState(): Boolean {
        if (TextUtils.isEmpty(tid)) {
            return false
        }
        return getSharedPreferences(PREF_LIKE_FAV, 0).getBoolean(KEY_FAVORITED_PREFIX + tid, false)
    }

    private fun saveFavoritedState(favorited: Boolean) {
        if (TextUtils.isEmpty(tid)) {
            return
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit().putBoolean(KEY_FAVORITED_PREFIX + tid, favorited).apply()
    }

    private fun applyServerActionState(detail: PostDetail?) {
        if (detail == null) {
            return
        }
        if (detail.likedStateKnown) {
            isLiked = detail.isLiked
            saveLikedState(isLiked)
        } else {
            isLiked = restoreLikedState()
        }
        if (detail.favoritedStateKnown) {
            isFavorited = detail.isFavorited
            saveFavoritedState(isFavorited)
        } else {
            isFavorited = restoreFavoritedState()
        }
    }

    private fun syncFavoriteStateFromServer(detail: PostDetail?) {
        if (detail == null || TextUtils.isEmpty(tid) || !httpClient.isLoggedIn()) {
            return
        }
        try {
            val html = httpClient.get(
                "https://bbs.binmt.cc/home.php?mod=space&do=favorite&mobile=2&_refresh=" +
                        System.currentTimeMillis()
            )
            if (ForumParser.isLoginPage(html)) {
                return
            }
            val favorites = ForumParser.parseFavoriteList(html)
            var found = false
            if (favorites != null) {
                for (item in favorites) {
                    if (item != null && tid == item.tid) {
                        found = true
                        break
                    }
                }
            }
            detail.isFavorited = found
            detail.favoritedStateKnown = true
        } catch (ignored: Exception) {
        }
    }

    private fun refreshServerActionState(detail: PostDetail?) {
        if (detail == null) {
            return
        }
        applyServerActionState(detail)
        syncFavoriteStateFromServer(detail)
        applyServerActionState(detail)
    }

    // ==================== 点赞人列表 ====================

    /** 绑定点赞人头像行(登录态才有数据;未登录/无数据时隐藏) */
    private fun bindLikeUsers(detail: PostDetail) {
        if (likeUsersAdapter == null) {
            likeUsersAdapter = LikeUsersAdapter(object : LikeUsersAdapter.OnUserClickListener {
                override fun onUserClick(uid: String?, name: String?) {
                    val intent = Intent(this@ThreadDetailActivity, UserProfileActivity::class.java)
                    intent.putExtra(ChatActivity.EXTRA_UID, uid)
                    intent.putExtra("username", if (name != null) name else "")
                    startActivity(intent)
                }
            })
            binding.rvLikeUsers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.rvLikeUsers.adapter = likeUsersAdapter
            binding.tvLikeMore.setOnClickListener { showLikeUsersSheet(detail) }
            binding.layoutLikeUsers.setOnClickListener { showLikeUsersSheet(detail) }
        }
        val uids = detail.likeUserUids
        val avatars = detail.likeUserAvatars
        val names = detail.likeUserNames
        if (uids != null && !uids.isEmpty()) {
            likeUsersAdapter!!.setData(uids, avatars, names)
            binding.layoutLikeUsers.visibility = View.VISIBLE
        } else {
            binding.layoutLikeUsers.visibility = View.GONE
        }
    }

    /** 底部弹层:全部点赞人(头像+用户名,可滚动) */
    private fun showLikeUsersSheet(detail: PostDetail) {
        var uids = detail.likeUserUids
        var avatars = detail.likeUserAvatars
        var names = detail.likeUserNames
        if (uids == null || uids.isEmpty()) {
            Toast.makeText(this, "暂无点赞数据", Toast.LENGTH_SHORT).show()
            return
        }
        // 防空兜底: PostDetail 三字段默认 null(ForumParser 只在非空时才 set),
        // 不兜底时 names.size()/avatars.size() 会 NPE, 表现为"查看全部"闪退回主页。
        if (names == null) names = ArrayList()
        if (avatars == null) avatars = ArrayList()
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dpToPx(16)
        root.setPadding(pad, pad, pad, pad)
        // 标题
        val title = TextView(this)
        title.text = "赞过此帖的人 (${uids.size})"
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.text_primary))
        title.setPadding(0, 0, 0, dpToPx(12))
        root.addView(title)
        // 滚动容器
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // build67: 收集每行引用, 供接口回填真实昵称/头像
        val nameViews = ArrayList<TextView>()
        val imgViews = ArrayList<ShapeableImageView>()
        val rowViews = ArrayList<LinearLayout>()
        for (i in uids.indices) {
            val uid = uids[i]
            val name = if (i < names.size && names[i] != null && !names[i]!!.isEmpty())
                names[i]!! else "用户$uid"
            val avatar = if (i < avatars.size) avatars[i] else null
            // build65: 每行 = 头像 + 名字(原来只有 TextView,头像数据白拿)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6))
            val iv = ShapeableImageView(this)
            val av = dpToPx(36)
            val ivLp = LinearLayout.LayoutParams(av, av)
            ivLp.marginEnd = dpToPx(10)
            iv.layoutParams = ivLp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageResource(R.drawable.ic_account)
            if (!TextUtils.isEmpty(avatar)) {
                Glide.with(this).load(avatar).circleCrop()
                    .placeholder(ColorDrawable(0xFFE0E0E0.toInt()))
                    .error(ColorDrawable(0xFFBDBDBD.toInt()))
                    .into(iv)
            }
            val tv = TextView(this)
            tv.text = name
            tv.textSize = 15f
            tv.setTextColor(getColor(R.color.text_primary))
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(iv)
            row.addView(tv)
            row.setOnClickListener {
                sheet.dismiss()
                val intent = Intent(this, UserProfileActivity::class.java)
                intent.putExtra(ChatActivity.EXTRA_UID, uid)
                intent.putExtra("username", name)
                startActivity(intent)
            }
            list.addView(row)
            rowViews.add(row)
            imgViews.add(iv)
            nameViews.add(tv)
        }
        sheet.setContentView(root)
        sheet.show()
        // build67: 弹窗先用详情页数据即时渲染, 后台拉独立接口补"真实昵称+头像"
        // (接口免登录、一次性返回全部点赞人, 只在用户点"查看全部"时发 1 发)
        val tidForLikers = tid
        java.lang.Thread({
            val items = LikeUserFetcher.fetch(tidForLikers)
            if (items.isEmpty()) {
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                var k = 0
                while (k < rowViews.size && k < items.size) {
                    val it = items[k]
                    nameViews[k].text = it.name
                    if (!TextUtils.isEmpty(it.avatar)) {
                        Glide.with(this)
                            .load(it.avatar).circleCrop()
                            .placeholder(ColorDrawable(0xFFE0E0E0.toInt()))
                            .error(ColorDrawable(0xFFBDBDBD.toInt()))
                            .into(imgViews[k])
                    }
                    k++
                }
            }
        }, "like-user-fetch").start()
    }

    // ==================== 图片与附件 ====================

    private fun openImagePreview(url: String?) {
        if (TextUtils.isEmpty(url)) {
            return
        }
        val intent = Intent(this, ImagePreviewActivity::class.java)
        // 多图: 传整个图组+当前图位置,可左右翻页
        val list = ArrayList(currentImageList)
        if (list.isEmpty() || !list.contains(url)) {
            list.add(url!!)
        }
        if (list.size > 1) {
            intent.putStringArrayListExtra("image_urls", ArrayList(list))
            intent.putExtra("image_index", list.indexOf(url))
        } else {
            intent.putExtra("image_url", url)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开图片", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_IMAGE_PICK)
    }

    /** 添加待发送图片到列表,显示预览,并开始逐张上传 */
    private fun addPendingImages(uris: List<Uri>?) {
        if (uris == null) return
        for (uri in uris) {
            if (!pendingImageUris.contains(uri)) {
                pendingImageUris.add(uri)
                uploadPendingImage(uri)
            }
        }
        refreshAllImagePreviews()
    }

    /** 删除待发送图片,移除对应的 [attachimg] 标签 */
    private fun removePendingImage(uri: Uri) {
        pendingImageUris.remove(uri)
        val aid = uploadedAidMap.remove(uri)
        if (aid != null) {
            val tag = "[attachimg]" + aid + "[/attachimg]"
            var cur = binding.etReply.text.toString()
            cur = cur.replace(tag, "")
            binding.etReply.setText(cur)
            binding.etReply.setSelection(binding.etReply.length())
            synchronized(pendingUploadAids) {
                pendingUploadAids.remove(aid)
            }
        }
        refreshAllImagePreviews()
    }

    /** 刷新所有图片预览(底部回复栅 + 弹窗) */
    private fun refreshAllImagePreviews() {
        updateInlineImagePreview()
        if (mBottomSheetDialog != null && mBottomSheetDialog!!.isShowing) {
            updateDialogImagePreview()
        }
    }

    /** 更新底部回复栅的图片预览 */
    private fun updateInlineImagePreview() {
        val hsv = binding.root.findViewById<android.widget.HorizontalScrollView>(R.id.hsv_inline_image_preview)
        val ll = binding.root.findViewById<LinearLayout>(R.id.ll_inline_image_preview)
        if (hsv == null || ll == null) return
        if (pendingImageUris.isEmpty()) {
            hsv.visibility = View.GONE
            return
        }
        hsv.visibility = View.VISIBLE
        ll.removeAllViews()
        for (uri in pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri))
        }
    }

    /** 更新底部弹窗的图片预览 */
    private fun updateDialogImagePreview() {
        if (mBottomSheetDialog == null) return
        val hsv = mBottomSheetDialog!!.findViewById<android.widget.HorizontalScrollView>(R.id.hsv_image_preview)
        val ll = mBottomSheetDialog!!.findViewById<LinearLayout>(R.id.ll_image_preview)
        if (hsv == null || ll == null) return
        if (pendingImageUris.isEmpty()) {
            hsv.visibility = View.GONE
            return
        }
        hsv.visibility = View.VISIBLE
        ll.removeAllViews()
        for (uri in pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri))
        }
    }

    /** 构建单个图片缩略图(含右上角删除按钮) */
    private fun buildPreviewThumbnail(uri: Uri): View {
        val size = dpToPx(72)
        val frame = android.widget.FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(size, size)
        frame.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))

        val iv = ShapeableImageView(this)
        iv.layoutParams = android.widget.FrameLayout.LayoutParams(size - dpToPx(4), size - dpToPx(4))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dpToPx(6).toFloat())
            .build()
        Glide.with(this).load(uri).centerCrop().into(iv)
        frame.addView(iv)

        val btnSize = dpToPx(22)
        val lp = android.widget.FrameLayout.LayoutParams(btnSize, btnSize)
        lp.gravity = Gravity.TOP or Gravity.END
        val btnDelete = ImageButton(this)
        btnDelete.layoutParams = lp
        btnDelete.setImageResource(R.drawable.ic_cross)
        btnDelete.scaleType = ImageView.ScaleType.FIT_CENTER
        btnDelete.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        btnDelete.setColorFilter(0xFFFFFFFF.toInt())
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0x99000000.toInt())
        bg.setSize(btnSize, btnSize)
        btnDelete.background = bg
        btnDelete.setOnClickListener { removePendingImage(uri) }
        frame.addView(btnDelete)

        return frame
    }

    /** 上传单张待发送图片,上传成功后 aid 存入 uploadedAidMap */
    private fun uploadPendingImage(uri: Uri) {
        if (imageUploadInProgress) {
            synchronized(imageUploadPendingQueue) {
                if (!imageUploadPendingQueue.contains(uri)) imageUploadPendingQueue.add(uri)
            }
            return
        }
        imageUploadInProgress = true
        java.lang.Thread({
            try {
                val file = transcodeReplyImageToJpeg(uri)
                if (file == null || !file.exists() || file.length() == 0L) {
                    runOnUiThread {
                        imageUploadInProgress = false
                        drainUploadQueue()
                        Toast.makeText(this, "图片读取失败,请换一张试试", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                if (!httpClient.isLoggedIn()) {
                    httpClient.syncFromCookieManager()
                    if (!httpClient.isLoggedIn()) {
                        runOnUiThread { imageUploadInProgress = false }
                        return@Thread
                    }
                }
                val detailHtml = httpClient.getDesktop(ForumParser.getThreadDetailUrl(tid))
                var uid = extractUploadValue(detailHtml, "discuz_uid")
                var hash = extractUploadValue(detailHtml, "hash")
                if (!isValidUploadUid(uid)) uid = null
                if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(hash)) {
                    var fid = extractForumFid(detailHtml)
                    if (TextUtils.isEmpty(fid)) fid = "39"
                    val postHtml = httpClient.getDesktop(
                        HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + fid
                    )
                    if (TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid")
                    if (!isValidUploadUid(uid)) uid = null
                    if (TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash")
                }
                if (!isValidUploadUid(uid) || TextUtils.isEmpty(hash)) {
                    runOnUiThread {
                        imageUploadInProgress = false
                        drainUploadQueue()
                        Toast.makeText(this, "图片上传授权失败,请重新登录后重试", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val extra = HashMap<String, String>()
                extra["uid"] = uid!!
                extra["hash"] = hash!!
                val url = HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload" +
                        "&type=image&inajax=yes&infloat=yes&simple=2"
                val result = httpClient.uploadFileWithUserAgent(
                    url, file, "Filedata", extra, HttpClient.DESKTOP_USER_AGENT, "image/jpeg"
                )
                val aid = parseUploadAid(result)
                if (!TextUtils.isEmpty(aid)) {
                    synchronized(pendingUploadAids) {
                        if (!pendingUploadAids.contains(aid)) pendingUploadAids.add(aid!!)
                    }
                    uploadedAidMap[uri] = aid!!
                    val tag = "\n[attachimg]" + aid + "[/attachimg]"
                    runOnUiThread {
                        binding.etReply.append(tag)
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                runOnUiThread {
                    imageUploadInProgress = false
                    drainUploadQueue()
                }
            }
        }).start()
    }

    /** build71: 上一张传完后,从排队队列里取下一张继续上传 */
    private fun drainUploadQueue() {
        val next: Uri
        synchronized(imageUploadPendingQueue) {
            if (imageUploadInProgress || imageUploadPendingQueue.isEmpty()) return
            next = imageUploadPendingQueue.removeAt(0)
        }
        uploadPendingImage(next)
    }

    /** 构建回复文本中的 [attachimg] 标签前缀 */
    private fun buildAttachTags(): String {
        val sb = StringBuilder()
        synchronized(pendingUploadAids) {
            for (aid in pendingUploadAids) {
                sb.append("[attachimg]").append(aid).append("[/attachimg]\n")
            }
        }
        return sb.toString()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_THREAD && resultCode == RESULT_OK) {
            // build73: 编辑保存成功 -> 重新拉一次帖子
            refreshPostDetail()
            return
        }
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            val imageUris = ArrayList<Uri>()
            if (data.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    if (uri != null) imageUris.add(uri)
                }
            } else if (data.data != null) {
                imageUris.add(data.data!!)
            }
            if (!imageUris.isEmpty()) {
                addPendingImages(imageUris)
            }
        }
    }

    private fun getDisplayNameFromUri(uri: Uri?): String? {
        if (uri == null) return null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column)
            }
        } catch (ignored: Exception) {
        } finally {
            cursor?.close()
        }
        val path = uri.lastPathSegment
        return if (TextUtils.isEmpty(path)) null else path
    }

    private fun buildUploadFileName(sourceName: String?, mimeType: String?): String {
        val name = if (TextUtils.isEmpty(sourceName)) ""
        else Regex("[\\\\/:*?\"<>|]").replace(sourceName!!, "_")
        if (name.isEmpty() || !name.matches(Regex("(?i).*\\.[a-z0-9]{2,5}$"))) {
            val extension = extensionFromMimeType(mimeType)
            return "reply_" + System.currentTimeMillis() + extension
        }
        return name
    }

    private fun extensionFromMimeType(mimeType: String?): String {
        if ("image/png".equals(mimeType, ignoreCase = true)) return ".png"
        if ("image/gif".equals(mimeType, ignoreCase = true)) return ".gif"
        if ("image/webp".equals(mimeType, ignoreCase = true)) return ".webp"
        if ("image/bmp".equals(mimeType, ignoreCase = true)) return ".bmp"
        if ("image/heic".equals(mimeType, ignoreCase = true)
            || "image/heif".equals(mimeType, ignoreCase = true)
        ) return ".heic"
        return ".jpg"
    }

    private fun uploadAndAttachImage(imageUri: Uri) {
        if (imageUploadInProgress) {
            Toast.makeText(this, "已有图片正在上传,请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        pendingImageUri = imageUri
        imageUploadInProgress = true
        Toast.makeText(this, "正在上传图片...", Toast.LENGTH_SHORT).show()
        java.lang.Thread {
            var file: File? = null
            try {
                if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager()
                if (!httpClient.isLoggedIn()) {
                    runOnUiThread { promptLogin() }
                    return@Thread
                }
                file = transcodeReplyImageToJpeg(imageUri)
                if (file == null || !file.exists() || file.length() == 0L) {
                    showUploadError("无法读取或转换图片文件")
                    return@Thread
                }
                val detailHtml = httpClient.getDesktop(ForumParser.getThreadDetailUrl(tid))
                var uid = extractUploadValue(detailHtml, "discuz_uid")
                var hash = extractUploadValue(detailHtml, "hash")
                if (!isValidUploadUid(uid)) uid = null
                if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(hash)) {
                    var fid = extractForumFid(detailHtml)
                    if (TextUtils.isEmpty(fid)) fid = "39"
                    val postHtml = httpClient.getDesktop(
                        HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + fid
                    )
                    if (TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid")
                    if (!isValidUploadUid(uid)) uid = null
                    if (TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash")
                }
                if (!isValidUploadUid(uid) || TextUtils.isEmpty(hash)) {
                    showUploadError("获取图片上传授权失败,请重新登录后重试")
                    return@Thread
                }
                val extra = HashMap<String, String>()
                extra["uid"] = uid!!
                extra["hash"] = hash!!
                val url = HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload" +
                        "&type=image&inajax=yes&infloat=yes&simple=2"
                val result = httpClient.uploadFileWithUserAgent(
                    url, file!!, "Filedata", extra, HttpClient.DESKTOP_USER_AGENT, "image/jpeg"
                )
                val aid = parseUploadAid(result)
                if (TextUtils.isEmpty(aid)) {
                    showUploadError(extractUploadError(result))
                    return@Thread
                }
                val finalAid = aid!!
                synchronized(pendingUploadAids) {
                    if (!pendingUploadAids.contains(finalAid)) pendingUploadAids.add(finalAid)
                }
                runOnUiThread {
                    binding.etReply.append("\n[attachimg]" + finalAid + "[/attachimg]")
                    Toast.makeText(this, "图片已上传,发送评论后才会正式关联", Toast.LENGTH_SHORT).show()
                    imageUploadInProgress = false
                }
            } catch (e: Exception) {
                showUploadError(
                    "图片上传失败:" + if (TextUtils.isEmpty(e.message)) "网络异常,请稍后重试" else e.message
                )
            } finally {
                file?.delete()
                runOnUiThread { imageUploadInProgress = false }
            }
        }.start()
    }

    private fun extractUploadError(response: String?): String {
        if (TextUtils.isEmpty(response)) {
            return "图片上传失败，服务器未返回结果"
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录", "登录")) {
            return "登录状态已失效，请重新登录"
        }
        val text = Regex("(?s)<[^>]+>").replace(response!!, " ").trim()
        if (text.startsWith("DISCUZUPLOAD|")) {
            val parts = text.split("\\|".toRegex(), -1).toTypedArray()
            if (parts.size > 3) {
                val error = parts[parts.size - 1].trim()
                if (!TextUtils.isEmpty(error) && !error.matches(Regex("\\d+"))) {
                    return "图片上传失败：$error"
                }
                return "图片上传失败，请检查图片格式、大小和登录状态"
            }
            return "图片上传失败，请检查图片格式、大小和登录状态"
        }
        return "图片上传失败，请检查图片格式、大小和登录状态"
    }

    private fun showUploadError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun extractForumFid(html: String?): String? {
        if (TextUtils.isEmpty(html)) {
            return null
        }
        val m = Pattern.compile("(?:forum-|[?&]fid=)(\\d+)").matcher(html!!)
        if (m.find()) {
            return m.group(1)
        }
        return null
    }

    private fun extractUploadValue(html: String?, key: String): String? {
        if (TextUtils.isEmpty(html)) {
            return null
        }
        val quotedKey = Pattern.quote(key)
        var m = Pattern.compile(
            "(?:var\\s+|\\b)" + quotedKey + "\\s*(?:=|:)\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE
        ).matcher(html!!)
        if (m.find()) return m.group(1)
        m = Pattern.compile("\\\"" + quotedKey + "\\\"\\s*:\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
            .matcher(html)
        if (m.find()) return m.group(1)
        m = Pattern.compile(
            "<input[^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"][^>]+value\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)
        m = Pattern.compile(
            "<input[^>]+value\\s*=\\s*['\"]([^'\"]+)['\"][^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"]",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)
        m = Pattern.compile("[?&]" + quotedKey + "=([^&\"'<>\\s]+)", Pattern.CASE_INSENSITIVE).matcher(html)
        if (m.find()) return m.group(1)
        return null
    }

    private fun parseUploadAid(response: String?): String? {
        if (TextUtils.isEmpty(response)) {
            return null
        }
        val trimmed = response!!.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val text = Regex("(?s)<[^>]+>").replace(trimmed, "").trim()
        if (text.uppercase(Locale.ROOT).startsWith("DISCUZUPLOAD|")) {
            val parts = text.split("\\|".toRegex(), -1).toTypedArray()
            if (parts.size >= 4 && "0" == parts[2] && parts[3].matches(Regex("\\d+"))) {
                return parts[3]
            }
            if (parts.size >= 3 && "0" == parts[1] && parts[2].matches(Regex("\\d+"))) {
                return parts[2]
            }
            val mPipe = Pattern.compile("(?i)DISCUZUPLOAD\\|[^|]*\\|0\\|([^|]+)").matcher(text)
            if (mPipe.find() && mPipe.group(1)!!.matches(Regex("\\d+"))) {
                return mPipe.group(1)
            }
        }
        var m = Pattern.compile("(?:aid|attach)(?:Id)?[\\s:='\"]+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text)
        if (m.find()) return m.group(1)
        m = Pattern.compile("\\\"(?:aid|attach)(?:Id)?\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text)
        if (m.find()) return m.group(1)
        return null
    }

    @Throws(Exception::class)
    private fun transcodeReplyImageToJpeg(uri: Uri?): File? {
        if (uri == null) {
            return null
        }
        val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
        val decoded = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val largest = maxOf(width, height)
            if (largest > 1920) {
                val scale = 1920.0f / largest
                decoder.setTargetSize(
                    maxOf(1, Math.round(width * scale)),
                    maxOf(1, Math.round(height * scale))
                )
            }
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (decoded == null || decoded.width <= 0 || decoded.height <= 0) {
            return null
        }
        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(-1)
        canvas.drawBitmap(decoded, 0.0f, 0.0f, null)
        if (flattened != decoded) {
            decoded.recycle()
        }
        var jpeg: ByteArray? = null
        var quality = 88
        while (quality >= 58) {
            val output = ByteArrayOutputStream()
            flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)
            jpeg = output.toByteArray()
            if (jpeg.size <= 2097152 || quality == 58) {
                break
            }
            quality -= 6
        }
        flattened.recycle()
        if (jpeg == null || jpeg.isEmpty()) {
            return null
        }
        val dir = File(cacheDir, "reply_uploads")
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val target = File(dir, "reply_" + System.currentTimeMillis() + ".jpg")
        FileOutputStream(target).use {
            it.write(jpeg)
            it.flush()
        }
        return target
    }

    @Throws(Exception::class)
    private fun copyUriToTempFile(uri: Uri, fileName: String?): File? {
        val dir = File(cacheDir, "reply_uploads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, fileName)
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val len = input.read(buffer)
                    if (len == -1) {
                        break
                    }
                    out.write(buffer, 0, len)
                }
            }
        }
        return file
    }

    private fun shareThread() {
        val detail = postDetail
        if (detail == null) {
            return
        }
        val shareText = detail.title + "\n" + HttpClient.BASE_URL + "thread-" + tid + "-1-1.html"
        val shareIntent = Intent("android.intent.action.SEND")
        // 原为 androidx.webkit.internal.AssetHelper.DEFAULT_MIME_TYPE，
        // 该值就是 "text/plain"；为一个字符串常量引入整个 androidx.webkit 依赖不划算，
        // 且 AssetHelper 在 internal 包下，非公开 API，库升级可能直接删掉。
        shareIntent.type = "text/plain"
        shareIntent.putExtra("android.intent.extra.TEXT", shareText)
        startActivity(Intent.createChooser(shareIntent, "分享帖子"))
    }

    // ==================== 打赏 / 踢帖 ====================

    private fun showRewardDialog() {
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        // build74b: 目标可以是主楼(空)或某条评论(评论菜单已排除本人,无需再判)
        val isReplyTarget = !TextUtils.isEmpty(rewardTargetPid)
        if (!isReplyTarget) {
            val currentUid = UserSessionManager.getInstance().getUid(applicationContext)
            val authorUid = postDetail?.authorUid
            if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid == authorUid) {
                Toast.makeText(this, "不能给自己打赏", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_reward, null)
        dialog.setContentView(view)
        val cardReward = view.findViewById<MaterialCardView>(R.id.card_reward)
        if (cardReward != null) FrostedGlassHelper.applyToCardViews(cardReward, this)
        dialog.setOnShowListener {
            val parent = view.parent as? View
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(parent)
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.5).toInt()
            }
        }
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_reward_author_avatar)
        val tvAuthorName = view.findViewById<TextView>(R.id.tv_reward_author_name)
        val tvHint = view.findViewById<TextView>(R.id.tv_reward_hint)
        val spinnerAmount = view.findViewById<Spinner>(R.id.spinner_reward_amount)
        val switchNotify = view.findViewById<SwitchCompat>(R.id.switch_notify_author)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_reward)
        val displayName = if (!TextUtils.isEmpty(rewardTargetName)) rewardTargetName
        else (postDetail?.author ?: "")
        val displayAvatar = if (!TextUtils.isEmpty(rewardTargetAvatar)) rewardTargetAvatar
        else postDetail?.avatarUrl
        if (!TextUtils.isEmpty(displayName)) {
            tvAuthorName.text = displayName
            tvHint.text = "给 $displayName 打赏鼓励吧"
            if (!TextUtils.isEmpty(displayAvatar)) {
                Glide.with(this).load(displayAvatar).transform(CircleCrop())
                    .placeholder(R.drawable.ic_account).error(R.drawable.ic_account)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account)
            }
        }
        val amounts = arrayOf("1", "5", "10", "50", "100")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, amounts)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAmount.adapter = adapter
        val etRewardMessage = view.findViewById<EditText>(R.id.et_reward_message)
        btnSubmit.setOnClickListener {
            val selectedAmount = amounts[spinnerAmount.selectedItemPosition].toInt()
            val notifyAuthor = switchNotify.isChecked
            val message = etRewardMessage.text.toString().trim()
            dialog.dismiss()
            performReward(selectedAmount, notifyAuthor, "", message)
        }
        dialog.show()
    }

    private fun performReward(amount: Int, notifyAuthor: Boolean, goodReview: String, message: String) {
        java.lang.Thread {
            try {
                val detail = postDetail ?: throw IllegalStateException("帖子数据为空")
                var pid = if (TextUtils.isEmpty(rewardTargetPid)) detail.postPid else rewardTargetPid
                // 用掉即清(下次默认主楼)
                rewardTargetPid = ""
                rewardTargetName = ""
                rewardTargetAvatar = ""
                if (TextUtils.isEmpty(pid)) {
                    val page = httpClient.get(ForumParser.getThreadDetailUrl(tid))
                    val latest = ForumParser.parseThreadDetail(page)
                    pid = latest.postPid
                    if (!TextUtils.isEmpty(latest.formhash)) {
                        detail.formhash = latest.formhash
                    }
                }
                if (TextUtils.isEmpty(pid)) {
                    throw IllegalStateException("无法获取帖子正文编号")
                }
                var rateForm = ""
                try {
                    val rateUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + tid +
                            "&pid=" + pid + "&showratetip=1&inajax=1&mobile=2"
                    rateForm = httpClient.get(rateUrl)
                } catch (ignored: Exception) {
                }
                var fh = ForumParser.parseFormhash(rateForm)
                if (TextUtils.isEmpty(fh)) {
                    fh = detail.formhash
                }
                if (TextUtils.isEmpty(fh)) {
                    val page2 = httpClient.get(ForumParser.getThreadDetailUrl(tid))
                    fh = ForumParser.parseFormhash(page2)
                }
                if (TextUtils.isEmpty(fh)) {
                    throw IllegalStateException("无法获取操作验证")
                }
                val params = HashMap<String, String>()
                params["formhash"] = fh!!
                params["tid"] = tid!!
                params["pid"] = pid!!
                params["ratesubmit"] = "yes"
                params["referer"] = ForumParser.getThreadDetailUrl(tid)
                params["score1"] = "1"
                params["score2"] = amount.toString()
                var reason = message
                if (TextUtils.isEmpty(reason)) {
                    reason = goodReview
                } else if (!TextUtils.isEmpty(goodReview)) {
                    reason = "$goodReview：$reason"
                }
                if (!TextUtils.isEmpty(reason)) {
                    params["reason"] = reason
                }
                val rewardUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + tid +
                        "&pid=" + pid + "&ratesubmit=yes&inajax=1&mobile=2"
                val result = httpClient.post(rewardUrl, params)
                val success = isForumActionResponseSuccessful(result)
                val rewardError = if (success) "" else extractRewardError(result)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(
                            this, getString(R.string.reward_success, amount), Toast.LENGTH_SHORT
                        ).show()
                        refreshPostDetail()
                    } else {
                        val msg = if (TextUtils.isEmpty(rewardError)) getString(R.string.reward_failed)
                        else rewardError
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.reward_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun isForumActionResponseSuccessful(response: String?): Boolean {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response) || isRewardLimitOrFailure(response)) {
            return false
        }
        val lower = response!!.lowercase(Locale.ROOT)
        return lower.contains("succeedhandle_rate") || lower.contains("rate_success")
                || containsAny(response, "评分成功", "打赏成功", "您已成功评分", "评价成功")
    }

    private fun isRewardLimitOrFailure(response: String?): Boolean {
        return containsAny(
            response,
            "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次", "每 24 小时只能评分一次",
            "24小时内已经评分", "24 小时内已经评分", "您已评价过本主题", "您已经评价过本主题",
            "您已评分过本主题", "您已经评分过本主题", "已经评分过", "已经评价过", "重复评分",
            "评分过本主题", "thread_rate_duplicate", "rate_duplicate", "评分失败", "评分范围错误",
            "thread_rate_range_invalid", "credit_limit_invalid", "积分不足", "余额不足",
            "提交频率过快", "操作频繁", "暂不支持高级操作", "formhash错误", "非法操作",
            "没有权限", "请先登录", "未定义操作", "undefined action", "操作失败"
        )
    }

    private fun extractRewardError(response: String?): String {
        if (TextUtils.isEmpty(response)) {
            return "打赏失败，服务器未返回结果"
        }
        if (!ForumParser.isLoginPage(response) && !containsAny(response, "请先登录")) {
            if (!containsAny(
                    response,
                    "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次",
                    "每 24 小时只能评分一次", "24小时内已经评分", "24 小时内已经评分", "重复评分",
                    "已评价过本主题", "已评分过本主题", "已经评分过", "已经评价过", "评分过本主题"
                )
            ) {
                if (!containsAny(response, "积分不足", "余额不足", "credit_limit_invalid")) {
                    if (!containsAny(response, "formhash错误", "非法操作")) {
                        if (!containsAny(response, "没有权限", "暂不支持高级操作")) {
                            if (containsAny(response, "提交频率过快", "操作频繁")) {
                                return "操作过于频繁，请稍后再试"
                            }
                            return "打赏失败，请重试"
                        }
                        return "当前账号没有评分权限"
                    }
                    return "验证已失效，请刷新页面后重试"
                }
                return "积分余额不足，无法完成打赏"
            }
            return "24小时内只能对同一帖子评分一次，请稍后再试"
        }
        return "登录状态已失效，请重新登录"
    }

    private fun showKickDialog() {
        performKick()
    }

    private fun submitKickRequest(reason: String) {
        java.lang.Thread {
            try {
                val page = httpClient.get(ForumParser.getThreadDetailUrl(tid))
                var fh = ForumParser.parseFormhash(page)
                if (TextUtils.isEmpty(fh)) {
                    fh = postDetail?.formhash
                }
                if (TextUtils.isEmpty(fh)) {
                    throw IllegalStateException("无法获取操作验证")
                }
                val kickUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=kick&tid=" + tid +
                        "&formhash=" + fh + "&inajax=1&mobile=2"
                val params = HashMap<String, String>()
                params["formhash"] = fh!!
                params["tid"] = tid!!
                params["kick_submit"] = "yes"
                params["inajax"] = "1"
                if (!TextUtils.isEmpty(reason)) {
                    params["kick_reason"] = reason
                }
                val result = httpClient.post(kickUrl, params)
                val success = isForumActionResponseSuccessful(result)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, R.string.kick_success, Toast.LENGTH_SHORT).show()
                        refreshPostDetail()
                    } else {
                        Toast.makeText(this, R.string.kick_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.kick_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun performKick() {
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        val currentUid = UserSessionManager.getInstance().getUid(applicationContext)
        val authorUid = postDetail?.authorUid
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid == authorUid) {
            Toast.makeText(this, "不能踢自己的帖子", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_kick, null)
        dialog.setContentView(view)
        dialog.setCancelable(true)
        val cardKick = view.findViewById<MaterialCardView>(R.id.card_kick)
        if (cardKick != null) FrostedGlassHelper.applyToCardViews(cardKick, this)
        dialog.setOnShowListener {
            val parent = view.parent as? View
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(parent)
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.5).toInt()
            }
        }
        val etKickReason = view.findViewById<EditText>(R.id.et_kick_reason)
        val btnCloseKick = view.findViewById<Button>(R.id.btn_close_kick)
        val btnSubmitKick = view.findViewById<Button>(R.id.btn_submit_kick)
        btnCloseKick.setOnClickListener { dialog.dismiss() }
        btnSubmitKick.setOnClickListener {
            val reason = etKickReason.text.toString().trim()
            if (TextUtils.isEmpty(reason)) {
                etKickReason.error = "请输入踢帖理由"
            } else {
                dialog.dismiss()
                submitKickRequest(reason)
            }
        }
        dialog.show()
    }

    // ==================== build73: 编辑 / 举报 / 删除 ====================

    /** 当前登录 uid(带缓存) */
    private fun loginUid(): String {
        if (currentLoginUid == null) {
            currentLoginUid = UserSessionManager.getInstance().getUid(applicationContext) ?: ""
        }
        return currentLoginUid ?: ""
    }

    /** 是否本人在看自己的帖子 */
    private fun isOwnThread(d: PostDetail?): Boolean {
        if (d == null) return false
        val me = loginUid()
        if (TextUtils.isEmpty(me)) return false
        if (!TextUtils.isEmpty(d.authorUid) && me == d.authorUid) return true
        return false
    }

    /** 打开编辑页(复用发帖页,编辑模式) */
    private fun openEditThread() {
        val detail = postDetail ?: return
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        if (TextUtils.isEmpty(detail.postPid)) {
            Toast.makeText(this, "缺少帖子编号,无法编辑", Toast.LENGTH_SHORT).show()
            return
        }
        val it = Intent(this, com.solosu.mtforum.ui.post.PostActivity::class.java)
        it.putExtra("edit_tid", tid)
        it.putExtra("edit_pid", detail.postPid)
        it.putExtra("edit_fid", detail.forumFid)
        it.putExtra("edit_forum_name", detail.forumName)
        it.putExtra("edit_title", detail.title)
        it.putExtra("edit_message", stripContentHtml(detail.contentHtml))
        startActivityForResult(it, REQUEST_EDIT_THREAD)
    }

    /** 正文 HTML -> 可编辑的 BBCode/纯文本(交给 BBCodeUtil 反向处理,失败则剥标签) */
    private fun stripContentHtml(html: String?): String {
        if (TextUtils.isEmpty(html)) return ""
        // build73c: BBCodeUtil 没有 html->bbcode 的反解能力,改为剥标签但保留附件标记
        var attachmentMarks = ""
        try {
            val am = Pattern.compile(
                "(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]", Pattern.CASE_INSENSITIVE
            ).matcher(html!!)
            val amsb = StringBuilder()
            while (am.find()) amsb.append("\n").append(am.group())
            attachmentMarks = amsb.toString()
        } catch (ignored: Exception) {
        }
        var t = Regex("(?is)<br\\s*/?>").replace(html!!, "\n")
        t = Regex("(?is)<script[^>]*>.*?</script>").replace(t, "")
        t = Regex("(?is)<[^>]+>").replace(t, "")
        t = Html.fromHtml(t).toString().trim()
        return (t + attachmentMarks).trim()
    }

    /** build77: 举报入口 -> 弹理由输入窗(常用理由下拉 + 补充说明) */
    private fun reportPost(pid: String?) {
        if (!httpClient.isLoggedIn()) {
            promptLogin()
            return
        }
        val isThread = TextUtils.isEmpty(pid)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val content = layoutInflater.inflate(R.layout.dialog_report, null)
        dialog.setContentView(content)
        val win = dialog.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(0))
            val lp = win.attributes
            lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            win.attributes = lp
        }
        val tvTarget = content.findViewById<TextView>(R.id.tv_report_target)
        tvTarget.text = if (isThread) "举报对象：本帖" else "举报对象：该评论"
        val cgReason = content.findViewById<ChipGroup>(R.id.cg_report_reason)
        content.findViewById<View>(R.id.btn_close_report).setOnClickListener { dialog.dismiss() }
        val etMessage = content.findViewById<EditText>(R.id.et_report_message)
        content.findViewById<View>(R.id.btn_cancel_report).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btn_submit_report).setOnClickListener {
            var reason = "其他"
            val checkedId = cgReason.checkedChipId
            val checkedView = if (checkedId != View.NO_ID) cgReason.findViewById<View>(checkedId) else null
            if (checkedView is Chip) {
                reason = checkedView.text.toString()
            }
            val msg = if (etMessage.text != null) etMessage.text.toString().trim() else ""
            dialog.dismiss()
            submitReport(pid, reason, msg)
        }
        dialog.show()
    }

    /** build77: 举报提交(理由/说明由弹窗传入) */
    private fun submitReport(pid: String?, reason: String?, userMessage: String?) {
        val rtype = if (TextUtils.isEmpty(pid)) "thread" else "post"
        val rid = if (TextUtils.isEmpty(pid)) tid else pid
        val finalReason = if (TextUtils.isEmpty(reason)) "其他" else reason
        val finalMessage =
            if (TextUtils.isEmpty(userMessage)) "该内容涉嫌违规，请核实处理。" else userMessage
        java.lang.Thread {
            try {
                if (TextUtils.isEmpty(tid)) throw IllegalStateException("缺少帖子编号")
                val fid = if (postDetail != null && !TextUtils.isEmpty(postDetail!!.forumFid))
                    postDetail!!.forumFid else "39"
                val formUrl = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype +
                        "&rid=" + rid + "&tid=" + tid + "&fid=" + fid + "&inajax=1&mobile=2"
                val form = httpClient.get(formUrl)
                var fh = ForumParser.parseFormhash(form)
                if (TextUtils.isEmpty(fh) && postDetail != null) fh = postDetail!!.formhash
                if (TextUtils.isEmpty(fh)) throw IllegalStateException("获取操作验证失败")

                val params = HashMap<String, String>()
                params["formhash"] = fh!!
                params["tid"] = tid!!
                if ("post" == rtype) {
                    params["pid"] = pid!!
                }
                params["fid"] = fid!!
                params["rtype"] = rtype
                params["rid"] = rid!!
                params["reportsubmit"] = "yes"
                params["reason"] = finalReason!!
                params["message"] = finalMessage!!
                val url = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype +
                        "&rid=" + rid + "&tid=" + tid + "&fid=" + fid +
                        "&reportsubmit=yes&inajax=1&mobile=2"
                val resp = httpClient.post(url, params)

                val ok = !TextUtils.isEmpty(resp) && !ForumParser.isLoginPage(resp)
                        && !resp!!.contains("举报理由") && !resp.contains("action=login")
                val msg = if (ok) "举报已提交，感谢反馈" else "举报未成功，请稍后重试"
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@ThreadDetailActivity,
                        "举报失败：" + (if (TextUtils.isEmpty(e.message)) "网络异常" else e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /** 删除自己的回复 */
    private fun deleteReply(item: ReplyItem?) {
        if (item == null || TextUtils.isEmpty(item.pid)) return
        java.lang.Thread {
            try {
                val pid = item.pid!!
                val formUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&tid=" + tid +
                        "&pid=" + pid + "&mobile=2"
                val form = httpClient.get(formUrl)
                var fh = ForumParser.parseFormhash(form)
                if (TextUtils.isEmpty(fh) && postDetail != null) fh = postDetail!!.formhash
                // build73c: 编辑页 HTML 里带该楼专用删除校验哈希,优先用它
                val delHash = extractDeleteHash(form, pid)
                if (!TextUtils.isEmpty(delHash)) fh = delHash
                if (TextUtils.isEmpty(fh)) throw IllegalStateException("获取操作验证失败")

                val params = HashMap<String, String>()
                params["formhash"] = fh!!
                params["delete"] = "1"
                params["pid"] = pid
                params["tid"] = tid!!
                val url = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&tid=" + tid +
                        "&pid=" + pid + "&delete=1&deletesubmit=yes&mobile=2"
                httpClient.post(url, params)
                runOnUiThread {
                    Toast.makeText(this, "已删除该回复", Toast.LENGTH_SHORT).show()
                    refreshPostDetail()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@ThreadDetailActivity,
                        "删除失败：" + (if (TextUtils.isEmpty(e.message)) "网络异常" else e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /** build73c: 从编辑页 HTML 抽取该楼删除用的校验哈希(找不到返回 null,退回通用 formhash) */
    private fun extractDeleteHash(html: String?, pid: String?): String? {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(pid)) return null
        try {
            var m = Pattern.compile("formhash=([0-9a-zA-Z]+)[^\"'<>]{0,200}?pid=" + pid).matcher(html!!)
            if (m.find()) return m.group(1)
            m = Pattern.compile("pid=" + pid + "[^\"'<>]{0,200}?formhash=([0-9a-zA-Z]+)").matcher(html)
            if (m.find()) return m.group(1)
        } catch (ignored: Exception) {
        }
        return null
    }

    /** build75: 评论操作菜单(自定义卡片弹窗:图标+文字行) */
    private fun showReplyActionMenu(item: ReplyItem?) {
        if (item == null) return
        val author = if (TextUtils.isEmpty(item.author)) "匿名" else item.author
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val content = layoutInflater.inflate(R.layout.dialog_action_menu, null)
        dialog.setContentView(content)
        val win = dialog.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(0))
            val lp = win.attributes
            lp.width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            win.attributes = lp
        }
        val tvMenuTitle = content.findViewById<TextView>(R.id.tv_menu_title)
        tvMenuTitle.text = "$author · 评论操作"
        val container = content.findViewById<LinearLayout>(R.id.ll_menu_items)

        addActionRow(container, R.drawable.ic_reply, "回复", false, {
            currentReplyPid = item.pid ?: ""
            currentReplyTarget = "回复 $author："
            showReplyBottomSheet("")
        }, dialog)
        if (!isOwnReply(item)) {
            addActionRow(container, R.drawable.ic_reward, "打赏", false, {
                rewardTargetPid = item.pid ?: ""
                rewardTargetName = author!!
                rewardTargetAvatar = item.avatarUrl ?: ""
                showRewardDialog()
            }, dialog)
            addActionRow(container, R.drawable.ic_flag, "举报", false, {
                reportPost(item.pid)
            }, dialog)
        } else {
            addActionRow(container, R.drawable.ic_delete, "删除", true, {
                confirmDeleteReply(item)
            }, dialog)
        }
        addActionRow(container, 0, "取消", false, null, dialog)
        dialog.show()
    }

    /** build75: 菜单行(图标+文字),action==null 视为取消 */
    private fun addActionRow(
        container: LinearLayout, iconRes: Int, label: String,
        danger: Boolean, action: (() -> Unit)?, dialog: Dialog
    ) {
        val row = layoutInflater.inflate(R.layout.item_action_menu, container, false)
        val iv = row.findViewById<ImageView>(R.id.iv_action_icon)
        val tv = row.findViewById<TextView>(R.id.tv_action_label)
        if (iconRes != 0) {
            iv.setImageResource(iconRes)
            if (danger) iv.setColorFilter(0xFFE53935.toInt())
        } else {
            iv.visibility = View.GONE
        }
        tv.text = label
        if (danger) tv.setTextColor(0xFFE53935.toInt())
        row.setOnClickListener {
            dialog.dismiss()
            action?.invoke()
        }
        container.addView(row)
    }

    private fun isOwnReply(item: ReplyItem?): Boolean {
        if (item == null) return false
        val me = loginUid()
        if (TextUtils.isEmpty(me)) return false
        return me == item.authorUid
    }

    private fun confirmDeleteReply(item: ReplyItem?) {
        AlertDialog.Builder(this)
            .setTitle("删除回复")
            .setMessage("确定要删除这条回复吗？删除后无法恢复。")
            .setPositiveButton("删除") { _, _ -> deleteReply(item) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 表情面板 ====================

    private fun showEmojiPanel() {
        // 自绘制快速回复图标,替代 emoji
        val iconIds = intArrayOf(
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        )
        val labels = arrayOf("微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶")
        val dp4 = dpToPx(4)
        val dp8 = dpToPx(8)
        val rvEmoji = RecyclerView(this)
        rvEmoji.layoutManager = GridLayoutManager(this, 5)
        rvEmoji.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val ll = LinearLayout(parent.context)
                ll.orientation = LinearLayout.VERTICAL
                ll.gravity = Gravity.CENTER
                ll.setPadding(dp8, dp8, dp8, dp8)
                val size = dpToPx(48)
                ll.layoutParams = RecyclerView.LayoutParams(size, size)

                val iv = ImageView(parent.context)
                iv.layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.id = View.generateViewId()
                ll.addView(iv)

                val tv = TextView(parent.context)
                tv.textSize = 9f
                tv.setTextColor(0xFF9CA3AF.toInt())
                tv.gravity = Gravity.CENTER
                tv.maxLines = 1
                tv.id = View.generateViewId()
                ll.addView(tv)

                return object : RecyclerView.ViewHolder(ll) {}
            }

            override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
                val ll = holder.itemView as LinearLayout
                val iv = ll.getChildAt(0) as ImageView
                val tv = ll.getChildAt(1) as TextView
                iv.setImageResource(iconIds[position])
                tv.text = labels[position]
                ll.setOnClickListener {
                    val pos = binding.etReply.selectionStart
                    val text = binding.etReply.text.toString()
                    val tag = "[" + labels[position] + "]"
                    binding.etReply.setText(text.substring(0, pos) + tag + text.substring(pos))
                    binding.etReply.setSelection(pos + tag.length)
                }
            }

            override fun getItemCount(): Int {
                return iconIds.size
            }
        }
        val popup = PopupWindow(rvEmoji, ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(160), true)
        popup.setBackgroundDrawable(ColorDrawable(getColor(R.color.background_primary)))
        popup.isOutsideTouchable = true
        popup.elevation = dpToPx(8).toFloat()
        popup.showAtLocation(binding.root, Gravity.BOTTOM, 0, 0)
    }

    // ==================== 链接与内容处理 ====================

    /** 内嵌图片 getter：UrlDrawable 占位 + Glide 异步回填（修复旧空壳实现图片不显示问题） */
    private fun createInlineImageGetter(textView: TextView): Html.ImageGetter {
        return Html.ImageGetter { source ->
            var imgUrl = normalizeImageUrl(source)
            if (imgUrl == null) {
                imgUrl = source
            }
            val tv = textView
            val maxW = maxOf(
                dpToPx(200),
                (if (tv.width > 0) tv.width * 0.92f
                else resources.displayMetrics.widthPixels * 0.92f).toInt()
            )
            val placeholder = UrlDrawable(tv, dpToPx(120))
            Glide.with(this)
                .load(imgUrl)
                .into(object : com.bumptech.glide.request.target.CustomTarget<Drawable?>() {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in Drawable?>?
                    ) {
                        var w = resource.intrinsicWidth
                        var h = resource.intrinsicHeight
                        if (w <= 0) {
                            w = maxW
                        }
                        if (h <= 0) {
                            h = maxW
                        }
                        if (w > maxW) {
                            h = (h.toLong() * maxW / maxOf(1, w)).toInt()
                            w = maxW
                        }
                        resource.setBounds(0, 0, w, h)
                        placeholder.setReal(resource, tv)
                    }

                    override fun onLoadCleared(ph: Drawable?) {
                    }
                })
            placeholder
        }
    }

    /** 安全解析 HTML,捕获 SpannableStringBuilder 的 PARAGRAPH 边界崩溃 */
    private fun safeFromHtml(html: String?, imageGetter: Html.ImageGetter, tagHandler: Html.TagHandler?): Spanned {
        if (TextUtils.isEmpty(html)) {
            return SpannedStringValueOf("")
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler)
        } catch (e: Exception) {
            android.util.Log.w("ThreadDetail", "Html.fromHtml failed, retrying with COMPACT mode", e)
            try {
                return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler)
            } catch (e2: Exception) {
                android.util.Log.w("ThreadDetail", "Html.fromHtml COMPACT also failed, stripping paragraph tags", e2)
                // 移除可能导致段落边界问题的标签
                var stripped = Regex("<div[^>]*>").replace(html!!, "")
                stripped = Regex("</div>").replace(stripped, "<br>")
                stripped = Regex("<p[^>]*>").replace(stripped, "")
                stripped = Regex("</p>").replace(stripped, "<br>")
                stripped = Regex("<li[^>]*>").replace(stripped, "• ")
                stripped = Regex("</li>").replace(stripped, "<br>")
                stripped = Regex("<ol[^>]*>").replace(stripped, "")
                stripped = Regex("</ol>").replace(stripped, "")
                stripped = Regex("<ul[^>]*>").replace(stripped, "")
                stripped = Regex("</ul>").replace(stripped, "")
                try {
                    return Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler)
                } catch (e3: Exception) {
                    android.util.Log.e("ThreadDetail", "All Html.fromHtml attempts failed", e3)
                    return SpannedStringValueOf(Html.fromHtml(TextUtils.htmlEncode(html)).toString())
                }
            }
        }
    }

    private fun SpannedStringValueOf(s: String): Spanned {
        return android.text.SpannedString(s)
    }

    private fun setupClickableLinks(textView: TextView?) {
        if (textView == null) {
            return
        }
        textView.setTextIsSelectable(true)
        textView.isFocusable = true
        textView.isClickable = true
        textView.isLongClickable = true
        textView.highlightColor = 857839347
        val value = textView.text
        val spannable: Spannable
        if (value is Spannable) {
            spannable = value
        } else {
            spannable = SpannableString(value ?: "")
            textView.setText(spannable, TextView.BufferType.SPANNABLE)
        }
        val linkColor = getColor(R.color.link_color)
        val urlPattern = Pattern.compile(
            "(?<!\\w)(?:https?://[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+|www\\.[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+)(?<![,.;:!?)>])",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        )
        FixNestedScrollLinkMovementMethod.matcherLinkify(
            spannable, urlPattern,
            { url -> canonicalizeUrl(url) ?: url },
            { url -> openLink(url) }
        )
        val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        for (oldSpan in urlSpans) {
            val targetUrl = canonicalizeUrl(oldSpan.url)
            val start = spannable.getSpanStart(oldSpan)
            val end = spannable.getSpanEnd(oldSpan)
            val flags = spannable.getSpanFlags(oldSpan)
            val spanText = spannable.subSequence(start, end).toString().trim()
            val isReplyLink = spanText == "回复" ||
                    (targetUrl != null && (targetUrl.contains("action=reply") || targetUrl.contains("mod=post")) && spanText.contains("回复"))
            spannable.removeSpan(oldSpan)
            if (start >= 0 && end > start) {
                if (isReplyLink) {
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            showReplyBottomSheet(currentReplyTarget)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = linkColor
                            ds.isUnderlineText = true
                        }
                    }, start, end, flags)
                } else if (!TextUtils.isEmpty(targetUrl)) {
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openLink(targetUrl)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = linkColor
                            ds.isUnderlineText = true
                        }
                    }, start, end, flags)
                }
            }
        }
        // 针对正文中包含的隐藏内容提示“...请回复”做增强：确保仅“回复”两字为高亮可点击并呼出输入框
        val fullStr = spannable.toString()
        val replyKeywords = arrayOf("如果您要查看本帖隐藏内容请回复", "要查看本帖隐藏内容请回复", "隐藏内容请回复")
        for (kw in replyKeywords) {
            var index = fullStr.indexOf(kw)
            while (index >= 0) {
                val replyStart = index + kw.lastIndexOf("回复")
                val replyEnd = replyStart + 2
                val existingSpans = spannable.getSpans(replyStart, replyEnd, ClickableSpan::class.java)
                if (existingSpans.isEmpty()) {
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            showReplyBottomSheet(currentReplyTarget)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = linkColor
                            ds.isUnderlineText = true
                        }
                    }, replyStart, replyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                index = fullStr.indexOf(kw, index + kw.length)
            }
        }
        textView.movementMethod = FixNestedScrollLinkMovementMethod()
        textView.autoLinkMask = 0
    }

    private fun canonicalizeUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) {
            return url
        }
        if (url!!.startsWith("//")) {
            return "https:$url"
        }
        if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1)
        }
        if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2)
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        if (url.startsWith("www.")) {
            // 用 https：本应用已移除 usesCleartextTraffic，明文请求会被系统直接拦掉
            return "https://$url"
        }
        return HttpClient.BASE_URL + url
    }

    private fun openLink(url: String?) {
        if (TextUtils.isEmpty(url)) {
            return
        }
        try {
            val lower = url!!.lowercase(Locale.ROOT)
            if (lower.contains("action=reply") || lower.contains("mod=post&action=reply")) {
                showReplyBottomSheet(currentReplyTarget)
                return
            }
            val isForumLink = lower.contains("bbs.binmt.cc")

            if (isForumLink) {
                // 论坛帖子链接: thread-{tid}-1-1.html 或 forum.php?mod=viewthread&tid={tid}
                var m = Pattern.compile("thread[-=]?(\\d+)").matcher(lower)
                if (m.find()) {
                    NavigationHelper.openThread(this, m.group(1))
                    return
                }

                // 论坛分区链接: forum-{fid}-1.html 或 forum.php?mod=forumdisplay&fid={fid}
                m = Pattern.compile("(?:forum-|(?<=[?&])fid=)(\\d+)").matcher(lower)
                if (m.find()) {
                    Toast.makeText(this, "论坛分区链接", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return
                }

                // 用户空间链接
                m = Pattern.compile("(?:uid[-=]|(?<=[?&])uid=)(\\d+)").matcher(lower)
                if (m.find()) {
                    val intent = Intent(this, UserProfileActivity::class.java)
                    intent.putExtra("uid", m.group(1))
                    startActivity(intent)
                    return
                }
                m = Pattern.compile("space-username-([^./?&]+)").matcher(lower)
                if (m.find()) {
                    val intent = Intent(this, UserProfileActivity::class.java)
                    intent.putExtra("username", m.group(1))
                    startActivity(intent)
                    return
                }
            }

            // 非论坛链接或无法识别的论坛链接,用浏览器打开
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isValidUploadUid(uid: String?): Boolean {
        if (TextUtils.isEmpty(uid)) {
            return false
        }
        try {
            return uid!!.toLong() > 0
        } catch (e: NumberFormatException) {
            return false
        }
    }

    private fun extractAndSeparateImages(html: String?, imageUrls: MutableList<String>): String {
        if (TextUtils.isEmpty(html)) {
            return ""
        }
        try {
            val doc = Jsoup.parse(html!!)
            doc.select("script").remove()
            doc.select("style").remove()
            doc.select("ignore_js_op").remove()
            doc.select("*:matchesOwn(^border\\s*=\\s*[\"']?\\d)").remove()
            val imgs = doc.select("img")
            for (img in imgs) {
                var realUrl: String? = null
                if (img.hasAttr("file") && !TextUtils.isEmpty(img.attr("file"))) {
                    realUrl = img.attr("file")
                } else if (img.hasAttr("comiis_loadimages") && !TextUtils.isEmpty(img.attr("comiis_loadimages"))) {
                    realUrl = img.attr("comiis_loadimages")
                } else if (img.hasAttr("data-original") && !TextUtils.isEmpty(img.attr("data-original"))) {
                    realUrl = img.attr("data-original")
                } else if (img.hasAttr("data-src") && !TextUtils.isEmpty(img.attr("data-src"))) {
                    realUrl = img.attr("data-src")
                } else if (img.hasAttr("data-file") && !TextUtils.isEmpty(img.attr("data-file"))) {
                    realUrl = img.attr("data-file")
                } else if (img.hasAttr("src") && !TextUtils.isEmpty(img.attr("src"))) {
                    realUrl = img.attr("src")
                }
                if (realUrl != null && realUrl.isNotEmpty()) {
                    val fullUrl = normalizeImageUrl(realUrl)
                    if (fullUrl != null && !fullUrl.contains("smiley") && !fullUrl.contains("emoticon")
                        && !fullUrl.contains("face") && !fullUrl.contains("/static/image/smiley")
                        && !fullUrl.contains("stamp") && !fullUrl.contains("magic")
                        && !fullUrl.contains("mini") && !fullUrl.contains("icon")
                        && !fullUrl.contains("none.gif") && !fullUrl.contains("common_")
                        && !imageUrls.contains(fullUrl)
                    ) {
                        imageUrls.add(fullUrl)
                    }
                }
            }
            doc.select("img").remove()
            val cleanedText = doc.body().html()
            var out = Regex("(?i)replyreload\\s*\\+?\\s*=\\s*'[^']*'").replace(cleanedText, "")
            out = Regex("(?i)replyreload\\s*\\+?\\s*=\\s*\"[^\"]*\"").replace(out, "")
            out = Regex("(?i)replyreload\\s*\\+?\\s*=\\s*[^;\\s<]+").replace(out, "")
            out = Regex("\\s*border\\s*=\\s*[\"'][^\"']*[\"']").replace(out, "")
            out = Regex("\\s*alt\\s*=\\s*[\"'][^\"']*[\"']").replace(out, "")
            out = Regex("\\s*title\\s*=\\s*[\"'][^\"']*[\"']").replace(out, "")
            out = Regex("<[^>]*>\\s*<").replace(out, "<")
            return out
        } catch (e: Exception) {
            return fallbackExtractImages(html!!, imageUrls)
        }
    }

    private fun fallbackExtractImages(html: String, imageUrls: MutableList<String>): String {
        var cleaned = Regex("(?i)<script[^>]*>.*?</script>").replace(html, "")
        cleaned = Regex("(?i)<style[^>]*>.*?</style>").replace(cleaned, "")
        val imgPattern = Pattern.compile(
            "<img[^>]*(?:file|comiis_loadimages|data-original|data-src|data-file|src)=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = imgPattern.matcher(cleaned)
        while (matcher.find()) {
            val url = matcher.group(1)
            val fullUrl = normalizeImageUrl(url)
            if (fullUrl != null && !fullUrl.contains("smiley") && !fullUrl.contains("face")
                && !fullUrl.contains("emoticon") && !fullUrl.contains("icon")
                && !fullUrl.contains("none.gif") && !fullUrl.contains("common_")
                && !imageUrls.contains(fullUrl)
            ) {
                imageUrls.add(fullUrl)
            }
        }
        return Regex("(?i)<img[^>]*>").replace(cleaned, "")
    }

    private fun normalizeImageUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) {
            return null
        }
        if (url!!.startsWith("//")) {
            return "https:$url"
        }
        if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1)
        }
        if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2)
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        return HttpClient.BASE_URL + url
    }

    override fun onDestroy() {
        if (mBottomSheetDialog != null && mBottomSheetDialog!!.isShowing) {
            try {
                mBottomSheetDialog!!.dismiss()
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int {
        return ((dp * resources.displayMetrics.density) + 0.5f).toInt()
    }

    companion object {
        private const val HIDDEN_QUOTE_PLACEHOLDER = "\ue000\ue001\ue002\ue003"
        private const val KEY_FAVORITED_PREFIX = "fav_"
        private const val KEY_LIKED_PREFIX = "liked_"
        private const val PREF_LIKE_FAV = "thread_like_fav_state"
        private const val REQUEST_IMAGE_PICK = 1002
        private const val REQUEST_EDIT_THREAD = 1003 // build73: 编辑帖子
    }
}
