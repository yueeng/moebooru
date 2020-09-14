package com.github.yueeng.moebooru

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.yueeng.moebooru.databinding.FragmentUserBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup


class UserActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? UserFragment
            ?: UserFragment().apply { arguments = intent.extras }
        val saved = supportFragmentManager.findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .replace(R.id.saved, saved).commit()
    }
}

class UserViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val user = handle.getLiveData("user", args?.getInt("user") ?: OAuth.user)
    val name = handle.getLiveData("name", args?.getString("name") ?: OAuth.name)
    val avatar = handle.getLiveData<Int>("avatar")
}

class UserViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = UserViewModel(handle, args) as T
}

class UserFragment : Fragment() {
    private val model: UserViewModel by viewModels { UserViewModelFactory(this, arguments) }
    private val adapter by lazy { ImageAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OAuth.available) {
            lifecycleScope.launchWhenCreated { query() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentUserBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.toolbar.setLogo(R.mipmap.ic_launcher)
            lifecycleScope.launchWhenCreated {
                model.name.asFlow().mapNotNull { it }.collectLatest { requireActivity().title = it }
            }
            model.avatar.observe(viewLifecycleOwner, Observer { id ->
                lifecycleScope.launchWhenCreated {
                    val list = withContext(Dispatchers.IO) {
                        Service.instance.post(1, Q().id(id), 1)
                    }
                    if (list.isEmpty()) return@launchWhenCreated
                    GlideApp.with(binding.image)
                        .load(list.first().sample_url)
                        .transform(AlphaBlackBitmapTransformation())
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.image)
                }
            })
            lifecycleScope.launchWhenCreated {
                model.user.asFlow().mapNotNull { it }.collectLatest {
                    GlideApp.with(binding.toolbar)
                        .load(OAuth.face(it))
                        .override(120, 120)
                        .circleCrop()
                        .into(object : CustomTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                binding.toolbar.logo = resource
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {
                            }
                        })
                }
            }
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                model.name.asFlow().mapNotNull { it }.collectLatest { name ->
                    val list = withContext(Dispatchers.IO) {
                        Service.instance.post(1, Q().user(name))
                    }
                    adapter.submitList(list)
                }
            }
        }.root

    private suspend fun query() {
        val url = "$moe_url/user/show/${model.user.value}"
        val html = okhttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() }
        val jsoup = Jsoup.parse(html, url)
        val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
        model.avatar.postValue(id)
    }

    class ImageHolder(private val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.preview_width, item.preview_height)
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    class ImageAdapter : ListAdapter<JImageItem, ImageHolder>(diffCallback { oldItem, newItem -> oldItem.id == newItem.id }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
            return ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position))
    }

}