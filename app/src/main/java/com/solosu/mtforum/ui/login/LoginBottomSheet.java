package com.solosu.mtforum.ui.login;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录底部弹窗(与回复弹窗同风格)。
 * 支持两种模式:账号密码登录 / Cookie 粘贴登录。
 */
public class LoginBottomSheet {

    @FunctionalInterface
    public interface OnLoginListener {
        void onLoginSuccess();
    }

    private static BottomSheetDialog current;

    private LoginBottomSheet() {
    }

    public static void show(Activity activity, OnLoginListener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        dismissCurrent();
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        current = dialog;
        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        final View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_login_bottom_sheet, null);

        MaterialCardView dialogCard = dialogView.findViewById(R.id.dialog_card);
        ImageView ivClose = dialogView.findViewById(R.id.iv_close);
        final LinearLayout formNormal = dialogView.findViewById(R.id.login_form_normal);
        final LinearLayout formCookie = dialogView.findViewById(R.id.login_form_cookie);
        TextInputEditText etUsername = dialogView.findViewById(R.id.et_username);
        TextInputEditText etPassword = dialogView.findViewById(R.id.et_password);
        TextInputEditText etCookie = dialogView.findViewById(R.id.et_cookie);
        TextInputLayout tilUsername = dialogView.findViewById(R.id.til_username);
        TextInputLayout tilPassword = dialogView.findViewById(R.id.til_password);
        TextInputLayout tilCookie = dialogView.findViewById(R.id.til_cookie);
        MaterialButton btnLogin = dialogView.findViewById(R.id.btn_login);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
        TextView tvError = dialogView.findViewById(R.id.tv_error);
        final TextView tvToggle = dialogView.findViewById(R.id.tv_toggle_mode);

        // 当前模式: false = 账号密码, true = Cookie
        final boolean[] isCookieMode = new boolean[1];

        if (dialogCard != null) {
            FrostedGlassHelper.applyToCardViews(dialogCard, activity);
        }

        dialog.setContentView(dialogView);
        DialogHelper.applyToBottomSheet(dialog, dialogView, activity);
        dialog.setOnDismissListener(d -> {
            if (current == dialog) {
                current = null;
            }
        });

        // 预取 formhash(仅账号密码模式需要,失败静默)
        // build57: [0]=formhash [1]=登录表单 action(含 loginhash)
        final String[] formhashRef = new String[2];
        new Thread(() -> {
            try {
                // build59: 预取必须先清登录态. 带旧 _auth 访问登录页, 服务端直接返回
                // "已登录"提示页, formhash/loginform 全没了 -> 切换账号死循环
                if (HttpClient.getInstance().isLoggedIn()) {
                    HttpClient.getInstance().clearCookies(activity);
                }
                String html = HttpClient.getInstance().get(ForumParser.getLoginUrl());
                String fh = ForumParser.parseFormhash(html);
                if (!TextUtils.isEmpty(fh)) {
                    formhashRef[0] = fh;
                    formhashRef[1] = ForumParser.extractLoginPostUrl(html);
                }
            } catch (Exception ignored) {
            }
        }).start();

        ivClose.setOnClickListener(v -> dialog.dismiss());

        // 切换登录模式
        tvToggle.setOnClickListener(v -> {
            isCookieMode[0] = !isCookieMode[0];
            boolean cookie = isCookieMode[0];
            formNormal.setVisibility(cookie ? View.GONE : View.VISIBLE);
            formCookie.setVisibility(cookie ? View.VISIBLE : View.GONE);
            tvError.setVisibility(View.GONE);
            if (cookie) {
                tvToggle.setText("账号密码登录");
                btnLogin.setText("Cookie 登录");
            } else {
                tvToggle.setText("Cookie 登录");
                btnLogin.setText(activity.getString(R.string.action_login));
            }
        });

        // 键盘回车触发登录
        etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performLogin(dialog, activityRef, formNormal, formCookie, etUsername, etPassword,
                        etCookie, tilUsername, tilPassword, tilCookie, btnLogin, progressBar,
                        tvError, formhashRef, isCookieMode, listener);
                return true;
            }
            return false;
        });

        btnLogin.setOnClickListener(v -> performLogin(dialog, activityRef, formNormal, formCookie,
                etUsername, etPassword, etCookie, tilUsername, tilPassword, tilCookie, btnLogin,
                progressBar, tvError, formhashRef, isCookieMode, listener));

        dialog.setOnShowListener(d -> {
            View parent = (View) dialogView.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                parent.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                parent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                int contentHeight = dialogView.getHeight();
                                if (contentHeight > 0) {
                                    int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.75d);
                                    behavior.setPeekHeight(Math.min(contentHeight + dp(activity, 48), maxHeight));
                                }
                            }
                        });
            }
        });

        dialog.show();
    }

    private static int dp(Activity activity, float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private static void dismissCurrent() {
        if (current != null && current.isShowing()) {
            current.dismiss();
        }
        current = null;
    }

    private static void performLogin(final BottomSheetDialog dialog,
                                     final WeakReference<Activity> activityRef,
                                     final LinearLayout formNormal,
                                     final LinearLayout formCookie,
                                     final TextInputEditText etUsername,
                                     final TextInputEditText etPassword,
                                     final TextInputEditText etCookie,
                                     final TextInputLayout tilUsername,
                                     final TextInputLayout tilPassword,
                                     final TextInputLayout tilCookie,
                                     final MaterialButton btnLogin,
                                     final ProgressBar progressBar,
                                     final TextView tvError,
                                     final String[] formhashRef,
                                     final boolean[] isCookieMode,
                                     final OnLoginListener listener) {
        final Activity activity = activityRef != null ? activityRef.get() : null;
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (isCookieMode[0]) {
            doCookieLogin(dialog, activity, formNormal, formCookie, etCookie, tilCookie,
                    btnLogin, progressBar, tvError, listener);
        } else {
            doPasswordLogin(dialog, activity, etUsername, etPassword, tilUsername, tilPassword,
                    btnLogin, progressBar, tvError, formhashRef, listener);
        }
    }

    // build58: 兜底时提取响应正文片段, 让用户看到真实拒绝原因
    private static String snippet(String html) {
        if (html == null) return "(无响应)";
        try {
            String text = android.text.TextUtils.isEmpty(html) ? "" : html
                    .replaceAll("(?s)<script.*?</script>", " ")
                    .replaceAll("(?s)<style.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&#\\d+;", " ").replaceAll("&[a-z]+;", " ")
                    .replaceAll("\\s+", " ").trim();
            return text.length() > 80 ? text.substring(0, 80) : text;
        } catch (Exception e) {
            return "(解析失败)";
        }
    }

    // ==================== 密码登录 ====================
    private static void doPasswordLogin(final BottomSheetDialog dialog,
                                        final Activity activity,
                                        final TextInputEditText etUsername,
                                        final TextInputEditText etPassword,
                                        final TextInputLayout tilUsername,
                                        final TextInputLayout tilPassword,
                                        final MaterialButton btnLogin,
                                        final ProgressBar progressBar,
                                        final TextView tvError,
                                        final String[] formhashRef,
                                        final OnLoginListener listener) {
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();

        if (TextUtils.isEmpty(username)) {
            tilUsername.setError("请输入帐号");
            etUsername.requestFocus();
            return;
        }
        tilUsername.setError(null);
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("请输入密码");
            etPassword.requestFocus();
            return;
        }
        tilPassword.setError(null);

        setLoading(btnLogin, progressBar, true);
        tvError.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                if (TextUtils.isEmpty(formhashRef[0])) {
                    // build57: 带有效登录态访问登录页, 服务端直接返回已登录提示页, formhash 拿不到
                    // (切换账号场景). 先清 Cookie 再 GET.
                    if (HttpClient.getInstance().isLoggedIn()) {
                        HttpClient.getInstance().clearCookies(activity);
                    }
                    String loginHtml = HttpClient.getInstance().get(ForumParser.getLoginUrl());
                    formhashRef[0] = ForumParser.parseFormhash(loginHtml);
                    if (!TextUtils.isEmpty(formhashRef[0])) {
                        formhashRef[1] = ForumParser.extractLoginPostUrl(loginHtml);
                    }
                    // build58: formhash 仍为空 -> 分流报错, 不再糊一句"获取登录信息失败"
                    if (TextUtils.isEmpty(formhashRef[0])) {
                        final String msg;
                        if (loginHtml == null) {
                            msg = "网络错误,取不到登录页(检查网络后重试)";
                        } else if (loginHtml.contains("you have been blocked")
                                || loginHtml.contains("403 Forbidden")) {
                            msg = "被站点防护拦截(403),请求太频繁,稍等几分钟再试";
                        } else if (!loginHtml.contains("formhash")) {
                            msg = "登录页异常(可能被风控),稍后再试或换Cookie方式登录";
                        } else {
                            msg = "解析登录页失败,请重新点登录";
                        }
                        runOnUi(activity, () -> {
                            setLoading(btnLogin, progressBar, false);
                            showError(tvError, msg);
                        });
                        return;
                    }
                }
                Map<String, String> params = new HashMap<>();
                params.put("formhash", formhashRef[0]);
                params.put("username", username);
                params.put("password", password);
                params.put("loginsubmit", "yes");
                params.put("fastloginfield", "username");
                params.put("cookietime", "2592000");

                // build57: 用登录表单真实 action(含 loginhash); 旧代码把 formhash 拼在 URL 尾是坏地址
                String postUrl = TextUtils.isEmpty(formhashRef[1])
                        ? ForumParser.getLoginPostUrl() : formhashRef[1];
                String result = HttpClient.getInstance().post(postUrl, params);

                if (HttpClient.getInstance().isLoggedIn()) {
                    finishLoginSuccess(dialog, activity, username, btnLogin, progressBar, listener);
                } else {
                    // build58: 7 层错误分流, 不再糊成"请检查帐号和密码"
                    String errorMsg = "登录失败:" + snippet(result);
                    if (result.contains("密码错误") || (result.contains("密码") && result.contains("错误"))) {
                        errorMsg = "密码错误";
                    } else if (result.contains("用户名") && result.contains("不存在")) {
                        errorMsg = "用户名不存在";
                    } else if (result.contains("次数过多") || result.contains("次数超")) {
                        errorMsg = "密码错误次数过多,临时锁定,请稍后再试";
                    } else if (result.contains("您已经被禁止访问") || result.contains("禁止登录")
                            || result.contains("禁止访问")) {
                        errorMsg = "账号被禁/封禁,无法登录";
                    } else if (result.contains("您已经登录") || result.contains("已经登录")) {
                        errorMsg = "服务器认为当前会话已登录(切换账号场景):重试一次即可";
                    } else if (result.contains("you have been blocked") || result.contains("403 Forbidden")) {
                        errorMsg = "被站点防护拦截(403),请求太频繁,稍等几分钟再试";
                    }
                    final String msg = errorMsg;
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, msg);
                    });
                }
            } catch (Exception e) {
                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    showError(tvError, "网络错误:" + (TextUtils.isEmpty(e.getMessage())
                            ? "请稍后重试" : e.getMessage()));
                });
            }
        }).start();
    }

    // ==================== Cookie 登录 ====================

    private static void doCookieLogin(final BottomSheetDialog dialog,
                                      final Activity activity,
                                      final LinearLayout formNormal,
                                      final LinearLayout formCookie,
                                      final TextInputEditText etCookie,
                                      final TextInputLayout tilCookie,
                                      final MaterialButton btnLogin,
                                      final ProgressBar progressBar,
                                      final TextView tvError,
                                      final OnLoginListener listener) {
        final String cookieStr = etCookie.getText().toString().trim();

        if (TextUtils.isEmpty(cookieStr)) {
            tilCookie.setError("请粘贴 Cookie 内容");
            etCookie.requestFocus();
            return;
        }
        tilCookie.setError(null);

        setLoading(btnLogin, progressBar, true);
        tvError.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                // 清除旧 Cookie,设置新 Cookie
                HttpClient.getInstance().clearCookies();
                HttpClient.getInstance().applyCookiesFromString(cookieStr);

                // 验证是否包含 _auth cookie(登录态标志)
                if (!HttpClient.getInstance().isLoggedIn()) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 无效或已过期,请检查后重试");
                    });
                    return;
                }

                // 进一步验证:请求个人中心确认未跳转登录页
                String verifyUrl = ForumParser.getBaseDomain() + "home.php?mod=space&do=profile&mobile=2";
                String verifyHtml;
                try {
                    verifyHtml = HttpClient.getInstance().get(verifyUrl);
                } catch (Exception e) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 验证失败:" + (e.getMessage() != null ? e.getMessage() : "网络异常"));
                    });
                    return;
                }

                if (ForumParser.isLoginPage(verifyHtml)) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 已过期,请重新获取");
                    });
                    return;
                }

                // Cookie 有效,保存并获取用户信息
                HttpClient.getInstance().commitCookieStore(activity);
                try {
                    UserProfile profile = ForumParser.parseUserProfile(verifyHtml);
                    Map<String, String> loginInfo = new HashMap<>();
                    loginInfo.put("username", profile != null && profile.getUsername() != null
                            ? profile.getUsername() : "用户");
                    loginInfo.put("uid", profile != null && profile.getUid() != null ? profile.getUid() : "0");
                    loginInfo.put("avatarUrl", profile != null && profile.getAvatarUrl() != null
                            ? profile.getAvatarUrl() : "");
                    loginInfo.put("level", profile != null && profile.getLevel() != null ? profile.getLevel() : "");
                    UserSessionManager.getInstance().saveLoginInfo(activity, loginInfo);
                } catch (Exception ignored) {
                }

                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    if (listener != null) {
                        listener.onLoginSuccess();
                    }
                });
            } catch (Exception e) {
                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    showError(tvError, "Cookie 登录失败:" + (TextUtils.isEmpty(e.getMessage())
                            ? "请稍后重试" : e.getMessage()));
                });
            }
        }).start();
    }

    // ==================== 公共方法 ====================

    private static void finishLoginSuccess(BottomSheetDialog dialog, Activity activity,
                                           String username, MaterialButton btnLogin,
                                           ProgressBar progressBar, OnLoginListener listener) {
        // 持久化 Cookie
        // build59: 先落盘再后续操作, 防崩溃留下半状态
        HttpClient.getInstance().commitCookieStore(activity);
        // 获取用户信息
        try {
            String spaceHtml = HttpClient.getInstance().get(
                    ForumParser.getBaseDomain() + "home.php?mod=space&do=profile&mobile=2");
            UserProfile profile = ForumParser.parseUserProfile(spaceHtml);
            if (profile != null && profile.getUsername() == null) {
                profile.setUsername(username);
            }
            Map<String, String> loginInfo = new HashMap<>();
            loginInfo.put("username", profile != null && profile.getUsername() != null
                    ? profile.getUsername() : username);
            loginInfo.put("uid", profile != null && profile.getUid() != null ? profile.getUid() : "0");
            loginInfo.put("avatarUrl", profile != null && profile.getAvatarUrl() != null
                    ? profile.getAvatarUrl() : "");
            loginInfo.put("level", profile != null && profile.getLevel() != null ? profile.getLevel() : "");
            UserSessionManager.getInstance().saveLoginInfo(activity, loginInfo);
        } catch (Exception ignored) {
        }

        runOnUi(activity, () -> {
            setLoading(btnLogin, progressBar, false);
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            if (listener != null) {
                listener.onLoginSuccess();
            }
        });
    }

    private static void setLoading(MaterialButton btn, ProgressBar pb, boolean show) {
        if (btn == null || pb == null) return;
        pb.setVisibility(show ? View.VISIBLE : View.GONE);
        btn.setEnabled(!show);
        btn.setText(show ? "" : btn.getContext().getString(R.string.action_login));
    }

    private static void showError(TextView tv, String msg) {
        if (tv == null) return;
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    private static void runOnUi(Activity activity, Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(runnable);
        }
    }
}
