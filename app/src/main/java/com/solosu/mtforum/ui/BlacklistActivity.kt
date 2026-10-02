package com.solosu.mtforum.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.solosu.mtforum.R
import com.solosu.mtforum.session.BlacklistManager
import com.solosu.mtforum.session.BlacklistSyncer
import com.solosu.mtforum.ui.space.UserProfileActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 个人小黑屋管理页:合并展示 个人+服务端,支持移出/刷新。
 * 长按计数行 = 清空个人名单;点右上角刷新服务端。
 */
class BlacklistActivity : AppCompatActivity() {

    private lateinit var tvCount: TextView
    private lateinit var adapter: EntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background))

        // 现代标准 Toolbar
        val toolbar = MaterialToolbar(this)
        toolbar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48f)
        )
        toolbar.title = "个人小黑屋"
        toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.text_primary))
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationIconTint(ContextCompat.getColor(this, R.color.text_primary))
        toolbar.setNavigationOnClickListener { finish() }
        root.addView(toolbar)

        tvCount = TextView(this)
        tvCount.setPadding(dp(16f), dp(10f), dp(16f), dp(8f))
        tvCount.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        tvCount.textSize = 12f
        tvCount.isClickable = true
        // 长按计数行 = 清空个人名单
        tvCount.setOnLongClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("清空黑名单")
                .setMessage("确定清空本地全部黑名单？")
                .setPositiveButton("清空") { d, w ->
                    BlacklistManager.clearLocal(this)
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        root.addView(tvCount, LinearLayout.LayoutParams(-1, -2))

        val listView = ListView(this)
        listView.divider = android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.divider)
        )
        listView.dividerHeight = dp(0.5f)
        adapter = EntryAdapter(this)
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(-1, -1, 1f))

        setContentView(root)

        render()
        // 打开页面即同步服务端(7 天过期才真拉)
        BlacklistSyncer.syncIfNeeded(this, object : BlacklistSyncer.Callback {
            override fun onSynced(count: Int, error: String?) {
                runOnUiThread { render() }
            }
        })
    }

    private fun render() {
        val merged = ArrayList<BlacklistManager.Entry>()
        merged.addAll(BlacklistManager.getLocalList(this))
        merged.addAll(BlacklistManager.getServerList(this))
        adapter.reload(merged)
        tvCount.text = "共 " + merged.size + " 人（个人 " + BlacklistManager.getLocalList(this).size +
                " · 服务端 " + BlacklistManager.getServerList(this).size + "）长按此处清空"
    }

    private fun dp(v: Float): Int {
        return (resources.displayMetrics.density * v + 0.5f).toInt()
    }

    /** 条目适配器 */
    private inner class EntryAdapter(c: Context) : ArrayAdapter<BlacklistManager.Entry>(c, 0) {
        fun reload(data: MutableList<BlacklistManager.Entry>) {
            clear()
            addAll(data)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, cv: View?, parent: ViewGroup): View {
            var view = cv
            val h: ViewHolder
            if (view == null) {
                view = LayoutInflater.from(context).inflate(R.layout.item_blacklist_entry, parent, false)
                h = ViewHolder()
                h.name = view.findViewById(R.id.tv_bl_name)
                h.meta = view.findViewById(R.id.tv_bl_meta)
                h.remove = view.findViewById(R.id.btn_bl_remove)
                h.avatar = view.findViewById(R.id.iv_bl_avatar)
                view.tag = h
            } else {
                h = view.tag as ViewHolder
            }
            val e = getItem(position)
            if (e == null) return view
            h.name!!.text = if (!TextUtils.isEmpty(e.user)) e.user else "UID: " + e.uid
            val src = if ("server" == e.source) "服务端" else "个人"
            val time = if (e.time > 0) SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(e.time)) else ""
            h.meta!!.text = "UID " + e.uid + " · " + src + (if (time.isEmpty()) "" else " · 拉黑于 " + time)
            h.remove!!.setOnClickListener {
                if ("server" == e.source) {
                    BlacklistManager.removeServer(context, e.uid)
                } else {
                    BlacklistManager.removeLocal(context, e.uid)
                }
                render()
            }
            // 头像: 全站最可靠的 uc_server/avatar.php?uid=N 构造, 服务端条目也一样能拿
            Glide.with(this@BlacklistActivity)
                .load("https://bbs.binmt.cc/uc_server/avatar.php?uid=" + e.uid + "&size=middle")
                .placeholder(R.drawable.ic_account)
                .error(R.drawable.ic_account)
                .circleCrop()
                .into(h.avatar!!)
            // 整行点击进用户详情
            view.setOnClickListener {
                val it = Intent(this@BlacklistActivity, UserProfileActivity::class.java)
                it.putExtra("uid", e.uid)
                it.putExtra("username", if (!TextUtils.isEmpty(e.user)) e.user else "UID: " + e.uid)
                startActivity(it)
            }
            return view
        }
    }

    private class ViewHolder {
        var name: TextView? = null
        var meta: TextView? = null
        var remove: TextView? = null
        var avatar: android.widget.ImageView? = null
    }
}
