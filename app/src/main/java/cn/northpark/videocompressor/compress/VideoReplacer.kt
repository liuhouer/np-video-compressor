package cn.northpark.videocompressor.compress

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 视频替换工具
 * 完美适配 Android 10/11/12/13/14+ 分区存储 (Scoped Storage)
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
     * 将临时压缩好的视频文件覆盖替换到相册原始视频 Uri
     * @param targetUri 相册原视频 Uri
     * @param targetPath 原视频文件路径 (可选)
     * @param compressedFile 压缩后的新文件
     */
    suspend fun replaceOriginalVideo(
        targetUri: Uri,
        targetPath: String?,
        compressedFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!compressedFile.exists() || compressedFile.length() == 0L) {
            return@withContext false
        }

        var writeSuccess = false

        // 策略1：通过 ContentResolver 打开 "wt" 模式流覆盖写入原相册 Uri (Android 10+ 推荐方式)
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

        // 策略2：若通过 Uri 写入受阻且 targetPath 存在并可直接写，尝试直接覆盖文件
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

        // 替换成功后，刷新媒体库数据库信息
        if (writeSuccess) {
            try {
                val newSize = compressedFile.length()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.SIZE, newSize)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }
                context.contentResolver.update(targetUri, values, null, null)

                // 触发系统扫描刷新
                if (!targetPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetPath),
                        arrayOf("video/mp4"),
                        null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        writeSuccess
    }
}
