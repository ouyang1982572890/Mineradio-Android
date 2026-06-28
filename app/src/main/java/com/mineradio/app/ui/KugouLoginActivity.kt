package com.mineradio.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mineradio.app.R
import kotlin.concurrent.thread
import java.security.MessageDigest

class KugouLoginActivity : AppCompatActivity() {

    companion object {
        const val RESULT_COOKIE = "kugou_music_cookie"
    }

    private lateinit var webView: WebView
    private var detectionInjectCount = 0
    private var loginCompleted = false
    // ★ 桌面端 Chrome UA，显示 PC 版登录界面
    private val kgUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kugou_login)

        // ★ 预种设备 Cookie（go-music-dl: initKugouLoginDevice）
        ensureDeviceCookies()

        webView = findViewById(R.id.kugouLoginWebView)
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
            userAgentString = kgUA
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
        }, "KGBridge")

        // 直接加载酷狗官网，用户在 WebView 中扫码登录
        Toast.makeText(this, "请用酷狗App扫码登录", Toast.LENGTH_LONG).show()
        webView.loadUrl("https://www.kugou.com/")
        injectCookiePolling()
    }

    // ★ go-music-dl: initKugouLoginDevice — 预种设备标识 Cookie
    private fun ensureDeviceCookies() {
        val existing = CookieManager.getInstance().getCookie(".kugou.com") ?: ""
        if (existing.contains("KUGOU_API_GUID=")) return

        val guid = randomHex(8) + "-" + randomHex(4) + "-" + randomHex(4) + "-" + randomHex(4) + "-" + randomHex(12)
        val mid = kgMD5(guid).let { 
            java.math.BigInteger(1, it).toString() 
        }
        val mac = randomHex(12).uppercase()
        val dev = randomHex(16).uppercase()

        val domains = listOf(
            "https://.kugou.com/",
            "https://h5.kugou.com/",
            "https://login-user.kugou.com/",
            "https://m.kugou.com/"
        )
        for (domain in domains) {
            CookieManager.getInstance().setCookie(domain, "KUGOU_API_GUID=$guid; Domain=.kugou.com; Path=/")
            CookieManager.getInstance().setCookie(domain, "KUGOU_API_MID=$mid; Domain=.kugou.com; Path=/")
            CookieManager.getInstance().setCookie(domain, "KUGOU_API_MAC=$mac; Domain=.kugou.com; Path=/")
            CookieManager.getInstance().setCookie(domain, "KUGOU_API_DEV=$dev; Domain=.kugou.com; Path=/")
        }
        CookieManager.getInstance().flush()
        Log.d("KugouLogin", "Device cookies set: guid=$guid mid=$mid")
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789ABCDEF"
        return (1..len).map { chars[(Math.random() * chars.length).toInt()] }.joinToString("")
    }

    private fun kgMD5(input: String): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray())
    }

    private fun checkLoginCookie() {
        val cookies = collectAllKGCookies()
        if (hasKGLogin(cookies) && detectionInjectCount >= 3) {
            handleLoginSuccess(cookies)
        }
    }

    private fun injectCookiePolling() {
        webView.evaluateJavascript("""
            (function(){
                if (window.__kgLoginPollStarted) return;
                window.__kgLoginPollStarted = true;
                setInterval(function(){
                    var c = document.cookie;
                    var hasUserId = c.indexOf('userid=') >= 0 || c.indexOf('KugooID=') >= 0 || c.indexOf('KuGoo=') >= 0;
                    var hasToken = c.indexOf('token=') >= 0 || c.indexOf('kg_mid=') >= 0;
                    if (hasUserId && hasToken) {
                        if (typeof KGBridge !== 'undefined') {
                            KGBridge.onLoginDetected(c);
                        }
                    }
                }, 1500);
            })();
        """.trimIndent(), null)
    }

    private fun collectAllKGCookies(): String {
        val domains = listOf(
            ".kugou.com",
            "https://h5login.kugou.com/",
            "https://www.kugou.com/",
            "https://m.kugou.com/"
        )
        val all = mutableSetOf<String>()
        for (domain in domains) {
            val c = CookieManager.getInstance().getCookie(domain)
            if (!c.isNullOrBlank()) {
                c.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { all.add(it) }
            }
        }
        return all.joinToString("; ")
    }

    private fun hasKGLogin(cookies: String): Boolean {
        val hasUserId = Regex("(^|;\\s*)userid=").containsMatchIn(cookies)
                || Regex("(^|;\\s*)KugooID=").containsMatchIn(cookies)
                || Regex("(^|;\\s*)KuGoo=").containsMatchIn(cookies)
        val hasToken = Regex("(^|;\\s*)token=").containsMatchIn(cookies)
                || Regex("(^|;\\s*)kg_mid=").containsMatchIn(cookies)
        return hasUserId && hasToken
    }

    private fun handleLoginSuccess(cookies: String) {
        loginCompleted = true
        Toast.makeText(this, "酷狗音乐登录成功", Toast.LENGTH_SHORT).show()
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
        webView.removeJavascriptInterface("KGBridge")
        webView.destroy()
        super.onDestroy()
    }
}
