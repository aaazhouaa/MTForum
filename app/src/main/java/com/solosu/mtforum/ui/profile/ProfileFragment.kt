package com.solosu.mtforum.ui.profile

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment

import com.bumptech.glide.Glide
import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.FragmentProfileBinding
import com.solosu.mtforum.model.UserProfile
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.session.UserSessionManager
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable
import com.solosu.mtforum.ui.widget.FrostedGlassHelper
import com.solosu.mtforum.ui.widget.NavBarAutoHideHelper
import com.solosu.mtforum.ui.login.LoginBottomSheet
import com.solosu.mtforum.ui.space.SpaceThreadListActivity
import com.solosu.mtforum.ui.space.FriendListActivity
import com.solosu.mtforum.ui.space.CreditDetailActivity
import com.solosu.mtforum.ui.space.EditProfileActivity
import com.solosu.mtforum.ui.space.SettingsActivity

/**
 * 个人中心 Fragment（全新 UI）
 * 展示用户完整资料信息：头像、用户名、UID、等级、用户组、
 * 帖子/回复/好友/粉丝统计、积分/金币/在线时长、注册信息、
 * 功能菜单（我的帖子、收藏、好友、积分详情、编辑资料、设置）
 */
class ProfileFragment : Fragment() {

    private var binding: FragmentProfileBinding? = null
    private lateinit var httpClient: HttpClient

    @Nullable
    override fun onCreateView(
        @NonNull inflater: LayoutInflater,
        @Nullable container: ViewGroup?,
        @Nullable savedInstanceState: Bundle?
    ): View {
        binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        FrostedGlassHelper.applyToCardViews(view, requireContext())

        // 为功能菜单图标设置毛玻璃背景
        applyFrostedGlassToIcon(binding!!.ivEmojiThreads)
        applyFrostedGlassToIcon(binding!!.ivEmojiFavorites)
        applyFrostedGlassToIcon(binding!!.ivEmojiFriends)
        applyFrostedGlassToIcon(binding!!.ivEmojiCredits)
        applyFrostedGlassToIcon(binding!!.ivEmojiEdit)
        applyFrostedGlassToIcon(binding!!.ivEmojiSettings)

        httpClient = HttpClient.getInstance()

        // build71: 滚动方向 -> 底部导航栏自动隐藏/出现
        val navScroll = view.findViewById<android.widget.ScrollView>(R.id.nav_scroll_profile)
        if (navScroll != null) {
            navScroll.setOnScrollChangeListener { v, sx, sy, osx, osy ->
                NavBarAutoHideHelper.onScrolled(activity, sy - osy)
            }
        }

        // === 点击统计项：跳转到对应原生列表页 ===
        binding!!.layoutThreads.setOnClickListener {
            val intent = Intent(requireContext(), SpaceThreadListActivity::class.java)
            intent.putExtra("mode", "my_threads")
            startActivity(intent)
        }
        binding!!.layoutReplies.setOnClickListener {
            val intent = Intent(requireContext(), SpaceThreadListActivity::class.java)
            intent.putExtra("mode", "my_replies")
            startActivity(intent)
        }
        binding!!.layoutFriends.setOnClickListener {
            val intent = Intent(requireContext(), FriendListActivity::class.java)
            intent.putExtra("mode", "friends")
            startActivity(intent)
        }
        binding!!.layoutFollowing.setOnClickListener {
            val intent = Intent(requireContext(), FriendListActivity::class.java)
            intent.putExtra("mode", "following")
            startActivity(intent)
        }
        binding!!.layoutFollowers.setOnClickListener {
            val intent = Intent(requireContext(), FriendListActivity::class.java)
            intent.putExtra("mode", "followers")
            startActivity(intent)
        }

        // === 功能菜单 ===
        binding!!.layoutMyThreads.setOnClickListener {
            val intent = Intent(requireContext(), SpaceThreadListActivity::class.java)
            intent.putExtra("mode", "my_threads")
            startActivity(intent)
        }

        binding!!.layoutMyFavorites.setOnClickListener {
            val intent = Intent(requireContext(), SpaceThreadListActivity::class.java)
            intent.putExtra("mode", "favorites")
            startActivity(intent)
        }

        binding!!.layoutMyFriends.setOnClickListener {
            val intent = Intent(requireContext(), FriendListActivity::class.java)
            intent.putExtra("mode", "friends")
            startActivity(intent)
        }

        binding!!.layoutCreditsDetail.setOnClickListener {
            val intent = Intent(requireContext(), CreditDetailActivity::class.java)
            startActivity(intent)
        }

        binding!!.layoutEditProfile.setOnClickListener {
            val intent = Intent(requireContext(), EditProfileActivity::class.java)
            startActivity(intent)
        }

        binding!!.layoutSettings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        // 退出登录/登录按钮
        binding!!.btnLogout.setOnClickListener {
            if (!isActuallyLoggedIn()) {
                startLogin()
                return@setOnClickListener
            }
            httpClient.clearCookies(requireContext())
            UserSessionManager.getInstance().clearLoginInfo(requireContext())
            updateLoginState()
            com.solosu.mtforum.util.ToastUtil.makeText(
                requireContext(),
                "已退出登录", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLoginState()
    }

    /**
     * 根据登录状态切换 UI
     */
    private fun isActuallyLoggedIn(): Boolean {
        return httpClient.isLoggedIn() &&
                UserSessionManager.getInstance().isLoggedIn(requireContext())
    }

    private fun applyFrostedGlassToIcon(icon: View?) {
        if (icon != null) {
            icon.background = FrostedGlassDrawable.create(requireContext(), 10f)
        }
    }

    private fun startLogin() {
        if (!isAdded) return
        LoginBottomSheet.show(requireActivity()) {
            if (isAdded) {
                updateLoginState()
            }
        }
    }

    private fun updateLoginState() {
        if (!isActuallyLoggedIn()) {
            // 未登录时保留个人中心页面，只显示登录入口；不要在 onResume 中反复启动 LoginActivity。
            binding!!.root.visibility = View.VISIBLE
            binding!!.tvUsername.text = "未登录"
            binding!!.tvUid.text = "登录后查看个人资料"
            binding!!.tvLevel.text = ""
            binding!!.tvGroup.text = ""
            binding!!.ivAvatar.setImageResource(R.drawable.ic_account)
            binding!!.ivAvatar.setOnClickListener { startLogin() }
            binding!!.tvUsername.setOnClickListener { startLogin() }
            binding!!.tvUid.setOnClickListener { startLogin() }
            binding!!.btnLogout.text = "登录账号"
            return
        }
        binding!!.root.visibility = View.VISIBLE
        binding!!.btnLogout.text = getString(R.string.action_logout)
        loadProfile()
    }

    /**
     * 加载用户资料
     * 先请求服务端；如果服务端返回登录页（Cookie 过期），清除 Cookie 并跳转登录页
     */
    private fun loadProfile() {
        Thread {
            try {
                // ★ 修复：使用空间首页 URL 并附加当前 uid 参数（确保获取到完整的个人空间主页）
                // 单纯 home.php?mod=space&mobile=2 可能不展示完整统计数据
                val loginUid = UserSessionManager.getInstance().getUid(requireContext())
                val profileUrl: String
                if (!TextUtils.isEmpty(loginUid)) {
                    profileUrl = HttpClient.BASE_URL + "home.php?mod=space&uid=" + loginUid + "&mobile=2"
                } else {
                    profileUrl = HttpClient.BASE_URL + "home.php?mod=space&mobile=2"
                }
                val html = httpClient.get(profileUrl)

                // === 关键修复：检测服务器是否返回了登录页（Cookie 过期） ===
                if (ForumParser.isLoginPage(html)) {
                    // Cookie 已过期/无效，清除所有 Cookie 并跳转登录
                    if (!isAdded) return@Thread
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        httpClient.clearCookies(requireContext())
                        UserSessionManager.getInstance().clearLoginInfo(requireContext())
                        LoginBottomSheet.show(requireActivity()) {
                            if (isAdded) {
                                updateLoginState()
                            }
                        }
                        com.solosu.mtforum.util.ToastUtil.makeText(
                            requireContext(),
                            "登录已过期，请重新登录", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }

                // === 服务端确认已登录 → 正常解析用户资料 ===
                var profile = ForumParser.parseUserProfile(html)

                if (profile == null || profile.username == null) {
                    val altUrl = HttpClient.BASE_URL + "home.php?mod=space&do=profile&mobile=2"
                    val altHtml = httpClient.get(altUrl)
                    if (!isAdded) return@Thread
                    if (ForumParser.isLoginPage(altHtml)) {
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            httpClient.clearCookies(requireContext())
                            UserSessionManager.getInstance().clearLoginInfo(requireContext())
                            LoginBottomSheet.show(requireActivity()) {
                                if (isAdded) {
                                    updateLoginState()
                                }
                            }
                        }
                        return@Thread
                    }
                    profile = ForumParser.parseUserProfile(altHtml)
                }

                if (!isAdded) return@Thread
                val finalProfile = profile
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (finalProfile != null && finalProfile.username != null) {
                        displayProfile(finalProfile)
                    } else {
                        com.solosu.mtforum.util.ToastUtil.makeText(
                            requireContext(),
                            "无法加载用户资料，请确认已登录", com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    com.solosu.mtforum.util.ToastUtil.makeText(
                        requireContext(),
                        "加载资料失败: " + e.message, com.solosu.mtforum.util.ToastUtil.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /**
     * 将 UserProfile 展示到新 UI 控件
     */
    private fun displayProfile(profile: UserProfile) {
        // 保存登录信息
        val loginInfo = HashMap<String, String>()
        if (profile.username != null) loginInfo["username"] = profile.username!!
        if (profile.uid != null) loginInfo["uid"] = profile.uid!!
        if (profile.avatarUrl != null) loginInfo["avatarUrl"] = profile.avatarUrl!!
        if (profile.level != null) loginInfo["level"] = profile.level!!
        UserSessionManager.getInstance().saveLoginInfo(requireContext(), loginInfo)

        // 头像
        val avatarUrl = profile.avatarUrl
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_account)
                .error(R.drawable.ic_account)
                .circleCrop()
                .into(binding!!.ivAvatar)
        } else {
            binding!!.ivAvatar.setImageResource(R.drawable.ic_account)
        }

        // 用户名
        binding!!.tvUsername.text = if (profile.username != null) profile.username else "未知用户"

        // UID
        binding!!.tvUid.text = "UID: " + (if (profile.uid != null) profile.uid else "—")

        // 等级（绿色小徽章）
        binding!!.tvLevel.text = if (profile.level != null) profile.level else ""

        // 用户组
        binding!!.tvGroup.text = if (profile.groupName != null) profile.groupName else ""

        // 五列统计
        binding!!.tvThreads.text = profile.threads.toString()
        binding!!.tvReplies.text = profile.posts.toString()
        binding!!.tvFriends.text = profile.friends.toString()
        // 关注数以服务端个人资料为准，避免本地操作缓存与网页端实际关注关系不一致。
        // FollowStateManager 只记录本 App 操作过的 UID，不能代表账号的完整关注数。
        binding!!.tvFollowing.text = profile.following.toString()
        binding!!.tvFollowers.text = profile.followers.toString()

        // 积分/金币/在线时长
        binding!!.tvCredits.text = profile.credits.toString()
        binding!!.tvGold.text = profile.gold.toString()
        binding!!.tvOnlineTime.text = if (profile.onlineTime != null) profile.onlineTime else "0小时"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
