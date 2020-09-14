package com.github.yueeng.moebooru

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup


class UserActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? UserFragment
            ?: UserFragment().apply { arguments = bundleOf("name" to "otaku_emmy", "user" to 73632) }
        val saved = supportFragmentManager.findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .replace(R.id.saved, saved).commit()
    }
}

class UserViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val name = handle.getLiveData("name", args?.getString("name"))
    val user = handle.getLiveData("user", args?.getInt("user"))
    val avatar = handle.getLiveData<Int>("avatar")
}

class UserViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = UserViewModel(handle, args) as T
}

class UserFragment : Fragment() {
    private val mine by lazy { arguments?.getBoolean("mine") ?: false }
    private val model: UserViewModel by viewModels { UserViewModelFactory(this, arguments) }
    private val adapter by lazy { ImageAdapter() }

    @FlowPreview
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentUserBinding.inflate(inflater, container, false).also { binding ->
            binding.toolbar.title = requireActivity().title
            binding.toolbar.setLogo(R.mipmap.ic_launcher)
            binding.toolbar.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.login -> true.also { OAuth.login(this) }
                    R.id.register -> true.also { OAuth.register(this) }
                    R.id.reset -> true.also { OAuth.reset(this) }
                    R.id.logout -> true.also { OAuth.logout(this) }
                    R.id.changeEmail -> true.also { OAuth.changeEmail(this) }
                    R.id.changePwd -> true.also { OAuth.changePwd(this) }
                    else -> false
                }
            }
            lifecycleScope.launchWhenCreated {
                OAuth.name.asFlow().collectLatest {
                    binding.toolbar.menu.findItem(R.id.userLogin).isVisible = it.isEmpty()
                    binding.toolbar.menu.findItem(R.id.userLogout).isVisible = it.isNotEmpty()
                    if (mine && it.isNotEmpty()) model.name.postValue(it)
                }
            }
            lifecycleScope.launchWhenCreated {
                OAuth.user.asFlow().collectLatest {
                    if (mine && it != 0) model.user.postValue(it)
                }
            }
            lifecycleScope.launchWhenCreated {
                model.name.asFlow().mapNotNull { it }.collectLatest { name ->
                    binding.toolbar.title = name
                    if (model.user.value == null) {
                        val id = Service.instance.user(name)
                        id.firstOrNull()?.id?.let { model.user.postValue(it) }
                    }
                }
            }
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
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                flowOf(model.name.asFlow().mapNotNull { it?.takeIf { it.isNotEmpty() } },
                    OAuth.name.asFlow().mapNotNull { it.takeIf { it.isNotEmpty() } })
                    .flattenMerge(2).collectLatest {
                        Log.i("MLDFLOW", "${OAuth.name.value}, ${model.name.value}")
                        if (OAuth.available) query()
                    }
            }
        }.root

    private suspend fun query() {
        val url = "$moe_url/user/show/${model.user.value}"
        val html = okHttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() }
        val jsoup = Jsoup.parse(html, url)
        val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
        model.avatar.postValue(id)
        val list = withContext(Dispatchers.IO) {
            Service.instance.post(1, Q().user(model.name.value!!))
        }
        adapter.submitList(list)
    }

    class ImageHolder(private val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.preview_width, item.preview_height)
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    class ImageAdapter : ListAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
            return ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position))
    }

}