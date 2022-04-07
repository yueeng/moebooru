package com.github.yueeng.moebooru

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.paging.*
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.github.yueeng.moebooru.databinding.FragmentSimilarBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL

class SimilarActivity : MoeActivity(R.layout.activity_container) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.putExtra("action", intent.action)
        supportFragmentManager.run {
            val fragment = findFragmentById(R.id.container) as? SimilarFragment ?: SimilarFragment().apply { arguments = intent.extras }
            beginTransaction().replace(R.id.container, fragment).commit()
        }
    }
}

class SimilarDataSource(private val args: Bundle?) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val id = args?.getInt("id")?.takeIf { it != 0 }
        val url = args?.getString("url")
        if (id == null && url == null) throw Exception("empty")
        val all = MoeSettings.safe.value == true
        val similar = Service.instance.similar(id = id, url = url)
        val posts = (listOf(similar.source) + similar.posts)
            .filter { all || it.rating == "s" }
            .filter { !it.isDeleted }
        LoadResult.Page(posts, null, null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, JImageItem>): Int? = null
}

class SimilarViewModel(@Suppress("UNUSED_PARAMETER") handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val posts = Pager(PagingConfig(20)) { SimilarDataSource(args) }
        .flow.cachedIn(viewModelScope)
}

class SimilarViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = SimilarViewModel(handle, defaultArgs) as T
}

class SimilarFragment : Fragment() {
    private val model: SimilarViewModel by viewModels { SimilarViewModelFactory(this, arguments) }
    private val adapter = SimilarAdapter()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launchWhenCreated {
            if (arguments?.getString("action") != Intent.ACTION_SEND) return@launchWhenCreated
            if (arguments?.containsKey("url") == true) return@launchWhenCreated
            val image: Uri = arguments?.getParcelable(Intent.EXTRA_STREAM) ?: return@launchWhenCreated
            runCatching {
                requireContext().contentResolver.openInputStream(image).use {
                    val base64 = withContext(Dispatchers.IO) {
                        val bitmap = GlideApp.with(this@SimilarFragment).asBitmap()
                            .load(image).submit(150, 150).get()
                        ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        }
                    }
                    arguments?.putString("url", "data:image/jpg;base64,$base64")
                    adapter.refresh()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentSimilarBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            lifecycleScope.launchWhenCreated {
                MoeSettings.safe.asFlow().drop(1).distinctUntilChanged().collectLatest {
                    adapter.refresh()
                }
            }
        }.root

    inner class SimilarHolder(val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text1.backgroundTintList = ColorStateList.valueOf(randomColor(0x80))
        }

        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(item: JImageItem) {
            val url = URL(URL(moeUrl), item.preview_url).toString()
            progress.postValue(url)
            GlideApp.with(binding.image1).load(url).placeholder(R.mipmap.ic_launcher_foreground)
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
            binding.text1.text = when (item.service) {
                null -> moeHost
                "" -> "Source"
                else -> item.service
            }
            binding.image1.layoutParams = (binding.image1.layoutParams as? ConstraintLayout.LayoutParams)?.also { params ->
                params.dimensionRatio = "${item.width}:${item.height}"
            }
        }
    }

    inner class SimilarAdapter : PagingDataAdapter<JImageItem, SimilarHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: SimilarHolder, position: Int) = holder.bind(getItem(position)!!)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimilarHolder =
            SimilarHolder(ImageItemBinding.inflate(layoutInflater, parent, false)).apply {
                binding.root.setOnClickListener {
                    val item = getItem(bindingAdapterPosition) ?: return@setOnClickListener
                    when (item.url) {
                        "" -> Unit
                        null -> {
                            val options = ActivityOptions.makeSceneTransitionAnimation(activity, it, "shared_element_container")
                            val intent = Intent(activity, PreviewActivity::class.java)
                                .putExtra("query", Q().id(item.id)).putExtra("index", 0)
                            requireActivity().startActivity(intent, options.toBundle())
                        }
                        else -> requireContext().openWeb(item.url)
                    }
                }
            }
    }
}