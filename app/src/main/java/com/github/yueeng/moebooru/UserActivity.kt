package com.github.yueeng.moebooru

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.yueeng.moebooru.databinding.FragmentStarBinding
import com.github.yueeng.moebooru.databinding.FragmentUserBinding
import com.github.yueeng.moebooru.databinding.UserImageItemBinding
import com.github.yueeng.moebooru.databinding.UserTagItemBinding
import com.github.yueeng.moebooru.databinding.UserTitleItemBinding
import com.github.yueeng.moebooru.databinding.VoteTitleItemBinding
import com.github.yueeng.moebooru.databinding.VoteUserItemBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import okhttp3.Request
import org.jsoup.Jsoup


class UserActivity : MoeActivity(R.layout.activity_container) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.run {
            val fragment = supportFragmentManager.findFragmentById(R.id.container) as? UserFragment ?: UserFragment().apply { arguments = intent.extras }
            beginTransaction().replace(R.id.container, fragment).commit()
        }
    }
}

class UserViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val name = handle.getLiveData("name", args?.getString("name"))
    val user = handle.getLiveData("user", args?.getInt("user"))
    val avatar = handle.getLiveData<Int>("avatar")
    val background = handle.getLiveData<String>("background")
    val data = handle.getLiveData<Array<Parcelable>>("data")
}

class UserViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = UserViewModel(handle, args) as T
}

class UserMineFragment : UserFragment() {
    private fun prepareOptionsMenu(menu: Menu) {
        val auth = OAuth.user.value != null && OAuth.user.value != 0
        menu.findItem(R.id.userLogin).isVisible = !auth
        menu.findItem(R.id.userLogout).isVisible = auth
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = super.onCreateView(inflater, container, savedInstanceState).also { view ->
        val binding = FragmentUserBinding.bind(view)
        prepareOptionsMenu(binding.toolbar.menu)
        launchWhenCreated {
            OAuth.user.asFlow().collectLatest {
                prepareOptionsMenu(binding.toolbar.menu)
                model.user.postValue(it)
            }
        }

        launchWhenCreated {
            OAuth.name.asFlow().collectLatest {
                model.name.postValue(it)
            }
        }

        launchWhenCreated {
            OAuth.avatar.asFlow().filter { model.avatar.value != it }.collectLatest {
                model.avatar.postValue(it)
            }
        }

        launchWhenCreated {
            model.avatar.asFlow().filter { OAuth.avatar.value != it }.collectLatest {
                OAuth.avatar.postValue(it)
            }
        }

        launchWhenCreated {
            OAuth.timestamp.asFlow().drop(1).collectLatest {
                face(binding, model.user.value)
            }
        }
    }
}

open class UserFragment : Fragment() {
    protected val model: UserViewModel by sharedViewModels({ arguments?.getString("name") ?: "" }) { UserViewModelFactory(this, arguments) }
    private val adapter by lazy { ImageAdapter() }
    private val busy = MutableLiveData(false)

    private fun optionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.login -> true.also { OAuth.login(this) }
        R.id.register -> true.also { OAuth.register(this) }
        R.id.reset -> true.also { OAuth.reset(this) }
        R.id.logout -> true.also { OAuth.logout(this) }
        R.id.changeEmail -> true.also { OAuth.changeEmail(this) }
        R.id.changePwd -> true.also { OAuth.changePwd(this) }
        R.id.userAvatar -> true.also {
            if ((model.avatar.value ?: 0) == 0) return@also
            val binding = FragmentUserBinding.bind(requireView())
            val options = binding.toolbar.findViewById<View>(item.itemId)?.let { ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container") }
            startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra("query", Q().id(model.avatar.value!!)), options?.toBundle())
        }

        else -> false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentUserBinding.inflate(inflater, container, false).also { binding ->
            binding.toolbar.menu.findItem(R.id.userLogin).isVisible = false
            binding.toolbar.menu.findItem(R.id.userLogout).isVisible = false
            binding.toolbar.setOnMenuItemClickListener { optionsItemSelected(it) }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.flexDirection = FlexDirection.ROW
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            binding.swipe.setOnRefreshListener {
                launchWhenCreated { query() }
            }
            launchWhenCreated {
                model.user.asFlow().filter { it == 0 }.collectLatest {
                    model.data.postValue(emptyArray())
                    model.avatar.postValue(0)
                    model.background.postValue("")
                }
            }
            launchWhenCreated {
                model.user.asFlow().distinctUntilChanged().collectLatest {
                    face(binding, it)
                }
            }
            launchWhenCreated {
                model.name.asFlow().mapNotNull { it }.collectLatest { name ->
                    binding.collapsing.title = name.toTitleCase()
                    binding.toolbar.title = name.toTitleCase()
                }
            }
            launchWhenCreated {
                val user = model.user.asFlow().distinctUntilChanged()
                val name = model.name.asFlow().distinctUntilChanged()
                flowOf(user, name).flattenMerge(2).collectLatest {
                    if (model.user.value == 0) return@collectLatest
                    if (model.name.value == "") return@collectLatest
                    if (model.data.value?.any() == true) return@collectLatest
                    query()
                }
            }
            launchWhenCreated {
                model.avatar.asFlow().distinctUntilChanged().collectLatest { id ->
                    if (id == 0) {
                        binding.toolbar.navigationIcon = null
                        return@collectLatest
                    }
                    val bg = runCatching { Service.instance.post(1, Q().id(id), 1).firstOrNull()?.sample_url }.getOrNull() ?: return@collectLatest
                    model.background.postValue(bg)
                }
            }
            launchWhenCreated {
                model.background.asFlow().distinctUntilChanged().collectLatest { url ->
                    if (url.isEmpty()) {
                        binding.image1.setImageDrawable(null)
                        return@collectLatest
                    }
                    Glide.with(binding.image1)
                        .load(url)
                        .transform(AlphaBlackBitmapTransformation())
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.image1)
                }
            }
            launchWhenCreated {
                model.data.asFlow().collectLatest { adapter.submitList(it.toList()) }
            }
            launchWhenCreated {
                busy.asFlow().collectLatest { binding.swipe.isRefreshing = it }
            }
        }.root

    protected fun face(binding: FragmentUserBinding, id: Int?) {
        if (id == null || id == 0) return
        Glide.with(binding.toolbar)
            .load(OAuth.face(id))
            .placeholder(R.mipmap.ic_launcher_foreground)
            .override(120, 120)
            .circleCrop()
            .into(binding.toolbar) { view, drawable ->
                view.navigationIcon = drawable
            }
    }

    private suspend fun query() {
        try {
            if (!OAuth.available) return
            val name = model.name.value ?: return
            val user = model.user.value ?: return
            busy.postValue(true)
            val tags = listOf("vote:3:$name order:vote" to listOf("Favorite Artists", "Favorite Copyrights", "Favorite Characters", "Favorite Styles", "Favorite Circles"), "user:$name" to listOf("Uploaded Tags", "Uploaded Artists", "Uploaded Copyrights", "Uploaded Characters", "Uploaded Styles", "Uploaded Circles"))
            val images = listOf("Favorites" to "vote:3:$name order:vote", "Uploads" to "user:$name")
            val data = (listOf("Common" to null) + tags.flatMap { it.second }.map { it to null } + images).associate { it.first to (mutableListOf<Parcelable>() to it.second) }
            coroutineScope {
                launch {
                    runCatching {
                        val url = "$moeUrl/user/show/$user"
                        val html = okHttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() } ?: return@runCatching
                        val jsoup = withContext(Dispatchers.Default) { Jsoup.parse(html, url) }
                        val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
                        model.avatar.postValue(id)
                        val posts = jsoup.select("td:contains(Posts)").next().firstOrNull()?.text()?.toIntOrNull() ?: 0
                        if (posts > 0) {
                            data["Common"]?.first?.add(Tag("Posts: ${posts}P", "user:$name"))
                        }
                        val votes = jsoup.select("th:contains(Votes)").next().select(".stars a").map { it.text().trim('★', ' ').toIntOrNull() ?: 0 }.zip(1..3).filter { it.first > 0 }
                        for (v in votes) {
                            data["Common"]?.first?.add(Tag("Vote ${v.second}: ${v.first}P", "vote:${v.second}:$name order:vote"))
                        }
                        if (votes.size > 1) data["Common"]?.first?.add(Tag("Vote all: ${votes.sumOf { it.first }}P", "vote:1..3:$name order:vote"))
                        for (i in tags) {
                            for (j in i.second) {
                                val m = jsoup.select("th:contains($j)").next().select("a").map { it.text() }
                                data[j]?.first?.addAll(m.map { Tag(it, i.first + " ${it.replace(' ', '_')}") })
                            }
                        }
                        val submit = data.filter { it.value.first.any() }.flatMap { listOf(Title(it.key, it.value.second)) + it.value.first }
                        model.data.postValue(submit.toTypedArray())
                    }
                }
                images.map { image ->
                    launch {
                        val result = runCatching { Service.instance.post(1, Q(image.second)) }.getOrElse { emptyList() }
                        data[image.first]?.first?.addAll(result)
                        val submit = data.filter { it.value.first.any() }.flatMap { listOf(Title(it.key, it.value.second)) + it.value.first }
                        model.data.postValue(submit.toTypedArray())
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            busy.postValue(false)
        }
    }

    @Parcelize
    data class Title(val name: String, val query: String? = null) : Parcelable
    inner class TitleHolder(private val binding: UserTitleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.button1.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                requireActivity().startActivity(Intent(activity, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
            }
        }

        var tag: Title? = null
        fun bind(tag: Title) {
            this.tag = tag
            binding.text1.text = tag.name
            binding.button1.isVisible = tag.query != null
        }
    }

    @Parcelize
    data class Tag(val name: String, val query: String) : Parcelable
    inner class TagHolder(private val binding: UserTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                requireActivity().startActivity(Intent(activity, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                binding.root.setOnLongClickListener { view ->
                    view.showSupportedActivitiesMenu(tag?.name ?: "").menu.size() > 0
                }
            }
        }

        var tag: Tag? = null
        fun bind(tag: Tag) {
            this.tag = tag
            binding.text1.text = tag.name
        }
    }

    inner class ImageHolder(private val binding: UserImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
            binding.root.setOnClickListener {
                val adapter = bindingAdapter as ImageAdapter
                val title = adapter.currentList.reversed().dropWhile { it != tag }.firstNotNullOfOrNull { it as? Title }!!
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                requireActivity().startActivity(
                    Intent(context, PreviewActivity::class.java).putExtra("query", Q(title.query)).putExtra("id", tag!!.id),
                    options.toBundle()
                )
            }
        }

        var tag: JImageItem? = null
        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(item: JImageItem) {
            progress.postValue(item.preview_url)
            tag = item
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.run {
                width = height * item.preview_width / item.preview_height
            }
            binding.image1.glideUrl(item.preview_url, R.mipmap.ic_launcher_foreground)
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
        }
    }

    inner class ImageAdapter : ListAdapter<Parcelable, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Title -> 0
            is Tag -> 1
            is JImageItem -> 2
            else -> throw IllegalArgumentException()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> TitleHolder(UserTitleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            1 -> TagHolder(UserTagItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            2 -> ImageHolder(UserImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> throw IllegalArgumentException()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int): Unit = when (holder) {
            is TitleHolder -> holder.bind(getItem(position) as Title)
            is TagHolder -> holder.bind(getItem(position) as Tag)
            is ImageHolder -> holder.bind(getItem(position) as JImageItem)
            else -> throw IllegalArgumentException()
        }
    }
}

class StarViewModel(handle: SavedStateHandle) : ViewModel() {
    val star = handle.getLiveData("star", 0)
    val data = handle.getLiveData<ItemScore>("data")
    val busy = handle.getLiveData("busy", false)
    suspend fun data(post: Int): ItemScore? = try {
        busy.postValue(true)
        runCatching { Service.instance.vote(post, apiKey = MoeSettings.apiKey()) }
            .getOrNull()?.vote?.let { score ->
                star.postValue(score)
                runCatching { Service.instance.vote(post, score, apiKey = MoeSettings.apiKey()) }.getOrNull()
            }
    } finally {
        busy.postValue(false)
    }

    suspend fun vote(post: Int, score: Int) = try {
        busy.postValue(true)
        runCatching { Service.instance.vote(post, score, apiKey = MoeSettings.apiKey()) }.getOrNull()
    } finally {
        busy.postValue(false)
    }
}

class StarViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = StarViewModel(handle) as T
}

class StarActivity : MoeActivity(R.layout.activity_container) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? StarFragment
            ?: StarFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class StarFragment : Fragment() {
    private val post by lazy { arguments?.getInt("post") ?: 155193 }
    private val model: StarViewModel by viewModels { StarViewModelFactory(this, arguments) }
    private val adapter by lazy { StarAdapter() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (model.data.value == null) {
            launchWhenCreated {
                model.data(post)?.let { model.data.postValue(it) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentStarBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            model.star.observe(viewLifecycleOwner) {
                binding.rating.rating = it.toFloat()
                requireActivity().title = getString(R.string.query_vote_star_yours, it)
            }
            launchWhenCreated {
                adapter.changedFlow.collectLatest {
                    adapter.currentList.mapIndexedNotNull { index, any -> (any as? Title)?.let { index } }
                        .forEach { adapter.notifyItemChanged(it, "count") }
                }
            }
            binding.rating.setOnRatingBarChangeListener { _, rating, fromUser ->
                if (!fromUser) return@setOnRatingBarChangeListener
                launchWhenCreated {
                    model.vote(post, rating.toInt())?.let {
                        model.data.postValue(it)
                        model.star.postValue(rating.toInt())
                    }
                }
            }
            binding.button1.setOnClickListener {
                launchWhenCreated {
                    model.vote(post, 0)?.let {
                        model.data.postValue(it)
                        binding.rating.rating = 0F
                        model.star.postValue(0)
                    }
                }
            }
            (binding.recycler.layoutManager as? GridLayoutManager)?.also { manager ->
                manager.spanSizeLookup {
                    when (adapter.currentList[it]) {
                        is Title -> manager.spanCount
                        else -> 1
                    }
                }
            }
            binding.recycler.adapter = adapter
            model.data.observe(viewLifecycleOwner) { score ->
                adapter.submitList(score.voted_by.v.flatMap { listOf(Title(it.key, it.value.size)) + it.value })
            }
            binding.swipe.setOnRefreshListener {
                launchWhenCreated {
                    model.data(post)?.let { model.data.postValue(it) }
                }
            }
            model.busy.observe(viewLifecycleOwner) {
                binding.swipe.isRefreshing = it
            }
        }.root

    data class Title(val star: Int, val count: Int) {
        override fun hashCode(): Int = star.hashCode()
        override fun equals(other: Any?): Boolean = hashCode() == other?.hashCode()
    }

    class TitleHolder(private val binding: VoteTitleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(value: Title) {
            val star = context.getString(
                when (value.star) {
                    1 -> R.string.query_vote_star_1
                    2 -> R.string.query_vote_star_2
                    3 -> R.string.query_vote_star_3
                    else -> R.string.app_name
                }
            )
            binding.rating.text = context.getString(R.string.query_vote_star_title, star, value.count)
        }
    }

    inner class StarHolder(private val binding: VoteUserItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, it, "shared_element_container")
                requireActivity().startActivity(Intent(activity, UserActivity::class.java).putExtras(bundleOf("user" to value.id, "name" to value.name)), options.toBundle())
            }
        }

        lateinit var value: ItemUser
        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(value: ItemUser) {
            progress.postValue(value.face)
            this.value = value
            Glide.with(binding.image1).load(value.face)
                .error(R.mipmap.ic_launcher)
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
            binding.text1.text = value.name
        }
    }

    inner class StarAdapter : ListAdapter<Any, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Title -> 0
            is ItemUser -> 1
            else -> throw IllegalArgumentException()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> TitleHolder(VoteTitleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            1 -> StarHolder(VoteUserItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> throw IllegalArgumentException()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (holder) {
            is TitleHolder -> holder.bind(getItem(position) as Title)
            is StarHolder -> holder.bind(getItem(position) as ItemUser)
            else -> throw IllegalArgumentException()
        }

        private val changed = MutableStateFlow(0)
        val changedFlow get() = changed.asStateFlow()
        override fun onCurrentListChanged(previousList: MutableList<Any>, currentList: MutableList<Any>) {
            changed.value++
        }
    }
}