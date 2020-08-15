package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataAdapter
import androidx.paging.PagingSource
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.github.yueeng.moebooru.databinding.ActivityMainBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding
import kotlinx.coroutines.flow.collectLatest

class ImageDataSource : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, limit = params.loadSize)
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle) : ViewModel() {
    private val source = ImageDataSource()

    val posts = Pager(PagingConfig(20)) { source }.flow
}

class ImageViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
        @Suppress("UNCHECKED_CAST")
        return ImageViewModel(handle) as T
    }
}

class MainActivity : AppCompatActivity() {
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by viewModels { ImageViewModelFactory(this, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.recycler.adapter = adapter
        lifecycleScope.launchWhenCreated {
            model.posts.collectLatest { adapter.submitData(it) }
        }
    }

    class ImageHolder(val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root)

    class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diff) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
            holder.binding.image = getItem(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        companion object {
            val diff = object : DiffUtil.ItemCallback<JImageItem>() {
                override fun areItemsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem == newItem
            }
        }
    }
}
