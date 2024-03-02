package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.TransitionManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.yueeng.moebooru.Save.save
import com.github.yueeng.moebooru.databinding.*
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.run {
            val fragment = findFragmentById(R.id.container) as? MainFragment ?: MainFragment()
            beginTransaction().replace(R.id.container, fragment).commit()
        }
//        checkAppUpdate(compare = true)
    }
}

class MainFragment : Fragment(), SavedFragment.Queryable, MenuProvider {
    private val adapter by lazy { PagerAdapter(this) }
    private lateinit var binding: FragmentMainBinding

    @SuppressLint("RestrictedApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentMainBinding.inflate(inflater, container, false).also { binding ->
            this.binding = binding
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.pager.adapter = adapter
            TabLayoutMediator(binding.tab, binding.pager) { tab, position -> tab.text = adapter.data[position].first }.attach()
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val current = adapter.data[binding.pager.currentItem]
                    binding.menu.getHeaderView(0)?.findViewById<TextView>(R.id.text1)?.text = current.second.keyword?.toTitleCase()
                    binding.menu.getHeaderView(0).isVisible = current.second.keyword?.isNotEmpty() == true
                }
            })
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    Db.tags.tagsWithIndex(true).collectLatest { tags ->
                        adapter.submitList(tags.map { it.name to Q(it.tag) })
                    }
                }
            }
            binding.menu.setNavigationItemSelectedListener {
                val tag = when (it.itemId) {
                    R.id.day -> "day"
                    R.id.week -> "week"
                    R.id.month -> "month"
                    R.id.year -> "year"
                    R.id.all -> "all"
                    else -> return@setNavigationItemSelectedListener false
                }
                val view = binding.menu.findViewById<View>(it.itemId)
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), view, "shared_element_container")
                val query = adapter.data[binding.pager.currentItem].second
                val intent = if (tag == "all") {
                    Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(query.keyword).order(Q.Order.score))
                } else {
                    Intent(requireContext(), PopularActivity::class.java).putExtra("type", tag).putExtra("key", query.keyword)
                }
                startActivity(intent, options.toBundle())
                true
            }
            binding.scram.setOnClickListener { binding.fab.performClick() }
            binding.fab.setOnClickListener {
                val visible = binding.fab.isVisible
                val transform = MaterialContainerTransform().apply {
                    startView = if (visible) binding.fab else binding.menu
                    endView = if (visible) binding.menu else binding.fab
                    addTarget(endView as View)
                    scrimColor = Color.TRANSPARENT
                }
                TransitionManager.beginDelayedTransition(binding.coordinator, transform)
                binding.scram.isInvisible = !visible
                binding.fab.isVisible = !visible
                binding.menu.isVisible = visible
            }
            requireActivity().addOnBackPressedCallback(viewLifecycleOwner) {
                if (binding.menu.isVisible) {
                    binding.fab.performClick()
                    return@addOnBackPressedCallback true
                }
                false
            }
        }.root

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        private val differ = AsyncListDiffer<Pair<String, Q>>(this, diffCallback { old, new -> old == new }).apply {
            submitList(listOf("Popular" to Q().order(Q.Order.score).date(1, Q.Value.Op.ge)))
        }
        val data get():List<Pair<String, Q>> = differ.currentList
        fun submitList(list: List<Pair<String, Q>>) = differ.submitList(listOf(data.first()) + list)
        override fun getItemId(position: Int): Long = data[position].hashCode().toLong()
        override fun containsItem(itemId: Long): Boolean = data.map { it.hashCode().toLong() }.contains(itemId)
        override fun getItemCount(): Int = data.size
        override fun createFragment(position: Int): Fragment = ImageFragment().apply {
            arguments = bundleOf("query" to data[position].second, "name" to data[position].first)
            if (position != 0) return@apply
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    adapter.loadStateFlow.collectLatest {
                        if (it.refresh !is LoadState.Error) return@collectLatest
                        if (adapter.itemCount != 0) return@collectLatest
                        if (MoeSettings.host.value == true) return@collectLatest
                        requireView().snack(R.string.settings_host_ip_on, Snackbar.LENGTH_LONG).setAction(R.string.app_ok) {
                            MoeSettings.host.setValueToPreferences(true)
                            adapter.refresh()
                        }.show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.column -> true.also {
            MoeSettings.column()
        }
        else -> false
    }

    override fun query(): Q = adapter.data[binding.pager.currentItem].second
}

class ListActivity : MoeActivity(R.layout.activity_container) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.getQueryParameter("tags")?.let {
                intent.putExtra("query", Q(it))
            } ?: return
        }
        supportFragmentManager.run {
            val fragment = findFragmentById(R.id.container) as? ListFragment
                ?: ListFragment().apply { arguments = intent.extras }
            beginTransaction().replace(R.id.container, fragment).commit()
        }
    }
}

class ListFragment : Fragment(), SavedFragment.Queryable, MenuProvider {
    private val query by lazy { arguments?.getParcelableCompat<Q>("query") }
    private val artist = MutableLiveData<ItemArtist?>()
    private lateinit var binding: FragmentListBinding
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentListBinding.inflate(inflater, container, false).also { binding ->
            this.binding = binding
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            query?.toString()?.let { requireActivity().title = it.toTitleCase() }
            val fragment = childFragmentManager.findFragmentById(R.id.container) as? ImageFragment
                ?: ImageFragment().also { it.arguments = arguments }
            childFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
            binding.fab.isVisible = query?.keyword?.isNotEmpty() == true
            binding.menu.getHeaderView(0).findViewById<TextView>(R.id.text1)?.text = query?.keyword?.toTitleCase()
            binding.menu.setNavigationItemSelectedListener {
                val tag = when (it.itemId) {
                    R.id.day -> "day"
                    R.id.week -> "week"
                    R.id.month -> "month"
                    R.id.year -> "year"
                    R.id.all -> "all"
                    else -> return@setNavigationItemSelectedListener false
                }
                val view = binding.menu.findViewById<View>(it.itemId)
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), view, "shared_element_container")
                val intent = if (tag == "all") {
                    Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(query?.keyword).order(Q.Order.score))
                } else {
                    Intent(requireContext(), PopularActivity::class.java).putExtra("type", tag).putExtra("key", query?.keyword)
                }
                startActivity(intent, options.toBundle())
                true
            }
            binding.scram.setOnClickListener { binding.fab.performClick() }
            binding.fab.setOnClickListener {
                val visible = binding.fab.isVisible
                val transform = MaterialContainerTransform().apply {
                    startView = if (visible) binding.fab else binding.menu
                    endView = if (visible) binding.menu else binding.fab
                    addTarget(endView as View)
                    scrimColor = Color.TRANSPARENT
                }
                TransitionManager.beginDelayedTransition(binding.coordinator, transform)
                binding.scram.isInvisible = !visible
                binding.fab.isVisible = !visible
                binding.menu.isVisible = visible
            }
            requireActivity().addOnBackPressedCallback(viewLifecycleOwner) {
                if (binding.menu.isVisible) {
                    binding.fab.performClick()
                    return@addOnBackPressedCallback true
                }
                false
            }
        }.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = arguments?.getString("artist") ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                artist.postValue(Service.instance.artist(name).firstOrNull())
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                artist.asFlow().filter { it?.urls?.any() == true }.collectLatest {
                    requireActivity().invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.artist)?.isVisible = artist.value?.urls?.any() == true
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.artist -> true.also {
            val value = artist.value ?: return@also
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(value.name.toTitleCase())
                .setItems(value.urls?.toTypedArray() ?: emptyArray()) { _, w ->
                    value.urls?.get(w)?.let(requireActivity()::openWeb)
                }
                .setPositiveButton(R.string.app_ok, null)
                .show()
        }
        R.id.column -> true.also {
            MoeSettings.column()
        }
        else -> false
    }

    override fun query(): Q = query ?: Q()
}

class ImageDataSource(private val query: Q? = Q(), private val begin: Int = 1, private val call: ((Int) -> Unit)? = null) : PagingSource<Int, JImageItem>() {
    private suspend fun children(q: Set<Q>, raw: List<JImageItem>, data: List<JImageItem> = raw): List<JImageItem> {
        val children = raw.filter { it.has_children }.map { Q().parent(it.id) }
        val parent = raw.filter { it.parent_id != 0 }.filter { data.all { i -> i.id != it.parent_id } }.map { Q().id(it.parent_id) }
        val queries = (children + parent).subtract(q)
        val subtract = coroutineScope {
            queries.map { async { Service.instance.post(page = 1, it, limit = 100) } }.awaitAll().flatten().subtract(data.toSet()).toList()
        }.takeIf { it.any() } ?: return data
        return children(q + queries, subtract, data + subtract)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: begin
        val raw = Service.instance.post(page = key, Q(query), limit = params.loadSize)
        val posts = if (query?.only("id", "parent") == true) children(setOf(Q(query)), raw) else raw
        call?.invoke(key)
        val prev = if (posts.isNotEmpty()) (key - 1).takeIf { it > 0 } else null
        val next = if (posts.size == params.loadSize) key + (params.loadSize / ImageViewModel.pageSize) else null
        LoadResult.Page(posts, prev, next)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, JImageItem>): Int? = null
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    companion object {
        const val pageSize = 20
    }

    val index = handle.getLiveData<Int>("index")
    val min = handle.getLiveData("min", 1)
    val max = handle.getLiveData<Int>("max")
    val query = handle.getLiveData<Q?>("query", defaultArgs?.getParcelableCompat("query"))
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
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ImageViewModel(handle, defaultArgs) as T
}

class ImageFragment : Fragment() {
    private val query by lazy { arguments?.getParcelableCompat("query") ?: Q() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    private val offset = MutableLiveData<Int>()
    private val sum = MutableLiveData<Int>()
    val adapter by lazy { ImageAdapter() }

    @OptIn(FlowPreview::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentImageBinding.inflate(inflater, container, false).also { binding ->
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter.withLoadStateHeaderAndFooter(HeaderAdapter(adapter), FooterAdapter(adapter))
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    model.posts.collectLatest { adapter.submitData(it) }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    adapter.loadStateFlow.collectLatest {
                        binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                        sum.postValue(adapter.itemCount)
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    val flow1 = model.index.asFlow().distinctUntilChanged()
                    val flow2 = model.min.asFlow().distinctUntilChanged()
                    val flow3 = model.max.asFlow().distinctUntilChanged()
                    flowOf(flow1, flow2, flow3).flattenMerge(3).collectLatest {
                        val index = model.index.value ?: return@collectLatest
                        val min = model.min.value ?: return@collectLatest
                        val max = model.max.value ?: return@collectLatest
                        binding.text1.text = getString(R.string.app_page_number, min(index + min, max), max)
                        binding.layout1.isInvisible = max == 0 || MoeSettings.page.value!!
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    sum.asFlow().distinctUntilChanged().collectLatest {
                        binding.progress.max = it
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    offset.asFlow().distinctUntilChanged().collectLatest {
                        binding.progress.setProgressCompat(it)
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    MoeSettings.column.asFlow().drop(1).distinctUntilChanged().collectLatest {
                        TransitionManager.beginDelayedTransition(binding.swipe)
                        (binding.recycler.layoutManager as? StaggeredGridLayoutManager)?.spanCount = it
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    MoeSettings.info.asFlow().drop(1).distinctUntilChanged().collectLatest {
                        adapter.notifyItemRangeChanged(0, adapter.itemCount, "info")
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    MoeSettings.preview.asFlow().drop(1).distinctUntilChanged().collectLatest {
                        adapter.notifyItemRangeChanged(0, adapter.itemCount, "preview")
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    MoeSettings.page.asFlow().drop(1).distinctUntilChanged().collectLatest {
                        val max = model.max.value ?: 0
                        binding.layout1.isInvisible = max == 0 || it
                    }
                }
            }
            (binding.recycler.layoutManager as? StaggeredGridLayoutManager)?.spanCount = MoeSettings.column.value!!
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
                    }.create().show()
            }
        }.root

    inner class ImageHolder(private val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text1.backgroundTintList = ColorStateList.valueOf(randomColor(0x80))
            binding.root.setOnClickListener {
                val query = binding.root.findFragment<Fragment>().arguments?.getParcelableCompat<Q>("query") ?: return@setOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                requireActivity().startActivity(
                    Intent(context, PreviewActivity::class.java).putExtra("query", query).putExtra("index", bindingAdapterPosition),
                    options.toBundle()
                )
            }
            binding.root.setOnLongClickListener {
                val item = this.item ?: return@setOnLongClickListener false
                val adapter = PreviewFragment.TagAdapter()
                val recycler = RecyclerView(context).apply {
                    layoutManager = FlexboxLayoutManager(context).apply {
                        flexDirection = FlexDirection.ROW
                    }
                    this.adapter = adapter
                }
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.CREATED) {
                        adapter.submit(item)
                    }
                }

                MaterialAlertDialogBuilder(context)
                    .setView(recycler)
                    .setPositiveButton(R.string.app_save, null)
                    .setNeutralButton(R.string.app_favorite, null)
                    .setNegativeButton(R.string.app_cancel, null)
                    .create()
                    .show {
                        positiveButton.setOnClickListener {
                            (requireActivity() as AppCompatActivity).save(item.id, item.save_url, Save.SO.SAVE, item.author, it)
                        }
                        neutralButton.setOnClickListener {
                            if (!OAuth.available) {
                                OAuth.login(this@ImageFragment) {
                                    it.performClick()
                                }
                                return@setOnClickListener
                            }
                            val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                            startActivity(Intent(requireContext(), StarActivity::class.java).putExtra("post", item.id), options.toBundle())
                            dismiss()
                        }
                    }
                true
            }
            binding.text1.setOnClickListener {
                val adapter = bindingAdapter as? ImageAdapter ?: return@setOnClickListener
                val item = adapter.peekSafe(bindingAdapterPosition) ?: return@setOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, it, "shared_element_container")
                requireActivity().startActivity(Intent(context, ListActivity::class.java).putExtra("query", Q().mpixels(item.resolution.mpixels, Q.Value.Op.ge)), options.toBundle())
            }
        }

        var item: JImageItem? = null
        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(item: JImageItem, payloads: MutableList<Any>) {
            this.item = item
            if (payloads.isEmpty() || payloads.contains("info")) {
                binding.text1.isGone = MoeSettings.info.value ?: false
            }
            if (payloads.isEmpty() || payloads.contains("preview")) {
                val sample = MoeSettings.preview.value == true
                val url = if (sample) item.sample_url else item.preview_url
                progress.postValue(url)
                GlideApp.with(binding.image1).load(url).placeholder(R.mipmap.ic_launcher_foreground)
                    .run {
                        val target = if (sample) GlideApp.with(binding.image1).load(item.preview_url) else this
                        target.transition(DrawableTransitionOptions.withCrossFade())
                            .onResourceReady { _, _, _, _, _ -> binding.image1.setImageDrawable(null); false }
                        if (sample) thumbnail(target) else this
                    }
                    .onComplete { _, _, _, _ -> progress.postValue(""); false }
                    .into(binding.image1)
            }
            if (payloads.isNotEmpty()) return
            binding.text1.text = binding.root.resources.getString(R.string.app_resolution, item.width, item.height, item.resolution.title)
            binding.text1.setCompoundResourcesDrawables(
                when {
                    item.has_children -> R.drawable.ic_photo_library
                    item.parent_id != 0 -> R.drawable.ic_photo_children
                    else -> null
                }, null, null, null
            )
            binding.image1.layoutParams = (binding.image1.layoutParams as? ConstraintLayout.LayoutParams)?.also { params ->
                params.dimensionRatio = "${item.preview_width}:${item.preview_height}"
            }
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = Unit
        override fun onBindViewHolder(holder: ImageHolder, position: Int, payloads: MutableList<Any>) = holder.bind(getItem(position)!!, payloads)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
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
