package com.solosu.mtforum.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.solosu.mtforum.R
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史 AI 会话列表。
 * 列出全部会话，支持新建 / 点击续聊 / 长按删除。
 */
class AiSessionListActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        sv.fitsSystemWindows = true
        sv.setBackgroundColor(ContextCompat.getColor(this, R.color.background))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        sv.addView(root)
        setContentView(sv)

        // 顶栏
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        )
        bar.setPadding(dp(8), 0, dp(12), 0)

        val back = ImageView(this)
        back.setImageResource(R.drawable.ic_back)
        back.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
        back.setPadding(dp(8), dp(8), dp(8), dp(8))
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this)
        title.text = "历史会话"
        title.textSize = 17f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setPadding(dp(6), 0, dp(6), 0)
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(title, tlp)

        val newBtn = TextView(this)
        newBtn.text = "新建会话"
        newBtn.textSize = 14f
        newBtn.setTextColor(com.solosu.mtforum.util.ThemeManager.getThemeColor(this))
        newBtn.setPadding(dp(12), dp(4), dp(12), dp(4))
        newBtn.setOnClickListener {
            val id = AiSessionStore.createSession(this)
            openChat(id, true)
        }
        bar.addView(newBtn)

        root.addView(bar)

        // 列表容器
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(10), dp(12), dp(16))
        root.addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val sessions = AiSessionStore.listSessions(this)
        if (sessions.isEmpty()) {
            val empty = TextView(this)
            empty.text = "还没有会话。点右上角「新建会话」开始。\n也可以在聊天页里直接开聊，会自动保存。"
            empty.textSize = 14f
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
            empty.gravity = Gravity.CENTER
            empty.setPadding(dp(24), dp(40), dp(24), dp(40))
            list.addView(empty)
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        for (s in sessions) {
            list.addView(buildItem(s, fmt))
        }
    }

    private fun buildItem(s: AiSessionStore.Session, fmt: SimpleDateFormat): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = FrostedGlassDrawable.create(this, 14f)
        val pad = dp(14)
        card.setPadding(pad, dp(12), pad, dp(12))
        val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        card.layoutParams = lp

        // 标题行
        val title = TextView(this)
        title.text = if (TextUtils.isEmpty(s.title)) "新会话" else s.title!!
        title.textSize = 15f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        card.addView(title)

        // 副信息行：消息数 + 时间
        val meta = TextView(this)
        meta.text = s.messageCount.toString() + " 条消息 · " + fmt.format(Date(s.updatedAt))
        meta.textSize = 12f
        meta.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        meta.setPadding(0, dp(4), 0, 0)
        card.addView(meta)

        card.setOnClickListener { openChat(s.id, false) }
        card.setOnLongClickListener {
            confirmDelete(s)
            true
        }
        return card
    }

    private fun confirmDelete(s: AiSessionStore.Session) {
        val b = android.app.AlertDialog.Builder(this)
        b.setTitle("删除会话")
        b.setMessage("删除「" + s.title + "」？对话将无法恢复。")
        b.setNegativeButton("取消", null)
        b.setPositiveButton("删除") { _, _ ->
            AiSessionStore.deleteSession(this, s.id)
            toast("已删除")
            refresh()
        }
        b.show()
    }

    private fun openChat(id: String?, newSession: Boolean) {
        val it = Intent(this, AiChatActivity::class.java)
        it.putExtra("session_id", id)
        it.putExtra("session_new", newSession)
        startActivity(it)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }
}
