package com.solosu.mtforum.ui.widget

import android.content.Context
import android.text.Editable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * 把站内表情代码（`[呵呵]` 等）当作一个整体来删除的输入框。
 *
 * 表情是以 BBCode 代码文本插入的，默认的退格会一个字符一个字符地删，
 * 用户要按五下才能删掉一个「[呵呵]」。这里在退格/删除时若光标紧邻一段
 * 形如 `[xxx]` 的表情代码，就整段删掉。
 *
 * 有文本时才改写删除范围：光标在纯文本中时 InputConnection 自带
 * 删一个字符的行为，无需我们接管（也避免自绘 `\n` 与输入法行为不一致）。
 */
open class SmileyAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : RichTextInputEditText(context, attrs, defStyleAttr) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return SmileyInputConnection(base, this)
    }

    /** 光标前若是表情代码则整段删掉，返回是否已接管本次删除。 */
    private fun deleteSmileyAtCaret(before: Boolean): Boolean {
        val editable = text ?: return false
        var start = selectionStart
        var end = selectionEnd
        if (start != end) {
            // 有选区时先看选中的是不是整段表情代码，是就一并删掉
            val selected = editable.subSequence(Math.min(start, end), Math.max(start, end)).toString()
            if (!isSmileyCode(selected)) return false
            editable.delete(Math.min(start, end), Math.max(start, end))
            return true
        }
        val caret = start
        if (caret < 0) return false
        val range = if (before) {
            smileyRangeBefore(editable, caret)
        } else {
            smileyRangeAfter(editable, caret)
        } ?: return false
        editable.delete(range.first, range.second)
        return true
    }

    /** 光标左侧完整的一段表情代码区间；光标左边不是 `]` 时立即放弃。 */
    private fun smileyRangeBefore(editable: Editable, caret: Int): Pair<Int, Int>? {
        if (caret <= 0 || editable[caret - 1] != ']') return null
        val from = editable.toString().lastIndexOf('[', caret - 1)
        if (from < 0) return null
        // 代码里不允许出现 '['，中间出现即说明这不是一段连续的表情代码
        val code = editable.subSequence(from, caret).toString()
        if (!isSmileyCode(code)) return null
        return from to caret
    }

    /** 光标右侧完整的一段表情代码区间；光标右侧不是 `[` 时立即放弃。 */
    private fun smileyRangeAfter(editable: Editable, caret: Int): Pair<Int, Int>? {
        if (caret >= editable.length || editable[caret] != '[') return null
        val to = editable.toString().indexOf(']', caret)
        if (to < 0) return null
        val code = editable.subSequence(caret, to + 1).toString()
        if (!isSmileyCode(code)) return null
        return caret to (to + 1)
    }

    /**
     * 是否像一段表情代码（`[呵呵]` / `[#滑稽]` / `[doge思考]`）。
     *
     * 不拿表情目录来精确比对，是为了让面板未加载时也能整体删除；代价是需要排除
     * BBCode 标签名与含空白的普通方括号文字，降低误删风险。
     */
    private fun isSmileyCode(s: String): Boolean {
        if (s.length !in 3..16 || s[0] != '[' || s[s.length - 1] != ']') return false
        val body = s.substring(1, s.length - 1)
        if (body.isEmpty() || body.any { it == '[' || it.isWhitespace() }) return false
        if (body.any { it == '<' || it == '>' || it == '/' || it == '=' || it == '\\' }) return false
        if (RESERVED_CODES.contains(body.lowercase())) return false
        // 表情代码必带中文字符或 `#` 前缀，借此与用户手打的英文缩写区分
        return body.startsWith("#") || body.any { it.code > 127 }
    }

    private companion object {
        /** 与表情同形但不该整段删除的 BBCode 标签名。 */
        private val RESERVED_CODES = hashSetOf(
            "attach", "attachimg", "quote", "free", "hide", "code", "url", "img",
            "media", "flash", "b", "i", "u", "color", "size", "font", "align",
            "table", "tr", "td", "hr", "list", "audio", "video", "sup", "sub",
            "email", "backcolor", "qq"
        )
    }

    private class SmileyInputConnection(
        target: InputConnection,
        private val host: SmileyAwareEditText
    ) : InputConnectionWrapper(target, false) {

        /** 已接管过按下的事件，抬起时一并吞掉，避免输入法收到不配对的键事件。 */
        private var handledKeyCode = 0

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // 只接管「删一个字符」的场景（退格/删除键）；多字符删除按输入法原行为走
            if (beforeLength == 1 && afterLength == 0 && host.deleteSmileyAtCaret(true)) {
                return true
            }
            if (beforeLength == 0 && afterLength == 1 && host.deleteSmileyAtCaret(false)) {
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val del = event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
            if (del && event.action == KeyEvent.ACTION_DOWN) {
                if (host.deleteSmileyAtCaret(event.keyCode == KeyEvent.KEYCODE_DEL)) {
                    handledKeyCode = event.keyCode
                    return true
                }
            } else if (del && event.action == KeyEvent.ACTION_UP && handledKeyCode == event.keyCode) {
                handledKeyCode = 0
                return true
            }
            return super.sendKeyEvent(event)
        }
    }
}
