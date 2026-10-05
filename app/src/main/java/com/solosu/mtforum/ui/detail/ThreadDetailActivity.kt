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
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
import com.solosu.mtforum.databinding.ItemThreadDetailHeaderBinding
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
import com.solosu.mtforum.util.RoundedImageDrawable
import com.solosu.mtforum.util.UrlDrawable

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.regex.Matcher
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

    /**
     * 「帖子正文头」item 的 ViewBinding。
     *
     * 正文头已从 activity 布局移到 item_thread_detail_header.xml，作为评论区 RecyclerView
     * 的第 0 项（整页只由一个 RecyclerView 滚动，恢复回收复用）。头视图会被回收重建，
     * 所以每次绑定都要重新赋值。未绑定时为 null。
     */
    private var headerBinding: ItemThreadDetailHeaderBinding? = null
    private var tid: String? = null
    private var onlyOpReplies = false
    private var repliesDescending = false // 默认时间正序（楼层从小到大）
    private var displayedReplies: MutableList<ReplyItem> = ArrayList()
    private var isLiked = false
    private var likeCount = 0
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

    /** 回复弹窗非图片附件 */
    private val replyAttachFiles: MutableList<ReplyAttachFile> = ArrayList()

    /** 回复弹窗附件选择器 */
    private var replyFilePickerLauncher: ActivityResultLauncher<Array<String>>? = null

    /** 论坛真实表情集（懒加载后缓存） */
    private var replySmileyCatalog: MutableList<ForumParser.SmileySet>? = null
    private var replySmileySetIndex = 0

    /** 插入面板当前选中的内容类型 */
    private var pendingInsertType = -1

    /** 当前帖全部图片(正文+附件+隐藏区,按 bindData 收集顺序) */
    private var currentImageList: MutableList<String> = ArrayList()

    /** build70: 相册选图 launcher */
    private var imagePickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    /** 原生回复贴底面板的系统返回键单次收起回调 */
    private var replyBackPressedCallback: androidx.activity.OnBackPressedCallback? = null

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
        initReplyPanel()
        val onBottomReplyBarClick = View.OnClickListener { showReplyBottomSheet(currentReplyTarget) }
        binding.etReply.setOnClickListener(onBottomReplyBarClick)
        binding.tilReply.setOnClickListener(onBottomReplyBarClick)
        binding.layoutReply.setOnClickListener(onBottomReplyBarClick)
        binding.etReply.isFocusable = false
        binding.etReply.isCursorVisible = false
        val commentsClick = View.OnClickListener {
            // 评论区现在是同一个 RecyclerView 里的 item，直接滚到「评论标题栏」位置
            scrollToReplySection()
        }
        binding.btnComments.setOnClickListener(commentsClick)
        binding.layoutComments.setOnClickListener(commentsClick)

        val pickImageClick = View.OnClickListener { pickImage() }
        binding.btnPickImageInline.setOnClickListener(pickImageClick)
        binding.layoutPickImage.setOnClickListener(pickImageClick)

        binding.btnLike.setOnClickListener { toggleLike() }
        binding.layoutLike.setOnClickListener { toggleLike() }
        // 长按点赞图标 → 弹出「赞过此帖的人」列表（原正文底部「赞过」行已移除）
        val showLikers = View.OnLongClickListener {
            showLikeUsersSheet()
            true
        }
        binding.layoutLike.setOnLongClickListener(showLikers)
        binding.btnLike.setOnLongClickListener(showLikers)

        // 顶栏双击快速回到顶部
        com.solosu.mtforum.util.ScrollToTopHelper.attachRecyclerView(binding.toolbar, binding.recyclerReplies)

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.layoutFavorite.setOnClickListener { toggleFavorite() }
        binding.btnShare.setOnClickListener { shareThread() }
        // 顶栏图标：打赏 / 踢帖（原来在正文下方一整条按钮，现上移到分享左侧）
        binding.btnReward.setOnClickListener { showRewardDialog() }
        binding.btnKick.setOnClickListener { showKickDialog() }
        loadPostDetail()
    }

    /**
     * 取正文头视图（单例）。
     *
     * 头视图一旦被 RecyclerView 回收，其内容（尤其是 tvContent 里那大块 HTML）
     * 就得重新构建。这里全程复用同一个实例，因此 bindData 渲染过的内容不会因
     * 滚动离开屏幕而丢失；只有监听需要在每次绑定时重挂（在 bindThreadHeader 里）。
     *
     * 不做 removeView：暂存的视图本就不在父容器里（ensureHeaderBound 阶段已在树外，
     * 或已被 RecyclerView 移除）；若仍在 RecyclerView 上说明回收/解绑顺序异常，
     * 此时 return 原 view 由 RecyclerView 自行处理，避免从父子树上把活视图抽走。
     */
    private var headerView: View? = null

    private fun obtainHeaderView(): View {
        headerView?.let { return it }
        // 必须传 parent 才能拿到正确的 LayoutParams：
        // 传 null 时根布局的 layout_width="match_parent" 不生成 LayoutParams，
        // RecyclerView 只能用默认 WRAP_CONTENT，头视图宽度会退化成“由子控件撑出来”
        // ——正文会被压成一小条、右侧大片空白（曾真实踩到）。
        val v = layoutInflater.inflate(R.layout.item_thread_detail_header,
                binding.recyclerReplies, false)
        // 双保险：显式声明宽度匹配父容器，不依赖 inflate 的推导结果
        v.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        headerView = v
        return v
    }

    /** 确保正文头已创建并绑定，供 bindData 在 RecyclerView 首次布局前使用。 */
    private fun ensureHeaderBound() {
        if (headerBinding != null) return
        bindThreadHeader(obtainHeaderView())
    }

    /**
     * 绑定正文头 item（它现在是 RecyclerView 的第 0 项，会被回收重建）。
     *
     * 头的控件监听原来在 onCreate 里一次性挂到 activity 视图上；视图改成 item 后
     * 每次重建监听都会丢，所以改为每次绑定重挂；数据回填走 applyHeaderFromDetail()。
     */
    private fun bindThreadHeader(view: View) {
        val hb: ItemThreadDetailHeaderBinding
        try {
            hb = ItemThreadDetailHeaderBinding.bind(view)
        } catch (e: Throwable) {
            android.util.Log.e("ThreadDetail", "Failed to bind thread header", e)
            return
        }
        headerBinding = hb

        hb.btnViewHidden.setOnClickListener { viewHiddenContent() }
        hb.layoutHiddenContent.setOnClickListener { viewHiddenContent() }
        // 动态适配隐藏内容卡片的主题色（锁图标、圆底、去回复按钮）
        applyHiddenCardTheme(hb)

        // 打赏/踢帖已移到顶栏图标（见 onCreate 的 binding.btnReward / binding.btnKick）
        hb.btnCollapseImages.setOnClickListener { toggleImageGallery() }

        // 图二分段药丸切换：楼主 / 正序 / 倒序
        hb.btnSegmentOp.setOnClickListener {
            onlyOpReplies = !onlyOpReplies
            updateReplyFilterAndOrder()
        }
        hb.btnSegmentAsc.setOnClickListener {
            if (repliesDescending) {
                repliesDescending = false
                refreshPostDetail()
            }
        }
        hb.btnSegmentDesc.setOnClickListener {
            if (!repliesDescending) {
                repliesDescending = true
                refreshPostDetail()
            }
        }

        // 正文由 bindData 负责渲染（含巨额 HTML），这里只保证绑定后的视觉状态正确
        applyHeaderStaticState()
    }

    private fun applyHiddenCardTheme(hb: ItemThreadDetailHeaderBinding) {
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        // 1. 去回复按钮：动态圆角渐变/纯色主题背景
        val btnBg = GradientDrawable().apply {
            cornerRadius = dpToPx(16).toFloat()
            setColor(themeColor)
        }
        hb.btnViewHidden.background = btnBg
        // 2. 锁头图标底衬：约 15% 透明度主题色圆底
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            val alphaColor = androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 0x26)
            setColor(alphaColor)
        }
        hb.layoutLockCircle.background = circleBg
        // 3. 锁头图标：主题色着色
        hb.ivHiddenLock.setColorFilter(themeColor)
    }

    private fun updateSegmentPill(tv: TextView, isSelected: Boolean) {
        if (isSelected) {
            tv.setBackgroundResource(R.drawable.bg_segment_pill_selected)
            tv.setTextColor(getColor(R.color.text_primary))
            tv.setTypeface(null, Typeface.BOLD)
        } else {
            tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            tv.setTextColor(getColor(R.color.text_secondary))
            tv.setTypeface(null, Typeface.NORMAL)
        }
    }

    /**
     * header 被回收重建后，把依赖 postDetail 的静态状态重新套上。
     * 不动 tvContent：它的 HTML 由 bindData 渲染并缓存在 postDetail.contentHtml，
     * 重新 fromHtml 一次代价很高，滚动中就重建 header 会明显掉帧。
     */
    private fun applyHeaderStaticState() {
        val hb = headerBinding ?: return
        val detail = postDetail ?: return
        hb.tvThreadTitle.text = if (!TextUtils.isEmpty(detail.title)) detail.title else ""
        hb.tvThreadTitle.isLongClickable = true
        hb.tvThreadTitle.setOnLongClickListener {
            reportPost(null)
            true
        }
        if (!TextUtils.isEmpty(detail.forumName)) {
            hb.tvForumName.visibility = View.VISIBLE
            hb.tvForumName.text = detail.forumName
        } else {
            hb.tvForumName.visibility = View.GONE
        }
        val avatarUrl = detail.avatarUrl
        if (!TextUtils.isEmpty(avatarUrl)) {
            Glide.with(this as FragmentActivity).load(avatarUrl).transform(CircleCrop())
                .placeholder(R.drawable.ic_account).error(R.drawable.ic_account)
                .into(hb.ivAuthorAvatar)
        } else {
            hb.ivAuthorAvatar.setImageResource(R.drawable.ic_account)
        }
        hb.tvAuthorName.text = if (!TextUtils.isEmpty(detail.author)) detail.author else "匿名"
        val authorUid = detail.authorUid
        if (!TextUtils.isEmpty(authorUid)) {
            hb.ivAuthorAvatar.setOnClickListener { openUserProfile(authorUid, detail.author) }
            hb.tvAuthorName.setOnClickListener { openUserProfile(authorUid, detail.author) }
        }
        if (!TextUtils.isEmpty(detail.authorLevel)) {
            hb.tvAuthorLevel.visibility = View.VISIBLE
            hb.tvAuthorLevel.text = detail.authorLevel
        } else {
            hb.tvAuthorLevel.visibility = View.GONE
        }
        hb.tvPublishTime.text = if (!TextUtils.isEmpty(detail.publishTime)) detail.publishTime else ""
        hb.tvLocation.visibility = View.GONE

        // 图集重建：bindData 只在首次渲染时填过图集，header 视图重建后必须补上，
        // 否则正文图会整块消失（只有图片列表，不需要重新 fromHtml）。
        // 兜底：若正文抽取为空，则用解析器从消息区抽到的 imageUrls。
        val galleryList = if (currentImageList.isNotEmpty()) currentImageList else detail.imageUrls
        rebuildImageGallery(galleryList)
        // 打赏/好评统计块与「赞过」行已移除：打赏人数改为顶栏图标角标，
        // 赞过列表改为长按底部点赞图标弹出
        bindRewardBadge(detail)
    }

    /**
     * 重建正文图集。
     *
     * 拆成独立方法是因为它需要在两个时机执行：bindData 首次渲染，以及 header
     * 视图被回收重建后的重新绑定。之前只做了前者，导致 header 一重建图集就空掉。
     */
    private fun rebuildImageGallery(imageList: List<String>?) {
        val hb = headerBinding ?: return
        // 正文中大图已原位图文混排呈现，隐藏底部冗余重复的横向缩略图卡片
        hb.cardImageGallery.visibility = View.GONE
        hb.llImageGallery.removeAllViews()
    }

    /**
     * 打赏：按用户要求仅保留顶栏红包图标，不再显示任何数字角标
     */
    private fun bindRewardBadge(detail: PostDetail) {
    }

    private fun setupRecyclerView() {
        replyAdapter = ReplyAdapter(ArrayList())
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
                    currentReplyTarget = "回复 @$author："
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

        // 正文头作为第 0 个 item，整页由这个 RecyclerView 自己滚动，
        // 不再依赖会一次性布局全部子项的 NestedScrollView，从而恢复回收复用。
        replyAdapter!!.setHeaderProvider(object : ReplyAdapter.HeaderProvider {
            override fun onCreateHeaderView(parent: ViewGroup): View {
                return obtainHeaderView()
            }

            override fun onBindHeaderView(view: View) {
                bindThreadHeader(view)
            }
        })

        binding.recyclerReplies.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 向下滚时检查是否该自动加载下一页（见 maybeAutoLoadMore）
                if (dy > 0) maybeAutoLoadMore()
            }
        })
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
                refreshServerActionState(detail)
                if (!TextUtils.isEmpty(detail.authorUid)) {
                    detail.isFollowed = FollowStateManager.resolve(this, detail.authorUid, detail.isFollowed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bindData(detail, true)
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
                refreshServerActionState(detail)
                if (!TextUtils.isEmpty(detail.authorUid)) {
                    detail.isFollowed = FollowStateManager.resolve(this, detail.authorUid, detail.isFollowed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.swipeRefresh.isRefreshing = false
                    applyServerActionState(detail)
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
        // 正文头是 RecyclerView 的 item，bindData 会直接写入它的控件；
        // RecyclerView 首次布局前 header 可能尚未创建/绑定，这里先确保就绪。
        ensureHeaderBound()
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isEnabled = true
        if (!TextUtils.isEmpty(postDetail.forumName)) {
            headerBinding!!.tvForumName.visibility = View.VISIBLE
            headerBinding!!.tvForumName.text = postDetail.forumName
        } else {
            headerBinding!!.tvForumName.visibility = View.GONE
        }
        headerBinding!!.tvThreadTitle.text = if (!TextUtils.isEmpty(postDetail.title)) postDetail.title else ""
        // build75: 长按标题 -> 举报帖子
        headerBinding!!.tvThreadTitle.isLongClickable = true
        headerBinding!!.tvThreadTitle.setOnLongClickListener {
            reportPost(null)
            true
        }
        val avatarUrl = postDetail.avatarUrl
        if (!TextUtils.isEmpty(avatarUrl)) {
            Glide.with(this as FragmentActivity).load(avatarUrl).transform(CircleCrop())
                .placeholder(R.drawable.ic_account).error(R.drawable.ic_account)
                .into(headerBinding!!.ivAuthorAvatar)
        } else {
            headerBinding!!.ivAuthorAvatar.setImageResource(R.drawable.ic_account)
        }
        headerBinding!!.tvAuthorName.text = if (!TextUtils.isEmpty(postDetail.author)) postDetail.author else "匿名"
        val authorUid = postDetail.authorUid
        if (!TextUtils.isEmpty(authorUid)) {
            headerBinding!!.ivAuthorAvatar.setOnClickListener { openUserProfile(authorUid, postDetail.author) }
            headerBinding!!.tvAuthorName.setOnClickListener { openUserProfile(authorUid, postDetail.author) }
        }
        if (!TextUtils.isEmpty(postDetail.authorLevel)) {
            headerBinding!!.tvAuthorLevel.visibility = View.VISIBLE
            headerBinding!!.tvAuthorLevel.text = postDetail.authorLevel
        } else {
            headerBinding!!.tvAuthorLevel.visibility = View.GONE
        }
        headerBinding!!.tvPublishTime.text = if (!TextUtils.isEmpty(postDetail.publishTime)) postDetail.publishTime else ""
        headerBinding!!.tvLocation.visibility = View.GONE
        // 收藏数回填缓存:详情页拿到数字后存进 FavoritesCache,列表卡片第四格就能显示
        if (postDetail.favoriteCount > 0) {
            FavoritesCache.put(this, postDetail.tid, postDetail.favoriteCount)
        }
        if (httpClient.isLoggedIn() && !TextUtils.isEmpty(postDetail.author)) {
            headerBinding!!.btnFollow.visibility = View.VISIBLE
            if (isOwnThread(postDetail)) {
                // build73: 自己的帖子 -> 右上角是「编辑」(不是关注)
                headerBinding!!.btnFollow.text = "编辑"
                headerBinding!!.btnFollow.setOnClickListener { openEditThread() }
            } else {
                headerBinding!!.btnFollow.setText(
                    if (postDetail.isFollowed) R.string.action_followed else R.string.action_follow
                )
                headerBinding!!.btnFollow.setOnClickListener { toggleFollow() }
            }
        } else {
            headerBinding!!.btnFollow.visibility = View.GONE
        }
        val contentHtml = postDetail.contentHtml
        var hiddenNotice = ""
        if (!TextUtils.isEmpty(contentHtml)) {
            val converted = BBCodeUtil.convertBBCodeToHtml(contentHtml)
            headerBinding!!.tvContent.visibility = View.VISIBLE
            val imageList = ArrayList<String>()
            val footerSplit = splitEditFooter(converted)
            val cleaned = extractAndSeparateImages(footerSplit[0], imageList)
            val imageUrls = postDetail.imageUrls ?: ArrayList<String>().also { postDetail.imageUrls = it }
            val missingImages = ArrayList<String>()
            if (imageUrls.isNotEmpty()) {
                for (str in imageUrls) {
                    val alreadyInContent = imageList.any { isSameImage(it, str) }
                    if (!alreadyInContent && missingImages.none { isSameImage(it, str) }) {
                        imageList.add(str)
                        missingImages.add(str)
                    }
                }
            }
            // 自动补全不在正文 HTML 内的附件大图，防止发帖附件图片在正文中丢失
            var fullHtml = cleaned
            if (missingImages.isNotEmpty()) {
                val sbAttach = StringBuilder()
                for (imgUrl in missingImages) {
                    sbAttach.append("<br><br><img src=\"").append(imgUrl).append("\">")
                }
                fullHtml += sbAttach.toString()
            }
            if (!TextUtils.isEmpty(footerSplit[1])) {
                headerBinding!!.layoutEditFooter.visibility = View.VISIBLE
                headerBinding!!.tvEditFooter.text = footerSplit[1]
            } else {
                headerBinding!!.layoutEditFooter.visibility = View.GONE
            }
            // 收集当前帖全部图片供全屏翻页
            currentImageList = ArrayList(imageList)

            val unlocked = postDetail.hasHiddenContent && httpClient.isLoggedIn()
                    && !TextUtils.isEmpty(postDetail.hiddenContentHtml)
                    && !isHiddenContentLocked(postDetail.hiddenContentHtml)

            val displayHtml: String
            if (unlocked) {
                // 已解锁：保留正文中的所有隐藏内容/引用块原位展示（与网页端排版顺序一致）
                // 统一将正文中的 comiis_quote / locked 转换为带有专属金色高亮标题与一键复制的 unlocked-hidden-card 质感卡片
                val quotePattern = Pattern.compile(
                    "<div\\s+class=[\"'](?:comiis_quote|locked)[^\"']*[\"']>(.*?)</div>",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                )
                val qMatcher = quotePattern.matcher(fullHtml)
                val sbQuote = StringBuffer()
                var quoteFound = false
                while (qMatcher.find()) {
                    quoteFound = true
                    var inner = qMatcher.group(1) ?: ""
                    qMatcher.appendReplacement(sbQuote, Matcher.quoteReplacement("<div class=\"unlocked-hidden-card\">$inner</div>"))
                }
                qMatcher.appendTail(sbQuote)
                var resolvedHtml = sbQuote.toString()
                if (!quoteFound && !TextUtils.isEmpty(postDetail.hiddenContentHtml)) {
                    val fallbackHidden = postDetail.hiddenContentHtml!!
                    resolvedHtml = "<div class=\"unlocked-hidden-card\">$fallbackHidden</div>" + resolvedHtml
                }
                displayHtml = resolvedHtml
                headerBinding!!.layoutHiddenContent.visibility = View.GONE
            } else if (postDetail.hasHiddenContent) {
                // 未解锁：将正文中的隐藏内容替换为锁定占位，并在底部显示提示回复解锁引导
                val placeholders = replaceHiddenQuoteWithPlaceholder(fullHtml)
                displayHtml = placeholders[0]
                hiddenNotice = placeholders[1]
                headerBinding!!.layoutHiddenContent.visibility = View.VISIBLE
                headerBinding!!.layoutHiddenLockedRow.visibility = View.VISIBLE
                if (httpClient.isLoggedIn()) {
                    headerBinding!!.tvHiddenContentHint.text = "回复本帖后自动刷新解锁"
                    headerBinding!!.btnViewHidden.text = "去回复"
                } else {
                    headerBinding!!.tvHiddenContentHint.text = "请先登录并回复查看"
                    headerBinding!!.btnViewHidden.text = "去登录"
                }
                applyHiddenCardTheme(headerBinding!!)
                headerBinding!!.tvHiddenContent.visibility = View.GONE
            } else {
                displayHtml = fullHtml
                headerBinding!!.layoutHiddenContent.visibility = View.GONE
            }

            renderContentSections(displayHtml, hiddenNotice)
            rebuildImageGallery(imageList)
        } else {
            headerBinding!!.tvContent.visibility = View.VISIBLE
            headerBinding!!.cardImageGallery.visibility = View.GONE
            headerBinding!!.tvContent.text = "[内容加载中，请刷新重试]"
            headerBinding!!.tvContent.setTextColor(getColor(R.color.text_hint))
            headerBinding!!.tvContent.textSize = 14.0f
            headerBinding!!.tvContent.gravity = Gravity.CENTER
            headerBinding!!.layoutHiddenContent.visibility = View.GONE
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
        replyAdapter!!.setFooterActionListener(object : ReplyAdapter.FooterActionListener {
            override fun onRetry() {
                replyAdapter?.setFooterState(null)
                loadMoreReplies()
            }
        })
        updateReplyFilterAndOrder()
        val replyCount = postDetail.replyCount
        if (replyCount > 0) {
            headerBinding!!.tvReplyCount.visibility = View.VISIBLE
            headerBinding!!.tvReplyCount.text = "($replyCount)"
        } else {
            headerBinding!!.tvReplyCount.visibility = View.GONE
        }

        fun updateFoldedBadge(count: Int, isExpanded: Boolean) {
            val hb = headerBinding ?: return
            if (count > 0) {
                hb.tvFoldedBadge.visibility = View.VISIBLE
                hb.tvFoldedBadge.text = if (isExpanded) "收起已折叠" else "已折叠 $count 条"
            } else {
                hb.tvFoldedBadge.visibility = View.GONE
            }
        }

        replyAdapter?.onFoldStateChanged = { count, isExpanded ->
            updateFoldedBadge(count, isExpanded)
        }
        updateFoldedBadge(replyAdapter?.foldedCount ?: 0, replyAdapter?.isFoldExpanded ?: false)

        headerBinding!!.tvFoldedBadge.setOnClickListener {
            replyAdapter?.toggleFoldExpanded()
        }
        binding.layoutReply.visibility = if (httpClient.isLoggedIn()) View.VISIBLE else View.GONE
        // 打赏/踢帖改为顶栏图标，不再按登录态显隐正文里的按钮条
        bindRewardBadge(postDetail)
        if (scrollToTop && binding.recyclerReplies.computeVerticalScrollOffset() != 0) {
            binding.recyclerReplies.scrollToPosition(0)
        }
    }

    private fun openUserProfile(uid: String?, username: String?) {
        val intent = Intent(this, UserProfileActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_UID, uid)
        intent.putExtra("username", username)
        startActivity(intent)
    }

    private fun toggleImageGallery() {
        val isCollapsed = headerBinding!!.hsvImageGallery.visibility == View.GONE
        if (isCollapsed) {
            headerBinding!!.hsvImageGallery.visibility = View.VISIBLE
            headerBinding!!.hsvImageGallery.alpha = 0.0f
            headerBinding!!.hsvImageGallery.animate().alpha(1.0f).setDuration(300L).start()
            headerBinding!!.btnCollapseImages.animate().rotation(90.0f).setDuration(200L).start()
            return
        }
        headerBinding!!.hsvImageGallery.animate().alpha(0.0f).setDuration(200L)
            .withEndAction { headerBinding!!.hsvImageGallery.visibility = View.GONE }
            .start()
        headerBinding!!.btnCollapseImages.animate().rotation(-90.0f).setDuration(200L).start()
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
        // 过滤/排序后数据整体变了，底部状态行重置：还有下一页就让滚动监听重新触发
        if (postDetail != null && postDetail!!.currentPage < postDetail!!.totalPages) {
            replyAdapter!!.setFooterState(null)
            // 首屏可能不够清屏内容、用户根本滑不动；布局完成后补一次检查
            binding.recyclerReplies.post { maybeAutoLoadMore() }
        } else {
            replyAdapter!!.setFooterState(ReplyAdapter.FooterState.END)
        }
        // 图二分段药丸样式刷新：白底卡片选中，移除彩色文字
        headerBinding?.let {
            updateSegmentPill(it.btnSegmentOp, onlyOpReplies)
            updateSegmentPill(it.btnSegmentAsc, !repliesDescending)
            updateSegmentPill(it.btnSegmentDesc, repliesDescending)
        }
        if (result.isEmpty()) {
            // 评论为空时 RecyclerView 仍要显示：正文头是它的第 0 项。
            binding.recyclerReplies.visibility = View.VISIBLE
            binding.recyclerReplies.scrollToPosition(0)
            headerBinding?.tvEmptyReplies?.visibility = View.VISIBLE
        } else {
            binding.recyclerReplies.visibility = View.VISIBLE
            headerBinding?.tvEmptyReplies?.visibility = View.GONE
        }
    }

    /** 跳转到评论区（正文头之后的第一屏评论，无动画瞬间直达）。 */
    private fun scrollToReplySection() {
        binding.recyclerReplies.post {
            val lm = binding.recyclerReplies.layoutManager as? LinearLayoutManager
            if (lm != null) {
                lm.scrollToPositionWithOffset(ReplyAdapter.HEADER_ITEM_COUNT, 0)
            } else {
                binding.recyclerReplies.scrollToPosition(ReplyAdapter.HEADER_ITEM_COUNT)
            }
        }
    }

    // ==================== 回复 ====================

    /** 标记回复面板是否正在打开中，避免初始打开软键盘尚未弹起时的错误联动 */
    private var isOpeningReplyPanel = false

    private fun initReplyPanel() {
        val onBottomReplyBarClick = View.OnClickListener { showReplyBottomSheet(currentReplyTarget) }
        binding.etReply.setOnClickListener(onBottomReplyBarClick)
        binding.tilReply.setOnClickListener(onBottomReplyBarClick)
        binding.layoutReply.setOnClickListener(onBottomReplyBarClick)
        binding.etReply.isFocusable = false
        binding.etReply.isCursorVisible = false

        binding.vReplyMask.setOnClickListener { hideReplyPanel() }

        val panel = binding.containerReplyPanel
        val etReplyDialog = panel.findViewById<com.solosu.mtforum.ui.widget.RichTextInputEditText>(R.id.et_reply_dialog)
        val btnSend = panel.findViewById<MaterialButton>(R.id.btn_send_reply)
        val tvTarget = panel.findViewById<TextView>(R.id.tv_reply_target)
        val btnPickImage = panel.findViewById<ImageButton>(R.id.btn_pick_image)

        // 表情 / @朋友 / 插入 / 附件 / 高级
        panel.findViewById<View>(R.id.btn_smile)?.setOnClickListener { toggleReplySmileyPanel() }
        panel.findViewById<View>(R.id.btn_at)?.setOnClickListener { toggleReplyAtPanel() }
        panel.findViewById<View>(R.id.btn_insert)?.setOnClickListener { toggleReplyInsertPanel() }
        panel.findViewById<View>(R.id.btn_attach)?.setOnClickListener { hideReplyToolPanels(); pickReplyFile() }
        panel.findViewById<View>(R.id.btn_advanced)?.setOnClickListener { toggleReplyAdvancedPanel() }
        panel.findViewById<View>(R.id.btn_at_insert)?.setOnClickListener {
            val name = panel.findViewById<android.widget.EditText>(R.id.et_at_username)?.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
            } else {
                insertIntoReplyDialog("@$name ")
                panel.findViewById<android.widget.EditText>(R.id.et_at_username)?.setText("")
                hideReplyToolPanels()
            }
        }

        mEtReplyDialog = etReplyDialog
        mTvReplyTarget = tvTarget
        mBtnSendReply = btnSend

        val card = panel.findViewById<View>(R.id.dialog_card)
        card?.setBackgroundResource(R.drawable.bg_reply_sheet)

        etReplyDialog?.onImageReceivedListener = { uri ->
            addPendingImages(listOf(uri))
            Toast.makeText(this@ThreadDetailActivity, "已添加图片", Toast.LENGTH_SHORT).show()
            true
        }

        btnSend?.setOnClickListener {
            val text = etReplyDialog?.text?.toString()?.trim() ?: ""
            if (TextUtils.isEmpty(text) && pendingImageUris.isEmpty()) {
                etReplyDialog?.error = getString(R.string.reply_hint_empty)
            } else {
                etReplyDialog?.error = null
                val attachTags = buildAttachTags()
                attemptReply(attachTags + text, etReplyDialog)
            }
        }

        btnPickImage?.setOnClickListener { pickImage() }
        registerReplyFilePicker()

        // 输入法激活时，拦截系统返回键一步同时收起键盘与回复面板，不再需要按两次
        etReplyDialog?.onKeyPreImeListener = {
            hideReplyPanel()
            true
        }

        // 联动全面屏手势返回：当软键盘由系统或手势收起时，回复框自动跟随立刻收起，彻底杜绝悬浮残留
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.containerReplyPanel) { _, insets ->
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (!imeVisible && isReplyPanelShowing() && !isOpeningReplyPanel) {
                hideReplyPanel()
            }
            insets
        }

        // 软键盘未激活但面板展开时的返回键回调
        val callback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideReplyPanel()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
        replyBackPressedCallback = callback
    }

    /** 回复弹窗内单个非图片附件 */
    private class ReplyAttachFile(val name: String, val aid: String)

    /** 向回复弹窗输入框光标处插入文本（替换选中内容），语义与发帖页一致 */
    private fun insertIntoReplyDialog(text: String) {
        val et = mEtReplyDialog ?: return
        val editable = et.text
        if (editable == null) {
            et.setText(text)
            return
        }
        var start = et.selectionStart
        val end = et.selectionEnd
        if (start < 0) start = editable.length
        if (end > start) {
            editable.replace(start, end, text)
        } else {
            editable.insert(start, text)
        }
        val caret = (start + text.length).coerceAtMost(editable.length)
        et.setSelection(caret)
    }

    /** 收起回复弹窗里的表情/@/插入/高级面板 */
    private fun hideReplyToolPanels() {
        val panel = binding.containerReplyPanel
        panel.findViewById<View>(R.id.ll_smiley_panel)?.visibility = View.GONE
        panel.findViewById<View>(R.id.ll_at_panel)?.visibility = View.GONE
        panel.findViewById<View>(R.id.ll_insert_panel)?.visibility = View.GONE
        panel.findViewById<View>(R.id.ll_reply_advanced)?.visibility = View.GONE
    }

    private fun toggleReplyAdvancedPanel() {
        val panel = binding.containerReplyPanel
        val adv = panel.findViewById<View>(R.id.ll_reply_advanced) ?: return
        val visible = adv.visibility == View.VISIBLE
        hideReplyToolPanels()
        if (!visible) adv.visibility = View.VISIBLE
    }

    private fun toggleReplyAtPanel() {
        val panel = binding.containerReplyPanel
        val atPanel = panel.findViewById<View>(R.id.ll_at_panel) ?: return
        val visible = atPanel.visibility == View.VISIBLE
        hideReplyToolPanels()
        if (!visible) {
            atPanel.visibility = View.VISIBLE
            panel.findViewById<android.widget.EditText>(R.id.et_at_username)?.requestFocus()
        }
    }

    /** 插入面板：与网页端一致的 9 项内容类型（链接/图片/音乐/视频/Flash/引用/代码/免费/隐藏） */
    private fun toggleReplyInsertPanel() {
        val panel = binding.containerReplyPanel
        val insert = panel.findViewById<View>(R.id.ll_insert_panel) ?: return
        val visible = insert.visibility == View.VISIBLE
        hideReplyToolPanels()
        if (visible) return
        insert.visibility = View.VISIBLE
        panel.findViewById<View>(R.id.ll_insert_input)?.visibility = View.GONE
        panel.findViewById<View>(R.id.btn_ins_link)?.setOnClickListener { selectInsertType(panel, INSERT_LINK, true, false, "链接网址", "链接文字") }
        panel.findViewById<View>(R.id.btn_ins_image)?.setOnClickListener { selectInsertType(panel, INSERT_IMAGE, true, false, "图片地址", "") }
        panel.findViewById<View>(R.id.btn_ins_audio)?.setOnClickListener { selectInsertType(panel, INSERT_AUDIO, true, false, "音乐文件地址", "") }
        panel.findViewById<View>(R.id.btn_ins_video)?.setOnClickListener { selectInsertType(panel, INSERT_VIDEO, true, false, "视频地址", "") }
        panel.findViewById<View>(R.id.btn_ins_flash)?.setOnClickListener { selectInsertType(panel, INSERT_FLASH, true, false, "Flash 地址", "") }
        panel.findViewById<View>(R.id.btn_ins_quote)?.setOnClickListener { selectInsertType(panel, INSERT_QUOTE, false, true, "", "") }
        panel.findViewById<View>(R.id.btn_ins_code)?.setOnClickListener { selectInsertType(panel, INSERT_CODE, false, true, "", "") }
        panel.findViewById<View>(R.id.btn_ins_free)?.setOnClickListener { selectInsertType(panel, INSERT_FREE, false, true, "", "") }
        panel.findViewById<View>(R.id.btn_ins_hide)?.setOnClickListener { selectInsertType(panel, INSERT_HIDE, false, true, "", "") }
    }

    private fun selectInsertType(panel: View, type: Int, needUrl: Boolean, needText: Boolean, hint1: String, hint2: String) {
        pendingInsertType = type
        val input = panel.findViewById<View>(R.id.ll_insert_input) ?: return
        val f1 = panel.findViewById<android.widget.EditText>(R.id.et_insert_field1)
        val f2 = panel.findViewById<android.widget.EditText>(R.id.et_insert_field2)
        val confirm = panel.findViewById<View>(R.id.btn_insert_confirm)
        input.visibility = View.VISIBLE
        f1?.setText("")
        f2?.setText("")
        f1?.hint = hint1
        f1?.visibility = if (needUrl || needText) View.VISIBLE else View.GONE
        f2?.hint = hint2
        f2?.visibility = if (needUrl && needText && hint2.isNotEmpty()) View.VISIBLE else View.GONE
        confirm?.setOnClickListener { applyInsert(type, f1?.text?.toString()?.trim() ?: "", f2?.text?.toString()?.trim() ?: "") }
    }

    private fun applyInsert(type: Int, field1: String, field2: String) {
        if (type == INSERT_LINK && TextUtils.isEmpty(field1)) {
            Toast.makeText(this, "请输入链接网址", Toast.LENGTH_SHORT).show()
            return
        }
        if ((type == INSERT_IMAGE || type == INSERT_AUDIO || type == INSERT_VIDEO || type == INSERT_FLASH)
            && TextUtils.isEmpty(field1)
        ) {
            Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show()
            return
        }
        val text = when (type) {
            INSERT_LINK -> {
                val label = if (TextUtils.isEmpty(field2)) field1 else field2
                "[url=" + field1 + "]" + label + "[/url]"
            }
            INSERT_IMAGE -> "[img]" + field1 + "[/img]"
            INSERT_AUDIO -> "[audio]" + field1 + "[/audio]"
            INSERT_VIDEO -> "[media]" + field1 + "[/media]"
            INSERT_FLASH -> "[flash]" + field1 + "[/flash]"
            INSERT_QUOTE -> "\n[quote]请输入引用内容[/quote]\n"
            INSERT_CODE -> "\n[code]请输入代码[/code]\n"
            INSERT_FREE -> "\n[free]请输入免费公开内容[/free]\n"
            INSERT_HIDE -> "\n[hide]请输入回复后可见的隐藏内容[/hide]\n"
            else -> ""
        }
        if (text.isNotEmpty()) insertIntoReplyDialog(text)
        hideReplyToolPanels()
    }

    // ---- 表情面板（论坛真实表情，取不到时为空提示） ----

    private fun toggleReplySmileyPanel() {
        val panel = binding.containerReplyPanel
        val smiley = panel.findViewById<View>(R.id.ll_smiley_panel) ?: return
        val visible = smiley.visibility == View.VISIBLE
        hideReplyToolPanels()
        if (visible) return
        smiley.visibility = View.VISIBLE
        loadReplySmileyCatalog()
    }

    private fun loadReplySmileyCatalog() {
        val panel = binding.containerReplyPanel
        val rv = panel.findViewById<RecyclerView>(R.id.rv_smiley) ?: return
        val empty = panel.findViewById<TextView>(R.id.tv_smiley_empty)
        val tabs = panel.findViewById<LinearLayout>(R.id.ll_smiley_tabs)
        val cached = replySmileyCatalog
        if (cached != null) {
            showSmileySet(rv, tabs, cached, replySmileySetIndex, empty)
            return
        }
        empty?.visibility = View.VISIBLE
        empty?.text = "表情加载中…"
        rv.visibility = View.GONE
        tabs?.removeAllViews()
        val tidValue = tid
        val fidValue = postDetail?.forumFid ?: "39"
        java.lang.Thread {
            var catalog: MutableList<ForumParser.SmileySet> = ArrayList()
            try {
                if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager()
                // 站点表情数据缓存文件（CDN 静态资源，名称固定，不触发 WAF），优先直接取
                for (url in ForumParser.getSmileyScriptUrlCandidates()) {
                    catalog = ForumParser.parseSmileyCatalog(httpClient.get(url))
                    if (catalog.isNotEmpty()) break
                }
                if (catalog.isEmpty()) {
                    val replyUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=reply&fid=" +
                        fidValue + "&tid=" + (tidValue ?: "")
                    val desktopHtml = httpClient.getDesktop(replyUrl)
                    catalog = ForumParser.parseSmileyCatalog(desktopHtml)
                    // 编辑器页里可能外链表情脚本，抖出地址再抓一次
                    if (catalog.isEmpty()) {
                        for (scriptUrl in ForumParser.extractSmileyScriptUrls(desktopHtml)) {
                            catalog = ForumParser.parseSmileyCatalog(httpClient.get(scriptUrl))
                            if (catalog.isNotEmpty()) break
                        }
                    }
                }
                if (catalog.isEmpty()) {
                    val newThreadUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + fidValue
                    catalog = ForumParser.parseSmileyCatalog(httpClient.getDesktop(newThreadUrl))
                }
                if (catalog.isEmpty()) {
                    val mobileHtml = httpClient.get(
                        HttpClient.BASE_URL + "forum.php?mod=post&action=reply&fid=" +
                            fidValue + "&tid=" + (tidValue ?: "") + "&mobile=2"
                    )
                    catalog = ForumParser.parseSmileyCatalog(mobileHtml)
                }
            } catch (ignored: Exception) {
            }
            val finalCatalog = catalog
            runOnUiThread {
                if (finalCatalog.isNotEmpty()) replySmileyCatalog = finalCatalog
                showSmileySet(rv, tabs, finalCatalog, 0, empty)
            }
        }.start()
    }

    /** 取不到论坛表情时回退内置图标（点击插入 [标签]），保证表情按钮始终可用 */
    private fun renderFallbackSmileyIcons(rv: RecyclerView, tabs: LinearLayout?) {
        val iconIds = intArrayOf(
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        )
        val labels = arrayOf("微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶")
        rv.visibility = View.VISIBLE
        rv.layoutManager = GridLayoutManager(this, 6)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val size = dpToPx(40)
                val iv = ImageView(parent.context)
                iv.layoutParams = RecyclerView.LayoutParams(size, size)
                iv.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.isClickable = true
                iv.setBackgroundResource(android.R.drawable.list_selector_background)
                return object : RecyclerView.ViewHolder(iv) {}
            }

            override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView as ImageView
                iv.setImageResource(iconIds[position])
                iv.setOnClickListener { insertIntoReplyDialog("[" + labels[position] + "]") }
            }

            override fun getItemCount(): Int = iconIds.size
        }
        tabs?.removeAllViews()
    }

    private fun showSmileySet(
        rv: RecyclerView,
        tabs: LinearLayout?,
        catalog: MutableList<ForumParser.SmileySet>,
        setIndex: Int,
        empty: TextView?
    ) {
        if (catalog.isEmpty()) {
            empty?.visibility = View.GONE
            renderFallbackSmileyIcons(rv, tabs)
            return
        }
        val idx = setIndex.coerceIn(0, catalog.size - 1)
        replySmileySetIndex = idx
        val items = catalog[idx].items
        empty?.visibility = View.GONE
        rv.visibility = View.VISIBLE
        rv.layoutManager = GridLayoutManager(this, 6)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val size = dpToPx(40)
                val iv = ImageView(parent.context)
                iv.layoutParams = RecyclerView.LayoutParams(size, size)
                iv.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.isClickable = true
                iv.setBackgroundResource(android.R.drawable.list_selector_background)
                return object : RecyclerView.ViewHolder(iv) {}
            }

            override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView as ImageView
                val smiley = items[position]
                Glide.with(this@ThreadDetailActivity).load(smiley.url).into(iv)
                iv.setOnClickListener { insertIntoReplyDialog(smiley.code) }
            }

            override fun getItemCount(): Int = items.size
        }
        tabs?.removeAllViews()
        if (catalog.size > 1) {
            for (i in catalog.indices) {
                val dot = View(this)
                val lp = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7))
                lp.marginStart = dpToPx(4)
                lp.marginEnd = dpToPx(4)
                dot.layoutParams = lp
                dot.setBackgroundResource(
                    if (i == idx) R.drawable.bg_segment_pill_selected else R.drawable.bg_page_indicator
                )
                dot.isClickable = true
                dot.setOnClickListener { showSmileySet(rv, tabs, catalog, i, empty) }
                tabs?.addView(dot)
            }
        }
    }

    // ---- 非图片附件 ----

    private fun registerReplyFilePicker() {
        if (replyFilePickerLauncher != null) return
        replyFilePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) uploadReplyFile(uri) }
    }

    private fun pickReplyFile() {
        if (!httpClient.isLoggedIn()) {
            httpClient.syncFromCookieManager()
            if (!httpClient.isLoggedIn()) {
                promptLogin()
                return
            }
        }
        replyFilePickerLauncher?.launch(arrayOf("*/*"))
    }

    private fun uploadReplyFile(uri: Uri) {
        Toast.makeText(this, "正在上传附件...", Toast.LENGTH_SHORT).show()
        java.lang.Thread {
            try {
                val name = buildUploadFileName(getDisplayNameFromUri(uri), contentResolver.getType(uri))
                val temp = copyUriToTempFile(uri, name)
                if (temp == null || !temp.exists()) {
                    showUploadError("无法读取文件")
                    return@Thread
                }
                if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager()
                if (!httpClient.isLoggedIn()) {
                    showUploadError("登录状态已失效，请重新登录")
                    return@Thread
                }
                val detailHtml = httpClient.getDesktop(ForumParser.getThreadDetailUrl(tid))
                var uid = extractUploadValue(detailHtml, "discuz_uid")
                var hash = extractUploadValue(detailHtml, "hash")
                if (!isValidUploadUid(uid)) uid = null
                if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(hash)) {
                    val fid = extractForumFid(detailHtml) ?: "39"
                    val postHtml = httpClient.getDesktop(
                        HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + fid
                    )
                    if (TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid")
                    if (!isValidUploadUid(uid)) uid = null
                    if (TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash")
                }
                if (!isValidUploadUid(uid) || TextUtils.isEmpty(hash)) {
                    showUploadError("获取附件上传授权失败，请重新登录后重试")
                    return@Thread
                }
                val extra = HashMap<String, String>()
                extra["uid"] = uid!!
                extra["hash"] = hash!!
                val url = HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload" +
                    "&type=attach&inajax=yes&infloat=yes&simple=2"
                val result = httpClient.uploadFileWithUserAgent(
                    url, temp, "Filedata", extra, HttpClient.DESKTOP_USER_AGENT, "application/octet-stream"
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
                    replyAttachFiles.add(ReplyAttachFile(name, finalAid))
                    updateReplyAttachList()
                    insertIntoReplyDialog("\n[attach]" + finalAid + "[/attach]\n")
                    Toast.makeText(this, "附件已上传: $name", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                showUploadError("附件上传失败: " + if (TextUtils.isEmpty(e.message)) "网络异常" else e.message)
            }
        }.start()
    }

    private fun updateReplyAttachList() {
        val panel = binding.containerReplyPanel
        val list = panel.findViewById<LinearLayout>(R.id.ll_attach_list) ?: return
        list.removeAllViews()
        if (replyAttachFiles.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        for (af in replyAttachFiles) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(R.drawable.bg_post_panel)
            row.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            val tv = TextView(this)
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.text = af.name
            tv.textSize = 12f
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.MIDDLE
            row.addView(tv)
            val del = ImageButton(this)
            del.layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            del.setImageResource(R.drawable.ic_cross)
            del.setBackgroundColor(0)
            del.setColorFilter(0xFFEF4444.toInt())
            del.setOnClickListener {
                replyAttachFiles.remove(af)
                synchronized(pendingUploadAids) { pendingUploadAids.remove(af.aid) }
                mEtReplyDialog?.let { et ->
                    val tag = "[attach]" + af.aid + "[/attach]"
                    et.setText(et.text.toString().replace(tag, ""))
                }
                updateReplyAttachList()
            }
            row.addView(del)
            list.addView(row)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (isReplyPanelShowing()) {
                hideReplyPanel()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isReplyPanelShowing(): Boolean {
        return binding.containerReplyPanel.visibility == View.VISIBLE
    }

    private fun hideReplyPanel() {
        val panel = binding.containerReplyPanel
        if (panel.visibility != View.VISIBLE) return

        isOpeningReplyPanel = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        mEtReplyDialog?.let { et ->
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus() // 关键：彻底取消回复框中的输入法焦点
        }
        binding.root.requestFocus()

        replyBackPressedCallback?.isEnabled = false
        hideReplyToolPanels()

        binding.vReplyMask.animate().cancel()
        binding.vReplyMask.visibility = View.GONE

        panel.animate().cancel()
        panel.visibility = View.GONE
        panel.translationY = 0f

        // 用户需求：已输入的内容未发送时暂时保留（草稿暂存），离开当前帖子时才清除
        currentReplyPid = ""
        currentReplyTarget = ""
    }

    private fun showReplyBottomSheet(prefillText: String?) {
        if (isFinishing || isDestroyed) return
        isOpeningReplyPanel = true
        val etReplyDialog = mEtReplyDialog
        val tvTarget = mTvReplyTarget

        if (!TextUtils.isEmpty(prefillText)) {
            if (prefillText!!.startsWith("回复 ") || prefillText.contains("：")) {
                tvTarget?.text = prefillText
                tvTarget?.visibility = View.VISIBLE
            } else {
                if (etReplyDialog?.text.isNullOrEmpty()) {
                    etReplyDialog?.setText(prefillText)
                }
                tvTarget?.text = prefillText
                tvTarget?.visibility = View.VISIBLE
            }
        } else {
            tvTarget?.visibility = View.GONE
        }
        etReplyDialog?.error = null

        // 草稿保留：光标移至当前已有文字末尾，绝不主动清空
        etReplyDialog?.setSelection(etReplyDialog.text?.length ?: 0)

        if (!pendingImageUris.isEmpty()) {
            updateDialogImagePreview()
        }

        // 像与输入法融为一体一样，从屏幕底部自然平滑升起，消除弹窗生硬感
        val panel = binding.containerReplyPanel
        panel.visibility = View.VISIBLE
        replyBackPressedCallback?.isEnabled = true

        binding.vReplyMask.visibility = View.VISIBLE
        binding.vReplyMask.alpha = 0f
        binding.vReplyMask.animate().alpha(1f).setDuration(220).start()

        panel.post {
            val h = if (panel.height > 0) panel.height.toFloat() else dpToPx(350).toFloat()
            panel.translationY = h
            panel.animate()
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        etReplyDialog?.post {
            etReplyDialog.isFocusable = true
            etReplyDialog.isFocusableInTouchMode = true
            etReplyDialog.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etReplyDialog, InputMethodManager.SHOW_IMPLICIT)
            etReplyDialog.postDelayed({ isOpeningReplyPanel = false }, 400)
        }
    }

    /** 未登录操作统一弹出登录底部弹窗(与回复弹窗同风格) */
    private fun promptLogin() {
        if (isFinishing || isDestroyed) {
            return
        }
        hideReplyPanel()
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
                    // 高级选项：签名默认开启，未勾选显式发 usesig=0；禁用表情/代码按勾选补 1
                    val advPanel = binding.containerReplyPanel
                    advPanel.findViewById<android.widget.CheckBox>(R.id.cb_reply_usesig)?.let {
                        params["usesig"] = if (it.isChecked) "1" else "0"
                    }
                    advPanel.findViewById<android.widget.CheckBox>(R.id.cb_reply_smileyoff)?.let {
                        if (it.isChecked) params["smileyoff"] = "1"
                    }
                    advPanel.findViewById<android.widget.CheckBox>(R.id.cb_reply_bbcodeoff)?.let {
                        if (it.isChecked) params["bbcodeoff"] = "1"
                    }
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
            replyAttachFiles.clear()
            updateReplyAttachList()
            synchronized(pendingUploadAids) {
                pendingUploadAids.clear()
            }
            synchronized(imageUploadPendingQueue) {
                imageUploadPendingQueue.clear()
            }
            binding.etReply.setText("")
            mEtReplyDialog?.setText("")
            refreshAllImagePreviews()
            Toast.makeText(this, R.string.reply_success, Toast.LENGTH_SHORT).show()
            hideReplyPanel()
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
        val iconRes = if (isLiked) R.drawable.ic_like_detail else R.drawable.ic_like_outline
        button.setImageResource(iconRes)
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
        val res = if (isFavorited) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
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
        headerBinding!!.btnFollow.isEnabled = false
        java.lang.Thread {
            val success = FollowStateManager.syncFollow(this, detail.authorUid, targetState)
            runOnUiThread {
                headerBinding!!.btnFollow.isEnabled = true
                if (!success) {
                    Toast.makeText(this, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                detail.isFollowed = targetState
                headerBinding!!.btnFollow.setText(
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

    private fun splitEditFooter(html: String?): Array<String> {
        if (html.isNullOrEmpty()) {
            return arrayOf("", "")
        }
        val pattern = Pattern.compile("(?is)(?:<(?:i|span|font|div|p|em)\\b[^>]*>|\\s)*本[帖贴]最后由[\\s\\S]*?编辑(?:\\s*</(?:i|span|font|div|p|em)>)*")
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            val matched = matcher.group(0) ?: ""
            var pureText = Regex("<[^>]+>").replace(matched, "")
            pureText = Regex("&nbsp;").replace(pureText, " ").trim()
            val clean = stripLeadingHtmlBreak(matcher.replaceFirst(""))
            return arrayOf(clean, pureText)
        }
        return arrayOf(stripLeadingHtmlBreak(html), "")
    }

    private fun renderHiddenContent(hiddenHtml: String?) {
        if (TextUtils.isEmpty(hiddenHtml)) {
            return
        }
        val hb = headerBinding ?: return
        hb.tvHiddenContent.visibility = View.VISIBLE
        // 高亮“本帖隐藏的内容:”为金橙色粗体，还原论坛醒目标题视觉
        val highlighted = hiddenHtml!!.replace(
            Regex("(?i)(本帖隐藏的内容[:：]?)"),
            "<font color=\"#F59E0B\"><b>$1</b></font><br>"
        )
        val bbcodeConverted = BBCodeUtil.convertBBCodeToHtml(highlighted)
        val hiddenImageUrls = ArrayList<String>()
        val cleanHiddenHtml = extractAndSeparateImages(bbcodeConverted, hiddenImageUrls)
        for (u in hiddenImageUrls) {
            if (!currentImageList.contains(u)) {
                currentImageList.add(u)
            }
        }
        hb.tvHiddenContent.text = safeFromHtml(
            cleanHiddenHtml,
            createInlineImageGetter(hb.tvHiddenContent),
            BBCodeUtil.createTagHandler(this)
        )
        setupClickableLinks(hb.tvHiddenContent)
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
        // 已登录情况下若未解锁直接呼出回复面板
        if (TextUtils.isEmpty(detail.hiddenContentHtml) || isHiddenContentLocked(detail.hiddenContentHtml)) {
            showReplyBottomSheet(currentReplyTarget)
            return
        }
        headerBinding?.layoutHiddenLockedRow?.visibility = View.GONE
        renderHiddenContent(detail.hiddenContentHtml)
    }

    /** 隐藏内容是否仍被「回复可见」门控锁住（空内容视为锁定） */
    private fun isHiddenContentLocked(hiddenHtml: String?): Boolean {
        if (TextUtils.isEmpty(hiddenHtml)) return true
        val t = hiddenHtml!!.replace(Regex("<[^>]+>"), " ")
        return t.contains("如果您要查看") || t.contains("隐藏内容请")
                || t.contains("查看本帖隐藏内容请")
                || t.contains("请回复")
                || t.contains("回复可见") || t.contains("需要回复")
    }

    /**
     * 顺序拉取后续页面，直到回复总数达到 targetCount 或无更多页。
     *
     * 注意：必须有“本页未产出新回复就立即停止”的出口。旧版只有
     * `replies.size < targetCount` 一个条件，一旦某页解析出的回复少于预期
     * （模板差异/楼层被过滤），size 不增长，循环就会一路翻到 totalPages ——
     * 变成几十上百次串行请求，这就是“有些帖子进帖特别慢”的原因。
     */
    private fun fetchRepliesUpTo(detail: PostDetail, targetCount: Int) {
        var curTotalPages = detail.totalPages
        var nextPage = detail.currentPage + 1
        var guard = 0
        while ((detail.replies?.size ?: 0) < targetCount && nextPage <= curTotalPages) {
            if (++guard > MAX_PREFETCH_PAGES) break
            var added = 0
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
                        added = nextReplies.size
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
            // 该页没有产出新回复：说明模板/解析与预期不符，继续翻页只会浪费时间
            if (added == 0) break
            nextPage++
        }
    }

    private fun maybeAutoLoadMore() {
        if (isFinishing || isDestroyed) return
        if (isLoadingMore) return
        val detail = postDetail ?: return
        val adapter = replyAdapter ?: return
        // 加载失败时不再自动重试，否则会陷入无休止的请求循环；等用户点「重试」
        if (adapter.footerState == ReplyAdapter.FooterState.RETRY) return
        if (detail.currentPage >= detail.totalPages) {
            adapter.setFooterState(ReplyAdapter.FooterState.END)
            return
        }

        val lm = binding.recyclerReplies.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        val total = adapter.itemCount
        if (total <= 0) return
        if (lastVisible >= total - AUTO_LOAD_THRESHOLD) {
            loadMoreReplies()
        } else {
            // 还没滑到接近底部：把底部状态行收起来，避免列表中间凭空多一行“加载中”
            adapter.setFooterState(null)
        }
    }

    private fun loadMoreReplies() {
        if (postDetail == null || isLoadingMore) {
            return
        }
        val curDetail = postDetail!!
        val adapter = replyAdapter
        if (curDetail.currentPage >= curDetail.totalPages) {
            adapter?.setFooterState(ReplyAdapter.FooterState.END)
            return
        }
        isLoadingMore = true
        adapter?.setFooterState(ReplyAdapter.FooterState.LOADING)
        java.lang.Thread {
            var failedMessage: String? = null
            val allNewReplies = ArrayList<ReplyItem>()
            var pagesFetched = 0
            try {
                var totalPages = curDetail.totalPages
                var page = curDetail.currentPage + 1
                // 尽量凑足一屏，但最多只连拉 LOAD_PAGE_BATCH 页：
                // 论坛每页条数不由我们决定，某些页可能只解析出几条甚至 0 条，
                // 只拉一页就会呈现“点一次没几条”的观感。
                while (page <= totalPages && pagesFetched < LOAD_PAGE_BATCH &&
                    allNewReplies.size < REPLIES_PER_PAGE
                ) {
                    pagesFetched++
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
            } catch (e: Exception) {
                failedMessage = e.message ?: "网络异常"
            }
            val err = failedMessage
            val fetched = allNewReplies
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isLoadingMore = false
                if (err != null) {
                    adapter?.setFooterState(ReplyAdapter.FooterState.RETRY)
                    Toast.makeText(this, "加载失败: $err", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (fetched.isNotEmpty()) {
                    val bl = BlacklistManager.uidSet(this)
                    if (!bl.isEmpty()) {
                        val itr = fetched.iterator()
                        while (itr.hasNext()) {
                            val r = itr.next()
                            if (r != null && r.authorUid != null && bl.contains(r.authorUid)) itr.remove()
                        }
                    }
                    val merged = ArrayList<ReplyItem>(curDetail.replies ?: ArrayList())
                    merged.addAll(fetched)
                    curDetail.replies = merged
                    displayedReplies = ArrayList(merged)
                    updateReplyFilterAndOrder()
                }
                // 拉完一屏后：没页了显示“到底了”；还有页就继续贴近底部自动加载
                if (curDetail.currentPage >= curDetail.totalPages) {
                    adapter?.setFooterState(ReplyAdapter.FooterState.END)
                } else if (fetched.isEmpty()) {
                    // 页码在推进但一条回复都没解析出来：别再自动续拉，避免空转
                    adapter?.setFooterState(ReplyAdapter.FooterState.RETRY)
                } else {
                    binding.recyclerReplies.post { maybeAutoLoadMore() }
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
        // 详情页已经能确定收藏状态时，不必再拉整个收藏列表（那是首页渲染前的串行请求）。
        if (detail.favoritedStateKnown) {
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

    // ==================== 赞过此帖的人 ====================

    /**
     * 弹出「赞过此帖的人」列表（长按底部点赞图标触发）。
     *
     * 数据优先用详情页已解析的 likeUserUids/Avatars/Names 即时渲染，
     * 再在后台线程用 LikeUserFetcher 拉一次独立接口补真实昵称与头像。
     *
     * 关闭动效：setDismissWithAnimation(true) 后，点空白处与返回键都走
     * BottomSheetBehavior 的下滑动画，两者一致。
     */
    private fun showLikeUsersSheet() {
        val detail = postDetail
        if (detail == null) {
            Toast.makeText(this, "暂无点赞数据", Toast.LENGTH_SHORT).show()
            return
        }
        val uidList = detail.likeUserUids
        if (uidList == null || uidList.isEmpty()) {
            Toast.makeText(this, "暂无点赞数据", Toast.LENGTH_SHORT).show()
            return
        }
        val avatars = detail.likeUserAvatars ?: ArrayList<String>()
        val names = detail.likeUserNames ?: ArrayList<String>()

        // 内容圆角卡片：与 App 其它底部弹层一致（顶部圆角 16dp，底部贴边）
        val card = MaterialCardView(this)
        card.setCardBackgroundColor(getColor(R.color.surface))
        card.strokeColor = getColor(R.color.divider)
        card.strokeWidth = dpToPx(1)
        card.cardElevation = 2f * dpToPx(1)
        card.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCorner(CornerFamily.ROUNDED, dpToPx(16).toFloat())
            .setTopRightCorner(CornerFamily.ROUNDED, dpToPx(16).toFloat())
            .setBottomLeftCorner(CornerFamily.ROUNDED, 0f)
            .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
            .build()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dpToPx(16)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "赞过此帖的人 (${uidList.size})"
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.text_primary))
        title.setPadding(0, 0, 0, dpToPx(12))
        root.addView(title)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(root, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        val sheet = BottomSheetDialog(this)

        val nameViews = ArrayList<TextView>()
        val imgViews = ArrayList<ShapeableImageView>()
        val rowViews = ArrayList<LinearLayout>()
        for (i in uidList.indices) {
            val uid = uidList[i]
            val name = if (i < names.size && names[i] != null && !names[i]!!.isEmpty())
                names[i]!! else "用户$uid"
            val avatar = if (i < avatars.size) avatars[i] else null
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
                openUserProfile(uid, name)
            }
            list.addView(row)
            rowViews.add(row)
            imgViews.add(iv)
            nameViews.add(tv)
        }

        sheet.setContentView(card)
        // 去掉弹层外层白底，让卡片圆角真正可见
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        // 返回键 → 与点空白处走同一条取消路径（同样的下滑退出动画）
        // 实现方式：setDismissWithAnimation(true) 让 cancel() 也走 BottomSheetBehavior
        // 滑出（原来 cancel() 走的是窗口动画：位移 20% + 淡出，与返回键的下滑不一致）。
        // 不能再靠 setOnKeyListener 抢返回键：API 33+ 的返回由 OnBackInvokedDispatcher
        // 分发，根本不经过 KEYCODE_BACK，监听器不会触发。
        sheet.setDismissWithAnimation(true)
        sheet.show()

        // 后台拉独立接口补“真实昵称+头像”（免登录、一次性返回全部点赞人）
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

    private fun sanitizeImageUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val unescaped = url.trim()
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "")
            .replace("'", "")
            .replace("\"", "")
        return normalizeImageUrl(unescaped) ?: unescaped
    }

    private fun extractAidFromUrl(url: String): String {
        val m = Pattern.compile("(?i)[?&]aid=([a-zA-Z0-9_-]+)").matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * 提取图片 URL 的核心规范化指纹，用于识别原图与缩略图、不同协议或不同参数是否指向同一张图片
     */
    private fun getImageCanonicalKey(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val clean = sanitizeImageUrl(url).lowercase(Locale.ROOT)
        val aid = extractAidFromUrl(clean)
        if (aid.isNotEmpty()) {
            return "aid:$aid"
        }
        val noQuery = clean.substringBefore("?").substringBefore("#")
        val stripped = noQuery
            .replace(".thumb.jpg", "")
            .replace(".thumb.png", "")
            .replace(".middle.jpg", "")
            .replace(".middle.png", "")
            .replace("_thumb.jpg", ".jpg")
            .replace("_thumb.png", ".png")
        val lastSlash = stripped.lastIndexOf('/')
        return if (lastSlash >= 0) stripped.substring(lastSlash + 1) else stripped
    }

    private fun isSameImage(url1: String?, url2: String?): Boolean {
        if (url1.isNullOrBlank() || url2.isNullOrBlank()) return false
        val clean1 = sanitizeImageUrl(url1)
        val clean2 = sanitizeImageUrl(url2)
        if (clean1.equals(clean2, ignoreCase = true)) return true
        val key1 = getImageCanonicalKey(clean1)
        val key2 = getImageCanonicalKey(clean2)
        return key1.isNotEmpty() && key1 == key2
    }

    private fun openImagePreview(url: String?) {
        val cleanTarget = sanitizeImageUrl(url)
        if (TextUtils.isEmpty(cleanTarget)) {
            return
        }
        val intent = Intent(this, ImagePreviewActivity::class.java)
        // 多图: 规范化全帖图片列表并去重，确保索引与 URL 精确对齐
        val rawList = ArrayList(currentImageList)
        val list = ArrayList<String>()
        for (item in rawList) {
            val s = sanitizeImageUrl(item)
            if (s.isNotEmpty() && list.none { isSameImage(it, s) }) {
                list.add(s)
            }
        }
        var targetIndex = list.indexOfFirst { isSameImage(it, cleanTarget) }
        if (targetIndex < 0) {
            list.add(cleanTarget)
            targetIndex = list.size - 1
        }
        if (list.size > 1) {
            intent.putStringArrayListExtra("image_urls", ArrayList(list))
            intent.putExtra("image_index", targetIndex)
        } else {
            intent.putExtra("image_url", cleanTarget)
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

    /** 刷新所有图片预览(底部回复栅 + 回复面板) */
    private fun refreshAllImagePreviews() {
        updateInlineImagePreview()
        if (isReplyPanelShowing()) {
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

    /** 更新底部回复面板的图片预览 */
    private fun updateDialogImagePreview() {
        val panel = binding.containerReplyPanel
        val hsv = panel.findViewById<android.widget.HorizontalScrollView>(R.id.hsv_image_preview)
        val ll = panel.findViewById<LinearLayout>(R.id.ll_image_preview)
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

    /** 内嵌图片 getter：表情小图固定/正文插图自适应屏幕宽度 + Glide 异步回填 */
    private fun createInlineImageGetter(textView: TextView): Html.ImageGetter {
        return Html.ImageGetter { source ->
            val imgUrl = sanitizeImageUrl(source)
            val tv = textView
            val isSmiley = isSmileyOrIcon(imgUrl)
            if (isSmiley) {
                val emojiSize = dpToPx(24)
                val placeholder = UrlDrawable(tv, emojiSize)
                placeholder.setBounds(0, 0, emojiSize, emojiSize)
                Glide.with(this)
                    .load(imgUrl)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<Drawable?>() {
                        override fun onResourceReady(
                            resource: Drawable,
                            transition: com.bumptech.glide.request.transition.Transition<in Drawable?>?
                        ) {
                            resource.setBounds(0, 0, emojiSize, emojiSize)
                            placeholder.setBounds(0, 0, emojiSize, emojiSize)
                            placeholder.setReal(resource, tv)
                        }

                        override fun onLoadCleared(ph: Drawable?) {}
                    })
                placeholder
            } else {
                // 正文配图：原实现按整屏可用宽度渲染，原图视觉过大；这里收窄到正文宽度的 90%
                // 且不超过屏幕宽度的 78%，并限制最大高宽比，避免竖长图占满多屏。
                val textWidth = if (tv.width > 0) tv.width - tv.paddingLeft - tv.paddingRight
                else resources.displayMetrics.widthPixels
                val maxW = maxOf(
                    dpToPx(160),
                    minOf((textWidth * 0.9f).toInt(), (resources.displayMetrics.widthPixels * 0.78f).toInt())
                )
                val maxH = (maxW * 1.25f).toInt()
                val radius = dpToPx(10).toFloat()
                val placeholder = UrlDrawable(tv, maxW)
                placeholder.setBounds(0, 0, maxW, dpToPx(120))

                Glide.with(this)
                    .load(imgUrl)
                    .into(object : com.bumptech.glide.request.target.CustomTarget<Drawable?>() {
                        override fun onResourceReady(
                            resource: Drawable,
                            transition: com.bumptech.glide.request.transition.Transition<in Drawable?>?
                        ) {
                            val srcW = resource.intrinsicWidth
                            val srcH = resource.intrinsicHeight
                            var finalW: Int
                            var finalH: Int
                            if (srcW > 0 && srcH > 0) {
                                if (srcW >= maxW) {
                                    finalW = maxW
                                    finalH = (srcH.toLong() * maxW / srcW).toInt()
                                } else if (srcW < dpToPx(100)) {
                                    finalW = srcW
                                    finalH = srcH
                                } else {
                                    finalW = maxW
                                    finalH = (srcH.toLong() * maxW / srcW).toInt()
                                }
                                // 竖长图限高
                                if (finalH > maxH) {
                                    finalH = maxH
                                    finalW = (srcW.toLong() * maxH / srcH).toInt().coerceAtMost(maxW)
                                }
                            } else {
                                finalW = maxW
                                finalH = maxH
                            }
                            val strokeWidth = dpToPx(1).toFloat()
                            val strokeColor = getColor(R.color.image_placeholder_stroke)
                            val rounded = RoundedImageDrawable(resource, radius, strokeWidth, strokeColor)
                            rounded.setBounds(0, 0, finalW, finalH)
                            placeholder.setBounds(0, 0, finalW, finalH)
                            placeholder.setReal(rounded, tv)
                        }

                        override fun onLoadCleared(ph: Drawable?) {}
                    })
                placeholder
            }
        }
    }

    /** 安全解析 HTML,捕获 SpannableStringBuilder 的 PARAGRAPH 边界崩溃，并移除彩色字体统一为系统文本色 */
    private fun safeFromHtml(html: String?, imageGetter: Html.ImageGetter, tagHandler: Html.TagHandler?): Spanned {
        if (TextUtils.isEmpty(html)) {
            return SpannedStringValueOf("")
        }
        val clean = BBCodeUtil.stripHtmlColors(html)
        val rawSpanned: Spanned = try {
            Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler)
        } catch (e: Exception) {
            android.util.Log.w("ThreadDetail", "Html.fromHtml failed, retrying with COMPACT mode", e)
            try {
                Html.fromHtml(clean, Html.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler)
            } catch (e2: Exception) {
                android.util.Log.w("ThreadDetail", "Html.fromHtml COMPACT also failed, stripping paragraph tags", e2)
                // 移除可能导致段落边界问题的标签
                var stripped = Regex("<div[^>]*>").replace(clean, "")
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
                    Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler)
                } catch (e3: Exception) {
                    android.util.Log.e("ThreadDetail", "All Html.fromHtml attempts failed", e3)
                    SpannedStringValueOf(Html.fromHtml(TextUtils.htmlEncode(clean)).toString())
                }
            }
        }
        val processed = BBCodeUtil.stripForegroundColorSpans(rawSpanned)
        val finalSpanned = (processed as? Spanned) ?: rawSpanned
        return trimSpanned(finalSpanned)
    }

    private fun trimSpanned(spanned: CharSequence): Spanned {
        var start = 0
        var end = spanned.length
        while (start < end && (spanned[start].isWhitespace() || spanned[start] == '\u00A0')) {
            start++
        }
        while (end > start && (spanned[end - 1].isWhitespace() || spanned[end - 1] == '\u00A0')) {
            end--
        }
        val sub = if (start == 0 && end == spanned.length) {
            spanned
        } else {
            spanned.subSequence(start, end)
        }
        return if (sub is Spanned) sub else SpannedStringValueOf(sub.toString())
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
        val linkColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        val urlPattern = Pattern.compile(
            "(?<!\\w)(?:https?://[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+|www\\.[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+)(?<![,.;:!?)>])",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        )
        FixNestedScrollLinkMovementMethod.matcherLinkify(
            spannable, urlPattern,
            { url -> canonicalizeUrl(url) ?: url },
            { url -> openLink(url) },
            linkColor
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
        // 为正文大图增加点击全屏大图预览手势
        val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
        for (imgSpan in imageSpans) {
            val src = imgSpan.source
            if (!TextUtils.isEmpty(src) && !isSmileyOrIcon(src!!)) {
                val start = spannable.getSpanStart(imgSpan)
                val end = spannable.getSpanEnd(imgSpan)
                if (start >= 0 && end > start) {
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openImagePreview(src)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = false
                        }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
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

    /**
     * 按给定属性名顺序取第一个非空值（与 ForumParser.firstNonEmptyAttr 同语义）。
     * 用于统一图片真实地址的取值优先级，避免列表/详情两套口径不一致。
     */
    private fun firstNonEmptyAttr(element: Element, vararg names: String): String? {
        for (name in names) {
            if (element.hasAttr(name)) {
                val value = element.attr(name)
                if (!TextUtils.isEmpty(value)) return value.trim()
            }
        }
        return null
    }

    private fun isSmileyOrIcon(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("smiley") || lower.contains("emoticon")
                || lower.contains("face") || lower.contains("/static/image/smiley")
                || lower.contains("stamp") || lower.contains("magic")
                || lower.contains("mini") || lower.contains("icon")
                || lower.contains("common_")
    }

    private fun extractAndSeparateImages(html: String?, imageUrls: MutableList<String>): String {
        if (TextUtils.isEmpty(html)) {
            return ""
        }
        try {
            val doc = Jsoup.parseBodyFragment(html!!)
            doc.select("script").remove()
            doc.select("style").remove()
            // 展开附件图片外壳 <ignore_js_op>，保留内部的 img 节点
            for (ignoreOp in doc.select("ignore_js_op")) {
                ignoreOp.unwrap()
            }
            val imgs = doc.select("img")
            for (img in imgs) {
                val realUrl: String? = firstNonEmptyAttr(
                    img,
                    "zoomfile", "file", "comiis_loadimages", "data-original", "data-src",
                    "data-file", "data-lazy-src", "src"
                )
                if (!TextUtils.isEmpty(realUrl)) {
                    val fullUrl = normalizeImageUrl(realUrl)
                    if (fullUrl != null && !fullUrl.contains("none.gif") && !fullUrl.contains("blank.gif")) {
                        if (!isSmileyOrIcon(fullUrl) && !imageUrls.contains(fullUrl)) {
                            imageUrls.add(fullUrl)
                        }
                        // 彻底纯化 img 属性：清空全部多余参数，仅保留标准 src，杜绝 smilieid/border/alt 等参数外露
                        val attrKeys = ArrayList<String>()
                        for (a in img.attributes()) {
                            attrKeys.add(a.key)
                        }
                        for (k in attrKeys) {
                            img.removeAttr(k)
                        }
                        img.attr("src", fullUrl)
                        continue
                    }
                }
                // 无效或占位图安全移除
                img.remove()
            }
            val cleanedText = doc.body().html()
            var out = Regex("(?i)replyreload\\s*\\+?\\s*='[^']*'").replace(cleanedText, "")
            out = Regex("(?i)replyreload\\s*\\+?\\s*=\"[^\"]*\"").replace(out, "")
            out = Regex("(?i)replyreload\\s*\\+?\\s*=[^;\\s<]+").replace(out, "")
            return out
        } catch (e: Exception) {
            return fallbackExtractImages(html!!, imageUrls)
        }
    }

    private fun fallbackExtractImages(html: String, imageUrls: MutableList<String>): String {
        var cleaned = Regex("(?i)<script[^>]*>.*?</script>").replace(html, "")
        cleaned = Regex("(?i)<style[^>]*>.*?</style>").replace(cleaned, "")
        // 匹配完整整个 <img ...> 标签，防止漏掉结尾属性导致参数外露
        val imgPattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE)
        val attrPattern = Pattern.compile(
            "(?:zoomfile|file|comiis_loadimages|data-original|data-src|data-file|data-lazy-src|src)\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = imgPattern.matcher(cleaned)
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
                if (!isSmileyOrIcon(chosenUrl) && !imageUrls.contains(chosenUrl)) {
                    imageUrls.add(chosenUrl)
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement("<img src=\"$chosenUrl\">"))
            } else {
                matcher.appendReplacement(sb, "")
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
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
        hideReplyPanel()
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

        // 插入面板的内容类型
        private const val INSERT_LINK = 1
        private const val INSERT_IMAGE = 2
        private const val INSERT_AUDIO = 3
        private const val INSERT_VIDEO = 4
        private const val INSERT_FLASH = 5
        private const val INSERT_QUOTE = 6
        private const val INSERT_CODE = 7
        private const val INSERT_FREE = 8
        private const val INSERT_HIDE = 9

        /** 进帖预取回复的最大页数，防止解析异常时无界翻页（串行请求会拖死首屏）。 */
        private const val MAX_PREFETCH_PAGES = 3

        /** 自动加载时，距列表底部还剩多少行就提前开始拉下一页。 */
        private const val AUTO_LOAD_THRESHOLD = 5

        /** 自动加载单次最多连拉的页数，避免尾页异常时一次请求过多。 */
        private const val LOAD_PAGE_BATCH = 3

        /** 目标每页回复数；论坛实际每页条数不止由我们决定，故连拉多页凑一屏。 */
        private const val REPLIES_PER_PAGE = 20
    }

    // ==================== 表格分段与原生 TableLayout 渲染 ====================

    private fun stripPostRedundantElements(rawHtml: String): String {
        if (rawHtml.isEmpty()) return ""
        try {
            val doc = Jsoup.parseBodyFragment(rawHtml)
            doc.select(
                "div.comiis_rate, div[class*=comiis_rate], " +
                "div.comiis_praise, div[class*=praise], " +
                "ul.comiis_recommend_list_a, ul.comiis_recommend_list_t, ul[class*=recommend_list], " +
                "em.comiis_recommend_num, a.comiis_recommend_addkey, " +
                "div.comiis_favshare, div[class*=favshare], a.followmod, " +
                "div.comiis_postli_time, div.manage, div.modact"
            ).remove()
            return stripLeadingHtmlBreak(doc.body().html())
        } catch (_: Exception) {
            return stripLeadingHtmlBreak(rawHtml)
        }
    }

    private fun groupContinuousImages(html: String): String {
        if (TextUtils.isEmpty(html)) return ""
        val imgPattern = Pattern.compile("(?i)<img\\b[^>]*src=[\"']([^\"']+)[\"'][^>]*>")
        val m = imgPattern.matcher(html)
        class ImgMatch(val src: String, val start: Int, val end: Int)
        val matches = ArrayList<ImgMatch>()
        while (m.find()) {
            val raw = m.group(1) ?: continue
            val src = sanitizeImageUrl(raw)
            if (src.isNotEmpty() && !isSmileyOrIcon(src)) {
                matches.add(ImgMatch(src, m.start(), m.end()))
            }
        }
        if (matches.size < 2) return html

        fun isContinuous(between: String): Boolean {
            if (Pattern.compile("(?i)<(?:table|customquote|blockquote|pre|hr|a\\b)").matcher(between).find()) {
                return false
            }
            if (Pattern.compile("(?i)<img\\b").matcher(between).find()) {
                return false
            }
            val stripped = between.replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", "")
                .replace("&#160;", "")
                .replace("&ensp;", "")
                .replace("&emsp;", "")
                .trim()
            return stripped.isEmpty()
        }

        val groups = ArrayList<List<ImgMatch>>()
        var currentGroup = ArrayList<ImgMatch>()
        currentGroup.add(matches[0])

        for (i in 1 until matches.size) {
            val prev = currentGroup.last()
            val curr = matches[i]
            val between = html.substring(prev.end, curr.start)
            if (isContinuous(between)) {
                currentGroup.add(curr)
            } else {
                if (currentGroup.size >= 2) {
                    groups.add(currentGroup)
                }
                currentGroup = ArrayList()
                currentGroup.add(curr)
            }
        }
        if (currentGroup.size >= 2) {
            groups.add(currentGroup)
        }
        if (groups.isEmpty()) return html

        var result = html
        for (i in groups.indices.reversed()) {
            val group = groups[i]
            val urls = group.map { it.src }
            val joined = urls.joinToString("|")
            val replacement = "<div class=\"continuous-image-gallery\" data-images=\"$joined\"></div>"
            val start = group.first().start
            val end = group.last().end
            result = result.substring(0, start) + replacement + result.substring(end)
        }
        return result
    }

    private fun parseGalleryUrls(galleryHtml: String): List<String> {
        val m = Pattern.compile("(?i)data-images=[\"']([^\"']+)[\"']").matcher(galleryHtml)
        if (m.find()) {
            val raw = m.group(1) ?: ""
            return raw.split("|").map { sanitizeImageUrl(it) }.filter { it.isNotBlank() }
        }
        return emptyList()
    }

    private fun createGridImageGallery(imageUrls: List<String>): View {
        val count = imageUrls.size
        // 确定网格列数：2张或4张时排成优雅对称的2列，其余（3张、5~9张及以上）排成3列九宫格
        val spanCount = if (count == 2 || count == 4) 2 else 3

        val displayMetrics = resources.displayMetrics
        // 正文容器横向可用宽度（屏幕宽减去左右内边距约 32dp）
        val screenW = displayMetrics.widthPixels
        val horizontalPadding = dpToPx(32)
        val gap = dpToPx(6)
        val availableW = maxOf(dpToPx(240), screenW - horizontalPadding)

        val cellWidth: Int
        val cellHeight: Int
        if (spanCount == 2) {
            // 双列网格：两张并排，单张宽度约为 (可用宽度 - 间距) / 2
            cellWidth = (availableW - gap) / 2
            // 高度设计为 4:3 或 1:1，约 130~150dp，视觉极其紧凑舒服
            cellHeight = (cellWidth * 0.85f).toInt()
        } else {
            // 三列网格：标准九宫格，单张正方形
            cellWidth = (availableW - gap * 2) / 3
            cellHeight = cellWidth
        }

        // 最多展示前 9 张（若大于 9 张，第 9 张覆盖 +N 蒙层）
        val displayList = if (count > 9) imageUrls.take(9) else imageUrls
        val overflowCount = count - 9

        val rv = RecyclerView(this).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(14)
            }
            layoutParams = lp
            layoutManager = GridLayoutManager(this@ThreadDetailActivity, spanCount)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
        }

        class GridViewHolder(
            val container: FrameLayout,
            val iv: ImageView,
            val tvMore: TextView
        ) : RecyclerView.ViewHolder(container)

        val adapter = object : RecyclerView.Adapter<GridViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
                val frame = FrameLayout(parent.context).apply {
                    val lp = RecyclerView.LayoutParams(cellWidth, cellHeight).apply {
                        val margin = gap / 2
                        setMargins(margin, margin, margin, margin)
                    }
                    layoutParams = lp
                    setBackgroundResource(R.drawable.bg_post_image_rounded)
                    foreground = androidx.core.content.ContextCompat.getDrawable(parent.context, R.drawable.bg_post_image_border_only)
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    isClickable = true
                    isFocusable = true
                }

                val iv = ImageView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                frame.addView(iv)

                val tvMore = TextView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    gravity = Gravity.CENTER
                    setBackgroundColor(0x77000000.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    visibility = View.GONE
                }
                frame.addView(tvMore)

                return GridViewHolder(frame, iv, tvMore)
            }

            override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
                val url = displayList[position]
                Glide.with(this@ThreadDetailActivity)
                    .load(url)
                    .placeholder(ColorDrawable(getColor(R.color.background_secondary)))
                    .error(ColorDrawable(getColor(R.color.divider)))
                    .into(holder.iv)

                if (overflowCount > 0 && position == 8) {
                    holder.tvMore.visibility = View.VISIBLE
                    holder.tvMore.text = "+$overflowCount"
                } else {
                    holder.tvMore.visibility = View.GONE
                }

                holder.container.setOnClickListener {
                    openImagePreview(url)
                }
            }

            override fun getItemCount(): Int = displayList.size
        }

        rv.adapter = adapter
        return rv
    }

    private fun renderContentSections(displayHtml: String, hiddenNotice: String?) {
        val hb = headerBinding ?: return
        val container = hb.llContentContainer
        val cleanedHtml = stripPostRedundantElements(displayHtml)
        // 关键：将连续 2 个以上的换行或空段落压缩，彻底根除正文大面积空白
        val collapsedHtml = Regex("(?i)(?:<br\\s*/?>\\s*){2,}").replace(cleanedHtml, "<br>")
            .replace(Regex("(?i)<p\\s*>\\s*(?:&nbsp;|&#160;|\\s)*</p>"), "")
            .replace(Regex("(?i)(?:\\r?\\n\\s*){3,}"), "\n\n")
        val processedHtml = groupContinuousImages(collapsedHtml)

        val hasTable = processedHtml.contains("<table", ignoreCase = true)
        val hasGallery = processedHtml.contains("continuous-image-gallery", ignoreCase = true)
        val hasHiddenCard = processedHtml.contains("unlocked-hidden-card", ignoreCase = true)

        if (!hasTable && !hasGallery && !hasHiddenCard) {
            // 无表格、无画廊、无解锁隐藏卡片：保留原单个 tvContent
            for (i in container.childCount - 1 downTo 0) {
                val child = container.getChildAt(i)
                if (child !== hb.tvContent) container.removeViewAt(i)
            }
            hb.tvContent.visibility = View.VISIBLE
            hb.tvContent.text = safeFromHtml(
                processedHtml,
                createInlineImageGetter(hb.tvContent),
                BBCodeUtil.createTagHandler(this)
            )
            if (!TextUtils.isEmpty(hiddenNotice)) {
                applyHiddenNoticeHighlight(hb.tvContent.text, hiddenNotice)
            }
            setupClickableLinks(hb.tvContent)
            return
        }

        // 包含表格、连续图片组或专属解锁卡片：分段渲染文本与专属原生成分
        // 关键：保留 hb.tvContent 在视图树内（仅设为 GONE），绝不从父容器移除，杜绝 ViewBinding 抛出 Missing required view 崩溃
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child !== hb.tvContent) container.removeViewAt(i)
        }
        hb.tvContent.visibility = View.GONE

        val p = Pattern.compile("(?is)(<table\\b.*?</table\\s*>|<div\\s+class=[\"']continuous-image-gallery[\"'][^>]*>.*?</div>|<div\\s+class=[\"']unlocked-hidden-card[\"'][^>]*>.*?</div>)")
        val m = p.matcher(processedHtml)
        var lastIdx = 0

        fun addTextChunk(htmlChunk: String) {
            val trimmed = htmlChunk.trim()
            if (trimmed.isEmpty()) return
            val tv = TextView(this)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.textSize = 15f
            tv.setTextColor(getColor(R.color.text_primary))
            tv.setLineSpacing(dpToPx(6).toFloat(), 1.0f)
            val spanned = safeFromHtml(
                trimmed,
                createInlineImageGetter(tv),
                BBCodeUtil.createTagHandler(this)
            )
            if (spanned.toString().trim().isEmpty()) {
                return
            }
            tv.text = spanned
            if (!TextUtils.isEmpty(hiddenNotice)) {
                applyHiddenNoticeHighlight(tv.text, hiddenNotice)
            }
            setupClickableLinks(tv)
            container.addView(tv)
        }

        while (m.find()) {
            val textBefore = processedHtml.substring(lastIdx, m.start())
            addTextChunk(textBefore)

            val matchedBlock = m.group(1) ?: ""
            if (matchedBlock.startsWith("<table", ignoreCase = true)) {
                val tableCard = createTableLayoutView(matchedBlock)
                if (tableCard != null) {
                    container.addView(tableCard)
                }
            } else if (matchedBlock.contains("continuous-image-gallery", ignoreCase = true)) {
                val urls = parseGalleryUrls(matchedBlock)
                if (urls.isNotEmpty()) {
                    val galleryCard = createGridImageGallery(urls)
                    container.addView(galleryCard)
                }
            } else if (matchedBlock.contains("unlocked-hidden-card", ignoreCase = true)) {
                val inner = matchedBlock.replace(Regex("(?is)^<div[^>]*>"), "").replace(Regex("(?is)</div>$"), "")
                val card = createUnlockedHiddenCard(inner)
                container.addView(card)
            }

            lastIdx = m.end()
        }

        val textAfter = processedHtml.substring(lastIdx)
        addTextChunk(textAfter)
    }

    private fun createUnlockedHiddenCard(innerHtml: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val bgColor = if (isDark) 0x1AF59E0B.toInt() else 0x14F59E0B.toInt()
            val strokeColor = if (isDark) 0x59F59E0B.toInt() else 0x66F59E0B.toInt()
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(10).toFloat()
                setStroke(dpToPx(1), strokeColor)
            }
            background = gd
            val pad = dpToPx(12)
            setPadding(pad, pad, pad, pad)
        }

        // 顶栏：左侧标题「🔓 本帖隐藏的内容」，右侧「复制」胶囊按钮
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tvTitle = TextView(this).apply {
            text = "🔓 本帖隐藏的内容"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFD97706.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvTitle)

        // 纯文本内容提取
        val cleanContent = innerHtml.replace(Regex("(?i)^.*?本帖隐藏的内容[:：]?\\s*"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

        val btnCopy = TextView(this).apply {
            text = "复制"
            textSize = 11.5f
            setTextColor(0xFFD97706.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isDark) 0x33F59E0B.toInt() else 0x24F59E0B.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            background = btnBg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cleanContent.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("隐藏内容", cleanContent))
                    Toast.makeText(this@ThreadDetailActivity, "已复制隐藏内容", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topRow.addView(btnCopy)
        card.addView(topRow)

        // 内容展示区
        val tvBody = TextView(this).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            layoutParams = lp
            textSize = 14.5f
            setTextColor(getColor(R.color.text_primary))
            setLineSpacing(dpToPx(5).toFloat(), 1.0f)
            val bodyHtml = innerHtml.replace(Regex("(?i)^.*?本帖隐藏的内容[:：]?\\s*"), "")
            text = safeFromHtml(
                bodyHtml,
                createInlineImageGetter(this),
                BBCodeUtil.createTagHandler(this@ThreadDetailActivity)
            )
            setupClickableLinks(this)
        }
        card.addView(tvBody)
        return card
    }

    private fun createTableLayoutView(tableHtml: String): View? {
        try {
            val doc = Jsoup.parseBodyFragment(tableHtml)
            val tableEl = doc.selectFirst("table") ?: return null
            val rows = tableEl.select("tr")
            if (rows.isEmpty()) return null

            var maxCols = 0
            for (r in rows) {
                val cols = r.select("th, td").size
                if (cols > maxCols) maxCols = cols
            }
            if (maxCols == 0) return null

            val card = FrameLayout(this)
            val cardLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            cardLp.setMargins(0, dpToPx(10), 0, dpToPx(10))
            card.layoutParams = cardLp
            card.setBackgroundResource(R.drawable.bg_table_border)
            card.outlineProvider = ViewOutlineProvider.BACKGROUND
            card.clipToOutline = true

            val dividerColor = getColor(R.color.divider)
            val headerBgColor = getColor(R.color.code_block_bg)
            val textColor = getColor(R.color.text_primary)

            val isTwoCols = maxCols == 2
            if (isTwoCols) {
                // 两列表格：采用垂直 LinearLayout + 每一行水平 LinearLayout 动态测量排版
                // 彻底解决 Android 原生 TableLayout 在 shrink 时截断多行文本的系统缺陷
                // 保证左侧表头严格垂直居中且背景平铺整行，右侧表单内容 100% 完整自适应展开不被截断
                val rootLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // 计算第0列（表头列）最大需要宽度
                val testPaint = android.text.TextPaint().apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13.5f, resources.displayMetrics)
                    typeface = Typeface.DEFAULT_BOLD
                }
                var maxKeyTextWidth = 0f
                for (r in rows) {
                    val firstCell = r.selectFirst("th, td")
                    if (firstCell != null) {
                        val lines = firstCell.text().split("\n", "/")
                        for (line in lines) {
                            val w = testPaint.measureText(line.trim())
                            if (w > maxKeyTextWidth) maxKeyTextWidth = w
                        }
                    }
                }
                val minKeyWidth = dpToPx(72)
                val maxKeyWidth = dpToPx(130)
                val keyColWidth = (maxKeyTextWidth + dpToPx(24)).toInt().coerceIn(minKeyWidth, maxKeyWidth)

                for ((rIdx, r) in rows.withIndex()) {
                    val cells = r.select("th, td")
                    if (cells.isEmpty()) continue

                    val isHeaderRow = rIdx == 0 || cells.first()?.tagName()?.equals("th", ignoreCase = true) == true
                    val rowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        if (isHeaderRow) {
                            setBackgroundColor(headerBgColor)
                        }
                    }

                    // 第0列（表头单元格，高度 MATCH_PARENT 保证垂直居中与背景完全铺满）
                    val cell0 = cells.getOrNull(0)
                    val tv0 = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(keyColWidth, ViewGroup.LayoutParams.MATCH_PARENT)
                        setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                        gravity = if (isHeaderRow) Gravity.CENTER else (Gravity.CENTER_VERTICAL or Gravity.START)
                        setTypeface(null, Typeface.BOLD)
                        textSize = if (isHeaderRow) 14f else 13.5f
                        setTextColor(textColor)
                        if (!isHeaderRow) setBackgroundColor(headerBgColor)
                        val rawText = cell0?.html()?.trim() ?: ""
                        text = safeFromHtml(rawText, createInlineImageGetter(this), null)
                    }
                    rowLayout.addView(tv0)

                    // 列间纵向细分割线
                    val vDiv = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(1), ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(dividerColor)
                    }
                    rowLayout.addView(vDiv)

                    // 第1列（表单内容单元格，weight=1 且 height=WRAP_CONTENT，多行完整展示绝对不被裁切）
                    val cell1 = cells.getOrNull(1)
                    val tv1 = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                        setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                        gravity = if (isHeaderRow) Gravity.CENTER else (Gravity.CENTER_VERTICAL or Gravity.START)
                        textSize = if (isHeaderRow) 14f else 13.5f
                        if (isHeaderRow) setTypeface(null, Typeface.BOLD)
                        setTextColor(textColor)
                        setLineSpacing(dpToPx(2).toFloat(), 1.0f)
                        val rawText = cell1?.html()?.trim() ?: ""
                        text = safeFromHtml(rawText, createInlineImageGetter(this), null)
                    }
                    rowLayout.addView(tv1)

                    rootLayout.addView(rowLayout)

                    if (rIdx < rows.size - 1) {
                        val hDiv = View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1))
                            setBackgroundColor(dividerColor)
                        }
                        rootLayout.addView(hDiv)
                    }
                }

                card.addView(rootLayout)
                return card
            }

            // 多列表格（>2列）：支持横向平滑滚动
            val tableLayout = TableLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isStretchAllColumns = false
            }

            for ((rIdx, r) in rows.withIndex()) {
                val cells = r.select("th, td")
                if (cells.isEmpty()) continue

                val isHeaderRow = rIdx == 0 || cells.first()?.tagName()?.equals("th", ignoreCase = true) == true
                val tr = TableRow(this).apply {
                    layoutParams = TableLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (isHeaderRow) setBackgroundColor(headerBgColor)
                }

                for (cell in cells) {
                    val cellTv = TextView(this).apply {
                        layoutParams = TableRow.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        textSize = 13.5f
                        if (isHeaderRow) setTypeface(null, Typeface.BOLD)
                        setTextColor(textColor)
                        setLineSpacing(dpToPx(2).toFloat(), 1.0f)
                        text = safeFromHtml(cell.html().trim(), createInlineImageGetter(this), null)
                    }
                    tr.addView(cellTv)
                }
                tableLayout.addView(tr)

                if (rIdx < rows.size - 1) {
                    val div = View(this).apply {
                        layoutParams = TableLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dpToPx(1)
                        )
                        setBackgroundColor(dividerColor)
                    }
                    tableLayout.addView(div)
                }
            }

            val hsv = HorizontalScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isFillViewport = true
                addView(tableLayout)
            }
            card.addView(hsv)
            return card
        } catch (e: Exception) {
            return null
        }
    }
}
