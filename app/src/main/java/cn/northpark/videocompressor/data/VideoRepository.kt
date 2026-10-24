package cn.northpark.videocompressor.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import cn.northpark.videocompressor.model.SortType
import cn.northpark.videocompressor.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 相册视频数据仓库
 */
class VideoRepository(private val context: Context) {

    /**
     * 查询系统相册中的所有视频并按指定规则排序
     */
    suspend fun loadAllVideos(sortType: SortType = SortType.SIZE_DESC): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val videoList = mutableListOf<VideoItem>()

            val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATA
            )

            // 过滤有效文件 (文件大小大于0)
            val selection = "${MediaStore.Video.Media.SIZE} > 0"
            val selectionArgs: Array<String>? = null
            val sortOrder = "${MediaStore.Video.Media.SIZE} DESC"

            try {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                    val dateAddedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                    val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: "Video_$id.mp4"
                        val size = cursor.getLong(sizeColumn)
                        val duration = cursor.getLong(durationColumn)
                        var width = cursor.getInt(widthColumn)
                        var height = cursor.getInt(heightColumn)
                        var dateModified = cursor.getLong(dateModifiedColumn)
                        var dateTaken = if (dateTakenColumn != -1) cursor.getLong(dateTakenColumn) else 0L
                        val dateAdded = if (dateAddedColumn != -1) cursor.getLong(dateAddedColumn) else 0L
                        val path = if (dataColumn != -1) cursor.getString(dataColumn) else null

                        // 严密的时间相互校验与兜底机制
                        if (dateTaken <= 0L) {
                            if (dateModified > 0L) {
                                dateTaken = dateModified * 1000L
                            } else if (dateAdded > 0L) {
                                dateTaken = dateAdded * 1000L
                            } else if (!path.isNullOrBlank()) {
                                try {
                                    val f = File(path)
                                    if (f.exists()) dateTaken = f.lastModified()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (dateModified <= 0L) {
                            if (dateTaken > 0L) {
                                dateModified = dateTaken / 1000L
                            } else if (dateAdded > 0L) {
                                dateModified = dateAdded
                            }
                        }

                        val contentUri = ContentUris.withAppendedId(collection, id)

                        // 如果宽度或高度为0，尝试通过 MediaMetadataRetriever 补充获取
                        if (width <= 0 || height <= 0) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(context, contentUri)
                                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                val rotation = rotationStr?.toIntOrNull() ?: 0

                                val rawW = wStr?.toIntOrNull() ?: 0
                                val rawH = hStr?.toIntOrNull() ?: 0
                                if (rotation == 90 || rotation == 270) {
                                    width = rawH
                                    height = rawW
                                } else {
                                    width = rawW
                                    height = rawH
                                }
                                retriever.release()
                            } catch (_: Exception) {
                            }
                        }

                        videoList.add(
                            VideoItem(
                                id = id,
                                uri = contentUri,
                                displayName = name,
                                size = size,
                                durationMs = duration,
                                width = width,
                                height = height,
                                dateModified = dateModified,
                                dateTaken = dateTaken,
                                dateAdded = dateAdded,
                                path = path
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            sortVideos(videoList, sortType)
        }

    /**
     * 对视频列表进行内存排序
     */
    fun sortVideos(list: List<VideoItem>, sortType: SortType): List<VideoItem> {
        return when (sortType) {
            SortType.SIZE_DESC -> list.sortedByDescending { it.size }
            SortType.SIZE_ASC -> list.sortedBy { it.size }
            SortType.DURATION_DESC -> list.sortedByDescending { it.durationMs }
            SortType.DATE_DESC -> list.sortedByDescending { it.effectiveDateMs }
        }
    }
}
