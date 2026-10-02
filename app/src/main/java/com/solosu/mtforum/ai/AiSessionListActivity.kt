package com.solosu.mtforum.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 会话列表页（Codex 风格）。
 *
 * 列出全部会话，支持新建 / 点击续聊 / 长按删除。
 * 布局代码化构建，不依赖新增 XML 资源。
 */
class AiSessionListActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(Color.parseColor("#F5F6F8"))
        sv.addView(root)
        setContentView(sv)

        // 顶栏
        val bar = LinearLayout(this)
        bar.setOrientation(LinearLayout.HORIZONTAL)
        bar.setBackgroundColor(Color.parseColor("#FFFFFF"))
        bar.setElevation(dp(2).toFloat())
        bar.setGravity(Gravity.CENTER_VERTICAL)
        bar.setPadding(dp(8), dp(10), dp(8), dp(10))

        val back = TextView(this)
        back.setText("←")
        back.setTextSize(20f)
        back.setTextColor(Color.parseColor("#1C1E21"))
        back.setPadding(dp(12), dp(4), dp(12), dp(4))
        back.setOnClickListener { finish() }
        bar.addView(back)

        val title = TextView(this)
        title.setText("历史会话")
        title.setTextSize(17f)
        title.setTextColor(Color.parseColor("#1C1E21"))
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(title, tlp)

        val newBtn = TextView(this)
        newBtn.setText("新建会话")
        newBtn.setTextSize(14f)
        newBtn.setTextColor(Color.parseColor("#1A73E8"))
        newBtn.setPadding(dp(12), dp(4), dp(12), dp(4))
        newBtn.setOnClickListener {
            val id = AiSessionStore.createSession(this)
            openChat(id, true)
        }
        bar.addView(newBtn)

        root.addView(bar)

        // 列表容器
        list = LinearLayout(this)
        list.setOrientation(LinearLayout.VERTICAL)
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
            empty.setText("还没有会话。点右上角「新建会话」开始。\n也可以在聊天页里直接开聊，会自动保存。")
            empty.setTextSize(14f)
            empty.setTextColor(Color.parseColor("#9CA3AF"))
            empty.setGravity(Gravity.CENTER)
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
        card.setOrientation(LinearLayout.VERTICAL)
        card.setBackgroundColor(Color.parseColor("#FFFFFF"))
        card.setElevation(dp(1).toFloat())
        val pad = dp(14)
        card.setPadding(pad, dp(12), pad, dp(12))
        val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        card.setLayoutParams(lp)

        // 标题行
        val title = TextView(this)
        title.setText(if (TextUtils.isEmpty(s.title)) "新会话" else s.title!!)
        title.setTextSize(15f)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(Color.parseColor("#1C1E21"))
        title.setMaxLines(2)
        title.setEllipsize(TextUtils.TruncateAt.END)
        card.addView(title)

        // 副信息行：消息数 + 时间
        val meta = TextView(this)
        meta.setText(s.messageCount.toString() + " 条消息 · " + fmt.format(Date(s.updatedAt)))
        meta.setTextSize(12f)
        meta.setTextColor(Color.parseColor("#9CA3AF"))
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
