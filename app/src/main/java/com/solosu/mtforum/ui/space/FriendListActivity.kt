package com.solosu.mtforum.ui.space

import android.content.Intent
import android.os.Bundle
import android.widget.Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager

import com.solosu.mtforum.R
import com.solosu.mtforum.adapter.FriendAdapter
import com.solosu.mtforum.databinding.ActivityFriendListBinding
import com.solosu.mtforum.model.Friend
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.UserSessionManager

import java.util.List

/**
 * 好友/关注/粉丝列表页（原生）
 * 支持 3 种模式：friends（好友）、following（关注）、followers（粉丝）
 */
class FriendListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendListBinding
    private lateinit var httpClient: HttpClient
    private lateinit var adapter: FriendAdapter
    private var mode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        httpClient = HttpClient.getInstance()
        mode = intent.getStringExtra("mode")

        val title: String
        val listUrl: String

        // ★ 修复：关注列表的正确 URL 需要当前登录用户的 UID
        var currentUid = UserSessionManager.getInstance().getUid(this)
        if (currentUid == null) currentUid = ""

        if ("following" == mode) {
            title = "我的关注"
            // ★ 修复：原来的 home.php?mod=space&do=follow 返回空页面
            //   正确 URL: home.php?mod=follow&do=following&uid={uid}&mobile=2
            listUrl = HttpClient.BASE_URL + "home.php?mod=follow&do=following&uid=" + currentUid + "&mobile=2"
        } else if ("followers" == mode) {
            title = "我的粉丝"
            listUrl = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&mobile=2"
        } else {
            title = "我的好友"
            listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=friend&mobile=2"
        }

        binding.toolbar.title = title
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FriendAdapter(this)
        adapter.setOnItemClickListener(object : FriendAdapter.OnItemClickListener {
            override fun onItemClick(friend: Friend?, position: Int) {
                // ★ 修复：从 WebView 跳转改为原生用户资料页
                if (friend != null && friend.uid != null) {
                    val intent = Intent(this@FriendListActivity, UserProfileActivity::class.java)
                    intent.putExtra("uid", friend.uid)
                    intent.putExtra("username", friend.username)
                    startActivity(intent)
                }
            }
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadData(listUrl) }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadData(listUrl)
    }

    private fun loadData(listUrl: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.swipeRefresh.isEnabled = false

        Thread {
            try {
                val html = httpClient.get(listUrl)

                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        binding.swipeRefresh.isEnabled = true
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }

                val friends: MutableList<Friend> = ForumParser.parseFriendList(html)

                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true

                    if (friends.isEmpty()) {
                        binding.recyclerView.visibility = android.view.View.GONE
                        binding.tvEmpty.visibility = android.view.View.VISIBLE
                    } else {
                        binding.recyclerView.visibility = android.view.View.VISIBLE
                        binding.tvEmpty.visibility = android.view.View.GONE
                        adapter.setFriendList(friends)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true
                    Toast.makeText(this, "加载失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
