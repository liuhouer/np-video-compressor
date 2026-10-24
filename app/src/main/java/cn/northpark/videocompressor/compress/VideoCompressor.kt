package cn.northpark.videocompressor.compress

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import cn.northpark.videocompressor.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 视频压缩引擎
 * 核心特性：
 * 1. 严格保持源视频分辨率 (Width x Height)
 * 2. 优先采用 H.265 (HEVC) 高效压缩，最大化缩减文件体积
 * 3. 动态码率智能下压
 */
class VideoCompressor(private val context: Context) {

    data class CompressResult(
        val success: Boolean,
        val originalSize: Long,
        val compressedSize: Long,
        val outputFile: File?,
        val errorMessage: String? = null
    ) {
        val savedBytes: Long
            get() = (originalSize - compressedSize).coerceAtLeast(0)

        val savedPercent: Int
            get() = if (originalSize > 0) {
                ((savedBytes.toDouble() / originalSize) * 100).toInt()
            } else 0
    }

    /**
     * 压缩单个视频文件
     * @param videoItem 待压缩视频信息
     * @param onProgress 进度回调 (0-100)
     */
    suspend fun compressVideo(
        videoItem: VideoItem,
        onProgress: (Int) -> Unit
    ): CompressResult = withContext(Dispatchers.IO) {
        val originalSize = videoItem.size
        val outputDir = File(context.cacheDir, "compressed_temp")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val tempOutputFile = File(outputDir, "comp_${System.currentTimeMillis()}_${videoItem.id}.mp4")

        try {
            // 获取原视频的实际码率和宽高
            val (width, height, originalBitrate) = getVideoProperties(videoItem.uri, videoItem.width, videoItem.height, videoItem.durationMs, originalSize)
            val useHevc = CodecUtils.isHevcEncoderSupported()
            val targetBitrate = CodecUtils.calculateTargetBitrate(width, height, originalBitrate, useHevc)

            val mimeType = if (useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

            // 配置视频编码参数 (保持原始分辨率，应用目标码率)
            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(targetBitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setEnableFallback(true) // 若指定编码器不支持则平滑降级
                .build()

            // 构建转码请求：指定目标视频编码与音频转码 (AAC 96kbps)
            val transformationRequest = TransformationRequest.Builder()
                .setVideoMimeType(mimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            // 创建待处理 MediaItem (不加任何分辨率裁剪滤镜，原汁原味保持原始分辨率)
            val mediaItem = MediaItem.fromUri(videoItem.uri)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

            // 开始使用协程驱动 Transformer 转码
            runTransformer(
                context = context,
                editedMediaItem = editedMediaItem,
                transformationRequest = transformationRequest,
                encoderFactory = encoderFactory,
                outputFile = tempOutputFile,
                onProgress = onProgress
            )

            val compressedSize = tempOutputFile.length()

            CompressResult(
                success = true,
                originalSize = originalSize,
                compressedSize = compressedSize,
                outputFile = tempOutputFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempOutputFile.exists()) {
                tempOutputFile.delete()
            }
            CompressResult(
                success = false,
                originalSize = originalSize,
                compressedSize = 0,
                outputFile = null,
                errorMessage = e.localizedMessage ?: "压缩失败"
            )
        }
    }

    /**
     * 挂起函数执行 Media3 Transformer 转码任务并轮询上报进度
     */
    private suspend fun runTransformer(
        context: Context,
        editedMediaItem: EditedMediaItem,
        transformationRequest: TransformationRequest,
        encoderFactory: DefaultEncoderFactory,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var isFinished = false

            val transformer = Transformer.Builder(context.applicationContext)
                .setTransformationRequest(transformationRequest)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isFinished = true
                        onProgress(100)
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        isFinished = true
                        if (continuation.isActive) {
                            continuation.resumeWithException(exportException)
                        }
                    }
                })
                .build()

            val progressHolder = ProgressHolder()
            val progressJob = CoroutineScope(Dispatchers.Main).launch {
                while (!isFinished && continuation.isActive) {
                    val progressState = transformer.getProgress(progressHolder)
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress)
                    }
                    delay(250)
                }
            }

            continuation.invokeOnCancellation {
                progressJob.cancel()
                transformer.cancel()
            }

            // 启动转码任务
            transformer.start(editedMediaItem, outputFile.absolutePath)
        }
    }

    /**
     * 辅助获取视频宽高与码率
     */
    private fun getVideoProperties(
        uri: Uri,
        fallbackWidth: Int,
        fallbackHeight: Int,
        durationMs: Long,
        fileSize: Long
    ): Triple<Int, Int, Long> {
        var width = fallbackWidth
        var height = fallbackHeight
        var bitrate = 0L

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val b = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            if (w != null && h != null && w > 0 && h > 0) {
                if (rot == 90 || rot == 270) {
                    width = h
                    height = w
                } else {
                    width = w
                    height = h
                }
            }
            if (b != null && b > 0) {
                bitrate = b
            }
            retriever.release()
        } catch (_: Exception) {
        }

        // 如果没有提取到码率，使用大小和时长反算原码率
        if (bitrate <= 0 && durationMs > 0) {
            bitrate = (fileSize * 8 * 1000) / durationMs
        }

        if (width <= 0) width = 1920
        if (height <= 0) height = 1080

        return Triple(width, height, bitrate)
    }
}
