package com.solosu.mtforum.ui.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * 支持从输入法剪切板和系统剪贴板直接粘贴/提交图片的输入框
 */
open class RichTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    var onImageReceivedListener: ((Uri) -> Boolean)? = null

    private val supportedMimeTypes = arrayOf(
        "image/*",
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/gif",
        "image/webp"
    )

    init {
        ViewCompat.setOnReceiveContentListener(this, supportedMimeTypes) { _, payload ->
            val split = payload.partition { item -> item.uri != null }
            val uriContent = split.first
            val remaining = split.second

            if (uriContent != null) {
                val clip = uriContent.clip
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    if (uri != null) {
                        onImageReceivedListener?.invoke(uri)
                    }
                }
            }
            remaining
        }
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        EditorInfoCompat.setContentMimeTypes(editorInfo, supportedMimeTypes)

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                try {
                    inputContentInfo.requestPermission()
                } catch (_: Exception) {
                    return@OnCommitContentListener false
                }
            }
            val uri = inputContentInfo.contentUri
            val handled = onImageReceivedListener?.invoke(uri) ?: false
            handled
        }
        return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
    }

    var onKeyPreImeListener: (() -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                // 用户按下返回键瞬间立即触发收起，防止输入法拦截吞掉事件
                if (onKeyPreImeListener?.invoke() == true) {
                    return true
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (onKeyPreImeListener?.invoke() == true) {
                    return true
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                var handledImage = false
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    val uri = item.uri
                    if (uri != null) {
                        val type = try {
                            context.contentResolver.getType(uri) ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        if (type.startsWith("image/") || uri.toString().contains("image")) {
                            onImageReceivedListener?.invoke(uri)
                            handledImage = true
                        }
                    }
                }
                if (handledImage) {
                    // 如果成功粘贴并处理了图片且无附加文本，直接返回 true 消费掉
                    val text = clip.getItemAt(0).text
                    if (text.isNullOrEmpty()) {
                        return true
                    }
                }
            }
        }
        return super.onTextContextMenuItem(id)
    }
}
