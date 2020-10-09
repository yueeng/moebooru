package com.github.yueeng.moebooru

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.yueeng.moebooru.databinding.*
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
import okhttp3.Request
import org.jsoup.Jsoup
import kotlin.math.roundToInt


class UserActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.run {
            val fragment = supportFragmentManager.findFragmentById(R.id.container) as? UserFragment
                ?: UserFragment().apply { arguments = intent.extras }
            val mine = findFragmentById(R.id.mine) as? UserFragment ?: UserFragment()
            val saved = findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
            beginTransaction().replace(R.id.container, fragment)
                .replace(R.id.mine, mine)
                .replace(R.id.saved, saved)
                .commit()
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
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = UserViewModel(handle, args) as T
}

class UserPoolViewModel : ViewModel() {
    val pool = RecyclerView.RecycledViewPool()
}

class UserFragment : Fragment() {
    private val busy = MutableLiveData(false)
    private val mine by lazy { arguments?.containsKey("name") != true }
    private val model: UserViewModel by sharedViewModels({ arguments?.getString("name") ?: "" }) { UserViewModelFactory(this, arguments) }
    private val adapter by lazy { ImageAdapter() }
    private val pool: UserPoolViewModel by activityViewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(!mine)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.user, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.userLogin).isVisible = OAuth.name.value?.isEmpty() ?: true
        menu.findItem(R.id.userLogout).isVisible = OAuth.name.value?.isNotEmpty() ?: false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.login -> true.also { OAuth.login(this) }
        R.id.register -> true.also { OAuth.register(this) }
        R.id.reset -> true.also { OAuth.reset(this) }
        R.id.logout -> true.also { OAuth.logout(this) }
        R.id.changeEmail -> true.also { OAuth.changeEmail(this) }
        R.id.changePwd -> true.also { OAuth.changePwd(this) }
        else -> super.onOptionsItemSelected(item)
    }

    @OptIn(FlowPreview::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentUserBinding.inflate(inflater, container, false).also { binding ->
            binding.recycler.setRecycledViewPool(pool.pool)
            if (!mine) (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar) else {
                binding.toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            }
            lifecycleScope.launchWhenCreated {
                OAuth.name.asFlow().collectLatest {
                    if (mine) onPrepareOptionsMenu(binding.toolbar.menu) else requireActivity().invalidateOptionsMenu()
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
                    if (mine) binding.toolbar.title = name.toTitleCase()
                    else requireActivity().title = name.toTitleCase()
                    if (model.user.value == null) {
                        runCatching { Service.instance.user(name) }.getOrNull()
                            ?.firstOrNull()?.id?.let { model.user.postValue(it) }
                    }
                }
            }
            lifecycleScope.launchWhenCreated {
                val flowUser = model.user.asFlow().mapNotNull { it }
                val flowTime = OAuth.timestamp.asFlow().drop(1)
                flowOf(flowUser, flowTime).flattenMerge(2).collectLatest {
                    if (model.user.value == null) return@collectLatest
                    GlideApp.with(binding.toolbar)
                        .load(OAuth.face(model.user.value!!))
                        .placeholder(R.mipmap.ic_launcher_foreground)
                        .override(120, 120)
                        .circleCrop()
                        .into(binding.toolbar) { view, drawable ->
                            view.navigationIcon = drawable
                        }
                }
            }
            binding.toolbar.setNavigationOnClickListener {
                if (model.avatar.value ?: 0 == 0) return@setNavigationOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra("query", Q().id(model.avatar.value!!)), options.toBundle())
            }
            OAuth.avatar.observe(viewLifecycleOwner, Observer {
                if (mine && model.avatar.value != it) {
                    model.avatar.postValue(it)
                    lifecycleScope.launchWhenCreated { background(it) }
                }
            })
            model.avatar.observe(viewLifecycleOwner, Observer {
                if (mine && OAuth.avatar.value != it) OAuth.avatar.postValue(it)
                if (model.background.value != null) return@Observer
                lifecycleScope.launchWhenCreated { background(it) }
            })
            lifecycleScope.launchWhenCreated {
                model.background.asFlow().mapNotNull { it }.collectLatest { url ->
                    GlideApp.with(binding.image1)
                        .load(url)
                        .transform(AlphaBlackBitmapTransformation())
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.image1)
                }
            }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.flexDirection = FlexDirection.ROW
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                val flowUser = model.user.asFlow().mapNotNull { it?.takeIf { it != 0 } }
                val flowName = OAuth.name.asFlow().mapNotNull { it.takeIf { it.isNotEmpty() } }
                flowOf(flowUser, flowName).flattenMerge(2).collectLatest {
                    if (model.data.value?.isEmpty() != false) query()
                }
            }
            model.data.observe(viewLifecycleOwner, Observer {
                adapter.submitList(it.toList())
            })
            binding.swipe.setOnRefreshListener {
                lifecycleScope.launchWhenCreated { query() }
            }
            busy.observe(viewLifecycleOwner, Observer { binding.swipe.isRefreshing = it })
        }.root

    private suspend fun background(id: Int) {
        runCatching { Service.instance.post(1, Q().id(id), 1) }.getOrNull()
            ?.firstOrNull()?.sample_url?.let { model.background.postValue(it) }
    }

    private suspend fun query() {
        if (!OAuth.available) return
        val name = model.name.value ?: return
        val user = model.user.value ?: return
        busy.postValue(true)
        try {
            val tags = listOf("vote:3:$name order:vote" to listOf("Favorite Artists", "Favorite Copyrights", "Favorite Characters", "Favorite Styles", "Favorite Circles"), "user:$name" to listOf("Uploaded Tags", "Uploaded Artists", "Uploaded Copyrights", "Uploaded Characters", "Uploaded Styles", "Uploaded Circles"))
            val images = listOf("Favorites" to "vote:3:$name order:vote", "Uploads" to "user:$name")
            val data = (listOf("Common" to null) + tags.flatMap { it.second }.map { it to null } + images).map { it.first to (mutableListOf<Parcelable>() to it.second) }.toMap()
            coroutineScope {
                launch {
                    val url = "$moeUrl/user/show/$user"
                    val html = okHttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() }
                    val jsoup = withContext(Dispatchers.Default) { Jsoup.parse(html, url) }
                    launch {
                        val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
                        model.avatar.postValue(id)
                    }
                    launch {
                        val posts = jsoup.select("td:contains(Posts)").next().firstOrNull()?.text()?.toIntOrNull() ?: 0
                        if (posts > 0) {
                            data["Common"]?.first?.add(Tag("Posts: ${posts}P", "user:$name"))
                        }
                        val votes = jsoup.select("th:contains(Votes)").next().select(".stars a").map { it.text().trim('★', ' ').toIntOrNull() ?: 0 }.zip(1..3).filter { it.first > 0 }
                        for (v in votes) {
                            data["Common"]?.first?.add(Tag("Vote ${v.second}: ${v.first}P", "vote:${v.second}:$name order:vote"))
                        }
                        if (votes.size > 1) data["Common"]?.first?.add(Tag("Vote all: ${votes.sumBy { it.first }}P", "vote:1..3:$name order:vote"))
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
        } finally {
            busy.postValue(false)
        }
    }

    @Parcelize
    data class Title(val name: String, val query: String? = null) : Parcelable
    class TitleHolder(private val binding: UserTitleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val activity get() = binding.root.findActivity<AppCompatActivity>()!!

        init {
            binding.button1.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                activity.startActivity(Intent(activity, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
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
    class TagHolder(private val binding: UserTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val activity get() = binding.root.findActivity<AppCompatActivity>()!!

        init {
            binding.root.setCardBackgroundColor(randomColor())
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                activity.startActivity(Intent(activity, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
            }
        }

        var tag: Tag? = null
        fun bind(tag: Tag) {
            this.tag = tag
            binding.text1.text = tag.name
        }
    }

    class ImageHolder(private val binding: UserImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val activity get() = binding.root.findActivity<AppCompatActivity>()!!

        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
            binding.root.setOnClickListener {
                val adapter = bindingAdapter as ImageAdapter
                val title = adapter.currentList.reversed().dropWhile { it != tag }.mapNotNull { it as? Title }.firstOrNull()!!
                val images = adapter.currentList.dropWhile { it != title }.takeWhile { it != tag }
                val options = ActivityOptions.makeSceneTransitionAnimation(activity, binding.root, "shared_element_container")
                activity.startActivity(
                    Intent(context, PreviewActivity::class.java).putExtra("query", Q(title.query)).putExtra("index", images.size - 1),
                    options.toBundle()
                )
            }
        }

        var tag: JImageItem? = null
        private val progress = ProgressBehavior.progress(activity, binding.progress)
        fun bind(item: JImageItem) {
            progress.postValue(item.preview_url)
            tag = item
            val height = binding.root.resources.getDimensionPixelSize(R.dimen.user_image_height) * 0.75F
            binding.root.minimumWidth = (height * item.preview_width / item.preview_height).roundToInt()
            binding.image1.glideUrl(item.preview_url, R.mipmap.ic_launcher_foreground)
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
        }
    }

    class ImageAdapter : ListAdapter<Parcelable, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
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
        runCatching { Service.instance.vote(post, authenticity_token = Service.csrf()!!) }
            .getOrNull()?.vote?.let { score ->
                star.postValue(score)
                runCatching { Service.instance.vote(post, score, authenticity_token = Service.csrf()!!) }.getOrNull()
            }
    } finally {
        busy.postValue(false)
    }

    suspend fun vote(post: Int, score: Int) = try {
        busy.postValue(true)
        runCatching { Service.instance.vote(post, score, authenticity_token = Service.csrf()!!) }.getOrNull()
    } finally {
        busy.postValue(false)
    }
}

class StarViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = StarViewModel(handle) as T
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
            lifecycleScope.launchWhenCreated {
                model.data(post)?.let { model.data.postValue(it) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentStarBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            model.star.observe(viewLifecycleOwner, Observer {
                binding.rating.rating = it.toFloat()
                requireActivity().title = getString(R.string.query_vote_star_yours, it)
            })
            lifecycleScope.launchWhenCreated {
                adapter.changedFlow.collectLatest {
                    adapter.currentList.mapIndexedNotNull { index, any -> (any as? Title)?.let { index } }
                        .forEach { adapter.notifyItemChanged(it, "count") }
                }
            }
            binding.rating.setOnRatingBarChangeListener { _, rating, fromUser ->
                if (!fromUser) return@setOnRatingBarChangeListener
                lifecycleScope.launchWhenCreated {
                    model.vote(post, rating.toInt())?.let {
                        model.data.postValue(it)
                        model.star.postValue(rating.toInt())
                    }
                }
            }
            binding.button1.setOnClickListener {
                lifecycleScope.launchWhenCreated {
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
            model.data.observe(viewLifecycleOwner, Observer { score ->
                adapter.submitList(score.voted_by.v.flatMap { listOf(Title(it.key, it.value.size)) + it.value })
            })
            binding.swipe.setOnRefreshListener {
                lifecycleScope.launchWhenCreated {
                    model.data(post)?.let { model.data.postValue(it) }
                }
            }
            model.busy.observe(viewLifecycleOwner, Observer {
                binding.swipe.isRefreshing = it
            })
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
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), UserActivity::class.java).putExtras(bundleOf("user" to value.id, "name" to value.name)), options.toBundle())
            }
        }

        lateinit var value: ItemUser
        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)
        fun bind(value: ItemUser) {
            progress.postValue(value.face)
            this.value = value
            GlideApp.with(binding.image1).load(value.face)
                .error(R.mipmap.ic_launcher)
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
            binding.text1.text = value.name
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    inner class StarAdapter : ListAdapter<Any, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Title -> 0
            is ItemUser -> 1
            else -> throw IllegalArgumentException()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> TitleHolder(VoteTitleItemBinding.inflate(layoutInflater, parent, false))
            1 -> StarHolder(VoteUserItemBinding.inflate(layoutInflater, parent, false))
            else -> throw IllegalArgumentException()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (holder) {
            is TitleHolder -> holder.bind(getItem(position) as Title)
            is StarHolder -> holder.bind(getItem(position) as ItemUser)
            else -> throw IllegalArgumentException()
        }

        private val changed = ConflatedBroadcastChannel<Unit>()
        val changedFlow get() = changed.asFlow()
        override fun onCurrentListChanged(previousList: MutableList<Any>, currentList: MutableList<Any>) {
            changed.offer(Unit)
        }
    }
}