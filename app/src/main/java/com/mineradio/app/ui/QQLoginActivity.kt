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

class QQLoginActivity : AppCompatActivity() {

    companion object {
        const val RESULT_COOKIE = "qq_music_cookie"
        // PC 版 QQ 音乐登录页 (桌面UA)
        const val QQ_LOGIN_URL = "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=101487368&redirect_uri=https://y.qq.com/portal/playlist.html&state=mineradio"
        const val PC_LOGIN_HOME = "https://y.qq.com/"
    }

    private lateinit var webView: WebView
    private var detectionInjectCount = 0
    private var loginCompleted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qq_login)

        webView = findViewById(R.id.qqLoginWebView)
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
            // PC 浏览器 UA, 使 QQ 音乐展示桌面版登录
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        // 接受所有 Cookie
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (loginCompleted) return
                detectionInjectCount++
                Log.d("QQLogin", "onPageFinished #$detectionInjectCount url=$url")
                if (detectionInjectCount <= 30) {
                    checkLoginCookie()
                }
            }
        }

        // 接收 JS 回调 (由检测脚本触发)
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onLoginDetected(rawCookie: String) {
                runOnUiThread {
                    if (!loginCompleted) handleLoginSuccess(rawCookie)
                }
            }
        }, "QQBridge")

        // 加载 QQ 音乐 PC 首页(会显示登录按钮)
        webView.loadUrl(PC_LOGIN_HOME)
        Toast.makeText(this, "请在打开的页面中点击登录, 使用手机QQ扫码", Toast.LENGTH_LONG).show()

        // ★ 立即启动 JS 轮询，不等待条件满足
        injectCookiePolling()
    }

    /**
     * 收集所有 QQ 音乐相关域名下的 Cookie，合并去重。
     * QQ 音乐登录涉及多个域名：y.qq.com / c.y.qq.com / u.y.qq.com / graph.qq.com / ptlogin2.qq.com / .qq.com
     * 只查单一 URL 会漏掉分布在不同域名下的关键 Cookie。
     */
    private fun collectAllQQCookies(): String {
        val domains = listOf(
            ".qq.com",
            "https://y.qq.com/",
            "https://c.y.qq.com/",
            "https://u.y.qq.com/",
            "https://i.y.qq.com/",
            "https://graph.qq.com/",
            "https://xui.ptlogin2.qq.com/",
            "https://ssl.ptlogin2.qq.com/"
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

    /**
     * 使用精确的正则匹配 Cookie key，避免子串误判。
     * 例如 "uin=" 不会错误匹配 "p_uin=" 或 "pt_uin="。
     */
    private fun hasQQLogin(cookies: String): Boolean {
        val hasUin = Regex("(^|;\\s*)(uin|qqmusic_uin|wxuin|p_uin)=").containsMatchIn(cookies)
        val hasKey = Regex("(^|;\\s*)(qm_keyst|qqmusic_key|music_key|p_skey|skey|psrf_qqaccess_token|psrf_qqrefresh_token|wxrefresh_token|wxskey)=").containsMatchIn(cookies)
        return hasUin && hasKey
    }

    private fun checkLoginCookie() {
        val cookies = collectAllQQCookies()
        Log.d("QQLogin", "checkLoginCookie #$detectionInjectCount cookies=${cookies.take(200)}")
        // ★ 至少等 3 次页面加载后再判断，避免登录流程未完成就提前触发
        if (hasQQLogin(cookies) && detectionInjectCount >= 3) {
            handleLoginSuccess(cookies)
        } else if (detectionInjectCount <= 8) {
            // 提示用户等待会话完成
            val hasUin = Regex("(^|;\\s*)(uin|qqmusic_uin|wxuin|p_uin)=").containsMatchIn(cookies)
            if (hasUin) {
                Toast.makeText(this, "正在等待QQ音乐会话完成...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun injectCookiePolling() {
        webView.evaluateJavascript("""
            (function(){
                if (window.__qqLoginPollStarted) return;
                window.__qqLoginPollStarted = true;
                setInterval(function(){
                    if (window.__qqLoginDone) return;
                    var c = document.cookie;
                    var hasUin = /(^|;\\s*)(uin|qqmusic_uin|wxuin|p_uin)=/.test(c);
                    var hasKey = /(^|;\\s*)(qm_keyst|qqmusic_key|music_key|p_skey|skey|psrf_qqaccess_token|psrf_qqrefresh_token|wxrefresh_token|wxskey)=/.test(c);
                    if (hasUin && hasKey) {
                        window.__qqLoginDone = true;
                        if (typeof QQBridge !== 'undefined') {
                            QQBridge.onLoginDetected(c);
                        }
                    }
                }, 1500);
            })();
        """.trimIndent(), null)
    }

    private fun handleLoginSuccess(cookies: String) {
        loginCompleted = true
        Toast.makeText(this, "QQ音乐登录成功", Toast.LENGTH_SHORT).show()
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
        webView.removeJavascriptInterface("QQBridge")
        webView.destroy()
        super.onDestroy()
    }
}
