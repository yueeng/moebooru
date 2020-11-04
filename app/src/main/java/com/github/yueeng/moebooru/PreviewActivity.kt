package com.github.yueeng.moebooru

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.FileProvider
import androidx.core.content.res.use
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import androidx.work.WorkInfo
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.yueeng.moebooru.Save.fileName
import com.github.yueeng.moebooru.databinding.FragmentPreviewBinding
import com.github.yueeng.moebooru.databinding.PreviewItemBinding
import com.github.yueeng.moebooru.databinding.PreviewTagItemBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import com.gun0912.tedpermission.TedPermission
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import kotlin.math.max


class PreviewActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.run {
            val fragment = supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment
                ?: PreviewFragment().also { it.arguments = intent.extras }
            val mine = findFragmentById(R.id.mine) as? UserFragment ?: UserFragment()
            val saved = findFragmentById(R.id.saved) as? SavedFragment ?: SavedFragment()
            beginTransaction().replace(R.id.container, fragment)
                .replace(R.id.mine, mine)
                .replace(R.id.saved, saved)
                .commit()
        }
    }

    override fun onBackPressed() {
        if ((supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment)?.onBackPressed() == true) return
        super.onBackPressed()
    }
}

class PreviewViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val index = handle.getLiveData("index", args?.getInt("index") ?: 0)
    val crop = handle.getLiveData<JImageItem>("crop")
}

class PreviewViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = PreviewViewModel(handle, defaultArgs) as T
}

class PreviewFragment : Fragment(), SavedFragment.Queryable {
    private val previewModel: PreviewViewModel by viewModels { PreviewViewModelFactory(this, arguments) }
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    private val binding by lazy { FragmentPreviewBinding.bind(requireView()) }
    private val adapter by lazy { ImageAdapter() }
    private val tagAdapter by lazy { TagAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentPreviewBinding.inflate(inflater, container, false).also { binding ->
            (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
            activity?.title = query.toString().toTitleCase()
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
            binding.pager.adapter = adapter
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = previewModel.index.postValue(position)
            })
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    .distinctUntilChangedBy { it.refresh }
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect {
                        if (previewModel.index.value!! >= 0) binding.pager.post {
                            binding.pager.setCurrentItem(previewModel.index.value!!, false)
                        }
                    }
            }
            lifecycleScope.launchWhenCreated {
                previewModel.index.asFlow().filter { it < adapter.itemCount }.mapNotNull { adapter.peek(it) }.collectLatest { item ->
                    ProgressBehavior.on(item.sample_url).onCompletion {
                        binding.progress1.isInvisible = true
                        binding.progress1.progress = 0
                        binding.progress1.alpha = 1F
                        binding.progress1.isIndeterminate = true
                        (binding.progress1.tag as? ObjectAnimator)?.cancel()
                    }.sample(500).collectLatest {
                        binding.progress1.setIndeterminateSafe(it == -1)
                        binding.progress1.setProgressCompat(max(0, it))
                        if (it != 100) binding.progress1.isInvisible = false else {
                            binding.progress1.tag = ObjectAnimator.ofFloat(binding.progress1, "alpha", 1F, 0F)
                                .setDuration(2000)
                                .apply {
                                    doOnEnd { binding.progress1.isInvisible = true }
                                    start()
                                }
                        }
                    }
                }
            }
            val background = MutableLiveData<Bitmap?>()
            lifecycleScope.launchWhenCreated {
                var anim: ObjectAnimator? = null
                fun trans(to: Int) {
                    val from = (binding.root.background as? ColorDrawable)?.color ?: 0
                    val target = if (to != 0) to else requireActivity().theme.obtainStyledAttributes(intArrayOf(R.attr.colorSurface)).use {
                        it.getColor(0, Color.WHITE)
                    }
                    anim = ObjectAnimator.ofObject(binding.root, "backgroundColor", ArgbEvaluator(), from, target).apply {
                        duration = 300
                        start()
                    }
                }
                MoeSettings.previewColor.asFlow().distinctUntilChanged().collectLatest {
                    if (!it) trans(0) else background.asFlow().onCompletion { anim?.cancel() }.collectLatest collect@{ bitmap ->
                        if (bitmap == null) return@collect
                        val palette = withContext(Dispatchers.Default) {
                            Palette.from(bitmap).clearTargets()/*.addTarget(Target.VIBRANT).addTarget(Target.MUTED)*/.generate()
                        }
                        val swatch = palette.dominantSwatch// ?: palette.vibrantSwatch ?: palette.mutedSwatch
                        trans(swatch?.rgb ?: 0)
                    }
                }
            }
            val bottomSheetBehavior = BottomSheetBehavior.from(binding.sliding)
            val tagItem = MutableLiveData<JImageItem>()
            lifecycleScope.launchWhenCreated {
                tagItem.asFlow().filter { bottomSheetBehavior.isOpen }.distinctUntilChanged().collectLatest {
                    tagAdapter.submit(it)
                    TransitionManager.beginDelayedTransition(binding.sliding, ChangeBounds())
                }
            }
            lifecycleScope.launchWhenCreated {
                previewModel.index.asFlow().filter { it < adapter.itemCount }.mapNotNull { adapter.peek(it) }.collectLatest { item ->
                    GlideApp.with(binding.button7).load(OAuth.face(item.creator_id))
                        .placeholder(R.mipmap.ic_launcher)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.button7)
                    GlideApp.with(this@PreviewFragment).asBitmap().load(item.preview_url)
                        .into(SimpleCustomTarget<Bitmap> { background.postValue(it) })
                    tagItem.postValue(item)
                }
            }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.flexDirection = FlexDirection.ROW
            binding.recycler.adapter = tagAdapter
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.busy.isVisible = it.refresh is LoadState.Loading
                }
            }
            binding.button1.setOnClickListener {
                val item = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                download(item.id, if (MoeSettings.quality.value == true) item.file_url else item.jpeg_url, item.author)
            }
            binding.button2.setOnClickListener {
                if (!OAuth.available) {
                    OAuth.login(this) {
                        binding.button2.callOnClick()
                    }
                    return@setOnClickListener
                }
                val item = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), StarActivity::class.java).putExtra("post", item.id), options.toBundle())
            }
            binding.button3.setOnClickListener {
                val open = bottomSheetBehavior.isOpen
                if (open) bottomSheetBehavior.close() else bottomSheetBehavior.open()
            }
            bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) = tagItem.postValue(adapter.peek(binding.pager.currentItem))
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    binding.button3.rotation = slideOffset * 180F
                }
            })
            binding.button4.setOnClickListener {
                if (!OAuth.available) {
                    OAuth.login(this) {
                        binding.button4.callOnClick()
                    }
                    return@setOnClickListener
                }
                val item = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                GlideApp.with(it).asFile().load(item.sample_url).into(SimpleCustomTarget { file ->
                    lifecycleScope.launchWhenCreated {
                        val dest = File(requireContext().cacheDir, UUID.randomUUID().toString())
                        val option = UCrop.Options().apply {
                            setAllowedGestures(UCropActivity.SCALE, UCropActivity.NONE, UCropActivity.SCALE)
                            setHideBottomControls(true)
                        }
                        val crop = UCrop.of(Uri.fromFile(file), Uri.fromFile(dest))
                            .withAspectRatio(1F, 1F).withOptions(option)
                        previewModel.crop.value = item
                        cropAvatar.launch(crop)
                    }
                })
            }
            binding.button5.setOnClickListener {
                TedPermission.with(requireContext()).setPermissions(Manifest.permission.SET_WALLPAPER).onGranted(binding.root) {
                    val item = adapter.peek(binding.pager.currentItem) ?: return@onGranted
                    val file = File(File(requireContext().cacheDir, "shared").apply { mkdirs() }, item.jpeg_url.fileName)
                    val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                    Save.save(item.id, item.jpeg_url, uri, Save.SO.WALLPAPER)
                }.check()
            }
            binding.button6.setOnClickListener {
                val item = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                val file = File(requireContext().cacheDir, item.jpeg_url.fileName)
                Save.save(item.id, item.jpeg_url, Uri.fromFile(file), Save.SO.OTHER, tip = false) {
                    lifecycleScope.launchWhenCreated {
                        it?.let { source ->
                            val dest = File(File(requireContext().cacheDir, "shared").apply { mkdirs() }, item.jpeg_url.fileName)
                            previewModel.crop.value = item
                            cropShare.launch(UCrop.of(source, Uri.fromFile(dest)))
                        }
                    }
                }
            }
            binding.button7.setOnClickListener {
                if (!OAuth.available) {
                    OAuth.login(this) {
                        binding.button7.callOnClick()
                    }
                    return@setOnClickListener
                }
                val model = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), UserActivity::class.java).putExtras(bundleOf("user" to model.creator_id, "name" to model.author)), options.toBundle())
            }
        }.root

    private fun download(id: Int, url: String, author: String) {
        fun download() {
            val filename = url.fileName
            val extension = MimeTypeMap.getFileExtensionFromUrl(filename)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, true)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$moeHost")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put(MediaStore.MediaColumns.ARTIST, author.toTitleCase())
                    put(MediaStore.MediaColumns.ALBUM, moeHost)
                }
            }
            val target = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            Save.save(id, url, target, Save.SO.SAVE)
        }

        fun check() {
            lifecycleScope.launchWhenCreated {
                when (Save.check("save-${id}")) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> {
                        Toast.makeText(requireContext(), getString(R.string.download_running), Toast.LENGTH_SHORT).show()
                        return@launchWhenCreated
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        Snackbar.make(binding.button1, getString(R.string.download_exists), Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.button1)
                            .setAction(R.string.app_ok) { download() }
                            .show()
                        return@launchWhenCreated
                    }
                    else -> download()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) check() else {
            TedPermission.with(requireContext()).setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).onGranted(binding.root) {
                check()
            }.check()
        }
    }

    private val cropShare = registerForActivityResult(CropImage()) { result ->
        val item = previewModel.crop.value ?: return@registerForActivityResult
        previewModel.crop.value = null
        if (result == null) return@registerForActivityResult
        val extension = MimeTypeMap.getFileExtensionFromUrl(result.output.path!!)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", File(result.output.path!!))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }, getString(R.string.app_share)))
        requireContext().notifyImageComplete(uri, item.id, getString(R.string.app_name), item.jpeg_url.fileName)
    }

    private val cropAvatar = registerForActivityResult(CropImage()) { data ->
        val item = previewModel.crop.value ?: return@registerForActivityResult
        previewModel.crop.value = null
        if (data == null) return@registerForActivityResult
        OAuth.avatar(this, OAuth.user.value!!, item.id, 1F * data.offsetX / data.originWidth, 1F * (data.offsetX + data.imageWidth) / data.originWidth, 1F * data.offsetY / data.originHeight, 1F * (data.offsetY + data.imageHeight) / data.originHeight) {
            Toast.makeText(requireContext(), R.string.app_complete, Toast.LENGTH_SHORT).show()
        }
    }

    fun onBackPressed(): Boolean {
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.sliding)
        val open = bottomSheetBehavior.isOpen
        if (open) bottomSheetBehavior.close()
        return open
    }

    inner class ImageHolder(private val binding: PreviewItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.image1.setOnClickListener {
                val fragment = this@PreviewFragment
                val pager = fragment.binding
                val behavior = BottomSheetBehavior.from(pager.sliding)
                if (behavior.isOpen) behavior.close()
                else if (pager.pager.currentItem + 1 < fragment.adapter.itemCount) pager.pager.setCurrentItem(pager.pager.currentItem + 1, true)
            }
        }

        private val progress = ProgressBehavior.progress(viewLifecycleOwner, binding.progress)

        @OptIn(FlowPreview::class)
        fun bind(item: JImageItem) {
            progress.postValue(item.sample_url)
            GlideApp.with(binding.image1).load(item.sample_url)
                .placeholder(R.mipmap.ic_launcher_foreground)
                .thumbnail(GlideApp.with(binding.image1).load(item.preview_url).transition(DrawableTransitionOptions.withCrossFade()).onResourceReady { _, _, _, _, _ ->
                    binding.image1.setImageDrawable(null)
                    false
                })
                .onComplete { _, _, _, _ -> progress.postValue(""); false }
                .into(binding.image1)
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(PreviewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    class TagHolder(val binding: PreviewTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
        }

        fun bind(value: Tag) {
            binding.text1.text = value.name
            binding.text2.text = value.string
            binding.text2.isGone = binding.text2.text.isNullOrEmpty()
            binding.category.setBackgroundColor(value.color)
            binding.root.setCardBackgroundColor(randomColor())
        }
    }

    inner class TagAdapter : ListAdapter<Tag, TagHolder>(diffCallback { old, new -> old.tag == new.tag }) {
        private val data get() = currentList
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder =
            TagHolder(PreviewTagItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)).apply {
                binding.root.setOnClickListener {
                    val tag = data[bindingAdapterPosition]
                    when (tag.type) {
                        Tag.TYPE_URL -> requireActivity().openWeb(tag.tag)
                        Tag.TYPE_DOWNLOAD -> download(item.id, tag.tag, item.author)
                        else -> if (tag.tag.isNotEmpty()) {
                            val options = ActivityOptions.makeSceneTransitionAnimation(activity, it, "shared_element_container")
                            requireActivity().startActivity(Intent(activity, ListActivity::class.java).putExtra("query", Q(tag.tag)), options.toBundle())
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    binding.root.setOnLongClickListener { view ->
                        val tag = data[bindingAdapterPosition]
                        view.showSupportedActivitiesMenu(tag.name).menu.size() > 0
                    }
                }
            }

        override fun onBindViewHolder(holder: TagHolder, position: Int) = holder.bind(data[position])

        private lateinit var item: JImageItem
        suspend fun submit(item: JImageItem) {
            this.item = item
            if (currentList.size == 0) submitList(listOf(Tag(Tag.TYPE_UNKNOWN, "Waiting...", "")))
            val tags = withContext(Dispatchers.Default) {
                val common = listOf(
                    Tag(Tag.TYPE_USER, item.author.toTitleCase(), "user:${item.author}"),
                    Tag(Tag.TYPE_SIZE, "${item.width}x${item.height}", "width:${item.width} height:${item.height}"),
                    Tag(Tag.TYPE_SIZE, item.resolution.title, Q().mpixels(item.resolution.mpixels, Q.Value.Op.ge).toString())
                ).toMutableList()
                if (item.has_children) {
                    common.add(Tag(Tag.TYPE_CHILDREN, "Children", "parent:${item.id}"))
                }
                if (item.parent_id != 0) {
                    common.add(Tag(Tag.TYPE_PARENT, "Parent", "id:${item.parent_id}"))
                }
                if (item.source.isNotEmpty()) {
                    common.add(Tag(Tag.TYPE_URL, "Source", item.source))
                }
                listOf(item.jpeg_url to item.jpeg_file_size, item.file_url to item.file_size).filter { it.first.isNotEmpty() }.forEach { i ->
                    val extension = MimeTypeMap.getFileExtensionFromUrl(i.first)
                    val name = "${extension.toUpperCase(Locale.ROOT)}${i.second.takeIf { it != 0 }?.toLong()?.sizeString()?.let { "[$it]" } ?: ""}"
                    common.add(Tag(Tag.TYPE_DOWNLOAD, name, i.first))
                }
                val tags = item.tags.split(' ').map {
                    Q.suggest(it, true).firstOrNull { i -> i.second == it }
                }.mapNotNull { it }.map { Tag(it.first, it.second.toTitleCase(), it.second) }
                (common + tags).sortedWith(compareBy({ -it.type }, Tag::name, Tag::tag))
            }
            submitList(tags)
        }
    }

    override fun query(): Q? = query
}
