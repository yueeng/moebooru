package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.transition.Explode
import android.transition.Fade
import android.transition.Slide
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.use
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.ChangeTransform
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.platform.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import java.util.*
import android.transition.TransitionSet as WindowTransitionSet
import com.google.android.material.transition.platform.MaterialSharedAxis as WindowMaterialSharedAxis

open class MoeActivity(contentLayoutId: Int) : AppCompatActivity(contentLayoutId) {
    override fun startActivity(intent: Intent?, options: Bundle?) =
        super.startActivity(
            intent, when (MoeSettings.animation.value) {
                "shared" -> options
                "scale", "explode", "fade", "slide", "axis_x", "axis_y", "axis_z" -> ActivityOptions.makeSceneTransitionAnimation(this).toBundle()
                else -> null
            }
        )

    private fun ensureTransform() {
        when (MoeSettings.animation.value) {
            "shared" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                findViewById<View>(android.R.id.content).transitionName = "shared_element_container"
                setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
                setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
                val background = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use {
                    it.getColor(0, Color.WHITE)
                }
                window.sharedElementEnterTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    endContainerColor = background
                    duration = 400L
                }
                window.sharedElementReturnTransition = MaterialContainerTransform().apply {
                    addTarget(android.R.id.content)
                    pathMotion = MaterialArcMotion()
                    startContainerColor = background
                    duration = 300L
                }
                window.allowEnterTransitionOverlap = true
            }
            "scale" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.enterTransition = MaterialElevationScale(true)
                window.exitTransition = MaterialElevationScale(false)
                window.allowEnterTransitionOverlap = true
            }
            "fade" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.enterTransition = MaterialFadeThrough()
                window.exitTransition = MaterialFadeThrough()
                window.allowEnterTransitionOverlap = true
            }
            "axis_x" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.reenterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.X, false)
                window.exitTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.X, true)
                window.enterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.X, true)
                window.returnTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.X, false)
                window.allowEnterTransitionOverlap = true
            }
            "axis_y" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.reenterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Y, false)
                window.exitTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Y, true)
                window.enterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Y, true)
                window.returnTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Y, false)
                window.allowEnterTransitionOverlap = true
            }
            "axis_z" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.reenterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Z, false)
                window.exitTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Z, true)
                window.enterTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Z, true)
                window.returnTransition = WindowMaterialSharedAxis(WindowMaterialSharedAxis.Z, false)
                window.allowEnterTransitionOverlap = true
            }
            "explode" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                val trans = WindowTransitionSet().addTransition(Explode()).addTransition(Fade())
                window.enterTransition = trans
                window.exitTransition = trans
                window.allowEnterTransitionOverlap = true
            }
            "slide" -> {
                window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                window.enterTransition = Slide(Gravity.END)
                window.exitTransition = Slide(Gravity.START)
                window.allowEnterTransitionOverlap = true
            }
            else -> Unit
        }
    }

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        ensureTransform()
        super.onCreate(savedInstanceState)
        lifecycleScope.launchWhenCreated {
            val flow1 = MoeSettings.daynight.asFlow().drop(1)
            val flow2 = MoeSettings.animation.asFlow().drop(1)
            flowOf(flow1, flow2).flattenMerge(2).collectLatest {
                recreate()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.app, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.settings -> true.also {
            val options = ActivityOptions.makeSceneTransitionAnimation(this, findViewById(item.itemId), "shared_element_container")
            startActivity(Intent(this, SettingsActivity::class.java), options.toBundle())
        }
        else -> super.onOptionsItemSelected(item)
    }
}

class MainActivity : MoeActivity(R.layout.activity_main) {
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
            val binding = FragmentMainBinding.bind(requireView())
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

class ImageDataSource(private val query: Q? = Q()) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, Q(query), limit = params.loadSize)
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    val query = handle.getLiveData<Q>("query", defaultArgs?.getParcelable("query"))
    val posts = Pager(PagingConfig(20, initialLoadSize = 20)) { ImageDataSource(query.value) }
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
        init {
            binding.text1.backgroundTintList = ColorStateList.valueOf(randomColor(0x80))
        }

        fun bind(item: JImageItem) {
            binding.text1.text = binding.root.resources.getString(R.string.app_resolution, item.width, item.height, item.resolution.title)
            bindImageRatio(binding.image1, item.preview_width, item.preview_height)
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher_foreground)
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
