package cn.northpark.videocompressor.model

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
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
    val dateModified: Long, // 单位：秒 (自 1970-01-01)
    val dateTaken: Long = 0L, // 单位：毫秒 (自 1970-01-01，相册首选展示时间)
    val dateAdded: Long = 0L, // 单位：秒 (自 1970-01-01)
    val path: String? = null,
    var isSelected: Boolean = false
) {
    /**
     * 获取视频最真实有效的绝对时间戳 (毫秒)
     * 优先采用拍摄时间 (dateTaken)，兜底使用修改时间 (dateModified * 1000) 或添加时间 (dateAdded * 1000)
     */
    val effectiveDateMs: Long
        get() = when {
            dateTaken > 0L -> dateTaken
            dateModified > 0L -> dateModified * 1000L
            dateAdded > 0L -> dateAdded * 1000L
            else -> 0L
        }

    /**
     * 格式化展示的日期文本，如 "2024-03-15 18:30"
     */
    val formattedDate: String
        get() {
            val ms = effectiveDateMs
            if (ms <= 0L) return "未知日期"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(ms))
        }

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
    DATE_DESC("视频日期 (从新到旧)")
}
