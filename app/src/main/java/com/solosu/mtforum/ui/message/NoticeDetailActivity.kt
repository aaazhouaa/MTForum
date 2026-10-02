package com.solosu.mtforum.ui.message

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.MessageAdapter
import com.solosu.mtforum.model.Message
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.network.NoticeBadgeManager
import com.solosu.mtforum.session.DiscuzUserActionManager

import com.solosu.mtforum.ui.widget.DialogHelper

/**
 * 消息详情页（原生 RecyclerView 列表）
 * 支持6个分类：我的消息(pm)、我的粉丝(follower)、我的帖子(mypost)、
 * 坛友互动(interactive)、系统提醒(system)、应用提醒(app)
 * 支持删除和屏蔽操作
 */
class NoticeDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var ivBack: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var adapter: MessageAdapter
    private var viewType: String? = null
    private var cachedFormhash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notice_detail)

        val url = intent.getStringExtra("url")
        viewType = intent.getStringExtra(EXTRA_VIEW_TYPE)
        val title = intent.getStringExtra(EXTRA_TITLE)

        tvTitle = findViewById(R.id.tv_title)
        ivBack = findViewById(R.id.iv_back)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        tvEmptyHint = findViewById(R.id.tv_empty_hint)

        if (title != null) tvTitle.text = title

        // 返回按钮
        ivBack.setOnClickListener {
            setResult(
                RESULT_OK, intent.putExtra(RESULT_CLEARED, true)
                    .putExtra(RESULT_VIEW_TYPE, viewType ?: "")
            )
            finish()
        }

        // 设置 RecyclerView
        adapter = MessageAdapter(this)
        adapter.setOnItemClickListener(object : MessageAdapter.OnItemClickListener {
            override fun onItemClick(message: Message?, position: Int) {
                // 私信必须优先进入原生聊天页，不能因为摘要中偶然包含 tid= 而跳到帖子页。
                if ("pm" == viewType) {
                    val pmid = message!!.pmid
                    if (pmid != null && !pmid.isEmpty()) {
                        val chatIntent = Intent(this@NoticeDetailActivity, ChatActivity::class.java)
                        chatIntent.putExtra(ChatActivity.EXTRA_PMID, pmid)
                        chatIntent.putExtra(ChatActivity.EXTRA_UID, message.authorUid)
                        chatIntent.putExtra(ChatActivity.EXTRA_NAME, message.author)
                        chatIntent.putExtra(ChatActivity.EXTRA_AVATAR, message.avatarUrl)
                        startActivity(chatIntent)
                    } else {
                        Toast.makeText(this@NoticeDetailActivity, "无法打开私信：缺少会话ID", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val summary = message!!.summary
                if (summary != null && !summary.isEmpty()) {
                    val m = java.util.regex.Pattern.compile("tid=(\\d+)").matcher(summary)
                    if (m.find()) {
                        val intent = Intent(this@NoticeDetailActivity, com.solosu.mtforum.ui.detail.ThreadDetailActivity::class.java)
                        intent.putExtra("tid", m.group(1))
                        startActivity(intent)
                    }
                }
            }
        })

        // ★ 删除/屏蔽按钮回调
        adapter.setOnActionClickListener(object : MessageAdapter.OnActionClickListener {
            override fun onDelete(message: Message?, position: Int) {
                showDeleteConfirmDialog(message, position)
            }

            override fun onBlock(message: Message?, position: Int) {
                showBlockConfirmDialog(message, position)
            }
        })

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 加载数据
        if (url != null) {
            loadData(url)
        } else {
            showEmpty("参数错误")
        }
    }

    private fun loadData(url: String) {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        tvEmptyHint.visibility = View.GONE

        Thread {
            try {
                val httpClient = HttpClient.getInstance()
                httpClient.syncFromCookieManager()

                // 统一使用移动端页面请求:私信(fm)/粉丝/通知(我的帖子/互动/系统/应用)
                // 移动版 li.b_b.bg_f.cl 结构可提取帖子 tid,实现整行点击跳转帖子
                val html = httpClient.get(url)

                // 检测是否为登录页
                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        showEmpty("登录已过期，请重新登录")
                    }
                    return@Thread
                }

                // 缓存 formhash（后续删除/屏蔽需要）
                cachedFormhash = ForumParser.parseFormhash(html)

                val items: MutableList<Message>
                if ("pm" == viewType) {
                    items = ForumParser.parsePmList(html)
                } else if ("follower" == viewType) {
                    items = ForumParser.parseFollowerList(html)
                } else {
                    items = ForumParser.parseNoticeList(html)
                }

                val finalItems = items
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    // 详情页已成功加载当前分类内容，确认该分类已查看。
                    NoticeBadgeManager.markViewed(this, viewType)
                    if (finalItems.isNotEmpty()) {
                        adapter.setMessageList(finalItems)
                        recyclerView.visibility = View.VISIBLE
                        tvEmptyHint.visibility = View.GONE
                    } else {
                        showEmpty("暂无消息内容")
                    }
                }
            } catch (e: Exception) {
                com.solosu.mtforum.util.CrashHandler.saveErrorLog(
                    "NoticeDetail", "加载通知列表失败: $url", e
                )
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    showEmpty("加载失败: " + e.message)
                }
            }
        }.start()
    }

    private fun showEmpty(hint: String) {
        recyclerView.visibility = View.GONE
        tvEmptyHint.visibility = View.VISIBLE
        tvEmptyHint.text = hint
    }

    // ========== 删除功能 ==========

    private fun showDeleteConfirmDialog(message: Message?, position: Int) {
        val alertDialog1: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("删除提醒")
            .setMessage("确定要删除这条提醒吗？")
            .setPositiveButton("删除") { dialog, which -> executeDelete(message, position) }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog1, this)
    }

    private fun executeDelete(message: Message?, position: Int) {
        val uid = message!!.authorUid
        if ("pm" == viewType) {
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(this, "无法删除：缺少会话用户ID", Toast.LENGTH_SHORT).show()
                return
            }

            progressBar.visibility = View.VISIBLE
            Thread {
                val success = DiscuzUserActionManager.deletePrivateMessage(
                    this, uid, cachedFormhash, message.deleteUrl
                )
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (success) {
                        adapter.removeItem(position)
                        Toast.makeText(
                            this@NoticeDetailActivity, "删除成功",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (adapter.itemCount == 0) showEmpty("暂无消息内容")
                    } else {
                        Toast.makeText(
                            this@NoticeDetailActivity,
                            "删除失败，请稍后重试", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
            return
        }

        executeNoticeDelete(message, position)
    }

    /** 非私信通知仍使用 Discuz! 的 delnotice 接口。 */
    private fun executeNoticeDelete(message: Message?, position: Int) {
        val noticeId = message!!.pmid
        if (TextUtils.isEmpty(noticeId)) {
            Toast.makeText(this, "无法删除：缺少通知ID", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        Thread {
            try {
                val httpClient = HttpClient.getInstance()
                httpClient.syncFromCookieManager()
                var deleteUrl = HttpClient.BASE_URL +
                        "home.php?mod=misc&ac=ajax&op=delnotice&inajax=1&id=" + noticeId
                if (!TextUtils.isEmpty(cachedFormhash)) {
                    deleteUrl += "&formhash=" + cachedFormhash
                }
                val result = httpClient.get(deleteUrl)
                val success = result != null &&
                        (result.contains("succeed") || result.contains("成功"))
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (success) {
                        adapter.removeItem(position)
                        Toast.makeText(
                            this@NoticeDetailActivity, "删除成功",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@NoticeDetailActivity, "删除失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@NoticeDetailActivity,
                        "删除失败: " + e.message, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // ========== 屏蔽功能 ==========

    private fun showBlockConfirmDialog(message: Message?, position: Int) {
        val authorName = if (message!!.author != null) message.author else "该用户"
        val alertDialog2: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("屏蔽用户")
            .setMessage("确定要屏蔽 " + authorName + " 吗？屏蔽后将不再收到该用户的通知。")
            .setPositiveButton("屏蔽") { dialog, which -> executeBlock(message, position) }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog2, this)
    }

    private fun executeBlock(message: Message?, position: Int) {
        val uid = message!!.authorUid
        if (uid == null || uid.isEmpty()) {
            Toast.makeText(this, "无法屏蔽：缺少用户信息", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        Thread {
            try {
                val httpClient = HttpClient.getInstance()
                httpClient.syncFromCookieManager()

                // Discuz! 屏蔽用户通知接口
                var blockUrl = HttpClient.BASE_URL +
                        "home.php?mod=spacecp&ac=common&op=ignore&authorid=" + uid +
                        "&type=post&handlekey=noticeignore"
                if (cachedFormhash != null && !cachedFormhash!!.isEmpty()) {
                    blockUrl += "&formhash=" + cachedFormhash
                }

                val result = httpClient.get(blockUrl)

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (result != null && !result.contains("error")) {
                        adapter.removeItem(position)
                        Toast.makeText(this@NoticeDetailActivity, "已屏蔽该用户", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@NoticeDetailActivity, "屏蔽失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@NoticeDetailActivity, "屏蔽失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_VIEW_TYPE = "view_type"
        const val EXTRA_TITLE = "title"
        const val RESULT_CLEARED = "cleared"
        const val RESULT_VIEW_TYPE = "result_view_type"
    }
}
