package com.solosu.mtforum.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动签到管理器。
 * 流程:1.检查登录态 2.检查设置开关 3.本地签到记录 4.网络请求签到状态 5.本地判断后执行签到。
 */
public final class AutoSignInManager {
    private static final String PREF_NAME = "app_settings";
    private static final String KEY_AUTO_SIGN_IN = "auto_sign_in_enabled";
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AutoSignInManager() {
    }

    public interface Callback {
        void onFinished(boolean success, boolean performed, String message);
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SIGN_IN, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_SIGN_IN, enabled).apply();
    }

    /**
     * 自动签到流程:1.检查登录态 2.检查设置开关 3.本地签到记录 4.网络请求签到状态 5.本地判断后执行签到。
     * 每一步都顺序执行,并发锁防止重复请求。
     */
    public static void checkAndSignIn(Context context, Callback callback) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        HttpClient client = HttpClient.getInstance();
        if (!CHECKING.compareAndSet(false, true)) return;

        // 第一步:检查登录状态
        if (!client.isLoggedIn()) {
            CHECKING.set(false);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onFinished(false, false, "请先登录"));
            }
            return;
        }

        // 第二步:检查是否开启自动签到
        if (!isEnabled(context)) {
            CHECKING.set(false);
            return;
        }

        // 第三步:检查本地签到记录(今天已签到则跳过,防止重复签到)
        if (UserSessionManager.getInstance().isSignedInToday(appContext)) {
            CHECKING.set(false);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onFinished(true, false, "今日已签到"));
            }
            return;
        }

        new Thread(() -> {
            boolean success = false;
            boolean performed = false;
            String message = "";
            try {
                // 第四步:网络请求获取签到状态
                String html = client.get(ForumParser.getForumlistMobileUrl());
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)) {
                    success = false;
                    message = "登录状态已失效";
                } else {
                    ForumParser.CommunityPageData data = ForumParser.parseCommunityPage(html);
                    String signText = data.getSignInText();

                    // 第五步:本地判断是否已签到
                    // 优先检查页面文本中是否包含"已签到"关键词(兜底:按钮文本 + 页面全文)
                    boolean pageShowsSignedIn = isAlreadySigned(signText)
                            || data.isAlreadySignedIn()
                            || html.contains("今日已签")
                            || html.contains("已签到");
                    if (pageShowsSignedIn) {
                        success = true;
                        message = "今日已签到";
                        // 同步本地签到记录,防止下次重复请求
                        UserSessionManager.getInstance().saveSignInDate(appContext);
                    } else if (data.isLoginRequired()) {
                        message = "请先登录";
                    } else if (!isNotSigned(signText)) {
                        message = "无法确认当前签到状态";
                    } else {
                        // 未签到 -> 执行自动签到
                        String formhash = data.getFormhash();
                        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html);
                        if (TextUtils.isEmpty(formhash)) {
                            message = "无法获取签到凭证";
                        } else {
                            Map<String, String> params = new HashMap<>();
                            params.put("formhash", formhash);
                            String signUrl = HttpClient.BASE_URL
                                    + "plugin.php?id=k_misign:sign&operation=qiandao&format=text";
                            String result = client.post(signUrl, params);
                            message = cleanMessage(result);
                            success = isSignSuccess(message);
                            performed = true;
                            if (success) {
                                UserSessionManager.getInstance().saveSignInDate(appContext);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                message = e.getMessage() == null ? "自动签到失败" : e.getMessage();
            } finally {
                CHECKING.set(false);
            }

            if (callback != null) {
                final boolean finalSuccess = success;
                final boolean finalPerformed = performed;
                final String finalMessage = message;
                MAIN_HANDLER.post(() -> callback.onFinished(finalSuccess, finalPerformed, finalMessage));
            }
        }).start();
    }

    private static boolean isAlreadySigned(String text) {
        if (TextUtils.isEmpty(text)) return false;
        return text.contains("今日已签") || text.contains("已签到") || text.contains("已签");
    }

    private static boolean isNotSigned(String text) {
        if (TextUtils.isEmpty(text)) return false;
        return text.contains("签到") && !isAlreadySigned(text);
    }

    private static boolean isSignSuccess(String text) {
        if (TextUtils.isEmpty(text)) return false;
        if (text.contains("失败") || text.contains("错误") || text.contains("请先登录")
                || text.contains("没有权限") || text.contains("非法操作")) return false;
        return text.contains("签到成功") || text.contains("今日已签")
                || text.contains("已签到") || text.contains("成功")
                || text.toLowerCase().contains("success")
                || text.toLowerCase().contains("succeed");
    }

    private static String cleanMessage(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String text = raw.replaceAll("<!\\[CDATA\\[(.*?)\\]\\]>", "$1")
                .replaceAll("<[^>]+>", "")
                .trim();
        return text.isEmpty() ? raw.trim() : text;
    }
}
