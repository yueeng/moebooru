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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SimilarActivity : MoeActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launchWhenCreated {
            if (intent.action == Intent.ACTION_SEND) {
                val image: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (image != null) {
                    runCatching {
                        suspendCoroutine<String> { c ->
                            contentResolver.openInputStream(image).use {
                                GlideApp.with(this@SimilarActivity).asBitmap().load(image)
                                    .override(100, 100).into(SimpleCustomTarget<Bitmap> {
                                        c.resume(ByteArrayOutputStream().use { stream ->
                                            it.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                            "data:image/jpg;base64,$base64"
                                        })
                                    })
                            }
                        }
                    }.getOrNull()?.let {
                        intent.putExtra("url", it)
                    }
                }
            }
            supportFragmentManager.run {
                val fragment = findFragmentById(R.id.container) as? SimilarFragment ?: SimilarFragment().apply { arguments = intent.extras }
                val mine = findFragmentById(R.id.mine) as? UserFragment ?: UserFragment()
                val saved = findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
                beginTransaction().replace(R.id.container, fragment)
                    .replace(R.id.mine, mine)
                    .replace(R.id.saved, saved)
                    .commit()
            }
        }
    }
}

class SimilarDataSource(private val id: Int? = null, private val url: String? = null) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val all = MoeSettings.safe.value == true
        val similar = Service.instance.similar(id = id, url = url)
        val posts = (listOf(similar.source) + similar.posts)
            .filter { all || it.rating == "s" }
            .filter { !it.isDeleted }
        LoadResult.Page(posts, null, null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class SimilarViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val posts = Pager(PagingConfig(20)) { SimilarDataSource(args?.getInt("id")?.takeIf { it != 0 }, args?.getString("url")) }
        .flow.cachedIn(viewModelScope)
}

class SimilarViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = SimilarViewModel(handle, defaultArgs) as T
}

class SimilarFragment : Fragment() {
    private val model: SimilarViewModel by viewModels { SimilarViewModelFactory(this, arguments) }
    private val adapter = SimilarAdapter()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
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
                            requireActivity().startActivity(Intent(activity, PreviewActivity::class.java).putExtra("query", Q().id(item.id)).putExtra("index", 0), options.toBundle())
                        }
                        else -> requireContext().openWeb(item.url)
                    }
                }
            }
    }
}