package cn.northpark.videocompressor.compress

import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 容器元数据处理工具
 * 用于在无损音视频数据前提下，精准修补 MP4 容器头部 (moov -> mvhd, trak -> tkhd, mdia -> mdhd)
 * 的 creation_time 与 modification_time，保证系统相册及媒体解析器识别到的拍摄/创建日期与原视频 100% 保持一致。
 */
object Mp4MetadataUtils {

    // 1904-01-01 00:00:00 UTC 到 1970-01-01 00:00:00 UTC 之间相隔的秒数 (66 年，含 17 个闰年)
    private const val MP4_EPOCH_OFFSET_SECONDS = 2082844800L

    /**
     * 将 Unix 毫秒时间戳 (自 1970) 转换为 MP4 内部自 1904-01-01 00:00:00 UTC 的秒数
     */
    fun millisToMp4TimeSeconds(timeMs: Long): Long {
        val secondsSince1970 = timeMs / 1000L
        return secondsSince1970 + MP4_EPOCH_OFFSET_SECONDS
    }

    /**
     * 修改 MP4 文件内部 mvhd、tkhd、mdhd 的创建时间和修改时间
     * @param mp4File 待修改的 MP4 文件
     * @param targetDateMs 目标时间（毫秒，自 1970-01-01）
     * @return 是否成功修补
     */
    fun updateCreationAndModificationDate(mp4File: File, targetDateMs: Long): Boolean {
        if (!mp4File.exists() || mp4File.length() < 16 || targetDateMs <= 0L) {
            return false
        }

        val targetMp4Seconds = millisToMp4TimeSeconds(targetDateMs)

        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(mp4File, "rw")
            patchBoxes(raf, 0L, raf.length(), targetMp4Seconds)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try {
                raf?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 递归/按层级扫描 Box 树并修补目标 Header Box
     */
    private fun patchBoxes(
        raf: RandomAccessFile,
        startOffset: Long,
        endOffset: Long,
        targetMp4Seconds: Long
    ) {
        var currentPos = startOffset
        val headerBuffer = ByteArray(8)

        while (currentPos + 8 <= endOffset) {
            raf.seek(currentPos)
            val bytesRead = raf.read(headerBuffer)
            if (bytesRead < 8) break

            var boxSize = ((headerBuffer[0].toLong() and 0xFF) shl 24) or
                    ((headerBuffer[1].toLong() and 0xFF) shl 16) or
                    ((headerBuffer[2].toLong() and 0xFF) shl 8) or
                    (headerBuffer[3].toLong() and 0xFF)

            val boxType = String(headerBuffer, 4, 4, Charsets.US_ASCII)

            var headerSize = 8L
            if (boxSize == 1L) {
                // 64-bit Large size
                val largeSizeBuffer = ByteArray(8)
                val readLarge = raf.read(largeSizeBuffer)
                if (readLarge < 8) break
                boxSize = 0L
                for (b in largeSizeBuffer) {
                    boxSize = (boxSize shl 8) or (b.toLong() and 0xFF)
                }
                headerSize = 16L
            } else if (boxSize == 0L) {
                // Box 一直延伸到文件末尾
                boxSize = endOffset - currentPos
            }

            if (boxSize < headerSize || currentPos + boxSize > endOffset) {
                break
            }

            val boxPayloadOffset = currentPos + headerSize
            val boxPayloadEnd = currentPos + boxSize

            when (boxType) {
                "moov", "trak", "mdia" -> {
                    // 容器 Box，递归修补内部的子 Box
                    patchBoxes(raf, boxPayloadOffset, boxPayloadEnd, targetMp4Seconds)
                }
                "mvhd", "tkhd", "mdhd" -> {
                    // 核心 Header Box，修补其中的 creation_time 与 modification_time
                    patchHeaderBox(raf, boxPayloadOffset, boxPayloadEnd, targetMp4Seconds)
                }
            }

            currentPos += boxSize
        }
    }

    /**
     * 修补具体的 Header Box 时间戳数据
     */
    private fun patchHeaderBox(
        raf: RandomAccessFile,
        payloadOffset: Long,
        payloadEnd: Long,
        targetMp4Seconds: Long
    ) {
        raf.seek(payloadOffset)
        val versionFlags = ByteArray(4)
        if (raf.read(versionFlags) < 4) return
        val version = versionFlags[0].toInt() and 0xFF

        val timeOffset = payloadOffset + 4
        raf.seek(timeOffset)

        if (version == 0) {
            // 32-bit creation_time (4 bytes) 和 32-bit modification_time (4 bytes)
            if (timeOffset + 8 <= payloadEnd) {
                val sec32 = (targetMp4Seconds and 0xFFFFFFFFL).toInt()
                raf.writeInt(sec32) // creation_time
                raf.writeInt(sec32) // modification_time
            }
        } else if (version == 1) {
            // 64-bit creation_time (8 bytes) 和 64-bit modification_time (8 bytes)
            if (timeOffset + 16 <= payloadEnd) {
                raf.writeLong(targetMp4Seconds) // creation_time
                raf.writeLong(targetMp4Seconds) // modification_time
            }
        }
    }
}
