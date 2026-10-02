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
    private lateinit var tvSelectedForum: TextView
    private lateinit var etContent: TextInputEditText
    private lateinit var cbAnonymous: CheckBox
    private lateinit var btnPublish: MaterialButton
    private var btnAiOptimize: MaterialButton? = null
    private var btnAiPost: MaterialButton? = null
    private lateinit var tvError: TextView

    // 五大功能按钮 + 图片按钮
    private lateinit var btnSmiley: TextView
    private lateinit var btnAt: TextView
    private lateinit var btnInsert: TextView
    private lateinit var btnImage: TextView
    private lateinit var btnAttach: TextView
    private lateinit var btnAdvanced: TextView
    private lateinit var llSmileyPanel: LinearLayout
    private lateinit var llAtPanel: LinearLayout
    private lateinit var etAtUsername: TextInputEditText
    private lateinit var btnAtInsert: TextView
    private lateinit var llAdvancedOptions: LinearLayout
    private lateinit var llAttachList: LinearLayout
    private lateinit var llImagePreview: LinearLayout

    // data
    private var selectedFid: String? = null
    private var selectedForumName: String? = null
    private var currentFormhash: String? = null
    private var currentUid: String? = null
    private var currentHash: String? = null
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
        val tvDrafts = findViewById<TextView>(R.id.tv_drafts)
        if (tvDrafts != null) tvDrafts.setOnClickListener { showDraftsDialog() }

        etTitle = findViewById(R.id.et_title)
        tvTitleCount = findViewById(R.id.tv_title_count)
        llCircleSelector = findViewById(R.id.ll_circle_selector)
        tvSelectedForum = findViewById(R.id.tv_selected_forum)
        etContent = findViewById(R.id.et_content)
        cbAnonymous = findViewById(R.id.cb_anonymous)
        btnPublish = findViewById(R.id.btn_publish)
        btnAiOptimize = findViewById(R.id.btn_ai_optimize)
        btnAiPost = findViewById(R.id.btn_ai_post)
        tvError = findViewById(R.id.tv_error)

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
        // 1. 表情
        btnSmiley.setOnClickListener { toggleSmileyPanel() }

        // 2. @朋友
        btnAt.setOnClickListener {
            hideAllPanels()
            llAtPanel.visibility = View.VISIBLE
        }
        btnAtInsert.setOnClickListener {
            val name = if (etAtUsername.text != null)
                etAtUsername.text.toString().trim() else ""
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            insertIntoContent("@ $name ")
            llAtPanel.visibility = View.GONE
            etAtUsername.setText("")
            Toast.makeText(this, "已插入 @$name", Toast.LENGTH_SHORT).show()
        }

        // 3. 插入（引用/代码/Free/Hide）
        btnInsert.setOnClickListener { showInsertDialog() }

        // 4. 🖼 图片上传（从相册选图，与网页端对齐）
        btnImage.setOnClickListener {
            hideAllPanels()
            pickImage()
        }

        // 5. 文件附件
        btnAttach.setOnClickListener { pickFile() }

        // 5. 高级
        btnAdvanced.setOnClickListener {
            hideAllPanels()
            llAdvancedOptions.visibility =
                if (llAdvancedOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    // ---- 表情功能 ----
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

    // ---- 插入功能 ----
    private fun showInsertDialog() {
        hideAllPanels()
        val items = arrayOf("引用", "代码", "免费信息", "隐藏内容")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("插入内容")
        builder.setItems(items) { dialog, which ->
            var bbcode = ""
            when (which) {
                0 -> bbcode = "\n[quote]请输入引用内容[/quote]\n"   // 引用
                1 -> bbcode = "\n[code]请输入代码[/code]\n"          // 代码
                2 -> bbcode = "\n[free]请输入免费内容[/free]\n"      // 免费信息
                3 -> bbcode = "\n[hide]请输入隐藏内容[/hide]\n"      // 隐藏内容
            }
            insertIntoContent(bbcode)
            Toast.makeText(this, "已插入" + items[which], Toast.LENGTH_SHORT).show()
        }
        val alertDialog: android.app.Dialog = builder.show()
        DialogHelper.applyToAlertDialog(alertDialog, this)
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
        // 筛选出图片类型的附件
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

        var row: LinearLayout? = null
        val colCount = 4
        val density = resources.displayMetrics.density.toInt()
        val imgSize = (75 * density)

        for (i in images.indices) {
            if (i % colCount == 0) {
                row = LinearLayout(this)
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, 0, 0, (4 * density))
                llImagePreview.addView(row)
            }

            val af = images[i]
            val iv = ImageView(this)
            val lp = LinearLayout.LayoutParams(imgSize, imgSize)
            lp.setMargins(0, 0, (4 * density), 0)
            if (row != null) {
                if (i % colCount < colCount - 1) lp.weight = 1f
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(getColor(R.color.background_secondary))

                Glide.with(this)
                    .load(af.path)
                    .placeholder(android.graphics.drawable.ColorDrawable(getColor(R.color.background_secondary)))
                    .error(android.graphics.drawable.ColorDrawable(getColor(R.color.divider)))
                    .into(iv)

                // 点击查看大图
                iv.setOnClickListener {
                    // 简单预览：Toast提示点击删除
                    Toast.makeText(this@PostActivity, "点击移除图片", Toast.LENGTH_SHORT).show()
                }
                // 长按删除
                iv.setOnLongClickListener {
                    attachFiles.remove(af)
                    updateAttachList()
                    updateImagePreview()
                    true
                }

                row.addView(iv)
            }
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
        if (attachFiles.isEmpty()) {
            llAttachList.visibility = View.GONE
            return
        }
        llAttachList.visibility = View.VISIBLE
        for (i in attachFiles.indices) {
            val af = attachFiles[i]
            val isImage = af.name!!.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$"))
            if (isImage) {
                // 图片附件：显示缩略图预览
                val iv = ImageView(this)
                val size = (120 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(8, 8, 8, 8)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(getColor(R.color.background_secondary))
                Glide.with(this)
                    .load(af.path)
                    .placeholder(android.graphics.drawable.ColorDrawable(getColor(R.color.background_secondary)))
                    .error(android.graphics.drawable.ColorDrawable(getColor(R.color.divider)))
                    .into(iv)
                // 点击删除
                val idx = i
                iv.setOnClickListener {
                    attachFiles.removeAt(idx)
                    updateAttachList()
                }
                llAttachList.addView(iv)
            } else {
                // 非图片附件：显示文件名
                val tv = TextView(this)
                tv.text = "📎 " + af.name
                tv.setPadding(8, 8, 8, 8)
                tv.setTextSize(13f)
                tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_delete, 0)
                val idx = i
                tv.setOnClickListener {
                    attachFiles.removeAt(idx)
                    updateAttachList()
                }
                llAttachList.addView(tv)
            }
        }
    }

    // ---- 高级功能 ----
    // llAdvancedOptions 中已包含：cb_hidden_replies, cb_reverse_order, cb_usesig, cb_smiley_off, cb_bbcode_off

    // ---- 面板管理 ----
    private fun hideAllPanels() {
        llSmileyPanel.visibility = View.GONE
        llAtPanel.visibility = View.GONE
        llAdvancedOptions.visibility = View.GONE
    }

    private fun hideAllPanelsExcept(except: View) {
        if (except !== llSmileyPanel) llSmileyPanel.visibility = View.GONE
        if (except !== llAtPanel) llAtPanel.visibility = View.GONE
        if (except !== llAdvancedOptions) llAdvancedOptions.visibility = View.GONE
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
        val allForums = ArrayList<ForumCategory.Forum>()
        val groupNames = ArrayList<String>()
        val groupRanges = ArrayList<IntArray>()

        if (categories != null) {
            for (category in categories) {
                if (category.forums == null || category.forums!!.isEmpty()) continue
                val start = allForums.size
                allForums.addAll(category.forums!!)
                val end = allForums.size - 1
                groupNames.add(category.name ?: "")
                groupRanges.add(intArrayOf(start, end))
            }
        }
        if (allForums.isEmpty()) {
            Toast.makeText(this, "暂无可用版块", Toast.LENGTH_SHORT).show()
            return
        }

        val totalItems = allForums.size + groupNames.size
        val displayItems = arrayOfNulls<String>(totalItems)
        val isHeader = BooleanArray(totalItems)
        val forumIndex = IntArray(totalItems)

        var pos = 0
        for (g in groupNames.indices) {
            displayItems[pos] = "╲╱ " + groupNames[g]
            isHeader[pos] = true
            forumIndex[pos] = -1
            pos++
            val range = groupRanges[g]
            for (i in range[0]..range[1]) {
                displayItems[pos] = "  " + allForums[i].name
                isHeader[pos] = false
                forumIndex[pos] = i
                pos++
            }
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("选择版块")
        builder.setAdapter(
            object : android.widget.ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1, displayItems
            ) {
                override fun isEnabled(position: Int): Boolean {
                    return !isHeader[position]
                }
            }
        ) { dialog, which ->
            val idx = forumIndex[which]
            if (idx >= 0) {
                selectForum(allForums[idx])
            }
        }

        val dialog = builder.create()
        dialog.show()
        DialogHelper.applyToAlertDialog(dialog, this)
        if (dialog.listView != null) {
            dialog.listView!!.setOnItemClickListener { parent, view, position, id ->
                if (isHeader[position]) return@setOnItemClickListener
                val idx = forumIndex[position]
                if (idx >= 0) {
                    selectForum(allForums[idx])
                    dialog.dismiss()
                }
            }
        }
    }

    private fun selectForum(forum: ForumCategory.Forum) {
        selectedFid = forum.fid
        selectedForumName = forum.name
        tvSelectedForum.text = selectedForumName
        tvSelectedForum.visibility = View.VISIBLE
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
                }
            } catch (ignored: Exception) {
            }
        }.start()
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
        draftId = e.id
        if (e.title != null) etTitle.setText(e.title)
        if (e.content != null) etContent.setText(e.content)
        if (e.fid != null && !e.fid!!.isEmpty()) {
            selectedFid = e.fid
            selectedForumName = e.forumName ?: ""
            tvSelectedForum.text = if (selectedForumName!!.isEmpty()) "已选版块 " + e.fid else selectedForumName
        }
        cbAnonymous.isChecked = e.anonymous
        Toast.makeText(this, "已恢复上次草稿", Toast.LENGTH_SHORT).show()
    }

    private fun saveDraftNow() {
        if (postedDone) return
        val title = if (etTitle.text != null) etTitle.text.toString().trim() else ""
        val content = if (etContent.text != null) etContent.text.toString().trim() else ""
        if (title.isEmpty() && content.isEmpty() && selectedFid == null) {
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
            tvSelectedForum.text = if (selectedForumName!!.isEmpty()) "已选版块 " + e.fid else selectedForumName
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
        AlertDialog.Builder(this)
            .setTitle("草稿箱(" + l.size + ") · 点条目载入")
            .setItems(items) { d, which -> loadDraftToEditor(l[which]) }
            .setNeutralButton("删单条") { d, w -> showDraftDeleteDialog() }
            .setNegativeButton("清空") { d, w ->
                DraftManager.clear(this@PostActivity)
                draftId = 0
                Toast.makeText(this@PostActivity, "草稿箱已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDraftDeleteDialog() {
        val l = DraftManager.list(this)
        if (l.isEmpty()) {
            Toast.makeText(this, "草稿箱是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOfNulls<String>(l.size)
        for (i in l.indices) items[i] = draftLabel(l[i])
        AlertDialog.Builder(this)
            .setTitle("点要删除的草稿")
            .setItems(items) { d, which ->
                val e = l[which]
                DraftManager.delete(this@PostActivity, e.id)
                if (e.id == draftId) draftId = 0
                Toast.makeText(this@PostActivity, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun setupPublishButton() {
        btnPublish.setOnClickListener { attemptPost() }
        if (btnAiOptimize != null) btnAiOptimize!!.setOnClickListener { aiOptimizeContent() }
        if (btnAiPost != null) btnAiPost!!.setOnClickListener { aiAutoPost() }
    }

    private fun attemptPost() {
        // build73: 编辑模式走 action=edit,与发新帖完全分开
        if (isEditMode()) {
            tvError.visibility = View.GONE
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
        tvError.visibility = View.GONE

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
            if (tvSelectedForum != null) tvSelectedForum.text = fname
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
        // 编辑模式:不给改版块,AI 按钮也用不上
        if (llCircleSelector != null) llCircleSelector.visibility = View.GONE
        if (btnAiOptimize != null) btnAiOptimize!!.visibility = View.GONE
        if (btnAiPost != null) btnAiPost!!.visibility = View.GONE
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
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun resetPublishButton() {
        btnPublish.isEnabled = true
        btnPublish.setText(R.string.post_publish)
    }

    // ==================== build70: AI 发帖 ====================

    /** 「优化」: 用 AI 把当前标题+正文改写得更规范易读 */
    private fun aiOptimizeContent() {
        if (!com.solosu.mtforum.ai.AiConfigManager.isConfigured(this)) {
            showError("请先在 AI 配置中填写接口地址与 API Key")
            return
        }
        val title = if (etTitle.text == null) "" else etTitle.text.toString().trim()
        val content = if (etContent.text == null) "" else etContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            showError("请先输入标题或正文")
            return
        }
        setAiButtonsBusy(true, "优化中…")
        Thread({
            var res: String? = null
            try {
                val sys = "你是论坛发帖优化助手。基于用户给出的标题与正文，在不改变原意、不编造事实的前提下，优化语句使其更通顺、层次更清晰；" +
                        "可适当补充排版(分段)。输出格式严格如下三行标签：\n" +
                        "【标题】优化后的标题(仅一行)\n【正文】优化后的正文\n" +
                        "不要输出除标签外的任何解释。"
                val u = StringBuilder()
                u.append("原标题：").append(title).append("\n\n原正文：\n").append(content)
                res = com.solosu.mtforum.ai.AiClient.simpleChat(this, sys, u.toString())
            } catch (e: Exception) {
                res = null
            }
            val out = res
            runOnUiThread {
                setAiButtonsBusy(false, null)
                if (TextUtils.isEmpty(out)) {
                    showError("AI 优化失败，请检查 AI 配置或网络")
                    return@runOnUiThread
                }
                val nt = extractLabeled(out!!, "标题")
                var nb = extractLabeled(out, "正文")
                if (TextUtils.isEmpty(nt) && TextUtils.isEmpty(nb)) {
                    // 模型没按标签输出: 整体作为正文
                    nb = com.solosu.mtforum.ai.AiSummarizeActivity.extractText(out)
                }
                if (!TextUtils.isEmpty(nt)) etTitle.setText(nt)
                if (!TextUtils.isEmpty(nb)) etContent.setText(nb)
                Toast.makeText(this@PostActivity, "已优化", Toast.LENGTH_SHORT).show()
            }
        }, "ai-optimize").start()
    }

    /** 「AI发帖」: 2. AI 生成标题+正文 → 3. 自动提交 */
    private fun aiAutoPost() {
        if (selectedFid == null) {
            showError("请先选择版块")
            return
        }
        if (!com.solosu.mtforum.ai.AiConfigManager.isConfigured(this)) {
            showError("请先在 AI 配置中填写接口地址与 API Key")
            return
        }
        val title = if (etTitle.text == null) "" else etTitle.text.toString().trim()
        val content = if (etContent.text == null) "" else etContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            showError("请先输入主题或要点，AI 将据此生成帖子")
            return
        }
        setAiButtonsBusy(true, "AI 生成中…")
        Thread({
            var res: String? = null
            try {
                val sys = "你是 MT 论坛(技术向)的发帖助手。根据用户给出的主题或要点，生成一篇可直接发布的帖子。要求：" +
                        "1. 标题 <=30 字，概括主题，不要加【】等括号标签；" +
                        "2. 正文用中文、分段、条理清晰，技术内容可用编号步骤；" +
                        "3. 允许结合你自己的知识补充，但不得编造与主题无关的信息；" +
                        "4. 不要使用 Markdown 记号(如 # 、 * 、 ` )；" +
                        (if (TextUtils.isEmpty(selectedForumName)) "" else ("当前版块：" + selectedForumName + "。")) +
                        "输出格式严格：\n【标题】一行标题\n【正文】帖子正文\n不要输出除标签外的任何解释。"
                val u = StringBuilder()
                if (!title.isEmpty()) u.append("主题/标题：").append(title).append("\n")
                if (!content.isEmpty()) u.append("要点/正文：\n").append(content)
                res = com.solosu.mtforum.ai.AiClient.simpleChat(this, sys, u.toString())
            } catch (e: Exception) {
                res = null
            }
            val out = res
            runOnUiThread {
                setAiButtonsBusy(false, null)
                if (TextUtils.isEmpty(out)) {
                    showError("AI 生成失败，请检查 AI 配置或网络")
                    return@runOnUiThread
                }
                var nt = extractLabeled(out!!, "标题")
                var nb = extractLabeled(out, "正文")
                if (TextUtils.isEmpty(nb)) nb = com.solosu.mtforum.ai.AiSummarizeActivity.extractText(out)
                if (TextUtils.isEmpty(nt)) nt = "分享"
                etTitle.setText(nt)
                etContent.setText(nb)
                Toast.makeText(this@PostActivity, "已生成，正在发布…", Toast.LENGTH_SHORT).show()
                // 立即自动提交
                if (!btnPublish.isEnabled) return@runOnUiThread
                attemptPost()
            }
        }, "ai-autopost").start()
    }

    private fun setAiButtonsBusy(busy: Boolean, label: String?) {
        if (btnAiOptimize != null) btnAiOptimize!!.isEnabled = !busy
        if (btnAiPost != null) btnAiPost!!.isEnabled = !busy
        if (btnPublish != null) btnPublish.isEnabled = !busy
        if (btnAiPost != null) btnAiPost!!.text = if (busy && label != null) label else "AI发帖"
    }

    /** 从形如「【标题】xxx【正文】yyy」的输出里取某标签后的内容 */
    private fun extractLabeled(ai: String?, label: String): String {
        if (ai == null) return ""
        val m = Pattern
            .compile("【" + Pattern.quote(label) + "】\\s*([\\s\\S]*?)(?=【|$)")
            .matcher(ai)
        if (m.find()) return m.group(1).trim()
        return ""
    }

    companion object {
        private const val REQUEST_FILE_PICK = 1001
        private const val REQUEST_IMAGE_PICK = 1002

        /** 帖子内容最大注入长度(字符) */
        private const val AI_POST_CONTENT_MAX = 6000
    }
}
