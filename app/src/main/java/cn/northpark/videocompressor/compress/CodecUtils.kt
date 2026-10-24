package cn.northpark.videocompressor.compress

import android.media.MediaCodecList
import android.media.MediaFormat

object CodecUtils {

    /**
     * 检测设备是否支持 HEVC (H.265) 视频编码
     */
    fun isHevcEncoderSupported(): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                for (type in codecInfo.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 根据分辨率与时长计算推荐极限压缩码率 (bps)
     * 保持原始分辨率的前提下，达到最大化体积削减与画质保留的平衡
     */
    fun calculateTargetBitrate(width: Int, height: Int, originalBitrate: Long, isHevc: Boolean): Int {
        val totalPixels = (width * height).toLong()
        
        // 基础目标码率 (基于 H.264 估算)
        val baseBitrate = when {
            totalPixels >= 3840 * 2160 -> 6_000_000  // 4K ~ 6 Mbps
            totalPixels >= 2560 * 1440 -> 3_500_000  // 2K ~ 3.5 Mbps
            totalPixels >= 1920 * 1080 -> 1_800_000  // 1080p ~ 1.8 Mbps
            totalPixels >= 1280 * 720  -> 900_000    // 720p ~ 900 kbps
            else -> 500_000                          // 480p及以下 ~ 500 kbps
        }

        // 如果是高效 HEVC (H.265)，在相同画质下码率可进一步降低 35%~45%
        var targetBitrate = if (isHevc) {
            (baseBitrate * 0.65).toInt()
        } else {
            baseBitrate
        }

        // 如果原视频本身的码率已经很低，取原码率的 70%，防止码率反而被无意义拉高
        if (originalBitrate in 100_000 until targetBitrate) {
            targetBitrate = (originalBitrate * 0.7).toInt()
        }

        return targetBitrate.coerceAtLeast(350_000)
    }
}
