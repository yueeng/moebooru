package com.github.yueeng.moebooru

import android.graphics.drawable.Drawable
import android.os.Bundle
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
import com.github.yueeng.moebooru.databinding.ListStringItemBinding
import com.github.yueeng.moebooru.databinding.UserImageItemBinding
import com.github.yueeng.moebooru.databinding.UserTagItemBinding
import com.google.android.flexbox.*
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
    val busy = handle.getLiveData("busy", false)
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
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.FLEX_START
                justifyContent = JustifyContent.FLEX_START
            }
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                flowOf(model.name.asFlow().mapNotNull { it?.takeIf { it.isNotEmpty() } },
                    OAuth.name.asFlow().mapNotNull { it.takeIf { it.isNotEmpty() } })
                    .flattenMerge(2).collectLatest {
                        if (OAuth.available) query()
                    }
            }
            binding.swipe.setOnRefreshListener {
                lifecycleScope.launchWhenCreated { query() }
            }
            model.busy.observe(viewLifecycleOwner, Observer { binding.swipe.isRefreshing = it })
        }.root

    private suspend fun query() {
        val name = model.name.value ?: return
        val user = model.user.value ?: return
        model.busy.postValue(true)
        val url = "$moeUrl/user/show/$user"
        val html = okHttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() }
        val jsoup = Jsoup.parse(html, url)
        val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
        model.avatar.postValue(id)

        val data = LinkedHashMap<String, MutableList<Any>>()
        val posts = jsoup.select("td:contains(Posts)").next().firstOrNull()?.text()?.toIntOrNull() ?: 0
        if (posts > 0) {
            data.getOrPut("Common") { mutableListOf() }.add(Tag("Posts: ${posts}P", "user:$name"))
        }
        val votes = jsoup.select("th:contains(Votes)").next().select(".stars a").map { it.text().trim('★', ' ').toIntOrNull() ?: 0 }.zip(1..3).filter { it.first > 0 }
        for (v in votes) {
            data.getOrPut("Common") { mutableListOf() }.add(Tag("Vote ${v.second}: ${v.first}P", "vote:${v.second}:$name order:vote"))
        }
        if (votes.size > 1) data.getOrPut("Common") { mutableListOf() }.add(Tag("Vote all: ${votes.sumBy { it.first }}P", "vote:1..3:$name order:vote"))
        for (i in listOf(Pair("vote:3:$name order:vote", listOf("Favorite Artists", "Favorite Copyrights", "Favorite Characters", "Favorite Styles", "Favorite Circles")), Pair("user:$name", listOf("Uploaded Tags", "Uploaded Artists", "Uploaded Copyrights", "Uploaded Characters", "Uploaded Styles", "Uploaded Circles")))) {
            for (j in i.second) {
                data[j] = mutableListOf()
                val m = jsoup.select("th:contains($j)").next().select("a").map { it.text() }
                if (m.isNotEmpty()) {
                    data[j]!!.addAll(m.map { Tag(it, i.first) })
                }
            }
        }
        adapter.submitList(data.flatMap { listOf(it.key) + it.value })
        withContext(Dispatchers.IO) {
            listOf("Favorites" to "vote:3:$name order:vote", "Uploads" to "user:$name order:id_desc").map {
                it.first to runCatching { Service.instance.post(1, Q(it.second)) }.getOrElse { emptyList() }
            }
        }.filter { it.second.any() }.forEach { data.getOrPut(it.first) { mutableListOf() }.addAll(0, it.second) }
        adapter.submitList(data.flatMap { listOf(it.key) + it.value })

        model.busy.postValue(false)
    }

    class TitleHolder(private val binding: ListStringItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tag: String) {
            binding.text1.text = tag
        }
    }

    data class Tag(val name: String, val query: String)
    class TagHolder(private val binding: UserTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
        }

        fun bind(tag: Tag) {
            binding.text1.text = tag.name
        }
    }

    class ImageHolder(private val binding: UserImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
        }

        fun bind(item: JImageItem) {
            binding.root.minimumWidth = binding.root.resources.getDimensionPixelSize(R.dimen.user_image_height) * item.preview_width / item.preview_height
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    class ImageAdapter : ListAdapter<Any, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is String -> 0
            is Tag -> 1
            is JImageItem -> 2
            else -> throw IllegalArgumentException()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> TitleHolder(ListStringItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            1 -> TagHolder(UserTagItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            2 -> ImageHolder(UserImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> throw IllegalArgumentException()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is TitleHolder -> holder.bind(getItem(position) as String)
                is TagHolder -> holder.bind(getItem(position) as Tag)
                is ImageHolder -> holder.bind(getItem(position) as JImageItem)
            }
        }
    }

}