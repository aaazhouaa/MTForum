package com.solosu.mtforum.ui.space

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.bumptech.glide.Glide
import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivityUserProfileBinding
import com.solosu.mtforum.model.UserProfile
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.message.ChatActivity
import com.solosu.mtforum.ui.widget.DialogHelper
import com.solosu.mtforum.ui.widget.FrostedGlassHelper
import com.solosu.mtforum.session.DiscuzUserActionManager
import com.solosu.mtforum.session.FollowStateManager
import com.solosu.mtforum.session.UserSessionManager

/**
 * 用户个人资料页（原生 Activity）
 * 通过 Intent 接收 uid 参数，从服务端加载目标用户的资料并展示
 * 可从好友列表、关注列表、粉丝列表、帖子详情、评论区等入口跳转
 */
class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var httpClient: HttpClient
    private var targetUid: String? = null
    // 仅保存本次网页端响应的状态，禁止从本地缓存推导按钮状态。
    private var serverFollowed = false
    private var serverFollowStateKnown = false
    private var requestInFlight = false

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FrostedGlassHelper.applyToCardViews(binding.root, this)

        httpClient = HttpClient.getInstance()

        targetUid = intent.getStringExtra("uid")
        val username = intent.getStringExtra("username")

        // 设置 Toolbar
        binding.toolbar.title = if (username != null) username else "用户资料"
        // ★ 移除返回箭头图标（仅保留点击返回功能）
        binding.toolbar.setNavigationIcon(null)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (targetUid == null) {
            Toast.makeText(this, "缺少用户ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 用户操作按钮与网页端空间页底部的“加好友/打招呼/聊天”对应。
        setupProfileActions()
        loadUserProfile()
    }

    private fun canUpdateUi(): Boolean {
        return !destroyed && !isFinishing() &&
                (android.os.Build.VERSION.SDK_INT < 17 || !isDestroyed())
    }

    private fun loadUserProfile() {
        if (!canUpdateUi() || requestInFlight || TextUtils.isEmpty(targetUid)) return
        requestInFlight = true
        binding.progressBar.visibility = View.VISIBLE

        Thread({
            var profile: UserProfile? = null
            var error: String? = null
            try {
                httpClient.syncFromCookieManager()
                val cacheBust = "&_ts=" + System.currentTimeMillis()
                val profileUrl = HttpClient.BASE_URL +
                        "home.php?mod=space&uid=" + targetUid + "&mobile=2" + cacheBust
                val html = httpClient.get(profileUrl)

                if (ForumParser.isLoginPage(html)) {
                    error = "登录已过期，请重新登录"
                } else {
                    profile = ForumParser.parseUserProfile(html)
                    if (profile == null || profile.username == null) {
                        val altUrl = HttpClient.BASE_URL +
                                "home.php?mod=space&uid=" + targetUid +
                                "&do=profile&mobile=2" + cacheBust
                        val altHtml = httpClient.get(altUrl)
                        if (ForumParser.isLoginPage(altHtml)) {
                            error = "登录已过期"
                        } else {
                            profile = ForumParser.parseUserProfile(altHtml)
                        }
                    }

                    if (error == null && profile != null) {
                        val followState = if (isOwnProfile())
                            -1 else FollowStateManager.queryServerFollowState(
                            applicationContext, targetUid!!
                        )
                        if (followState >= 0) {
                            profile.followed = followState == 1
                            profile.followStateKnown = true
                        }
                    }
                }
            } catch (e: Exception) {
                error = "加载失败，请稍后重试"
            }

            val resultProfile = profile
            val resultError = error
            runOnUiThread {
                if (!canUpdateUi()) return@runOnUiThread
                requestInFlight = false
                binding.progressBar.visibility = View.GONE
                if (resultError != null) {
                    Toast.makeText(this, resultError, Toast.LENGTH_SHORT).show()
                    if (resultError.contains("登录已过期")) finish()
                } else if (resultProfile != null && resultProfile.username != null) {
                    serverFollowStateKnown = resultProfile.followStateKnown
                    if (serverFollowStateKnown) serverFollowed = resultProfile.followed
                    displayProfile(resultProfile)
                } else {
                    Toast.makeText(this, "无法加载用户资料", Toast.LENGTH_SHORT).show()
                }
            }
        }, "user-profile-load").start()
    }

    /** 当前资料是否属于当前登录账号本人。 */
    private fun isOwnProfile(): Boolean {
        if (TextUtils.isEmpty(targetUid)) return false
        val currentUid = UserSessionManager.getInstance().getUid(this)
        return !TextUtils.isEmpty(currentUid) &&
                "0" != currentUid &&
                currentUid == targetUid
    }

    private fun setupProfileActions() {
        binding.layoutProfileActions.visibility = View.GONE
        binding.btnProfileFriend.setOnClickListener { showFriendDialog() }
        binding.btnProfilePoke.setOnClickListener { showPokeDialog() }
        binding.btnProfileMessage.setOnClickListener { openChat() }
        binding.btnProfileBlock.setOnClickListener { showBlockDialog() }
    }

    /** 打开与当前资料用户的原生聊天页面，不再弹出“发私信”输入框。 */
    private fun openChat() {
        if (!canUseProfileAction()) return

        var chatName = if (binding.tvUsername.text == null)
            "用户"
        else
            binding.tvUsername.text.toString().trim()
        if (TextUtils.isEmpty(chatName)) chatName = "用户"

        val intent = Intent(this, ChatActivity::class.java)
        // Comiis 模板使用 touid 作为会话标识，ChatActivity.EXTRA_PMID 实际传入目标 UID。
        intent.putExtra(ChatActivity.EXTRA_PMID, targetUid)
        intent.putExtra(ChatActivity.EXTRA_UID, targetUid)
        intent.putExtra(ChatActivity.EXTRA_NAME, chatName)
        startActivity(intent)
    }

    private fun canUseProfileAction(): Boolean {
        if (TextUtils.isEmpty(targetUid)) return false
        if (!FollowStateManager.isLoggedIn(this)) {
            com.solosu.mtforum.ui.login.LoginBottomSheet.show(this, null)
            return false
        }
        return true
    }

    private fun makeInput(hint: String, multiLine: Boolean): EditText {
        val input = EditText(this)
        input.setHint(hint)
        input.isSingleLine = !multiLine
        if (multiLine) {
            input.setMinLines(3)
            input.gravity = android.view.Gravity.TOP
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad / 2, pad, pad / 2)
        return input
    }

    private fun showFriendDialog() {
        if (!canUseProfileAction()) return
        val input = makeInput("附加留言（可选）", true)
        val alertDialog1: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("添加好友")
            .setView(input)
            .setPositiveButton("发送申请") { dialog, which ->
                val note = input.text.toString().trim()
                runUserAction(
                    "正在发送好友申请…",
                    { DiscuzUserActionManager.addFriend(this, targetUid, note) },
                    "好友申请已发送", "好友申请发送失败"
                )
            }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog1, this)
    }

    private fun showPokeDialog() {
        if (!canUseProfileAction()) return
        val input = makeInput("招呼内容，例如：你好！", false)
        input.setText("你好！")
        val alertDialog2: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("打招呼")
            .setView(input)
            .setPositiveButton("发送") { dialog, which ->
                val message = input.text.toString().trim()
                if (TextUtils.isEmpty(message)) {
                    Toast.makeText(this, "请输入招呼内容", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runUserAction(
                    "正在发送招呼…",
                    { DiscuzUserActionManager.sendPoke(this, targetUid, message) },
                    "打招呼成功", "打招呼失败"
                )
            }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog2, this)
    }

    private fun showBlockDialog() {
        if (!canUseProfileAction()) return
        val name = if (binding.tvUsername.text == null)
            "该用户"
        else
            binding.tvUsername.text.toString()
        val alertDialog3: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("屏蔽用户")
            .setMessage(
                "屏蔽“" + name + "”后将减少看到该用户的内容（加入黑名单）。" +
                        "如需恢复，可再次打开本窗口选择“取消屏蔽”。"
            )
            .setPositiveButton("屏蔽") { dialog, which ->
                runUserAction(
                    "正在屏蔽用户…",
                    { DiscuzUserActionManager.blockUser(this, targetUid) },
                    "已屏蔽该用户", "屏蔽用户失败"
                )
            }
            .setNeutralButton("取消屏蔽") { dialog, which ->
                runUserAction(
                    "正在取消屏蔽…",
                    { DiscuzUserActionManager.unblockUser(this, targetUid) },
                    "已取消屏蔽", "取消屏蔽失败"
                )
            }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog3, this)
    }

    private fun interface UserAction {
        fun run(): Boolean
    }

    private fun runUserAction(
        loadingText: String, action: UserAction,
        successText: String, failureText: String
    ) {
        binding.layoutProfileActions.isEnabled = false
        Toast.makeText(this, loadingText, Toast.LENGTH_SHORT).show()
        Thread {
            val success = action.run()
            runOnUiThread {
                binding.layoutProfileActions.isEnabled = true
                Toast.makeText(
                    this, if (success) successText else failureText,
                    Toast.LENGTH_SHORT
                ).show()
                if (success && "已屏蔽该用户" == successText) finish()
            }
        }.start()
    }

    private fun displayProfile(profile: UserProfile) {
        // 自己的资料不显示“关注”以及加好友、打招呼、发私信、屏蔽用户等针对他人的操作。
        val ownProfile = isOwnProfile()
        val loggedIn = !ownProfile &&
                FollowStateManager.isLoggedIn(this) &&
                !TextUtils.isEmpty(targetUid)
        binding.layoutProfileActions.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.btnProfileFollow.visibility = if (loggedIn) View.VISIBLE else View.GONE
        if (loggedIn) {
            if (serverFollowStateKnown) {
                binding.btnProfileFollow.isEnabled = !requestInFlight
                binding.btnProfileFollow.setText(
                    if (serverFollowed)
                        R.string.action_followed else R.string.action_follow
                )
                binding.btnProfileFollow.setOnClickListener { toggleFollowProfile() }
            } else {
                // 服务端没有明确状态时，禁止操作，也不能伪显示为“关注”。
                binding.btnProfileFollow.isEnabled = false
                binding.btnProfileFollow.text = "状态同步中"
                binding.btnProfileFollow.setOnClickListener(null)
            }
        } else {
            binding.btnProfileFollow.setOnClickListener(null)
            binding.btnProfileFollow.isEnabled = false
        }

        // 更新标题
        if (profile.username != null) {
            binding.toolbar.title = profile.username
        }

        // 头像
        val avatarUrl = profile.avatarUrl
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_account)
                .error(R.drawable.ic_account)
                .circleCrop()
                .into(binding.ivAvatar)
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_account)
        }

        // 用户名
        binding.tvUsername.text = if (profile.username != null) profile.username else "未知用户"

        // UID
        binding.tvUid.text = "UID: " + (if (profile.uid != null) profile.uid else "—")

        // 等级
        binding.tvLevel.text = if (profile.level != null) profile.level else ""

        // 用户组
        binding.tvGroup.text = if (profile.groupName != null) profile.groupName else ""

        // 五列统计
        binding.tvThreads.text = profile.threads.toString()
        binding.tvReplies.text = profile.posts.toString()
        binding.tvFriends.text = profile.friends.toString()
        // 目标用户资料页必须显示目标用户自己的服务端关注数，不能套用当前账号的本地关注集合。
        binding.tvFollowing.text = profile.following.toString()
        binding.tvFollowers.text = profile.followers.toString()

        // 积分/金币/在线时长
        binding.tvCredits.text = profile.credits.toString()
        binding.tvGold.text = profile.gold.toString()
        binding.tvOnlineTime.text = if (profile.onlineTime != null) profile.onlineTime else "0小时"

        // 账号信息
        binding.tvRegDate.text = if (profile.regDate != null) profile.regDate else "—"
        binding.tvLastVisit.text = if (profile.lastVisit != null) profile.lastVisit else "—"

        // 帖子统计项：查看该用户发布的全部帖子
        binding.layoutThreads.setOnClickListener {
            val intent = Intent(this, SpaceThreadListActivity::class.java)
            intent.putExtra("mode", "uid_threads")
            intent.putExtra("uid", targetUid)
            intent.putExtra("username", binding.tvUsername.text.toString())
            startActivity(intent)
        }

        // 性别
        var genderText = "保密"
        if ("boy" == profile.gender) {
            genderText = "男 ♂"
        } else if ("girl" == profile.gender) {
            genderText = "女 ♀"
        }
        binding.tvGender.text = genderText
    }

    private fun toggleFollowProfile() {
        if (TextUtils.isEmpty(targetUid) || requestInFlight || !serverFollowStateKnown) return
        // 切换目标状态只能基于当前网页端刚返回的状态，不能读取本地 SharedPreferences。
        val targetState = !serverFollowed
        requestInFlight = true
        binding.btnProfileFollow.isEnabled = false
        Thread {
            val success = FollowStateManager.syncFollow(this@UserProfileActivity, targetUid!!, targetState)
            if (success) {
                // 操作后立即从网页端读取并确认，确认前不直接修改按钮状态。
                runOnUiThread {
                    Toast.makeText(
                        this, if (targetState)
                            R.string.action_follow_success
                        else
                            R.string.action_unfollow_success, Toast.LENGTH_SHORT
                    ).show()
                    requestInFlight = false
                    loadUserProfile()
                }
            } else {
                runOnUiThread {
                    requestInFlight = false
                    binding.btnProfileFollow.isEnabled = serverFollowStateKnown
                    Toast.makeText(this, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        destroyed = true
        requestInFlight = false
        super.onDestroy()
    }
}
