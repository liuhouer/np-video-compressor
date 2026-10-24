package cn.northpark.videocompressor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.northpark.videocompressor.compress.VideoCompressor
import cn.northpark.videocompressor.compress.VideoReplacer
import cn.northpark.videocompressor.databinding.ActivityMainBinding
import cn.northpark.videocompressor.databinding.DialogAboutBinding
import cn.northpark.videocompressor.databinding.DialogProgressBinding
import cn.northpark.videocompressor.databinding.DialogResultBinding
import cn.northpark.videocompressor.model.SortType
import cn.northpark.videocompressor.model.VideoItem
import cn.northpark.videocompressor.ui.MainViewModel
import cn.northpark.videocompressor.ui.VideoAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var compressor: VideoCompressor
    private lateinit var replacer: VideoReplacer

    // 待执行替换的原视频列表缓存 (用于 Android 11+ 请求写授权后继续执行)
    private var pendingVideosToCompress: List<VideoItem> = emptyList()
    private var compressionJob: Job? = null

    // 相册读取权限请求器
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadVideos()
        } else {
            showPermissionDeniedUI()
        }
    }

    // Android 11+ (API 30+) 系统原生修改/写入公共媒体文件授权请求器
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 用户授权允许修改原视频，开始执行压缩替换流程
            if (pendingVideosToCompress.isNotEmpty()) {
                startBatchCompressionAndReplace(pendingVideosToCompress)
            }
        } else {
            Toast.makeText(this, "用户取消了原视频替换授权，操作已中止", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        compressor = VideoCompressor(this)
        replacer = VideoReplacer(this)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeViewModel()

        checkAndRequestPermission()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { selectedCount, totalBytes ->
            updateBottomSummary(selectedCount, totalBytes)
        }
        binding.rvVideos.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = videoAdapter
        }
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            checkAndRequestPermission()
        }

        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        binding.btnStartCompress.setOnClickListener {
            val selected = videoAdapter.getSelectedVideos()
            if (selected.isEmpty()) {
                Toast.makeText(this, "请先勾选需要压缩的视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmStartCompression(selected)
        }
    }

    private fun observeViewModel() {
        viewModel.videoList.observe(this) { list ->
            binding.swipeRefresh.isRefreshing = false
            videoAdapter.clearSelection()
            videoAdapter.submitList(list)

            if (list.isNullOrEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.tvEmptyMessage.text = getString(R.string.no_videos_found)
                binding.btnGrantPermission.visibility = View.GONE
            } else {
                binding.layoutEmpty.visibility = View.GONE
            }
            updateBottomSummary(0, 0)
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.swipeRefresh.isRefreshing = loading
        }
    }

    private fun updateBottomSummary(selectedCount: Int, totalBytes: Long) {
        if (selectedCount > 0) {
            val formatted = formatBytes(totalBytes)
            binding.tvSelectedSummary.text = getString(R.string.selected_summary, selectedCount, formatted)
            binding.btnStartCompress.isEnabled = true
            binding.btnStartCompress.alpha = 1.0f
        } else {
            binding.tvSelectedSummary.text = "请勾选要压缩的相册视频"
            binding.btnStartCompress.isEnabled = false
            binding.btnStartCompress.alpha = 0.5f
        }
    }

    /**
     * 权限检查
     */
    private fun checkAndRequestPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            binding.layoutEmpty.visibility = View.GONE
            viewModel.loadVideos()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun showPermissionDeniedUI() {
        binding.swipeRefresh.isRefreshing = false
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.tvEmptyMessage.text = getString(R.string.permission_denied_tip)
        binding.btnGrantPermission.visibility = View.VISIBLE
    }

    /**
     * 弹出二次确认提示对话框
     */
    private fun confirmStartCompression(selectedVideos: List<VideoItem>) {
        val totalSizeStr = formatBytes(selectedVideos.sumOf { it.size })
        MaterialAlertDialogBuilder(this)
            .setTitle("确认压缩并替换？")
            .setMessage("已选 ${selectedVideos.size} 个视频 (总大小 $totalSizeStr)。\n\n压缩过程将严格保持原有画面分辨率，并通过 HEVC 高效编码最大化缩减文件大小。\n\n压缩完成后将直接替换覆盖相册源文件，释放存储空间。")
            .setPositiveButton("立即开始") { _, _ ->
                proceedWithScopedStorageCheck(selectedVideos)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Android 11+ 分区存储授权检查
     */
    private fun proceedWithScopedStorageCheck(selectedVideos: List<VideoItem>) {
        pendingVideosToCompress = selectedVideos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = selectedVideos.map { it.uri }
            val pendingIntent = replacer.createWriteRequestIntent(uris)
            if (pendingIntent != null) {
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                writePermissionLauncher.launch(request)
                return
            }
        }
        // Android 10 及以下直接开始
        startBatchCompressionAndReplace(selectedVideos)
    }

    /**
     * 启动批量压缩与替换流水线
     */
    private fun startBatchCompressionAndReplace(selectedVideos: List<VideoItem>) {
        val progressBinding = DialogProgressBinding.inflate(layoutInflater)
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(progressBinding.root)
            .setCancelable(false)
            .setNegativeButton("取消并中止") { dialog, _ ->
                compressionJob?.cancel()
                dialog.dismiss()
                Toast.makeText(this, "压缩已中止", Toast.LENGTH_SHORT).show()
            }
            .create()

        progressDialog.show()

        var totalOriginalBytes = 0L
        var totalCompressedBytes = 0L
        var completedCount = 0

        compressionJob = lifecycleScope.launch {
            for ((index, video) in selectedVideos.withIndex()) {
                val currentIndex = index + 1
                progressBinding.tvProgressTitle.text = "正在压缩并替换 ($currentIndex/${selectedVideos.size})"
                progressBinding.tvCurrentVideoName.text = video.displayName
                progressBinding.progressBar.progress = 0
                progressBinding.tvPercent.text = "0%"
                progressBinding.tvStatusDesc.text = "正在以原始分辨率 (${video.resolutionText}) 进行HEVC极限转码..."

                // 执行压缩
                val result = compressor.compressVideo(video) { percent ->
                    progressBinding.progressBar.progress = percent
                    progressBinding.tvPercent.text = "$percent%"
                }

                if (result.success && result.outputFile != null && result.outputFile.exists()) {
                    totalOriginalBytes += result.originalSize

                    // 仅当压缩后文件确实更小时才覆盖替换，防止体积反弹
                    if (result.compressedSize < result.originalSize) {
                        progressBinding.tvStatusDesc.text = "转码成功，正在安全替换相册源视频..."
                        val replaced = replacer.replaceOriginalVideo(video, result.outputFile)
                        if (replaced) {
                            totalCompressedBytes += result.compressedSize
                        } else {
                            totalCompressedBytes += result.originalSize
                        }
                    } else {
                        // 压缩后反而没有更小，保留原文件
                        totalCompressedBytes += result.originalSize
                    }

                    // 删除临时转码缓存
                    result.outputFile.delete()
                    completedCount++

                    val savedBytes = (totalOriginalBytes - totalCompressedBytes).coerceAtLeast(0)
                    progressBinding.tvSavedSoFar.text = "已累计节省空间: ${formatBytes(savedBytes)}"
                } else {
                    Toast.makeText(this@MainActivity, "视频 ${video.displayName} 压缩失败: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
                }
            }

            progressDialog.dismiss()

            // 展示最终战果结算弹窗
            showCompletionDialog(totalOriginalBytes, totalCompressedBytes, completedCount)

            // 重新加载刷新列表
            videoAdapter.clearSelection()
            viewModel.loadVideos()
        }
    }

    /**
     * 压缩完成结算对话框
     */
    private fun showCompletionDialog(totalOriginal: Long, totalCompressed: Long, count: Int) {
        val resultBinding = DialogResultBinding.inflate(layoutInflater)
        val savedBytes = (totalOriginal - totalCompressed).coerceAtLeast(0)
        val savedPercent = if (totalOriginal > 0) ((savedBytes.toDouble() / totalOriginal) * 100).toInt() else 0

        resultBinding.tvResultSavedSize.text = "已释放 ${formatBytes(savedBytes)}"
        resultBinding.tvResultSavedPercent.text = "成功处理 $count 个视频 · 体积缩减约 $savedPercent% · 原分辨率无损"
        resultBinding.tvOriginalTotal.text = formatBytes(totalOriginal)
        resultBinding.tvCompressedTotal.text = formatBytes(totalCompressed)

        MaterialAlertDialogBuilder(this)
            .setView(resultBinding.root)
            .setPositiveButton("太棒了", null)
            .show()
    }

    /**
     * 弹出“关于”对话框
     */
    private fun showAboutDialog() {
        val aboutBinding = DialogAboutBinding.inflate(layoutInflater)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            aboutBinding.tvAboutVersion.text = "版本 v${pInfo.versionName}"
        } catch (_: Exception) {
            aboutBinding.tvAboutVersion.text = "版本 v1.0.0"
        }

        MaterialAlertDialogBuilder(this)
            .setView(aboutBinding.root)
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                val selectedCount = videoAdapter.getSelectedVideos().size
                val totalCount = videoAdapter.currentList.size
                val willSelectAll = totalCount > 0 && selectedCount < totalCount
                videoAdapter.selectAll(willSelectAll)
                item.title = if (willSelectAll) getString(R.string.deselect_all) else getString(R.string.select_all)
                true
            }
            R.id.sort_size_desc -> {
                viewModel.setSortType(SortType.SIZE_DESC)
                Toast.makeText(this, "已按大小 (从大到小) 排序", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.sort_size_asc -> {
                viewModel.setSortType(SortType.SIZE_ASC)
                Toast.makeText(this, "已按大小 (从小到大) 排序", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.sort_duration_desc -> {
                viewModel.setSortType(SortType.DURATION_DESC)
                Toast.makeText(this, "已按时长 (从长到短) 排序", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.sort_date_desc -> {
                viewModel.setSortType(SortType.DATE_DESC)
                Toast.makeText(this, "已按修改时间排序", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
