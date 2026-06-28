# 四大音乐平台 API 实现逻辑

> 源码位置: `app/src/main/java/com/mineradio/app/manager/NeteaseMusicApi.kt` (~3200行)  
> 路由层: `app/src/main/java/com/mineradio/app/manager/MineradioServer.kt` (~800行)  
> 前端调用: `app/src/main/assets/mineradio/index.html`

---

## 架构总览

```
┌──────────────────────────────────────────┐
│  index.html (前端 JS)                    │
│  apiJson('/api/xxx/search?...')         │
│  apiJson('/api/xxx/song/url?...')       │
│  apiJson('/api/xxx/login/cookie', ...)  │
└────────────────┬─────────────────────────┘
                 │ fetch() to localhost
┌────────────────▼─────────────────────────┐
│  MineradioServer.kt (路由层)             │
│  pathOnly == "/api/qq/search"           │
│  → 参数解析 → 调用 Api → 返回 JSON       │
└────────────────┬─────────────────────────┘
                 │ 函数调用
┌────────────────▼─────────────────────────┐
│  NeteaseMusicApi.kt (核心实现 ~3200行)    │
│  网易云: weapiPost() AES-CBC 加密        │
│  QQ音乐: httpGetQQ() / httpPostQQ()      │
│  汽水:   HttpURLConnection 直连          │
│  酷狗:   HttpURLConnection 直连          │
└──────────────────────────────────────────┘
```

| 平台 | 加密方式 | 核心域名 | API 函数数 |
|------|---------|---------|-----------|
| 网易云 | WeAPI (AES-CBC) | `music.163.com` | 14 |
| QQ音乐 | u.y.qq.com JSON-RPC | `c.y.qq.com` / `u.y.qq.com` | 14 |
| 汽水 | HTTP 直连 + Cookie | `api.qishui.com` / `beta-luna.douyin.com` | 8 |
| 酷狗 | HTTP 直连 | `songsearch.kugou.com` / `m.kugou.com` | 8 |

---

## 一、网易云音乐

### 1.1 登录 — 二维码扫码

```
前端 → GET /api/login/qr/key
     → NeteaseMusicApi.getLoginQrKey()
     → POST https://music.163.com/api/login/qrcode/unikey
       参数: { type: "3" }
       加密: weapiPost() — AES-128-CBC + Base64
     → 返回 unikey (如: "abc123def...")

前端 → GET /api/login/qr/create?key=xxx
     → NeteaseMusicApi.createLoginQrImage(key)
     → 本地 ZXing 生成二维码 (300x300, ErrorCorrectionLevel.M)
     → 二维码内容: https://music.163.com/login?codekey={key}
     → 输出 Base64 PNG

前端 → 轮询 GET /api/login/qr/check?key=xxx  (每 2 秒)
     → NeteaseMusicApi.checkLoginQrStatus(key)
     → POST /api/login/qrcode/client/login
       参数: { key: "xxx", type: 3 }
       加密: weapiPost()
     → 返回状态码:
       - code=801 → 等待扫码
       - code=802 → 已扫码，等待确认
       - code=803 → 已确认 → setCookie() 保存 MUSIC_U
       - code=800 → 二维码过期

Cookie 存储: 文件 cookie_file (含 MUSIC_U + MUSIC_A)
```

### 1.2 WeAPI 加密算法 (weapiPost)

```kotlin
// AES-128-CBC 加密
fun weapiEncrypt(text: String): Map<String, String> {
    val secretKey = generateRandomString(16)   // 随机 16 字节 key
    val encText = aesEncrypt(
        aesEncrypt(text, NONCE, IV),           // 第1轮: 固定 NONCE+IV
        secretKey, IV                          // 第2轮: 随机 key+IV
    )
    // encSecKey: RSA 加密 随机key (反转后)
    // 模数: 0x00e0b509... 指数: 0x010001
    return mapOf("params" to encText, "encSecKey" to encSecKey)
}

// POST https://music.163.com/weapi/{path}
// Content-Type: application/x-www-form-urlencoded
// body: params=encText&encSecKey=encSecKey
```

### 1.3 搜索

```
前端 → GET /api/search?keywords=xxx&limit=30&offset=0
     → NeteaseMusicApi.searchSongs(keywords, limit, offset)
     → POST /weapi/cloudsearch/get/web
       参数: { s: keywords, type: 1, limit, offset }
     → 解析 result.songs[] → 提取:
       - id, name, ar[].name → artist, al.name → album
       - dt → duration(ms), alia[] → 别名
       - privilege.fee, privilege.maxbr
     → 空结果重试: 若 songs.length==0 且无 MUSIC_A → 重新获取匿名 token 后重试一次
```

### 1.4 歌曲 URL

```
前端 → GET /api/song/url?id=xxx&quality=exhigh
     → NeteaseMusicApi.getSongUrl(id, quality, version=0)
     → POST /weapi/song/enhance/player/url/v1
       参数: { ids: "[id]", level: quality, encodeType: "flac" }
     → 音质映射:
       standard → 128kbps
       exhigh   → 320kbps
       lossless → 990kbps
       hires    → 1999kbps
       jymaster → 4608kbps
     → 解析 data[0].url → 直接播放链接
     → 若 url 为空且无登录 → 返回 trial=true (试听片段)
     → 若 url 为空且有登录 → 返回 playable=false
```

### 1.5 歌词

```
GET /api/lyric?id=xxx
  → getLyric(id)
  → POST /weapi/song/lyric?os=pc
    参数: { id: "xxx", lv: -1, kv: -1, tv: -1 }
  → lrc.lyric → 纯文本歌词 (带时间戳)
  → tlyric.lyric → 翻译歌词 (如有)

GET /api/lyric/search?name=xxx&artist=xxx
  → searchLyricByName(name, artist)
  → 先 searchSongs(1条) → 取 id → getLyric(id)
```

### 1.6 歌单

```
我的歌单:
  GET /api/user/playlists?uid=0
    → getUserPlaylists(uid)
    → POST /weapi/user/playlist
      参数: { uid: uid, limit: 100, offset: 0 }
    → playlist[] → id, name, coverImgUrl, trackCount, creator.nickname

歌单歌曲:
  GET /api/playlist/tracks?id=xxx
    → getPlaylistTracks(id, limit=100000)
    → POST /weapi/v6/playlist/detail
      参数: { id: "xxx", n: 100000, s: 8 }
    → playlist.tracks[] → 全部歌曲 (无上限)

创建歌单:
  POST /api/playlist/create { name: "xxx", privacy: "0" }
  → createPlaylist(name, privacy)
  → POST /weapi/playlist/create
    参数: { name: "xxx", privacy: 0 }

添加到歌单:
  POST /api/playlist/add-song { pid: xxx, trackIds: "id1,id2" }
  → addSongToPlaylist(pid, trackIds)
  → POST /weapi/playlist/manipulate/tracks
    参数: { op: "add", pid, trackIds, ... }
```

### 1.7 推荐 / 喜欢

```
每日推荐:
  GET /api/discover/home
    → getLoginStatus() + getRecommendSongs()
    → POST /weapi/v1/discovery/recommend/songs  (需登录)
    → recommend[] → id, name, artists, album, reason

喜欢歌曲:
  POST /api/song/like?id=xxx&like=true
    → likeSong(id, like)
    → POST /weapi/radio/like
      参数: { alg: "itembased", trackId, like, time }

检查喜欢:
  GET /api/song/like/check?ids=1,2,3
    → likeCheck(ids)
    → POST /weapi/song/like/check 参数: { ids: [1,2,3] }
```

---

## 二、QQ音乐

### 2.1 登录 — WebView Cookie 扫码

```
方式一 (内置 WebView — 推荐):
  JS → window.openQQWebLogin() → KeepApp.openQQMusicLogin()
     → MainActivity 启动 QQLoginActivity
     → WebView (UA: Chrome Win64) 加载:
       "https://i2.y.qq.com/n2/m/share/login/login.html"
     → 用户扫码 → Cookie 自动收集 → WebViewClient 检测
     → onPageFinished → evaluateJavascript 提取 document.cookie
     → RESULT_COOKIE → MainActivity.onActivityResult()
     → webView.evaluateJavascript("window._onQQLoginCookie(cookie)")

方式二 (手动粘贴 Cookie):
  JS → submitQQCookieLogin()
     → POST /api/qq/login/cookie { cookie: "uin=xxx; qm_keyst=xxx; ..." }
```

**服务端 Cookie 验证** (`MineradioServer.kt:167`):

```kotlin
val cookieObj = parseCookieObj(raw)

// ★ 全面 key 匹配，支持 QQ/微信/手机多端登录
val uin = qqCookieUin(cookieObj)
// 匹配顺序:
//   login_type==2 → wxuin > uin > p_uin  (微信登录)
//   login_type!=2 → uin > qqmusic_uin > wxuin > p_uin
// 最终 normalizeQQUin: 去除非数字字符 + 去前导0

val musicKey = qqCookieMusicKey(cookieObj)
// 匹配顺序:
//   qm_keyst > qqmusic_key > music_key > p_skey
//   > skey > psrf_qqaccess_token > psrf_qqrefresh_token
//   > wxrefresh_token > wxskey

if (uin.isEmpty() || musicKey.isEmpty())
  → "QQ cookie 缺少 uin 或有效登录票据"
else
  → saveQQCookie(raw) → getQQLoginInfo()
```

**登录状态获取** (`getQQLoginInfo()`)：

```kotlin
fun getQQLoginInfo(): JSONObject {
    // 有 Cookie → 解析 uin + musicKey
    // 尝试获取用户资料:
    //   GET https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg
    //     ?cid=205360838&userid={uin}&reqfrom=1
    //   → data.creator.nick / headpic
    // 失败则用 Cookie 中的 ptnick + qlogo.cn 回退
    // 返回: provider, loggedIn, userId, nickname, avatar, playbackKeyReady
}
```

### 2.2 搜索 — 两步法

```
GET /api/qq/search?keywords=xxx&limit=16
  → getQQSearch(keywords, limit)

第一步: smartbox 联想
  GET https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg
    ?format=json&key={keywords}&g_tk=5381&platform=yqq.json
  → data.song.itemlist[] → mid, name, singer
  → 最多取 min(limit, 10) 条

第二步: 逐个获取详情
  POST https://u.y.qq.com/cgi-bin/musicu.fcg
  Body: {
    comm: { ct: "24", cv: 0 },
    songinfo: {
      module: "music.pf_song_detail_svr",
      method: "get_song_detail_yqq",
      param: { song_mid: "xxx" }
    }
  }
  → songinfo.data.track_info → album, singer[], duration, cover
```

### 2.3 歌曲 URL — VKey 获取

```
GET /api/qq/song/url?mid=xxx&mediaMid=xxx&quality=hires
  → getQQSongUrl(mid, mediaMid, quality)

音质模板 (按降级顺序):
  (1) RS01.flac → hires    (Hi-Res)
  (2) F000.flac → lossless (无损)
  (3) M800.mp3  → exhigh   (320kbps)
  (4) M500.mp3  → standard (128kbps)
  (5) C400.m4a  → aac      (AAC)

URL 生成公式:
  POST https://u.y.qq.com/cgi-bin/musicu.fcg
  模块: vkey.GetVkeyServer / CgiGetVkey
  参数: guid, songmid[], songtype[], uin, loginflag=1, platform=20, filename[]
  → req_0.data.midurlinfo[i].purl
  → "http://" + sip[0] + purl   (sip[0] = 服务器 IP 列表)

guid 生成: 10000000 + random(90000000)
```

### 2.4 歌词

```
GET /api/qq/lyric?mid=xxx&id=xxx
  → getQQLyric(mid, id)
  → GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg
      ?songmid={mid}&g_tk=5381&format=json&inCharset=utf8&...
    Referer: y.qq.com
  → 正则提取: /<lyric><!\[CDATA\[([\s\S]*?)\]\]><\/lyric>/
```

### 2.5 歌单

```
我的歌单:
  GET /api/qq/user/playlists
    → getQQUserPlaylists()
    → 并发请求:
      (A) fcg_get_profile_homepage → userid + 基本信息
      (B) musicu.fcg (创建歌单):
          module: "playlist.Mr_pc_playlist_adaptor"
          method: "get_my_playlist_by_category"
          → m_mydiss.list[] → 用 uin 创建歌单
      (C) musicu.fcg (收藏歌单):
          同上模块但 category=collect
          → m_collect.list[] → 收藏歌单

歌单歌曲:
  GET /api/qq/playlist/tracks?id=xxx
    → getQQPlaylistTracks(id)
    → GET https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg
        ?type=1&utf8=1&disstid={id}&loginUin={uin}
        &num=100000&format=json&platform=yqq.json&needNewCode=0
    → cdlist[0].songlist[] → 全部歌曲 (num=100000 无上限)
    每首歌提取: songid, songmid, songname, singer[].name, album.name

添加歌曲到歌单:
  POST /api/qq/playlist/add-song { pid: "xxx", mid: "xxx" }
    → qqAddSongToPlaylist(pid, mid)
    → POST https://c.y.qq.com/qzone/fcg-bin/cgi_operate_playlist_song.fcg
```

---

## 三、汽水音乐 (抖音)

### 3.1 登录 — WebView Cookie 扫码

```
JS → window.openQishuiLogin() → KeepApp.openQishuiLogin()
  → MainActivity 启动 QishuiLoginActivity
  → WebView UA: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"
  → 加载 https://www.douyin.com/  (抖音官网 → 汽水共用账号)
  → 用户扫码 → Cookie 收集 → RESULT_COOKIE
  → POST /api/qishui/login/cookie { cookie: "..." }
```

**Cookie 验证**:
```kotlin
fun getQSLoginInfo(): JSONObject {
    // 核心凭证: passport_csrf_token + sessionid
    // userId: passport_user_id / uid / user_unique_id
    // nickname: nickname / user_name (URL decode)
    // 判断 isReallyLoggedIn: hasCsrf && hasSession
}
```

### 3.2 搜索

```
GET /api/qishui/search?keywords=xxx&limit=16
  → getQSSearch(keywords, limit)
  → GET https://api.qishui.com/luna/pc/search/track
      ?q={keywords}&cursor=0&search_method=input
      &aid=386088&device_platform=web&channel=pc_web
    Referer: https://www.qishui.com/
  → result_groups[0].data[].entity.track
  → 提取: id, name, artists[].name, album.url_cover, duration, bit_rates
```

### 3.3 歌曲 URL — 两种方式

```
GET /api/qishui/song/url?trackId=xxx
  → getQSSongUrl(trackId)

方式A (优先): beta-luna.douyin.com
  GET https://beta-luna.douyin.com/luna/h5/seo_track?track_id=xxx
    → track_player.url_player_info → 二层 URL
    → GET {url_player_info} → 返回真实播放链接
    → 解析 { code: 0, data: { url: "..." } }

方式B (回落): api.qishui.com
  GET https://api.qishui.com/luna/pc/track/detail?track_id=xxx
    → bit_rates[].playable_url
    → 选择最佳音质 (按 bit_rate 排序)
```

### 3.4 歌词

```
GET /api/qishui/lyric?trackId=xxx
  → getQSLyric(trackId)
  → GET https://api.qishui.com/luna/pc/lyric?track_id=xxx
     Referer: https://www.qishui.com/
  → data.lyric_lines[] → content, line_index, type(0=原文/1=翻译)
  → 组合成 LRC 格式: [timestamp]content\n
```

### 3.5 歌单

```
我的歌单:
  GET /api/qishui/user/playlists
    → getQSUserPlaylists()
    → 步骤1: GET https://api.qishui.com/luna/pc/me → 获取用户ID
    → 步骤2: GET https://api.qishui.com/luna/pc/user/playlist
              ?user_id=xxx&cursor=0&cnt=100&aid=386088
    → playlist[] → id, title, cover_url, track_count

歌单歌曲:
  GET /api/qishui/playlist/tracks?id=xxx
    → getQSPlaylistTracks(id)
    → GET https://api.qishui.com/luna/pc/playlist/detail
        ?playlist_id=xxx&cursor=0&cnt=100000
        &aid=386088&device_platform=web&channel=pc_web
    → tracks[] → 全部歌曲
```

---

## 四、酷狗音乐

### 4.1 登录 — WebView Cookie 扫码

```
JS → window.openKugouLogin() → KeepApp.openKugouLogin()
  → MainActivity 启动 KugouLoginActivity
  → WebView UA: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"
  → 加载 https://www.kugou.com/  (酷狗官网)
  → 用户扫码 → Cookie 收集 → RESULT_COOKIE
  → POST /api/kg/login/cookie { cookie: "..." }
```

**Cookie 验证**:
```kotlin
fun getKGLoginInfo(): JSONObject {
    // 核心凭证: userid + token  (对标 go-music-dl)
    // userId: userid / KugooID / KuGoo
    // token:  token / kg_mid
    // nickname: NickName / nickname (URL decode)
    // 判断 isReallyLoggedIn: hasUserId && hasToken
}
```

### 4.2 搜索

```
GET /api/kg/search?keywords=xxx&limit=16
  → getKGSearch(keywords, limit)

GET https://songsearch.kugou.com/song_search_v2
    ?keyword={keywords}&platform=WebFilter&format=json
    &page=1&pagesize={limit}&userid=-1&tag=em
    &filter=2&iscorrection=1&privilege_filter=0&_={ts}

策略:
  1. 先带 Cookie 请求 (kgCookie)
  2. 失败或非 JSON → 不带 Cookie 重试
    UA: Android Chrome 80 (手机UA更稳定)

数据解析:
  data.lists[] →
    FileHash → id (song hash)
    AlbumID  → 专辑ID
    SongName → 歌曲名
    SingerName → 歌手
    AlbumName → 专辑名
    Image    → 封面 (replace("{size}", "240"))
    Duration → 秒 (×1000 → 毫秒)
```

### 4.3 歌曲 URL — 三步获取

```
GET /api/kg/song/url?hash=xxx&albumId=xxx&quality=hires
  → getKGSongUrl(hash, albumId, quality)

步骤1 (优先): m.kugou.com playInfo
  GET https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=xxx
    Referer: https://m.kugou.com/
  → { url: "...", bitRate: 320 } → 直接返回
  → 若 url 为空 → 步骤2

步骤2 (回落): trackercdn.kugou.com
  ⇒ getKGSongUrlLegacy(hash, albumId)
  GET http://trackercdn.kugou.com/i/v2/?key=xxx&hash=xxx&...
  → key 计算: md5(hash + "kgcloudv2")   (固定 salt)
  → 返回: { url: ["http://..."], ... }
  → "http://" + url[0]

步骤3 (最终):
  拼接播放 URL:
  - aac 格式: http://fs.w.kugou.com/ + 返回路径
  - mp3 格式: http://fs.open.kugou.com/ + 返回路径
  根据 qualityPreference 选择最高可用的音质
```

### 4.4 歌词

```
GET /api/kg/lyric?hash=xxx&albumId=xxx
  → getKGLyric(hash, albumId)

第一步: 搜索歌词ID
  GET https://krcs.kugou.com/search
    ?ver=1&man=yes&client=mobi&keyword={hash}
    &hash={hash}&album_audio_id=xxx&duration=xxx
  → candidates[0].id, accesskey

第二步: 获取歌词内容
  GET http://lyrics.kugou.com/download
    ?ver=1&client=pc&id={id}&accesskey={key}
    &fmt=lrc&charset=utf8
  → content → 解码 → LRC 文本

基础 LRC: GET http://m.kugou.com/krc/{hash}.lrc  (无损用)
```

### 4.5 歌单

```
我的歌单:
  GET /api/kg/user/playlists
    → getKGUserPlaylists()
    → GET http://m.kugou.com/plist/index
        ?op=getMyPlist&token=&t={ts}
    → 若已登录 → Cookie 中的 userid + token
    → lists[] → specialid, specialname, imgurl, songcount

歌单歌曲:
  GET /api/kg/playlist/tracks?id=xxx
    → getKGPlaylistTracks(id)
    → GET http://mobilecdn.kugou.com/api/v3/special/song
        ?specialid=xxx&page=1&pagesize=100000
        &version=9108&area_code=1
    → data.info[] → hash, filename, singername, album_name, duration
    → 页面轮询: 若 total > pagesize → 翻页获取全部歌曲
```

---

## 五、公共基础设施

### 5.1 HTTP 请求封装

```kotlin
// 网易云 — WeAPI 加密请求
fun weapiPost(path: String, params: JSONObject): JSONObject
  → AES-CBC 加密 → POST https://music.163.com/weapi/{path}

// QQ音乐 — JSON-RPC
fun httpGetQQ(url: String): String
  → GET url, UA: QQ 浏览器, Cookie: qqCookie

fun httpPostQQ(url: String, body: String): String
  → POST url, Content-Type: application/json, Cookie: qqCookie

// 酷狗 — 手机版
fun httpGetKG(url: String): String
  → UA: Android Chrome, Cookie: kgCookie

// 汽水 — 直连
  → HttpURLConnection / HttpsURLConnection 直接 get/post
  → UA: Chrome Win64, Cookie: qsCookie
```

### 5.2 Cookie 管理

```kotlin
// 持久化: 文件存储 (app filesDir)
private var userCookie  = ""     // cookie_file   → 网易云
private var qqCookie    = ""     // qq_cookie_file → QQ音乐
private var kgCookie    = ""     // kg_cookie_file → 酷狗
private var kgMid       = ""     // kg_mid_file    → 酷狗设备ID
private var qsCookie    = ""     // qs_cookie_file → 汽水

// Cookie 解析
fun parseCookieObj(raw: String): Map<String, String>
  → 正则: /([^=; ]+)=([^;]*)/g
  → 返回 Map<key, value>

fun parseCookieString(raw: String): Map<String, String>
  → 同上
```

### 5.3 匿名 Token (网易云)

```kotlin
// 网易云匿名请求需要 MUSIC_A token
var anonymousTokenFetched = false

fun ensureAnonymousToken() {
    // GET https://music.163.com/api/register/anonimous
    // Set-Cookie: MUSIC_A=xxx
    // 将 MUSIC_A 合并到 userCookie
}
```

---

## 六、完整 API 路由表

### 网易云音乐

| 路由 | 方法 | 函数 | 说明 |
|------|------|------|------|
| `/api/login/qr/key` | GET | `getLoginQrKey()` | 获取二维码 Key |
| `/api/login/qr/create?key=` | GET | `createLoginQrImage()` | 生成二维码 Base64 |
| `/api/login/qr/check?key=` | GET | `checkLoginQrStatus()` | 轮询扫码状态 |
| `/api/login/status` | GET | `getLoginStatus()` | 获取登录状态 |
| `/api/login/cookie` | POST | `setCookie()` | 手动设置 Cookie |
| `/api/logout` | GET | `logout()` | 退出登录 |
| `/api/search?keywords=&limit=` | GET | `searchSongs()` | 搜索歌曲 |
| `/api/song/url?id=&quality=` | GET | `getSongUrl()` | 获取播放地址 |
| `/api/lyric?id=` | GET | `getLyric()` | 获取歌词 |
| `/api/user/playlists` | GET | `getUserPlaylists()` | 我的歌单 |
| `/api/playlist/tracks?id=` | GET | `getPlaylistTracks()` | 歌单歌曲 |
| `/api/song/like?id=&like=` | POST | `likeSong()` | 喜欢/取消 |
| `/api/song/like/check?ids=` | GET | `likeCheck()` | 批量查喜欢状态 |
| `/api/playlist/create` | POST | `createPlaylist()` | 创建歌单 |
| `/api/playlist/add-song` | POST | `addSongToPlaylist()` | 添加歌曲 |
| `/api/discover/home` | GET | `getRecommendSongs()` | 每日推荐 |

### QQ音乐

| 路由 | 方法 | 函数 | 说明 |
|------|------|------|------|
| `/api/qq/login/cookie` | POST | `saveQQCookie()` | QQ Cookie 登录 |
| `/api/qq/login/status` | GET | `getQQLoginInfo()` | 登录状态 |
| `/api/qq/logout` | GET | `saveQQCookie("")` | 退出 |
| `/api/qq/search?keywords=&limit=` | GET | `getQQSearch()` | 搜索 |
| `/api/qq/song/url?mid=&mediaMid=` | GET | `getQQSongUrl()` | 播放地址 |
| `/api/qq/lyric?mid=&id=` | GET | `getQQLyric()` | 歌词 |
| `/api/qq/user/playlists` | GET | `getQQUserPlaylists()` | 我的歌单 |
| `/api/qq/playlist/tracks?id=` | GET | `getQQPlaylistTracks()` | 歌单歌曲 |
| `/api/qq/playlist/add-song` | POST | `qqAddSongToPlaylist()` | 添加到歌单 |

### 酷狗音乐

| 路由 | 方法 | 函数 | 说明 |
|------|------|------|------|
| `/api/kg/login/cookie` | POST | `saveKGCookie()` | Cookie 登录 |
| `/api/kg/login/status` | GET | `getKGLoginInfo()` | 登录状态 |
| `/api/kg/logout` | GET | `saveKGCookie("")` | 退出 |
| `/api/kg/search?keywords=&limit=` | GET | `getKGSearch()` | 搜索 |
| `/api/kg/song/url?hash=&albumId=` | GET | `getKGSongUrl()` | 播放地址 |
| `/api/kg/lyric?hash=&albumId=` | GET | `getKGLyric()` | 歌词 |
| `/api/kg/user/playlists` | GET | `getKGUserPlaylists()` | 我的歌单 |
| `/api/kg/playlist/tracks?id=` | GET | `getKGPlaylistTracks()` | 歌单歌曲 |

### 汽水音乐

| 路由 | 方法 | 函数 | 说明 |
|------|------|------|------|
| `/api/qishui/login/cookie` | POST | `saveQSCookie()` | Cookie 登录 |
| `/api/qishui/login/status` | GET | `getQSLoginInfo()` | 登录状态 |
| `/api/qishui/logout` | GET | `saveQSCookie("")` | 退出 |
| `/api/qishui/search?keywords=&limit=` | GET | `getQSSearch()` | 搜索 |
| `/api/qishui/song/url?trackId=` | GET | `getQSSongUrl()` | 播放地址 |
| `/api/qishui/lyric?trackId=` | GET | `getQSLyric()` | 歌词 |
| `/api/qishui/user/playlists` | GET | `getQSUserPlaylists()` | 我的歌单 |
| `/api/qishui/playlist/tracks?id=` | GET | `getQSPlaylistTracks()` | 歌单歌曲 |
