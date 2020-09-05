package com.github.yueeng.moebooru

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import java.util.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? MainFragment ?: MainFragment()
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class MainFragment : Fragment() {
    private val adapter by lazy { PagerAdapter(this) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentMainBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.pager.adapter = adapter
            TabLayoutMediator(binding.tab, binding.pager) { tab, position -> tab.text = adapter.data[position].first }.attach()
        }.root

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val data = listOf(
            "Newest" to Q(),
            "Day" to Q().popular_by_day(Date()),
            "Week" to Q().popular_by_week(Date()),
            "Month" to Q().popular_by_month(Date())
        )

        override fun getItemCount(): Int = data.size

        override fun createFragment(position: Int): Fragment = ListFragment().apply {
            arguments = bundleOf("query" to data[position].second.toString(), "name" to data[position].first)
        }
    }
}

class ListFragment : Fragment() {
    private val query by lazy { Q(arguments?.getString("query") ?: "") }
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentListBinding.inflate(inflater, container, false).also { binding ->
            binding.recycler.adapter = adapter.withLoadStateFooter(HeaderAdapter(adapter))
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    // Only emit when REFRESH LoadState for RemoteMediator changes.
                    .distinctUntilChangedBy { it.refresh }
                    // Only react to cases where Remote REFRESH completes i.e., NotLoading.
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect { binding.recycler.scrollToPosition(0) }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
        }.root

    class ImageHolder(val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.sample_width, item.sample_height)
            bindImageFromUrl(binding.image1, item.sample_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(ImageItemDiffItemCallback()) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
            holder.bind(getItem(position)!!)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)).apply {
                binding.root.setOnClickListener {
                    startActivity(
                        Intent(context, PreviewActivity::class.java)
                            .putExtra("query", query.toString())
                            .putExtra("index", bindingAdapterPosition)
                    )
                }
            }
    }

    class HeaderHolder(parent: ViewGroup, private val retryCallback: () -> Unit) :
        RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.state_item, parent, false)) {

        private val binding = StateItemBinding.bind(itemView)
        private val progressBar = binding.progressBar
        private val errorMsg = binding.errorMsg
        private val retry = binding.retryButton.also { it.setOnClickListener { retryCallback() } }

        fun bindTo(loadState: LoadState) {
            progressBar.isVisible = loadState is LoadState.Loading
            retry.isVisible = loadState is LoadState.Error
            when (loadState) {
                is LoadState.Error -> {
                    errorMsg.isVisible = true
                    errorMsg.text = loadState.error.message
                }
                is LoadState.NotLoading -> {
                    errorMsg.isVisible = loadState.endOfPaginationReached
                    errorMsg.text = if (loadState.endOfPaginationReached) "END" else null
                }
                else -> {
                    errorMsg.isVisible = false
                    errorMsg.text = null
                }
            }
        }
    }

    class HeaderAdapter(private val adapter: ImageAdapter) : LoadStateAdapter<HeaderHolder>() {
        override fun displayLoadStateAsItem(loadState: LoadState): Boolean = when (loadState) {
            is LoadState.NotLoading -> loadState.endOfPaginationReached
            else -> super.displayLoadStateAsItem(loadState)
        }

        override fun onBindViewHolder(holder: HeaderHolder, loadState: LoadState) = holder.bindTo(loadState)

        override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): HeaderHolder = HeaderHolder(parent) { adapter.retry() }
    }
}

class PreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment
            ?: PreviewFragment().also { it.arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class PreviewFragment : Fragment() {
    private val query by lazy { Q(arguments?.getString("query") ?: "") }
    private val index by lazy { arguments?.getInt("index") ?: -1 }
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentPreviewBinding.inflate(inflater, container, false).also { binding ->
            binding.pager.adapter = adapter
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    // Only emit when REFRESH LoadState for RemoteMediator changes.
                    .distinctUntilChangedBy { it.refresh }
                    // Only react to cases where Remote REFRESH completes i.e., NotLoading.
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect { binding.pager.post { binding.pager.setCurrentItem(index, false) } }
            }
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
        }.root

    class ImageHolder(val binding: PreviewItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.sample_width, item.sample_height)
            bindImageFromUrl(binding.image1, item.sample_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(ImageItemDiffItemCallback()) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
            holder.bind(getItem(position)!!)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(PreviewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}

class ImageItemDiffItemCallback : DiffUtil.ItemCallback<JImageItem>() {
    override fun areItemsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem == newItem
}