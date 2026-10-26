package com.hongguotv.lite.data.content

import android.media.MediaCodecList
import com.hongguotv.core.port.DecoderCapabilities
import com.hongguotv.core.port.VideoCodec

/**
 * 用平台解码器表回答"这台设备能不能解 HEVC"。
 *
 * 只按 `MediaCodecList` 是否注册了该 MIME 判定，不区分软硬解：软解在低配盒子的高分辨率下
 * 通常不可用，但把它排除会让只有软解的机型连低档都拿不到；能不能解最终由播放器判定。
 * 查询放在第一次取流时做（[mimeTypes] 是 lazy），不占启动帧。
 */
class AndroidDecoderCapabilities : DecoderCapabilities {

    private val mimeTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .toSet()
    }

    override fun supports(codec: VideoCodec): Boolean = when (codec) {
        VideoCodec.AVC -> MIME_AVC in mimeTypes
        VideoCodec.HEVC -> MIME_HEVC in mimeTypes
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
    }
}
