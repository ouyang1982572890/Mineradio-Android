package com.mineradio.app.manager

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.mineradio.app.manager.NeteaseMusicApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.nio.charset.StandardCharsets

/**
 * 嵌入式 HTTP 服务器 — 为 Mineradio WebView 前端提供服务
 *
 * 职责:
 *  1. 提供静态文件服务 (assets/mineradio)
 *  2. 代理网易云音乐 API 调用
 *  3. 桥接 KeepApp Kotlin <-> JS 通道
 */
class MineradioServer(
    private val context: Context,
    private val port: Int = 0  // 0 = 自动分配端口
) {
    companion object {
        private const val TAG = "MineradioServer"
        private val MIME: Map<String, String> = mapOf(
            "html" to "text/html; charset=utf-8",
            "css"  to "text/css; charset=utf-8",
            "js"   to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "png"  to "image/png",
            "jpg"  to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif"  to "image/gif",
            "svg"  to "image/svg+xml",
            "ico"  to "image/x-icon",
            "bin"  to "application/octet-stream",
            "mp3"  to "audio/mpeg",
            "m4a"  to "audio/mp4",
            "flac" to "audio/flac",
            "ogg"  to "audio/ogg",
            "wav"  to "audio/wav",
            "webm" to "audio/webm",
            "txt"  to "text/plain; charset=utf-8"
        )
    }

    var actualPort: Int = 0
        private set
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val assets: AssetManager = context.assets
    private val staticBase = "mineradio/"

    fun start(): Int {
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        actualPort = serverSocket!!.localPort
        running = true
        Log.i(TAG, "服务器启动: http://127.0.0.1:$actualPort")
        Thread({ acceptLoop() }, "MineradioServer").start()
        return actualPort
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                Thread({ handleClient(client) }, "Mineradio-Client").start()
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "accept 异常: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "accept 异常: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = readRequest(input)
            if (request == null) { socket.close(); return }

            val (method, rawPath) = request
            val pathOnly = rawPath.substringBefore("?")
            val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

            Log.d(TAG, "$method $rawPath")

            when {
                // ── API 路由 ──
                pathOnly == "/api/login/qr/key" -> {
                    try {
                        val key = NeteaseMusicApi.getLoginQrKey()
                        sendJson(output, 200, JSONObject().apply { put("key", key) })
                    } catch (e: Exception) {
                        sendJson(output, 500, JSONObject().apply { put("error", e.message) })
                    }
                }
                pathOnly == "/api/login/qr/create" -> {
                    val key = getQueryParam(query, "key")
                    if (key.isNullOrBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("error", "Missing key") })
                    } else {
                        val base64 = NeteaseMusicApi.createLoginQrImage(key)
                        if (base64 != null) {
                            sendJson(output, 200, JSONObject().apply {
                                put("img", "data:image/png;base64,$base64")
                                put("url", "https://music.163.com/login?codekey=$key")
                            })
                        } else {
                            sendJson(output, 500, JSONObject().apply { put("error", "QR生成失败") })
                        }
                    }
                }
                pathOnly == "/api/login/qr/check" -> {
                    val key = getQueryParam(query, "key")
                    if (key.isNullOrBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("code", 400); put("message", "Missing key") })
                    } else {
                        val result = NeteaseMusicApi.checkLoginQrStatus(key)
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/login/status" -> {
                    val result = NeteaseMusicApi.getLoginStatus()
                    sendJson(output, 200, result)
                }
                pathOnly == "/api/login/cookie" -> {
                    val body = readBody(input, method)
                    val cookie = extractCookie(body)
                    if (cookie.isNotBlank()) {
                        NeteaseMusicApi.setCookie(cookie)
                        val status = NeteaseMusicApi.getLoginStatus()
                        status.put("saved", true)
                        status.put("hasCookie", NeteaseMusicApi.getCookie().isNotBlank())
                        sendJson(output, 200, status)
                    } else {
                        sendJson(output, 400, JSONObject().apply {
                            put("loggedIn", false); put("error", "INVALID_NETEASE_COOKIE")
                            put("message", "网易云 cookie 缺少 MUSIC_U")
                        })
                    }
                }
                pathOnly == "/api/logout" -> {
                    NeteaseMusicApi.logout()
                    sendJson(output, 200, JSONObject().apply { put("ok", true); put("loggedIn", false) })
                }
                // ── QQ 音乐 API 路由 ──
                pathOnly == "/api/qq/login/cookie" -> {
                    val bodyStr = readBody(input, method)
                    val raw = try {
                        val obj = JSONObject(bodyStr)
                        obj.optString("cookie", obj.optString("data", obj.optString("text", "")))
                    } catch (e: Exception) { bodyStr.trim() }
                    if (raw.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "qq"); put("loggedIn", false)
                            put("error", "INVALID_QQ_COOKIE"); put("message", "QQ cookie 为空")
                        })
                    } else {
                        val cookieObj = parseCookieObj(raw)
                        // ★ 使用全面的 key 匹配，支持 QQ/微信/手机等多端登录 cookie 格式
                        val uin = NeteaseMusicApi.qqCookieUin(cookieObj)
                        val musicKey = NeteaseMusicApi.qqCookieMusicKey(cookieObj)
                        if (uin.isEmpty() || musicKey.isEmpty()) {
                            sendJson(output, 400, JSONObject().apply {
                                put("provider", "qq"); put("loggedIn", false)
                                put("error", "INVALID_QQ_COOKIE"); put("message", "QQ cookie 缺少 uin 或有效登录票据")
                            })
                        } else {
                            NeteaseMusicApi.saveQQCookie(raw)
                            val info = NeteaseMusicApi.getQQLoginInfo()
                            info.put("saved", true)
                            sendJson(output, 200, info)
                        }
                    }
                }
                pathOnly == "/api/qq/login/status" -> {
                    val info = NeteaseMusicApi.getQQLoginInfo()
                    sendJson(output, 200, info)
                }
                pathOnly == "/api/qq/logout" -> {
                    NeteaseMusicApi.saveQQCookie("")
                    sendJson(output, 200, JSONObject().apply {
                        put("provider", "qq"); put("ok", true); put("loggedIn", false)
                    })
                }
                pathOnly == "/api/qq/user/playlists" -> {
                    val result = NeteaseMusicApi.getQQUserPlaylists()
                    sendJson(output, 200, result)
                }
                pathOnly == "/api/qq/playlist/tracks" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    if (id.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("loggedIn", false); put("provider", "qq"); put("tracks", JSONArray())
                        })
                    } else {
                        val result = NeteaseMusicApi.getQQPlaylistTracks(id)
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/qq/song/url" -> {
                    val mid = getQueryParam(query, "mid") ?: getQueryParam(query, "id") ?: ""
                    val mediaMid = getQueryParam(query, "mediaMid") ?: getQueryParam(query, "media_mid") ?: ""
                    val quality = getQueryParam(query, "quality") ?: ""
                    if (mid.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "qq"); put("url", ""); put("playable", false)
                            put("error", "Missing QQ song mid")
                        })
                    } else {
                        val result = NeteaseMusicApi.getQQSongUrl(mid, mediaMid, quality)
                        sendJson(output, 200, result)
                    }
                }
                // ── 网易云音乐搜索 ──
                pathOnly == "/api/search" -> {
                    val keyword = getQueryParam(query, "keywords") ?: getQueryParam(query, "keyword")
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 30
                    val offset = getQueryParam(query, "offset")?.toIntOrNull() ?: 0
                    if (keyword.isNullOrBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("songs", JSONArray()); put("total", 0) })
                    } else {
                        val result = try {
                            NeteaseMusicApi.searchSongs(keyword, limit, offset)
                        } catch (e: Exception) {
                            JSONObject().apply { put("songs", JSONArray()); put("total", 0); put("error", e.message) }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/song/url" -> {
                    val id = getQueryParam(query, "id")?.toLongOrNull()
                    val quality = getQueryParam(query, "quality") ?: "exhigh"
                    if (id == null) {
                        sendJson(output, 400, JSONObject().apply { put("url", ""); put("playable", false) })
                    } else {
                        val result = try {
                            NeteaseMusicApi.getSongUrl(id, quality)
                        } catch (e: Exception) {
                            JSONObject().apply { put("url", ""); put("playable", false); put("error", e.message) }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/lyric" -> {
                    val id = getQueryParam(query, "id")?.toLongOrNull()
                    if (id == null) {
                        sendJson(output, 400, JSONObject().apply { put("lyric", "") })
                    } else {
                        val result = try {
                            NeteaseMusicApi.getLyric(id)
                        } catch (e: Exception) {
                            JSONObject().apply { put("lyric", ""); put("translate", ""); put("error", e.message) }
                        }
                        sendJson(output, 200, result)
                    }
                }
                // 根据歌名搜索歌词（用于本地歌曲）
                pathOnly == "/api/lyric/search" -> {
                    val name = getQueryParam(query, "name") ?: ""
                    val artist = getQueryParam(query, "artist") ?: ""
                    if (name.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("lyric", ""); put("error", "Missing name") })
                    } else {
                        val result = try {
                            val lyric = NeteaseMusicApi.searchLyricByName(name, artist)
                            JSONObject().apply { put("lyric", lyric ?: ""); put("found", lyric != null) }
                        } catch (e: Exception) {
                            JSONObject().apply { put("lyric", ""); put("found", false); put("error", e.message ?: "unknown") }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/user/playlists" -> {
                    val result = try {
                        NeteaseMusicApi.getUserPlaylists()
                    } catch (e: Exception) {
                        JSONObject().apply { put("loggedIn", false); put("playlists", JSONArray()) }
                    }
                    sendJson(output, 200, result)
                }
                pathOnly == "/api/playlist/tracks" -> {
                    val id = getQueryParam(query, "id")?.toLongOrNull()
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 100000
                    if (id == null) {
                        sendJson(output, 400, JSONObject().apply { put("tracks", JSONArray()) })
                    } else {
                        val result = try {
                            NeteaseMusicApi.getPlaylistTracks(id, limit)
                        } catch (e: Exception) {
                            JSONObject().apply { put("tracks", JSONArray()); put("error", e.message) }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/song/like" -> {
                    val body = readBody(input, method)
                    val trackId = extractJsonParam(body, "id")?.toLongOrNull()
                    val like = extractJsonParam(body, "like")?.equals("true", ignoreCase = true) ?: true
                    if (trackId == null) {
                        sendJson(output, 400, JSONObject().apply { put("code", 400); put("message", "Missing id") })
                    } else {
                        val result = try {
                            NeteaseMusicApi.likeSong(trackId, like)
                        } catch (e: Exception) {
                            JSONObject().apply { put("code", 500); put("error", e.message) }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/app/version" -> {
                    try {
                        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        sendJson(output, 200, JSONObject().apply {
                            put("version", pkgInfo.versionName ?: "1.0.0")
                            put("platform", "android")
                        })
                    } catch (e: Exception) {
                        sendJson(output, 200, JSONObject().apply {
                            put("version", "1.0.0"); put("platform", "android")
                        })
                    }
                }
                pathOnly == "/api/discover/home" -> {
                    try {
                        val loginStatus = NeteaseMusicApi.getLoginStatus()
                        val qqLoginStatus = NeteaseMusicApi.getQQLoginInfo()
                        val kgLoginStatus = NeteaseMusicApi.getKGLoginInfo()
                        val qsLoginStatus = NeteaseMusicApi.getQSLoginInfo()
                        val loggedIn = loginStatus.optBoolean("loggedIn", false)
                        val qqLoggedIn = qqLoginStatus.optBoolean("loggedIn", false)
                        val kgLoggedIn = kgLoginStatus.optBoolean("loggedIn", false)
                        val anyLoggedIn = loggedIn || qqLoggedIn || kgLoggedIn
                        val result = JSONObject()
                        result.put("loggedIn", anyLoggedIn)
                        result.put("kgLoggedIn", kgLoggedIn)
                        result.put("qsAvailable", true)
                        if (loggedIn) {
                            result.put("user", JSONObject().apply {
                                put("userId", loginStatus.optInt("userId", 0))
                                put("nickname", loginStatus.optString("nickname", "网易云用户"))
                                put("avatar", loginStatus.optString("avatar", ""))
                            })
                            val rec = NeteaseMusicApi.getRecommendSongs()
                            result.put("dailySongs", rec.optJSONArray("songs") ?: JSONArray())
                        } else if (qqLoggedIn) {
                            result.put("user", JSONObject().apply {
                                put("userId", qqLoginStatus.optString("userId", ""))
                                put("nickname", qqLoginStatus.optString("nickname", "QQ音乐用户"))
                                put("avatar", qqLoginStatus.optString("avatar", ""))
                            })
                            result.put("dailySongs", JSONArray())
                        } else if (kgLoggedIn) {
                            result.put("user", JSONObject().apply {
                                put("userId", kgLoginStatus.optString("userId", ""))
                                put("nickname", kgLoginStatus.optString("nickname", "酷狗用户"))
                                put("avatar", kgLoginStatus.optString("avatar", ""))
                            })
                            result.put("dailySongs", JSONArray())
                        } else {
                            result.put("user", JSONObject.NULL)
                            result.put("dailySongs", JSONArray())
                        }
                        result.put("playlists", JSONArray())
                        result.put("podcasts", JSONArray())
                        result.put("updatedAt", System.currentTimeMillis())
                        sendJson(output, 200, result)
                    } catch (e: Exception) {
                        sendJson(output, 200, JSONObject().apply {
                            put("loggedIn", false)
                            put("user", JSONObject.NULL)
                            put("dailySongs", JSONArray())
                            put("playlists", JSONArray())
                            put("podcasts", JSONArray())
                            put("updatedAt", System.currentTimeMillis())
                        })
                    }
                }
                pathOnly == "/favicon.ico" -> {
                    sendEmpty(output, 204)
                }
                pathOnly == "/api/cover" -> {
                    // 代理封面图片请求
                    proxyUrl(output, query, "url")
                }
                pathOnly == "/api/audio" -> {
                    // 代理音频请求
                    proxyUrl(output, query, "url")
                }
                // ── 存根路由（返回空数据，不让前端报错） ──
                pathOnly == "/api/qq/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 8
                    val result = NeteaseMusicApi.getQQSearch(kw, limit)
                    sendJson(output, 200, result)
                }
                pathOnly == "/api/qq/song/comments" -> sendJson(output, 200, JSONObject().apply {
                    put("provider", "qq"); put("comments", JSONArray()); put("total", 0)
                })
                pathOnly == "/api/qq/artist/detail" -> sendJson(output, 200, JSONObject().apply {
                    put("provider", "qq"); put("artist", JSONObject.NULL); put("songs", JSONArray())
                })
                pathOnly == "/api/qq/lyric" -> {
                    val mid = getQueryParam(query, "mid") ?: getQueryParam(query, "songmid") ?: ""
                    val id = getQueryParam(query, "id") ?: getQueryParam(query, "qqId") ?: ""
                    if (mid.isBlank() && id.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "qq"); put("error", "Missing QQ song mid or id"); put("lyric", "")
                        })
                    } else {
                        val result = try {
                            NeteaseMusicApi.getQQLyric(mid, id)
                        } catch (e: Exception) {
                            JSONObject().apply {
                                put("provider", "qq"); put("error", e.message ?: "unknown"); put("lyric", "")
                            }
                        }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly.startsWith("/api/qq/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                // ── 酷狗音乐 API 路由 ──
                pathOnly == "/api/kg/login/cookie" -> {
                    val bodyStr = readBody(input, method)
                    val raw = try {
                        val obj = JSONObject(bodyStr)
                        obj.optString("cookie", obj.optString("data", obj.optString("text", "")))
                    } catch (e: Exception) { bodyStr.trim() }
                    if (raw.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "kg"); put("loggedIn", false)
                            put("error", "INVALID_KG_COOKIE"); put("message", "酷狗 cookie 为空")
                        })
                    } else {
                        NeteaseMusicApi.saveKGCookie(raw)
                        val info = NeteaseMusicApi.getKGLoginInfo()
                        info.put("saved", true)
                        sendJson(output, 200, info)
                    }
                }
                pathOnly == "/api/kg/login/status" -> {
                    sendJson(output, 200, NeteaseMusicApi.getKGLoginInfo())
                }
                pathOnly == "/api/kg/logout" -> {
                    NeteaseMusicApi.saveKGCookie("")
                    sendJson(output, 200, JSONObject().apply {
                        put("provider", "kg"); put("ok", true); put("loggedIn", false)
                    })
                }
                pathOnly == "/api/kg/user/playlists" -> {
                    sendJson(output, 200, NeteaseMusicApi.getKGUserPlaylists())
                }
                pathOnly == "/api/kg/playlist/tracks" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getKGPlaylistTracks(id))
                }
                pathOnly == "/api/kg/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 12
                    sendJson(output, 200, NeteaseMusicApi.getKGSearch(kw, limit))
                }
                pathOnly == "/api/kg/song/url" -> {
                    val hash = getQueryParam(query, "hash") ?: getQueryParam(query, "id") ?: ""
                    val albumId = getQueryParam(query, "albumId") ?: getQueryParam(query, "kgAlbumId") ?: ""
                    val quality = getQueryParam(query, "quality") ?: ""
                    if (hash.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "kg"); put("url", ""); put("playable", false)
                            put("error", "Missing KG song hash")
                        })
                    } else {
                        sendJson(output, 200, NeteaseMusicApi.getKGSongUrl(hash, albumId, quality))
                    }
                }
                pathOnly == "/api/kg/lyric" -> {
                    val hash = getQueryParam(query, "hash") ?: getQueryParam(query, "id") ?: ""
                    val albumId = getQueryParam(query, "albumId") ?: getQueryParam(query, "kgAlbumId") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getKGLyric(hash, albumId))
                }
                // ── 汽水音乐 API 路由 ──
                pathOnly == "/api/qishui/login/status" -> {
                    sendJson(output, 200, NeteaseMusicApi.getQSLoginInfo())
                }
                pathOnly == "/api/qishui/login/cookie" -> {
                    val bodyStr = readBody(input, method)
                    val raw = try {
                        val obj = JSONObject(bodyStr)
                        obj.optString("cookie", obj.optString("data", obj.optString("text", "")))
                    } catch (e: Exception) { bodyStr.trim() }
                    if (raw.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "qishui"); put("loggedIn", false)
                            put("error", "INVALID_QS_COOKIE"); put("message", "汽水 cookie 为空")
                        })
                    } else {
                        NeteaseMusicApi.saveQSCookie(raw)
                        val info = NeteaseMusicApi.getQSLoginInfo()
                        info.put("saved", true)
                        sendJson(output, 200, info)
                    }
                }
                pathOnly == "/api/qishui/login/status" -> {
                    sendJson(output, 200, NeteaseMusicApi.getQSLoginInfo())
                }
                pathOnly == "/api/qishui/logout" -> {
                    NeteaseMusicApi.saveQSCookie("")
                    sendJson(output, 200, JSONObject().apply {
                        put("provider", "qishui"); put("ok", true); put("loggedIn", false)
                    })
                }
                pathOnly == "/api/qishui/user/playlists" -> {
                    sendJson(output, 200, NeteaseMusicApi.getQSUserPlaylists())
                }
                pathOnly == "/api/qishui/playlist/tracks" -> {
                    val id = getQueryParam(query, "id") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getQSPlaylistTracks(id))
                }
                pathOnly == "/api/qishui/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 12
                    sendJson(output, 200, NeteaseMusicApi.getQSSearch(kw, limit))
                }
                pathOnly == "/api/qishui/song/url" -> {
                    val trackId = getQueryParam(query, "trackId") ?: getQueryParam(query, "id") ?: ""
                    if (trackId.isBlank()) {
                        sendJson(output, 400, JSONObject().apply {
                            put("provider", "qishui"); put("url", ""); put("playable", false)
                            put("error", "Missing QS track id")
                        })
                    } else {
                        sendJson(output, 200, NeteaseMusicApi.getQSSongUrl(trackId))
                    }
                }
                pathOnly == "/api/qishui/lyric" -> {
                    val trackId = getQueryParam(query, "trackId") ?: getQueryParam(query, "id") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getQSLyric(trackId))
                }
                // ── 酷我音乐 ──
                pathOnly == "/api/kuwo/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 20
                    sendJson(output, 200, NeteaseMusicApi.getKuwoSearch(kw, limit))
                }
                pathOnly == "/api/kuwo/song/url" -> {
                    val rid = getQueryParam(query, "id") ?: getQueryParam(query, "rid") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getKuwoSongUrl(rid))
                }
                pathOnly == "/api/kuwo/lyric" -> {
                    val rid = getQueryParam(query, "id") ?: getQueryParam(query, "rid") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getKuwoLyric(rid))
                }
                // ── 咪咕音乐 ──
                pathOnly == "/api/migu/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 20
                    sendJson(output, 200, NeteaseMusicApi.getMiguSearch(kw, limit))
                }
                pathOnly == "/api/migu/song/url" -> {
                    val copyrightId = getQueryParam(query, "copyrightId") ?: getQueryParam(query, "id") ?: ""
                    val contentId = getQueryParam(query, "contentId") ?: ""
                    val toneControl = getQueryParam(query, "toneControl") ?: getQueryParam(query, "quality") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getMiguSongUrl(copyrightId, contentId, toneControl))
                }
                pathOnly == "/api/migu/lyric" -> {
                    val copyrightId = getQueryParam(query, "copyrightId") ?: getQueryParam(query, "id") ?: ""
                    val contentId = getQueryParam(query, "contentId") ?: ""
                    sendJson(output, 200, NeteaseMusicApi.getMiguLyric(copyrightId, contentId))
                }
                // ── 千千音乐 ──
                pathOnly == "/api/qianqian/search" -> {
                    val kw = getQueryParam(query, "keywords") ?: ""
                    val limit = getQueryParam(query, "limit")?.toIntOrNull() ?: 20
                    sendJson(output, 200, NeteaseMusicApi.getQianqianSearch(kw, limit))
                }
                pathOnly == "/api/qianqian/song/url" -> {
                    val tsid = getQueryParam(query, "id") ?: getQueryParam(query, "tsid") ?: ""
                    val rate = getQueryParam(query, "rate") ?: "320"
                    sendJson(output, 200, NeteaseMusicApi.getQianqianSongUrl(tsid, rate))
                }
                pathOnly.startsWith("/api/kg/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                pathOnly.startsWith("/api/qishui/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                // ── 视频音频提取 ──
                pathOnly == "/api/video/extract" && method == "POST" -> {
                    try {
                        val body = readBody(input, method)
                        // body 是一个 data URL: data:video/mp4;base64,AAAA...
                        val base64Data = body.substringAfter("base64,").trim()
                        val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        // 写入临时文件
                        val tempDir = File(context.cacheDir, "video_extract")
                        tempDir.mkdirs()
                        val tempVideo = File(tempDir, "temp_${System.currentTimeMillis()}.mp4")
                        tempVideo.writeBytes(rawBytes)
                        // 使用 MediaExtractor 提取音轨
                        val extractAudioOutput = File(tempDir, "audio_${System.currentTimeMillis()}.aac")
                        var extracted = false
                        try {
                            val extractor = MediaExtractor()
                            extractor.setDataSource(tempVideo.absolutePath)
                            var audioIdx = -1
                            for (i in 0 until extractor.trackCount) {
                                val fmt = extractor.getTrackFormat(i)
                                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                                if (mime.startsWith("audio/")) { audioIdx = i; break }
                            }
                            if (audioIdx >= 0) {
                                extractor.selectTrack(audioIdx)
                                val outStream = FileOutputStream(extractAudioOutput)
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
                        } catch (ex: Exception) {
                            Log.e(TAG, "MediaExtractor failed", ex)
                        }
                        // 清理临时视频
                        tempVideo.delete()
                        if (extracted && extractAudioOutput.exists() && extractAudioOutput.length() > 0) {
                            val bytes = extractAudioOutput.readBytes()
                            extractAudioOutput.delete()
                            // 返回原始音频数据
                            sendBinary(output, 200, "audio/aac", bytes)
                        } else {
                            sendJson(output, 500, JSONObject().apply { put("error", "extraction failed") })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "video extract error", e)
                        sendJson(output, 500, JSONObject().apply { put("error", e.message ?: "unknown") })
                    }
                }
                pathOnly.startsWith("/api/podcast/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                pathOnly.startsWith("/api/beatmap/") -> {
                    val subPath = pathOnly.substringAfter("/api/beatmap/")
                    if (subPath == "cache" && method == "GET") {
                        val cacheKey = getQueryParam(query, "key") ?: ""
                        if (cacheKey.isBlank()) {
                            sendJson(output, 400, JSONObject().apply { put("error", "Missing key") })
                        } else {
                            serveBeatCache(output, cacheKey)
                        }
                    } else if (subPath == "cache" && method == "POST") {
                        val body = readBody(input, method)
                        serveBeatCacheWrite(output, body)
                    } else {
                        sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                    }
                }
                pathOnly.startsWith("/api/update/") -> sendJson(output, 200, JSONObject().apply { put("notImplemented", true) })
                pathOnly == "/api/artist/detail" -> sendJson(output, 200, JSONObject().apply {
                    put("artist", JSONObject()); put("songs", JSONArray())
                })
                pathOnly == "/api/song/comments" -> sendJson(output, 200, JSONObject().apply {
                    put("comments", JSONArray()); put("hotComments", JSONArray())
                })
                pathOnly == "/api/song/like/check" -> {
                    val idsParam = getQueryParam(query, "ids") ?: getQueryParam(query, "id") ?: ""
                    val ids = idsParam.split(",").mapNotNull { it.trim().toLongOrNull() }
                    if (ids.isEmpty()) {
                        sendJson(output, 400, JSONObject().apply { put("liked", JSONObject()); put("ids", JSONArray()) })
                    } else {
                        val result = try { NeteaseMusicApi.likeCheck(ids) }
                            catch (e: Exception) { JSONObject().apply { put("code", 500); put("liked", JSONObject()) } }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/playlist/create" -> {
                    val body = readBody(input, method)
                    val name = extractJsonParam(body, "name") ?: getQueryParam(query, "name") ?: ""
                    val privacy = extractJsonParam(body, "privacy") ?: getQueryParam(query, "privacy") ?: "0"
                    if (name.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("code", 400); put("message", "Missing name") })
                    } else {
                        val result = try { NeteaseMusicApi.createPlaylist(name, privacy) }
                            catch (e: Exception) { JSONObject().apply { put("code", 500); put("error", e.message) } }
                        sendJson(output, 200, result)
                    }
                }
                pathOnly == "/api/playlist/add-song" -> {
                    val body = readBody(input, method)
                    val pid = (extractJsonParam(body, "pid") ?: getQueryParam(query, "pid"))?.toLongOrNull()
                    val trackIds = extractJsonParam(body, "id") ?: extractJsonParam(body, "ids") ?: getQueryParam(query, "id") ?: getQueryParam(query, "ids") ?: ""
                    if (pid == null || trackIds.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("code", 400); put("message", "Missing pid or track ids") })
                    } else {
                        val result = try { NeteaseMusicApi.addSongToPlaylist(pid, trackIds) }
                            catch (e: Exception) { JSONObject().apply { put("code", 500); put("error", e.message) } }
                        sendJson(output, 200, result)
                    }
                }
                // ── QQ 音乐操作 ──
                pathOnly == "/api/qq/song/like" -> {
                    // QQ 音乐红心暂时没有官方 API，返回友好错误
                    sendJson(output, 200, JSONObject().apply {
                        put("provider", "qq"); put("liked", false)
                        put("error", "QQ_MUSIC_LIKE_NOT_SUPPORTED")
                        put("message", "QQ 音乐红心功能需升级接入")
                    })
                }
                pathOnly == "/api/qq/song/like/check" -> sendJson(output, 200, JSONObject().apply {
                    put("provider", "qq"); put("liked", JSONObject())
                })
                pathOnly == "/api/qq/playlist/add-song" -> {
                    val body = readBody(input, method)
                    val pid = extractJsonParam(body, "pid") ?: getQueryParam(query, "pid") ?: ""
                    val mid = extractJsonParam(body, "mid") ?: getQueryParam(query, "mid") ?: ""
                    if (pid.isBlank() || mid.isBlank()) {
                        sendJson(output, 400, JSONObject().apply { put("code", 400); put("message", "Missing pid or mid") })
                    } else {
                        val result = try { NeteaseMusicApi.qqAddSongToPlaylist(pid, mid) }
                            catch (e: Exception) { JSONObject().apply { put("code", 500); put("error", e.message) } }
                        sendJson(output, 200, result)
                    }
                }
                // ── 本地歌曲文件服务（持久化） ──
                pathOnly.startsWith("/local-song/") -> {
                    val fileName = pathOnly.substringAfter("/local-song/")
                    serveLocalSongFile(output, fileName)
                }
                // ── SAF 文件服务（Android 文件夹导入的 content:// URI / 绝对路径） ──
                pathOnly.startsWith("/api/local-file") -> {
                    val filePath = getQueryParam(query, "path") ?: ""
                    val uriStr = getQueryParam(query, "uri") ?: ""
                    if (filePath.isNotBlank()) {
                        serveFilePath(output, filePath)
                    } else {
                        serveSafFile(output, uriStr)
                    }
                }
                // ── 静态文件 ──
                else -> serveStaticFile(output, pathOnly)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理请求异常: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════
    //  静态文件服务
    // ═══════════════════════════════════════════════

    private fun serveStaticFile(output: OutputStream, path: String) {
        try {
            var filePath = path.trimStart('/')
            if (filePath.isEmpty() || filePath == "/") filePath = "index.html"

            val assetPath = "$staticBase$filePath"
            var stream: InputStream? = null
            try {
                stream = assets.open(assetPath)
            } catch (e: Exception) {
                // 尝试不带 prefix
                try { stream = assets.open(filePath) } catch (_: Exception) {}
            }

            if (stream == null) {
                // 如果是 SPA 路由，回退到 index.html
                try {
                    stream = assets.open("${staticBase}index.html")
                } catch (e: Exception) {
                    send404(output)
                    return
                }
            }

            val ext = filePath.substringAfterLast('.', "html")
            val mime = MIME[ext] ?: "application/octet-stream"
            val data = stream.readBytes()
            stream.close()

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveStatic: ${e.message}")
            send404(output)
        }
    }

    // ═══════════════════════════════════════════════
    //  HTTP 工具
    // ═══════════════════════════════════════════════

    private fun sendJson(output: OutputStream, status: Int, json: JSONObject) {
        val body = json.toString()
        val header = "HTTP/1.1 $status ${statusText(status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun send404(output: OutputStream) {
        val body = "Not Found"
        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    /**
     * 从内部存储提供本地歌曲文件
     */
    private fun serveLocalSongFile(output: OutputStream, fileName: String) {
        try {
            // ★ URL 解码文件名（中文等非 ASCII 字符在 HTTP 路径中会被 % 编码）
            val decoded = try { URLDecoder.decode(fileName, "UTF-8") } catch (_: Exception) { fileName }
            val safeName = decoded.replace(Regex("[\\\\/]"), "").replace("..", ".")
            if (safeName.isEmpty()) {
                send404(output)
                return
            }
            val songsDir = File(context.filesDir, "local_songs")
            val file = File(songsDir, safeName)
            if (!file.exists() || !file.isFile) {
                send404(output)
                return
            }
            val ext = file.name.substringAfterLast('.', "mp3")
            val mime = MIME[ext] ?: "audio/mpeg"
            val contentLength = file.length()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ 流式读取，64KB 缓冲区，避免 readBytes() 一次性全部读入内存
            val buf = ByteArray(65536)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveLocalSongFile error", e)
            send404(output)
        }
    }

    /**
     * 通过 content:// URI 提供 SAF 文件（文件夹导入的音频）
     */
    private fun serveSafFile(output: OutputStream, uriStr: String) {
        try {
            if (uriStr.isBlank()) { send404(output); return }
            val uri = Uri.parse(uriStr)
            val cr = context.contentResolver
            // 获取文件大小
            var fileSize = 0L
            var fileName = "audio"
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(0) ?: "audio"
                    fileSize = cursor.getLong(1)
                }
            }
            val ext = fileName.substringAfterLast('.', "mp3").lowercase()
            val mime = MIME[ext] ?: "audio/mpeg"
            val inputStream = cr.openInputStream(uri) ?: run { send404(output); return }
            if (fileSize <= 0) fileSize = inputStream.available().toLong().coerceAtLeast(1)
            inputStream.use { stream ->
                val data = stream.readBytes()
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${data.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: max-age=86400\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray())
                output.write(data)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "serveSafFile error: ${e.message}")
            send404(output)
        }
    }

    /**
     * 通过绝对路径 serve 文件（内置文件管理器）
     */
    private fun serveFilePath(output: OutputStream, filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists() || !file.isFile) { send404(output); return }
            val ext = file.extension.lowercase().ifBlank { "mp3" }
            val mime = MIME[ext] ?: "audio/mpeg"
            val contentLength = file.length()
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Cache-Control: max-age=86400\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            // ★ 流式读取，64KB 缓冲区
            val buf = ByteArray(65536)
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } != -1) {
                    output.write(buf, 0, bytesRead)
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveFilePath error: ${e.message}")
            send404(output)
        }
    }

    private fun sendEmpty(output: OutputStream, status: Int) {
        val header = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
    }

    private fun sendBinary(output: OutputStream, status: Int, contentType: String, data: ByteArray) {
        val header = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(data)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 204 -> "No Content"; 301 -> "Moved Permanently"
        302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"
        else -> ""
    }

    // ═══════════════════════════════════════════════
    //  请求解析
    // ═══════════════════════════════════════════════

    private fun readRequest(input: InputStream): Pair<String, String>? {
        val sb = StringBuilder()
        var prev = 0; var cur: Int
        var headerComplete = false
        while (true) {
            cur = input.read()
            if (cur == -1) break
            sb.append(cur.toChar())
            if (prev == '\r'.code && cur == '\n'.code && sb.endsWith("\r\n\r\n")) {
                headerComplete = true
                break
            }
            prev = cur
            if (sb.length > 8192) break
        }
        if (!headerComplete || sb.isEmpty()) return null

        val lines = sb.toString().split("\r\n")
        if (lines.isEmpty()) return null

        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0]
        val path = first[1]

        return Pair(method, path)
    }

    private fun readBody(input: InputStream, method: String): String {
        if (method == "GET" || method == "HEAD") return ""
        return try {
            val bytes = ByteArray(65536)
            val read = input.read(bytes)
            if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else ""
        } catch (e: Exception) { "" }
    }

    private fun getQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        return try {
            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && URLDecoder.decode(parts[0], "UTF-8") == key) {
                    return URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun extractCookie(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val json = JSONObject(body)
            json.optString("cookie", json.optString("data", json.optString("text", "")))
        } catch (e: Exception) {
            body.trim()
        }
    }

    private fun extractJsonParam(body: String, key: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = JSONObject(body)
            if (obj.has(key)) obj.getString(key) else null
        } catch (e: Exception) { null }
    }

    private fun parseCookieObj(cookieText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cookieText.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    /**
     * 代理外部 URL 内容（用于封面/音频转发，解决 WebView 跨域+Referer问题）
     */
    private fun proxyUrl(output: OutputStream, query: String, paramName: String) {
        try {
            val rawUrl = getQueryParam(query, paramName)
            if (rawUrl.isNullOrBlank()) {
                send404(output)
                return
            }
            val url = URL(rawUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.requestMethod = "GET"
            // ★ 禁用 keep-alive，每次请求建立新连接，避免复用慢速连接
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            when {
                rawUrl.contains("music.126.net") || rawUrl.contains("music.163.com") || rawUrl.contains("126.net") -> {
                    conn.setRequestProperty("Referer", "https://music.163.com/")
                    // 转发网易云音乐Cookie，确保VIP/付费歌曲能正常播放
                    val neCookie = NeteaseMusicApi.getCookie()
                    if (neCookie.isNotBlank()) {
                        conn.setRequestProperty("Cookie", neCookie)
                    }
                }
                rawUrl.contains("qqmusic") || rawUrl.contains("qq.com") || rawUrl.contains("y.qq.com") -> {
                    conn.setRequestProperty("Referer", "https://y.qq.com/")
                    // 转发QQ音乐Cookie，确保需要授权的歌曲能正常播放
                    val qqC = NeteaseMusicApi.getQQCookie()
                    if (qqC.isNotBlank()) {
                        conn.setRequestProperty("Cookie", qqC)
                    }
                }
                rawUrl.contains("woff") || rawUrl.contains("google") ->
                    conn.setRequestProperty("Referer", "")
                else ->
                    conn.setRequestProperty("Referer", "https://music.163.com/")
            }

            var workingConn: HttpURLConnection = conn
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code in 301..308) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null && location.startsWith("http")) {
                        val redirectUrl = URL(location)
                        val redirConn = redirectUrl.openConnection() as HttpURLConnection
                        redirConn.connectTimeout = 15000
                        redirConn.readTimeout = 120000
                        redirConn.requestMethod = "GET"
                        redirConn.setRequestProperty("Connection", "close")
                        redirConn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        // 重定向也同样需要携带Cookie
                        val redirHost = redirectUrl.host.lowercase()
                        when {
                            redirHost.contains("music.126.net") || redirHost.contains("music.163.com") || redirHost.contains("126.net") -> {
                                redirConn.setRequestProperty("Referer", "https://music.163.com/")
                                val neCookie = NeteaseMusicApi.getCookie()
                                if (neCookie.isNotBlank()) {
                                    redirConn.setRequestProperty("Cookie", neCookie)
                                }
                            }
                            redirHost.contains("qqmusic") || redirHost.contains("qq.com") || redirHost.contains("y.qq.com") -> {
                                redirConn.setRequestProperty("Referer", "https://y.qq.com/")
                                val qqC = NeteaseMusicApi.getQQCookie()
                                if (qqC.isNotBlank()) {
                                    redirConn.setRequestProperty("Cookie", qqC)
                                }
                            }
                            else ->
                                redirConn.setRequestProperty("Referer", "https://music.163.com/")
                        }
                        if (redirConn.responseCode in 200..299) {
                            workingConn = redirConn
                        } else {
                            redirConn.disconnect()
                            sendEmpty(output, 302)
                            return
                        }
                    } else {
                        sendEmpty(output, 302)
                        return
                    }
                } else {
                    sendEmpty(output, 302)
                    return
                }
            }

            val contentType = workingConn.contentType ?: "application/octet-stream"
            val contentLength = workingConn.contentLength
            val inputStream = workingConn.inputStream

            // 流式传输：立即发送 header，然后 8KB 分块发送数据
            // 不再 readBytes() 一次性下载整首歌 — 那会导致 10-30 秒等待
            val header = if (contentLength > 0) {
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: public, max-age=3600\r\n" +
                    "Connection: close\r\n\r\n"
            } else {
                // 未知长度用 chunked 编码
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: public, max-age=3600\r\n" +
                    "Connection: close\r\n\r\n"
            }
            output.write(header.toByteArray())
            output.flush()

            // 分块流式写入 — 使用 64KB 大缓冲区提升吞吐量
            val buf = ByteArray(65536)
            var totalRead = 0L
            var bytesRead: Int
            while (inputStream.read(buf).also { bytesRead = it } != -1) {
                if (contentLength <= 0) {
                    // chunked encoding: 写入 chunk 大小 + 数据 + CRLF
                    val sizeHex = Integer.toHexString(bytesRead) + "\r\n"
                    output.write(sizeHex.toByteArray())
                }
                output.write(buf, 0, bytesRead)
                if (contentLength <= 0) {
                    output.write("\r\n".toByteArray())
                }
                totalRead += bytesRead
                if (totalRead % 262144 < 65536) output.flush() // 每 ~256KB flush 一次
            }
            if (contentLength <= 0) {
                output.write("0\r\n\r\n".toByteArray()) // chunked 结束标记
            }
            output.flush()
            inputStream.close()
            workingConn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "proxyUrl failed: ${e.message}")
            try { sendEmpty(output, 302) } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════
    //  Beatmap 磁盘缓存
    // ═══════════════════════════════════════════════

    private fun serveBeatCache(output: OutputStream, cacheKey: String) {
        try {
            val safeName = cacheKey.replace(Regex("[^A-Za-z0-9_\\-.:]"), "").take(128)
            if (safeName.isBlank()) {
                sendJson(output, 404, JSONObject().apply { put("hit", false); put("enabled", true) })
                return
            }
            val cacheDir = File(context.filesDir, "beat_cache")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$safeName.json")
            if (!file.exists() || !file.isFile) {
                sendJson(output, 200, JSONObject().apply { put("hit", false); put("enabled", true) })
                return
            }
            val content = file.readText()
            val json = JSONObject(content)
            json.put("hit", true)
            json.put("enabled", true)
            sendJson(output, 200, json)
        } catch (e: Exception) {
            Log.w(TAG, "serveBeatCache read error: ${e.message}")
            sendJson(output, 200, JSONObject().apply { put("hit", false); put("enabled", true) })
        }
    }

    private fun serveBeatCacheWrite(output: OutputStream, body: String) {
        try {
            val json = JSONObject(body)
            val cacheKey = json.optString("key", "")
            val safeName = cacheKey.replace(Regex("[^A-Za-z0-9_\\-.:]"), "").take(128)
            val mapVal = json.opt("map")
            if (safeName.isBlank() || mapVal == null || mapVal == JSONObject.NULL) {
                sendJson(output, 400, JSONObject().apply { put("ok", false); put("error", "Missing key or map") })
                return
            }
            val cacheDir = File(context.filesDir, "beat_cache")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$safeName.json")
            val writeJson = JSONObject(body).apply {
                put("savedAt", System.currentTimeMillis())
                put("enabled", true)
            }
            file.writeText(writeJson.toString())
            // 清理超过 60 条最旧记录
            val allFiles = cacheDir.listFiles()?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (allFiles.size > 60) {
                allFiles.drop(60).forEach { it.delete() }
            }
            sendJson(output, 200, JSONObject().apply { put("ok", true); put("enabled", true) })
        } catch (e: Exception) {
            Log.w(TAG, "serveBeatCache write error: ${e.message}")
            sendJson(output, 500, JSONObject().apply { put("ok", false); put("error", e.message ?: "unknown") })
        }
    }
}
