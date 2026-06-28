package com.mineradio.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mineradio.app.R

class QishuiLoginActivity : AppCompatActivity() {

    companion object {
        const val RESULT_COOKIE = "qishui_music_cookie"
    }

    private lateinit var webView: WebView
    private var detectionInjectCount = 0
    private var loginCompleted = false
    // ★ 桌面端 Chrome UA，强制显示 PC 版登录界面
    private val sodaUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qishui_login)

        webView = findViewById(R.id.qishuiLoginWebView)
        webView.setBackgroundColor(Color.parseColor("#010304"))
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = sodaUA
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (loginCompleted) return
                detectionInjectCount++
                if (detectionInjectCount <= 30) {
                    checkLoginCookie()
                }
            }
        }

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onLoginDetected(rawCookie: String) {
                runOnUiThread {
                    if (!loginCompleted) handleLoginSuccess(rawCookie)
                }
            }
        }, "QSBridge")

        // 直接加载抖音扫码登录页面
        Toast.makeText(this, "请使用抖音App扫描页面上的二维码登录", Toast.LENGTH_LONG).show()
        webView.loadUrl("https://www.douyin.com/")
        injectCookiePolling()
    }

    private fun checkLoginCookie() {
        val cookies = collectAllQSCookies()
        if (hasLoginSession(cookies) && detectionInjectCount >= 3) {
            handleLoginSuccess(cookies)
        }
    }

    private fun injectCookiePolling() {
        webView.evaluateJavascript("""
            (function(){
                if (window.__qsLoginPollStarted) return;
                window.__qsLoginPollStarted = true;
                setInterval(function(){
                    var c = document.cookie;
                    // 抖音的登录 cookie 包含 passport 相关字段
                    var hasLogin = (c.indexOf('passport_csrf_token') >= 0 && c.indexOf('sessionid') >= 0)
                        || (c.indexOf('sso_uid_tt') >= 0)
                        || (c.indexOf('sid_guard') >= 0 && c.indexOf('uid_tt') >= 0);
                    if (hasLogin) {
                        if (typeof QSBridge !== 'undefined') {
                            QSBridge.onLoginDetected(c);
                        }
                    }
                }, 1500);
            })();
        """.trimIndent(), null)
    }

    private fun collectAllQSCookies(): String {
        val domains = listOf(".douyin.com", ".qishui.com", "https://www.douyin.com/", "https://www.qishui.com/")
        val all = mutableSetOf<String>()
        for (domain in domains) {
            val c = CookieManager.getInstance().getCookie(domain)
            if (!c.isNullOrBlank()) {
                c.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { all.add(it) }
            }
        }
        return all.joinToString("; ")
    }

    private fun hasLoginSession(cookies: String): Boolean {
        val hasDouyin = Regex("(^|;\\s*)(sso_uid_tt|sid_guard|uid_tt)").containsMatchIn(cookies)
        val hasQishui = Regex("(^|;\\s*)(passport_csrf_token)").containsMatchIn(cookies)
                && Regex("(^|;\\s*)sessionid").containsMatchIn(cookies)
        return hasDouyin || hasQishui
    }

    private fun handleLoginSuccess(cookies: String) {
        loginCompleted = true
        Toast.makeText(this, "汽水音乐登录成功", Toast.LENGTH_SHORT).show()
        val result = Intent().apply {
            putExtra(RESULT_COOKIE, cookies)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("QSBridge")
        webView.destroy()
        super.onDestroy()
    }
}
