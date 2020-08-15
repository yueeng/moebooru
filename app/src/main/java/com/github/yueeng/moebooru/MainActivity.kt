package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataAdapter
import androidx.paging.PagingSource
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.yueeng.moebooru.databinding.ActivityMainBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding
import kotlinx.coroutines.flow.collectLatest
import retrofit2.await

class ImageDataSource : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, limit = params.loadSize).await()
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel : ViewModel() {
    private val source = ImageDataSource()

    val posts = Pager(PagingConfig(20)) { source }.flow
}

class MainActivity : AppCompatActivity() {
    private val adapter by lazy { ImageAdapter() }
    private val model = ImageViewModel()
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
