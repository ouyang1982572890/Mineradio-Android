package com.mineradio.app.ui

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.DialogInterface
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mineradio.app.R
import com.mineradio.app.manager.MediaSessionManager
import com.mineradio.app.manager.MineradioServer
import com.mineradio.app.manager.NeteaseMusicApi
import com.mineradio.app.view.DesktopLyricView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object DesktopLyricBridge {
    @Volatile
    var currentText: String = ""
    // ★ 锁屏控制器用：歌曲元数据
    @Volatile
    var songTitle: String = ""
    @Volatile
    var songArtist: String = ""
    @Volatile
    var isPlaying: Boolean = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var server: MineradioServer
    private var bridgeInjected = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 7001
    private val CAMERA_PERMISSION_REQUEST = 7002
    private val QQ_LOGIN_REQUEST = 7003
    private val QISHUI_LOGIN_REQUEST = 7005
    private val KUGOU_LOGIN_REQUEST = 7006
    private val LOCATION_PERMISSION_REQUEST = 7007
    private val FOLDER_PICKER_REQUEST = 7004
    private var qqLoginCallback: ((String) -> Unit)? = null
    private var musicPlaying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionManager? = null
    private var desktopLyricView: DesktopLyricView? = null
    @Volatile var desktopLyricText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NeteaseMusicApi.init(this)

        // 创建通知渠道
        createMediaNotificationChannel()

        // 初始化系统媒体会话（通知栏/锁屏控件）
        try {
            mediaSession = MediaSessionManager(this, MainActivity::class.java)
            mediaSession?.init()
        } catch (e: Exception) {
            Log.e("MainActivity", "MediaSession 初始化失败: ${e.message}", e)
        }
        // 回调在 setupWebView 中绑定

        // 处理通知栏按钮发来的 media_action
        handleMediaActionIntent(intent)

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    7004
                )
            }
        }

        // ★ 定位权限 — 天气面板需要
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }

        server = MineradioServer(this)
        val port = server.start()

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.mainWebView)
        setupWebView(port)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMediaActionIntent(intent)
    }

    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MediaSessionManager.CHANNEL_ID,
                MediaSessionManager.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示当前播放歌曲和控制按钮"
                setShowBadge(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun handleMediaActionIntent(intent: Intent?) {
        val action = intent?.getStringExtra("media_action") ?: return
        mediaSession?.handleMediaAction(action)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(port: Int) {
        webView.setBackgroundColor(Color.parseColor("#010304"))
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(KAppBridge(), "KeepApp")

        // 绑定系统媒体控件回调（通知栏/锁屏按钮 -> WebView JS）
        mediaSession?.onPlayClicked = { webView.evaluateJavascript("if(window.togglePlay)window.togglePlay()", null) }
        mediaSession?.onPauseClicked = { webView.evaluateJavascript("if(window.togglePlay)window.togglePlay()", null) }
        mediaSession?.onSkipNextClicked = { webView.evaluateJavascript("if(window.nextTrack)window.nextTrack()", null) }
        mediaSession?.onSkipPrevClicked = { webView.evaluateJavascript("if(window.prevTrack)window.prevTrack()", null) }
        mediaSession?.onSeekTo = { pos ->
            val sec = pos / 1000.0
            webView.evaluateJavascript("if(window.handleMediaSeek)window.handleMediaSeek($sec)", null)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        // 音频
                        "audio/mpeg", "audio/flac", "audio/wav", "audio/ogg",
                        "audio/x-m4a", "audio/aac", "audio/x-ms-wma",
                        "audio/aiff", "audio/x-ape", "audio/opus", "audio/mp4",
                        // 图片 (自定义背景)
                        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp",
                        // ★ 视频 (自定义背景视频)
                        "video/mp4", "video/webm", "video/quicktime", "video/x-msvideo",
                        "video/x-matroska", "video/mpeg", "video/x-ms-wmv", "video/3gpp",
                        "video/mp2t", "video/x-flv"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                return true
            }
            // ★ 定位权限：允许 WebView 使用 GPS
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!bridgeInjected) {
                    bridgeInjected = true
                    view?.post { view.evaluateJavascript(BRIDGE_JS, null) }
                }
            }
        }
        webView.loadUrl("http://127.0.0.1:$port/")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QQ_LOGIN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val cookie = data.getStringExtra(QQLoginActivity.RESULT_COOKIE) ?: ""
                if (cookie.isNotBlank()) {
                    webView.evaluateJavascript("window._onQQLoginCookie(${org.json.JSONObject.quote(cookie)})", null)
                }
            } else {
                Toast.makeText(this, "QQ音乐登录已取消", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == QISHUI_LOGIN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val cookie = data.getStringExtra(QishuiLoginActivity.RESULT_COOKIE) ?: ""
                if (cookie.isNotBlank()) {
                    webView.evaluateJavascript("window._onQishuiLoginCookie(${org.json.JSONObject.quote(cookie)})", null)
                }
            } else {
                Toast.makeText(this, "汽水音乐登录已取消", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == KUGOU_LOGIN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val cookie = data.getStringExtra(KugouLoginActivity.RESULT_COOKIE) ?: ""
                if (cookie.isNotBlank()) {
                    webView.evaluateJavascript("window._onKugouLoginCookie(${org.json.JSONObject.quote(cookie)})", null)
                }
            } else {
                Toast.makeText(this, "酷狗音乐登录已取消", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val cb = fileChooserCallback ?: return
            fileChooserCallback = null
            if (resultCode == RESULT_OK && data != null) {
                val clipData = data.clipData
                val rawUris = mutableListOf<Uri>()
                if (clipData != null && clipData.itemCount > 0) {
                    for (i in 0 until clipData.itemCount) rawUris.add(clipData.getItemAt(i).uri)
                } else {
                    val uri = data.data
                    if (uri != null) rawUris.add(uri)
                }
                if (rawUris.isEmpty()) {
                    cb.onReceiveValue(null)
                    return
                }
                // 后台保存本地文件到内部存储（持久化）
                Thread {
                    for (uri in rawUris) {
                        val mime = contentResolver.getType(uri) ?: ""
                        if (!mime.startsWith("video/")) {
                            saveLocalSongCopy(uri)
                        }
                    }
                }.start()
                // 检查是否有视频, 后台提取音频
                var hasVideo = false
                for (uri in rawUris) {
                    val mime = contentResolver.getType(uri) ?: ""
                    if (mime.startsWith("video/")) { hasVideo = true; break }
                }
                if (hasVideo) {
                    runOnUiThread {
                        Toast.makeText(this, "检测到视频文件, 正在提取音频…", Toast.LENGTH_SHORT).show()
                    }
                    // 后台线程提取音频
                    Thread {
                        val extractedAudioUris = mutableListOf<Uri>()
                        for (uri in rawUris) {
                            val mime = contentResolver.getType(uri) ?: ""
                            if (mime.startsWith("video/")) {
                                val audioUri = extractAudioFromVideo(uri)
                                if (audioUri != null) {
                                    extractedAudioUris.add(audioUri)
                                    // 通知 WebView 有提取好的音频
                                    val videoName = getFileName(uri) ?: "video"
                                    val audioName = videoName.replace(Regex("\\.[^.]+$"), "") + ".aac"
                                    // 保存提取的视频音频到内部存储
                                    val savedName = saveLocalAudioUri(audioUri, audioName)
                                    runOnUiThread {
                                        webView.evaluateJavascript(
                                            "if(window._onVideoAudioExtracted)window._onVideoAudioExtracted('$audioUri','$audioName'" +
                                            (if (savedName != null) ",'$savedName'" else "") + ")", null
                                        )
                                    }
                                } else {
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "音频提取失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                extractedAudioUris.add(uri)
                            }
                        }
                        runOnUiThread {
                            if (extractedAudioUris.isNotEmpty()) {
                                cb.onReceiveValue(extractedAudioUris.toTypedArray())
                            } else {
                                cb.onReceiveValue(null)
                            }
                        }
                    }.start()
                    return
                }
                // 无视频, 直接返回
                cb.onReceiveValue(rawUris.toTypedArray())
            } else {
                cb.onReceiveValue(null)
            }
        }
        if (requestCode == FOLDER_PICKER_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.data != null) {
                val treeUri = data.data!!
                // 持久化权限
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // 后台扫描文件夹
                Thread {
                    val files = scanFolderForAudio(treeUri)
                    runOnUiThread {
                        if (files.isNotEmpty()) {
                            Toast.makeText(this, "找到 ${files.size} 个音频文件，开始分析...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "文件夹中没有找到音频文件", Toast.LENGTH_SHORT).show()
                        }
                        // 将文件列表发送给 JS
                        val jsonArr = org.json.JSONArray()
                        for (f in files) {
                            val obj = org.json.JSONObject()
                            obj.put("name", f.name)
                            obj.put("path", f.uri.toString())
                            obj.put("size", f.size)
                            jsonArr.put(obj)
                        }
                        webView.evaluateJavascript(
                            "if(window._onFolderScanned)window._onFolderScanned('${jsonArr.toString().replace("'", "\\'")}')",
                            null
                        )
                    }
                    // 逐个分析音频文件
                    for ((index, f) in files.withIndex()) {
                        val info = analyzeAudioFile(f.uri)
                        runOnUiThread {
                            val infoJson = org.json.JSONObject()
                            infoJson.put("index", index)
                            infoJson.put("duration", info.first)
                            infoJson.put("artist", info.second)
                            infoJson.put("title", info.third)
                            webView.evaluateJavascript(
                                "if(window._onLocalSongAnalyzed)window._onLocalSongAnalyzed($index,'${infoJson.toString().replace("'", "\\'")}')",
                                null
                            )
                        }
                    }
                }.start()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    /**
     * 将用户导入的音频文件复制到内部存储，确保重启后仍可播放
     * @return 保存后的文件名
     */
    private fun saveLocalSongCopy(uri: Uri): String? {
        return try {
            val songsDir = File(filesDir, "local_songs")
            songsDir.mkdirs()
            val originalName = getFileName(uri) ?: "song_${System.currentTimeMillis()}"
            // 保留原始扩展名
            val ext = originalName.substringAfterLast('.', "")
            val baseName = originalName.substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]"), "_")
                .take(40)
            val safeName = if (ext.isNotEmpty() && ext.length <= 6)
                "${baseName}_${System.currentTimeMillis()}.$ext"
            else
                "song_${System.currentTimeMillis()}.mp3"
            val destFile = File(songsDir, safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0) {
                // ★ 直接写入 localStorage（绕过 pendingLocalSongs 匹配）
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.__addLocalSongWithCachedFile)window.__addLocalSongWithCachedFile('${destFile.name}','${originalName.replace("'", "\\'")}','${safeName}')",
                        null
                    )
                }
                // 保留旧的回调作为兼容
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window._onLocalSongSaved)window._onLocalSongSaved('${destFile.name}','${originalName.replace("'", "\\'")}')",
                        null
                    )
                }
                destFile.name
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("Mineradio", "saveLocalSongCopy error", e)
            null
        }
    }

    /**
     * 将从视频提取的音频保存到内部存储
     */
    private fun saveLocalAudioUri(audioUri: Uri, audioName: String): String? {
        return try {
            val songsDir = File(filesDir, "local_songs")
            songsDir.mkdirs()
            val safeName = audioName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-.]"), "_")
            val destFile = File(songsDir, "${System.currentTimeMillis()}_$safeName")
            contentResolver.openInputStream(audioUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0) destFile.name else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("Mineradio", "saveLocalAudioUri error", e)
            null
        }
    }

    private fun extractAudioFromVideo(videoUri: Uri): Uri? {
        return try {
            val extractDir = File(cacheDir, "video_extract")
            extractDir.mkdirs()
            val outputFile = File(extractDir, "audio_${System.currentTimeMillis()}.aac")
            var extracted = false
            val fd = contentResolver.openFileDescriptor(videoUri, "r")
            if (fd != null) {
                try {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(fd.fileDescriptor)
                    var audioIdx = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) { audioIdx = i; break }
                    }
                    if (audioIdx >= 0) {
                        extractor.selectTrack(audioIdx)
                        val outStream = FileOutputStream(outputFile)
                        val buf = java.nio.ByteBuffer.allocateDirect(256 * 1024)
                        while (true) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size <= 0) break
                            val bytes = ByteArray(size)
                            buf.rewind()
                            buf.get(bytes, 0, size)
                            buf.clear()
                            outStream.write(bytes, 0, size)
                            extractor.advance()
                        }
                        outStream.close()
                        extracted = true
                    }
                    extractor.release()
                } finally {
                    fd.close()
                }
            }
            if (extracted && outputFile.exists() && outputFile.length() > 0) {
                Uri.fromFile(outputFile)
            } else {
                outputFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("Mineradio", "extractAudioFromVideo error", e)
            null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            webView.evaluateJavascript("window._onCameraPermissionResult($granted)", null)
        }
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 权限已授予（公告面板无需天气，保留兼容调用）
                webView.evaluateJavascript("if(typeof loadHomeWeatherPanel==='function'){homeWeatherPanelState.loaded=false;homeWeatherPanelState.error='';loadHomeWeatherPanel()}", null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        releaseWakeLock()
    }

    override fun onPause() {
        // 音乐播放中不暂停 WebView，保持后台播放
        if (musicPlaying) {
            acquireWakeLock()
            super.onPause()
            return
        }
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        releaseWakeLock()
        // ★ 停止前台音乐播放 Service
        try {
            val intent = Intent(this, com.mineradio.app.service.MusicPlaybackService::class.java)
            intent.action = "STOP"
            startService(intent)
        } catch (_: Exception) {}
        if (mediaSession != null) mediaSession?.release()
        if (::server.isInitialized) server.stop()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("KeepApp")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Mineradio:MusicPlayback"
        )
        // ★ 不设超时，保持后台播放
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onBackPressed() {
        if (!::webView.isInitialized) {
            super.onBackPressed()
            return
        }
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : JSON.stringify({closed:false})") { result ->
            val closed = result?.contains("\"closed\":true") == true
            if (!closed) {
                AlertDialog.Builder(this)
                    .setTitle("退出程序")
                    .setMessage("确定要退出 Mineradio 吗？")
                    .setPositiveButton("确定") { _: DialogInterface, _: Int -> finish() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    inner class KAppBridge {
        @JavascriptInterface
        fun requestCameraPermission(): Boolean {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST
                )
            }
            return false
        }

        @JavascriptInterface
        fun openQQMusicLogin(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, QQLoginActivity::class.java)
                    startActivityForResult(intent, QQ_LOGIN_REQUEST)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开QQ音乐登录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openQishuiMusicLogin(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, QishuiLoginActivity::class.java)
                    startActivityForResult(intent, QISHUI_LOGIN_REQUEST)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开汽水音乐登录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openKugouMusicLogin(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, KugouLoginActivity::class.java)
                    startActivityForResult(intent, KUGOU_LOGIN_REQUEST)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开酷狗音乐登录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun setMusicPlaying(playing: Boolean) {
            musicPlaying = playing
            DesktopLyricBridge.isPlaying = playing
            if (playing) {
                acquireWakeLock()
                // ★ 启动前台音乐播放 Service，防止系统杀进程
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(this@MainActivity, com.mineradio.app.service.MusicPlaybackService::class.java)
                    startForegroundService(intent)
                }
            } else {
                releaseWakeLock()
                // ★ 停止前台音乐播放 Service
                val intent = Intent(this@MainActivity, com.mineradio.app.service.MusicPlaybackService::class.java)
                intent.action = "STOP"
                startService(intent)
            }
            // 同步更新系统媒体会话状态
            mediaSession?.updatePlaybackState(playing)
        }

        // ── 系统媒体控件 (通知栏/锁屏) ──

        /** 简化接口：直接传 title, artist, cover — 供 ensureDesktopLyricSync 调用 */
        @JavascriptInterface
        fun updateMediaMetadata(title: String, artist: String, cover: String) {
            try {
                DesktopLyricBridge.songTitle = title
                DesktopLyricBridge.songArtist = artist
                DesktopLyricBridge.isPlaying = musicPlaying
                val duration = 0L // JS 侧可单独通过 updateMediaTrack 设置精确 duration
                mediaSession?.updateTrack(title, artist, "", cover, duration, musicPlaying)
            } catch (e: Exception) {
                Log.w("KAppBridge", "updateMediaMetadata 失败: ${e.message}")
            }
        }

        /** 更新歌曲信息到系统 [title, artist, album, coverUrl, duration(秒)] */
        @JavascriptInterface
        fun updateMediaTrack(metaJson: String) {
            try {
                val obj = JSONObject(metaJson)
                val title = obj.optString("title", "")
                val artist = obj.optString("artist", "")
                val album = obj.optString("album", "")
                val cover = obj.optString("cover", "")
                val duration = (obj.optDouble("duration", 0.0) * 1000).toLong()
                mediaSession?.updateTrack(title, artist, album, cover, duration, musicPlaying)
            } catch (e: Exception) {
                Log.w("KAppBridge", "updateMediaTrack 解析失败: ${e.message}")
            }
        }

        /** 更新播放进度 [position(秒), duration(秒)] */
        @JavascriptInterface
        fun updateMediaPosition(positionSec: Double, durationSec: Double) {
            mediaSession?.updatePosition(
                (positionSec * 1000).toLong(),
                (durationSec * 1000).toLong()
            )
        }

        /** 隐藏系统媒体通知 */
        @JavascriptInterface
        fun hideMediaNotification() {
            mediaSession?.hideNotification()
        }

        // ── 系统音量控制 ──
        @JavascriptInterface
        fun setSystemVolume(vol: String): String {
            return try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val v = (vol.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
                val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
                am?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                "ok"
            } catch (e: Exception) { "error:${e.message}" }
        }

        @JavascriptInterface
        fun getSystemVolume(): String {
            return try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val cur = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1
                String.format("%.2f", cur.toFloat() / max.coerceAtLeast(1))
            } catch (e: Exception) { "0.50" }
        }

        // ── 桌面歌词控制（直接从 MainActivity 管理，不启动 FloatService）──
        @JavascriptInterface
        fun toggleDesktopLyric(show: Boolean) {
            runOnUiThread {
                try {
                    if (show && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        android.widget.Toast.makeText(this@MainActivity, "请先授权悬浮窗权限", android.widget.Toast.LENGTH_LONG).show()
                        val permIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${packageName}")
                        )
                        permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(permIntent)
                        return@runOnUiThread
                    }
                    if (show) {
                        if (desktopLyricView == null) {
                            desktopLyricView = DesktopLyricView(this@MainActivity)
                        }
                        desktopLyricView?.show()
                    } else {
                        desktopLyricView?.hide()
                    }
                } catch (e: Exception) {
                    Log.w("KAppBridge", "toggleDesktopLyric 失败: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun updateDesktopLyricText(text: String) {
            try {
                desktopLyricText = text
                DesktopLyricBridge.currentText = text
                desktopLyricView?.text = text
            } catch (e: Exception) {
                Log.w("KAppBridge", "updateDesktopLyricText 失败: ${e.message}")
            }
        }

        // ── 设置文件持久化 ──
        @JavascriptInterface
        fun saveSettings(settingsJson: String): String {
            return try {
                val settingsFile = java.io.File(filesDir, "mineradio_settings.json")
                settingsFile.writeText(settingsJson)
                "ok"
            } catch (e: Exception) {
                "error:${e.message}"
            }
        }

        @JavascriptInterface
        fun loadSettings(): String {
            return try {
                val settingsFile = java.io.File(filesDir, "mineradio_settings.json")
                if (settingsFile.exists()) settingsFile.readText() else "{}"
            } catch (e: Exception) {
                "{}"
            }
        }

        // ── 本地歌曲管理 ──
        @JavascriptInterface
        fun scanLocalSongs(): String {
            return try {
                val songsDir = java.io.File(filesDir, "local_songs")
                if (!songsDir.exists()) return "[]"
                val files = songsDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
                val result = org.json.JSONArray()
                for (f in files.sortedByDescending { it.lastModified() }.take(50)) {
                    val obj = org.json.JSONObject()
                    obj.put("fileName", f.name)
                    obj.put("size", f.length())
                    obj.put("lastModified", f.lastModified())
                    // 从文件名提取歌曲名
                    val name = f.nameWithoutExtension.replace(Regex("_\\d{13}$"), "")
                        .replace("_", " ")
                    obj.put("name", name)
                    result.put(obj)
                }
                result.toString()
            } catch (e: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteLocalSong(fileName: String): String {
            return try {
                val songsDir = java.io.File(filesDir, "local_songs")
                val safeName = fileName.replace(Regex("[\\\\/]"), "").replace("..", ".")
                val file = java.io.File(songsDir, safeName)
                if (file.exists() && file.delete()) "ok" else "error"
            } catch (e: Exception) {
                "error:${e.message}"
            }
        }

        @JavascriptInterface
        fun getLocalSongsDir(): String {
            return java.io.File(filesDir, "local_songs").absolutePath
        }

        // ── 内置文件管理器 ──

        @JavascriptInterface
        fun getStorageRoots(): String {
            val list = JSONArray()
            try {
                val roots = mutableSetOf<String>()
                // 内部存储
                roots.add(Environment.getExternalStorageDirectory().absolutePath)
                // SD 卡 / 其他外部存储
                val dirs = ContextCompat.getExternalFilesDirs(this@MainActivity, null)
                for (d in dirs) {
                    if (d == null) continue
                    var p = d.absolutePath
                    val idx = p.indexOf("/Android/data/")
                    if (idx > 0) p = p.substring(0, idx)
                    if (p.isNotBlank()) roots.add(p)
                }
                for (r in roots) {
                    val f = java.io.File(r)
                    val obj = JSONObject()
                    obj.put("name", if (r.contains("emulated")) "内部存储" else f.name)
                    obj.put("path", r)
                    list.put(obj)
                }
            } catch (e: Exception) { Log.e(TAG, "getStorageRoots", e) }
            return list.toString()
        }

        @JavascriptInterface
        fun listDirectory(dirPath: String): String {
            val result = JSONObject()
            val dirs = JSONArray()
            val files = JSONArray()
            result.put("path", dirPath)
            try {
                val dir = java.io.File(dirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    result.put("error", "目录不存在")
                    return result.toString()
                }
                val children = dir.listFiles() ?: emptyArray()
                // 文件夹在前，文件在后，均按名称排序
                val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in sorted) {
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    if (f.isDirectory) {
                        obj.put("type", "dir")
                        dirs.put(obj)
                    } else {
                        val ext = f.extension.lowercase()
                        if (ext in setOf("mp3", "flac", "wav", "ogg", "m4a", "wma", "aac", "opus", "webm", "ape", "wv", "aiff")) {
                            obj.put("type", "file")
                            obj.put("size", f.length())
                            files.put(obj)
                        }
                    }
                }
                result.put("dirs", dirs)
                result.put("files", files)
                val parent = dir.parentFile
                result.put("parent", if (parent != null) parent.absolutePath else "")
            } catch (e: Exception) {
                Log.e(TAG, "listDirectory", e)
                result.put("error", e.message ?: "读取失败")
            }
            return result.toString()
        }

        // ── 文件夹选择器 ──

        @JavascriptInterface
        fun selectLocalSongFolder() {
            // ★ 改为启动内置文件管理器
            runOnUiThread {
                webView?.evaluateJavascript("if(typeof openFileManager==='function')openFileManager()", null)
            }
        }

        /** 新：通过绝对路径扫描文件夹（内置文件管理器专用） */
        @JavascriptInterface
        fun scanFolderPath(dirPath: String): String {
            val list = JSONArray()
            try {
                val dir = java.io.File(dirPath)
                if (!dir.exists() || !dir.isDirectory) return "[]"
                val audioExts = setOf("mp3", "flac", "wav", "ogg", "m4a", "wma", "aac", "opus", "webm", "ape", "wv", "aiff")
                val results = mutableListOf<java.io.File>()
                // 递归扫描
                val queue = ArrayDeque<java.io.File>()
                queue.add(dir)
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val children = current.listFiles() ?: continue
                    for (f in children) {
                        if (f.isDirectory) { queue.add(f) }
                        else if (f.extension.lowercase() in audioExts) { results.add(f) }
                    }
                }
                // 按文件大小降序排序
                results.sortByDescending { it.length() }
                for (f in results) {
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    obj.put("size", f.length())
                    obj.put("lastModified", f.lastModified())
                    list.put(obj)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanFolderPath error", e)
            }
            return list.toString()
        }

        /** ★ 导入并分析单个文件：复制到 local_songs/ + 提取元数据，返回 {cachedFile,duration,artist,title} */
        @JavascriptInterface
        fun importAndAnalyzeFile(filePath: String): String {
            return try {
                val src = java.io.File(filePath)
                if (!src.exists() || !src.isFile) return """{"error":"文件不存在"}"""
                val songsDir = java.io.File(filesDir, "local_songs")
                songsDir.mkdirs()
                val ext = src.extension.lowercase()
                val baseName = src.nameWithoutExtension
                    .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]"), "_")
                    .take(40)
                val safeName = "${baseName}_${System.currentTimeMillis()}.${ext.ifBlank { "mp3" }}"
                val destFile = java.io.File(songsDir, safeName)
                src.copyTo(destFile, overwrite = true)
                // 分析
                var duration = 0L
                var artist = ""
                var title = ""
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(destFile.absolutePath)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                // 也写入 localStorage（和上传流程一致）
                val originalName = src.name
                webView?.post {
                    webView?.evaluateJavascript(
                        "if(window.__addLocalSongWithCachedFile)window.__addLocalSongWithCachedFile('$safeName','${originalName.replace("'", "\\'")}','$safeName')",
                        null
                    )
                }
                val result = JSONObject()
                result.put("cachedFile", safeName)
                result.put("duration", duration)
                result.put("artist", artist)
                result.put("title", title)
                result.toString()
            } catch (e: Exception) {
                Log.e(TAG, "importAndAnalyzeFile error", e)
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /** 新：分析绝对路径的音频文件（用于内置文件管理器） */
        @JavascriptInterface
        fun analyzeAudioFilePath(filePath: String): String {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) return """{"duration":0,"artist":"","title":""}"""
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    return """{"duration":$dur,"artist":${JSONObject.quote(artist)},"title":${JSONObject.quote(title)}}"""
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                return """{"duration":0,"artist":"","title":""}"""
            }
        }

    }

    // ── 文件夹扫描 + 音频分析工具 ──

    data class AudioFileEntry(val name: String, val uri: Uri, val size: Long)

    private fun scanFolderForAudio(treeUri: Uri): List<AudioFileEntry> {
        val results = mutableListOf<AudioFileEntry>()
        val audioExts = setOf("mp3", "flac", "wav", "ogg", "m4a", "wma", "aac", "opus", "webm", "ape", "wv", "aiff")
        fun scanUri(parentUri: Uri, parentDocId: String) {
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    parentUri, parentDocId
                )
                val cursor: Cursor? = contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null, null, null
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val docId = it.getString(0) ?: continue
                        val name = it.getString(1) ?: continue
                        val mime = it.getString(2) ?: ""
                        val size = it.getLong(3)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, docId)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            scanUri(parentUri, docId)
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext in audioExts) {
                                results.add(AudioFileEntry(name, childUri, if (size > 0) size else 0))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scanFolderForAudio skip $parentDocId: ${e.message}")
            }
        }
        // 用 getTreeDocumentId 而不是 getDocumentId（tree uri ≠ document uri）
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            treeUri.lastPathSegment ?: ""
        }
        scanUri(treeUri, rootDocId)
        // 按文件大小降序排序
        results.sortByDescending { it.size }
        return results
    }

    /** 返回 Triple(durationMs, artist, title) */
    private fun analyzeAudioFile(uri: Uri): Triple<Long, String, String> {
        var duration = 0L
        var artist = ""
        var title = ""
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyzeAudioFile error: ${e.message}")
        }
        return Triple(duration, artist, title)
    }

    companion object {
        private const val TAG = "Mineradio"
        val BRIDGE_JS = """
(function(){
    // ═══════════════════════════════════════════════════════════
    //  安卓全适配 v13
    //  - 触摸照搬参考版  - 上传音频+图片+视频→提取音频
    //  - void背景强制显示  - QQ内置WebView扫码登录  - 手势真实错误提示+预加载
    // ═══════════════════════════════════════════════════════════

    // ── A0. 全局: 去除蓝色点击高亮 + 禁止长按选择 ──
    (function(){
        var style0 = document.createElement('style');
        style0.textContent =
            '* { -webkit-tap-highlight-color:transparent!important; -webkit-touch-callout:none!important; -webkit-user-select:none!important; user-select:none!important; } ' +
            'input,textarea,[contenteditable] { -webkit-user-select:text!important; user-select:text!important; } ' +
            '*:focus { outline:none!important; } ' +
            '*:active { -webkit-tap-highlight-color:transparent!important; }';
        document.head.appendChild(style0);
    })();

    // ── A0.5. 跳过 HTML 启动屏（已由 SplashActivity 代替）──
    (function(){
        var s = document.getElementById('splash');
        if (s) { s.style.display = 'none'; s.classList.add('hide'); }
        document.body.classList.remove('splash-active', 'splash-revealing');
        if (typeof splashTimer !== 'undefined') { clearTimeout(splashTimer); splashTimer = null; }
        if (typeof splashReadyToEnter !== 'undefined') splashReadyToEnter = false;
        if (typeof splashAnimating !== 'undefined') splashAnimating = false;
        // 恢复主页显示
        if (typeof window.homeForcedOpen !== 'undefined') window.homeForcedOpen = true;
        if (typeof window.emptyHomeActive !== 'undefined') window.emptyHomeActive = true;
        setTimeout(function(){
            if (typeof updateEmptyHomeVisibility === 'function') updateEmptyHomeVisibility({forceLoad:true});
        }, 100);
    })();

    // ── A. 整体 UI 缩放 ──
    (function(){
        var style = document.createElement('style');
        style.textContent = 'html, body { zoom: 0.80; -moz-transform: scale(0.80); }';
        document.head.appendChild(style);
        // 启动界面单独缩放至 0.935 (0.80 × 1.16875 ≈ 0.935)，不与主界面一起缩小
        var splashStyle = document.createElement('style');
        splashStyle.textContent = '#splash { zoom: 1.16875; }';
        document.head.appendChild(splashStyle);
    })();

    // ── B. 删除 DIY 视觉引导 ──
    window.markVisualGuideSeen = function(){};
    window.visualGuideWasSeen = function(){ return true; };
    window.maybeRunStartupVisualGuide = function(){ return false; };
    window.startVisualGuide = function(){};
    try { localStorage.setItem('mineradio-visual-guide-seen-v2', '1'); } catch(e) {}

    // ── C. 隐藏音质选择器 ──
    (function(){
        var style = document.createElement('style');
        style.textContent = '#quality-control { display:none !important; }';
        document.head.appendChild(style);
    })();

    // ── D. 音质默认 standard ──
    try { localStorage.setItem('mineradio-playback-quality-v1', 'standard'); } catch(e) {}
    if (typeof window.playbackQuality !== 'undefined') window.playbackQuality = 'standard';
    window.readPlaybackQualityPreference = function(){ return 'standard'; };

    // ── E. 全屏按钮 → 沉浸 ──
    (function(){
        var btn = document.querySelector('.fullscreen-toggle-btn');
        if (btn) btn.style.display = 'none';
        window.toggleFullscreen = function(){
            if (window.toggleImmersiveMode) window.toggleImmersiveMode();
        };
    })();

    // ── F. 底部白条: 默认隐藏, 点屏出现, 点白条弹控制栏 ──
    (function(){
        var style2 = document.createElement('style');
        style2.textContent =
          '#bottom-handle { opacity:0!important; pointer-events:none!important; transition:opacity .35s,transform .35s!important; }' +
          'body.handle-peek #bottom-handle { opacity:0.62!important; pointer-events:auto!important; transform:translateX(-50%) translateY(0) scale(0.96)!important; }' +
          '#bottom-handle:active { opacity:0.92!important; transform:translateX(-50%) translateY(-3px) scale(1)!important; }' +
          'body.controls-visible #bottom-handle { opacity:0!important; pointer-events:none!important; }';
        document.head.appendChild(style2);

        var handlePeekTimeout = null;
        function isBarVisible() {
            var bar = document.getElementById('bottom-bar');
            return !!(bar && bar.classList.contains('visible'));
        }
        window.addEventListener('touchstart', function(e){
            if (isBarVisible()) return;
            if (e.target && e.target.closest && e.target.closest('#bottom-bar')) return;
            if (e.target && e.target.closest && e.target.closest('#bottom-handle')) return;
            document.body.classList.add('handle-peek');
            clearTimeout(handlePeekTimeout);
            handlePeekTimeout = setTimeout(function(){
                if (!isBarVisible()) document.body.classList.remove('handle-peek');
            }, 5000);
        }, {passive:true});

        var handle = document.getElementById('bottom-handle');
        if (handle) {
            handle.addEventListener('touchstart', function(e){
                e.stopPropagation(); e.preventDefault();
                document.body.classList.remove('handle-peek');
                if (typeof window.toggleBottomControlsFromHandle === 'function') window.toggleBottomControlsFromHandle();
            }, {passive:false});
        } else {
            var _retryHandle = setInterval(function(){
                var h = document.getElementById('bottom-handle');
                if (h) { clearInterval(_retryHandle);
                    h.addEventListener('touchstart', function(ev){
                        ev.stopPropagation(); ev.preventDefault();
                        document.body.classList.remove('handle-peek');
                        if (typeof window.toggleBottomControlsFromHandle === 'function') window.toggleBottomControlsFromHandle();
                    }, {passive:false});
                }
            }, 300);
        }
    })();

    // ── G. 显示 fx-fab + diy 模式 ──
    document.documentElement.classList.remove('simple-mode-preload');
    document.body.classList.remove('simple-mode');
    window.diyPlayerMode = true;
    try { localStorage.setItem('mineradio-diy-player-mode-v1', '1'); } catch(e) {}

    // ── H. 3D 歌单架: Android 禁用右边缘触摸（避免误触自动播放） ──
    (function(){
        var _shelfManualOpen = false;
        var shelfTouchTimeout = null;
        // Android 禁用右边缘滑动弹架，防止误触自动选歌播放
        window.addEventListener('touchstart', function(e){
            var t = e.touches[0];
            if (!t) return;
            // ★ 右边缘触摸在 Android 上禁用 — 不做任何 shelf 操作
            if (t.clientX > window.innerWidth - 50) {
                return;
            }
            _shelfManualOpen = false;
            var target = e.target;
            var isOnShelfContent = false;
            if (target && target.closest) {
              isOnShelfContent = !!(target.closest('#playlist-panel') || target.closest('#fx-panel') ||
                    target.closest('.modal') || target.closest('.track-detail-modal') ||
                    target.closest('#bottom-bar') || target.closest('#bottom-handle') ||
                    target.closest('#search-area') || target.closest('#shelf-hint'));
            }
            // 点击非面板区域 → 自动隐藏歌单架
            if (!isOnShelfContent && window.shelfPinnedOpen && !_shelfManualOpen) {
                clearTimeout(shelfTouchTimeout);
                shelfTouchTimeout = setTimeout(function(){
                    if (typeof window.setShelfPinnedOpen === 'function' && window.shelfPinnedOpen) window.setShelfPinnedOpen(false, true);
                }, 300);
            }
        }, {passive:true});

        // fx-panel 内注入歌单架常驻按钮: 设置 shelfPresence (off/hover/always)
        function injectShelfPinToFxPanel() {
            var foldBody = document.querySelector('#fx-stage-fold .fx-fold-body');
            if (!foldBody) { setTimeout(injectShelfPinToFxPanel, 600); return; }
            if (document.getElementById('fx-shelf-pin-btn')) return;
            var label = document.createElement('div');
            label.className = 'fx-section-label';
            label.textContent = '歌单架常驻';
            label.style.marginTop = '10px';
            var seg = document.createElement('div');
            seg.className = 'fx-seg';
            // 三态按钮: always(常驻) / hover(自动) / off(关闭)
            seg.innerHTML = '<button id="fx-shelf-pin-btn" data-pin="hover">自动隐藏</button>';
            var btnEl = seg.querySelector('button');
            btnEl.onclick = function(){
                // 切换 shelfPresence: always → hover → off → always
                var cur = (typeof window.fx !== 'undefined' && window.fx && window.fx.shelfPresence) ? window.fx.shelfPresence : 'hover';
                var next = cur === 'always' ? 'hover' : (cur === 'hover' ? 'off' : 'always');
                if (typeof window.fx !== 'undefined' && window.fx) window.fx.shelfPresence = next;
                // 同步 shelfPinnedOpen
                if (typeof window.setShelfPinnedOpen === 'function') {
                    window.setShelfPinnedOpen(next === 'always', true);
                }
                if (typeof window.saveLyricLayout === 'function') window.saveLyricLayout();
                updatePinBtnState();
            };
            foldBody.appendChild(label);
            foldBody.appendChild(seg);
            function updatePinBtnState() {
                var b = document.getElementById('fx-shelf-pin-btn');
                if (!b) return;
                var cur = (typeof window.fx !== 'undefined' && window.fx && window.fx.shelfPresence) ? window.fx.shelfPresence : 'hover';
                b.setAttribute('data-pin', cur);
                b.textContent = cur === 'always' ? '已常驻' : (cur === 'off' ? '已关闭' : '自动隐藏');
                b.classList.toggle('active', cur === 'always');
            }
            updatePinBtnState();
            setInterval(updatePinBtnState, 2000);
        }
        setTimeout(injectShelfPinToFxPanel, 4000);
    })();

    // ── I. 歌单详情: 滑动切换歌曲 ──
  (function(){
    var _scrollTimer = 0;
    var _lastPlayedIdx = -1;
    function bindPlaylistScroll() {
      var panel = document.getElementById('playlist-track-panel');
      if (!panel) { setTimeout(bindPlaylistScroll, 500); return; }
      panel.addEventListener('scroll', function() {
        clearTimeout(_scrollTimer);
        _scrollTimer = setTimeout(function(){
          var rows = panel.querySelectorAll('[data-pl-detail-row]');
          if (!rows.length) return;
          var midY = panel.scrollTop + panel.clientHeight / 2;
          var bestIdx = -1, bestDist = Infinity;
          rows.forEach(function(row){
            var rect = row.getBoundingClientRect();
            var panelRect = panel.getBoundingClientRect();
            var rowMid = rect.top - panelRect.top + rect.height / 2 + panel.scrollTop;
            var dist = Math.abs(rowMid - midY);
            if (dist < bestDist) { bestDist = dist; bestIdx = Number(row.getAttribute('data-pl-detail-row')); }
          });
          if (bestIdx >= 0 && bestIdx !== _lastPlayedIdx) {
            _lastPlayedIdx = bestIdx;
            if (typeof window.playPlaylistPanelDetailTrack === 'function') window.playPlaylistPanelDetailTrack(bestIdx);
          }
        }, 500);
      }, {passive:true});
    }
    setTimeout(bindPlaylistScroll, 3000);
  })();

    // ── J. 原生上传按钮扩展格式 ──
    (function(){
        var input = document.getElementById('file-input');
        if (input) input.setAttribute('accept', '.mp3,.flac,.wav,.ogg,.m4a,.aac,.wma,.aiff,.ape,.opus,.mp4');
    })();

    // ── K. 音频解锁 + NotAllowedError 重试 ──
    var _androidAudioUnlocked = false;
    var _playRetryQueue = null;
    var _playRetryToken = 0;
    var _playRetryCount = 0;
    var _playRetryMax = 3;
    function unlockAndroidAudio() {
        if (_androidAudioUnlocked) return;
        _androidAudioUnlocked = true;
        try {
            var ctx = new (window.AudioContext || window.webkitAudioContext)();
            var buf = ctx.createBuffer(1, 1, 22050);
            var src = ctx.createBufferSource();
            src.buffer = buf; src.connect(ctx.destination); src.start(0);
            ctx.resume().catch(function(){});
            setTimeout(function(){ try { ctx.close(); } catch(e) {} }, 300);
        } catch(e) {}
        try {
            var unlockEl = document.createElement('audio');
            unlockEl.setAttribute('playsinline', '');
            unlockEl.src = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';
            var p = unlockEl.play();
            if (p) p.then(function(){ unlockEl.pause(); unlockEl.removeAttribute('src'); unlockEl.load(); }).catch(function(){});
        } catch(e) {}
    }
    (function(){
        var origPlay = HTMLMediaElement.prototype.play;
        HTMLMediaElement.prototype.play = function() {
            var self = this;
            var p = origPlay.call(self);
            if (p && p.then) {
                p = p.catch(function(err) {
                    if (err && err.name === 'NotAllowedError') {
                        unlockAndroidAudio();
                        _playRetryCount = 0;
                        var retryToken = ++_playRetryToken;
                        _playRetryQueue = function() {
                            if (retryToken !== _playRetryToken) return;
                            _playRetryCount++;
                            unlockAndroidAudio();
                            try {
                                if (window.audioCtx && window.audioCtx.state === 'suspended') window.audioCtx.resume();
                                if (window.uiSfxCtx && window.uiSfxCtx.state === 'suspended') window.uiSfxCtx.resume();
                            } catch(e) {}
                            // 如果音频源无效，可能需要前端重新获取播放地址
                             var currentSrc = (self.src || self.currentSrc || '').replace(/#.*$/, '');
                             if (!currentSrc || currentSrc === window.location.href || currentSrc === 'about:blank') {
                                 if (window.showToast) window.showToast('音频源未就绪,请重新选择歌曲');
                                 return;
                             }
                            var rp = origPlay.call(self);
                            if (rp && rp.then) {
                                rp.then(function() {
                                    if (window.playing === false && window.setPlayIcon) { window.playing = true; window.setPlayIcon(true); try { KeepApp.setMusicPlaying(true); } catch(e) {} }
                                }).catch(function(e2){
                                    // 重试失败，如果还有重试次数则继续
                                    if (_playRetryCount < _playRetryMax) {
                                        if (window.showToast) window.showToast('正在重试播放(' + (_playRetryCount + 1) + '/' + _playRetryMax + ')...');
                                        // 延迟后自动重试，不需要等用户触摸
                                        setTimeout(function() {
                                            if (_playRetryQueue && _playRetryToken === retryToken) {
                                                var fn = _playRetryQueue; _playRetryQueue = null; fn();
                                            }
                                        }, 800);
                                        // 同时注册触摸重试
                                        _playRetryQueue = function() {
                                            if (retryToken !== _playRetryToken) return;
                                            _playRetryCount++;
                                            unlockAndroidAudio();
                                            var rp2 = origPlay.call(self);
                                            if (rp2 && rp2.then) {
                                                rp2.then(function() {
                                                    if (window.playing === false && window.setPlayIcon) { window.playing = true; window.setPlayIcon(true); try { KeepApp.setMusicPlaying(true); } catch(e) {} }
                                                }).catch(function(e3){
                                                    if (_playRetryCount < _playRetryMax) {
                                                        if (window.showToast) window.showToast('播放受阻,请点击播放按钮重试');
                                                    } else {
                                                        if (window.showToast) window.showToast('播放失败,请重新选择歌曲');
                                                    }
                                                });
                                            }
                                        };
                                        return;
                                    }
                                    if (window.showToast) window.showToast('播放失败,请重新选择歌曲');
                                });
                            }
                        };
                        document.addEventListener('touchstart', function retryFn(e){
                            document.removeEventListener('touchstart', retryFn, true);
                            document.removeEventListener('pointerdown', retryFn, true);
                            if (!_playRetryQueue) return;
                            var fn = _playRetryQueue; _playRetryQueue = null; fn();
                        }, {capture:true,once:true,passive:false});
                        document.addEventListener('pointerdown', function retryFn(e){
                            document.removeEventListener('touchstart', retryFn, true);
                            document.removeEventListener('pointerdown', retryFn, true);
                            if (!_playRetryQueue) return;
                            var fn = _playRetryQueue; _playRetryQueue = null; fn();
                        }, {capture:true,once:true,passive:false});
                        if (window.showToast) window.showToast('再次点击屏幕即可播放');
                    }
                    throw err;
                });
            }
            return p;
        };
        // 监听音频暂停以通知原生层
        (function(){
            var origPause = HTMLMediaElement.prototype.pause;
            HTMLMediaElement.prototype.pause = function() {
                try { KeepApp.setMusicPlaying(false); } catch(e) {} 
                return origPause.call(this);
            };
        })();
        document.addEventListener('touchstart', unlockAndroidAudio, {capture:true,passive:false,once:true});
        document.addEventListener('pointerdown', unlockAndroidAudio, {capture:true,passive:false,once:true});
    })();

    // ── L. 触摸旋转 3D (照搬 mineradio 参考版) ──
    // 在 renderer.domElement 上绑定, shelf联动, 双击回正
    (function(){
        function initTouchControls() {
            var canvas = window.renderer && window.renderer.domElement;
            if (!canvas) { setTimeout(initTouchControls, 300); return; }

            var touchState = { active: false, lastTouchX: 0, lastTouchY: 0, lastPinchDist: 0, startTouchX: 0, startTouchY: 0 };
            var lastTapTime = 0;
            var DOUBLE_TAP_MS = 320;
            var CLICK_THRESHOLD = 6;

            function getTouchDistance(touches) {
                var dx = touches[0].clientX - touches[1].clientX;
                var dy = touches[0].clientY - touches[1].clientY;
                return Math.sqrt(dx*dx + dy*dy);
            }
            function dispatchFakeMouse(type, x, y) {
                var evt = new MouseEvent(type, { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }
            function dispatchFakeClick(x, y) {
                var evt = new MouseEvent('click', { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }
            function dispatchFakeWheel(deltaY, x, y) {
                var evt = new WheelEvent('wheel', { deltaY: deltaY, clientX: x != null ? x : touchState.lastTouchX, clientY: y != null ? y : touchState.lastTouchY, bubbles: true, cancelable: true });
                canvas.dispatchEvent(evt);
            }

            canvas.addEventListener('touchstart', function(e) {
                e.preventDefault();
                if (e.touches.length === 1) {
                    touchState.active = true;
                    touchState.lastTouchX = e.touches[0].clientX;
                    touchState.lastTouchY = e.touches[0].clientY;
                    touchState.startTouchX = e.touches[0].clientX;
                    touchState.startTouchY = e.touches[0].clientY;
                    dispatchFakeMouse('mousedown', e.touches[0].clientX, e.touches[0].clientY);
                } else if (e.touches.length === 2) {
                    touchState.lastPinchDist = getTouchDistance(e.touches);
                    if (touchState.active) {
                        dispatchFakeMouse('mouseup', touchState.lastTouchX, touchState.lastTouchY);
                        touchState.active = false;
                    }
                }
            }, { passive: false });

            canvas.addEventListener('touchmove', function(e) {
                e.preventDefault();
                if (e.touches.length === 1 && touchState.active) {
                    var curX = e.touches[0].clientX;
                    var curY = e.touches[0].clientY;
                    dispatchFakeMouse('mousemove', curX, curY);
                    // shelf 可见时: 垂直滑动→wheel（卡片滚动 + 详情滚动）
                    if (typeof window.shelfManager !== 'undefined' && window.shelfManager) {
                        var shelfMode = null;
                        try { shelfMode = window.shelfManager.getMode && window.shelfManager.getMode(); } catch(x) {}
                        var shelfOpen = false;
                        try { shelfOpen = !!(window.shelfManager.hasOpenContent && window.shelfManager.hasOpenContent()); } catch(x) {}
                        if (shelfMode === 'side' || shelfMode === 'stage' || shelfOpen) {
                            var dy = touchState.lastTouchY - curY;
                            if (Math.abs(dy) > 1) { dispatchFakeWheel(dy * 0.6, curX, curY); }
                        }
                    }
                    touchState.lastTouchX = curX;
                    touchState.lastTouchY = curY;
                } else if (e.touches.length === 2) {
                    var currentDist = getTouchDistance(e.touches);
                    var delta = touchState.lastPinchDist - currentDist;
                    if (Math.abs(delta) > 0.5) {
                        dispatchFakeWheel(delta * 0.8);
                        touchState.lastPinchDist = currentDist;
                    }
                }
            }, { passive: false });

            canvas.addEventListener('touchend', function(e) {
                e.preventDefault();
                if (e.touches.length === 0 && touchState.active) {
                    var finalX = touchState.lastTouchX;
                    var finalY = touchState.lastTouchY;
                    dispatchFakeMouse('mouseup', finalX, finalY);
                    var dx = finalX - (touchState.startTouchX || finalX);
                    var dy = finalY - (touchState.startTouchY || finalY);
                    if (Math.sqrt(dx*dx + dy*dy) < CLICK_THRESHOLD) {
                        dispatchFakeClick(finalX, finalY);
                        var now = Date.now();
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            lastTapTime = 0;
                            if (typeof window.recenterCamera === 'function') window.recenterCamera();
                        } else {
                            lastTapTime = now;
                        }
                    }
                    touchState.active = false;
                } else if (e.touches.length === 1) {
                    // 从双指变单指: 重新开始拖拽
                    touchState.active = true;
                    touchState.lastTouchX = e.touches[0].clientX;
                    touchState.lastTouchY = e.touches[0].clientY;
                    touchState.startTouchX = e.touches[0].clientX;
                    touchState.startTouchY = e.touches[0].clientY;
                    dispatchFakeMouse('mousedown', e.touches[0].clientX, e.touches[0].clientY);
                }
            }, { passive: false });

            canvas.addEventListener('touchcancel', function(e) {
                if (touchState.active) {
                    dispatchFakeMouse('mouseup', touchState.lastTouchX, touchState.lastTouchY);
                    touchState.active = false;
                }
            });
        }
        if (window.renderer && window.renderer.domElement) initTouchControls();
        else window.addEventListener('DOMContentLoaded', function(){ initTouchControls(); }, {once:true});
    })();

    // ── M. 歌曲详情弹框: 触摸滚动 (强化) ──
    (function(){
        setTimeout(function(){
            var style = document.createElement('style');
            style.textContent =
                '.detail-scroll { overflow-y:auto!important; overflow-x:hidden!important; max-height:min(520px,calc(100vh - 280px))!important; -webkit-overflow-scrolling:touch!important; touch-action:pan-y!important; } ' +
                '.artist-song-item { min-height:52px; touch-action:manipulation; } ' +
                '#track-detail-body { -webkit-overflow-scrolling:touch!important; touch-action:pan-y!important; overflow-y:auto!important; } ' +
                // 弹框遮罩层捕获所有触摸 → 阻止 canvas 旋转
                '#track-detail-modal { touch-action:auto!important; } ' +
                '.track-detail-modal { touch-action:pan-y!important; }';
            document.head.appendChild(style);

            var detailBody = document.getElementById('track-detail-body');
            if (detailBody) {
                detailBody.style.webkitOverflowScrolling = 'touch';
                detailBody.style.overflowY = 'auto';
                detailBody.style.touchAction = 'pan-y';
            }
            // 弹框遮罩 → 阻止穿透
            var mask = document.getElementById('track-detail-modal');
            if (mask) {
                mask.addEventListener('touchstart', function(e){
                    e.stopPropagation();
                }, {passive:true});
                mask.addEventListener('touchmove', function(e){
                    e.stopPropagation();
                }, {passive:true});
                mask.addEventListener('touchend', function(e){
                    e.stopPropagation();
                }, {passive:true});
            }

            function fixExistingScrolls() {
                document.querySelectorAll('.detail-scroll').forEach(function(el){
                    el.style.overflowY = 'auto';
                    el.style.overflowX = 'hidden';
                    el.style.touchAction = 'pan-y';
                    el.style.webkitOverflowScrolling = 'touch';
                });
            }
            fixExistingScrolls();
            var origOpenDetail = window.openTrackDetailModal;
            if (origOpenDetail) {
                window.openTrackDetailModal = function(type, songOverride) {
                    origOpenDetail(type, songOverride);
                    setTimeout(fixExistingScrolls, 600);
                };
            }
        }, 2000);
    })();

    // ── N. 静音 splash ──
    window.splashSoundPlayed = true;
    window.splashAudioCtx = null;
    window.playMineradioIntroSound = function(){ window.splashSoundPlayed = true; };

    // ── O. 修复粒子 ──
    window.revealIdleParticles = function(target, durationMs) {
        if (window.uniforms && window.uniforms.uAlpha) window.uniforms.uAlpha.value = 0.96;
        if (window.uniforms && window.uniforms.uFloatAlpha) window.uniforms.uFloatAlpha.value = 1.0;
        document.body.classList.remove('render-deep-sleep');
    };
    window.prewarmHomeWallpaperPreview = function() {
        if (window.homeWallpaperPrewarmStarted) return;
        window.homeWallpaperPrewarmStarted = true;
        setTimeout(function(){
            document.body.classList.add('home-wallpaper-preview');
            if (window.uniforms && window.uniforms.uAlpha) window.uniforms.uAlpha.value = 0.96;
            if (window.uniforms && window.uniforms.uFloatAlpha) window.uniforms.uFloatAlpha.value = 0;
            document.body.classList.remove('render-deep-sleep');
        }, 400);
    };

    // ── P. Splash 后显示主页 ──
    window.homeForcedOpen = true;
    window.playing = false;
    window.currentIdx = -1;
    if (!window.immersiveMode) window.immersiveMode = false;
    if (!window.shelfPinnedOpen) window.shelfPinnedOpen = false;
    window.hasActivePlaybackControls = function(){ return false; };
    window.closeMiniQueue = function(){};
    window.closeFxPanel = function(){};
    window.setHomeControlsLocked = function(){};
    window.shouldShowEmptyHomeAfterSplash = function(){ return true; };
    window.shouldForceEmptyHomeAfterSplash = function(){ return true; };
    window.shouldUseIdleWallpaperPreview = function(){ return true; };

    // ── Q. 音频代理（content:// / 绝对路径 → HTTP, http → /api/audio） ──
    (function(){
        var origDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
        if (origDesc && origDesc.set) {
            var origSet = origDesc.set;
            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                set: function(url) {
                    if (typeof url === 'string') {
                        // ★ content:// URI → 通过本地服务器中转（WebView 无法直接加载 SAF URI）
                        if (url.startsWith('content://')) {
                            url = '/api/local-file?uri=' + encodeURIComponent(url);
                        // ★ 绝对文件路径（/storage/...）→ 通过服务器中转
                        } else if (url.charAt(0) === '/' && !url.startsWith('/api/') && !url.startsWith('/local-song/') && !url.startsWith('/assets/')) {
                            url = '/api/local-file?path=' + encodeURIComponent(url);
                        } else if (url.startsWith('http') && !url.includes('127.0.0.1') && !url.includes('localhost') && !url.startsWith('blob:')) {
                            url = '/api/audio?url=' + encodeURIComponent(url);
                        }
                    }
                    origSet.call(this, url);
                },
                get: origDesc.get,
                configurable: true, enumerable: true
            });
        }
    })();

    // ── R. Android 返回键: 逐层关闭面板 ──
    window.handleAndroidBack = function() {
        function isVisible(el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && r.right > -50 && r.bottom > -50 && r.left < window.innerWidth + 50 && r.top < window.innerHeight + 50;
        }
        var dm = document.getElementById('track-detail-modal');
        if (isVisible(dm)) {
            if (typeof window.closeTrackDetailModal === 'function') window.closeTrackDetailModal();
            else { dm.style.display = 'none'; dm.classList.remove('show'); }
            return JSON.stringify({closed:true});
        }
        var modals = document.querySelectorAll('.modal:not(.track-detail-modal)');
        for (var mi = 0; mi < modals.length; mi++) {
            var m = modals[mi];
            if (isVisible(m)) {
                if (typeof window.closeLoginModal === 'function' && m.id === 'login-modal') { window.closeLoginModal(); return JSON.stringify({closed:true}); }
                if (m.id === 'update-modal') { m.style.display = 'none'; m.classList.remove('show'); return JSON.stringify({closed:true}); }
                if (m.id === 'cover-color-modal' && typeof window.closeCoverColorPop === 'function') { window.closeCoverColorPop(); return JSON.stringify({closed:true}); }
                if (m.id === 'color-lab-modal' && typeof window.closeColorLabPop === 'function') { window.closeColorLabPop(); return JSON.stringify({closed:true}); }
                m.style.display = 'none'; m.classList.remove('show'); return JSON.stringify({closed:true});
            }
        }
        var sa = document.getElementById('search-area');
        if (sa && sa.classList.contains('peek')) {
            if (typeof window.setPeek === 'function') window.setPeek(sa, false, 'search');
            else sa.classList.remove('peek');
            return JSON.stringify({closed:true});
        }
        var pp = document.getElementById('playlist-panel');
        if (pp && pp.classList.contains('peek')) {
            if (typeof window.setPeek === 'function') window.setPeek(pp, false, 'pl');
            else pp.classList.remove('peek');
            return JSON.stringify({closed:true});
        }
        var fx = document.getElementById('fx-panel');
        if (fx && (fx.classList.contains('peek') || fx.classList.contains('show'))) {
            if (typeof window.closeFxPanel === 'function') window.closeFxPanel();
            else fx.classList.remove('peek', 'show', 'closing');
            return JSON.stringify({closed:true});
        }
        if (window.miniQueueOpen) {
            if (typeof window.closeMiniQueue === 'function') window.closeMiniQueue();
            return JSON.stringify({closed:true});
        }
        if (window.immersiveMode) {
            if (typeof window.setImmersiveMode === 'function') window.setImmersiveMode(false);
            return JSON.stringify({closed:true});
        }
        if (!window.emptyHomeActive && typeof window.openHomeControls === 'function') {
            window.openHomeControls();
            return JSON.stringify({closed:true});
        }
        return JSON.stringify({closed:false});
    };

    // ── S0. 修复: void模式强制显示自定义背景 ──
    // 1) 注入 CSS 强制 #custom-bg::before 永远可见
    // 2) 定期检查/恢复背景变量
    // 3) MutationObserver 监听 style 变更
    (function(){
        var styleBg = document.createElement('style');
        styleBg.textContent =
            // 当有自定义背景时, 强制 #custom-bg::before 显示
            'body.custom-background-override #custom-bg { display:block!important; } ' +
            'body.custom-background-override #custom-bg::before { opacity:var(--custom-bg-image-opacity,1)!important; } ' +
            // 当有自定义背景时, 降低粒子画布不透明度让背景透出
            'body.custom-background-override #canvas-container { opacity:0!important; } ' +
            // 但非 void 模式恢复粒子
            'body.custom-background-override:not(.preset-void) #canvas-container { opacity:1!important; }';
        document.head.appendChild(styleBg);

        // 定期检查 void preset 并打 class
        function syncPresetClass() {
            var p = (typeof fx !== 'undefined' && fx && typeof fx.preset === 'number') ? fx.preset : -1;
            var isVoid = (p === 3);
            document.body.classList.toggle('preset-void', isVoid);
        }
        setInterval(syncPresetClass, 1500);

        // 定期强制恢复背景图片变量
        function reapplyBg() {
            var el = document.getElementById('custom-bg');
            if (!el) return;
            var img = el.style.getPropertyValue('--custom-bg-image');
            if (img && img !== 'none') {
                el.style.setProperty('--custom-bg-image-opacity', '1', 'important');
            }
        }
        setInterval(reapplyBg, 2000);

        // MutationObserver: 监听 #custom-bg style 变更
        setTimeout(function(){
            var target = document.getElementById('custom-bg');
            if (target) {
                var obs = new MutationObserver(function(){ reapplyBg(); });
                obs.observe(target, { attributes: true, attributeFilter: ['style'] });
            }
        }, 1000);
    })();

    // ── S1. 视频上传 → 自动提取音频 (原生层处理, JS只扩展accept) ──
    (function(){
        setTimeout(function(){
            var input = document.getElementById('file-input');
            if (input) input.setAttribute('accept', input.getAttribute('accept') + ',.mp4,.webm,.mov,.mkv,.avi,video/mp4,video/webm,video/quicktime,video/x-matroska,video/x-msvideo');
        }, 2000);
    })();

    // ── S2. 手势错误提示修复: 预加载 MediaPipe + 拦截误导信息 ──
    (function(){
        // 预加载 MediaPipe 脚本 (延迟到第一次触摸)
        var _mediaPipePreloaded = false;
        function preloadMediaPipe() {
            if (_mediaPipePreloaded) return;
            _mediaPipePreloaded = true;
            if (typeof window.loadScriptOnce === 'function') {
                var p1 = window.loadScriptOnce('https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js');
                var p2 = window.loadScriptOnce('https://cdn.jsdelivr.net/npm/@mediapipe/hands/hands.js');
                Promise.all([p1, p2]).then(function(){}).catch(function(e){
                    window._mediaPipePreloadError = e;
                });
            }
        }
        // 在用户第一次交互时触发预加载
        window.addEventListener('touchstart', preloadMediaPipe, {once: true, passive: true});
        window.addEventListener('pointerdown', preloadMediaPipe, {once: true, passive: true});

        // 拦截 showToast: 当 "手势启动失败 (需要摄像头权限)" 出现时替换为诊断信息
        if (window.showToast) {
            var _origShowToast = window.showToast;
            window.showToast = function(msg) {
                if (typeof msg === 'string' && msg.indexOf('手势启动失败') >= 0) {
                    var realMsg = '手势启动失败';
                    if (window._mediaPipePreloadError) {
                        realMsg = '手势库加载失败, 请检查网络连接';
                    } else if (typeof window.Camera === 'undefined') {
                        realMsg = '手势库未就绪, 请检查网络连接';
                    } else if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                        realMsg = '设备不支持摄像头';
                    } else {
                        realMsg = '手势启动失败: 请确认摄像头未被其他应用占用';
                    }
                    _origShowToast.call(window, realMsg);
                    return;
                }
                _origShowToast.call(window, msg);
            };
        }
    })();

    // ── S3. QQ 音乐登录: 内置 WebView 扫码登录 ──
    window._onQQLoginCookie = async function(rawCookie) {
        try {
            if (!rawCookie || rawCookie.trim() === '') {
                if (window.showToast) window.showToast('QQ音乐登录未完成, 未获取到cookie');
                return;
            }
            if (window.showToast) window.showToast('正在同步QQ音乐会话…');
            // POST cookie to server endpoint (same flow as submitQQCookieLogin / openQQWebLogin)
            var info = await window.apiJson('/api/qq/login/cookie', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cookie: rawCookie })
            });
            if (!info || !info.loggedIn) {
                var msg = (info && (info.message || info.error)) || 'QQ会话不可用';
                if (window.showToast) window.showToast('QQ音乐登录失败: ' + msg);
                return;
            }
            // 更新前端状态
            window.qqLoginStatus = info;
            window.activeAccountProvider = 'qq';
            if (typeof window.renderUserBtn === 'function') window.renderUserBtn();
            if (typeof window.refreshUserPlaylists === 'function') window.refreshUserPlaylists(true);
            // 关闭登录弹窗
            if (typeof window.closeLoginModal === 'function') window.closeLoginModal();
            var qqPlaybackReady = !!info.playbackKeyReady;
            if (window.showToast) window.showToast('QQ音乐已登录: ' + (info.nickname || info.userId || ''));
        } catch(e) {
            if (window.showToast) window.showToast('QQ音乐登录同步失败: ' + (e && e.message ? e.message : '网络错误'));
        }
    };

    // 覆盖 openQQWebLogin → 打开内置 QQLoginActivity
    var _origQQWebLogin = window.openQQWebLogin;
    if (_origQQWebLogin) {
        window.openQQWebLogin = function() {
            if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                if (window.showToast) window.showToast('正在打开QQ音乐登录页…');
                KeepApp.openQQMusicLogin('');
            } else {
                _origQQWebLogin();
            }
        };
    }
    // 覆盖 openProviderWebLogin → QQ时走内置
    var _origProviderWebLogin = window.openProviderWebLogin;
    if (_origProviderWebLogin) {
        window.openProviderWebLogin = function() {
            if (window.loginProvider === 'qq') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                    if (window.showToast) window.showToast('正在打开QQ音乐登录页…');
                    KeepApp.openQQMusicLogin('');
                    return;
                }
            }
            _origProviderWebLogin();
        };
    }

    // ── S4. 汽水音乐登录: 内置 WebView 扫码登录 ──
    window._onQishuiLoginCookie = async function(rawCookie) {
        try {
            if (!rawCookie || rawCookie.trim() === '') {
                if (window.showToast) window.showToast('汽水音乐登录未完成, 未获取到cookie');
                return;
            }
            if (window.showToast) window.showToast('正在同步汽水音乐会话…');
            var info = await window.apiJson('/api/qishui/login/cookie', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cookie: rawCookie })
            });
            if (!info || !info.loggedIn) {
                var msg = (info && (info.message || info.error)) || '汽水会话不可用';
                if (window.showToast) window.showToast('汽水音乐登录失败: ' + msg);
                return;
            }
            window.qsLoginStatus = info;
            window.activeAccountProvider = 'qishui';
            if (typeof window.renderUserBtn === 'function') window.renderUserBtn();
            if (typeof window.refreshUserPlaylists === 'function') window.refreshUserPlaylists(true);
            if (typeof window.closeLoginModal === 'function') window.closeLoginModal();
            if (window.showToast) window.showToast('汽水音乐已登录: ' + (info.nickname || info.userId || ''));
        } catch(e) {
            if (window.showToast) window.showToast('汽水音乐登录同步失败: ' + (e && e.message ? e.message : '网络错误'));
        }
    };

    // 覆盖 openProviderWebLogin → 汽水时走内置
    var _origProviderWebLogin2 = window.openProviderWebLogin;
    if (_origProviderWebLogin2) {
        window.openProviderWebLogin = function() {
            if (window.loginProvider === 'qq') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                    if (window.showToast) window.showToast('正在打开QQ音乐登录页…');
                    KeepApp.openQQMusicLogin('');
                    return;
                }
            }
            if (window.loginProvider === 'qishui') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQishuiMusicLogin) {
                    if (window.showToast) window.showToast('正在打开汽水音乐登录页…');
                    KeepApp.openQishuiMusicLogin('');
                    return;
                }
            }
            _origProviderWebLogin2();
        };
    }

    // ── S5. 酷狗音乐登录: 内置 WebView 扫码/账号登录 ──
    window._onKugouLoginCookie = async function(rawCookie) {
        try {
            if (!rawCookie || rawCookie.trim() === '') {
                if (window.showToast) window.showToast('酷狗音乐登录未完成, 未获取到cookie');
                return;
            }
            if (window.showToast) window.showToast('正在同步酷狗音乐会话…');
            var info = await window.apiJson('/api/kg/login/cookie', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cookie: rawCookie })
            });
            if (!info || !info.loggedIn) {
                var msg = (info && (info.message || info.error)) || '酷狗会话不可用';
                if (window.showToast) window.showToast('酷狗音乐登录失败: ' + msg);
                return;
            }
            window.kgLoginStatus = info;
            window.activeAccountProvider = 'kg';
            if (typeof window.renderUserBtn === 'function') window.renderUserBtn();
            if (typeof window.refreshUserPlaylists === 'function') window.refreshUserPlaylists(true);
            if (typeof window.closeLoginModal === 'function') window.closeLoginModal();
            if (window.showToast) window.showToast('酷狗音乐已登录: ' + (info.nickname || info.userId || ''));
        } catch(e) {
            if (window.showToast) window.showToast('酷狗音乐登录同步失败: ' + (e && e.message ? e.message : '网络错误'));
        }
    };

    // 覆盖 openProviderWebLogin → 酷狗时走内置
    var _origProviderWebLogin3 = window.openProviderWebLogin;
    if (_origProviderWebLogin3) {
        window.openProviderWebLogin = function() {
            if (window.loginProvider === 'qq') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQQMusicLogin) {
                    if (window.showToast) window.showToast('正在打开QQ音乐登录页…');
                    KeepApp.openQQMusicLogin('');
                    return;
                }
            }
            if (window.loginProvider === 'qishui') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openQishuiMusicLogin) {
                    if (window.showToast) window.showToast('正在打开汽水音乐登录页…');
                    KeepApp.openQishuiMusicLogin('');
                    return;
                }
            }
            if (window.loginProvider === 'kg') {
                if (typeof KeepApp !== 'undefined' && KeepApp.openKugouMusicLogin) {
                    if (window.showToast) window.showToast('正在打开酷狗音乐登录页…');
                    KeepApp.openKugouMusicLogin('');
                    return;
                }
            }
            _origProviderWebLogin3();
        };
    }

    // ── S. 摄像头权限拦截: 点击手势触碰时自动请求权限 ──
    window._camModePending = null;
    window._onCameraPermissionResult = function(granted) {
        if (granted && window._camModePending === 'gesture') {
            window._camModePending = null;
            if (typeof window._origSetCamMode === 'function') window._origSetCamMode('gesture');
        } else if (!granted) {
            window._camModePending = null;
            if (window.showToast) window.showToast('需要摄像头权限才能启用手势操作');
            var seg = document.getElementById('cam-seg');
            if (seg) {
                seg.querySelectorAll('button').forEach(function(b){ b.classList.toggle('active', b.dataset.cam === 'off'); });
            }
        }
    };
    var _origSetCamMode2 = window.setCamMode;
    if (_origSetCamMode2) {
        window._origSetCamMode = _origSetCamMode2;
        window.setCamMode = function(m) {
            if (m === 'gesture') {
                if (typeof KeepApp !== 'undefined' && KeepApp.requestCameraPermission) {
                    var hasPermission = KeepApp.requestCameraPermission();
                    if (!hasPermission) {
                        window._camModePending = 'gesture';
                        if (window.showToast) window.showToast('正在请求摄像头权限…');
                        return;
                    }
                }
            }
            window._origSetCamMode(m);
        };
    }

    // ── T. 设置文件持久化 → 覆盖 saveLyricLayout 和 readSavedLyricLayout ──
    (function(){
        // 从 Android 文件恢复设置 (优先级高于 localStorage)
        function loadSettingsFromFile() {
            if (typeof KeepApp === 'undefined' || !KeepApp.loadSettings) return;
            try {
                var json = KeepApp.loadSettings();
                if (!json || json === '{}') return;
                var data = JSON.parse(json);
                if (data && data.lyricLayout) {
                    try { localStorage.setItem('mineradio-lyric-layout-v1', JSON.stringify(data.lyricLayout)); } catch(e) {}
                }
                if (data && data.volume != null) {
                    try { localStorage.setItem('apex-player-volume', String(data.volume)); } catch(e) {}
                }
                if (data && data.booleans) {
                    var bools = data.booleans;
                    for (var k in bools) {
                        try { localStorage.setItem(k, bools[k] ? '1' : '0'); } catch(e) {}
                    }
                }
                // ★ 恢复本地歌单数据（从文件备份覆盖 localStorage）
                if (data && data.localLikes && Array.isArray(data.localLikes) && data.localLikes.length > 0) {
                    try { localStorage.setItem('mineradio-local-likes-v2', JSON.stringify(data.localLikes)); } catch(e) {}
                    try { if (typeof syncLikedSongMapFromLocal === 'function') syncLikedSongMapFromLocal(); } catch(e) {}
                }
                if (data && data.localPlaylists && Array.isArray(data.localPlaylists) && data.localPlaylists.length > 0) {
                    try { localStorage.setItem('mineradio-local-playlists-v2', JSON.stringify(data.localPlaylists)); } catch(e) {}
                }
                // ★ 恢复搜索历史（从文件备份覆盖 localStorage）
                if (data && data.searchHistory && Array.isArray(data.searchHistory) && data.searchHistory.length > 0) {
                    try { localStorage.setItem('mineradio-search-history', JSON.stringify(data.searchHistory)); } catch(e) {}
                }
            } catch(e) {}
        }
        setTimeout(loadSettingsFromFile, 1200);

        // 每 3 秒自动保存所有设置到文件
        var _lastSavedSettingsJson = '';
        function persistAllSettings() {
            if (typeof KeepApp === 'undefined' || !KeepApp.saveSettings) return;
            try {
                var likesData = [];
                var playlistsData = [];
                var searchHistoryData = [];
                try { likesData = JSON.parse(localStorage.getItem('mineradio-local-likes-v2') || '[]'); } catch(e) {}
                try { playlistsData = JSON.parse(localStorage.getItem('mineradio-local-playlists-v2') || '[]'); } catch(e) {}
                try { searchHistoryData = JSON.parse(localStorage.getItem('mineradio-search-history') || '[]'); } catch(e) {}
                var data = {
                    savedAt: Date.now(),
                    lyricLayout: JSON.parse(localStorage.getItem('mineradio-lyric-layout-v1') || '{}'),
                    volume: parseFloat(localStorage.getItem('apex-player-volume') || '0.8'),
                    booleans: {
                        'mineradio-diy-player-mode-v1': localStorage.getItem('mineradio-diy-player-mode-v1') === '1',
                        'mineradio-playlist-panel-pinned-v1': localStorage.getItem('mineradio-playlist-panel-pinned-v1') === '1',
                        'mineradio-user-capsule-auto-hide-v1': localStorage.getItem('mineradio-user-capsule-auto-hide-v1') === '1',
                        'mineradio-fx-fab-auto-hide-v1': localStorage.getItem('mineradio-fx-fab-auto-hide-v1') === '1',
                        'mineradio-controls-auto-hide-v1': localStorage.getItem('mineradio-controls-auto-hide-v1') === '1',
                        'mineradio-playback-quality-v1': localStorage.getItem('mineradio-playback-quality-v1'),
                        'mineradio-free-camera-v1': localStorage.getItem('mineradio-free-camera-v1') === '1'
                    },
                    localLikes: likesData,
                    localPlaylists: playlistsData,
                    searchHistory: searchHistoryData
                };
                var json = JSON.stringify(data);
                if (json !== _lastSavedSettingsJson) {
                    _lastSavedSettingsJson = json;
                    KeepApp.saveSettings(json);
                }
            } catch(e) {}
        }
        setInterval(persistAllSettings, 3000);

        // 每 15 秒也保存本地歌曲列表到文件备份
        function persistLocalSongsBackup() {
            if (typeof KeepApp === 'undefined' || !KeepApp.saveSettings) return;
            try {
                var songs = JSON.parse(localStorage.getItem('mineradio-local-songs-v1') || '[]');
                if (songs.length > 0) {
                    var backupKey = '__local_songs_backup__';
                    var prev = '';
                    try { prev = localStorage.getItem(backupKey) || ''; } catch(e) {}
                    var curr = JSON.stringify(songs);
                    if (curr !== prev) {
                        localStorage.setItem(backupKey, curr);
                    }
                }
            } catch(e) {}
        }
        setInterval(persistLocalSongsBackup, 15000);
    })();

    // ── U. 本地歌曲磁盘扫描同步 ──
    (function(){
        function syncLocalSongs() {
            if (typeof KeepApp === 'undefined' || !KeepApp.scanLocalSongs) {
                setTimeout(syncLocalSongs, 1000); return;
            }
            try {
                var json = KeepApp.scanLocalSongs();
                var diskFiles = JSON.parse(json || '[]');
                var songs = [];
                try { songs = JSON.parse(localStorage.getItem('mineradio-local-songs-v1') || '[]'); } catch(e) {}
                
                // 索引1: cachedFile -> entry（已有缓存的条目）
                var songsByFile = {};
                // 索引2: 磁盘文件 name（无扩展名） -> diskFile
                var diskByName = {};
                for (var i = 0; i < songs.length; i++) {
                    if (songs[i].cachedFile) songsByFile[songs[i].cachedFile] = songs[i];
                }
                for (var d = 0; d < diskFiles.length; d++) {
                    var diskBase = (diskFiles[d].name || diskFiles[d].fileName || '').replace(/\.[^.]+$/, '');
                    diskByName[diskBase] = diskFiles[d];
                }
                
                var changed = false;
                
                // ★ 第一步: 为 cachedFile 为空的条目尝试匹配磁盘文件
                for (var r = 0; r < songs.length; r++) {
                    if (songs[r].cachedFile) continue; // 已有缓存，跳过
                    var songName = (songs[r].name || '').trim();
                    if (!songName) continue;
                    // 尝试用歌名精确匹配磁盘文件
                    var matchedDisk = diskByName[songName];
                    if (matchedDisk && !songsByFile[matchedDisk.fileName]) {
                        songs[r].cachedFile = matchedDisk.fileName;
                        songs[r].duration = songs[r].duration || 0;
                        if (!songs[r].localKey) {
                            songs[r].localKey = [matchedDisk.fileName, matchedDisk.size, matchedDisk.lastModified].join(':');
                        }
                        songsByFile[matchedDisk.fileName] = songs[r];
                        changed = true;
                    }
                }
                
                // ★ 第二步: 磁盘文件补录到 localStorage
                for (var d2 = 0; d2 < diskFiles.length; d2++) {
                    var df = diskFiles[d2];
                    if (!songsByFile[df.fileName]) {
                        // 也检查是否已有同名的（修复后可能已经关联）
                        var alreadyExists = false;
                        for (var c = 0; c < songs.length; c++) {
                            if (songs[c].cachedFile === df.fileName) { alreadyExists = true; break; }
                        }
                        if (!alreadyExists) {
                            var newSong = {
                                type: 'local',
                                name: df.name || df.fileName,
                                artist: '本地文件',
                                localKey: [df.fileName, df.size, df.lastModified].join(':'),
                                duration: 0,
                                cachedFile: df.fileName
                            };
                            songs.unshift(newSong);
                            songsByFile[df.fileName] = newSong;
                            changed = true;
                        }
                    } else {
                        var existing = songsByFile[df.fileName];
                        if (existing.cachedFile !== df.fileName) {
                            existing.cachedFile = df.fileName;
                            changed = true;
                        }
                    }
                }
                
                // ★ 第三步: 清理失效条目
                // 规则: cachedFile 为空且 localUrl 是 blob: URL（已失效）→ 删除
                //       cachedFile 不为空但磁盘文件已删 → 删除
                var diskNames = {};
                for (var j = 0; j < diskFiles.length; j++) diskNames[diskFiles[j].fileName] = true;
                var filtered = [];
                for (var k = 0; k < songs.length; k++) {
                    var s = songs[k];
                    // 没有cachedFile，且localUrl是已失效的blob URL
                    if (!s.cachedFile && (!s.localUrl || s.localUrl.indexOf('blob:') === 0)) {
                        changed = true; continue;
                    }
                    // 有cachedFile但磁盘无此文件
                    if (s.cachedFile && !diskNames[s.cachedFile]) {
                        changed = true; continue;
                    }
                    // 没有cachedFile但localUrl可能还有效（如 /local-song/ 路径）
                    if (!s.cachedFile) filtered.push(s);
                    // 正常条目
                    else if (diskNames[s.cachedFile]) filtered.push(s);
                    else changed = true;
                }
                songs = filtered;
                
                if (songs.length > 50) songs = songs.slice(-50);
                if (changed) {
                    try { localStorage.setItem('mineradio-local-songs-v1', JSON.stringify(songs)); } catch(e) {}
                }
            } catch(e) {}
        }
        setTimeout(syncLocalSongs, 2000);

        // 暴露给 JS 层
        window.__syncLocalSongsFromDisk = syncLocalSongs;

        // 覆盖 persistLocalSong 确保调用 bridge 删除/保存
        var _origPersistLocalSong = window.persistLocalSong;
        if (_origPersistLocalSong) {
            window.persistLocalSong = function(songMeta) {
                _origPersistLocalSong(songMeta);
                // 立即同步到备份
                setTimeout(function(){
                    try {
                        var songs = JSON.parse(localStorage.getItem('mineradio-local-songs-v1') || '[]');
                        localStorage.setItem('__local_songs_backup__', JSON.stringify(songs));
                    } catch(e) {}
                }, 500);
            };
        }
    })();

    // ── V. 系统媒体控件辅助函数（通知栏/锁屏按钮回调）──
    (function(){
        // togglePlay: 通知栏/锁屏的播放/暂停按钮
        if (!window.togglePlay) {
            window.togglePlay = function() {
                var audio = document.querySelector('audio');
                if (!audio || !audio.src) return;
                if (audio.paused) {
                    audio.play().catch(function(){});
                } else {
                    audio.pause();
                }
            };
        }
        // nextTrack / prevTrack: 通知栏/锁屏的上一首/下一首按钮
        if (!window.nextTrack) {
            window.nextTrack = function() {
                try {
                    if (typeof playNext === 'function') playNext();
                } catch(e) {}
            };
        }
        if (!window.prevTrack) {
            window.prevTrack = function() {
                try {
                    if (typeof playPrev === 'function') playPrev();
                } catch(e) {}
            };
        }
        // handleMediaSeek: 通知栏的进度拖动
        if (!window.handleMediaSeek) {
            window.handleMediaSeek = function(sec) {
                var audio = document.querySelector('audio');
                if (audio && audio.duration) {
                    audio.currentTime = sec;
                    try { KeepApp.updateMediaPosition(sec, audio.duration); } catch(e) {}
                }
            };
        }
    })();
})();
        """.trimIndent()
    }
}
