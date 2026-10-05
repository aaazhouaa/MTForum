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

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.solosu.mtforum.R
import com.solosu.mtforum.model.ForumCategory
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.DraftManager
import com.solosu.mtforum.util.AiLog
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

    // 发投票
    private lateinit var btnTabPost: TextView
    private lateinit var btnTabPoll: TextView
    private lateinit var llPollSection: LinearLayout
    private lateinit var llPollOptions: LinearLayout
    private lateinit var cbPollSingleBox: CheckBox
    private lateinit var etPollOptionsMultiline: TextInputEditText
    private lateinit var btnPollAddOption: View
    private lateinit var etPollExpiration: TextInputEditText

    // data
    private var selectedFid: String? = null
    private var selectedForumName: String? = null
    private var currentFormhash: String? = null
    private var currentUid: String? = null
    private var currentHash: String? = null

    /** 论坛真实表情集（从编辑器页解析后缓存） */
    private var smileyCatalog: MutableList<ForumParser.SmileySet>? = null
    private var postSmileySetIndex = 0
    private var smileyLoading = false
    private var smileyLoadFailed = false
    private var draftId = 0L
    private var postedDone = false

    // 发投票
    private var isPollMode = false
    private val pollOptionRows = ArrayList<TextInputEditText>()

    // 当前高亮的面板按钮 / 插入类型 chip
    private var highlightedTool: View? = null
    private var highlightedInsertChip: TextView? = null

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
        setupPostModeTabs()
        setupToolbarButtons()
        setupPublishButton()
        // 表情目录与版块无关，进入即加载，避免首次点表情时仍是占位图标
        loadSmileyCatalog()
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

        btnTabPost = findViewById(R.id.btn_tab_post)
        btnTabPoll = findViewById(R.id.btn_tab_poll)
        llPollSection = findViewById(R.id.ll_poll_section)
        llPollOptions = findViewById(R.id.ll_poll_options)
        cbPollSingleBox = findViewById(R.id.cb_poll_single_box)
        etPollOptionsMultiline = findViewById(R.id.et_poll_options_multiline)
        btnPollAddOption = findViewById(R.id.btn_poll_add_option)
        etPollExpiration = findViewById(R.id.et_poll_expiration)
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

    // ==================== 发表帖子 / 发投票 ====================

    private fun setupPostModeTabs() {
        btnTabPost.setOnClickListener { if (isPollMode) switchPostMode(false) }
        btnTabPoll.setOnClickListener { if (!isPollMode) switchPostMode(true) }
        cbPollSingleBox.setOnCheckedChangeListener { _, checked ->
            llPollOptions.visibility = if (checked) View.GONE else View.VISIBLE
            etPollOptionsMultiline.visibility = if (checked) View.VISIBLE else View.GONE
            btnPollAddOption.visibility = if (checked) View.GONE else View.VISIBLE
        }
        btnPollAddOption.setOnClickListener { addPollOptionRow() }
        updatePostModeTabs()
        addPollOptionRow()
        addPollOptionRow()
        addPollOptionRow()
    }

    private fun switchPostMode(poll: Boolean) {
        isPollMode = poll
        llPollSection.visibility = if (poll) View.VISIBLE else View.GONE
        etContent.hint = if (poll) "说点什么吧…（可选）" else getString(R.string.post_content_hint)
        if (poll) {
            hideAllPanels()
        } else {
            val t = if (etTitle.text != null) etTitle.text.toString().trim() else ""
            val c = if (etContent.text != null) etContent.text.toString().trim() else ""
            if (t.isEmpty() && c.isEmpty()) etTitle.requestFocus()
        }
        updatePostModeTabs()
    }

    private fun updatePostModeTabs() {
        val selected = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        for ((tv, isSel) in listOf(btnTabPost to !isPollMode, btnTabPoll to isPollMode)) {
            if (isSel) {
                tv.setBackgroundResource(R.drawable.bg_segment_pill_selected)
                tv.setTextColor(selected)
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    /** 新增一个投票选项输入行，上限 20 项；已有 2 项以上时才能删行 */
    private fun addPollOptionRow() {
        if (pollOptionRows.size >= MAX_POLL_OPTIONS) {
            Toast.makeText(this, "最多只能填写 20 个选项", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val et = TextInputEditText(this)
        et.hint = "填写投票选项"
        et.textSize = 14f
        et.setBackgroundResource(R.drawable.bg_edittext_border)
        et.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        et.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary))
        et.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint))
        val etLp = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
        et.layoutParams = etLp
        row.addView(et)

        val del = ImageView(this)
        val delSize = (20 * density).toInt()
        val delLp = LinearLayout.LayoutParams(delSize, delSize)
        delLp.marginStart = (10 * density).toInt()
        del.layoutParams = delLp
        del.setImageResource(R.drawable.ic_cross)
        del.setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
        androidx.core.widget.ImageViewCompat.setImageTintList(
            del, android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
        )
        del.setOnClickListener {
            if (pollOptionRows.size <= 2) {
                Toast.makeText(this, "投票至少需要 2 个选项", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pollOptionRows.remove(et)
            llPollOptions.removeView(row)
        }
        row.addView(del)

        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = (6 * density).toInt()
        row.layoutParams = rowLp
        pollOptionRows.add(et)
        llPollOptions.addView(row)
    }

    /** 收集投票选项：单框模式按行拆分，否则取各行的非空值 */
    private fun collectPollOptions(): ArrayList<String> {
        val result = ArrayList<String>()
        if (cbPollSingleBox.isChecked) {
            val raw = if (etPollOptionsMultiline.text != null)
                etPollOptionsMultiline.text.toString() else ""
            for (line in raw.split("\n")) {
                val v = line.trim()
                if (v.isNotEmpty()) result.add(v)
            }
        } else {
            for (et in pollOptionRows) {
                val v = if (et.text != null) et.text.toString().trim() else ""
                if (v.isNotEmpty()) result.add(v)
            }
        }
        return result
    }

    /** 发投票帖：special=1 + polloption[]，其余字段与普通发帖共用 */
    private fun attemptPostPoll(title: String, content: String) {
        val options = collectPollOptions()
        if (options.size < 2) {
            showError("投票至少需要 2 个选项")
            return
        }
        if (options.size > MAX_POLL_OPTIONS) {
            showError("最多只能填写 20 个选项")
            return
        }
        val maxChoicesRaw = findViewById<TextInputEditText>(R.id.et_poll_maxchoices)
            ?.text?.toString()?.trim() ?: ""
        val maxChoices = if (maxChoicesRaw.isEmpty()) 1 else (maxChoicesRaw.toIntOrNull() ?: 1)
        val expiration = etPollExpiration.text?.toString()?.trim() ?: ""

        btnPublish.isEnabled = false
        btnPublish.text = "发布中..."

        Thread {
            try {
                if (currentFormhash == null) loadFormhashSync()
                if (currentFormhash == null || currentFormhash!!.isEmpty()) {
                    runOnUiThread {
                        showError("获取安全验证失败，请重试")
                        resetPublishButton()
                    }
                    return@Thread
                }

                val params = HashMap<String, String>()
                params["formhash"] = currentFormhash!!
                params["subject"] = title
                params["message"] = content
                params["allownoticeauthor"] = "1"
                params["special"] = "1"
                if (cbAnonymous.isChecked) params["anonymous"] = "1"

                if (cbPollSingleBox.isChecked) {
                    params["tpolloption"] = "2"
                    params["polloptions"] = options.joinToString("\n")
                } else {
                    params["tpolloption"] = "1"
                }
                for (i in options.indices) {
                    params["polloption[" + i + "]"] = options[i]
                }
                params["maxchoices"] = maxChoices.toString()
                if (expiration.isNotEmpty()) params["expiration"] = expiration
                val swVisible = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sw_poll_visible)
                if (swVisible != null && swVisible.isChecked) params["visibilitypoll"] = "1"
                val swOvert = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.sw_poll_overt)
                if (swOvert != null && swOvert.isChecked) params["overt"] = "1"

                val submitUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" +
                        selectedFid + "&special=1&topicsubmit=yes"
                val response = HttpClient.getInstance().post(submitUrl, params)

                if (response != null && (response.contains("tid=") || response.contains("viewthread"))) {
                    runOnUiThread {
                        postedDone = true
                        if (draftId > 0) {
                            DraftManager.delete(this@PostActivity, draftId)
                            draftId = 0
                        }
                        Toast.makeText(this@PostActivity, "投票帖发布成功", Toast.LENGTH_SHORT).show()
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
                runOnUiThread {
                    showError(getString(R.string.network_error))
                    resetPublishButton()
                }
            }
        }.start()
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
                updateToolHighlight(btnAt)
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
            if (!visible) updateToolHighlight(btnAdvanced)
        }

        // 点击正文时自动收起快捷面板
        etContent.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) hideAllPanels()
        }
        etContent.setOnClickListener { hideAllPanels() }
    }

    // ---- 表情功能 ----

    /** 论坛真实表情集与版块无关，进页即预取，避免打开面板时只有占位内容 */
    private fun loadSmileyCatalog(force: Boolean = false) {
        if (!force && smileyCatalog != null) return
        if (smileyLoading) return
        smileyLoading = true
        val empty = findViewById<TextView>(R.id.tv_smiley_empty)
        empty?.text = "表情加载中…"
        empty?.setOnClickListener(null)
        Thread {
            var parsed: MutableList<ForumParser.SmileySet> = ArrayList()
            try {
                for (url in ForumParser.getSmileyScriptUrlCandidates()) {
                    parsed = ForumParser.parseSmileyCatalog(HttpClient.getInstance().get(url))
                    if (parsed.isNotEmpty()) break
                }
            } catch (ignored: Exception) {
            }
            val finalCatalog = parsed
            runOnUiThread {
                smileyLoading = false
                if (finalCatalog.isEmpty()) {
                    smileyLoadFailed = true
                    val e = findViewById<TextView>(R.id.tv_smiley_empty)
                    e?.text = "表情加载失败，点击重试"
                    e?.setOnClickListener { loadSmileyCatalog(force = true) }
                } else {
                    smileyLoadFailed = false
                    smileyCatalog = finalCatalog
                    if (llSmileyPanel.visibility == View.VISIBLE) renderPostSmileySet(finalCatalog)
                }
            }
        }.start()
    }

    /** 渲染论坛真实表情（当前分类 + 底部分类切换），点击插入对应表情代码 */
    private fun renderPostSmileySet(catalog: MutableList<ForumParser.SmileySet>) {
        val rv = findViewById<RecyclerView>(R.id.rv_smiley) ?: return
        val tabs = findViewById<LinearLayout>(R.id.ll_smiley_tabs)
        val empty = findViewById<TextView>(R.id.tv_smiley_empty)
        empty?.visibility = View.GONE
        rv.visibility = View.VISIBLE
        val idx = postSmileySetIndex.coerceIn(0, catalog.size - 1)
        postSmileySetIndex = idx
        val items = catalog[idx].items
        rv.layoutManager = GridLayoutManager(this, 6)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            override fun onCreateViewHolder(@NonNull parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val size = (40 * resources.displayMetrics.density).toInt()
                val iv = ImageView(parent.context)
                iv.layoutParams = RecyclerView.LayoutParams(size, size)
                val padding = (4 * resources.displayMetrics.density).toInt()
                iv.setPadding(padding, padding, padding, padding)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.isClickable = true
                iv.setBackgroundResource(android.R.drawable.list_selector_background)
                return object : RecyclerView.ViewHolder(iv) {}
            }

            override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView as ImageView
                val smiley = items[position]
                Glide.with(this@PostActivity).load(smiley.url).into(iv)
                iv.setOnClickListener { insertIntoContent(smiley.code) }
            }

            override fun getItemCount(): Int = items.size
        }
        tabs?.removeAllViews()
        if (catalog.size <= 1 || tabs == null) return
        for (i in catalog.indices) {
            val tv = TextView(this)
            tv.text = if (catalog[i].name.isEmpty()) "表情" + (i + 1) else catalog[i].name
            tv.textSize = 13f
            tv.setPadding((14 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt())
            if (i == idx) {
                tv.setTextColor(com.solosu.mtforum.util.ThemeManager.getThemeColor(this))
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener {
                postSmileySetIndex = i
                renderPostSmileySet(catalog)
            }
            tabs.addView(tv)
        }
    }

    /** 主题色选中态底衬：浅色主题色填充 + 描边 */
    private fun selectedPanelBackground(): android.graphics.drawable.GradientDrawable {
        val themeColor = com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 0x1F))
            setStroke((1.2f * resources.displayMetrics.density).toInt(),
                androidx.core.graphics.ColorUtils.setAlphaComponent(themeColor, 0x59))
        }
    }

    /** 同步工具栏按钮的高亮状态，传入 null 表示全部取消 */
    private fun updateToolHighlight(target: View?) {
        if (highlightedTool !== target) {
            highlightedTool?.let {
                it.setBackgroundResource(R.drawable.bg_post_tool_btn)
                (it as? android.view.ViewGroup)?.getChildAt(0)?.let { icon ->
                    androidx.core.widget.ImageViewCompat.setImageTintList(
                        icon as ImageView,
                        android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
                        )
                    )
                }
            }
        }
        highlightedTool = target
        target?.let {
            it.background = selectedPanelBackground()
            (it as? android.view.ViewGroup)?.getChildAt(0)?.let { icon ->
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    icon as ImageView,
                    android.content.res.ColorStateList.valueOf(
                        com.solosu.mtforum.util.ThemeManager.getThemeColor(this)
                    )
                )
            }
        }
    }

    private fun toggleSmileyPanel() {
        hideAllPanelsExcept(llSmileyPanel)
        if (llSmileyPanel.visibility == View.VISIBLE) {
            llSmileyPanel.visibility = View.GONE
            return
        }
        llSmileyPanel.visibility = View.VISIBLE
        updateToolHighlight(btnSmiley)

        // 论坛真实表情（与回复弹窗同一套数据）；取不到时给出重试入口
        val catalog = smileyCatalog
        if (catalog != null && catalog.isNotEmpty() && catalog[0].items.isNotEmpty()) {
            renderPostSmileySet(catalog)
            return
        }
        val rv = findViewById<RecyclerView>(R.id.rv_smiley)
        val empty = findViewById<TextView>(R.id.tv_smiley_empty)
        rv?.visibility = View.GONE
        findViewById<LinearLayout>(R.id.ll_smiley_tabs)?.removeAllViews()
        if (smileyLoadFailed) {
            empty?.visibility = View.VISIBLE
            empty?.text = "表情加载失败，点击重试"
            empty?.setOnClickListener { loadSmileyCatalog(force = true) }
        } else {
            empty?.visibility = View.VISIBLE
            empty?.text = "表情加载中…"
            empty?.setOnClickListener(null)
            loadSmileyCatalog()
        }
    }

    // ---- 插入功能（9 项内容类型面板，与网页端一致）----
    private fun toggleInsertPanel() {
        val visible = llInsertPanel.visibility == View.VISIBLE
        hideAllPanels()
        if (visible) return
        llInsertPanel.visibility = View.VISIBLE
        updateToolHighlight(btnInsert)
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
        updateInsertChipHighlight(type)
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

    /** 插入类型 chip 的主题色选中态，同步更新上/当前项 */
    private fun updateInsertChipHighlight(type: Int) {
        val ids = intArrayOf(
            R.id.btn_ins_link, R.id.btn_ins_image, R.id.btn_ins_audio, R.id.btn_ins_video,
            R.id.btn_ins_flash, R.id.btn_ins_quote, R.id.btn_ins_code, R.id.btn_ins_free, R.id.btn_ins_hide
        )
        for (i in ids.indices) {
            val chip = findViewById<TextView>(ids[i]) ?: continue
            if (i + 1 == type) {
                chip.background = selectedPanelBackground()
                chip.setTextColor(com.solosu.mtforum.util.ThemeManager.getThemeColor(this))
                highlightedInsertChip = chip
            } else {
                chip.setBackgroundResource(R.drawable.bg_post_forum_chip)
                chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            }
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
     * 解析 Discuz 上传响应。forum_upload::uploadmsg() 的真实格式：
     *   simple=2：DISCUZUPLOAD|是否图片(0/1)|状态码|aid|是否图片(-1/0/1)|附件路径|文件名|大小上限
     *   simple=1：DISCUZUPLOAD|状态码|aid|是否图片|大小上限
     * 仅状态码为 0 时 aid 有效；失败时按状态码给出原因（原实现只认 parts[1]=="error"，
     * 永远匹配不上真实失败格式，所有失败都被归为笼统的「上传失败」）。
     */
    private fun parseDiscuzUploadResponse(response: String?): String? {
        val text = response?.let { Regex("(?s)<[^>]+>").replace(it, "") }?.trim() ?: ""
        if (text.isEmpty()) {
            AiLog.e("upload", "附件上传响应为空（misc_swfupload 鉴权失败时会直接 exit）")
            runOnUiThread { Toast.makeText(this@PostActivity, "附件上传失败：校验未通过，请重新登录后重试", Toast.LENGTH_SHORT).show() }
            return null
        }
        AiLog.i("upload", "附件上传响应: " + AiLog.clip(text, 300))
        if (!text.startsWith("DISCUZUPLOAD|")) {
            AiLog.e("upload", "未识别的上传响应（可能是风控挑战页）")
            runOnUiThread { Toast.makeText(this@PostActivity, "附件上传失败，请稍后重试", Toast.LENGTH_SHORT).show() }
            return null
        }
        val parts = text.split("\\|".toRegex(), -1).toTypedArray()
        val statusIndex = if (parts.size >= 8) 2 else 1
        val aidIndex = statusIndex + 1
        if (parts.size <= aidIndex) return null
        val status = parts[statusIndex].trim().toIntOrNull()
        val aidPart = parts[aidIndex].trim()
        if (status == 0 && aidPart.matches(Regex("\\d+"))) return aidPart
        if (status != null && status != 0) {
            val reason = uploadStatusReason(status)
            runOnUiThread { Toast.makeText(this@PostActivity, reason, Toast.LENGTH_SHORT).show() }
        }
        return null
    }

    /** Discuz forum_upload 的状态码文案（见 source/class/forum/forum_upload.php）。 */
    private fun uploadStatusReason(status: Int): String {
        return when (status) {
            1 -> "此类型附件不允许上传"
            2 -> "文件上传失败或为空"
            3 -> "附件超过大小限制"
            4, 5 -> "该格式附件被禁止或超过格式大小限制"
            6 -> "今日附件数量已达上限"
            7 -> "不是有效的图片文件"
            8, 9 -> "附件保存失败，请稍后重试"
            10 -> "上传校验失败，请重新登录后重试"
            11 -> "今日附件总大小已达上限"
            12 -> "文件名含敏感词，请改名后重试"
            13 -> "图片尺寸不符合要求"
            else -> "附件上传失败（错误码 $status）"
        }
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

    /** 从 content:// 解析真实文件名：此前直接用 uri.path 末段，相册返回纯数字 id 时会拼出无扩展名的名字 */
    private fun getFileNameFromUri(uri: Uri): String {
        var name = ""
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0) name = cursor.getString(column) ?: ""
            }
        } catch (ignored: Exception) {
        } finally {
            cursor?.close()
        }
        if (TextUtils.isEmpty(name)) {
            val path = uri.lastPathSegment
            if (!TextUtils.isEmpty(path)) name = path!!
        }
        name = Regex("[\\\\/:*?\"<>|]").replace(name, "_")
        if (TextUtils.isEmpty(name)) name = "attachment"
        if (!name.matches(Regex("(?i).*\\.[a-z0-9]{2,5}$"))) name += ".dat"
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
        updateToolHighlight(null)
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
                    cacheSmileyCatalogFromEditor(desktopHtml)
                }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    /** 桌面编辑器页内联了论坛表情数据，从中解析并缓存，供表情面板使用 */
    private fun cacheSmileyCatalogFromEditor(html: String?) {
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
                cacheSmileyCatalogFromEditor(desktopHtml)
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
        if (content.isEmpty() && !isPollMode) {
            showError("请输入正文内容")
            return
        }

        if (isPollMode) {
            attemptPostPoll(title, content)
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
        findViewById<View>(R.id.ll_post_mode_tabs)?.visibility = View.GONE
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

        private const val MAX_POLL_OPTIONS = 20
    }
}
