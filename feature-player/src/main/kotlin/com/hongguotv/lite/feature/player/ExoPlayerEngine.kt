package com.hongguotv.lite.feature.player

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.model.FrameMode
import com.hongguotv.core.port.MediaStream
import com.hongguotv.lite.uikit.token.Tokens
import com.hongguotv.lite.uikit.token.UiTier

/**
 * media3 的实现。**media3 只出现在两个文件里**（本文件与 `StreamDataSource`，后者是"端口流 → media3
 * DataSource"的适配），模块边界由此成立：app 拿不到 ExoPlayer 类型，换播放器实现时
 * 状态机、按键表、侧栏一行都不用动。
 *
 * 两处按设备能力分档（`docs/PLATFORM.md`「内存极小」）：老盒子的缓冲窗口与目标字节都调小，
 * 否则 512MB 的设备会在起播时被自己的缓冲区顶穿。
 *
 * 画面之上**不放任何常驻控件**：`useController = false`，选集与设置全在 MENU 侧栏里。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ExoPlayerEngine(context: Context, tokens: Tokens) : PlaybackEngine {

    private val playerView: PlayerView
    private val player: ExoPlayer
    private var listener: EngineListener? = null
    private var stream: MediaStream? = null
    private var tracksChecked = false

    init {
        val lowRam = tokens.tier == UiTier.LOW_RAM
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (lowRam) 8_000 else 15_000,
                /* maxBufferMs = */ if (lowRam) 16_000 else 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setTargetBufferBytes((if (lowRam) 6 else 12) * 1024 * 1024)
            .build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val enginePlayer = ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build()
        player = enginePlayer
        playerView = PlayerView(context).apply {
            useController = false
            // 画面不是焦点项：遥控器方向键留给按键表，不交给视图树
            isFocusable = false
            isClickable = false
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setKeepContentOnPlayerReset(true)
            player = enginePlayer
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // "就绪但没有视频轨"是解码器不支持当集编码的强特征，早点报，别让用户对黑屏等
                        if (!tracksChecked) {
                            tracksChecked = true
                            if (!player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                                listener?.onFailed(PlaybackFailure.Decode("no video track"))
                                return
                            }
                        }
                        listener?.onReady()
                    }

                    Player.STATE_BUFFERING, Player.STATE_IDLE -> listener?.onChanged()
                    Player.STATE_ENDED -> listener?.onEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onChanged()
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                listener?.onChanged()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                listener?.onChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onFailed(translate(error))
            }
        })
    }

    /** 无感切集的缓冲指示兜底：超时把转圈恢复成常开。 */
    private val restoreBuffering = Runnable {
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
    }

    /** 画面视图。由页面负责挂到树上、以及 `keepScreenOn`。 */
    fun view(): View = playerView

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    override fun load(stream: MediaStream, positionMs: Long, playWhenReady: Boolean, seamless: Boolean) {
        this.stream?.close()
        this.stream = stream
        tracksChecked = false
        // 无感切集：上一集最后一帧留在画面上，同时把缓冲转圈压掉。
        // 只压 1.2 秒——预取的数据万一被换出、还得走网络，转圈该回来就回来，
        // 不能让用户对着一帧静止画面干等（旧实现同口径）。
        playerView.removeCallbacks(restoreBuffering)
        if (seamless) {
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            playerView.postDelayed(restoreBuffering, SEAMLESS_BUFFERING_MS)
        } else {
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        val source = ProgressiveMediaSource.Factory { StreamDataSource(stream) }
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(EPISODE_URI)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build(),
            )
        player.setMediaSource(source)
        player.seekTo(positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setFrame(mode: FrameMode) {
        playerView.resizeMode = when (mode) {
            FrameMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            FrameMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            FrameMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    override fun stop() {
        playerView.removeCallbacks(restoreBuffering)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        player.stop()
        player.clearMediaItems()
        stream?.close()
        stream = null
        tracksChecked = false
    }

    override fun release() {
        playerView.removeCallbacks(restoreBuffering)
        player.stop()
        player.release()
        stream?.close()
        stream = null
        playerView.player = null
    }

    override val positionMs: Long
        get() = player.currentPosition.coerceAtLeast(0)

    override val bufferedPositionMs: Long
        get() = player.bufferedPosition.coerceAtLeast(0)

    override val durationMs: Long
        get() = player.duration.let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0) }

    override val buffering: Boolean
        get() = player.playbackState == Player.STATE_BUFFERING

    override val ready: Boolean
        get() = player.playbackState == Player.STATE_READY

    override val ended: Boolean
        get() = player.playbackState == Player.STATE_ENDED

    /**
     * 失败分类：解码类错误码（3000—4999）**不自动重试**——同一个文件再解三次还是解不了，
     * 换个清晰度才有用；其余（网络、IO、超时）都当成"过一会儿可能好"。
     */
    private fun translate(error: PlaybackException): PlaybackFailure {
        val code = error.errorCode
        return if (code in DECODE_ERROR_RANGE) {
            PlaybackFailure.Decode(error.errorCodeName)
        } else {
            PlaybackFailure.Unavailable(error)
        }
    }

    private companion object {
        /** 播放地址永远不落盘、也不进 `MediaItem` 的 URL：它会被重签，而且带时效。 */
        const val EPISODE_URI = "hongguotv://episode"

        /** media3 的解码类错误码区间（`PlaybackException.ERROR_CODE_DECODING_*` 到 `ERROR_CODE_DRM_*`）。 */
        val DECODE_ERROR_RANGE = 3_000..4_999

        /** 无感切集最多压住缓冲转圈的时长（旧实现同值）。 */
        const val SEAMLESS_BUFFERING_MS = 1_200L
    }
}
