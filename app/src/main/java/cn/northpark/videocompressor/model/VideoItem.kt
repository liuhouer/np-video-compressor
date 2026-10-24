package cn.northpark.videocompressor.model

import android.net.Uri
import java.util.Locale

/**
 * 相册视频数据实体
 */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val path: String? = null,
    var isSelected: Boolean = false
) {
    /**
     * 格式化文件大小 (B, KB, MB, GB)
     */
    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
                else -> "$size B"
            }
        }

    /**
     * 格式化时长 (mm:ss 或 hh:mm:ss)
     */
    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }

    /**
     * 分辨率文本，例如 1920x1080
     */
    val resolutionText: String
        get() {
            return if (width > 0 && height > 0) {
                "${width}x${height}"
            } else {
                "未知分辨率"
            }
        }
}

/**
 * 排序方式
 */
enum class SortType(val displayName: String) {
    SIZE_DESC("文件大小 (从大到小)"),
    SIZE_ASC("文件大小 (从小到大)"),
    DURATION_DESC("视频时长 (从长到短)"),
    DATE_DESC("修改时间 (从新到旧)")
}
