package cn.northpark.videocompressor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.northpark.videocompressor.data.VideoRepository
import cn.northpark.videocompressor.model.SortType
import cn.northpark.videocompressor.model.VideoItem
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    private val _videoList = MutableLiveData<List<VideoItem>>()
    val videoList: LiveData<List<VideoItem>> = _videoList

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _currentSortType = MutableLiveData(SortType.SIZE_DESC)
    val currentSortType: LiveData<SortType> = _currentSortType

    /**
     * 刷新加载相册视频
     */
    fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            val sort = _currentSortType.value ?: SortType.SIZE_DESC
            val list = repository.loadAllVideos(sort)
            _videoList.value = list
            _isLoading.value = false
        }
    }

    /**
     * 切换排序方式
     */
    fun setSortType(sortType: SortType) {
        if (_currentSortType.value == sortType) return
        _currentSortType.value = sortType
        val current = _videoList.value ?: return
        val sorted = repository.sortVideos(current, sortType)
        _videoList.value = sorted
    }
}
