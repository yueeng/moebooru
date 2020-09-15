package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import java.util.*

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? MainFragment ?: MainFragment()
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class MainFragment : Fragment() {
    private val adapter by lazy { PagerAdapter(this) }

    @SuppressLint("RestrictedApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentMainBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.pager.adapter = adapter
            TabLayoutMediator(binding.tab, binding.pager) { tab, position -> tab.text = adapter.data[position].first }.attach()

            lifecycleScope.launchWhenCreated {
                Db.db.invalidationTracker.createLiveData(arrayOf("tags"), true) { }.asFlow().collectLatest {
                    val tags = Db.tags.tags(true)
                    adapter.data.removeAll(adapter.data.drop(1))
                    adapter.data.addAll(tags.map { it.name to Q(it.tag) })
                    adapter.notifyDataSetChanged()
                }
            }
        }.root

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val data = mutableListOf("Popular" to Q().order(Q.Order.score).date(Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }.time, Q.Value.Op.ge))
        override fun getItemId(position: Int): Long = data[position].hashCode().toLong()
        override fun getItemCount(): Int = data.size
        override fun createFragment(position: Int): Fragment = ImageFragment().apply {
            arguments = bundleOf("query" to data[position].second, "name" to data[position].first)
        }
    }

}

class ListActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? ListFragment
            ?: ListFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class ListFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentListBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            arguments?.getParcelable<Q>("query")?.toString()?.let { requireActivity().title = it.toTitleCase() }
            val fragment = childFragmentManager.findFragmentById(R.id.container) as? ImageFragment
                ?: ImageFragment().also { it.arguments = arguments }
            childFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
        }.root
}

class ImageDataSource(private val query: Q? = Q()) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, Q(query).rating(Q.Rating.safe), limit = params.loadSize)
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    val posts = Pager(PagingConfig(20, initialLoadSize = 20)) { ImageDataSource(defaultArgs?.getParcelable("query")) }
        .flow.cachedIn(viewModelScope)
}

class ImageViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ImageViewModel(handle, defaultArgs) as T
}

class ImageFragment : Fragment() {
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.search -> true.also {
            startActivity(Intent(requireContext(), QueryActivity::class.java).putExtra("query", query))
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentImageBinding.inflate(inflater, container, false).also { binding ->
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter.withLoadStateFooter(HeaderAdapter(adapter))
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
        }.root

    class ImageHolder(val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.preview_width, item.preview_height)
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)).apply {
                binding.root.setOnClickListener {
                    startActivity(
                        Intent(context, PreviewActivity::class.java)
                            .putExtra("query", query)
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
