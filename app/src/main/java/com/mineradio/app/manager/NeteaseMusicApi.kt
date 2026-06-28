package com.mineradio.app.manager

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/**
 * 网易云音乐 API 客户端
 * 实现 weapi 加密协议，支持扫码登录、搜索、获取歌曲URL、歌词等
 */
object NeteaseMusicApi {

    private const val BASE_URL = "https://music.163.com"
    private const val API_BASE_URL = "https://interface.music.163.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"

    // RSA 公钥参数
    private const val RSA_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val RSA_PUBKEY = "010001"

    // Cookie 存储
    private var userCookie = ""
    private var cookieFile: java.io.File? = null
    private var qqCookie = ""
    private var qqCookieFile: java.io.File? = null
    private var kgCookie = ""
    private var kgCookieFile: java.io.File? = null
    private var kgMid = ""
    private var kgMidFile: java.io.File? = null
    private var qsCookie = ""
    private var qsCookieFile: java.io.File? = null
    private var loginInfoCache: JSONObject? = null

    private var anonymousTokenFetched = false

    fun init(context: Context) {
        cookieFile = java.io.File(context.filesDir, ".netease_cookie")
        qqCookieFile = java.io.File(context.filesDir, ".qq_cookie")
        kgCookieFile = java.io.File(context.filesDir, ".kg_cookie")
        qsCookieFile = java.io.File(context.filesDir, ".qs_cookie")
        kgMidFile = java.io.File(context.filesDir, ".kg_mid")
        try {
            if (cookieFile!!.exists()) {
                userCookie = cookieFile!!.readText().trim()
            }
        } catch (e: Exception) {
            userCookie = ""
        }
        try {
            if (qqCookieFile!!.exists()) {
                qqCookie = qqCookieFile!!.readText().trim()
            }
        } catch (e: Exception) {
            qqCookie = ""
        }
        try {
            if (kgCookieFile!!.exists()) {
                kgCookie = kgCookieFile!!.readText().trim()
                Log.d("NeteaseMusic", "KG cookie loaded from disk: ${kgCookieFile?.absolutePath} size=${kgCookie.length}")
            } else {
                Log.d("NeteaseMusic", "KG cookie file not found at ${kgCookieFile?.absolutePath}")
            }
        } catch (e: Exception) {
            kgCookie = ""
            Log.w("NeteaseMusic", "KG cookie load error: ${e.message}")
        }
        try {
            if (qsCookieFile!!.exists()) {
                qsCookie = qsCookieFile!!.readText().trim()
                Log.d("NeteaseMusic", "QS cookie loaded from disk: ${qsCookieFile?.absolutePath} size=${qsCookie.length}")
            } else {
                Log.d("NeteaseMusic", "QS cookie file not found at ${qsCookieFile?.absolutePath}")
            }
        } catch (e: Exception) {
            qsCookie = ""
            Log.w("NeteaseMusic", "QS cookie load error: ${e.message}")
        }
        try {
            if (kgMidFile!!.exists()) {
                kgMid = kgMidFile!!.readText().trim()
            }
        } catch (e: Exception) {
            kgMid = ""
        }
        // 后台获取匿名 token — 搜索/推荐/发现 API 必需 MUSIC_A cookie
        Thread {
            try {
                ensureAnonymousToken()
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * 确保匿名 token 已获取。
     * NeteaseCloudMusicApi NPM 的 cloudsearch / recommend_songs
     * 内部自动调用 register_anonymous 获取 MUSIC_A cookie，
     * Kotlin 版必须显式调用。
     */
    @Synchronized
    private fun ensureAnonymousToken() {
        if (anonymousTokenFetched && userCookie.contains("MUSIC_A")) return
        try {
            // 先确保基础 Cookie（防 WAF）
            if (userCookie.isBlank()) fetchInitialCookie()
            // register_anonymous → 获取 MUSIC_A token
            // 使用统一的 httpPost 入口（含 TLS、Cookie 管理、重试）
            val params = JSONObject()
            val enc = weapi(params.toString())
            val respBody = httpPost("/api/register/anonimous", enc)
            Log.d("NeteaseMusic", "registerAnonymous resp: ${respBody.take(200)}")
            anonymousTokenFetched = true
            Log.d("NeteaseMusic", "anonymousToken ready: ${userCookie.contains("MUSIC_A")}")
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "registerAnonymous failed (non-fatal): ${e.message}")
            anonymousTokenFetched = true // 不阻塞，空结果重试会兜底
        }
    }

    private fun saveCookie(cookie: String) {
        userCookie = cookie
        try {
            cookieFile?.writeText(cookie)
        } catch (e: Exception) { }
    }

    // ==================== 加密工具 ====================

    private fun randomString(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sb = StringBuilder()
        val rnd = SecureRandom()
        for (i in 0 until length) {
            sb.append(chars[rnd.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun aesEncrypt(text: String, key: String, ivStr: String = IV): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun rsaEncrypt(text: ByteArray): String {
        // 对标 NeteaseCloudMusicApi 的 forge.publicKey.encrypt(str, 'NONE')：
        // 1. 将输入字节当作正 BigInteger（big-endian）
        // 2. 计算 m^e mod n
        // 3. 输出 128 字节 hex
        val m = BigInteger(1, text)
        val e = BigInteger(RSA_PUBKEY, 16)
        val n = BigInteger(RSA_MODULUS, 16)
        val c = m.modPow(e, n)
        val encrypted = c.toByteArray()
        // BigInteger.toByteArray() 可能返回 129 字节（多一个 0x00 符号位），去头
        val result = if (encrypted.size > 128) {
            encrypted.copyOfRange(encrypted.size - 128, encrypted.size)
        } else if (encrypted.size < 128) {
            // 左侧补零到 128 字节
            val padded = ByteArray(128)
            System.arraycopy(encrypted, 0, padded, 128 - encrypted.size, encrypted.size)
            padded
        } else {
            encrypted
        }
        return bytesToHex(result)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return bytesToHex(md.digest(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * weapi 加密
     * 对标 NeteaseCloudMusicApi 的 weapi() 函数
     */
    private fun weapi(text: String): Map<String, String> {
        val secKey = randomString(16)
        val encText = aesEncrypt(aesEncrypt(text, NONCE), secKey)
        // 关键：secKey 需要先反转（对标 secretKey.split('').reverse().join('')）
        val encSecKey = rsaEncrypt(secKey.toByteArray(Charsets.UTF_8).reversedArray())
        return mapOf("params" to encText, "encSecKey" to encSecKey)
    }

    // ==================== HTTP 请求 ====================

    /**
     * 初始化：获取基础 Cookie（防止被 WAF 拦截）
     */
    private fun fetchInitialCookie() {
        try {
            val url = URL("$BASE_URL/")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (conn is HttpsURLConnection) {
                val sslCtx = SSLContext.getInstance("TLSv1.2")
                sslCtx.init(null, null, SecureRandom())
                conn.sslSocketFactory = sslCtx.socketFactory
            }
            conn.responseCode // trigger connection
            val cookies = conn.headerFields["Set-Cookie"]
            if (cookies != null) {
                for (c in cookies) {
                    val parts = c.split(";").firstOrNull()?.trim() ?: continue
                    if (parts.isBlank()) continue
                    val key = parts.substringBefore("=")
                    val existing = userCookie.split(";").map { it.trim() }.filter { it.isNotBlank() }
                    val updated = existing.filter { it.substringBefore("=") != key }.toMutableList()
                    updated.add(parts)
                    userCookie = updated.joinToString("; ")
                }
                saveCookie(userCookie)
                Log.d("NeteaseMusic", "初始Cookie已获取: ${userCookie.take(100)}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "初始Cookie获取失败(可忽略): ${e.message}")
        }
    }

    // ---- 字节数组工具 ----
    private fun bytesToInt(b0: Byte, b1: Byte): Int = ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)

    /**
     * 直接从 HttpURLConnection 读原始字节 → 字符串
     * 手动检测 gzip 魔数 (0x1F 0x8B)，不再依赖 Content-Encoding 头
     */
    private fun readResponseBody(conn: HttpURLConnection, code: Int): String? {
        val rawStream: java.io.InputStream = try {
            if (code in 200..299) conn.inputStream
            else conn.errorStream ?: conn.inputStream
        } catch (e: java.io.IOException) {
            Log.e("NeteaseMusic", "无法获取InputStream: ${e.message}")
            return null
        }

        try {
            val rawBytes = rawStream.readBytes()
            Log.d("NeteaseMusic", "Raw bytes read: ${rawBytes.size}")

            if (rawBytes.isEmpty()) return ""

            // 如果是 gzip 压缩（前两个字节是 0x1F 0x8B），解压
            val decompressed = if (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
                Log.d("NeteaseMusic", "检测到 gzip 压缩，解压中...")
                try {
                    java.util.zip.GZIPInputStream(rawBytes.inputStream()).readBytes()
                } catch (e: Exception) {
                    Log.w("NeteaseMusic", "gzip解压失败: ${e.message}")
                    rawBytes
                }
            } else {
                rawBytes
            }

            return String(decompressed, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "读取响应体异常: ${e.message}")
            return null
        } finally {
            try { rawStream.close() } catch (_: Exception) {}
        }
    }

    private fun httpPost(urlPath: String, data: Map<String, String>, baseUrl: String = BASE_URL, extraCookies: Map<String, String>? = null): String {
        // 确保有基础 Cookie
        if (userCookie.isBlank()) {
            fetchInitialCookie()
        }

        var lastError: Exception? = null
        // 重试 2 次
        for (attempt in 1..2) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$urlPath")
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.doInput = true
                conn.useCaches = false
                // 关键：禁用自动重定向，POST 变 GET 会丢失 weapi 参数
                conn.instanceFollowRedirects = false
                // 关键：告诉服务器不要压缩，简化响应读取
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Connection", "close")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                conn.setRequestProperty("Referer", "https://music.163.com/")
                conn.setRequestProperty("Origin", "https://music.163.com")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                if (conn is HttpsURLConnection) {
                    try {
                        val sslCtx = SSLContext.getInstance("TLSv1.2")
                        sslCtx.init(null, null, SecureRandom())
                        conn.sslSocketFactory = sslCtx.socketFactory
                    } catch (e: Exception) {
                        Log.w("NeteaseMusic", "TLSv1.2 fail: ${e.message}")
                    }
                }
                // 构造 Cookie：userCookie + extraCookies
                val cookieBuilder = StringBuilder()
                if (userCookie.isNotBlank()) {
                    cookieBuilder.append(userCookie)
                }
                if (extraCookies != null) {
                    extraCookies.forEach { (k, v) ->
                        if (cookieBuilder.isNotEmpty()) cookieBuilder.append("; ")
                        cookieBuilder.append(k).append("=").append(v)
                    }
                }
                if (cookieBuilder.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookieBuilder.toString())
                }

                // 写 POST body
                val formBody = StringBuilder()
                data.forEach { (k, v) ->
                    if (formBody.isNotEmpty()) formBody.append("&")
                    formBody.append(URLEncoder.encode(k, "UTF-8"))
                    formBody.append("=")
                    formBody.append(URLEncoder.encode(v, "UTF-8"))
                }
                val bodyBytes = formBody.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bodyBytes.size)
                conn.outputStream.use { it.write(bodyBytes); it.flush() }

                // 读取响应
                val code = conn.responseCode
                val respHeaders = StringBuilder()
                conn.headerFields.forEach { (k, v) -> respHeaders.append("$k=${v?.firstOrNull()}, ") }
                Log.d("NeteaseMusic", "POST $urlPath → $code [${respHeaders}]")

                // 先提取 Cookie（任何响应码都可能有 Set-Cookie，比如 302 重定向）
                extractCookies(conn)

                val response = readResponseBody(conn, code)
                val bodyLen = response?.length ?: -1
                Log.d("NeteaseMusic", "body=$bodyLen bytes (attempt $attempt)")

                if (response != null && response.isNotBlank()) {
                    if (code in 200..299 || code in 300..399) {
                        // 2xx 和 3xx 都返回响应体，让调用者判断
                        return response
                    } else {
                        throw Exception("HTTP $code: ${response.take(200)}")
                    }
                }

                // 302/301 重定向但 body 为空 — 可能是登录成功后的重定向
                // Cookie 已经在上面提取了，调用者可以通过 Cookie 判断是否登录成功
                if (code in 300..399) {
                    Log.d("NeteaseMusic", "重定向响应 (HTTP $code)，body 为空，但 Cookie 已提取")
                    return ""
                }

                // body 为空：可能被 WAF，获取初始 Cookie 后重试
                Log.w("NeteaseMusic", "HTTP $code body 为空, len=$bodyLen")
                if (attempt == 1) {
                    fetchInitialCookie()
                    Thread.sleep(600)
                }
                lastError = RuntimeException("服务器返回空内容 (HTTP $code)")

            } catch (e: Exception) {
                Log.e("NeteaseMusic", "请求异常 attempt $attempt: ${e.message}")
                lastError = e
                if (attempt < 2) { Thread.sleep(600); continue }
                throw e
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        throw lastError ?: RuntimeException("请求失败")
    }

    private fun extractCookies(conn: HttpURLConnection) {
        try {
            val cookies = conn.headerFields["Set-Cookie"] ?: return
            for (c in cookies) {
                val parts = c.split(";").firstOrNull()?.trim() ?: continue
                if (parts.isBlank()) continue
                val key = parts.substringBefore("=")
                val existing = userCookie.split(";").map { it.trim() }.filter { it.isNotBlank() }
                val updated = existing.filter { it.substringBefore("=") != key }.toMutableList()
                updated.add(parts)
                userCookie = updated.joinToString("; ")
            }
            saveCookie(userCookie)
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Cookie 提取失败: ${e.message}")
        }
    }

    /**
     * 从 Cookie 字符串中提取指定字段的值
     */
    private fun extractCookieValue(cookie: String, name: String): String? {
        if (cookie.isBlank()) return null
        val pairs = cookie.split(";").map { it.trim() }.filter { it.isNotBlank() }
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                if (key == name) {
                    return pair.substring(idx + 1).trim()
                }
            }
        }
        return null
    }

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://music.163.com/")
            if (userCookie.isNotBlank()) {
                conn.setRequestProperty("Cookie", userCookie)
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            reader.readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun weapiPost(urlPath: String, params: JSONObject): JSONObject {
        val enc = weapi(params.toString())
        val resp = httpPost(urlPath, enc)
        if (resp.isBlank()) {
            throw Exception("服务器返回空响应 ($urlPath)")
        }
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "JSON解析失败 url=$urlPath body=${resp.take(300)}")
            throw Exception("API响应格式异常: ${e.message?.take(100)}")
        }
    }

    /**
     * API 版本接口调用（不加密，直接 form 格式）
     * 对标官方 NeteaseCloudMusicApi 的 crypto='api' 模式
     * 使用 interface.music.163.com 域名
     * 包含客户端设备信息 Cookie
     */
    private fun apiPost(urlPath: String, params: Map<String, String>): JSONObject {
        // 生成设备信息 Cookie（模拟 Android 客户端）
        val deviceCookies = buildApiDeviceCookies()
        val resp = httpPost(urlPath, params, API_BASE_URL, deviceCookies)
        if (resp.isBlank()) {
            throw Exception("服务器返回空响应 ($urlPath)")
        }
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "JSON解析失败 url=$urlPath body=${resp.take(300)}")
            throw Exception("API响应格式异常: ${e.message?.take(100)}")
        }
    }

    /**
     * 构建 api 版本需要的设备信息 Cookie
     * 对标官方 NeteaseCloudMusicApi 的 createHeaderCookie
     */
    private fun buildApiDeviceCookies(): Map<String, String> {
        val now = System.currentTimeMillis()
        val deviceId = randomString(32).lowercase()
        val requestId = "${now}_${(0..9999).random().toString().padStart(4, '0')}"
        
        return mapOf(
            "os" to "Android",
            "osver" to "12.0",
            "appver" to "8.8.50",
            "versioncode" to "140",
            "mobilename" to "Pixel 6",
            "buildver" to (now / 1000).toString(),
            "resolution" to "1080x1920",
            "__csrf" to "",
            "channel" to "netease",
            "requestId" to requestId,
            "deviceId" to deviceId
        )
    }

    // ==================== 登录相关 API ====================

    /**
     * 获取扫码登录的 key
     * 使用官方 api 版本接口（不加密）
     */
    fun getLoginQrKey(): String {
        val params = mapOf("type" to "3")
        Log.d("NeteaseMusic", "开始获取二维码Key...")
        val resp = apiPost("/api/login/qrcode/unikey", params)
        val code = resp.optInt("code", -1)
        // api 版本返回的 unikey 直接在根对象，不是在 data 里
        val key = resp.optString("unikey", "")
        if (key.isBlank()) {
            Log.e("NeteaseMusic", "获取二维码Key失败: code=$code, message=${resp.optString("message")}, resp=${resp.toString().take(200)}")
        } else {
            Log.i("NeteaseMusic", "获取二维码Key成功: ${key.take(8)}...")
        }
        return key
    }

    /**
     * 生成二维码图片 (base64)
     * 本地生成，对标官方 NeteaseCloudMusicApi 的实现
     */
    fun createLoginQrImage(key: String): String? {
        if (key.isBlank()) return null
        try {
            // 二维码 URL 格式（和官方库一致）
            val qrUrl = "https://music.163.com/login?codekey=$key"
            
            // 使用 ZXing 生成二维码
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            
            val size = 300
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(qrUrl, BarcodeFormat.QR_CODE, size, size, hints)
            
            // 转换成 Bitmap
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            
            // 转换成 base64
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            
            Log.i("NeteaseMusic", "二维码生成成功: ${base64.take(20)}...")
            return base64
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "二维码生成失败: ${e.message}")
            return null
        }
    }

    /**
     * 检查扫码状态（对标 Mineradio login_qr_check 流程）
     * 返回: 801=等待扫码, 802=已扫码待确认, 803=授权成功, 800=过期
     * 使用官方 api 版本接口（不加密）
     */
    fun checkLoginQrStatus(key: String): JSONObject {
        val params = mapOf("key" to key, "type" to "3")
        
        var code = -1
        var message = ""
        var nickname = ""
        
        try {
            val resp = apiPost("/api/login/qrcode/client/login", params)
            code = resp.optInt("code", -1)
            message = resp.optString("message", "")
            nickname = resp.optJSONObject("data")?.optString("nickname") ?: ""
            Log.d("NeteaseMusic", "checkLoginQrStatus 正常返回: code=$code, msg=$message")
        } catch (e: Exception) {
            // JSON 解析失败或响应为空 — 可能是 302 重定向（登录成功后的重定向）
            // 检查 Cookie 中是否已经有 MUSIC_U，如果有就认为登录成功
            Log.w("NeteaseMusic", "checkLoginQrStatus 异常: ${e.message}，检查 Cookie...")
            message = "api_error"
        }
        
        // 检查是否已经通过 Cookie 获取到登录态（可能是 302 重定向的情况）
        val hasCookie = userCookie.isNotBlank() && userCookie.contains("MUSIC_U")
        
        // 如果 code 不是 803，但 Cookie 中有 MUSIC_U，也认为登录成功
        if (code != 803 && hasCookie) {
            Log.i("NeteaseMusic", "通过 Cookie 检测到登录成功（可能是重定向场景）")
            code = 803
        }
        
        val result = JSONObject().apply {
            put("code", code)
            put("message", message)
            put("nickname", nickname)
        }
        
        // 803=授权成功
        if (code == 803) {
            if (hasCookie) {
                result.put("loggedIn", true)
                result.put("hasCookie", true)
                Log.i("NeteaseMusic", "扫码登录成功，已获取 Cookie")
            } else {
                // 第一次没拿到 cookie → 重试一次
                Log.w("NeteaseMusic", "checkLoginQrStatus code=803 但 Cookie 不完整，重试中...")
                try {
                    val retryResp = apiPost("/api/login/qrcode/client/login", params)
                    val retryCode = retryResp.optInt("code", -1)
                    val retryHasCookie = userCookie.isNotBlank() && userCookie.contains("MUSIC_U")
                    if (retryCode == 803 || retryHasCookie) {
                        result.put("loggedIn", true)
                        result.put("hasCookie", true)
                        Log.d("NeteaseMusic", "重试获取 cookie 成功")
                    }
                } catch (e: Exception) {
                    // 重试也失败了，但再检查一次 Cookie
                    val retryHasCookie = userCookie.isNotBlank() && userCookie.contains("MUSIC_U")
                    if (retryHasCookie) {
                        result.put("loggedIn", true)
                        result.put("hasCookie", true)
                        Log.d("NeteaseMusic", "重试后通过 Cookie 检测到登录成功")
                    } else {
                        Log.w("NeteaseMusic", "重试获取 cookie 失败: ${e.message}")
                    }
                }
            }
        }
        return result
    }

    /**
     * 检查登录状态
     */
    fun getLoginStatus(): JSONObject {
        if (userCookie.isBlank()) {
            return JSONObject().apply {
                put("loggedIn", false)
            }
        }
        return try {
            // 使用 login_status 检查
            val params = JSONObject()
            val enc = weapi(params.toString())
            val resp = httpPost("/weapi/w/nuser/account/get", enc)
            val body = JSONObject(resp)
            val profile = body.optJSONObject("profile") ?: JSONObject()
            val account = body.optJSONObject("account") ?: JSONObject()

            val loggedIn = profile.length() > 0 || account.length() > 0
            val result = JSONObject().apply {
                put("loggedIn", loggedIn)
                put("userId", account.optInt("id", 0))
                put("nickname", profile.optString("nickname", "网易云用户"))
                put("avatar", profile.optString("avatarUrl", ""))
                put("vipType", profile.optInt("vipType", 0))
            }

            if (loggedIn) {
                loginInfoCache = result
            }
            result
        } catch (e: Exception) {
            // 降级：尝试 cloudsearch 随便搜索验证
            try {
                val testParams = JSONObject().apply {
                    put("s", "test")
                    put("type", 1)
                    put("limit", 1)
                }
                weapiPost("/weapi/cloudsearch/get/web", testParams)
                loginInfoCache?.let {
                    return it.apply { put("loggedIn", true) }
                }
                return JSONObject().apply {
                    put("loggedIn", true)
                    put("nickname", "网易云用户")
                }
            } catch (e2: Exception) {
                return JSONObject().apply {
                    put("loggedIn", false)
                    put("error", e2.message ?: "登录态已失效")
                }
            }
        }
    }

    /**
     * 退出登录
     */
    fun logout(): Boolean {
        try {
            val params = JSONObject()
            weapiPost("/weapi/logout", params)
        } catch (_: Exception) { }
        userCookie = ""
        loginInfoCache = null
        saveCookie("")
        return true
    }

    // ==================== 搜索 API ====================

    /**
     * 搜索歌曲
     */
    fun searchSongs(keyword: String, limit: Int = 30, offset: Int = 0): JSONObject {
        ensureAnonymousToken() // 确保匿名 token 已获取

        val params = JSONObject().apply {
            put("s", keyword)
            put("type", 1)
            put("limit", limit)
            put("offset", offset)
        }
        // 使用 cloudsearch endpoint (与 Mineradio 原版 Node.js server 一致)
        val resp = weapiPost("/weapi/cloudsearch/get/web", params)
        val result = resp.optJSONObject("result") ?: JSONObject()
        var songs = result.optJSONArray("songs") ?: JSONArray()
        var total = result.optInt("songCount", songs.length())

        // 空结果时重试一次（可能是 MUSIC_A 过期）
        if (songs.length() == 0 && !userCookie.contains("MUSIC_A")) {
            Log.d("NeteaseMusic", "search empty, retry after registerAnonymous")
            anonymousTokenFetched = false
            ensureAnonymousToken()
            val resp2 = weapiPost("/weapi/cloudsearch/get/web", params)
            val result2 = resp2.optJSONObject("result") ?: JSONObject()
            songs = result2.optJSONArray("songs") ?: JSONArray()
            total = result2.optInt("songCount", songs.length())
        }

        val list = JSONArray()
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val artists = song.optJSONArray("ar")
            val artistNames = mutableListOf<String>()
            val artistList = JSONArray()
            var firstArtistId = 0
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    val ar = artists.optJSONObject(j) ?: continue
                    val aid = ar.optInt("id", 0)
                    if (j == 0) firstArtistId = aid
                    artistNames.add(ar.optString("name", ""))
                    artistList.put(JSONObject().apply {
                        put("id", aid)
                        put("name", ar.optString("name", ""))
                    })
                }
            }
            val album = song.optJSONObject("al") ?: JSONObject()
            list.put(JSONObject().apply {
                put("type", "song")
                put("id", song.optInt("id", 0))
                put("name", song.optString("name", ""))
                put("artist", artistNames.joinToString(" / "))
                put("artists", artistList)
                put("artistId", firstArtistId)
                put("album", album.optString("name", ""))
                put("albumId", album.optInt("id", 0))
                put("cover", album.optString("picUrl", ""))
                put("duration", song.optLong("dt", 0))
                put("fee", song.optInt("fee", 0))
                put("provider", "netease")
                put("source", "netease")
            })
        }
        return JSONObject().apply {
            put("songs", list)
            put("total", total)
            put("provider", "netease")
        }
    }

    // ==================== 歌曲URL API ====================

    /**
     * 获取歌曲播放URL
     * quality: standard, exhigh, lossless, hires, jymaster
     */
    fun getSongUrl(id: Long, quality: String = "exhigh", version: Int = 0): JSONObject {
        val levelMap = mapOf(
            "standard" to "standard",
            "exhigh" to "exhigh", 
            "lossless" to "lossless",
            "hires" to "hires",
            "jymaster" to "jymaster"
        )
        val qualityLabelMap = mapOf(
            "standard" to "标准",
            "exhigh" to "极高",
            "lossless" to "无损",
            "hires" to "Hi-Res",
            "jymaster" to "母带"
        )
        val brMap = mapOf(
            "standard" to 128000,
            "exhigh" to 320000,
            "lossless" to 990000,
            "hires" to 1999000,
            "jymaster" to 4608000
        )
        val level = levelMap[quality] ?: "exhigh"
        val isLoggedIn = userCookie.isNotBlank() && userCookie.contains("MUSIC_U")
        val vipInfo = loginInfoCache

        val params = JSONObject().apply {
            put("ids", "[$id]")
            put("level", level)
            put("encodeType", "flac")
        }
        val resp = weapiPost("/weapi/song/enhance/player/url/v1", params)
        val respData = resp.optJSONArray("data") ?: JSONArray()
        if (respData.length() > 0) {
            val songData = respData.optJSONObject(0) ?: JSONObject()
            val url = songData.optString("url", "")
            val type = songData.optString("type", "")
            val freeTrial = songData.optJSONObject("freeTrialInfo")
            val actualBr = songData.optInt("br", brMap[quality] ?: 320000)

            if (url.isNotBlank()) {
                return JSONObject().apply {
                    put("provider", "netease")
                    put("url", url)
                    put("playable", true)
                    put("trial", freeTrial != null && freeTrial.length() > 0)
                    put("level", level)
                    put("quality", qualityLabelMap[quality] ?: "极高")
                    put("br", actualBr)
                    put("requestedQuality", quality)
                    put("type", type)
                    put("id", id)
                    put("version", version)
                    // 登录信息（前端 trial UI 需要）
                    put("loggedIn", isLoggedIn)
                    put("vipType", vipInfo?.optInt("vipType", 0) ?: 0)
                    put("vipLevel", if (isLoggedIn) (vipInfo?.optString("vipLevel", "none") ?: "none") else "none")
                    put("isVip", (vipInfo?.optInt("vipType", 0) ?: 0) > 0)
                    put("isSvip", (vipInfo?.optInt("vipType", 0) ?: 0) >= 11)
                    put("vipLabel", when {
                        !isLoggedIn -> "未登录"
                        (vipInfo?.optInt("vipType", 0) ?: 0) >= 11 -> "SVIP"
                        (vipInfo?.optInt("vipType", 0) ?: 0) > 0 -> "VIP"
                        else -> "无VIP"
                    })
                }
            }

            val fee = songData.optInt("fee", 0)
            val code = songData.optInt("code", 0)
            return JSONObject().apply {
                put("provider", "netease")
                put("url", "")
                put("playable", false)
                put("trial", false)
                put("level", "")
                put("quality", "")
                put("fee", fee)
                put("code", code)
                put("id", id)
                put("version", version)
                put("requestedQuality", quality)
                put("loggedIn", isLoggedIn)
                put("vipType", vipInfo?.optInt("vipType", 0) ?: 0)
                put("vipLevel", if (isLoggedIn) (vipInfo?.optString("vipLevel", "none") ?: "none") else "none")
                put("isVip", (vipInfo?.optInt("vipType", 0) ?: 0) > 0)
                put("isSvip", (vipInfo?.optInt("vipType", 0) ?: 0) >= 11)
                put("vipLabel", if (!isLoggedIn) "未登录" else if ((vipInfo?.optInt("vipType", 0) ?: 0) >= 11) "SVIP" else "无VIP")
                put("message", when {
                    fee == 1 && !isLoggedIn -> "需要登录才能播放"
                    fee > 1 -> "此歌曲需要 VIP 才能播放"
                    code == -110 -> "暂无版权"
                    code == -114 -> "该地区不可用"
                    else -> "获取播放地址失败"
                })
            }
        }
        return JSONObject().apply {
            put("provider", "netease")
            put("url", "")
            put("playable", false)
            put("trial", false)
            put("level", "")
            put("quality", "")
            put("id", id)
            put("version", version)
            put("requestedQuality", quality)
            put("loggedIn", isLoggedIn)
            put("vipType", vipInfo?.optInt("vipType", 0) ?: 0)
            put("vipLevel", if (isLoggedIn) (vipInfo?.optString("vipLevel", "none") ?: "none") else "none")
            put("isVip", (vipInfo?.optInt("vipType", 0) ?: 0) > 0)
            put("isSvip", (vipInfo?.optInt("vipType", 0) ?: 0) >= 11)
            put("vipLabel", if (!isLoggedIn) "未登录" else if ((vipInfo?.optInt("vipType", 0) ?: 0) >= 11) "SVIP" else "无VIP")
            put("message", "获取播放地址失败")
        }
    }

    // ==================== 歌词 API ====================

    /**
     * 获取歌词
     */
    fun getLyric(id: Long): JSONObject {
        val params = JSONObject().apply {
            put("id", id.toString())
            put("lv", -1)
            put("tv", -1)
            put("yv", -1) // 逐字歌词版本
            put("cp", false)
        }
        val resp = weapiPost("/weapi/song/lyric?lv=-1&tv=-1&yv=-1", params)
        val lrc = resp.optJSONObject("lrc") ?: JSONObject()
        val tlyric = resp.optJSONObject("tlyric") ?: JSONObject()
        val yrc = resp.optJSONObject("yrc") ?: JSONObject() // 逐字歌词

        return JSONObject().apply {
            put("provider", "netease")
            put("lyric", lrc.optString("lyric", ""))
            put("translate", tlyric.optString("lyric", ""))
            put("yrc", yrc.optString("lyric", "")) // 逐字歌词原始字符串
        }
    }

    /**
     * 根据歌名和歌手搜索歌词（用于本地歌曲的歌词匹配）
     * 返回 LRC 格式歌词字符串，未找到返回 null
     */
    fun searchLyricByName(name: String, artist: String = ""): String? {
        return try {
            // 构建搜索关键词
            val keyword = if (artist.isNotBlank() && artist != "本地文件") {
                "$name $artist"
            } else {
                name
            }
            val searchResult = searchSongs(keyword, limit = 5)
            val songs = searchResult.optJSONArray("songs") ?: JSONArray()
            if (songs.length() == 0) return null

            // 取第一个结果的歌词
            val firstSong = songs.getJSONObject(0)
            val songId = firstSong.optLong("id", 0)
            if (songId <= 0) return null

            val lyricResult = getLyric(songId)
            val lyric = lyricResult.optString("lyric", "")
            if (lyric.isNotBlank()) lyric else null
        } catch (e: Exception) {
            Log.w("NeteaseMusicApi", "searchLyricByName failed: ${e.message}")
            null
        }
    }

    // ==================== 用户歌单 API ====================

    /**
     * 获取用户歌单
     */
    fun getUserPlaylists(uid: Long = 0): JSONObject {
        if (userCookie.isBlank()) {
            return JSONObject().apply {
                put("loggedIn", false)
                put("playlists", JSONArray())
            }
        }
        return try {
            val params = JSONObject().apply {
                put("uid", uid)
                put("limit", 60)
                put("offset", 0)
                put("includeVideo", true)
            }
            val resp = weapiPost("/weapi/user/playlist", params)
            val playlist = resp.optJSONArray("playlist") ?: JSONArray()
            val list = JSONArray()
            for (i in 0 until playlist.length()) {
                val pl = playlist.optJSONObject(i) ?: continue
                list.put(JSONObject().apply {
                    put("id", pl.optLong("id", 0))
                    put("name", pl.optString("name", ""))
                    put("cover", pl.optString("coverImgUrl", ""))
                    put("trackCount", pl.optInt("trackCount", 0))
                    put("playCount", pl.optInt("playCount", 0))
                    put("creator", pl.optJSONObject("creator")?.optString("nickname") ?: "")
                    put("provider", "netease")
                })
            }
            JSONObject().apply {
                put("loggedIn", true)
                put("playlists", list)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("loggedIn", true)
                put("playlists", JSONArray())
                put("error", e.message ?: "")
            }
        }
    }

    /**
     * 红心/取消红心歌曲
     */
    fun likeSong(trackId: Long, like: Boolean = true): JSONObject {
        if (userCookie.isBlank()) {
            return JSONObject().apply {
                put("code", 301)
                put("message", "未登录")
            }
        }
        return try {
            // 从 Cookie 中提取 __csrf
            val csrf = extractCookieValue(userCookie, "__csrf") ?: ""
            val params = JSONObject().apply {
                put("alg", "itembased")
                put("trackId", trackId)
                put("like", like)
                put("time", "3")
                put("csrf_token", csrf)
            }
            weapiPost("/weapi/radio/like", params)
        } catch (e: Exception) {
            Log.e("NeteaseMusicApi", "likeSong error: ${e.message}")
            JSONObject().apply {
                put("code", 500)
                put("error", e.message ?: "")
            }
        }
    }

    /**
     * 批量检查红心状态
     */
    fun likeCheck(ids: List<Long>): JSONObject {
        if (ids.isEmpty()) return JSONObject().apply {
            put("code", 200)
            put("loggedIn", true)
            put("liked", JSONObject())
            put("ids", JSONArray())
        }
        if (userCookie.isBlank()) return JSONObject().apply {
            put("code", 301); put("loggedIn", false); put("liked", JSONObject())
        }
        try {
            // 优先用 song_like_check
            val idJson = ids.joinToString(",")
            val csrf = extractCookieValue(userCookie, "__csrf") ?: ""
            val params = JSONObject().apply {
                put("ids", "[" + idJson + "]")
                put("csrf_token", csrf)
            }
            val resp = weapiPost("/weapi/song/like/check", params)
            val checkIds = resp.optJSONArray("checkIds") ?: JSONArray()
            val likedMap = JSONObject()
            val set = mutableSetOf<String>()
            for (i in 0 until checkIds.length()) set.add(checkIds.optString(i))
            ids.forEach { likedMap.put(it.toString(), set.contains(it.toString())) }
            return JSONObject().apply {
                put("code", resp.optInt("code", 200))
                put("loggedIn", true)
                put("liked", likedMap)
                put("ids", JSONArray(ids.map { it.toString() }))
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "like check direct failed: ${e.message}, trying likelist fallback")
            // 回退：拿用户的喜欢列表
            try {
                val uid = getLoginStatus().optInt("userId", 0)
                if (uid <= 0) return JSONObject().apply { put("code", 301); put("liked", JSONObject()) }
                val params = JSONObject().apply { put("uid", uid) }
                val resp = weapiPost("/weapi/song/likelist/get", params)
                val rawIds = resp.optJSONArray("ids") ?: JSONArray()
                val map = JSONObject()
                val set = mutableSetOf<String>()
                for (i in 0 until rawIds.length()) set.add(rawIds.optString(i))
                ids.forEach { map.put(it.toString(), set.contains(it.toString())) }
                return JSONObject().apply {
                    put("code", 200); put("loggedIn", true); put("liked", map)
                }
            } catch (e2: Exception) {
                Log.e("NeteaseMusic", "like check fallback failed: ${e2.message}")
                return JSONObject().apply { put("code", 500); put("liked", JSONObject()); put("error", e2.message) }
            }
        }
    }

    /**
     * 创建歌单
     */
    fun createPlaylist(name: String, privacy: String = "0"): JSONObject {
        if (userCookie.isBlank()) return JSONObject().apply {
            put("code", 301); put("success", false); put("message", "未登录")
        }
        val csrf = extractCookieValue(userCookie, "__csrf") ?: ""
        val params = JSONObject().apply {
            put("name", name)
            put("privacy", privacy)
            put("csrf_token", csrf)
        }
        return try {
            val resp = weapiPost("/weapi/playlist/create", params)
            val created = resp.optJSONObject("playlist") ?: resp.optJSONObject("data") ?: JSONObject()
            JSONObject().apply {
                put("code", resp.optInt("code", 200))
                put("success", resp.optInt("code", 200) == 200)
                put("playlist", created)
            }
        } catch (e: Exception) {
            JSONObject().apply { put("code", 500); put("success", false); put("error", e.message) }
        }
    }

    /**
     * 收藏/添加歌曲到指定歌单
     */
    fun addSongToPlaylist(pid: Long, trackIds: String): JSONObject {
        if (userCookie.isBlank()) return JSONObject().apply {
            put("code", 301); put("success", false); put("message", "未登录")
        }
        val csrf = extractCookieValue(userCookie, "__csrf") ?: ""
        val params = JSONObject().apply {
            put("op", "add")
            put("pid", pid)
            put("tracks", trackIds)
            put("csrf_token", csrf)
        }
        return try {
            val resp = weapiPost("/weapi/playlist/manipulate/tracks", params)
            JSONObject().apply {
                put("code", resp.optInt("code", 200))
                put("success", resp.optInt("code", 200) == 200)
                put("playlistId", pid)
            }
        } catch (e: Exception) {
            JSONObject().apply { put("code", 500); put("success", false); put("error", e.message) }
        }
    }
    /**
     * 获取歌单歌曲
     */
    fun getPlaylistTracks(playlistId: Long, limit: Int = 100000): JSONObject {
        val params = JSONObject().apply {
            put("id", playlistId)
            put("n", limit)
            put("s", 0)
        }
        val resp = weapiPost("/weapi/v6/playlist/detail", params)
        val playlist = resp.optJSONObject("playlist") ?: JSONObject()
        val tracks = playlist.optJSONArray("tracks") ?: JSONArray()
        
        val list = JSONArray()
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val artists = track.optJSONArray("ar")
            val artistNames = mutableListOf<String>()
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    artistNames.add(artists.optJSONObject(j)?.optString("name") ?: "")
                }
            }
            val album = track.optJSONObject("al") ?: JSONObject()
            list.put(JSONObject().apply {
                put("id", track.optLong("id", 0))
                put("name", track.optString("name", ""))
                put("artist", artistNames.joinToString(" / "))
                put("album", album.optString("name", ""))
                put("cover", album.optString("picUrl", ""))
                put("duration", track.optLong("dt", 0))
                put("fee", track.optInt("fee", 0))
                put("provider", "netease")
            })
        }
        return JSONObject().apply {
            put("tracks", list)
            put("total", tracks.length())
        }
    }

    // ==================== 推荐 API ====================

    /**
     * 每日推荐歌曲
     */
    fun getRecommendSongs(): JSONObject {
        ensureAnonymousToken()
        val params = JSONObject()
        val resp = weapiPost("/weapi/v1/discovery/recommend/songs", params)
        var recommend = resp.optJSONArray("recommend") ?: resp.optJSONArray("data") ?: JSONArray()

        // 空结果重试
        if (recommend.length() == 0 && !userCookie.contains("MUSIC_A")) {
            Log.d("NeteaseMusic", "recommend empty, retry after registerAnonymous")
            anonymousTokenFetched = false
            ensureAnonymousToken()
            val resp2 = weapiPost("/weapi/v1/discovery/recommend/songs", params)
            recommend = resp2.optJSONArray("recommend") ?: resp2.optJSONArray("data") ?: JSONArray()
        }

        val list = JSONArray()
        for (i in 0 until recommend.length()) {
            val song = recommend.optJSONObject(i) ?: continue
            val artists = song.optJSONArray("artists")
            val artistNames = mutableListOf<String>()
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    artistNames.add(artists.optJSONObject(j)?.optString("name") ?: "")
                }
            }
            list.put(JSONObject().apply {
                put("id", song.optLong("id", 0))
                put("name", song.optString("name", ""))
                put("artist", artistNames.joinToString(" / "))
                put("album", song.optJSONObject("album")?.optString("name") ?: "")
                put("cover", song.optJSONObject("album")?.optString("picUrl") ?: "")
                put("duration", song.optLong("duration", 0))
                put("fee", song.optInt("fee", 0))
                put("provider", "netease")
            })
        }
        return JSONObject().apply {
            put("songs", list)
            put("total", list.length())
        }
    }

    /**
     * 获取cookie（调试用）
     */
    fun getCookie(): String = userCookie

    /**
     * 设置cookie（手动导入）
     */
    fun setCookie(cookie: String) {
        userCookie = cookie
        saveCookie(cookie)
    }

    // ==================== QQ 音乐 API ====================

    private fun parseCookieString(cookieText: String): MutableMap<String, String> {
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

    fun normalizeQQUin(raw: String?): String {
        return (raw ?: "").replace(Regex("\\D"), "").trimStart('0')
    }

    internal fun qqCookieUin(obj: Map<String, String>): String {
        val loginType = obj["login_type"]?.toIntOrNull() ?: 0
        val raw = if (loginType == 2) {
            obj["wxuin"] ?: obj["uin"] ?: obj["p_uin"] ?: ""
        } else {
            obj["uin"] ?: obj["qqmusic_uin"] ?: obj["wxuin"] ?: obj["p_uin"] ?: ""
        }
        return normalizeQQUin(raw)
    }

    internal fun qqCookieMusicKey(obj: Map<String, String>): String {
        return obj["qm_keyst"] ?: obj["qqmusic_key"] ?: obj["music_key"] ?: obj["p_skey"]
        ?: obj["skey"] ?: obj["psrf_qqaccess_token"] ?: obj["psrf_qqrefresh_token"]
        ?: obj["wxrefresh_token"] ?: obj["wxskey"] ?: ""
    }

    private fun qqCookieNickname(obj: Map<String, String>, uin: String?): String {
        val raw = qqCookieNicknameRaw(obj, uin)
        return if (raw.isNotBlank()) try {
            java.net.URLDecoder.decode(raw.replace("+", "%20"), "UTF-8")
        } catch (e: Exception) { raw } else ""
    }

    private fun qqCookieNicknameRaw(obj: Map<String, String>, uin: String?): String {
        val normalizedUin = uin ?: qqCookieUin(obj)
        val padded = if (normalizedUin.isNotEmpty()) "0$normalizedUin" else ""
        val keys = mutableListOf<String>()
        if (normalizedUin.isNotEmpty()) keys.add("ptnick_$normalizedUin")
        if (padded.isNotEmpty()) keys.add("ptnick_$padded")
        keys.addAll(listOf("ptnick", "nick", "nickname", "qq_nickname"))
        for (k in keys) {
            val v = obj[k]
            if (!v.isNullOrBlank()) return v
        }
        // 正则匹配 ptnick_ 开头的 key
        val ptnickKey = obj.keys.find { it.startsWith("ptnick_") && obj[it]?.isNotBlank() == true }
        return ptnickKey?.let { obj[it] ?: "" } ?: ""
    }

    private fun qqCookieAvatar(obj: Map<String, String>, uin: String?): String {
        // 第一优先: qqmusic_avatar, avatar, avatarUrl, headpic 的直接值
        val direct = obj["qqmusic_avatar"] ?: obj["avatar"] ?: obj["avatarUrl"] ?: obj["headpic"] ?: ""
        if (direct.isNotBlank()) return try { java.net.URLDecoder.decode(direct.replace("+", "%20"), "UTF-8") } catch (e: Exception) { direct }
        // 回退: qlogo.cn
        val normalizedUin = uin ?: qqCookieUin(obj)
        return if (normalizedUin.isNotEmpty()) "https://q1.qlogo.cn/g?b=qq&nk=${java.net.URLEncoder.encode(normalizedUin, "UTF-8")}&s=100" else ""
    }

    fun saveQQCookie(cookie: String) {
        // 归一化：wxuin→uin, 去除前导0
        qqCookie = normalizeQQCookieInput(cookie)
        Log.d("NeteaseMusic", "QQ cookie saved, uin=${qqCookieUin(parseCookieString(qqCookie))}")
        try { qqCookieFile?.writeText(qqCookie) } catch (_: Exception) {}
    }

    private fun normalizeQQCookieInput(cookieText: String): String {
        val obj = parseCookieString(cookieText)
        // login_type==2 且 wxuin存在但uin不存在 → 复制 wxuin 到 uin
        if (obj["login_type"]?.toIntOrNull() == 2 && obj["wxuin"]?.isNotEmpty() == true && obj["uin"].isNullOrEmpty()) {
            obj["uin"] = obj["wxuin"] ?: ""
        }
        if (obj["uin"].isNullOrEmpty() && (obj["qqmusic_uin"]?.isNotEmpty() == true || obj["p_uin"]?.isNotEmpty() == true)) {
            obj["uin"] = obj["qqmusic_uin"] ?: obj["p_uin"] ?: ""
        }
        if (obj["uin"]?.isNotEmpty() == true) {
            obj["uin"] = normalizeQQUin(obj["uin"])
        }
        // 重新序列化
        return obj.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    fun getQQCookie(): String = qqCookie

    fun getQQLoginInfo(): JSONObject {
        if (qqCookie.isBlank()) return JSONObject().apply {
            put("provider", "qq"); put("loggedIn", false); put("hasCookie", false)
        }
        val cookieObj = parseCookieString(qqCookie)
        val uin = qqCookieUin(cookieObj)
        val musicKey = qqCookieMusicKey(cookieObj)
        if (uin.isEmpty() || musicKey.isEmpty()) return JSONObject().apply {
            put("provider", "qq"); put("loggedIn", false); put("hasCookie", true)
        }

        val fallback = JSONObject().apply {
            put("provider", "qq")
            put("loggedIn", true)
            put("userId", uin)
            put("nickname", qqCookieNickname(cookieObj, uin).ifEmpty { "QQ $uin" })
            put("avatar", qqCookieAvatar(cookieObj, uin))
            put("vipType", 0)
            put("hasCookie", true)
            put("playbackKeyReady", true)
        }

        // 尝试获取 QQ 音乐用户资料
        try {
            val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
                "?cid=205360838&userid=$uin&reqfrom=1&g_tk=5381&loginUin=$uin" +
                "&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0"
            val resp = httpGetQQ(url)
            val body = JSONObject(resp)
            val data = body.optJSONObject("data") ?: JSONObject()
            val creator = data.optJSONObject("creator") ?: data
            val profileNick = creator.optString("nick", "").ifEmpty {
                creator.optString("nickname", "").ifEmpty { creator.optString("name", "") }
            }
            val profileAvatar = creator.optString("headpic", "").ifEmpty {
                creator.optString("avatarUrl", "").ifEmpty { creator.optString("avatar", "") }
            }
            val nick = profileNick.ifEmpty { qqCookieNickname(cookieObj, uin) }.ifEmpty { "QQ $uin" }
            return JSONObject().apply {
                put("provider", "qq")
                put("loggedIn", true)
                put("userId", uin)
                put("nickname", nick)
                put("avatar", profileAvatar.ifEmpty { qqCookieAvatar(cookieObj, uin) })
                put("vipType", 0)
                put("hasCookie", true)
                put("playbackKeyReady", true)
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ profile fetch failed: ${e.message}")
            return fallback
        }
    }

    fun getQQUserPlaylists(): JSONObject {
        val info = getQQLoginInfo()
        if (!info.optBoolean("loggedIn") || !info.has("userId")) {
            return JSONObject().apply {
                put("loggedIn", false); put("provider", "qq"); put("playlists", JSONArray())
            }
        }
        val uin = info.optString("userId")

        val allPlaylists = mutableListOf<JSONObject>()

        // 创建的歌单
        try {
            val createdUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss" +
                "?hostUin=0&hostuin=$uin&sin=0&size=200&g_tk=5381&loginUin=$uin" +
                "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            val resp = httpGetQQ(createdUrl)
            val body = JSONObject(resp)
            val data = body.optJSONObject("data") ?: JSONObject()
            val disslist = data.optJSONArray("disslist") ?: JSONArray()
            for (i in 0 until disslist.length()) {
                val pl = disslist.optJSONObject(i) ?: continue
                val id = pl.optString("dissid", pl.optString("tid", ""))
                if (id.isEmpty()) continue
                val name = pl.optString("diss_name", pl.optString("name", pl.optString("title", "")))
                if (name.isEmpty()) continue
                allPlaylists.add(JSONObject().apply {
                    put("provider", "qq"); put("source", "qq")
                    put("id", id)
                    put("name", name)
                    put("cover", pl.optString("diss_cover", pl.optString("logo", pl.optString("picurl", ""))))
                    put("trackCount", pl.optInt("song_cnt", pl.optInt("songnum", pl.optInt("total_song_num", 0))))
                    put("playCount", pl.optInt("listen_num", pl.optInt("visitnum", 0)))
                    put("creator", pl.optString("hostname", pl.optString("nick", "QQ 音乐")))
                    put("subscribed", false)
                })
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ created playlists failed: ${e.message}")
        }

        // 收藏的歌单
        try {
            val collectUrl = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg" +
                "?ct=20&cid=205360956&userid=$uin&reqtype=3&sin=0&ein=80"
            val resp = httpGetQQ(collectUrl)
            val body = JSONObject(resp)
            val data = body.optJSONObject("data") ?: JSONObject()
            val cdlist = data.optJSONArray("cdlist") ?: JSONArray()
            val seen = allPlaylists.map { it.optString("id") }.toSet()
            for (i in 0 until cdlist.length()) {
                val pl = cdlist.optJSONObject(i) ?: continue
                val id = pl.optString("dissid", pl.optString("tid", ""))
                if (id.isEmpty() || id in seen || (pl.optString("diss_name", "") + pl.optString("name", "")).contains(Regex("空间|qzone|背景音乐", RegexOption.IGNORE_CASE))) continue
                val name = pl.optString("diss_name", pl.optString("name", pl.optString("title", "")))
                if (name.isEmpty()) continue
                allPlaylists.add(JSONObject().apply {
                    put("provider", "qq"); put("source", "qq")
                    put("id", id)
                    put("name", name)
                    put("cover", pl.optString("diss_cover", pl.optString("logo", pl.optString("picurl", ""))))
                    put("trackCount", pl.optInt("song_cnt", pl.optInt("songnum", pl.optInt("total_song_num", 0))))
                    put("playCount", pl.optInt("listen_num", pl.optInt("visitnum", 0)))
                    put("creator", pl.optString("hostname", pl.optString("nick", "QQ 音乐")))
                    put("subscribed", true)
                })
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ collect playlists failed: ${e.message}")
        }

        // 我喜欢排在前面
        allPlaylists.sortByDescending { pl ->
            if (pl.optString("name").contains(Regex("我喜欢|我的喜欢|喜欢的音乐"))) 2
            else if (pl.optBoolean("subscribed")) 1
            else 0
        }

        return JSONObject().apply {
            put("loggedIn", true)
            put("provider", "qq")
            put("userId", uin)
            put("playlists", JSONArray(allPlaylists))
        }
    }

    fun getQQPlaylistTracks(id: String): JSONObject {
        val info = getQQLoginInfo()
        if (!info.optBoolean("loggedIn") || !info.has("userId")) {
            return JSONObject().apply {
                put("loggedIn", false); put("provider", "qq"); put("tracks", JSONArray())
            }
        }
        val uin = info.optString("userId")
        try {
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                "?type=1&utf8=1&disstid=$id&loginUin=$uin&format=json&inCharset=utf8" +
                "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0" +
                "&num=100000"
            val resp = httpGetQQ(url, "https://y.qq.com/n/yqq/playlist")
            val body = JSONObject(resp)
            val cdlist = body.optJSONArray("cdlist") ?: JSONArray()
            val detail = if (cdlist.length() > 0) cdlist.optJSONObject(0) ?: JSONObject() else JSONObject()
            val songlist = detail.optJSONArray("songlist") ?: JSONArray()
            val tracks = JSONArray()
            for (i in 0 until songlist.length()) {
                val raw = songlist.optJSONObject(i) ?: continue
                val mid = raw.optString("mid", raw.optString("songmid", ""))
                val name = raw.optString("name", raw.optString("songname", ""))
                if (name.isEmpty() || mid.isEmpty()) continue
                val singers = raw.optJSONArray("singer") ?: JSONArray()
                val artistParts = mutableListOf<String>()
                for (j in 0 until singers.length()) {
                    val s = singers.optJSONObject(j) ?: continue
                    artistParts.add(s.optString("name", s.optString("title", "")))
                }
                val album = raw.optJSONObject("album") ?: JSONObject()
                val albumMid = album.optString("mid", raw.optString("albummid", ""))
                tracks.put(JSONObject().apply {
                    put("provider", "qq"); put("source", "qq"); put("type", "qq")
                    put("id", mid)
                    put("qqId", raw.optString("id", raw.optString("songid", "")))
                    put("mid", mid); put("songmid", mid)
                    put("mediaMid", (raw.optJSONObject("file")?.optString("media_mid") ?: raw.optString("strMediaMid", "")))
                    put("name", name)
                    put("artist", artistParts.joinToString(" / "))
                    put("album", album.optString("name", album.optString("title", "")))
                    put("albumMid", albumMid)
                    put("cover", if (albumMid.isNotEmpty()) "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg?max_age=2592000" else "")
                    put("duration", raw.optInt("interval", raw.optInt("duration", 0)) * 1000L)
                    put("playable", true)
                })
            }
            return JSONObject().apply {
                put("loggedIn", true); put("provider", "qq")
                put("playlist", JSONObject().apply {
                    put("provider", "qq"); put("id", id)
                    put("name", detail.optString("dissname", detail.optString("diss_name", "")))
                    put("cover", detail.optString("logo", detail.optString("diss_cover", "")))
                    put("trackCount", tracks.length())
                })
                put("tracks", tracks)
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ playlist tracks failed: ${e.message}")
            return JSONObject().apply {
                put("loggedIn", true); put("provider", "qq"); put("tracks", JSONArray())
            }
        }
    }

    private fun httpGetQQ(url: String, referer: String = "https://y.qq.com/"): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", referer)
        if (qqCookie.isNotBlank()) {
            conn.setRequestProperty("Cookie", qqCookie)
        }
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun qqCookiePlaybackKey(obj: Map<String, String>): String {
        return obj["qm_keyst"] ?: obj["qqmusic_key"] ?: obj["music_key"] ?: obj["wxskey"] ?: ""
    }

    /**
     * QQ 音乐搜索
     */
    fun getQQSearch(keywords: String, limit: Int): JSONObject {
        if (keywords.isBlank()) return JSONObject().apply {
            put("provider", "qq"); put("songs", JSONArray())
        }
        try {
            // 1. 智能联想搜索
            val smartboxUrl = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg" +
                "?format=json&key=${URLEncoder.encode(keywords, "UTF-8")}&g_tk=5381" +
                "&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0&is_xml=0"
            val resp = httpGetQQ(smartboxUrl)
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: JSONObject()
            val song = data.optJSONObject("song") ?: JSONObject()
            val itemlist = song.optJSONArray("itemlist") ?: JSONArray()
            val clampedLimit = maxOf(1, minOf(limit, 10))
            val items = mutableListOf<JSONObject>()
            for (i in 0 until minOf(itemlist.length(), clampedLimit)) {
                val item = itemlist.optJSONObject(i) ?: continue
                val mid = item.optString("mid", item.optString("songmid", item.optString("id", "")))
                if (mid.isEmpty()) continue
                val name = item.optString("name", item.optString("title", ""))
                if (name.isEmpty()) continue
                items.add(JSONObject().apply {
                    put("provider", "qq"); put("source", "qq"); put("type", "qq")
                    put("id", mid)
                    put("qqId", item.optString("id", item.optString("docid", "")))
                    put("mid", mid); put("songmid", mid)
                    put("name", name)
                    put("artist", item.optString("singer", ""))
                    put("artists", if (item.optString("singer", "").isNotBlank())
                        JSONArray().apply { put(JSONObject().apply { put("name", item.optString("singer", "")) }) }
                        else JSONArray())
                    put("album", ""); put("cover", "")
                    put("duration", 0); put("fee", 0); put("playable", true)
                })
            }

            // 2. 逐个获取歌曲详情
            val songs = JSONArray()
            for (item in items) {
                val mid = item.optString("mid")
                try {
                    val detailPayload = JSONObject().apply {
                        put("comm", JSONObject().apply { put("ct", "24"); put("cv", 0) })
                        put("songinfo", JSONObject().apply {
                            put("module", "music.pf_song_detail_svr")
                            put("method", "get_song_detail_yqq")
                            put("param", JSONObject().apply { put("song_mid", mid) })
                        })
                    }
                    val detailResp = httpPostQQ("https://u.y.qq.com/cgi-bin/musicu.fcg", detailPayload.toString())
                    val detailJson = JSONObject(detailResp)
                    val songinfo = detailJson.optJSONObject("songinfo") ?: JSONObject()
                    val detailData = songinfo.optJSONObject("data") ?: JSONObject()
                    val trackInfo = detailData.optJSONObject("track_info") ?: JSONObject()

                    val album = trackInfo.optJSONObject("album") ?: JSONObject()
                    val albumMid = album.optString("mid", album.optString("pmid", ""))
                    val singers = trackInfo.optJSONArray("singer") ?: JSONArray()
                    val artistParts = mutableListOf<String>()
                    val artistArr = JSONArray()
                    for (j in 0 until singers.length()) {
                        val s = singers.optJSONObject(j) ?: continue
                        val sName = s.optString("name", s.optString("title", ""))
                        artistParts.add(sName)
                        artistArr.put(JSONObject().apply {
                            put("id", s.optString("id", ""))
                            put("mid", s.optString("mid", ""))
                            put("name", sName)
                        })
                    }

                    songs.put(JSONObject().apply {
                        put("provider", "qq"); put("source", "qq"); put("type", "qq")
                        put("id", mid)
                        put("qqId", trackInfo.optString("id", item.optString("qqId", "")))
                        put("mid", mid); put("songmid", mid)
                        put("mediaMid", (trackInfo.optJSONObject("file")?.optString("media_mid") ?: ""))
                        put("name", trackInfo.optString("name", trackInfo.optString("title", item.optString("name", ""))))
                        put("artist", if (artistParts.isNotEmpty()) artistParts.joinToString(" / ") else item.optString("artist", ""))
                        put("artists", if (artistArr.length() > 0) artistArr else item.optJSONArray("artists") ?: JSONArray())
                        put("album", album.optString("name", album.optString("title", "")))
                        put("albumMid", albumMid)
                        put("cover", if (albumMid.isNotEmpty()) "https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg?max_age=2592000" else "")
                        put("duration", (trackInfo.optInt("interval", 0)) * 1000L)
                        put("fee", if (trackInfo.optJSONObject("pay")?.optInt("pay_play", 0) == 1) 1 else 0)
                        put("playable", true)
                    })
                } catch (e: Exception) {
                    // detail failed, use fallback from smartbox
                    item.put("playable", true)
                    songs.put(item)
                }
            }

            return JSONObject().apply {
                put("provider", "qq"); put("songs", songs)
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ search failed: ${e.message}")
            return JSONObject().apply {
                put("provider", "qq"); put("songs", JSONArray())
                put("error", e.message ?: "搜索失败")
            }
        }
    }

    /**
     * QQ 音乐获取歌曲播放 URL
     * 调用 u.y.qq.com CgiGetVkey 获取真实音频流地址
     */
    fun getQQSongUrl(mid: String, mediaMid: String, qualityPreference: String): JSONObject {
        if (mid.isBlank()) return JSONObject().apply {
            put("provider", "qq"); put("url", ""); put("playable", false)
            put("error", "MISSING_MID"); put("message", "Missing QQ song mid")
        }

        val cookieObj = parseCookieString(qqCookie)
        val uin = qqCookieUin(cookieObj).ifEmpty { "0" }
        val musicKey = qqCookieMusicKey(cookieObj)
        val playbackKey = qqCookiePlaybackKey(cookieObj)

        // 音质候选
        val qualityTemplates = listOf(
            Triple("RS01", ".flac", "hires"),
            Triple("F000", ".flac", "lossless"),
            Triple("M800", ".mp3", "exhigh"),
            Triple("M500", ".mp3", "standard"),
            Triple("C400", ".m4a", "aac")
        )

        // 按用户指定音质排序
        val normalizedQuality = normalizeQQQuality(qualityPreference)
        val startIdx = qualityTemplates.indexOfFirst { it.third == normalizedQuality }
        val candidates = if (startIdx >= 0) qualityTemplates.drop(startIdx) else qualityTemplates

        val mediaIds = mutableListOf<String>()
        if (mediaMid.isNotBlank()) mediaIds.add(mediaMid)
        if (mid !in mediaIds) mediaIds.add(mid)

        val filenames = mutableListOf<String>()
        for (mediaId in mediaIds) {
            for ((prefix, ext, _) in candidates) {
                filenames.add("$prefix$mediaId$ext")
            }
        }

        val guid = (10000000 + (Math.random() * 90000000).toInt()).toString()

        val param = JSONObject().apply {
            put("guid", guid)
            put("songmid", JSONArray(filenames.map { mid }))
            put("songtype", JSONArray(filenames.map { 0 }))
            put("uin", uin)
            put("loginflag", 1)
            put("platform", "20")
            put("filename", JSONArray(filenames))
        }

        val comm = JSONObject().apply {
            put("uin", uin)
            put("format", "json")
            put("ct", if (musicKey.isNotEmpty()) "19" else "24")
            put("cv", 0)
            if (musicKey.isNotEmpty()) put("authst", musicKey)
        }

        val payload = JSONObject().apply {
            put("comm", comm)
            put("req_0", JSONObject().apply {
                put("module", "vkey.GetVkeyServer")
                put("method", "CgiGetVkey")
                put("param", param)
            })
        }

        try {
            val resp = httpPostQQ("https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString())
            val json = JSONObject(resp)
            val req0 = json.optJSONObject("req_0") ?: JSONObject()
            val data = req0.optJSONObject("data") ?: JSONObject()
            val midurlinfo = data.optJSONArray("midurlinfo") ?: JSONArray()
            val sip = data.optJSONArray("sip")
            val sipUrl = sip?.optString(0) ?: "https://ws.stream.qqmusic.qq.com/"

            // 找第一个有效的 purl
            var purl = ""
            for (i in 0 until midurlinfo.length()) {
                val info = midurlinfo.optJSONObject(i) ?: continue
                purl = info.optString("purl", "")
                if (purl.isNotBlank()) break
            }

            if (purl.isNotBlank()) {
                return JSONObject().apply {
                    put("provider", "qq")
                    put("url", sipUrl + purl)
                    put("trial", false)
                    put("playable", true)
                    put("quality", normalizedQuality)
                }
            }

            // 无可用 URL
            val hasSession = uin.isNotEmpty() && musicKey.isNotEmpty()
            return JSONObject().apply {
                put("provider", "qq"); put("url", ""); put("playable", false)
                put("error", "QQ_URL_UNAVAILABLE")
                put("loggedIn", hasSession)
                put("playbackKeyReady", uin.isNotEmpty() && playbackKey.isNotEmpty())
                put("message", if (!hasSession) "QQ 音乐需要登录或授权后才能获取播放地址"
                    else "QQ 音乐没有返回播放地址，可能受版权或会员限制")
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QQ song url failed: ${e.message}")
            return JSONObject().apply {
                put("provider", "qq"); put("url", ""); put("playable", false)
                put("error", "QQ_URL_UNAVAILABLE"); put("message", e.message ?: "网络错误")
            }
        }
    }

    private fun normalizeQQQuality(value: String): String {
        return when (value.lowercase().trim()) {
            "jymaster", "master", "studio", "svip" -> "jymaster"
            "hires", "hi-res", "highres", "zhenyin", "spatial" -> "hires"
            "lossless", "flac", "sq" -> "lossless"
            "exhigh", "high", "320", "320k", "hq" -> "exhigh"
            "standard", "normal", "128", "128k", "std" -> "standard"
            else -> "hires"
        }
    }

    private fun httpPostQQ(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", "https://y.qq.com/")
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        if (qqCookie.isNotBlank()) {
            conn.setRequestProperty("Cookie", qqCookie)
        }
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        try {
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * QQ 音乐添加歌曲到歌单
     * QQ 音乐暂时没有公开 API，返回友好提示
     */
    fun qqAddSongToPlaylist(pid: String, mid: String): JSONObject {
        return JSONObject().apply {
            put("provider", "qq")
            put("code", 0)
            put("success", true)
            put("message", "QQ 音乐收藏功能当前通过本地记录支持，云端同步待开放")
            put("playlistId", pid)
        }
    }

    // ==================== QQ 音乐歌词 ====================

    /**
     * 获取 QQ 音乐歌词
     * 参考 server.js handleQQLyric 实现
     * 双路径: 主路径 musicu.fcg PlayLyricInfo, 回退 fcg_query_lyric_new
     */
    fun getQQLyric(mid: String, id: String): JSONObject {
        val songMid = mid.trim()
        val songId = id.trim().replace(Regex("\\D"), "").toLongOrNull() ?: 0
        if (songMid.isEmpty() && songId <= 0) {
            return JSONObject().apply {
                put("provider", "qq"); put("lyric", "")
                put("error", "Missing QQ song mid or id")
            }
        }

        var lyricText = ""
        var transText = ""
        var qrcText = ""
        var romaText = ""
        var source = "qq-musicu"

        // ── 路径 1: music.musichallSong.PlayLyricInfo ──
        try {
            val param = JSONObject()
            if (songMid.isNotEmpty()) param.put("songMID", songMid)
            if (songId > 0) param.put("songID", songId)
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", 24)
                    put("cv", 0)
                })
                put("lyric", JSONObject().apply {
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("method", "GetPlayLyricInfo")
                    put("param", param)
                })
            }
            val resp = httpPostQQ("https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString())
            val json = JSONObject(resp)
            val data = json.optJSONObject("lyric")?.optJSONObject("data")
            lyricText = decodeQQLyricText(data?.optString("lyric", ""))
            transText = decodeQQLyricText(data?.optString("trans", ""))
            qrcText = decodeQQLyricText(data?.optString("qrc", ""))
            romaText = decodeQQLyricText(data?.optString("roma", ""))
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "[QQLyric] musicu failed: ${e.message}")
        }

        // ── 路径 2: 回退旧版 API ──
        if (lyricText.isEmpty() && songMid.isNotEmpty()) {
            try {
                val legacyUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${java.net.URLEncoder.encode(songMid, "UTF-8")}" +
                    "&songtype=0&format=json&nobase64=1&g_tk=5381" +
                    "&loginUin=${qqCookieUin(parseCookieString(qqCookie)).ifEmpty { "0" }}" +
                    "&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0" +
                    "&platform=yqq.json&needNewCode=0"
                val legacyResp = httpGetQQ(legacyUrl, "https://y.qq.com/portal/player.html")
                val body = JSONObject(legacyResp)
                lyricText = decodeQQLyricText(body.optString("lyric", ""))
                transText = decodeQQLyricText(body.optString("trans", body.optString("tlyric", "")))
                    .ifEmpty { transText }
                source = "qq-legacy"
            } catch (e: Exception) {
                Log.w("NeteaseMusic", "[QQLyric] legacy failed: ${e.message}")
            }
        }

        return JSONObject().apply {
            put("provider", "qq")
            put("id", songId)
            put("mid", songMid)
            put("lyric", lyricText)
            put("tlyric", transText)
            put("yrc", "")
            put("qrc", qrcText)
            put("roma", romaText)
            put("source", if (lyricText.isNotEmpty()) source else "qq-empty")
        }
    }

    /**
     * QQ 歌词文本解码
     * 处理 HTML 实体解码 + base64 解码（参考 server.js decodeQQLyricText）
     */
    private fun decodeQQLyricText(text: String?): String {
        var raw = decodeQQLyricHtmlEntities((text ?: "").trim())
        if (raw.isEmpty()) return ""
        val compact = raw.replace(Regex("\\s+"), "")
        val looksBase64 = compact.length >= 8 && compact.length % 4 == 0 &&
            compact.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
        if (looksBase64 && !raw.trimStart().startsWith("[")) {
            try {
                val decoded = Base64.decode(compact, Base64.DEFAULT).toString(Charsets.UTF_8)
                    .replace("\uFEFF", "")
                if (decoded.contains("[") || decoded.any { it in '\u4e00'..'\u9fa5' }) {
                    raw = decoded
                }
            } catch (e: Exception) {
                Log.w("NeteaseMusic", "[QQLyric] base64 decode failed: ${e.message}")
            }
        }
        return decodeQQLyricHtmlEntities(raw)
            .replace("\r\n", "\n").replace('\r', '\n').trim()
    }

    /**
     * HTML 实体解码（简化版，处理常见 QQ 歌词实体）
     */
    private fun decodeQQLyricHtmlEntities(text: String): String {
        return text
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
    }

    // ==================== 酷狗音乐 API ====================

    private fun ensureKGGuid(): String {
        if (kgMid.isBlank()) {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val sb = StringBuilder()
            val rnd = SecureRandom()
            for (i in 0 until 4) sb.append(chars[rnd.nextInt(chars.length)])
            kgMid = md5Text(sb.toString())
            try { kgMidFile?.writeText(kgMid) } catch (_: Exception) {}
        }
        return kgMid
    }

    private fun md5Text(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun saveKGCookie(cookie: String) {
        kgCookie = cookie.trim()
        Log.d("NeteaseMusic", "KG cookie saved, length=${kgCookie.length}")
        try {
            kgCookieFile?.writeText(kgCookie)
            Log.d("NeteaseMusic", "KG cookie file written OK: ${kgCookieFile?.absolutePath} size=${kgCookieFile?.length()}")
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "KG cookie file write FAILED: ${e.message}", e)
        }
    }

    fun getKGCookie(): String = kgCookie

    fun getKGLoginInfo(): JSONObject {
        if (kgCookie.isBlank()) return JSONObject().apply {
            put("provider", "kg"); put("loggedIn", false); put("hasCookie", false)
        }
        val cookieObj = parseCookieString(kgCookie)
        // ★ go-music-dl: userid + token 才是登录核心凭证
        val hasUserId = cookieObj.containsKey("userid") || cookieObj.containsKey("KugooID") || cookieObj.containsKey("KuGoo")
        val hasToken = cookieObj.containsKey("token") || cookieObj.containsKey("kg_mid")
        val isReallyLoggedIn = kgCookie.isNotBlank() && hasUserId && hasToken
        val userId = if (isReallyLoggedIn) {
            cookieObj["userid"] ?: cookieObj["KugooID"] ?: cookieObj["KuGoo"] ?: ""
        } else ""
        val nickname = try {
            val encoded = cookieObj["NickName"] ?: cookieObj["nickname"] ?: ""
            if (encoded.isEmpty()) "酷狗用户" else java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) { "酷狗用户" }
        val displayUserId = userId.take(16)
        Log.d("NeteaseMusic", "KG login info: hasUserId=$hasUserId hasToken=$hasToken isLoggedIn=$isReallyLoggedIn userId=$displayUserId cookieKeys=${cookieObj.keys.take(10)}")
        return JSONObject().apply {
            put("provider", "kg"); put("loggedIn", isReallyLoggedIn)
            put("hasCookie", true); put("userId", displayUserId.ifEmpty { "酷狗用户" })
            put("nickname", nickname.ifEmpty { "酷狗用户" })
            put("avatar", ""); put("vipType", 0)
        }
    }

    fun getKGSearch(keywords: String, limit: Int): JSONObject {
        if (keywords.isBlank()) return JSONObject().apply { put("provider", "kg"); put("songs", JSONArray()) }
        try {
            val enc = URLEncoder.encode(keywords, "UTF-8")
            val ts = System.currentTimeMillis()
            // ★ go-music-dl 方式: 完整参数确保返回 Image 等字段
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=$enc&platform=WebFilter&format=json&page=1&pagesize=$limit&userid=-1&clientver=&tag=em&filter=2&iscorrection=1&privilege_filter=0&_=$ts"
            // 先尝试带Cookie请求
            var respBody: String? = null
            try {
                val conn = URL(url).openConnection() as HttpsURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36")
                conn.setRequestProperty("Referer", "https://www.kugou.com/")
                if (kgCookie.isNotBlank()) conn.setRequestProperty("Cookie", kgCookie)
                conn.connectTimeout = 8000; conn.readTimeout = 12000
                respBody = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            } catch (_: Exception) {}
            // 如果带Cookie没结果，尝试不带Cookie
            if (respBody == null || respBody.isBlank() || !respBody.trimStart().startsWith("{")) {
                val conn = URL(url).openConnection() as HttpsURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36")
                conn.setRequestProperty("Referer", "https://www.kugou.com/")
                conn.connectTimeout = 8000; conn.readTimeout = 12000
                respBody = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            }
            val body = JSONObject(respBody ?: "{}")
            val infoList = body.optJSONObject("data")?.optJSONArray("lists") ?: JSONArray()
            Log.d("NeteaseMusic", "KG search: total=${infoList.length()} url_len=${url.length}")
            val songs = JSONArray()
            for (i in 0 until minOf(limit, infoList.length())) {
                val info = infoList.optJSONObject(i) ?: continue
                val hash = info.optString("FileHash", info.optString("hash", ""))
                val albumId = info.optString("AlbumID", info.optString("album_id", ""))
                val name = info.optString("SongName", info.optString("songname", ""))
                val singer = info.optString("SingerName", info.optString("singername", ""))
                // ★ go-music-dl 方式: Image 字段就是封面
                val imgRaw = info.optString("Image", "")
                val cover = if (imgRaw.isNotBlank()) imgRaw.replace("{size}", "240") else ""
                songs.put(JSONObject().apply {
                    put("provider", "kg"); put("source", "kg"); put("type", "kg")
                    put("id", hash); put("kgHash", hash); put("kgAlbumId", albumId)
                    put("name", name.ifEmpty { "未知歌曲" })
                    put("artist", singer.ifEmpty { "未知歌手" })
                    put("artists", if (singer.isNotBlank()) JSONArray().apply { put(JSONObject().apply { put("name", singer) }) } else JSONArray())
                    put("album", info.optString("AlbumName", info.optString("album_name", "")))
                    put("cover", cover)
                    put("duration", info.optLong("Duration", info.optLong("duration", 0)) * 1000L)
                    put("fee", 0); put("playable", true)
                })
            }
            return JSONObject().apply { put("provider", "kg"); put("songs", songs) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "KG search: ${e.message}")
            return JSONObject().apply { put("provider", "kg"); put("songs", JSONArray()); put("error", e.message ?: "酷狗搜索失败") }
        }
    }

    fun getKGSongUrl(hash: String, albumId: String, qualityPreference: String): JSONObject {
        if (hash.isBlank()) return JSONObject().apply {
            put("provider", "kg"); put("url", ""); put("playable", false)
            put("error", "MISSING_HASH"); put("message", "Missing KG song hash")
        }
        try {
            // ★ Listen1 方式: m.kugou.com/app/i/getSongInfo.php?cmd=playInfo
            val primaryUrl = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=${URLEncoder.encode(hash, "UTF-8")}"
            val conn = URL(primaryUrl).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://m.kugou.com/")
            if (kgCookie.isNotBlank()) conn.setRequestProperty("Cookie", kgCookie)
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            Log.d("NeteaseMusic", "KG song url (playInfo): status=${body.optInt("status", -1)} errcode=${body.optInt("errcode", -1)}")
            val playUrl = body.optString("url", "") 
                .ifBlank { body.optJSONObject("data")?.optString("play_url", "") ?: "" }
            val bitRate = body.optInt("bitRate", body.optInt("bitrate", 0))
            if (playUrl.isNotBlank()) {
                return JSONObject().apply { 
                    put("provider", "kg"); put("url", playUrl); put("trial", false); put("playable", true)
                    put("quality", if (bitRate > 0) "${bitRate}kbps" else "standard") 
                }
            }
            Log.w("NeteaseMusic", "KG song url: no playable URL from playInfo, trying legacy endpoint...")
            // Fallback: legacy play/getdata endpoint
            return getKGSongUrlLegacy(hash, albumId)
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "KG song url primary: ${e.message}, trying legacy...")
            return getKGSongUrlLegacy(hash, albumId)
        }
    }
    
    private fun getKGSongUrlLegacy(hash: String, albumId: String): JSONObject {
        try {
            val kgMid = ensureKGGuid()
            val userCookie = if (kgCookie.isNotBlank()) kgCookie else ""
            val midCookie = if (userCookie.contains("kg_mid=")) "" else "kg_mid=$kgMid"
            val cookieHeader = listOfNotNull(
                userCookie.takeIf { it.isNotBlank() },
                midCookie.takeIf { it.isNotBlank() }
            ).joinToString("; ")
            val url = "http://www.kugou.com/yy/index.php?r=play/getdata&hash=${URLEncoder.encode(hash, "UTF-8")}&album_id=$albumId&_=${System.currentTimeMillis()}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.kugou.com/")
            if (cookieHeader.isNotBlank()) conn.setRequestProperty("Cookie", cookieHeader)
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            val playUrl = (body.optJSONObject("data")?.optString("play_url", "") ?: "")
                .ifBlank { body.optString("play_url", "") }
            if (playUrl.isNotBlank()) {
                return JSONObject().apply { put("provider", "kg"); put("url", playUrl); put("trial", false); put("playable", true); put("quality", "standard") }
            }
            return JSONObject().apply { put("provider", "kg"); put("url", ""); put("playable", false); put("error", "KG_URL_UNAVAILABLE"); put("message", "酷狗未返回播放地址") }
        } catch (e: Exception) {
            return JSONObject().apply { put("provider", "kg"); put("url", ""); put("playable", false); put("error", "KG_URL_UNAVAILABLE"); put("message", e.message ?: "网络错误") }
        }
    }

    fun getKGLyric(hash: String, albumId: String): JSONObject {
        if (hash.isBlank()) return JSONObject().apply { put("provider", "kg"); put("lyric", ""); put("translate", "") }
        try {
            // ★ Listen1 方式: wwwapi.kugou.com + callback=jQuery + platid=4
            val url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&callback=jQuery&mid=1&hash=${URLEncoder.encode(hash, "UTF-8")}&platid=4&album_id=$albumId&_=${System.currentTimeMillis()}"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.kugou.com/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val rawResp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            // JSONP: "jQuery(...)" → strip wrapper
            val jsonStr = rawResp.substringAfter("(").substringBeforeLast(")")
            val lyric = JSONObject(jsonStr).optJSONObject("data")?.optString("lyrics", "") ?: ""
            return JSONObject().apply { put("provider", "kg"); put("lyric", lyric); put("translate", "") }
        } catch (e: Exception) {
            return JSONObject().apply { put("provider", "kg"); put("lyric", ""); put("translate", ""); put("error", e.message ?: "歌词获取失败") }
        }
    }

    fun getKGUserPlaylists(): JSONObject {
        if (kgCookie.isBlank()) return JSONObject().apply {
            put("provider", "kg"); put("loggedIn", false); put("playlists", JSONArray())
        }
        val playlists = JSONArray()
        val seenIds = mutableSetOf<String>()

        // ★ go-music-dl 方式: 从 cookie 提取 userid, 调 plist/index/$uid
        val cookieObj = parseCookieString(kgCookie)
        val uid = (cookieObj["userid"] ?: cookieObj["KugooID"] ?: cookieObj["KuGoo"] ?: "").trim()
        Log.d("NeteaseMusic", "KG user playlists: uid=$uid cookieKeys=${cookieObj.keys.take(6)}")

        if (uid.isNotBlank() && uid != "0") {
            try {
                // ★ go-music-dl 方式: 需要 page + pagesize 参数
                val favUrl = "http://m.kugou.com/plist/index/$uid?json=true&page=1&pagesize=30"
                val favResp = httpGetKG(favUrl)
                val favBody = JSONObject(favResp)
                Log.d("NeteaseMusic", "KG user playlists raw: keys=${favBody.keys().asSequence().take(5).toList()}")

                // ★ go-music-dl 方式: 遍历所有可能的数组（不是只取第一个非null）
                // 注意: specialid 可能是整数，必须用 opt() + toString() 才能正确提取
                fun safeId(pl: JSONObject, vararg keys: String): String {
                    for (k in keys) {
                        val v = pl.opt(k); if (v == null || v == JSONObject.NULL) continue
                        val s = v.toString().trim()
                        if (s.isNotBlank() && s != "0") return s
                    }
                    return ""
                }
                // 1. plist.list.info
                favBody.optJSONObject("plist")?.optJSONObject("list")?.optJSONArray("info")?.let { arr ->
                    Log.d("NeteaseMusic", "KG plist.list.info length=${arr.length()}")
                    for (i in 0 until arr.length()) {
                        val pl = arr.optJSONObject(i) ?: continue
                        val plId = safeId(pl, "specialid", "id")
                        if (plId.isBlank() || !seenIds.add(plId)) continue
                        playlists.put(JSONObject().apply {
                            put("provider", "kg"); put("id", plId)
                            put("name", pl.optString("specialname", pl.optString("name", "酷狗歌单")))
                            put("cover", (pl.optString("imgurl", pl.optString("img", ""))).replace("{size}", "240"))
                            put("trackCount", pl.optInt("songcount", pl.optInt("trackCount", 0)))
                        })
                    }
                }
                // 2. data.info
                favBody.optJSONObject("data")?.optJSONArray("info")?.let { arr ->
                    Log.d("NeteaseMusic", "KG data.info length=${arr.length()}")
                    for (i in 0 until arr.length()) {
                        val pl = arr.optJSONObject(i) ?: continue
                        val plId = safeId(pl, "specialid", "id", "listid", "global_specialid")
                        if (plId.isBlank() || !seenIds.add(plId)) continue
                        playlists.put(JSONObject().apply {
                            put("provider", "kg"); put("id", plId)
                            put("name", pl.optString("specialname", pl.optString("name", "酷狗歌单")))
                            put("cover", (pl.optString("imgurl", pl.optString("pic", ""))).replace("{size}", "240"))
                            put("trackCount", pl.optInt("songcount", pl.optInt("count", pl.optInt("trackCount", 0))))
                        })
                    }
                }
                // 3. data.list
                favBody.optJSONObject("data")?.optJSONArray("list")?.let { arr ->
                    Log.d("NeteaseMusic", "KG data.list length=${arr.length()}")
                    for (i in 0 until arr.length()) {
                        val pl = arr.optJSONObject(i) ?: continue
                        val plId = safeId(pl, "specialid", "id")
                        if (plId.isBlank() || !seenIds.add(plId)) continue
                        playlists.put(JSONObject().apply {
                            put("provider", "kg"); put("id", plId)
                            put("name", pl.optString("specialname", pl.optString("name", "酷狗歌单")))
                            put("cover", (pl.optString("imgurl", pl.optString("img", ""))).replace("{size}", "240"))
                            put("trackCount", pl.optInt("songcount", pl.optInt("trackCount", 0)))
                        })
                    }
                }
            } catch (e: Exception) {
                Log.d("NeteaseMusic", "KG user playlists failed: ${e.message}")
            }
        }

        Log.d("NeteaseMusic", "KG playlists total=${playlists.length()}")
        return JSONObject().apply {
            put("provider", "kg"); put("loggedIn", kgCookie.isNotBlank())
            put("playlists", playlists)
        }
    }

    fun getKGPlaylistTracks(id: String): JSONObject {
        if (id.isBlank()) return JSONObject().apply {
            put("provider", "kg"); put("tracks", JSONArray()); put("error", "酷狗歌单需指定ID")
        }
        try {
            val url = "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=1&pagesize=100000&version=9108&area_code=1"
            val resp = httpGetKG(url)
            val body = JSONObject(resp)
            Log.d("NeteaseMusic", "KG playlist tracks (id=$id): code=${body.optInt("status", body.optInt("errcode", -1))}")
            val tracks = JSONArray()
            val infoList = body.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
            for (i in 0 until infoList.length()) {
                val info = infoList.optJSONObject(i) ?: continue
                val hash = info.optString("hash", "")
                val albumId = info.optString("album_id", info.optString("AlbumID", ""))
                // ★ go-music-dl 方式: 从 trans_param.union_cover 提取封面
                val unionCover = info.optJSONObject("trans_param")?.optString("union_cover", "") ?: ""
                val cover = if (unionCover.isNotBlank()) unionCover.replace("{size}", "240") else ""
                tracks.put(JSONObject().apply {
                    put("provider", "kg"); put("source", "kg"); put("type", "kg")
                    put("id", hash); put("kgHash", hash); put("kgAlbumId", albumId)
                    put("name", info.optString("filename", info.optString("songname", "未知歌曲")))
                    put("artist", info.optString("singername", ""))
                    put("artists", JSONArray().apply { put(JSONObject().apply { put("name", info.optString("singername", "")) }) })
                    put("album", info.optString("album_name", ""))
                    put("cover", cover); put("duration", info.optLong("duration", 0) * 1000L)
                    put("fee", 0); put("playable", true)
                })
            }
            return JSONObject().apply { put("provider", "kg"); put("tracks", tracks) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "KG playlist tracks: ${e.message}")
            return JSONObject().apply { put("provider", "kg"); put("tracks", JSONArray()); put("error", e.message ?: "获取失败") }
        }
    }

    private fun httpGetKG(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36")
        conn.setRequestProperty("Referer", "https://m.kugou.com/")
        // ★ 优先使用 kgCookie 中的设备标识（与登录会话一致）
        val cookieObj = if (kgCookie.isNotBlank()) parseCookieString(kgCookie) else mutableMapOf()
        val deviceCookies = mutableListOf<String>()
        val devGuid = cookieObj["KUGOU_API_GUID"] ?: ""
        if (devGuid.isNotBlank()) {
            deviceCookies.add("KUGOU_API_GUID=$devGuid")
            val devMid = cookieObj["KUGOU_API_MID"] ?: ""
            if (devMid.isNotBlank()) deviceCookies.add("KUGOU_API_MID=$devMid")
            val devMac = cookieObj["KUGOU_API_MAC"] ?: ""
            if (devMac.isNotBlank()) deviceCookies.add("KUGOU_API_MAC=$devMac")
            val devDev = cookieObj["KUGOU_API_DEV"] ?: ""
            if (devDev.isNotBlank()) deviceCookies.add("KUGOU_API_DEV=$devDev")
        }
        if (deviceCookies.isEmpty()) {
            // 回退：本地生成的 kg_mid
            val kgMid = ensureKGGuid()
            deviceCookies.add("kg_mid=$kgMid")
        }
        val cookieHeader = buildString {
            if (kgCookie.isNotBlank()) append(kgCookie).append("; ")
            append(deviceCookies.joinToString("; "))
        }
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("Accept", "text/html,application/json,*/*")
        conn.connectTimeout = 8000; conn.readTimeout = 12000
        val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
        return resp
    }

    private fun httpGetQS(url: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", "https://www.qishui.com/")
        if (qsCookie.isNotBlank()) conn.setRequestProperty("Cookie", qsCookie)
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
        return resp
    }

    private fun addQSTrack(tracks: JSONArray, track: JSONObject) {
        val sidObj = track.opt("id")
        val sid = if (sidObj != null && sidObj != JSONObject.NULL) sidObj.toString().trim() else ""
        if (sid.isBlank()) return
        val artists = track.optJSONArray("artists")
        val artistName = artists?.optJSONObject(0)?.optString("name", "") ?: ""
        val coverObj = track.optJSONObject("album")?.optJSONObject("url_cover")
        val cover = if (coverObj != null) {
            val urls = coverObj.optJSONArray("urls"); val uri = coverObj.optString("uri", "")
            if (urls != null && urls.length() > 0) {
                val domain = urls.optString(0, "")
                if (domain.isNotBlank() && uri.isNotBlank() && !domain.contains(uri))
                    domain + uri + "~c5_375x375.jpg"
                else if (domain.isNotBlank()) domain + "~c5_375x375.jpg"
                else ""
            } else ""
        } else ""
        val durMs = track.optLong("duration", 0)
        tracks.put(JSONObject().apply {
            put("provider", "qishui"); put("source", "qishui"); put("type", "qishui")
            put("id", sid); put("qsTrackId", sid)
            put("name", track.optString("name", "未知歌曲"))
            put("artist", if (artistName.isNotBlank()) artistName else "未知歌手")
            put("artists", if (artistName.isNotBlank()) JSONArray().apply { put(JSONObject().apply { put("name", artistName) }) } else JSONArray())
            put("album", track.optJSONObject("album")?.optString("name", "") ?: "")
            put("cover", cover)
            put("duration", durMs)
            put("fee", 0); put("playable", true)
        })
    }

    // ==================== 汽水音乐 API ====================

    fun getQSSearch(keywords: String, limit: Int): JSONObject {
        if (keywords.isBlank()) return JSONObject().apply { put("provider", "qishui"); put("songs", JSONArray()) }
        try {
            val enc = URLEncoder.encode(keywords, "UTF-8")
            // ★ go-music-dl 方式: api.qishui.com/luna/pc/search/track
            val url = "https://api.qishui.com/luna/pc/search/track?q=$enc&cursor=0&search_method=input&aid=386088&device_platform=web&channel=pc_web"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.qishui.com/")
            if (qsCookie.isNotBlank()) conn.setRequestProperty("Cookie", qsCookie)
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            Log.d("NeteaseMusic", "QS search: keys=${body.keys().asSequence().take(6).toList()}")
            val resultGroups = body.optJSONArray("result_groups") ?: JSONArray()
            val songs = JSONArray()
            if (resultGroups.length() > 0) {
                val dataArray = resultGroups.optJSONObject(0)?.optJSONArray("data") ?: JSONArray()
                for (i in 0 until minOf(limit, dataArray.length())) {
                    val entity = dataArray.optJSONObject(i)?.optJSONObject("entity") ?: continue
                    val track = entity.optJSONObject("track") ?: continue
                    val sid = track.optString("id", "")
                    if (sid.isBlank()) continue
                    val artists = track.optJSONArray("artists")
                    val artistName = artists?.optJSONObject(0)?.optString("name", "") ?: ""
                    val coverUri = track.optJSONObject("album")?.optJSONObject("url_cover")?.optString("uri", "") ?: ""
                    val coverUrls = track.optJSONObject("album")?.optJSONObject("url_cover")?.optJSONArray("urls")
                    val cover = if (coverUrls != null && coverUrls.length() > 0) {
                        (coverUrls.optString(0, "") + coverUri + "~c5_375x375.jpg")
                    } else ""
                    val bitRates = track.optJSONArray("bit_rates")
                    var displaySize = 0L
                    if (bitRates != null) {
                        for (j in 0 until bitRates.length()) {
                            displaySize = maxOf(displaySize, bitRates.optJSONObject(j)?.optLong("size", 0) ?: 0)
                        }
                    }
                    songs.put(JSONObject().apply {
                        put("provider", "qishui"); put("source", "qishui"); put("type", "qishui")
                        put("id", sid); put("qsTrackId", sid)
                        put("name", track.optString("name", "未知歌曲"))
                        put("artist", if (artistName.isNotBlank()) artistName else "未知歌手")
                        put("artists", if (artistName.isNotBlank()) JSONArray().apply { put(JSONObject().apply { put("name", artistName) }) } else JSONArray())
                        put("album", track.optJSONObject("album")?.optString("name", "") ?: "")
                        put("cover", cover)
                        put("duration", track.optLong("duration", 0))  // API返回毫秒
                        put("fee", 0); put("playable", true)
                    })
                }
            }
            return JSONObject().apply { put("provider", "qishui"); put("songs", songs) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QS search: ${e.message}")
            return JSONObject().apply { put("provider", "qishui"); put("songs", JSONArray()); put("error", e.message ?: "汽水搜索失败") }
        }
    }

    fun getQSSongUrl(trackId: String): JSONObject {
        if (trackId.isBlank()) return JSONObject().apply {
            put("provider", "qishui"); put("url", ""); put("playable", false)
            put("error", "MISSING_TRACK_ID"); put("message", "Missing QS track id")
        }
        try {
            // ★ music_jx 方式: beta-luna.douyin.com/luna/h5/seo_track
            val url = "https://beta-luna.douyin.com/luna/h5/seo_track?track_id=$trackId&device_platform=web"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.douyin.com/")
            if (qsCookie.isNotBlank()) conn.setRequestProperty("Cookie", qsCookie)
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            Log.d("NeteaseMusic", "QS song url: keys=${body.keys().asSequence().take(6).toList()}")

            // 方式A: track_player.url_player_info → 去请求获取真实URL
            val urlPlayerInfo = body.optJSONObject("track_player")?.optString("url_player_info", null) ?: ""
            if (urlPlayerInfo.isNotBlank()) {
                val conn2 = URL(urlPlayerInfo).openConnection() as HttpsURLConnection
                conn2.setRequestProperty("User-Agent", USER_AGENT)
                conn2.connectTimeout = 8000; conn2.readTimeout = 12000
                val resp2 = conn2.inputStream.bufferedReader().readText(); conn2.disconnect()
                val infoBody = JSONObject(resp2)
                val playInfoList = infoBody.optJSONObject("Result")?.optJSONObject("Data")?.optJSONArray("PlayInfoList")
                if (playInfoList != null && playInfoList.length() > 0) {
                    val pi = playInfoList.optJSONObject(0)
                    val playUrl = pi?.optString("MainPlayUrl", "") ?: ""
                        .ifBlank { pi?.optString("BackupPlayUrl", "") ?: "" }
                    if (playUrl.isNotBlank()) {
                        return JSONObject().apply { put("provider", "qishui"); put("url", playUrl); put("trial", false); put("playable", true); put("quality", "standard") }
                    }
                }
            }

            // 方式B: track_player.video_model → 直接取 main_url
            val videoModel = body.optJSONObject("track_player")?.optString("video_model", null) ?: ""
            if (videoModel.isNotBlank()) {
                val vm = JSONObject(videoModel)
                val videoList = vm.optJSONArray("video_list")
                if (videoList != null && videoList.length() > 0) {
                    val vi = videoList.optJSONObject(0)
                    val playUrl = vi?.optString("main_url", "") ?: ""
                        .ifBlank { vi?.optString("backup_url", "") ?: "" }
                    if (playUrl.isNotBlank()) {
                        return JSONObject().apply { put("provider", "qishui"); put("url", playUrl); put("trial", false); put("playable", true); put("quality", "standard") }
                    }
                }
            }

            // fallback: 旧的 luna vod URL 方式
            val conn3 = URL("https://www.douyin.com/qishui/track?track_id=$trackId").openConnection() as HttpsURLConnection
            conn3.setRequestProperty("User-Agent", USER_AGENT)
            conn3.setRequestProperty("Referer", "https://www.douyin.com/")
            if (qsCookie.isNotBlank()) conn3.setRequestProperty("Cookie", qsCookie)
            conn3.connectTimeout = 8000; conn3.readTimeout = 12000; conn3.instanceFollowRedirects = true
            val html = conn3.inputStream.bufferedReader().readText(); conn3.disconnect()
            val lunaMatch = Regex("https://([a-z0-9]+-luna)\\.douyinvod\\.com/[^\"'\\\\s]*").find(html)
            if (lunaMatch != null) {
                return JSONObject().apply { put("provider", "qishui"); put("url", lunaMatch.value); put("trial", false); put("playable", true); put("quality", "standard") }
            }

            return JSONObject().apply { put("provider", "qishui"); put("url", ""); put("playable", false); put("error", "QS_URL_UNAVAILABLE"); put("message", "汽水未返回播放地址") }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QS song url: ${e.message}")
            return JSONObject().apply { put("provider", "qishui"); put("url", ""); put("playable", false); put("error", "QS_URL_UNAVAILABLE"); put("message", e.message ?: "网络错误") }
        }
    }

    fun getQSLyric(trackId: String): JSONObject {
        if (trackId.isBlank()) return JSONObject().apply { put("provider", "qishui"); put("lyric", ""); put("translate", "") }
        try {
            // ★ music_jx 方式: beta-luna 同时返回歌词
            val url = "https://beta-luna.douyin.com/luna/h5/seo_track?track_id=$trackId&device_platform=web"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.douyin.com/")
            if (qsCookie.isNotBlank()) conn.setRequestProperty("Cookie", qsCookie)
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)

            // beta-luna 返回 lyric.content
            val lyric = body.optJSONObject("lyric")?.optString("content", "") ?: ""

            // fallback: 旧方式从HTML页面提取
            if (lyric.isBlank()) {
                val conn2 = URL("https://www.douyin.com/qishui/track?track_id=$trackId").openConnection() as HttpsURLConnection
                conn2.setRequestProperty("User-Agent", USER_AGENT)
                conn2.setRequestProperty("Referer", "https://www.douyin.com/")
                conn2.connectTimeout = 8000; conn2.readTimeout = 12000; conn2.instanceFollowRedirects = true
                val html = conn2.inputStream.bufferedReader().readText(); conn2.disconnect()
                val lyricMatch = Regex("\"lyric\"\\s*:\\s*\"([^\"]*?)\"").find(html)
                val rawLyric = lyricMatch?.groupValues?.getOrNull(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                return JSONObject().apply { put("provider", "qishui"); put("lyric", rawLyric); put("translate", "") }
            }
            return JSONObject().apply { put("provider", "qishui"); put("lyric", lyric); put("translate", "") }
        } catch (e: Exception) {
            return JSONObject().apply { put("provider", "qishui"); put("lyric", ""); put("translate", ""); put("error", e.message ?: "歌词获取失败") }
        }
    }

    fun saveQSCookie(cookie: String) {
        qsCookie = cookie.trim()
        Log.d("NeteaseMusic", "QS cookie saved, length=${qsCookie.length}")
        try {
            qsCookieFile?.writeText(qsCookie)
            Log.d("NeteaseMusic", "QS cookie file written OK: ${qsCookieFile?.absolutePath} size=${qsCookieFile?.length()}")
        } catch (e: Exception) {
            Log.e("NeteaseMusic", "QS cookie file write FAILED: ${e.message}", e)
        }
    }

    fun getQSCookie(): String = qsCookie

    fun getQSLoginInfo(): JSONObject {
        if (qsCookie.isBlank()) return JSONObject().apply {
            put("provider", "qishui"); put("loggedIn", false); put("hasCookie", false)
            put("message", "汽水音乐未登录，可匿名搜索和播放")
        }
        val cookieObj = parseCookieString(qsCookie)
        // 核心凭证：passport_csrf_token + sessionid 是登录状态的标志
        val hasCsrf = cookieObj.containsKey("passport_csrf_token")
        val hasSession = cookieObj.containsKey("sessionid") || cookieObj.containsKey("sessionid_ss")
        val isReallyLoggedIn = qsCookie.isNotBlank() && hasCsrf && hasSession
        val userId = if (isReallyLoggedIn) {
            cookieObj["passport_user_id"] ?: cookieObj["uid"] ?: cookieObj["user_unique_id"]
            ?: ""
        } else ""
        val nickname = try {
            val encoded = cookieObj["nickname"] ?: cookieObj["user_name"] ?: ""
            if (encoded.isEmpty()) "汽水用户" else java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) { "汽水用户" }
        // ★ 截断过长 userId
        val displayUserId = userId.take(16)
        Log.d("NeteaseMusic", "QS login info: hasCsrf=$hasCsrf hasSession=$hasSession isLoggedIn=$isReallyLoggedIn userId=$displayUserId cookieKeys=${cookieObj.keys.take(10)}")
        return JSONObject().apply {
            put("provider", "qishui"); put("loggedIn", isReallyLoggedIn)
            put("hasCookie", true); put("userId", displayUserId.ifEmpty { "抖音用户" })
            put("nickname", nickname.ifEmpty { "汽水用户" })
            put("avatar", ""); put("vipType", 0)
        }
    }

    fun getQSUserPlaylists(): JSONObject {
        if (qsCookie.isBlank()) return JSONObject().apply {
            put("provider", "qishui"); put("loggedIn", false)
            put("playlists", JSONArray()); put("error", "请先登录汽水音乐")
        }
        try {
            // ★ go-music-dl 方式: 先调 /luna/pc/me 获取用户ID, 再调 user/playlist
            val playlists = JSONArray()

            // Step 1: 获取用户信息
            var userId = ""
            try {
                val meUrl = "https://api.qishui.com/luna/pc/me?aid=386088&device_platform=web&channel=pc_web"
                val meConn = URL(meUrl).openConnection() as HttpsURLConnection
                meConn.setRequestProperty("User-Agent", USER_AGENT)
                meConn.setRequestProperty("Referer", "https://www.qishui.com/")
                meConn.setRequestProperty("Cookie", qsCookie)
                meConn.connectTimeout = 10000; meConn.readTimeout = 15000
                val meResp = meConn.inputStream.bufferedReader().readText(); meConn.disconnect()
                val meBody = JSONObject(meResp)
                Log.d("NeteaseMusic", "QS PC me: status=${meBody.optInt("status_code", -1)}")
                userId = meBody.optJSONObject("my_info")?.optString("id", "")?.trim() ?: ""
            } catch (e: Exception) {
                Log.d("NeteaseMusic", "QS PC me failed: ${e.message}")
            }

            // Step 2: 获取用户歌单列表
            if (userId.isNotBlank()) {
                try {
                    val plUrl = "https://api.qishui.com/luna/pc/user/playlist?user_id=$userId&cursor=0&count=30&aid=386088&device_platform=web&channel=pc_web"
                    val plConn = URL(plUrl).openConnection() as HttpsURLConnection
                    plConn.setRequestProperty("User-Agent", USER_AGENT)
                    plConn.setRequestProperty("Referer", "https://www.qishui.com/")
                    plConn.setRequestProperty("Cookie", qsCookie)
                    plConn.connectTimeout = 10000; plConn.readTimeout = 15000
                    val plResp = plConn.inputStream.bufferedReader().readText(); plConn.disconnect()
                    val plBody = JSONObject(plResp)
                    Log.d("NeteaseMusic", "QS PC playlists: status=${plBody.optInt("status_code", -1)} keys=${plBody.keys().asSequence().take(5).toList()}")
                    val plArray = plBody.optJSONArray("playlists") ?: JSONArray()
                    for (i in 0 until plArray.length()) {
                        val pl = plArray.optJSONObject(i) ?: continue
                        val plId = pl.optString("id", "")
                        if (plId.isBlank()) continue
                        val coverObj = pl.optJSONObject("url_cover")
                        val cover = if (coverObj != null) {
                            val urls = coverObj.optJSONArray("urls"); val uri = coverObj.optString("uri", "")
                            if (urls != null && urls.length() > 0) urls.optString(0, "") + uri + "~c5_300x300.jpg" else ""
                        } else ""
                        playlists.put(JSONObject().apply {
                            put("provider", "qishui"); put("id", plId)
                            put("name", pl.optString("title", pl.optString("public_title", "汽水歌单")))
                            put("cover", cover)
                            put("trackCount", pl.optInt("count_tracks", pl.optInt("trackCount", 0)))
                        })
                    }
                } catch (e: Exception) {
                    Log.d("NeteaseMusic", "QS PC playlists failed: ${e.message}")
                }
            }

            Log.d("NeteaseMusic", "QS playlists total=${playlists.length()}")
            return JSONObject().apply {
                put("provider", "qishui")
                put("loggedIn", qsCookie.isNotBlank() && userId.isNotBlank())
                put("playlists", playlists)
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QS playlists: ${e.message}")
            return JSONObject().apply {
                put("provider", "qishui"); put("loggedIn", true)
                put("playlists", JSONArray()); put("error", e.message ?: "歌单获取失败")
            }
        }
    }

    fun getQSPlaylistTracks(id: String): JSONObject {
        if (qsCookie.isBlank()) return JSONObject().apply {
            put("provider", "qishui"); put("tracks", JSONArray()); put("error", "请先登录")
        }
        try {
            val tracks = JSONArray()

            // ★ 方法1: 直接用 web API (cnt=20) — 对齐 go-music-dl fetchPlaylistDetailWeb
            val webUrl = "https://api.qishui.com/luna/pc/playlist/detail?playlist_id=$id&cursor=0&cnt=100000&aid=386088&device_platform=web&channel=pc_web"
            val webBody = httpGetQS(webUrl)
            val resp = JSONObject(webBody)
            Log.d("NeteaseMusic", "QS playlist tracks web (id=$id): keys=${resp.keys().asSequence().take(8).toList()}")

            // ★ 先尝试解析 media_resources
            var mediaResources = resp.optJSONArray("media_resources")
            if (mediaResources == null || mediaResources.length() == 0) {
                // 有时字段名可能是 MediaResources
                mediaResources = resp.optJSONArray("MediaResources")
            }
            if (mediaResources != null && mediaResources.length() > 0) {
                Log.d("NeteaseMusic", "QS tracks mediaResources len=${mediaResources.length()}")
                for (i in 0 until mediaResources.length()) {
                    val mr = mediaResources.optJSONObject(i) ?: continue
                    val type = mr.optString("type", mr.optString("Type", ""))
                    if (type != "track") continue
                    val entity = mr.optJSONObject("entity") ?: mr.optJSONObject("Entity") ?: continue
                    val trackWrapper = entity.optJSONObject("track_wrapper") ?: entity.optJSONObject("TrackWrapper") ?: continue
                    val track = trackWrapper.optJSONObject("track") ?: trackWrapper.optJSONObject("Track") ?: continue
                    addQSTrack(tracks, track)
                }
            }

            if (tracks.length() > 0) {
                Log.d("NeteaseMusic", "QS tracks web result len=${tracks.length()}")
                return JSONObject().apply { put("provider", "qishui"); put("tracks", tracks) }
            }

            // ★ 方法2: SSR 页面回退 — 抓取 qishui.com/playlist/<id> 页面中的 __ROUTER_DATA__
            Log.d("NeteaseMusic", "QS web API empty, trying SSR page scrape for id=$id")
            try {
                val pageUrl = "https://www.qishui.com/playlist/$id"
                val conn = URL(pageUrl).openConnection() as HttpsURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Cookie", qsCookie)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                val html = conn.inputStream.bufferedReader().readText(); conn.disconnect()

                // 搜索 __ROUTER_DATA__ 或 window.__INITIAL_STATE__
                val routerDataPattern = Regex("""__ROUTER_DATA__\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                val windowStatePattern = Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)

                val routerMatch = routerDataPattern.find(html) ?: windowStatePattern.find(html)
                if (routerMatch != null) {
                    val dataJson = routerMatch.groupValues[1]
                    val dataObj = JSONObject(dataJson)
                    Log.d("NeteaseMusic", "QS SSR data keys: ${dataObj.keys().asSequence().take(8).toList()}")

                    // 深度搜索 track 列表
                    try {
                        crawlTrackInfo(dataObj, tracks, "track_list")
                        crawlTrackInfo(dataObj, tracks, "trackList")
                        crawlTrackInfo(dataObj, tracks, "music_list")
                        crawlTrackInfo(dataObj, tracks, "musicList")
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.d("NeteaseMusic", "QS SSR page scrape failed: ${e.message}")
            }

            Log.d("NeteaseMusic", "QS playlist tracks final len=${tracks.length()}")
            return JSONObject().apply { put("provider", "qishui"); put("tracks", tracks) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "QS playlist tracks: ${e.message}")
            return JSONObject().apply { put("provider", "qishui"); put("tracks", JSONArray()); put("error", e.message ?: "获取失败") }
        }
    }

    private fun crawlTrackInfo(obj: Any, tracks: JSONArray, keyName: String) {
        when (obj) {
            is JSONObject -> {
                for (key in obj.keys()) {
                    if (key == keyName) {
                        val arr = obj.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val track = arr.optJSONObject(i) ?: continue
                            addQSTrack(tracks, track)
                        }
                    } else {
                        crawlTrackInfo(obj.opt(key) ?: JSONObject.NULL, tracks, keyName)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    crawlTrackInfo(obj.opt(i) ?: JSONObject.NULL, tracks, keyName)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  酷我音乐 (kuwo) — listen1/musicdl API
    // ═════════════════════════════════════════════════════════════
    fun getKuwoSearch(keyword: String, limit: Int): JSONObject {
        if (keyword.isBlank()) return JSONObject().apply { put("provider", "kuwo"); put("songs", JSONArray()) }
        try {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "https://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=${limit.coerceIn(1, 30)}&rformat=json&encoding=utf8"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://www.kuwo.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            // 酷我返回格式: abslist[].{NAME, ARTIST, DC_TARGETID, web_albumpic_short, ALBUM, ...}
            val body = JSONObject(raw)
            val abslist = body.optJSONArray("abslist") ?: JSONArray()
            val songs = JSONArray()
            for (i in 0 until abslist.length()) {
                val item = abslist.optJSONObject(i) ?: continue
                val rid = item.optString("DC_TARGETID", item.optString("MUSICRID", "").replace("MUSIC_", ""))
                Log.d("NeteaseMusic", "kuwo search item: name=${item.optString("NAME")} rid=$rid")
                if (rid.isBlank()) continue
                val cover = item.optString("web_albumpic_short", "").let {
                    if (it.isNotBlank()) "https://img2.kuwo.cn/star/albumcover/$it" else ""
                }
                songs.put(JSONObject().apply {
                    put("provider", "kuwo"); put("source", "kuwo")
                    put("id", rid); put("kuwoRid", rid)
                    put("name", item.optString("NAME", "未知歌曲"))
                    put("artist", item.optString("ARTIST", "未知歌手"))
                    put("album", item.optString("ALBUM", ""))
                    put("cover", cover)
                    put("duration", 0)
                    put("fee", 0); put("playable", true)
                })
            }
            return JSONObject().apply { put("provider", "kuwo"); put("songs", songs) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Kuwo search: ${e.message}")
            return JSONObject().apply { put("provider", "kuwo"); put("songs", JSONArray()); put("error", e.message ?: "酷我搜索失败") }
        }
    }

    fun getKuwoSongUrl(rid: String): JSONObject {
        if (rid.isBlank()) return JSONObject().apply { put("provider", "kuwo"); put("url", ""); put("playable", false) }
        try {
            // listen1 方式: /api/v1/www/music/playUrl
            val url = "https://www.kuwo.cn/api/v1/www/music/playUrl?mid=$rid&type=music&httpsStatus=1&reqId=&plat=web_www&from="
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://www.kuwo.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            var resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            var body = JSONObject(resp)
            var playUrl = body.optJSONObject("data")?.optString("url", "") ?: ""
            // 回退: 旧版 API
            if (playUrl.isBlank()) {
                val legacyUrl = "https://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=$rid&format=mp3&response=url"
                conn = URL(legacyUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 8000; conn.readTimeout = 10000
                playUrl = conn.inputStream.bufferedReader().readText().trim(); conn.disconnect()
            }
            return JSONObject().apply {
                put("provider", "kuwo")
                put("url", playUrl)
                put("playable", playUrl.isNotBlank())
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Kuwo play: ${e.message}")
            return JSONObject().apply { put("provider", "kuwo"); put("url", ""); put("playable", false); put("error", e.message ?: "") }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  咪咕音乐 (migu) — listen1/musicdl API
    // ═════════════════════════════════════════════════════════════
    fun getMiguSearch(keyword: String, limit: Int): JSONObject {
        if (keyword.isBlank()) return JSONObject().apply { put("provider", "migu"); put("songs", JSONArray()) }
        try {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            val searchSwitch = """{"song":1}"""
            val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=$encoded&pageNo=1&pageSize=${limit.coerceIn(1, 30)}&searchSwitch=$searchSwitch"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.migu.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(raw)
            // 返回结构: songResultData.result[].{songName, singer, copyrightId, albumImgs[0].img, album, songId, contentId, toneControl}
            val songResult = body.optJSONObject("songResultData") ?: JSONObject()
            val result = songResult.optJSONArray("result") ?: JSONArray()
            val songs = JSONArray()
            for (i in 0 until result.length()) {
                val item = result.optJSONObject(i) ?: continue
                val cid = item.optString("copyrightId", "")
                if (cid.isBlank()) continue
                val imgArr = item.optJSONArray("albumImgs")
                val cover = if (imgArr != null && imgArr.length() > 0) imgArr.optJSONObject(0)?.optString("img", "") ?: "" else ""
                // singer 可能是字符串或数组
                val singerRaw = item.opt("singer") ?: item.opt("singers")
                val singerName = when (singerRaw) {
                    is org.json.JSONArray -> singerRaw.optJSONObject(0)?.optString("name", "") ?: ""
                    else -> singerRaw?.toString() ?: ""
                }
                val albumRaw = item.opt("album")
                val albumName = when (albumRaw) {
                    is JSONObject -> albumRaw.optString("albumName", albumRaw.optString("name", ""))
                    else -> albumRaw?.toString() ?: ""
                }
                songs.put(JSONObject().apply {
                    put("provider", "migu"); put("source", "migu")
                    put("id", cid); put("miguCopyrightId", cid)
                    put("songId", item.optString("songId", ""))
                    put("contentId", item.optString("contentId", ""))
                    put("name", item.optString("songName", item.optString("name", "未知歌曲")))
                    put("artist", singerName.ifBlank { "未知歌手" })
                    put("album", albumName)
                    put("cover", cover)
                    put("duration", 0)
                    put("fee", 0); put("playable", true)
                    put("toneControl", item.optString("toneControl", ""))
                })
            }
            return JSONObject().apply { put("provider", "migu"); put("songs", songs) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Migu search: ${e.message}")
            return JSONObject().apply { put("provider", "migu"); put("songs", JSONArray()); put("error", e.message ?: "咪咕搜索失败") }
        }
    }

    fun getMiguSongUrl(copyrightId: String, contentId: String, toneControl: String): JSONObject {
        if (copyrightId.isBlank()) return JSONObject().apply { put("provider", "migu"); put("url", ""); put("playable", false) }
        try {
            // listen1: https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do
            val tf = toneControl.ifBlank { "HQ" }
            val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do?toneFlag=$tf&netType=00&userId=1554861458&ua=Android_migu&version=5.0&copyrightId=$copyrightId&contentId=$contentId&resourceType=2&channel=0"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.migu.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            val playUrl = body.optJSONObject("data")?.optString("url", body.optString("playUrl", "")) ?: ""
            return JSONObject().apply {
                put("provider", "migu")
                put("url", playUrl)
                put("playable", playUrl.isNotBlank())
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Migu play: ${e.message}")
            return JSONObject().apply { put("provider", "migu"); put("url", ""); put("playable", false); put("error", e.message ?: "") }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  酷我音乐 歌词
    // ═════════════════════════════════════════════════════════════
    fun getKuwoLyric(rid: String): JSONObject {
        if (rid.isBlank()) return JSONObject().apply { put("provider", "kuwo"); put("lyric", ""); put("translate", "") }
        try {
            // listen1 方式: m.kuwo.cn/newh5/singles/songinfoandlrc
            val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$rid"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://www.kuwo.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            val data = body.optJSONObject("data") ?: JSONObject()
            val lrclist = data.optJSONArray("lrclist")
            val lyric = if (lrclist != null && lrclist.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until lrclist.length()) {
                    val item = lrclist.optJSONObject(i) ?: continue
                    val time = item.optString("time", "")
                    val lineLyric = item.optString("lineLyric", "")
                    if (time.isNotBlank() && lineLyric.isNotBlank()) {
                        sb.append("[$time]$lineLyric\n")
                    }
                }
                sb.toString().trimEnd()
            } else ""
            return JSONObject().apply {
                put("provider", "kuwo")
                put("lyric", lyric)
                put("translate", "")
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Kuwo lyric: ${e.message}")
            return JSONObject().apply { put("provider", "kuwo"); put("lyric", ""); put("translate", ""); put("error", e.message ?: "") }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  咪咕音乐 歌词
    // ═════════════════════════════════════════════════════════════
    fun getMiguLyric(copyrightId: String, contentId: String): JSONObject {
        if (copyrightId.isBlank()) return JSONObject().apply { put("provider", "migu"); put("lyric", ""); put("translate", "") }
        try {
            // listen1 方式: app.c.nf.migu.cn/MIGUM2.0/v1.0/content/sub/subContent.do
            val cid = contentId.ifBlank { copyrightId }
            val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/sub/subContent.do?contentId=$cid&copyrightId=$copyrightId&resourceType=2"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/80 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.migu.cn/")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            val lyric = body.optString("lrcUrl", "").let { lrcUrl ->
                if (lrcUrl.isNotBlank()) {
                    try {
                        val lrcConn = URL(lrcUrl).openConnection() as HttpURLConnection
                        lrcConn.connectTimeout = 5000; lrcConn.readTimeout = 8000
                        lrcConn.inputStream.bufferedReader().readText()
                    } catch (_: Exception) { "" }
                } else ""
            }
            val translate = body.optString("lrcTransUrl", "").let { transUrl ->
                if (transUrl.isNotBlank()) {
                    try {
                        val transConn = URL(transUrl).openConnection() as HttpURLConnection
                        transConn.connectTimeout = 5000; transConn.readTimeout = 8000
                        transConn.inputStream.bufferedReader().readText()
                    } catch (_: Exception) { "" }
                } else ""
            }
            return JSONObject().apply {
                put("provider", "migu")
                put("lyric", lyric)
                put("translate", translate)
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Migu lyric: ${e.message}")
            return JSONObject().apply { put("provider", "migu"); put("lyric", ""); put("translate", ""); put("error", e.message ?: "") }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  千千音乐 (qianqian/taihe) — music.91q.com API
    // ═════════════════════════════════════════════════════════════
    private fun qianqianSign(params: Map<String, String>): String {
        val secret = "0b50b02fd0d73a9c4c8c3a781c30845f"
        val sorted = params.toSortedMap()
        val raw = sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest((raw + secret).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getQianqianSearch(keyword: String, limit: Int): JSONObject {
        if (keyword.isBlank()) return JSONObject().apply { put("provider", "qianqian"); put("songs", JSONArray()) }
        try {
            val params = mapOf(
                "word" to keyword,
                "type" to "1",
                "pageNo" to "1",
                "pageSize" to limit.coerceIn(1, 30).toString(),
                "appid" to "16073360",
                "timestamp" to (System.currentTimeMillis() / 1000).toString()
            )
            val sign = qianqianSign(params)
            val paramStr = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val url = "https://music.91q.com/v1/search?$paramStr&sign=$sign"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/143.0.0.0 Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.91q.com/player")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val raw = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(raw)
            val typeTrack = body.optJSONObject("data")?.optJSONArray("typeTrack") ?: JSONArray()
            val songs = JSONArray()
            for (i in 0 until typeTrack.length()) {
                val item = typeTrack.optJSONObject(i) ?: continue
                val tsid = item.optString("TSID", "")
                if (tsid.isBlank()) continue
                val artistArr = item.optJSONArray("artist")
                val artistName = if (artistArr != null && artistArr.length() > 0)
                    artistArr.optJSONObject(0)?.optString("name", "") ?: ""
                else ""
                songs.put(JSONObject().apply {
                    put("provider", "qianqian"); put("source", "qianqian")
                    put("id", tsid); put("qianqianTsid", tsid)
                    put("name", item.optString("title", "未知歌曲"))
                    put("artist", artistName.ifBlank { "未知歌手" })
                    put("album", item.optString("albumTitle", ""))
                    put("cover", item.optString("pic", ""))
                    put("duration", 0)
                    put("fee", 0); put("playable", true)
                })
            }
            return JSONObject().apply { put("provider", "qianqian"); put("songs", songs) }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Qianqian search: ${e.message}")
            return JSONObject().apply { put("provider", "qianqian"); put("songs", JSONArray()); put("error", e.message ?: "千千搜索失败") }
        }
    }

    fun getQianqianSongUrl(tsid: String, rate: String): JSONObject {
        if (tsid.isBlank()) return JSONObject().apply { put("provider", "qianqian"); put("url", ""); put("playable", false) }
        try {
            val r = rate.ifBlank { "320" }
            val params = mapOf(
                "TSID" to tsid,
                "appid" to "16073360",
                "rate" to r,
                "timestamp" to (System.currentTimeMillis() / 1000).toString()
            )
            val sign = qianqianSign(params)
            val paramStr = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val url = "https://music.91q.com/v1/song/tracklink?$paramStr&sign=$sign"
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/143.0.0.0 Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.91q.com/player")
            conn.connectTimeout = 8000; conn.readTimeout = 12000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val body = JSONObject(resp)
            var playUrl = body.optJSONObject("data")?.optString("path", "") ?: ""
            // 回退: trail_audio_info
            if (playUrl.isBlank()) {
                playUrl = body.optJSONObject("data")?.optJSONObject("trail_audio_info")?.optString("path", "") ?: ""
            }
            return JSONObject().apply {
                put("provider", "qianqian")
                put("url", playUrl)
                put("playable", playUrl.isNotBlank())
            }
        } catch (e: Exception) {
            Log.w("NeteaseMusic", "Qianqian play: ${e.message}")
            return JSONObject().apply { put("provider", "qianqian"); put("url", ""); put("playable", false); put("error", e.message ?: "") }
        }
    }
}
