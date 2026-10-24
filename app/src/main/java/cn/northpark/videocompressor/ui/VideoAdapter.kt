package cn.northpark.videocompressor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import cn.northpark.videocompressor.databinding.ItemVideoBinding
import cn.northpark.videocompressor.model.VideoItem

class VideoAdapter(
    private val onSelectionChanged: (selectedCount: Int, totalSelectedBytes: Long) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    // 使用选中的视频 ID 集合维护选中状态，防止复选框事件拦截与列表滑动错位
    private val selectedIds = mutableSetOf<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, selectedIds.contains(item.id))
    }

    /**
     * 全选或全部取消
     */
    fun selectAll(select: Boolean) {
        if (select) {
            currentList.forEach { selectedIds.add(it.id) }
        } else {
            selectedIds.clear()
        }
        notifyItemRangeChanged(0, currentList.size, PAYLOAD_SELECTION)
        dispatchSelectionUpdate()
    }

    /**
     * 获取所有选中的视频项
     */
    fun getSelectedVideos(): List<VideoItem> {
        return currentList.filter { selectedIds.contains(it.id) }
    }

    /**
     * 清空选中项 (例如压缩完成后重置)
     */
    fun clearSelection() {
        selectedIds.clear()
        notifyItemRangeChanged(0, currentList.size, PAYLOAD_SELECTION)
        dispatchSelectionUpdate()
    }

    private fun dispatchSelectionUpdate() {
        val selected = getSelectedVideos()
        val totalBytes = selected.sumOf { it.size }
        onSelectionChanged(selected.size, totalBytes)
    }

    private fun toggleSelection(item: VideoItem, position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val isNowSelected = if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
            false
        } else {
            selectedIds.add(item.id)
            true
        }
        item.isSelected = isNowSelected
        notifyItemChanged(position, PAYLOAD_SELECTION)
        dispatchSelectionUpdate()
    }

    override fun onBindViewHolder(
        holder: VideoViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val item = getItem(position)
            holder.updateSelection(selectedIds.contains(item.id))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem, isSelected: Boolean) {
            binding.tvTitle.text = item.displayName
            binding.tvSize.text = item.formattedSize
            binding.tvDuration.text = item.formattedDuration
            binding.tvResolution.text = item.resolutionText
            updateSelection(isSelected)

            // 加载视频缩略图
            Glide.with(binding.root.context)
                .load(item.uri)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .into(binding.ivThumbnail)

            // 点击整张卡片切换选中状态
            binding.cardVideo.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(getItem(pos), pos)
                }
            }

            // 关键修复：复选框自身也显式绑定点击事件，确保点在复选框上 100% 触发状态更新
            binding.cbSelect.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    toggleSelection(getItem(pos), pos)
                }
            }
        }

        fun updateSelection(isSelected: Boolean) {
            binding.cbSelect.isChecked = isSelected
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"
    }

    class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }
}
