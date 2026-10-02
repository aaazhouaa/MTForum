package com.solosu.mtforum.ui.space

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.widget.Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivityEditProfileBinding
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.widget.FrostedGlassHelper

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern

/** 编辑资料导航页。头像在本页使用原生选图、规范化和上传流程。 */
class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var httpClient: HttpClient
    private lateinit var executor: ExecutorService
    private var avatarUploadInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FrostedGlassHelper.applyToCardViews(binding.root, this)
        httpClient = HttpClient.getInstance()
        executor = Executors.newSingleThreadExecutor()

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 不再打开 WebView：直接交给系统照片选择器，然后走原生上传。
        binding.btnEditAvatar.setOnClickListener { pickAvatar() }
        binding.btnEditBaseInfo.setOnClickListener { openNativeForm("base") }
        binding.btnEditContact.setOnClickListener { openNativeForm("contact") }
        binding.btnEditInfo.setOnClickListener { openNativeForm("info") }
    }

    private fun pickAvatar() {
        if (!httpClient.isLoggedIn()) {
            Toast.makeText(this, R.string.login_required_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        try {
            startActivityForResult(intent, REQUEST_PICK_AVATAR)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统相册", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_AVATAR || resultCode != Activity.RESULT_OK
            || data == null || data.data == null
        ) {
            return
        }
        uploadAvatar(data.data!!)
    }

    /**
     * 统一转成受尺寸、体积和透明通道约束的 JPEG，避免旧 Discuz 头像接口拒绝 HEIC、
     * 透明 PNG、超大照片或厂商私有编码。服务端会继续负责最终的头像裁剪。
     */
    @Throws(Exception::class)
    private fun normalizeAvatar(uri: Uri): File {
        val maxSide = 1024
        val source = ImageDecoder.createSource(contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, ignored ->
            val largest = Math.max(info.size.width, info.size.height)
            if (largest > maxSide) decoder.setTargetSize(
                Math.max(1, Math.round(info.size.width * maxSide / largest.toFloat())),
                Math.max(1, Math.round(info.size.height * maxSide / largest.toFloat()))
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (decoded == null) throw IllegalStateException("无法解码所选图片")

        // JPEG 不支持透明像素；先在白底画布压平，保证真实字节、后缀和 MIME 一致。
        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(decoded, 0f, 0f, null)
        decoded.recycle()

        val output = ByteArrayOutputStream()
        var quality = 90
        do {
            output.reset()
            flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)
            quality -= 5
        } while (output.size() > 400 * 1024 && quality >= 55)
        flattened.recycle()
        if (output.size() == 0) throw IllegalStateException("图片压缩失败")

        val dir = File(cacheDir, "avatar_uploads")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建临时目录")
        val file = File(dir, "avatar_" + System.currentTimeMillis() + ".jpg")
        FileOutputStream(file).use { stream ->
            stream.write(output.toByteArray())
            stream.flush()
        }
        return file
    }

    private fun uploadAvatar(uri: Uri) {
        if (avatarUploadInProgress) return
        avatarUploadInProgress = true
        binding.btnEditAvatar.isEnabled = false
        Toast.makeText(this, "正在处理并上传头像…", Toast.LENGTH_SHORT).show()
        executor.execute {
            var imageFile: File? = null
            try {
                imageFile = normalizeAvatar(uri)
                ensureLoggedIn()
                val avatarPageUrl = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar"
                var page = httpClient.getDesktop(avatarPageUrl)
                if (ForumParser.isLoginPage(page)) page = httpClient.get(avatarPageUrl)
                if (ForumParser.isLoginPage(page)) {
                    throw IllegalStateException("登录已过期，请重新登录")
                }

                // Discuz 的头像设置页只是承载 UCenter Flash/裁剪组件；真正写入头像的接口是
                // 组件携带 input、agent、ucapi 参数调用的 rectavatar，而不是 home.php 的 &ref 路由。
                // 之前先 POST &ref，会在本站路由下稳定返回 HTTP 404，且异常使备用流程无法运行。
                val response = uploadAvatarViaUCenter(imageFile, page)
                if (!isAvatarUploadSuccess(response)) {
                    val detail = extractAvatarMessage(response)
                    throw IllegalStateException(if (TextUtils.isEmpty(detail)) "服务器未确认头像上传" else detail)
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "头像上传成功，请返回个人页面刷新查看", Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                val message = if (TextUtils.isEmpty(e.message)) "网络异常，请稍后重试" else e.message
                runOnUiThread {
                    Toast.makeText(
                        this, "头像上传失败：" + message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (imageFile != null && imageFile.exists()) imageFile.delete()
                runOnUiThread {
                    avatarUploadInProgress = false
                    binding.btnEditAvatar.isEnabled = true
                }
            }
        }
    }

    private fun ensureLoggedIn() {
        if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager()
        if (!httpClient.isLoggedIn() || TextUtils.isEmpty(httpClient.getCookieHeader())) {
            throw IllegalStateException("未检测到登录状态，请重新登录")
        }
        httpClient.syncToCookieManager()
    }

    @Throws(Exception::class)
    private fun uploadAvatarViaUCenter(imageFile: File, avatarPage: String): String {
        // input、agent、ucapi 是头像页面下发的短期授权参数；优先使用同一次页面请求，
        // 避免重新请求页面造成 token 或登录态不一致。
        var page = avatarPage
        var input = extractJsAvatarValue(page, "input")
        var agent = extractJsAvatarValue(page, "agent")
        var ucApi = extractJsAvatarValue(page, "ucapi")
        if (TextUtils.isEmpty(input) || TextUtils.isEmpty(agent)) {
            page = httpClient.get(HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2")
            input = extractJsAvatarValue(page, "input")
            agent = extractJsAvatarValue(page, "agent")
            if (TextUtils.isEmpty(ucApi)) ucApi = extractJsAvatarValue(page, "ucapi")
        }
        if (TextUtils.isEmpty(input) || TextUtils.isEmpty(agent)) {
            throw IllegalStateException("未获取到头像上传授权参数")
        }
        if (TextUtils.isEmpty(ucApi)) ucApi = HttpClient.BASE_URL + "uc_server"
        if (ucApi.startsWith("//")) {
            ucApi = "https:$ucApi"
        } else if (!ucApi.startsWith("http://") && !ucApi.startsWith("https://")) {
            ucApi = HttpClient.BASE_URL + (if (ucApi.startsWith("/")) ucApi.substring(1) else ucApi)
        }
        if (ucApi.endsWith("/")) ucApi = ucApi.substring(0, ucApi.length - 1)

        // ImageDecoder 默认可能返回 Hardware Bitmap；而 avatarBase64 需要使用 Canvas 软件绘制。
        // 即使临时文件是本地 JPEG，也必须在此二次解码时明确要求 SOFTWARE allocator。
        val original = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(imageFile)
        ) { decoder, info, source -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        if (original == null || original.isRecycled) {
            throw IllegalStateException("无法解码头像图片")
        }
        try {
            val form = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("avatar1", avatarBase64(original, 200))
                .addFormDataPart("avatar2", avatarBase64(original, 120))
                .addFormDataPart("avatar3", avatarBase64(original, 48))
                .addFormDataPart("input", input)
                .addFormDataPart("agent", agent)
                .addFormDataPart("appid", "1")
                .build()
            val request = okhttp3.Request.Builder()
                .url("$ucApi/index.php?m=user&a=rectavatar&base64=yes")
                .header("User-Agent", HttpClient.USER_AGENT)
                .header("Cookie", httpClient.getCookieHeader())
                .header("Referer", HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2")
                .post(form).build()
            httpClient.executeDirect(request).use { response ->
                if (!response.isSuccessful) throw IllegalStateException("备用上传请求失败（HTTP " + response.code + "）")
                return if (response.body == null) "" else response.body!!.string()
            }
        } finally {
            original.recycle()
        }
    }

    private fun extractJsAvatarValue(html: String?, name: String): String {
        if (TextUtils.isEmpty(html)) return ""
        var matcher = Pattern.compile(
            "(?:var\\s+)?" + Pattern.quote(name) +
                    "\\s*=\\s*['\"]([^'\"]+)['\"]"
        ).matcher(html!!)
        if (matcher.find()) return matcher.group(1)
        matcher = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&,\"']+)").matcher(html)
        return try {
            if (matcher.find()) java.net.URLDecoder.decode(matcher.group(1), "UTF-8") else ""
        } catch (ignored: Exception) {
            ""
        }
    }

    private fun avatarBase64(source: Bitmap?, size: Int): String {
        if (source == null || source.isRecycled) {
            throw IllegalArgumentException("头像源图片不可用")
        }
        val edge = Math.min(source.width, source.height)
        val targetSize = Math.max(1, Math.min(size, edge))

        // 不能用 createBitmap(source, 0, 0, source.getWidth(), source.getHeight()) 后回收：
        // 当源图本身为正方形时，框架可直接返回 source；回收中间图会把 source 一并回收，
        // 导致随后生成 avatar2/avatar3 时抛出 "cannot use a recycled source"。
        // 始终新建目标 Bitmap 并以 Canvas 绘制，整个过程中 source 的所有权仍属于调用方。
        val target = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        try {
            val left = (source.width - edge) / 2
            val top = (source.height - edge) / 2
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                source,
                android.graphics.Rect(left, top, left + edge, top + edge),
                android.graphics.Rect(0, 0, targetSize, targetSize), null
            )
            val output = ByteArrayOutputStream()
            if (!target.compress(Bitmap.CompressFormat.JPEG, 90, output) || output.size() == 0) {
                throw IllegalStateException("头像图片编码失败")
            }
            return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            target.recycle()
        }
    }

    private fun isAvatarUploadSuccess(response: String?): Boolean {
        if (TextUtils.isEmpty(response)) return false
        val trimmed = response!!.trim()
        // UCenter rectavatar 的标准成功响应就是纯文本 "1"。
        if ("1" == trimmed) return true
        val lower = trimmed.lowercase()
        val message = extractAvatarMessage(response).lowercase()
        if (lower.contains("<error") || message.contains("失败") || message.contains("错误")) return false
        return message.contains("成功") || message.contains("success") || lower.contains("上传成功")
                || lower.contains("success") || lower.contains("avatar1") || lower.contains("avatar2")
                || lower.contains("avatar3") || lower.contains("<url>") || lower.contains("<avatar")
    }

    private fun extractAvatarMessage(response: String?): String {
        if (TextUtils.isEmpty(response)) return ""
        val matcher = Pattern.compile("(?is)<(?:message|error)[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</(?:message|error)>")
            .matcher(response!!)
        if (matcher.find()) return android.text.Html.fromHtml(
            matcher.group(1),
            android.text.Html.FROM_HTML_MODE_LEGACY
        ).toString().trim()
        return response.replace(Regex("(?s)<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun openNativeForm(tab: String) {
        if (!httpClient.isLoggedIn()) {
            Toast.makeText(this, R.string.login_required_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, NativeProfileFormActivity::class.java)
        intent.putExtra("tab", tab)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executor != null) executor.shutdownNow()
    }

    companion object {
        private const val REQUEST_PICK_AVATAR = 5101
    }
}
