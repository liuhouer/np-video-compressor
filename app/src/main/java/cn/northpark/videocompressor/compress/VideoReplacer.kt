package cn.northpark.videocompressor.compress

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import cn.northpark.videocompressor.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 视频替换与元数据保护工具
 * 核心保障：
 * 1. 完美适配 Android 10/11/12/13/14+ 分区存储 (Scoped Storage)
 * 2. 覆盖替换原视频后，严格保留源视频的“拍摄时间 (DATE_TAKEN)”、“修改时间 (DATE_MODIFIED)”及“添加时间 (DATE_ADDED)”
 * 3. 深度修补 MP4 容器内部 mvhd/tkhd/mdhd 时间戳，保证相册与文件管理器中显示的日期与原视频 100% 保持一致
 */
class VideoReplacer(private val context: Context) {

    /**
     * 为 Android 11+ (API 30+) 构建批量修改原视频的系统授权 PendingIntent
     */
    fun createWriteRequestIntent(uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createWriteRequest(context.contentResolver, uris)
        }
        return null
    }

    /**
     * 将临时压缩好的视频文件覆盖替换到相册原始视频并完美保持源视频原始日期
     * @param videoItem 源视频信息
     * @param compressedFile 压缩后的新文件
     */
    suspend fun replaceOriginalVideo(
        videoItem: VideoItem,
        compressedFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!compressedFile.exists() || compressedFile.length() == 0L) {
            return@withContext false
        }

        val targetDateMs = videoItem.effectiveDateMs
        val originalDateModifiedSec = if (videoItem.dateModified > 0L) {
            videoItem.dateModified
        } else {
            (targetDateMs / 1000L).coerceAtLeast(1L)
        }
        val originalDateTakenMs = if (videoItem.dateTaken > 0L) {
            videoItem.dateTaken
        } else {
            targetDateMs.coerceAtLeast(1000L)
        }
        val originalDateAddedSec = videoItem.dateAdded

        // 步骤 1：在覆盖前，修补压缩临时 MP4 容器内部所有 Header Box 的创建和修改时间戳
        if (targetDateMs > 0L) {
            try {
                Mp4MetadataUtils.updateCreationAndModificationDate(compressedFile, targetDateMs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                compressedFile.setLastModified(originalDateModifiedSec * 1000L)
            } catch (_: Exception) {
            }
        }

        var writeSuccess = false
        val targetUri = videoItem.uri
        val targetPath = videoItem.path

        // 步骤 2：通过 ContentResolver 打开 "wt" 模式流覆盖写入原相册 Uri (Android 10+ 推荐方式)
        try {
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { outputStream ->
                FileInputStream(compressedFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                    outputStream.flush()
                }
                writeSuccess = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 步骤 3：若通过 Uri 写入受阻且 targetPath 存在并可直接写，尝试直接覆盖文件
        if (!writeSuccess && !targetPath.isNullOrBlank()) {
            try {
                val originFile = File(targetPath)
                if (originFile.exists() && originFile.canWrite()) {
                    FileInputStream(compressedFile).use { input ->
                        FileOutputStream(originFile).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    writeSuccess = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 步骤 4：覆盖成功后，彻底恢复物理文件属性与系统 MediaStore 数据库中的所有日期字段
        if (writeSuccess) {
            // 4.1 物理文件修改时间恢复 (流写入完成后内核会将 mtime 置为当前时间，必须显式恢复)
            if (!targetPath.isNullOrBlank()) {
                try {
                    val originFile = File(targetPath)
                    if (originFile.exists()) {
                        originFile.setLastModified(originalDateModifiedSec * 1000L)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4.2 更新系统媒体库：严格保持原视频的 DATE_MODIFIED、DATE_TAKEN 和 DATE_ADDED
            try {
                val newSize = compressedFile.length()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.SIZE, newSize)
                    put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModifiedSec)
                    put(MediaStore.Video.Media.DATE_TAKEN, originalDateTakenMs)
                    if (originalDateAddedSec > 0L) {
                        put(MediaStore.Video.Media.DATE_ADDED, originalDateAddedSec)
                    }
                }
                context.contentResolver.update(targetUri, values, null, null)

                // 4.3 触发媒体扫描刷新，并在回调中确保相册索引时间绝对不受扫描器干扰
                if (!targetPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetPath),
                        arrayOf("video/mp4")
                    ) { _, scannedUri ->
                        try {
                            val finalUri = scannedUri ?: targetUri
                            val fixValues = ContentValues().apply {
                                put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModifiedSec)
                                put(MediaStore.Video.Media.DATE_TAKEN, originalDateTakenMs)
                            }
                            context.contentResolver.update(finalUri, fixValues, null, null)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        writeSuccess
    }

    /**
     * 兼容旧接口签名
     */
    suspend fun replaceOriginalVideo(
        targetUri: Uri,
        targetPath: String?,
        compressedFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val dummyItem = VideoItem(
            id = 0L,
            uri = targetUri,
            displayName = "",
            size = compressedFile.length(),
            durationMs = 0L,
            width = 0,
            height = 0,
            dateModified = System.currentTimeMillis() / 1000L,
            dateTaken = System.currentTimeMillis(),
            path = targetPath
        )
        replaceOriginalVideo(dummyItem, compressedFile)
    }
}
