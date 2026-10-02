package com.solosu.mtforum.ui.profile;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.FragmentProfileBinding;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import com.solosu.mtforum.ui.widget.NavBarAutoHideHelper;
import com.solosu.mtforum.ui.login.LoginBottomSheet;
import com.solosu.mtforum.ui.space.SpaceThreadListActivity;
import com.solosu.mtforum.ui.space.FriendListActivity;
import com.solosu.mtforum.ui.space.CreditDetailActivity;
import com.solosu.mtforum.ui.space.EditProfileActivity;
import com.solosu.mtforum.ui.space.SettingsActivity;

/**
 * 个人中心 Fragment（全新 UI）
 * 展示用户完整资料信息：头像、用户名、UID、等级、用户组、
 * 帖子/回复/好友/粉丝统计、积分/金币/在线时长、注册信息、
 * 功能菜单（我的帖子、收藏、好友、积分详情、编辑资料、设置）
 */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private HttpClient httpClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FrostedGlassHelper.applyToCardViews(view, requireContext());

        // 为功能菜单图标设置毛玻璃背景
        applyFrostedGlassToIcon(binding.ivEmojiThreads);
        applyFrostedGlassToIcon(binding.ivEmojiFavorites);
        applyFrostedGlassToIcon(binding.ivEmojiFriends);
        applyFrostedGlassToIcon(binding.ivEmojiCredits);
        applyFrostedGlassToIcon(binding.ivEmojiEdit);
        applyFrostedGlassToIcon(binding.ivEmojiSettings);

        httpClient = HttpClient.getInstance();

        // build71: 滚动方向 -> 底部导航栏自动隐藏/出现
        android.widget.ScrollView navScroll = view.findViewById(R.id.nav_scroll_profile);
        if (navScroll != null) {
            navScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) ->
                    NavBarAutoHideHelper.onScrolled(getActivity(), sy - osy));
        }

        // === 点击统计项：跳转到对应原生列表页 ===
        binding.layoutThreads.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SpaceThreadListActivity.class);
            intent.putExtra("mode", "my_threads");
            startActivity(intent);
        });
        binding.layoutReplies.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SpaceThreadListActivity.class);
            intent.putExtra("mode", "my_replies");
            startActivity(intent);
        });
        binding.layoutFriends.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), FriendListActivity.class);
            intent.putExtra("mode", "friends");
            startActivity(intent);
        });
        binding.layoutFollowing.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), FriendListActivity.class);
            intent.putExtra("mode", "following");
            startActivity(intent);
        });
        binding.layoutFollowers.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), FriendListActivity.class);
            intent.putExtra("mode", "followers");
            startActivity(intent);
        });

        // === 功能菜单 ===
        binding.layoutMyThreads.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SpaceThreadListActivity.class);
            intent.putExtra("mode", "my_threads");
            startActivity(intent);
        });

        binding.layoutMyFavorites.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SpaceThreadListActivity.class);
            intent.putExtra("mode", "favorites");
            startActivity(intent);
        });

        binding.layoutMyFriends.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), FriendListActivity.class);
            intent.putExtra("mode", "friends");
            startActivity(intent);
        });

        binding.layoutCreditsDetail.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CreditDetailActivity.class);
            startActivity(intent);
        });

        binding.layoutEditProfile.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), EditProfileActivity.class);
            startActivity(intent);
        });

        binding.layoutSettings.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SettingsActivity.class);
            startActivity(intent);
        });

    // 退出登录/登录按钮
        binding.btnLogout.setOnClickListener(v -> {
            if (!isActuallyLoggedIn()) {
                startLogin();
                return;
            }
            httpClient.clearCookies(requireContext());
            UserSessionManager.getInstance().clearLoginInfo(requireContext());
            updateLoginState();
            android.widget.Toast.makeText(requireContext(),
                    "已退出登录", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLoginState();
    }

    /**
     * 根据登录状态切换 UI
     */
    private boolean isActuallyLoggedIn() {
        return httpClient.isLoggedIn()
                && UserSessionManager.getInstance().isLoggedIn(requireContext());
    }

    private void applyFrostedGlassToIcon(View icon) {
        if (icon != null) {
            icon.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));
        }
    }

    private void startLogin() {
        if (!isAdded()) return;
        LoginBottomSheet.show(requireActivity(), () -> {
            if (isAdded()) {
                updateLoginState();
            }
        });
    }

    private void updateLoginState() {
        if (!isActuallyLoggedIn()) {
            // 未登录时保留个人中心页面，只显示登录入口；不要在 onResume 中反复启动 LoginActivity。
            binding.getRoot().setVisibility(View.VISIBLE);
            binding.tvUsername.setText("未登录");
            binding.tvUid.setText("登录后查看个人资料");
            binding.tvLevel.setText("");
            binding.tvGroup.setText("");
            binding.ivAvatar.setImageResource(R.drawable.ic_account);
            binding.ivAvatar.setOnClickListener(v -> startLogin());
            binding.tvUsername.setOnClickListener(v -> startLogin());
            binding.tvUid.setOnClickListener(v -> startLogin());
            binding.btnLogout.setText("登录账号");
            return;
        }
        binding.getRoot().setVisibility(View.VISIBLE);
        binding.btnLogout.setText(getString(R.string.action_logout));
        loadProfile();
    }

    /**
     * 加载用户资料
     * 先请求服务端；如果服务端返回登录页（Cookie 过期），清除 Cookie 并跳转登录页
     */
    private void loadProfile() {
        new Thread(() -> {
            try {
                // ★ 修复：使用空间首页 URL 并附加当前 uid 参数（确保获取到完整的个人空间主页）
                // 单纯 home.php?mod=space&mobile=2 可能不展示完整统计数据
                String loginUid = UserSessionManager.getInstance().getUid(requireContext());
                String profileUrl;
                if (!TextUtils.isEmpty(loginUid)) {
                    profileUrl = HttpClient.BASE_URL + "home.php?mod=space&uid=" + loginUid + "&mobile=2";
                } else {
                    profileUrl = HttpClient.BASE_URL + "home.php?mod=space&mobile=2";
                }
                String html = httpClient.get(profileUrl);

                // === 关键修复：检测服务器是否返回了登录页（Cookie 过期） ===
                if (ForumParser.isLoginPage(html)) {
                    // Cookie 已过期/无效，清除所有 Cookie 并跳转登录
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        httpClient.clearCookies(requireContext());
                        UserSessionManager.getInstance().clearLoginInfo(requireContext());
                        LoginBottomSheet.show(requireActivity(), () -> {
                            if (isAdded()) {
                                updateLoginState();
                            }
                        });
                        android.widget.Toast.makeText(requireContext(),
                                "登录已过期，请重新登录", android.widget.Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // === 服务端确认已登录 → 正常解析用户资料 ===
                UserProfile profile = ForumParser.parseUserProfile(html);

                if (profile == null || profile.getUsername() == null) {
                    String altUrl = HttpClient.BASE_URL + "home.php?mod=space&do=profile&mobile=2";
                    String altHtml = httpClient.get(altUrl);
                    if (!isAdded()) return;
                    if (ForumParser.isLoginPage(altHtml)) {
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            httpClient.clearCookies(requireContext());
                            UserSessionManager.getInstance().clearLoginInfo(requireContext());
                            LoginBottomSheet.show(requireActivity(), () -> {
                                if (isAdded()) {
                                    updateLoginState();
                                }
                            });
                        });
                        return;
                    }
                    profile = ForumParser.parseUserProfile(altHtml);
                }

                if (!isAdded()) return;
                final UserProfile finalProfile = profile;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (finalProfile != null && finalProfile.getUsername() != null) {
                        displayProfile(finalProfile);
                    } else {
                        android.widget.Toast.makeText(requireContext(),
                                "无法加载用户资料，请确认已登录", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    android.widget.Toast.makeText(requireContext(),
                            "加载资料失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 将 UserProfile 展示到新 UI 控件
     */
    private void displayProfile(UserProfile profile) {
        // 保存登录信息
        java.util.Map<String, String> loginInfo = new java.util.HashMap<>();
        if (profile.getUsername() != null) loginInfo.put("username", profile.getUsername());
        if (profile.getUid() != null) loginInfo.put("uid", profile.getUid());
        if (profile.getAvatarUrl() != null) loginInfo.put("avatarUrl", profile.getAvatarUrl());
        if (profile.getLevel() != null) loginInfo.put("level", profile.getLevel());
        UserSessionManager.getInstance().saveLoginInfo(requireContext(), loginInfo);

        // 头像
        String avatarUrl = profile.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(binding.ivAvatar);
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_account);
        }

        // 用户名
        binding.tvUsername.setText(profile.getUsername() != null ? profile.getUsername() : "未知用户");

        // UID
        binding.tvUid.setText("UID: " + (profile.getUid() != null ? profile.getUid() : "—"));

        // 等级（绿色小徽章）
        binding.tvLevel.setText(profile.getLevel() != null ? profile.getLevel() : "");

        // 用户组
        binding.tvGroup.setText(profile.getGroupName() != null ? profile.getGroupName() : "");

        // 五列统计
        binding.tvThreads.setText(String.valueOf(profile.getThreads()));
        binding.tvReplies.setText(String.valueOf(profile.getPosts()));
        binding.tvFriends.setText(String.valueOf(profile.getFriends()));
        // 关注数以服务端个人资料为准，避免本地操作缓存与网页端实际关注关系不一致。
        // FollowStateManager 只记录本 App 操作过的 UID，不能代表账号的完整关注数。
        binding.tvFollowing.setText(String.valueOf(profile.getFollowing()));
        binding.tvFollowers.setText(String.valueOf(profile.getFollowers()));

        // 积分/金币/在线时长
        binding.tvCredits.setText(String.valueOf(profile.getCredits()));
        binding.tvGold.setText(String.valueOf(profile.getGold()));
        binding.tvOnlineTime.setText(profile.getOnlineTime() != null ? profile.getOnlineTime() : "0小时");


    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}