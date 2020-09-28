package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.ChangeTransform
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import java.util.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? MainFragment ?: MainFragment()
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class MainFragment : Fragment() {
    private val adapter by lazy { PagerAdapter(this) }
    private lateinit var binding: FragmentMainBinding

    @SuppressLint("RestrictedApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentMainBinding.inflate(inflater, container, false).also { binding ->
            this.binding = binding
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.pager.adapter = adapter
            TabLayoutMediator(binding.tab, binding.pager) { tab, position -> tab.text = adapter.data[position].first }.attach()
            lifecycleScope.launchWhenCreated {
                Db.tags.tagsWithIndex(true).collectLatest { tags ->
                    adapter.data.removeAll(adapter.data.drop(1))
                    adapter.data.addAll(tags.map { it.name to Q(it.tag) })
                    adapter.notifyDataSetChanged()
                }
            }
            binding.button1.setOnClickListener {
                val ex = binding.button1.rotation == 45F
                val axis = MaterialSharedAxis(MaterialSharedAxis.Y, !ex)
                val set = TransitionSet().addTransition(axis).addTransition(ChangeTransform())
                TransitionManager.beginDelayedTransition(binding.root, set)
                binding.button1.rotation = if (ex) 0F else 45F
                binding.button2.isInvisible = ex
                binding.button3.isInvisible = ex
                binding.button4.isInvisible = ex
            }
            listOf(binding.button2, binding.button3, binding.button4).forEach { fab ->
                fab.setOnClickListener {
                    val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), fab, "shared_element_container")
                    startActivity(Intent(requireContext(), PopularActivity::class.java).putExtra("type", "${it.tag}"), options.toBundle())
                }
            }
        }.root

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val data = mutableListOf("Popular" to Q().order(Q.Order.score).date(Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }.time, Q.Value.Op.ge))
        override fun getItemId(position: Int): Long = data[position].hashCode().toLong()
        override fun containsItem(itemId: Long): Boolean = data.map { it.hashCode().toLong() }.contains(itemId)
        override fun getItemCount(): Int = data.size
        override fun createFragment(position: Int): Fragment = ImageFragment().apply {
            arguments = bundleOf("query" to data[position].second, "name" to data[position].first)
        }
    }

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
            val query = adapter.data[binding.pager.currentItem].second
            val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), requireView().findViewById(item.itemId), "shared_element_container")
            startActivity(Intent(requireContext(), QueryActivity::class.java).putExtra("query", query), options.toBundle())
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class ListActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? ListFragment
            ?: ListFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class ListFragment : Fragment() {
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentListBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            arguments?.getParcelable<Q>("query")?.toString()?.let { requireActivity().title = it.toTitleCase() }
            val fragment = childFragmentManager.findFragmentById(R.id.container) as? ImageFragment
                ?: ImageFragment().also { it.arguments = arguments }
            childFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
        }.root

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
            val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), requireView().findViewById(item.itemId), "shared_element_container")
            startActivity(Intent(requireContext(), QueryActivity::class.java).putExtra("query", query), options.toBundle())
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class ImageDataSource(private val query: Q? = Q(), private val begin: Int = 1, private val call: ((Int) -> Unit)? = null) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: begin
        val posts = Service.instance.post(page = key, Q(query), limit = params.loadSize)
        call?.invoke(key)
        val prev = if (posts.isNotEmpty()) (key - 1).takeIf { it > 0 } else null
        val next = if (posts.size == params.loadSize) key + 1 else null
        LoadResult.Page(posts, prev, next)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    companion object {
        const val pageSize = 20
    }

    val index = handle.getLiveData<Int>("index")
    val min = handle.getLiveData("min", 1)
    val max = handle.getLiveData<Int>("max")
    val query = handle.getLiveData<Q>("query", defaultArgs?.getParcelable("query"))
    val posts = Pager(PagingConfig(pageSize, initialLoadSize = pageSize * 2)) {
        max.postValue(0)
        ImageDataSource(query.value, min.value ?: 1) {
            min.postValue(min(it, min.value!!))
            max.postValue(max(it, max.value!!))
        }
    }.flow.cachedIn(viewModelScope)
}

class ImageViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ImageViewModel(handle, defaultArgs) as T
}

class ImageFragment : Fragment() {
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    private val offset = MutableLiveData<Int>()
    private val sum = MutableLiveData<Int>()

    @OptIn(FlowPreview::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentImageBinding.inflate(inflater, container, false).also { binding ->
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter.withLoadStateHeaderAndFooter(HeaderAdapter(adapter), FooterAdapter(adapter))
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                    sum.postValue(adapter.itemCount)
                }
            }
            lifecycleScope.launchWhenCreated {
                val flow1 = model.index.asFlow().distinctUntilChanged()
                val flow2 = model.min.asFlow().distinctUntilChanged()
                val flow3 = model.max.asFlow().distinctUntilChanged()
                flowOf(flow1, flow2, flow3).flattenMerge(3).collectLatest {
                    val index = model.index.value ?: return@collectLatest
                    val min = model.min.value ?: return@collectLatest
                    val max = model.max.value ?: return@collectLatest
                    @SuppressLint("SetTextI18n")
                    binding.text1.text = "${min(index + min, max)}/$max"
                    binding.layout1.isInvisible = max == 0
                }
            }
            lifecycleScope.launchWhenCreated {
                sum.asFlow().distinctUntilChanged().collectLatest {
                    binding.progress.max = it
                }
            }
            lifecycleScope.launchWhenCreated {
                offset.asFlow().distinctUntilChanged().collectLatest {
                    binding.progress.setProgressCompat(it)
                }
            }
            binding.recycler.isVerticalScrollBarEnabled = true
            binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    (view.layoutManager as? StaggeredGridLayoutManager)?.findFirstCompletelyVisibleItemPositions(null)?.minOrNull()?.let {
                        model.index.postValue(it / ImageViewModel.pageSize)
                    }
                    (view.layoutManager as? StaggeredGridLayoutManager)?.findLastCompletelyVisibleItemPositions(null)?.maxOrNull()?.let {
                        offset.postValue(it)
                    }
                }
            })
            binding.button1.setOnClickListener {
                val edit = SimpleInputBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(requireContext()).setView(edit.root)
                    .setTitle(R.string.app_page_jump)
                    .setPositiveButton(R.string.app_ok) { _, _ ->
                        edit.edit1.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let {
                            if (model.min.value!! > it || model.max.value!! < it) {
                                model.min.value = it
                                adapter.refresh()
                            } else {
                                binding.recycler.scrollToPosition((it - model.min.value!!) * ImageViewModel.pageSize)
                            }
                        }
                    }
                    .create().show()
            }
        }.root

    inner class ImageHolder(val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text1.backgroundTintList = ColorStateList.valueOf(randomColor(0x80))
        }

        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(item: JImageItem) {
            progress.postValue(item.preview_url)
            binding.text1.text = binding.root.resources.getString(R.string.app_resolution, item.width, item.height, item.resolution.title)
            binding.image1.layoutParams = (binding.image1.layoutParams as? ConstraintLayout.LayoutParams)?.also { params ->
                params.dimensionRatio = "${item.preview_width}:${item.preview_height}"
            }
            binding.image1.glideUrl(item.preview_url, R.mipmap.ic_launcher_foreground)
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)).apply {
                binding.root.setOnClickListener {
                    val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), binding.root, "shared_element_container")
                    startActivity(
                        Intent(context, PreviewActivity::class.java).putExtra("query", query).putExtra("index", bindingAdapterPosition),
                        options.toBundle()
                    )
                }
                binding.text1.setOnClickListener {
                    val item = getItem(bindingAdapterPosition)!!
                    val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                    startActivity(Intent(context, ListActivity::class.java).putExtra("query", Q().mpixels(item.resolution.mpixels, Q.Value.Op.ge)), options.toBundle())
                }
            }
    }

    class HeaderHolder(private val binding: StateItemBinding, private val retryCallback: () -> Unit) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.retryButton.also { it.setOnClickListener { retryCallback() } }
        }

        fun bindTo(loadState: LoadState) {
            binding.progressBar.isVisible = loadState is LoadState.Loading
            binding.retryButton.isVisible = loadState is LoadState.Error
            when (loadState) {
                is LoadState.Error -> {
                    binding.errorMsg.isVisible = true
                    binding.errorMsg.text = loadState.error.message
                }
                is LoadState.NotLoading -> {
                    binding.errorMsg.isVisible = loadState.endOfPaginationReached
                    binding.errorMsg.text = if (loadState.endOfPaginationReached) "END" else null
                }
                else -> {
                    binding.errorMsg.isVisible = false
                    binding.errorMsg.text = null
                }
            }
        }
    }

    open class HeaderAdapter(private val adapter: ImageAdapter) : LoadStateAdapter<HeaderHolder>() {
        override fun onBindViewHolder(holder: HeaderHolder, loadState: LoadState) = holder.bindTo(loadState)
        override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): HeaderHolder =
            HeaderHolder(StateItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)) { adapter.retry() }
    }

    class FooterAdapter(adapter: ImageAdapter) : HeaderAdapter(adapter) {
        override fun displayLoadStateAsItem(loadState: LoadState): Boolean = when (loadState) {
            is LoadState.NotLoading -> loadState.endOfPaginationReached
            else -> super.displayLoadStateAsItem(loadState)
        }
    }
}
