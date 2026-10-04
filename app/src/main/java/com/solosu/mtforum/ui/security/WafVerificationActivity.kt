package com.solosu.mtforum.ui.security

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivityWafVerificationBinding
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.network.WafChallenge
import com.solosu.mtforum.util.ToastUtil as Toast

/**
 * WAF 人机验证兜底页。
 *
 * 站点下发阿里云 ESA 的 JS 挑战页时，正常情况下由 [WafChallenge] 本地求解 acw_sc__v2。
 * 一旦站点换版导致算法失配，本地求解会持续失败、页面恒为空——此时改用真实 WebView
 * 让用户本人完成人机验证：WAF 通过后会把 acw_sc__v2 等 Cookie 写进 WebView，
 * 本页再把 Cookie 同步回 OkHttp 会话，后续原生请求即可恢复正常。
 *
 * 本页只做「用户手动通过验证 + Cookie 回注」，不含任何自动化破解或请求重放。
 */
class WafVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWafVerificationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityWafVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnDone.setOnClickListener { syncBackAndFinish() }

        // 错开转场高峰：等待页面过渡完成再进行 WebView 实际加载，防止抽屉收起或转场掉帧
        binding.root.post {
            if (isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@post
            setupAndLoadWebView()
        }
    }

    private fun setupAndLoadWebView() {
        // 先把原生会话的 Cookie 灌进 WebView，避免验证通过后同步时用旧值覆盖登录态
        HttpClient.getInstance().syncToCookieManager()

        val webView = binding.webView
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // UA 必须与 OkHttp 发出的请求完全一致：WAF 下发 clearance cookie 时会绑定 UA 指纹，
        // 两者不一致时即使 Cookie 同步成功也会被判为伪造并继续拦截。
        // 用触发挑战那次请求的 UA（移动/桌面两套 UA 各自配对），而不是固定值。
        webView.settings.userAgentString = HttpClient.getInstance().getWafChallengeUserAgent()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                onPageSettled(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 站点与验证挑战都在同一域名内，放行由 WebView 自行处理
                return false
            }
        }

        webView.loadUrl(HttpClient.BASE_URL)
    }

    /** 页面加载结束后同步 Cookie，并检测人机验证是否已通过。 */
    private fun onPageSettled(view: WebView?) {
        val webView = view ?: return
        // 先强制把 WebView 内存中的 Cookie 落盘，再回注，避免提取到旧值/漏值
        CookieManager.getInstance().flush()
        // 无条件同步一次：WAF 通过后会把新 Cookie 写进 WebView，回注后原生请求才生效
        HttpClient.getInstance().syncFromCookieManager()
        webView.evaluateJavascript("document.cookie") { cookie ->
            if (isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@evaluateJavascript
            if (cookie != null && cookie.contains(WafChallenge.COOKIE_NAME)) {
                binding.tvStatus.text = "验证已通过，可返回继续使用"
                HttpClient.getInstance().clearWafChallenge()
            } else {
                binding.tvStatus.text = "若页面出现人机验证，请在此完成"
            }
        }
    }

    private fun syncBackAndFinish() {
        CookieManager.getInstance().flush()
        HttpClient.getInstance().syncFromCookieManager()
        Toast.makeText(this, "已同步验证结果", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        @JvmStatic
        fun intent(context: android.content.Context): Intent {
            return Intent(context, WafVerificationActivity::class.java)
        }
    }
}
