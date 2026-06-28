package com.mineradio.app.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 系统媒体会话管理器
 *
 * 让系统识别 Mineradio 为音乐播放器，实现在：
 *  - 通知栏显示播放控件
 *  - 锁屏界面显示歌曲信息和控件
 *  - 蓝牙/耳机线控响应
 *  - Android Auto 等
 */
class MediaSessionManager(
    private val context: Context,
    private val targetActivityClass: Class<*>
) {

    companion object {
        private const val TAG = "MediaSessionMgr"
        private const val NOTIFICATION_ID = 9700
        const val CHANNEL_ID = "mineradio_playback"
        const val CHANNEL_NAME = "Mineradio 音乐播放"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var mediaSession: MediaSessionCompat? = null
    private var coverBitmap: Bitmap? = null
    private var coverUrl: String = ""

    // 媒体按钮回调
    var onPlayClicked: (() -> Unit)? = null
    var onPauseClicked: (() -> Unit)? = null
    var onSkipNextClicked: (() -> Unit)? = null
    var onSkipPrevClicked: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    /** 初始化 MediaSession */
    fun init() {
        val session = MediaSessionCompat(context, "Mineradio", null, createPendingIntent())
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                Log.d(TAG, "onPlay from system")
                handler.post { onPlayClicked?.invoke() }
            }
            override fun onPause() {
                Log.d(TAG, "onPause from system")
                handler.post { onPauseClicked?.invoke() }
            }
            override fun onSkipToNext() {
                Log.d(TAG, "onSkipToNext from system")
                handler.post { onSkipNextClicked?.invoke() }
            }
            override fun onSkipToPrevious() {
                Log.d(TAG, "onSkipToPrevious from system")
                handler.post { onSkipPrevClicked?.invoke() }
            }
            override fun onSeekTo(pos: Long) {
                Log.d(TAG, "onSeekTo: $pos")
                handler.post { onSeekTo?.invoke(pos) }
            }
            override fun onStop() {
                Log.d(TAG, "onStop from system")
                updatePlaybackStateInternal(PlaybackStateCompat.STATE_STOPPED, 0)
            }
        })

        // 初始状态：停止
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )

        session.isActive = true
        this.mediaSession = session
        Log.i(TAG, "MediaSession 初始化完成")
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, targetActivityClass)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 更新歌曲信息和播放状态 — 供 WebView JS 桥调用 */
    fun updateTrack(
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        duration: Long,
        isPlaying: Boolean
    ) {
        handler.post {
            val session = mediaSession ?: return@post
            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

            // 封面异步加载
            if (coverUrl.isNotBlank() && coverUrl != this.coverUrl) {
                this.coverUrl = coverUrl
                executor.execute { loadCoverBitmap(coverUrl) }
            }

            if (coverBitmap != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, coverBitmap)
            }

            session.setMetadata(builder.build())
            updatePlaybackStateInternal(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                0
            )
            updateNotification(title, artist, isPlaying)
        }
    }

    /** 更新播放状态（播放/暂停） */
    fun updatePlaybackState(playing: Boolean) {
        handler.post {
            updatePlaybackStateInternal(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                0
            )
            // 同步更新通知
            val meta = mediaSession?.controller?.metadata
            if (meta != null) {
                updateNotification(
                    meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "",
                    meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "",
                    playing
                )
            }
        }
    }

    /** 更新播放进度 */
    fun updatePosition(position: Long, duration: Long) {
        handler.post {
            val state = mediaSession?.controller?.playbackState ?: return@post
            val newState = PlaybackStateCompat.Builder(state)
                .setState(state.state, position, state.playbackSpeed, System.currentTimeMillis())
                .build()
            mediaSession?.setPlaybackState(newState)
        }
    }

    private fun updatePlaybackStateInternal(state: Int, position: Long) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

        val builder = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f, System.currentTimeMillis())
            .setActions(actions)

        mediaSession?.setPlaybackState(builder.build())
    }

    private fun updateNotification(title: String, artist: String, isPlaying: Boolean) {
        try {
            val playIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

            val playAction = NotificationCompat.Action.Builder(
                playIcon,
                if (isPlaying) "暂停" else "播放",
                createMediaButtonPendingIntent(if (isPlaying) "pause" else "play")
            ).build()

            val nextAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next, "下一首",
                createMediaButtonPendingIntent("next")
            ).build()

            val prevAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous, "上一首",
                createMediaButtonPendingIntent("prev")
            ).build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(coverBitmap)
                .setContentIntent(createPendingIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .addAction(prevAction)
                .addAction(playAction)
                .addAction(nextAction)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setSilent(true)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "通知更新失败: ${e.message}")
        }
    }

    private fun createMediaButtonPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, targetActivityClass).apply {
            putExtra("media_action", action)
        }
        return PendingIntent.getActivity(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun handleMediaAction(action: String?) {
        when (action) {
            "play" -> onPlayClicked?.invoke()
            "pause" -> onPauseClicked?.invoke()
            "next" -> onSkipNextClicked?.invoke()
            "prev" -> onSkipPrevClicked?.invoke()
        }
    }

    /** 隐藏通知 */
    fun hideNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /** 销毁 MediaSession */
    fun release() {
        handler.post {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }
        hideNotification()
        coverBitmap?.recycle()
        coverBitmap = null
    }

    private fun loadCoverBitmap(url: String) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doInput = true
            conn.connect()
            val input: InputStream = conn.inputStream
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            conn.disconnect()

            if (bitmap != null) {
                // 缩放到合适大小
                val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
                if (scaled != bitmap) bitmap.recycle()

                handler.post {
                    coverBitmap?.recycle()
                    coverBitmap = scaled
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "封面加载失败: ${e.message}")
        }
    }
}
