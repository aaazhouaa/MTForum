package com.solosu.mtforum.ui.post

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R
import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.DraftManager
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import com.solosu.mtforum.ui.widget.DialogHelper

/**
 * 发帖页面 Activity
 * 完全对接网页端功能：表情、@朋友、插入、附件、高级
 */
class PostActivity : AppCompatActivity() {

    // views
    private lateinit var tvCancel: TextView
    private lateinit var etTitle: TextInputEditText
    private lateinit var tvTitleCount: TextView
    private lateinit var llCircleSelector: LinearLayout
    private lateinit var tvCircleLabel: TextView
    private lateinit var tvSelectedForum: TextView
    private lateinit var etContent: TextInputEditText
    private lateinit var cbAnonymous: CheckBox
    private lateinit var btnPublish: MaterialButton

    // 五大功能按钮 + 图片按钮
    private lateinit var btnSmiley: View
    private lateinit var btnAt: View
    private lateinit var btnInsert: View
    private lateinit var btnImage: View
    private lateinit var btnAttach: View
    private lateinit var btnAdvanced: View
    private lateinit var llSmileyPanel: LinearLayout
    private lateinit var llAtPanel: LinearLayout
    private lateinit var etAtUsername: TextInputEditText
    private lateinit var btnAtInsert: View
    private lateinit var llAdvancedOptions: LinearLayout
    private lateinit var llAttachList: LinearLayout
    private lateinit var llImagePreview: LinearLayout
    private lateinit var llInsertPanel: LinearLayout
    private lateinit var llInsertInput: LinearLayout

    // data
    private var selectedFid: String? = null
    private var selectedForumName: String? = null
    private var currentFormhash: String? = null
    private var currentUid: String? = null
    private var currentHash: String? = null

    /** 论坛真实表情集（从编辑器页解析后缓存） */
    private var smileyCatalog: MutableList<ForumParser.SmileySet>? = null
    private var postSmileySetIndex = 0
    private var draftId = 0L
    private var postedDone = false

    // build73: 编辑模式(本人帖)上下文
    private var editTid: String? = null
    private var editPid: String? = null

    // 附件列表
    private val attachFiles = ArrayList<AttachFile>()

    private class AttachFile(name: String?, path: String?) {
        @JvmField
        var name: String? = name
        @JvmField
        var path: String? = path
        @JvmField
        var url: String? = null
        @JvmField
        var bbcode: String? = null
        @JvmField
        var aid: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.post_activity)
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.background)

        initViews()
        setupTitleCounter()
        setupCircleSelector()
        setupToolbarButtons()
        setupPublishButton()
        // build73: 编辑模式优先于草稿恢复
        loadFormhashAndUserInfo()
        setupEditMode()
        if (!isEditMode()) {
            restoreDraft()
        }
    }

    private fun initViews() {
        tvCancel = findViewById(R.id.tv_cancel)
        tvCancel.setOnClickListener { finish() }
        val tvDrafts = findViewById<View>(R.id.tv_drafts)
        if (tvDrafts != null) tvDrafts.setOnClickListener { showDraftsDialog() }

        etTitle = findViewById(R.id.et_title)
        tvTitleCount = findViewById(R.id.tv_title_count)
        llCircleSelector = findViewById(R.id.ll_circle_selector)
        tvCircleLabel = findViewById(R.id.tv_circle_label)
        tvSelectedForum = findViewById(R.id.tv_selected_forum)
        etContent = findViewById(R.id.et_content)
        cbAnonymous = findViewById(R.id.cb_anonymous)
        btnPublish = findViewById(R.id.btn_publish)

        // 五大功能按钮
        btnSmiley = findViewById(R.id.btn_smiley)
        btnAt = findViewById(R.id.btn_at)
        btnInsert = findViewById(R.id.btn_insert)
        btnImage = findViewById(R.id.btn_image)
        btnAttach = findViewById(R.id.btn_attach)
        btnAdvanced = findViewById(R.id.btn_advanced)

        // 功能面板
        llSmileyPanel = findViewById(R.id.ll_smiley_panel)
        llAtPanel = findViewById(R.id.ll_at_panel)
        etAtUsername = findViewById(R.id.et_at_username)
        btnAtInsert = findViewById(R.id.btn_at_insert)
        llAdvancedOptions = findViewById(R.id.ll_advanced_options)
        llAttachList = findViewById(R.id.ll_attach_list)
        llImagePreview = findViewById(R.id.ll_image_preview)
        llInsertPanel = findViewById(R.id.ll_insert_panel)
        llInsertInput = findViewById(R.id.ll_insert_input)
    }

    private fun setupTitleCounter() {
        etTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val len = if (s != null) s.length else 0
                tvTitleCount.text = "$len/80"
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupCircleSelector() {
        llCircleSelector.setOnClickListener { showForumPicker() }
    }

    // ==================== 五大功能 ====================

    private fun setupToolbarButtons() {
        // 1. 图片上传
        btnImage.setOnClickListener {
            hideAllPanels()
            pickImage()
        }

        // 2. @朋友
        btnAt.setOnClickListener {
            val visible = llAtPanel.visibility == View.VISIBLE
            hideAllPanels()
            llAtPanel.visibility = if (visible) View.GONE else View.VISIBLE
            if (!visible) {
                etAtUsername.requestFocus()
            }
        }
        btnAtInsert.setOnClickListener {
            val name = if (etAtUsername.text != null)
                etAtUsername.text.toString().trim() else ""
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            insertIntoContent("@$name ")
            llAtPanel.visibility = View.GONE
            etAtUsername.setText("")
            Toast.makeText(this, "已插入 @$name", Toast.LENGTH_SHORT).show()
        }

        // 3. 插入代码/引用/隐藏
        btnInsert.setOnClickListener {
            toggleInsertPanel()
        }

        // 4. 表情
        btnSmiley.setOnClickListener { toggleSmileyPanel() }

        // 5. 文件附件
        btnAttach.setOnClickListener {
            hideAllPanels()
            pickFile()
        }

        // 6. 高级设置
        btnAdvanced.setOnClickListener {
            val visible = llAdvancedOptions.visibility == View.VISIBLE
            hideAllPanels()
            llAdvancedOptions.visibility = if (visible) View.GONE else View.VISIBLE
        }

        // 点击正文时自动收起快捷面板
        etContent.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) hideAllPanels()
        }
        etContent.setOnClickListener { hideAllPanels() }
    }

    // ---- 表情功能 ----

    /** 渲染论坛真实表情（当前分类 + 底部分类切换），点击插入对应表情代码 */
    private fun renderPostSmileySet(catalog: MutableList<ForumParser.SmileySet>) {
        val container = findViewById<LinearLayout>(R.id.ll_smiley_container) ?: return
        val tabs = findViewById<LinearLayout>(R.id.ll_smiley_tabs)
        container.removeAllViews()
        val idx = postSmileySetIndex.coerceIn(0, catalog.size - 1)
        postSmileySetIndex = idx
        val density = resources.displayMetrics.density
        val size = (40 * density).toInt()
        val padding = (4 * density).toInt()
        for (smiley in catalog[idx].items) {
            val iv = ImageView(this)
            iv.layoutParams = LinearLayout.LayoutParams(size, size)
            iv.setPadding(padding, padding, padding, padding)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setBackgroundResource(android.R.drawable.list_selector_background)
            iv.isClickable = true
            iv.isFocusable = true
            Glide.with(this).load(smiley.url).into(iv)
            iv.setOnClickListener { insertIntoContent(smiley.code) }
            container.addView(iv)
        }
        tabs?.removeAllViews()
        if (catalog.size <= 1 || tabs == null) return
        for (i in catalog.indices) {
            val tv = TextView(this)
            tv.text = if (catalog[i].name.isEmpty()) "表情" + (i + 1) else catalog[i].name
            tv.textSize = 12f
            tv.setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            tv.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (i == idx) R.color.text_primary else R.color.text_secondary
                )
            )
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener {
                postSmileySetIndex = i
                renderPostSmileySet(catalog)
            }
            tabs.addView(tv)
        }
    }

    private fun toggleSmileyPanel() {
        // 自绘制快速回复图标,替代 emoji 表情
        hideAllPanelsExcept(llSmileyPanel)
        if (llSmileyPanel.visibility == View.VISIBLE) {
            llSmileyPanel.visibility = View.GONE
            return
        }
        llSmileyPanel.visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.ll_smiley_container) ?: return
        container.removeAllViews()

        // 优先使用论坛真实表情（与回复弹窗同一套数据）；未取到时回退自绘图标
        val catalog = smileyCatalog
        if (catalog != null && catalog.isNotEmpty() && catalog[0].items.isNotEmpty()) {
            renderPostSmileySet(catalog)
            return
        }

        val iconIds = intArrayOf(
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        )
        val labels = arrayOf("微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶")
        val size = (48 * resources.displayMetrics.density).toInt()
        val padding = (6 * resources.displayMetrics.density).toInt()

        for (idx in iconIds.indices) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(padding, padding / 2, padding, padding / 2)
            val itemLp = LinearLayout.LayoutParams(size, size)
            item.layoutParams = itemLp
            item.setBackgroundResource(android.R.drawable.list_selector_background)
            item.isClickable = true
            item.isFocusable = true

            val iv = ImageView(this)
            val iconSize = (28 * resources.displayMetrics.density).toInt()
            val ivLp = LinearLayout.LayoutParams(iconSize, iconSize)
            iv.layoutParams = ivLp
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageResource(iconIds[idx])
            item.addView(iv)

            val tv = TextView(this)
            tv.text = labels[idx]
            tv.setTextSize(9f)
            tv.setTextColor(0xFF9CA3AF.toInt())
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            item.addView(tv)

            val tag = "[" + labels[idx] + "]"
            item.setOnClickListener {
                val editable = etContent.text
                if (editable == null) {
                    etContent.setText(tag)
                    return@setOnClickListener
                }
                var selStart = etContent.selectionStart
                if (selStart < 0) selStart = editable.length
                editable.insert(selStart, tag)
                etContent.setSelection(selStart + tag.length)
            }
            container.addView(item)
        }
    }

    // ---- 插入功能（9 项内容类型面板，与网页端一致）----
    private fun toggleInsertPanel() {
        val visible = llInsertPanel.visibility == View.VISIBLE
        hideAllPanels()
        if (visible) return
        llInsertPanel.visibility = View.VISIBLE
        llInsertInput.visibility = View.GONE
        findViewById<View>(R.id.btn_ins_link)?.setOnClickListener { selectInsertType(INSERT_LINK, true, false, "链接网址", "链接文字") }
        findViewById<View>(R.id.btn_ins_image)?.setOnClickListener { selectInsertType(INSERT_IMAGE, true, false, "图片地址", "") }
        findViewById<View>(R.id.btn_ins_audio)?.setOnClickListener { selectInsertType(INSERT_AUDIO, true, false, "音乐文件地址", "") }
        findViewById<View>(R.id.btn_ins_video)?.setOnClickListener { selectInsertType(INSERT_VIDEO, true, false, "视频地址", "") }
        findViewById<View>(R.id.btn_ins_flash)?.setOnClickListener { selectInsertType(INSERT_FLASH, true, false, "Flash 地址", "") }
        findViewById<View>(R.id.btn_ins_quote)?.setOnClickListener { selectInsertType(INSERT_QUOTE, false, true, "", "") }
        findViewById<View>(R.id.btn_ins_code)?.setOnClickListener { selectInsertType(INSERT_CODE, false, true, "", "") }
        findViewById<View>(R.id.btn_ins_free)?.setOnClickListener { selectInsertType(INSERT_FREE, false, true, "", "") }
        findViewById<View>(R.id.btn_ins_hide)?.setOnClickListener { selectInsertType(INSERT_HIDE, false, true, "", "") }
    }

    private fun selectInsertType(type: Int, needUrl: Boolean, needText: Boolean, hint1: String, hint2: String) {
        val f1 = findViewById<TextInputEditText>(R.id.et_insert_field1)
        val f2 = findViewById<TextInputEditText>(R.id.et_insert_field2)
        llInsertInput.visibility = View.VISIBLE
        f1?.setText("")
        f2?.setText("")
        f1?.hint = hint1
        f1?.visibility = if (needUrl || needText) View.VISIBLE else View.GONE
        f2?.hint = hint2
        f2?.visibility = if (needUrl && needText && hint2.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_insert_confirm)?.setOnClickListener {
            applyInsert(type, f1?.text?.toString()?.trim() ?: "", f2?.text?.toString()?.trim() ?: "")
        }
    }

    private fun applyInsert(type: Int, field1: String, field2: String) {
        if ((type == INSERT_LINK || type == INSERT_IMAGE || type == INSERT_AUDIO ||
                type == INSERT_VIDEO || type == INSERT_FLASH) && TextUtils.isEmpty(field1)
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
        insertIntoContent(text)
        hideAllPanels()
    }

    private fun updateSelectedForumUI() {
        if (!TextUtils.isEmpty(selectedForumName)) {
            tvCircleLabel.visibility = View.GONE
            tvSelectedForum.text = "# " + selectedForumName
            tvSelectedForum.visibility = View.VISIBLE
            llCircleSelector.setBackgroundResource(R.drawable.bg_post_forum_chip_selected)
        } else {
            tvCircleLabel.visibility = View.VISIBLE
            tvCircleLabel.text = getString(R.string.post_circle_required)
            tvSelectedForum.visibility = View.GONE
            llCircleSelector.setBackgroundResource(R.drawable.bg_post_forum_chip)
        }
    }

    // ---- 🖼 图片上传功能（与网页端对齐：支持多选、预览、上传） ----

    private fun pickImage() {
        hideAllPanels()
        // 使用 Intent.ACTION_PICK 打开系统相册，支持多图选择
        val intent: Intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            intent = Intent(Intent.ACTION_PICK)
            intent.data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.type = "image/*"
        } else {
            intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE_PICK)
    }

    private fun uploadImages(uris: MutableList<Uri>) {
        Toast.makeText(this, "正在上传 " + uris.size + " 张图片...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                if (!ensureUploadAuth()) {
                    runOnUiThread {
                        Toast.makeText(this, "获取上传授权失败，请先选择版块或重新登录", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // 与网页端 buildfileupload 完全一致：simple=2 + inajax/infloat。
                val uploadUrl = HttpClient.BASE_URL +
                        "misc.php?mod=swfupload&operation=upload" +
                        "&type=image&inajax=yes&infloat=yes&simple=2"

                for (uri in uris) {
                    val rawName = getFileNameFromUri(uri)
                    val fileName = if (rawName.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$")))
                        rawName else "image_" + System.currentTimeMillis() + ".jpg"
                    val tempFile = copyUriToTempFile(uri, fileName) ?: continue

                    val extraFields = HashMap<String, String>()
                    extraFields["uid"] = currentUid ?: ""
                    extraFields["hash"] = currentHash ?: ""
                    val response = HttpClient.getInstance().uploadFile(
                        uploadUrl, tempFile, "Filedata", extraFields
                    )
                    val aid = parseDiscuzUploadResponse(response)
                    if (aid == null) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "$fileName 上传失败", Toast.LENGTH_SHORT
                            ).show()
                        }
                        continue
                    }

                    val bbcode = "\n[attachimg]" + aid + "[/attachimg]\n"
                    val af = AttachFile(fileName, tempFile.absolutePath)
                    af.aid = aid
                    af.bbcode = bbcode
                    synchronized(attachFiles) { attachFiles.add(af) }
                    runOnUiThread {
                        insertIntoContent(bbcode)
                        updateAttachList()
                        updateImagePreview()
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "图片上传处理完成", Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "图片上传异常: " + e.message, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun updateImagePreview() {
        if (llImagePreview == null) return
        llImagePreview.removeAllViews()
        val images = ArrayList<AttachFile>()
        for (af in attachFiles) {
            if (af.name != null && af.name!!.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$"))) {
                images.add(af)
            }
        }
        if (images.isEmpty()) {
            llImagePreview.visibility = View.GONE
            return
        }
        llImagePreview.visibility = View.VISIBLE

        val colCount = 3
        val density = resources.displayMetrics.density
        val gap = Math.round(6 * density)
        val imgHeight = Math.round(96 * density)

        val previewUrls = ArrayList<String>()
        for (af in images) {
            val p = if (!TextUtils.isEmpty(af.path)) af.path!! else (af.url ?: "")
            previewUrls.add(p)
        }

        val totalItems = images.size + (if (images.size < 9) 1 else 0)
        val rowCount = (totalItems + colCount - 1) / colCount

        for (r in 0 until rowCount) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                imgHeight
            )
            rowLp.topMargin = if (r == 0) 0 else gap
            row.layoutParams = rowLp

            for (c in 0 until colCount) {
                val index = r * colCount + c
                if (index < images.size) {
                    val af = images[index]
                    val fl = FrameLayout(this)
                    val flLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (c > 0) flLp.leftMargin = gap / 2
                    if (c < colCount - 1) flLp.rightMargin = gap / 2
                    fl.layoutParams = flLp

                    val iv = ImageView(this)
                    iv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundResource(R.drawable.thread_image_bg)
                    iv.clipToOutline = true

                    Glide.with(this)
                        .load(af.path)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .transform(com.bumptech.glide.load.resource.bitmap.CenterCrop(), com.bumptech.glide.load.resource.bitmap.RoundedCorners(Math.round(8 * density)))
                        .into(iv)

                    val pos = index
                    iv.setOnClickListener {
                        val intent = Intent(this, com.solosu.mtforum.ui.detail.ImagePreviewActivity::class.java)
                        intent.putStringArrayListExtra("image_urls", previewUrls)
                        intent.putExtra("image_index", pos)
                        startActivity(intent)
                    }
                    fl.addView(iv)

                    val delBtn = ImageView(this)
                    val delSize = Math.round(22 * density)
                    val delLp = FrameLayout.LayoutParams(delSize, delSize, Gravity.TOP or Gravity.END)
                    delLp.topMargin = Math.round(4 * density)
                    delLp.rightMargin = Math.round(4 * density)
                    delBtn.layoutParams = delLp
                    delBtn.setBackgroundResource(R.drawable.bg_image_remove_btn)
                    delBtn.setImageResource(R.drawable.ic_cross)
                    val p4 = Math.round(5 * density)
                    delBtn.setPadding(p4, p4, p4, p4)
                    androidx.core.widget.ImageViewCompat.setImageTintList(delBtn, android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt()))
                    delBtn.setOnClickListener {
                        attachFiles.remove(af)
                        updateAttachList()
                        updateImagePreview()
                    }
                    fl.addView(delBtn)

                    row.addView(fl)
                } else if (index == images.size && images.size < 9) {
                    val fl = FrameLayout(this)
                    val flLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (c > 0) flLp.leftMargin = gap / 2
                    if (c < colCount - 1) flLp.rightMargin = gap / 2
                    fl.layoutParams = flLp
                    fl.setBackgroundResource(R.drawable.bg_image_add_placeholder)
                    fl.isClickable = true
                    fl.isFocusable = true

                    val centerLayout = LinearLayout(this)
                    centerLayout.orientation = LinearLayout.VERTICAL
                    centerLayout.gravity = Gravity.CENTER
                    centerLayout.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )

                    val addIv = ImageView(this)
                    val addIvSize = Math.round(22 * density)
                    addIv.layoutParams = LinearLayout.LayoutParams(addIvSize, addIvSize)
                    addIv.setImageResource(R.drawable.ic_add)
                    androidx.core.widget.ImageViewCompat.setImageTintList(addIv, android.content.res.ColorStateList.valueOf(0xFF94A3B8.toInt()))
                    centerLayout.addView(addIv)

                    val addTv = TextView(this)
                    addTv.text = "添加图片"
                    addTv.textSize = 10f
                    addTv.setTextColor(0xFF94A3B8.toInt())
                    val tvLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    tvLp.topMargin = Math.round(2 * density)
                    addTv.layoutParams = tvLp
                    centerLayout.addView(addTv)

                    fl.addView(centerLayout)
                    fl.setOnClickListener { pickImage() }
                    row.addView(fl)
                } else {
                    val dummy = View(this)
                    val dummyLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (c > 0) dummyLp.leftMargin = gap / 2
                    if (c < colCount - 1) dummyLp.rightMargin = gap / 2
                    dummy.layoutParams = dummyLp
                    row.addView(dummy)
                }
            }
            llImagePreview.addView(row)
        }
    }

    // ---- 附件功能 ----
    private fun pickFile() {
        hideAllPanels()
        // 先选文件，上传时在后台线程统一获取授权
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_FILE_PICK)
    }

    // 在后台线程安全地预加载上传授权（必须使用桌面版UA，移动端UA会被Discuz!强制返回移动版页面，不含hash令牌）
    private fun ensureUploadAuth(): Boolean {
        // 登录可能由 WebView 完成；仅在原生 CookieJar 未登录时才同步，避免覆盖有效会话。
        if (!HttpClient.getInstance().isLoggedIn()) {
            HttpClient.getInstance().syncFromCookieManager()
        }
        if (!HttpClient.getInstance().isLoggedIn()) return false
        if (isValidUploadAuth()) return true
        return try {
            // 桌面版发帖页面（含hash上传令牌）— 必须用桌面版 UA，不能带 mobile=2
            val fid = selectedFid ?: "39"
            val url = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=$fid"
            val html = HttpClient.getInstance().getDesktop(url)
            if (html != null) {
                if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(html)
                extractUidAndHash(html)
            }
            isValidUploadAuth()
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidUploadAuth(): Boolean {
        return currentUid != null && currentUid!!.matches(Regex("[1-9]\\d*"))
                && currentHash != null && !currentHash!!.trim().isEmpty()
    }

    private fun preloadUploadAuth() {
        Thread {
            ensureUploadAuth()
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_PICK && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                uploadFile(uri)
            }
        } else if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            val imageUris = ArrayList<Uri>()
            // 多图选择
            if (data.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    if (uri != null) imageUris.add(uri)
                }
            } else if (data.data != null) {
                imageUris.add(data.data!!)
            }
            if (imageUris.isNotEmpty()) {
                uploadImages(imageUris)
            }
        }
    }

    private fun uploadFile(uri: Uri) {
        Toast.makeText(this, "正在上传附件...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val fileName = getFileNameFromUri(uri)
                val tempFile = copyUriToTempFile(uri, fileName)
                if (tempFile == null) {
                    runOnUiThread { Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                if (!ensureUploadAuth()) {
                    runOnUiThread {
                        Toast.makeText(this, "获取上传授权失败，请先选择版块或重新登录", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val uploadUrl = HttpClient.BASE_URL +
                        "misc.php?mod=swfupload&operation=upload" +
                        "&type=attach&inajax=yes&infloat=yes&simple=2"
                val extraFields = HashMap<String, String>()
                extraFields["uid"] = currentUid ?: ""
                extraFields["hash"] = currentHash ?: ""
                val response = HttpClient.getInstance().uploadFile(
                    uploadUrl, tempFile, "Filedata", extraFields
                )
                val aid = parseDiscuzUploadResponse(response)
                if (aid == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "附件上传失败", Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }

                val bbcode = if (fileName.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$")))
                    "\n[attachimg]" + aid + "[/attachimg]\n"
                else
                    "\n[attach]" + aid + "[/attach]\n"
                val af = AttachFile(fileName, tempFile.absolutePath)
                af.aid = aid
                af.bbcode = bbcode
                synchronized(attachFiles) { attachFiles.add(af) }
                runOnUiThread {
                    insertIntoContent(bbcode)
                    updateAttachList()
                    updateImagePreview()
                    Toast.makeText(this, "附件已上传: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "附件上传异常: " + e.message, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /**
     * 解析 Discuz! 附件上传响应（管道分隔符格式）
     * 成功: DISCUZUPLOAD|aid|uploadId|imageWidth|imageHeight
     * 失败: DISCUZUPLOAD|error|错误码|错误信息
     */
    private fun parseDiscuzUploadResponse(response: String?): String? {
        if (response == null) return null
        val text = response.trim()
        if (text.isEmpty()) return null
        try {
            // Discuz! 实际格式：DISCUZUPLOAD|状态|错误码|aid|hash|路径|文件名...
            // 成功条件是 data[0]=DISCUZUPLOAD 且 data[2]=0，aid 在 data[3]。
            if (text.startsWith("DISCUZUPLOAD|")) {
                val parts = text.split("\\|".toRegex(), 0).toTypedArray()
                if (parts.size > 3 && "0" == parts[2]
                    && parts[3].matches(Regex("\\d+"))
                ) {
                    return parts[3]
                }
                if (parts.size > 3 && "error".equals(parts[1], ignoreCase = true)) {
                    val errorMsg = if (parts.size > 7) parts[7]
                    else (if (parts.size > 3) parts[3] else "未知错误")
                    runOnUiThread {
                        Toast.makeText(
                            this@PostActivity,
                            "上传错误: $errorMsg", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun extractAttachUrlFromResponse(response: String?): String? {
        try {
            val json = JSONObject(response)
            if (json.has("url")) return json.getString("url")
            if (json.has("attach")) return json.getJSONObject("attach").optString("url")
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun extractAidFromHtml(html: String?): String? {
        if (TextUtils.isEmpty(html)) return null
        try {
            val doc = Jsoup.parse(html!!)
            val input = doc.select("input[name=aid]").first()
            if (input != null) return input.`val`()
            // 从attachnotice中提取
            val notice = doc.getElementById("attachnotice_attach")
            if (notice != null) {
                val text = notice.text()
                val m = Pattern.compile("(\\d+)").matcher(text)
                if (m.find()) return m.group(1)
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "attachment"
        try {
            val path = uri.path
            if (path != null) name = path.substring(path.lastIndexOf('/') + 1)
        } catch (ignored: Exception) {
        }
        if (!name.contains(".")) name += ".dat"
        return name
    }

    @Throws(Exception::class)
    private fun copyUriToTempFile(uri: Uri, fileName: String): File? {
        val tmpDir = File(cacheDir, "attachments")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val tmpFile = File(tmpDir, fileName)
        contentResolver.openInputStream(uri).use { `is` ->
            FileOutputStream(tmpFile).use { os ->
                if (`is` == null) return null
                val buf = ByteArray(8192)
                var len = `is`.read(buf)
                while (len != -1) {
                    os.write(buf, 0, len)
                    len = `is`.read(buf)
                }
            }
        }
        return tmpFile
    }

    private fun updateAttachList() {
        llAttachList.removeAllViews()
        val nonImageFiles = ArrayList<AttachFile>()
        for (af in attachFiles) {
            val isImage = af.name != null && af.name!!.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$"))
            if (!isImage) {
                nonImageFiles.add(af)
            }
        }
        if (nonImageFiles.isEmpty()) {
            llAttachList.visibility = View.GONE
            return
        }
        llAttachList.visibility = View.VISIBLE
        val density = resources.displayMetrics.density

        for (i in nonImageFiles.indices) {
            val af = nonImageFiles[i]
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val cardLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(44 * density)
            )
            cardLp.topMargin = Math.round(6 * density)
            card.layoutParams = cardLp
            card.setBackgroundResource(R.drawable.bg_post_panel)
            card.setPadding(Math.round(12 * density), 0, Math.round(12 * density), 0)

            val iconIv = ImageView(this)
            val iconSize = Math.round(18 * density)
            iconIv.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            iconIv.setImageResource(R.drawable.ic_post_attach)
            androidx.core.widget.ImageViewCompat.setImageTintList(iconIv, android.content.res.ColorStateList.valueOf(0xFF64748B.toInt()))
            card.addView(iconIv)

            val tv = TextView(this)
            val tvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tvLp.leftMargin = Math.round(8 * density)
            tvLp.rightMargin = Math.round(8 * density)
            tv.layoutParams = tvLp
            tv.text = af.name ?: "未知文件"
            tv.setTextColor(0xFF0F172A.toInt())
            tv.textSize = 13f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.MIDDLE
            card.addView(tv)

            val delBtn = ImageView(this)
            val delSize = Math.round(20 * density)
            delBtn.layoutParams = LinearLayout.LayoutParams(delSize, delSize)
            delBtn.setImageResource(R.drawable.ic_cross)
            delBtn.setPadding(Math.round(2 * density), Math.round(2 * density), Math.round(2 * density), Math.round(2 * density))
            androidx.core.widget.ImageViewCompat.setImageTintList(delBtn, android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt()))
            delBtn.setOnClickListener {
                attachFiles.remove(af)
                updateAttachList()
            }
            card.addView(delBtn)

            llAttachList.addView(card)
        }
    }

    // ---- 高级功能 ----
    // llAdvancedOptions 中已包含：cb_hidden_replies, cb_reverse_order, cb_usesig, cb_smiley_off, cb_bbcode_off

    // ---- 面板管理 ----
    private fun hideAllPanels() {
        llSmileyPanel.visibility = View.GONE
        llAtPanel.visibility = View.GONE
        llAdvancedOptions.visibility = View.GONE
        llInsertPanel.visibility = View.GONE
    }

    private fun hideAllPanelsExcept(except: View) {
        if (except !== llSmileyPanel) llSmileyPanel.visibility = View.GONE
        if (except !== llAtPanel) llAtPanel.visibility = View.GONE
        if (except !== llAdvancedOptions) llAdvancedOptions.visibility = View.GONE
        if (except !== llInsertPanel) llInsertPanel.visibility = View.GONE
    }

    // 插入内容到正文
    private fun insertIntoContent(text: String) {
        val editable = etContent.text
        if (editable == null) {
            etContent.setText(text)
            return
        }
        var start = etContent.selectionStart
        val end = etContent.selectionEnd
        if (start < 0) start = editable.length
        if (start >= 0 && end > start) {
            editable.replace(start, end, text)
        } else {
            editable.insert(if (start >= 0) start else editable.length, text)
        }
    }

    // ==================== 版块选择 ====================

    private fun showForumPicker() {
        Thread {
            try {
                val url = HttpClient.BASE_URL + "forum.php?forumlist=1&mobile=2"
                val html = HttpClient.getInstance().get(url)
                if (html == null || html.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@PostActivity,
                            R.string.network_error, Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }
                val categories = ForumParser.parseForumCategories(html)
                runOnUiThread { buildForumPickerDialog(categories) }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@PostActivity,
                        R.string.network_error, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun buildForumPickerDialog(categories: MutableList<ForumCategory>?) {
        if (categories == null || categories.isEmpty()) {
            Toast.makeText(this, "暂无可用版块", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_forum_picker_sheet, null)
        bottomSheet.setContentView(sheetView)

        bottomSheet.setOnShowListener {
            val d = it as? com.google.android.material.bottomsheet.BottomSheetDialog
            val sheet = d?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        val container = sheetView.findViewById<LinearLayout>(R.id.ll_forum_groups)
        container?.removeAllViews()

        val density = resources.displayMetrics.density
        val colCount = 3
        val gap = Math.round(8 * density)
        val chipHeight = Math.round(40 * density)
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)

        for (category in categories) {
            val forums = category.forums
            if (forums == null || forums.isEmpty()) continue

            // 分组标题条
            val headerLayout = LinearLayout(this)
            headerLayout.orientation = LinearLayout.HORIZONTAL
            headerLayout.gravity = Gravity.CENTER_VERTICAL
            val headerLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            headerLp.topMargin = Math.round(14 * density)
            headerLp.bottomMargin = Math.round(8 * density)
            headerLayout.layoutParams = headerLp

            // 主题色微竖条
            val bar = View(this)
            val barLp = LinearLayout.LayoutParams(Math.round(3 * density), Math.round(14 * density))
            barLp.rightMargin = Math.round(6 * density)
            bar.layoutParams = barLp
            bar.setBackgroundColor(themeColor)
            headerLayout.addView(bar)

            // 分区名
            val tvCategory = TextView(this)
            tvCategory.text = category.name ?: "论坛专区"
            tvCategory.textSize = 13.5f
            tvCategory.setTextColor(getColor(R.color.text_primary))
            tvCategory.typeface = android.graphics.Typeface.DEFAULT_BOLD
            headerLayout.addView(tvCategory)

            // 数量
            val tvCount = TextView(this)
            val countLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            countLp.leftMargin = Math.round(6 * density)
            tvCount.layoutParams = countLp
            tvCount.text = "${forums.size}个版块"
            tvCount.textSize = 11f
            tvCount.setTextColor(getColor(R.color.text_hint))
            headerLayout.addView(tvCount)

            container?.addView(headerLayout)

            // 3列网格
            val rowCount = (forums.size + colCount - 1) / colCount
            for (r in 0 until rowCount) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val rowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    chipHeight
                )
                rowLp.topMargin = if (r == 0) 0 else gap
                row.layoutParams = rowLp

                for (c in 0 until colCount) {
                    val index = r * colCount + c
                    if (index < forums.size) {
                        val forum = forums[index]
                        val isSelected = (selectedFid != null && selectedFid == forum.fid)

                        val chip = TextView(this)
                        val chipLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        if (c > 0) chipLp.leftMargin = gap / 2
                        if (c < colCount - 1) chipLp.rightMargin = gap / 2
                        chip.layoutParams = chipLp
                        chip.gravity = Gravity.CENTER
                        chip.text = forum.name ?: ""
                        chip.textSize = 13f
                        chip.maxLines = 1
                        chip.ellipsize = TextUtils.TruncateAt.END
                        chip.setPadding(Math.round(4 * density), 0, Math.round(4 * density), 0)

                        if (isSelected) {
                            val bg = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 16 * density
                                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 0x1F))
                                setStroke(Math.round(1 * density), androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 0x66))
                            }
                            chip.background = bg
                            chip.setTextColor(themeColor)
                            chip.typeface = android.graphics.Typeface.DEFAULT_BOLD
                        } else {
                            chip.setBackgroundResource(R.drawable.bg_post_forum_chip)
                            chip.setTextColor(getColor(R.color.text_primary))
                        }

                        chip.isClickable = true
                        chip.isFocusable = true
                        chip.setOnClickListener {
                            selectForum(forum)
                            bottomSheet.dismiss()
                        }
                        row.addView(chip)
                    } else {
                        val dummy = View(this)
                        val dummyLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        if (c > 0) dummyLp.leftMargin = gap / 2
                        if (c < colCount - 1) dummyLp.rightMargin = gap / 2
                        dummy.layoutParams = dummyLp
                        row.addView(dummy)
                    }
                }
                container?.addView(row)
            }
        }

        bottomSheet.show()
        DialogHelper.applyToBottomSheet(bottomSheet, sheetView, this)
    }

    private fun selectForum(forum: ForumCategory.Forum) {
        selectedFid = forum.fid
        selectedForumName = forum.name
        updateSelectedForumUI()
        // 切换圈子后重新加载formhash
        loadFormhashAndUserInfo()
    }

    // ==================== formhash & hash 加载 ====================

    private fun loadFormhashAndUserInfo() {
        Thread {
            try {
                if (selectedFid == null) return@Thread
                // 获取 formhash 用移动端页面（更轻量，formhash 双端都有）
                val mobileUrl = ForumParser.getNewThreadUrl(selectedFid)
                val mobileHtml = HttpClient.getInstance().get(mobileUrl)
                if (mobileHtml != null) {
                    currentFormhash = ForumParser.parseFormhash(mobileHtml)
                }
                // 获取 uid + hash 必须用桌面版页面（移动版不含 hash 令牌）
                val desktopUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid
                val desktopHtml = HttpClient.getInstance().getDesktop(desktopUrl)
                if (desktopHtml != null) {
                    if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(desktopHtml)
                    extractUidAndHash(desktopHtml)
                    cacheSmileyCatalog(desktopHtml)
                }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    /** 桌面编辑器页内联了论坛表情数据，从中解析并缓存，供表情面板使用 */
    private fun cacheSmileyCatalog(html: String?) {
        if (smileyCatalog != null) return
        var parsed: MutableList<ForumParser.SmileySet> = ArrayList()
        for (url in ForumParser.getSmileyScriptUrlCandidates()) {
            parsed = ForumParser.parseSmileyCatalog(HttpClient.getInstance().get(url))
            if (parsed.isNotEmpty()) break
        }
        if (parsed.isEmpty()) {
            parsed = ForumParser.parseSmileyCatalog(html)
            if (parsed.isEmpty()) {
                for (url in ForumParser.extractSmileyScriptUrls(html)) {
                    parsed = ForumParser.parseSmileyCatalog(HttpClient.getInstance().get(url))
                    if (parsed.isNotEmpty()) break
                }
            }
        }
        if (parsed.isNotEmpty()) smileyCatalog = parsed
    }

    private fun loadFormhashSync() {
        try {
            if (selectedFid == null) return
            // formhash 可从移动端获取
            val mobileUrl = ForumParser.getNewThreadUrl(selectedFid)
            val mobileHtml = HttpClient.getInstance().get(mobileUrl)
            if (mobileHtml != null) {
                currentFormhash = ForumParser.parseFormhash(mobileHtml)
            }
            // uid + hash 必须桌面版
            val desktopUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid
            val desktopHtml = HttpClient.getInstance().getDesktop(desktopUrl)
            if (desktopHtml != null) {
                if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(desktopHtml)
                extractUidAndHash(desktopHtml)
                cacheSmileyCatalog(desktopHtml)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun extractUidAndHash(html: String?) {
        if (TextUtils.isEmpty(html)) return
        try {
            // 兼容 JS 变量、JSON 对象以及隐藏 input 的属性顺序/单双引号差异。
            var m = Pattern.compile(
                "(?:var\\s+|\\b)discuz_uid\\s*(?:=|:)\\s*['\"]([1-9]\\d*)['\"]",
                Pattern.CASE_INSENSITIVE
            ).matcher(html!!)
            if (m.find()) currentUid = m.group(1)

            m = Pattern.compile(
                "<input[^>]+name\\s*=\\s*['\"]hash['\"][^>]+value\\s*=\\s*['\"]([^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            var hashFound = m.find()
            if (!hashFound) {
                m = Pattern.compile(
                    "<input[^>]+value\\s*=\\s*['\"]([^'\"]+)['\"][^>]+name\\s*=\\s*['\"]hash['\"]",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                hashFound = m.find()
            }
            if (!hashFound) {
                m = Pattern.compile(
                    "(?:var\\s+|\\b)hash\\s*(?:=|:)\\s*['\"]([^'\"]+)['\"]",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                hashFound = m.find()
            }
            if (hashFound) currentHash = m.group(1)
        } catch (ignored: Exception) {
        }
    }

    // ==================== 发布 ====================

    // ═══ 草稿箱(build52) ═══
    private fun restoreDraft() {
        val e = DraftManager.latest(this) ?: return
        val empty = (etTitle.text == null || etTitle.text.toString().trim().isEmpty())
                && (etContent.text == null || etContent.text.toString().trim().isEmpty())
        if (!empty) return // 已有内容不覆盖
        if (e.title.isNullOrBlank() && e.content.isNullOrBlank()) {
            DraftManager.delete(this, e.id)
            return
        }
        draftId = e.id
        if (e.title != null) etTitle.setText(e.title)
        if (e.content != null) etContent.setText(e.content)
        if (e.fid != null && !e.fid!!.isEmpty()) {
            selectedFid = e.fid
            selectedForumName = e.forumName ?: ""
            updateSelectedForumUI()
        }
        cbAnonymous.isChecked = e.anonymous
        Toast.makeText(this, "已恢复上次草稿", Toast.LENGTH_SHORT).show()
    }

    private fun saveDraftNow() {
        if (postedDone || isEditMode()) return
        val title = if (etTitle.text != null) etTitle.text.toString().trim() else ""
        val content = if (etContent.text != null) etContent.text.toString().trim() else ""
        // 无有效内容（选择版块不算有效内容）时不保存为草稿
        if (title.isEmpty() && content.isEmpty()) {
            if (draftId > 0) {
                DraftManager.delete(this, draftId)
                draftId = 0
            }
            return
        }
        draftId = DraftManager.saveDraft(
            this, draftId, title, content,
            selectedFid, selectedForumName, cbAnonymous.isChecked
        )
    }

    override fun onPause() {
        super.onPause()
        saveDraftNow()
    }

    private fun draftLabel(e: DraftManager.Entry): String {
        val t = if (e.title == null || e.title!!.isEmpty()) "(无标题)" else e.title!!
        var preview = if (e.content == null) "" else e.content!!.replace(10.toChar().toString(), " ")
        if (preview.length > 18) preview = preview.substring(0, 18)
        val fm = if (e.forumName == null || e.forumName!!.isEmpty()) "" else " · " + e.forumName
        val ts = if (e.time > 0) java.text.SimpleDateFormat(
            "MM-dd HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date(e.time)) else ""
        return t + fm + 10.toChar().toString() + ts + " · " + preview
    }

    private fun loadDraftToEditor(e: DraftManager.Entry) {
        draftId = e.id
        if (e.title != null) etTitle.setText(e.title)
        if (e.content != null) etContent.setText(e.content)
        if (e.fid != null && !e.fid!!.isEmpty()) {
            selectedFid = e.fid
            selectedForumName = e.forumName ?: ""
            updateSelectedForumUI()
        }
        cbAnonymous.isChecked = e.anonymous
        Toast.makeText(this, "草稿已载入", Toast.LENGTH_SHORT).show()
    }

    private fun showDraftsDialog() {
        val l = DraftManager.list(this)
        if (l.isEmpty()) {
            Toast.makeText(this, "草稿箱是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOfNulls<String>(l.size)
        for (i in l.indices) items[i] = draftLabel(l[i])
        val dialog = AlertDialog.Builder(this)
            .setTitle("草稿箱 (" + l.size + "条) · 点条目载入")
            .setItems(items) { d, which -> loadDraftToEditor(l[which]) }
            .setNeutralButton("删单条") { d, w -> showDraftDeleteDialog() }
            .setNegativeButton("清空") { d, w ->
                DraftManager.clear(this@PostActivity)
                draftId = 0
                Toast.makeText(this@PostActivity, "草稿箱已清空", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
        DialogHelper.applyToAlertDialog(dialog, this)
    }

    private fun showDraftDeleteDialog() {
        val l = DraftManager.list(this)
        if (l.isEmpty()) {
            Toast.makeText(this, "草稿箱是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOfNulls<String>(l.size)
        for (i in l.indices) items[i] = draftLabel(l[i])
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择要删除的草稿")
            .setItems(items) { d, which ->
                val e = l[which]
                DraftManager.delete(this@PostActivity, e.id)
                if (e.id == draftId) draftId = 0
                Toast.makeText(this@PostActivity, "已删除该条草稿", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        DialogHelper.applyToAlertDialog(dialog, this)
    }

    private fun setupPublishButton() {
        btnPublish.setOnClickListener { attemptPost() }
    }

    private fun attemptPost() {
        // build73: 编辑模式走 action=edit,与发新帖完全分开
        if (isEditMode()) {
            btnPublish.isEnabled = false
            btnPublish.text = "保存中..."
            Thread {
                try {
                    attemptEdit()
                } catch (e: Exception) {
                    val msg = if (TextUtils.isEmpty(e.message))
                        getString(R.string.network_error) else e.message
                    runOnUiThread {
                        showError(msg!!)
                        resetPublishButton()
                    }
                }
            }.start()
            return
        }

        val title = if (etTitle.text != null) etTitle.text.toString().trim() else ""
        val content = if (etContent.text != null)
            etContent.text.toString().trim() else ""

        if (title.isEmpty()) {
            showError("请输入标题")
            return
        }
        if (selectedFid == null) {
            showError("请选择版块")
            return
        }
        if (content.isEmpty()) {
            showError("请输入正文内容")
            return
        }

        btnPublish.isEnabled = false
        btnPublish.text = "发布中..."

        Thread {
            try {
                // 获取formhash
                if (currentFormhash == null) {
                    loadFormhashSync()
                }
                if (currentFormhash == null) {
                    val postUrl = ForumParser.getNewThreadUrl(selectedFid)
                    val postHtml = HttpClient.getInstance().get(postUrl)
                    if (postHtml != null) {
                        currentFormhash = ForumParser.parseFormhash(postHtml)
                    }
                }
                if (currentFormhash == null || currentFormhash!!.isEmpty()) {
                    runOnUiThread {
                        showError("获取安全验证失败，请重试")
                        resetPublishButton()
                    }
                    return@Thread
                }

                // 组装 POST 参数（与网页端完全对齐）
                val params = HashMap<String, String>()
                params["formhash"] = currentFormhash!!
                params["subject"] = title
                params["message"] = content
                params["allownoticeauthor"] = "1"

                // 上传接口返回的 aid 只是暂存附件，发帖时还必须提交 attachnew[aid][description]，
                // Discuz! 才会把附件正式关联到新主题。
                synchronized(attachFiles) {
                    for (af in attachFiles) {
                        if (af != null && af.aid != null && af.aid!!.matches(Regex("\\d+"))) {
                            params["attachnew[" + af.aid + "][description]"] = ""
                        }
                    }
                }

                // 高级选项参数
                val cbHiddenReplies = findViewById<CheckBox>(R.id.cb_hidden_replies)
                if (cbHiddenReplies != null && cbHiddenReplies.isChecked) {
                    params["hiddenreplies"] = "1"
                }
                val cbReverseOrder = findViewById<CheckBox>(R.id.cb_reverse_order)
                if (cbReverseOrder != null && cbReverseOrder.isChecked) {
                    params["ordertype"] = "1"
                }
                val cbUsesig = findViewById<CheckBox>(R.id.cb_usesig)
                if (cbUsesig != null && cbUsesig.isChecked) {
                    params["usesig"] = "1"
                }
                val cbSmileyOff = findViewById<CheckBox>(R.id.cb_smiley_off)
                if (cbSmileyOff != null && cbSmileyOff.isChecked) {
                    params["smileyoff"] = "1"
                }
                val cbBbcodeOff = findViewById<CheckBox>(R.id.cb_bbcode_off)
                if (cbBbcodeOff != null && cbBbcodeOff.isChecked) {
                    params["bbcodeoff"] = "1"
                }

                if (cbAnonymous.isChecked) {
                    params["anonymous"] = "1"
                }

                // POST 提交
                val submitUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid + "&topicsubmit=yes"
                val response = HttpClient.getInstance().post(submitUrl, params)

                // 解析结果
                if (response != null && (response.contains("tid=") || response.contains("viewthread"))) {
                    runOnUiThread {
                        postedDone = true
                        if (draftId > 0) {
                            DraftManager.delete(this@PostActivity, draftId)
                            draftId = 0
                        }
                        Toast.makeText(this@PostActivity, R.string.post_success, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK, Intent().putExtra("tid", extractTid(response)))
                        finish()
                    }
                } else {
                    val errorMsg = extractErrorFromResponse(response)
                    runOnUiThread {
                        showError(errorMsg)
                        resetPublishButton()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    showError(getString(R.string.network_error))
                    resetPublishButton()
                }
            }
        }.start()
    }

    // ==================== build73: 编辑帖子模式 ====================

    /** 编辑模式:详情页传入 tid/pid/fid/title/message */
    private fun setupEditMode() {
        val it = intent
        editTid = it.getStringExtra("edit_tid")
        editPid = it.getStringExtra("edit_pid")
        if (TextUtils.isEmpty(editTid) || TextUtils.isEmpty(editPid)) {
            return // 普通发帖模式
        }
        val fname = it.getStringExtra("edit_forum_name")
        if (!TextUtils.isEmpty(fname)) {
            selectedForumName = fname
            updateSelectedForumUI()
        }
        val fid = it.getStringExtra("edit_fid")
        if (!TextUtils.isEmpty(fid)) {
            selectedFid = fid
        }
        if (etTitle != null) {
            etTitle.setText(it.getStringExtra("edit_title"))
            etTitle.isEnabled = false // 编辑不改标题,避免触发审核
        }
        if (etContent != null) {
            etContent.setText(it.getStringExtra("edit_message"))
        }
        if (btnPublish != null) btnPublish.text = "保存修改"
        if (tvTitleCount != null) tvTitleCount.visibility = View.GONE
        // 编辑模式:不给改版块
        if (llCircleSelector != null) llCircleSelector.visibility = View.GONE
    }

    private fun isEditMode(): Boolean {
        return !TextUtils.isEmpty(editTid) && !TextUtils.isEmpty(editPid)
    }

    /** 编辑模式提交:forum.php?mod=post&action=edit */
    @Throws(Exception::class)
    private fun attemptEdit() {
        val message = if (etContent.text != null) etContent.text.toString().trim() else ""
        if (message.isEmpty()) {
            runOnUiThread {
                showError("请输入正文内容")
                resetPublishButton()
            }
            return
        }
        var fh: String? = null
        try {
            val form = HttpClient.getInstance().get(
                HttpClient.BASE_URL +
                        "forum.php?mod=post&action=edit&tid=" + editTid + "&pid=" + editPid + "&mobile=2"
            )
            fh = ForumParser.parseFormhash(form)
        } catch (ignored: Exception) {
        }
        if (TextUtils.isEmpty(fh)) fh = currentFormhash
        if (TextUtils.isEmpty(fh)) {
            runOnUiThread {
                showError("获取安全验证失败，请重试")
                resetPublishButton()
            }
            return
        }

        val params = HashMap<String, String>()
        params["formhash"] = fh!!
        params["subject"] = if (etTitle.text == null) "" else etTitle.text.toString().trim()
        params["message"] = message
        params["editsubmit"] = "yes"
        // 附件同样要带上 attachnew,否则编辑会丢附件
        synchronized(attachFiles) {
            for (af in attachFiles) {
                if (af != null && af.aid != null && af.aid!!.matches(Regex("\\d+"))) {
                    params["attachnew[" + af.aid + "][description]"] = ""
                }
            }
        }

        val url = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2" +
                "&handlekey=editform&tid=" + editTid + "&pid=" + editPid + "&page=1"
        val response = HttpClient.getInstance().post(url, params)

        val ok = response != null && !ForumParser.isLoginPage(response)
                && (response.contains("viewthread") || response.contains("thread-$editTid")
                || response.contains("成功") || response.contains("回复"))
        if (ok) {
            runOnUiThread {
                postedDone = true
                Toast.makeText(this@PostActivity, "已保存修改", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        } else {
            val err = extractErrorFromResponse(response)
            runOnUiThread {
                showError(err)
                resetPublishButton()
            }
        }
    }

    private fun extractTid(html: String?): String {
        try {
            var m = Pattern.compile("tid=(\\d+)").matcher(html!!)
            if (m.find()) return m.group(1)
            m = Pattern.compile("thread-(\\d+)").matcher(html)
            if (m.find()) return m.group(1)
        } catch (ignored: Exception) {
        }
        return ""
    }

    private fun extractErrorFromResponse(html: String?): String {
        if (html == null || html.isEmpty()) return "发帖失败，请稍后重试"
        try {
            val doc = Jsoup.parse(html)
            val alerts = doc.select("div.alert_error, div.alert_info, p.alert, div.alert")
            if (!alerts.isEmpty()) {
                val text = alerts.first()!!.text()
                if (text != null && !text.isEmpty()) return text
            }
            val title = doc.title()
            if (title != null && !title.isEmpty() && !title.contains("发表帖子") && !title.contains("MT论坛"))
                return title
        } catch (ignored: Exception) {
        }
        return "发帖失败，请检查内容或稍后重试"
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun resetPublishButton() {
        btnPublish.isEnabled = true
        btnPublish.setText(R.string.post_publish)
    }

    companion object {
        private const val REQUEST_FILE_PICK = 1001
        private const val REQUEST_IMAGE_PICK = 1002

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
    }
}
