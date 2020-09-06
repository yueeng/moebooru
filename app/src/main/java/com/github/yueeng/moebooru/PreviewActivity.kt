package com.github.yueeng.moebooru

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.yueeng.moebooru.databinding.FragmentPreviewBinding
import com.github.yueeng.moebooru.databinding.PreviewItemBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter


class PreviewActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment
            ?: PreviewFragment().also { it.arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class PreviewFragment : Fragment() {
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    private val index by lazy { arguments?.getInt("index") ?: -1 }
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentPreviewBinding.inflate(inflater, container, false).also { binding ->
            binding.pager.adapter = adapter
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    .distinctUntilChangedBy { it.refresh }
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect { binding.pager.post { binding.pager.setCurrentItem(index, false) } }
            }
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
        }.root

    class ImageHolder(private val binding: PreviewItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.image1.setBitmapDecoderClass(GlideDecoder::class.java)
            binding.image1.setRegionDecoderClass(GlideRegionDecoder::class.java)
        }

        fun bind(item: JImageItem) {
            binding.progress.isVisible = true
            DispatchingProgressBehavior.expect(item.sample_url, object : UIonProgressListener {
                override val granualityPercentage: Float
                    get() = 1F

                override fun onProgress(bytesRead: Long, expectedLength: Long) {
                    (100 * bytesRead / expectedLength).toInt().let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            binding.progress.setProgress(it, true)
                        } else {
                            binding.progress.progress = it
                        }
                    }
                }
            })
            binding.image1.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onImageLoaded() {
                    DispatchingProgressBehavior.forget(item.sample_url)
                    binding.progress.isInvisible = true
                }
            })
            binding.image1.setImage(ImageSource.uri(item.sample_url).dimensions(item.sample_width, item.sample_height), ImageSource.uri(item.preview_url))
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(ImageItemDiffItemCallback()) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(PreviewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}
