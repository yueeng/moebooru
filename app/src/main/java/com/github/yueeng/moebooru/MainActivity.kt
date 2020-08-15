package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.yueeng.moebooru.databinding.ActivityMainBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding

class MainActivity : AppCompatActivity() {
    private val adapter by lazy { ImageAdapter() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.recycler.adapter = adapter
        Service.instance.post().observe(this) {
            when (it) {
                is ApiSuccessResponse -> {
                    lifecycleScope.launchWhenCreated {
                        adapter.submitData(PagingData.from(it.body))
                    }
                }
            }
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
