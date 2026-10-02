package com.solosu.mtforum.ui.space

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivityNativeProfileFormBinding
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.google.android.material.tabs.TabLayout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern
import com.solosu.mtforum.ui.widget.DialogHelper

/**
 * 原生编辑资料页面
 *
 * 完全去除 WebView 套壳，使用原生表单编辑 Discuz! 基本资料、
 * 联系方式、个人信息，并支持原生选图上传修改头像。
 *
 * 字段说明（基于抓包分析）：
 * - 基本资料 (op=base): occupation, resideprovince/residecity, realname,
 *   birthprovince/birthcity, birthyear/birthmonth/birthday, gender
 * - 联系方式 (op=contact): qq, mobile
 * - 个人信息 (op=info): bio, customstatus, sightml, timeoffset
 * - 每个字段对应的隐私开关: privacy[字段名]
 *
 * 头像上传: 使用 avatarform 的 Filedata 字段上传图片文件，
 * 以 multipart/form-data 形式 POST 到 home.php?mod=spacecp&ac=avatar&ref
 */
class NativeProfileFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNativeProfileFormBinding
    private lateinit var httpClient: HttpClient
    private lateinit var executor: ExecutorService

    private var currentFormhash: String? = null
    private var currentOp = "base"
    private var currentAvatarUrl: String? = null

    // 存储当前表单参数值
    private val formValues: MutableMap<String, String> = HashMap()
    // 存储当前表单的隐私开关值
    private val privacyValues: MutableMap<String, String> = HashMap()
    // 存储所有表单字段定义
    private val formFields: MutableList<FormField> = ArrayList()

    // 头像选择临时文件
    private var tempAvatarFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNativeProfileFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        httpClient = HttpClient.getInstance()
        executor = Executors.newSingleThreadExecutor()

        // ★ 修复：移除返回箭头图标（仅保留点击返回功能）
        // Discuz! 原生模板默认带箭头，显式设置为 null 可移除
        binding.toolbar.setNavigationIcon(null)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 处理传入的 tab 参数
        val intent = intent
        var initialTab = if (intent != null) intent.getStringExtra("tab") else null
        if (initialTab == null) initialTab = "base"

        val tabIndex: Int
        when (initialTab) {
            "contact" -> tabIndex = 1
            "info" -> tabIndex = 2
            else -> tabIndex = 0
        }

        // Tab 切换
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> currentOp = "base"
                    1 -> currentOp = "contact"
                    2 -> currentOp = "info"
                }
                showLoading(true)
                loadProfileForm(currentOp)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })

        // 保存按钮
        binding.btnSave.setOnClickListener { saveCurrentForm() }

        // 加载初始 tab
        if (tabIndex > 0) {
            val tab = binding.tabLayout.getTabAt(tabIndex)
            if (tab != null) tab.select()
        } else {
            showLoading(true)
            loadProfileForm("base")
        }
    }

    // ================================================================
    // 1. 从服务器加载表单 HTML 并解析填写
    // ================================================================

    private fun loadProfileForm(op: String) {
        executor.execute {
            try {
                val url = HttpClient.BASE_URL +
                        "home.php?mod=spacecp&ac=profile&op=" + op
                val html = httpClient.get(url)

                if (html == null || html.isEmpty()) {
                    runOnUiThread {
                        showLoading(false)
                        Toast.makeText(this, "加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // 提取 formhash
                var fh = ForumParser.parseFormhash(html)
                if (fh == null) {
                    // 从 JS 变量中提取
                    val m = Pattern
                        .compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]")
                        .matcher(html)
                    if (m.find()) fh = m.group(1)
                }
                val formhash = fh ?: ""

                // 解析当前值
                val values = parseCurrentValues(html, op)
                val privacies = parsePrivacyValues(html, op)

                // 解析字段定义
                val fields = buildFormFields(op)

                runOnUiThread {
                    currentFormhash = formhash
                    formValues.clear()
                    formValues.putAll(values)
                    privacyValues.clear()
                    privacyValues.putAll(privacies)
                    formFields.clear()
                    formFields.addAll(fields)
                    renderForm(op)
                    showLoading(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this, "加载失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 从桌面版 HTML 中解析当前已填写的字段值
     */
    private fun parseCurrentValues(html: String?, op: String): MutableMap<String, String> {
        val values = HashMap<String, String>()
        try {
            val doc = Jsoup.parse(html!!)

            // 解析 input 字段
            val inputs = doc.select("input[type=text], input[type=hidden], input:not([type])")
            for (el in inputs) {
                val name = el.attr("name")
                val `val` = el.attr("value")
                if (!name.isEmpty() && !name.startsWith("privacy") && name != "formhash"
                    && name != "profilesubmit" && name != "mod" && name != "srchtxt"
                ) {
                    values[name] = `val` ?: ""
                }
            }

            // 解析 select 当前选中值
            val selects = doc.select("select")
            for (sel in selects) {
                val name = sel.attr("name")
                if (name.isEmpty()) continue
                val selected = sel.select("option[selected]").first()
                if (selected != null) {
                    values[name] = selected.attr("value")
                } else {
                    // 取第一个 option 的值
                    val first = sel.select("option").first()
                    if (first != null) {
                        values[name] = first.attr("value")
                    }
                }
            }

            // 解析 textarea
            val textareas = doc.select("textarea")
            for (ta in textareas) {
                val name = ta.attr("name")
                if (!name.isEmpty()) {
                    values[name] = ta.text()
                }
            }

            // 特殊处理：从 HTML 中提取手机号等已填内容
            // 手机号在 input 中已经有 value 属性，上面已提取
        } catch (ignored: Exception) {
        }

        return values
    }

    /**
     * 解析隐私开关值
     */
    private fun parsePrivacyValues(html: String?, op: String): MutableMap<String, String> {
        val privacies = HashMap<String, String>()
        try {
            val doc = Jsoup.parse(html!!)
            // 查找所有 select[name^=privacy] 的当前选中值
            val privacySelects = doc.select("select[name^=privacy]")
            for (sel in privacySelects) {
                val name = sel.attr("name")
                val selected = sel.select("option[selected]").first()
                if (selected != null) {
                    privacies[name] = selected.attr("value")
                } else {
                    // 默认公开
                    privacies[name] = "0"
                }
            }
        } catch (ignored: Exception) {
        }
        return privacies
    }

    /**
     * 构建每个 tab 下的表单字段定义
     */
    private fun buildFormFields(op: String): MutableList<FormField> {
        val list = ArrayList<FormField>()
        when (op) {
            "base" -> {
                list.add(FormField("occupation", "职业", FormField.TYPE_TEXT, ""))
                list.add(FormField("resideprovince", "居住地（省）", FormField.TYPE_TEXT, ""))
                list.add(FormField("residecity", "居住地（市）", FormField.TYPE_TEXT, ""))
                list.add(FormField("realname", "真实姓名", FormField.TYPE_TEXT, ""))
                list.add(FormField("birthprovince", "出生地（省）", FormField.TYPE_TEXT, ""))
                list.add(FormField("birthcity", "出生地（市）", FormField.TYPE_TEXT, ""))
                list.add(FormField("birthyear", "出生年份", FormField.TYPE_TEXT, ""))
                list.add(FormField("birthmonth", "出生月份", FormField.TYPE_TEXT, ""))
                list.add(FormField("birthday", "出生日期", FormField.TYPE_TEXT, ""))
                list.add(
                    FormField(
                        "gender", "性别", FormField.TYPE_SELECT,
                        arrayOf(arrayOf("0", "保密"), arrayOf("1", "男"), arrayOf("2", "女"))
                    )
                )
            }

            "contact" -> {
                list.add(FormField("qq", "QQ", FormField.TYPE_TEXT, ""))
                list.add(FormField("mobile", "手机号", FormField.TYPE_TEXT, ""))
            }

            "info" -> {
                list.add(FormField("customstatus", "自定义头衔", FormField.TYPE_TEXT, ""))
                list.add(FormField("bio", "自我介绍", FormField.TYPE_TEXT_AREA, ""))
                list.add(FormField("sightml", "个人签名", FormField.TYPE_TEXT_AREA, ""))
            }
        }
        return list
    }

    // ================================================================
    // 2. 渲染原生表单
    // ================================================================

    private fun renderForm(op: String) {
        binding.formContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // 针对联系方式 tab，第一行显示用户名（不可编辑）
        if (op == "contact") {
            val row = inflater.inflate(R.layout.item_form_field_readonly, binding.formContainer, false)
            val tvLabel = row.findViewById<TextView>(R.id.tv_field_label)
            val tvValue = row.findViewById<TextView>(R.id.tv_field_value)
            tvLabel.text = "用户名"
            val selfName = com.solosu.mtforum.session.UserSessionManager
                .getInstance().getUsername(this)
            tvValue.text = if (TextUtils.isEmpty(selfName)) "" else selfName
            binding.formContainer.addView(row)
        }

        // 遍历字段并创建 UI
        for (field in formFields) {
            var row: View? = null
            val privacyKey = "privacy[" + field.name + "]"
            val currentVal = if (formValues.containsKey(field.name)) formValues[field.name]!! else ""
            val currentPrivacy = if (privacyValues.containsKey(privacyKey)) privacyValues[privacyKey]!! else "0"

            when (field.type) {
                FormField.TYPE_TEXT -> {
                    row = inflater.inflate(R.layout.item_form_field_text, binding.formContainer, false)
                    setupTextField(row, field, currentVal, privacyKey, currentPrivacy)
                }

                FormField.TYPE_TEXT_AREA -> {
                    row = inflater.inflate(R.layout.item_form_field_textarea, binding.formContainer, false)
                    setupTextAreaField(row, field, currentVal, privacyKey, currentPrivacy)
                }

                FormField.TYPE_SELECT -> {
                    row = inflater.inflate(R.layout.item_form_field_select, binding.formContainer, false)
                    setupSelectField(row, field, currentVal, privacyKey, currentPrivacy)
                }
            }
            if (row != null) {
                binding.formContainer.addView(row)
            }
        }
    }

    private fun setupTextField(row: View, field: FormField, currentVal: String,
                               privacyKey: String, currentPrivacy: String) {
        val tvLabel = row.findViewById<TextView>(R.id.tv_field_label)
        val etInput = row.findViewById<EditText>(R.id.et_field_input)
        val spPrivacy = row.findViewById<Spinner>(R.id.sp_privacy)

        tvLabel.text = field.label
        etInput.setText(currentVal)
        etInput.tag = field.name

        // 设置隐私开关适配器
        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy)
    }

    private fun setupTextAreaField(row: View, field: FormField, currentVal: String,
                                   privacyKey: String, currentPrivacy: String) {
        val tvLabel = row.findViewById<TextView>(R.id.tv_field_label)
        val etInput = row.findViewById<EditText>(R.id.et_field_textarea)
        val spPrivacy = row.findViewById<Spinner>(R.id.sp_privacy)

        tvLabel.text = field.label
        etInput.setText(currentVal)
        etInput.tag = field.name

        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy)
    }

    private fun setupSelectField(row: View, field: FormField, currentVal: String,
                                 privacyKey: String, currentPrivacy: String) {
        val tvLabel = row.findViewById<TextView>(R.id.tv_field_label)
        val spSelect = row.findViewById<Spinner>(R.id.sp_field_select)
        val spPrivacy = row.findViewById<Spinner>(R.id.sp_privacy)

        tvLabel.text = field.label

        // 设置选项
        val displayValues = arrayOfNulls<String>(field.options.size)
        val optionValues = arrayOfNulls<String>(field.options.size)
        for (i in field.options.indices) {
            optionValues[i] = field.options[i][0]
            displayValues[i] = field.options[i][1]
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, displayValues
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSelect.adapter = adapter

        // 设置当前选中值
        for (i in optionValues.indices) {
            if (optionValues[i] == currentVal) {
                spSelect.setSelection(i)
                break
            }
        }

        spSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                formValues[field.name] = optionValues[position]!!
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy)
    }

    /**
     * 设置隐私开关 Spinner
     * 隐私值：0=公开, 1=好友可见, 3=保密
     */
    private fun setupPrivacySpinner(spPrivacy: Spinner, privacyKey: String, currentPrivacy: String) {
        val privacyDisplay = arrayOf("公开", "仅好友可见", "保密")
        val privacyVals = arrayOf("0", "1", "3")

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, privacyDisplay
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spPrivacy.adapter = adapter

        // 设置当前值
        for (i in privacyVals.indices) {
            if (privacyVals[i] == currentPrivacy) {
                spPrivacy.setSelection(i)
                break
            }
        }

        val key = privacyKey
        spPrivacy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                privacyValues[key] = privacyVals[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    // ================================================================
    // 3. 头像上传
    // ================================================================

    private fun showAvatarPicker() {
        val options = arrayOf("从相册选择", "拍照")
        val alertDialog: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("选择头像")
            .setItems(options) { dialog, which ->
                if (which == 0) {
                    // 从相册选择
                    val intent = Intent(
                        Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    )
                    intent.type = "image/*"
                    startActivityForResult(intent, REQUEST_PICK_IMAGE)
                } else {
                    // 拍照
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (intent.resolveActivity(packageManager) != null) {
                        tempAvatarFile = File(
                            cacheDir,
                            "avatar_" + System.currentTimeMillis() + ".jpg"
                        )
                        intent.putExtra(
                            MediaStore.EXTRA_OUTPUT,
                            Uri.fromFile(tempAvatarFile)
                        )
                        startActivityForResult(intent, REQUEST_CAMERA)
                    } else {
                        Toast.makeText(this, "未找到相机应用", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog, this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == REQUEST_PICK_IMAGE && data != null && data.data != null) {
            val imageUri = data.data
            val tempFile = copyUriToCache(imageUri!!, "avatar_pick.jpg")
            if (tempFile != null) {
                uploadAvatar(tempFile)
            }
        } else if (requestCode == REQUEST_CAMERA) {
            if (tempAvatarFile != null && tempAvatarFile!!.exists()) {
                uploadAvatar(tempAvatarFile!!)
            }
        }
    }

    /**
     * 上传头像 — V4 双重方案（彻底修复 Cookie 同步问题）
     *
     * ★ 关键修复：
     * 1. 先检查 OkHttp CookieJar 中是否有有效 Cookie，避免被 WebView 空 Cookie 覆盖
     * 2. 使用 HttpClient 自己的 Cookie 而非依赖自动 CookieJar
     * 3. 强制携带 Cookie Header 和桌面 UA
     */
    private fun uploadAvatar(imageFile: File) {
        showLoading(true)
        executor.execute {
            try {
                // ★ 关键修复：先检查 OkHttp CookieJar 是否已有有效 Cookie
                // 如果已有有效 Cookie，则不覆盖；如果为空，才从 WebView 同步
                val hasValidCookie = httpClient.isLoggedIn()
                if (!hasValidCookie) {
                    // OkHttp CookieJar 为空，从 WebView 同步
                    httpClient.syncFromCookieManager()
                }

                // 同步后再次检查
                if (!httpClient.isLoggedIn()) {
                    throw IllegalStateException("未检测到登录态，请先登录")
                }

                // 确保 Cookie 双向同步（OkHttp -> WebView）
                httpClient.syncToCookieManager()

                val avatarPageUrl = HttpClient.BASE_URL +
                        "home.php?mod=spacecp&ac=avatar"

                // 使用桌面 UA 获取头像页面（formhash 更稳定）
                var avatarPageHtml = httpClient.getDesktop(avatarPageUrl)

                // 如果桌面 UA 返回登录页，用移动 UA 重试
                if (ForumParser.isLoginPage(avatarPageHtml)) {
                    avatarPageHtml = httpClient.get(avatarPageUrl)
                }

                // 解析 formhash
                var fh = ForumParser.parseFormhash(avatarPageHtml)
                if (TextUtils.isEmpty(fh)) {
                    val m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]")
                        .matcher(if (avatarPageHtml != null) avatarPageHtml else "")
                    if (m.find()) fh = m.group(1)
                }

                // 双重检查：必须是有效页面且有 formhash
                if (ForumParser.isLoginPage(avatarPageHtml) || TextUtils.isEmpty(fh)) {
                    throw IllegalStateException("登录已过期或未获取到头像页面令牌")
                }

                // 头像上传 URL
                val uploadUrl = HttpClient.BASE_URL +
                        "home.php?mod=spacecp&ac=avatar&ref"
                val mime = getContentMime(imageFile)
                val mediaType = mime.toMediaTypeOrNull()
                val fileBody = imageFile.asRequestBody(mediaType)

                // ★ 关键：构建 multipart 请求体
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("formhash", fh!!)
                    .addFormDataPart("avatarsubmit", "yes")
                    .addFormDataPart("upload", "1")
                    .addFormDataPart("Filedata", imageFile.name, fileBody)
                    .build()

                // ★ 关键：显式获取 Cookie Header，避免依赖 CookieJar 自动处理
                val cookieHeader = httpClient.getCookieHeader()
                if (TextUtils.isEmpty(cookieHeader)) {
                    throw IllegalStateException("未找到论坛登录Cookie，请先在软件内重新登录")
                }

                // 构建请求：强制使用桌面 UA 和显式 Cookie Header
                val request = okhttp3.Request.Builder()
                    .url(uploadUrl)
                    .header("User-Agent", HttpClient.DESKTOP_USER_AGENT)
                    .header("Cookie", cookieHeader)
                    .header("Referer", avatarPageUrl)
                    .header("Origin", HttpClient.BASE_URL)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .post(requestBody)
                    .build()

                // 执行请求
                var response: String
                httpClient.executeDirect(request).use { resp ->
                    response = if (resp.body != null) resp.body!!.string() else ""
                }

                // 解析响应
                val result = response.trim()
                val serverMessage = extractAvatarXmlMessage(result)
                val success = isAvatarUploadSuccess(result)

                // ★ 关键修复：如果主方案失败，自动切换到 UCenter API
                if (!success) {
                    runOnUiThread {
                        showLoading(false)
                        Toast.makeText(this, "标准上传失败，尝试备用方案...", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        uploadAvatarViaUCenter(imageFile)
                        return@execute
                    } catch (e: Exception) {
                        runOnUiThread {
                            showLoading(false)
                            Toast.makeText(this, "头像上传失败: " + e.message, Toast.LENGTH_SHORT).show()
                        }
                        return@execute
                    }
                }

                runOnUiThread {
                    showLoading(false)
                    if (success) {
                        Toast.makeText(this, "头像上传成功！请返回刷新页面查看", Toast.LENGTH_SHORT).show()
                    } else {
                        var detail = if (TextUtils.isEmpty(serverMessage)) result else serverMessage
                        detail = detail.replace(Regex("\\s+"), " ").trim()
                        Toast.makeText(
                            this, "头像上传失败: " +
                                    detail.substring(0, Math.min(160, detail.length)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(
                        this, "头像上传失败: " + e.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getContentMime(file: File?): String {
        val name = if (file != null) file.name.lowercase() else ""
        if (name.endsWith(".png")) return "image/png"
        if (name.endsWith(".gif")) return "image/gif"
        if (name.endsWith(".webp")) return "image/webp"
        return "image/jpeg"
    }

    /**
     * Discuz! 头像接口返回的是 XML。提取 message，避免把整段 XML 直接显示给用户。
     */
    private fun extractAvatarXmlMessage(response: String?): String {
        if (TextUtils.isEmpty(response)) return ""
        try {
            var m = Pattern.compile("(?is)<message[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</message>")
                .matcher(response!!)
            if (m.find()) {
                return android.text.Html.fromHtml(
                    m.group(1), android.text.Html.FROM_HTML_MODE_LEGACY
                )
                    .toString().trim()
            }
            m = Pattern.compile("(?is)<error[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</error>")
                .matcher(response)
            if (m.find()) return m.group(1).trim()
        } catch (ignored: Exception) {
        }
        return ""
    }

    private fun isAvatarUploadSuccess(response: String?): Boolean {
        if (TextUtils.isEmpty(response)) return false
        val lower = response!!.lowercase()
        val message = extractAvatarXmlMessage(response).lowercase()
        // Discuz XML 成功响应通常含 message=上传成功，或返回头像地址/文件名。
        if (lower.contains("<error") || message.contains("失败") || message.contains("错误")) {
            return false
        }
        return message.contains("成功") ||
                message.contains("success") ||
                lower.contains("上传成功") ||
                lower.contains("success") ||
                lower.contains("avatar1") ||
                lower.contains("avatar2") ||
                lower.contains("avatar3") ||
                lower.contains("<url>") ||
                lower.contains("<avatar")
    }

    /**
     * 备用方案：UCenter rectavatar API（base64方式）
     */
    @Throws(Exception::class)
    private fun uploadAvatarViaUCenter(imageFile: File) {
        val avatarPageHtml = httpClient.get(
            HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2"
        )

        var inputParam = ""
        var agentParam = ""
        var ucApi = ""

        var dataMatcher: Matcher

        dataMatcher = Pattern.compile("input=([^&,]+)").matcher(avatarPageHtml)
        if (dataMatcher.find()) inputParam = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8")
        dataMatcher = Pattern.compile("agent=([^&,]+)").matcher(avatarPageHtml)
        if (dataMatcher.find()) agentParam = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8")
        dataMatcher = Pattern.compile("ucapi=([^&,]+)").matcher(avatarPageHtml)
        if (dataMatcher.find()) ucApi = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8")

        if (inputParam.isEmpty()) {
            dataMatcher = Pattern.compile("var\\s+input\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml)
            if (dataMatcher.find()) inputParam = dataMatcher.group(1)
        }
        if (agentParam.isEmpty()) {
            dataMatcher = Pattern.compile("var\\s+agent\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml)
            if (dataMatcher.find()) agentParam = dataMatcher.group(1)
        }
        if (ucApi.isEmpty()) {
            dataMatcher = Pattern.compile("var\\s+ucapi\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml)
            if (dataMatcher.find()) ucApi = dataMatcher.group(1)
        }

        var baseUcApi = ucApi
        if (TextUtils.isEmpty(baseUcApi)) {
            baseUcApi = HttpClient.BASE_URL + "uc_server"
        } else if (!baseUcApi.startsWith("http://") && !baseUcApi.startsWith("https://")) {
            if (baseUcApi.startsWith("//")) {
                baseUcApi = "https:$baseUcApi"
            } else {
                baseUcApi = HttpClient.BASE_URL + (if (baseUcApi.startsWith("/")) baseUcApi.substring(1) else baseUcApi)
            }
        }
        if (baseUcApi.endsWith("/")) baseUcApi = baseUcApi.substring(0, baseUcApi.length - 1)

        val rectavatarUrl = "$baseUcApi/index.php?m=user&a=rectavatar&base64=yes"

        val opts = BitmapFactory.Options()
        opts.inSampleSize = 1
        val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath, opts)
        if (originalBitmap == null) {
            runOnUiThread { Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show() }
            return
        }

        val imgW = originalBitmap.width
        val imgH = originalBitmap.height
        val avatar1Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 200, 250)
        val avatar2Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 120, 120)
        val avatar3Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 48, 48)
        originalBitmap.recycle()

        val builder = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("avatar1", avatar1Base64)
            .addFormDataPart("avatar2", avatar2Base64)
            .addFormDataPart("avatar3", avatar3Base64)
            .addFormDataPart("input", inputParam)
            .addFormDataPart("agent", agentParam)
            .addFormDataPart("appid", "1")

        val requestBody = builder.build()
        val request = okhttp3.Request.Builder()
            .url(rectavatarUrl)
            .header("User-Agent", HttpClient.USER_AGENT)
            .header("Cookie", httpClient.getCookieHeader())
            .header("Referer", HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2")
            .post(requestBody)
            .build()

        var rectResponse = ""
        httpClient.executeDirect(request).use { resp ->
            rectResponse = if (resp.body != null) resp.body!!.string() else ""
        }

        val finalResponse = rectResponse
        runOnUiThread {
            showLoading(false)
            if (finalResponse.contains("success")
                || finalResponse.contains("SUCCESS")
            ) {
                Toast.makeText(this, "头像上传成功！请返回刷新页面查看", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, "头像上传失败: " + finalResponse.substring(0, Math.min(100, finalResponse.length)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 生成指定尺寸的头像 JPEG base64 数据
     * 从原图中心截取正方形区域，缩放至目标尺寸
     */
    private fun generateAvatarBase64(src: Bitmap, srcW: Int, srcH: Int, maxW: Int, maxH: Int): String {
        try {
            // 取正方形：从中心截取
            val squareSize = Math.min(srcW, srcH)
            val x = (srcW - squareSize) / 2
            val y = (srcH - squareSize) / 2
            val square = Bitmap.createBitmap(src, x, y, squareSize, squareSize)

            // 缩放至目标尺寸
            var tw = Math.min(maxW, squareSize)
            var th = Math.min(maxH, squareSize)
            if (tw > th) th = tw
            else tw = th
            tw = Math.min(tw, 200)
            th = Math.min(th, 250)
            if (squareSize < tw) {
                tw = squareSize
                th = squareSize
            }
            val scaled = Bitmap.createScaledBitmap(square, tw, th, true)
            if (scaled !== square) square.recycle()

            // 转为 JPEG base64
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            scaled.recycle()

            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
    }

    // ================================================================
    // 4. 保存表单
    // ================================================================

    private fun saveCurrentForm() {
        // 先收集所有 EditText 的值
        collectCurrentValues()

        if (currentFormhash == null || currentFormhash!!.isEmpty()) {
            Toast.makeText(this, "表单状态异常，请重新加载", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)
        executor.execute {
            try {
                // 构造 POST 参数
                val postUrl = HttpClient.BASE_URL +
                        "home.php?mod=spacecp&ac=profile&op=" + currentOp
                val params = HashMap<String, String>()
                params["formhash"] = currentFormhash!!
                params["profilesubmit"] = "true"

                // 添加当前 tab 下的所有字段值
                for (field in formFields) {
                    val `val` = formValues[field.name]
                    if (`val` != null) {
                        params[field.name] = `val`
                    }
                    // 添加隐私开关
                    val privacyKey = "privacy[" + field.name + "]"
                    val privacyVal = privacyValues[privacyKey]
                    if (privacyVal != null) {
                        params[privacyKey] = privacyVal
                    }
                }

                val response = httpClient.post(postUrl, params)

                // 检查响应是否成功（包含 show_success 或提示成功信息）
                var success = false
                if (response != null) {
                    if (response.contains("show_success") || response.contains("资料更新成功")
                        || response.contains("success") || response.contains("成功")
                    ) {
                        success = true
                    }
                }

                val finalSuccess = success
                runOnUiThread {
                    showLoading(false)
                    if (finalSuccess) {
                        Toast.makeText(this, "资料更新成功！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "资料更新失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this, "保存失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 遍历当前所有可见的 EditText，收集值
     */
    private fun collectCurrentValues() {
        val container = binding.formContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is LinearLayout) {
                collectFromRow(child)
            }
        }
    }

    private fun collectFromRow(row: LinearLayout) {
        // 查找 EditText
        val et = row.findViewById<EditText>(R.id.et_field_input)
        if (et != null && et.tag is String) {
            formValues[et.tag as String] = et.text.toString()
            return
        }
        val eta = row.findViewById<EditText>(R.id.et_field_textarea)
        if (eta != null && eta.tag is String) {
            formValues[eta.tag as String] = eta.text.toString()
            return
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun getUid(): String {
        // 从 cookie 中提取 uid（Discuz! 的 home 等页面需要）
        // 这里从当前会话中尝试获取
        try {
            val html = httpClient.getDesktop(HttpClient.BASE_URL + "home.php?mod=spacecp")
            val m = Pattern
                .compile("uid=(\\d+)").matcher(html)
            if (m.find()) return m.group(1)
            // 尝试从 Cookie 中的 7ZiC_uid 获取
            val uidCookie = httpClient.getCookieValue(
                java.net.URI.create(HttpClient.BASE_URL).host, "7ZiC_uid"
            )
            if (uidCookie != null && !uidCookie.isEmpty()) return uidCookie
        } catch (ignored: Exception) {
        }
        return "0"
    }

    private fun copyUriToCache(uri: Uri, fileName: String): File? {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) return null
            val cacheFile = File(cacheDir, fileName)
            val fos = FileOutputStream(cacheFile)
            val buffer = ByteArray(8192)
            var len = inputStream.read(buffer)
            while (len != -1) {
                fos.write(buffer, 0, len)
                len = inputStream.read(buffer)
            }
            fos.close()
            inputStream.close()
            return cacheFile
        } catch (e: Exception) {
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executor != null && !executor.isShutdown) {
            executor.shutdownNow()
        }
    }

    // ================================================================
    // 内部类：表单字段定义
    // ================================================================

    class FormField {
        @JvmField
        var name: String
        @JvmField
        var label: String
        @JvmField
        var type: Int
        @JvmField
        var options: Array<Array<String>> // 仅 TYPE_SELECT 使用，[值, 显示文本]

        constructor(name: String, label: String, type: Int, defaultValue: String) {
            this.name = name
            this.label = label
            this.type = type
            this.options = arrayOf(arrayOf("", defaultValue))
        }

        constructor(name: String, label: String, type: Int, options: Array<Array<String>>) {
            this.name = name
            this.label = label
            this.type = type
            this.options = options
        }

        companion object {
            const val TYPE_TEXT = 0
            const val TYPE_TEXT_AREA = 1
            const val TYPE_SELECT = 2
        }
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001
        private const val REQUEST_CAMERA = 1002
    }
}
