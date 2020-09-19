package com.github.yueeng.moebooru

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.yueeng.moebooru.databinding.FragmentPreviewBinding
import com.github.yueeng.moebooru.databinding.PreviewItemBinding
import com.github.yueeng.moebooru.databinding.PreviewTagItemBinding
import com.google.android.flexbox.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.gun0912.tedpermission.TedPermission
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*


class PreviewActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment
            ?: PreviewFragment().also { it.arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class PreviewViewModel(handle: SavedStateHandle) : ViewModel() {
    val crop = handle.getLiveData("crop", 0)
}

class PreviewViewModelFactory(owner: SavedStateRegistryOwner, defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = PreviewViewModel(handle) as T
}

class PreviewFragment : Fragment() {
    private val previewModel: PreviewViewModel by viewModels { PreviewViewModelFactory(this, arguments) }
    private val query by lazy { arguments?.getParcelable("query") ?: Q() }
    private val index by lazy { arguments?.getInt("index") ?: -1 }
    private val adapter by lazy { ImageAdapter() }
    private val tagAdapter by lazy { TagAdapter() }
    private val model: ImageViewModel by sharedViewModels({ query.toString() }) { ImageViewModelFactory(this, arguments) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentPreviewBinding.inflate(inflater, container, false).also { binding ->
            binding.pager.adapter = adapter
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.STRETCH
                justifyContent = JustifyContent.FLEX_START
            }
            binding.recycler.itemAnimator = null
            binding.recycler.adapter = tagAdapter
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    .distinctUntilChangedBy { it.refresh }
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect { if (index >= 0) binding.pager.post { binding.pager.setCurrentItem(index, false) } }
            }
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val item = adapter.snapshot()[position] ?: return
                    lifecycleScope.launchWhenCreated {
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
                        val tags = item.tags.split(' ').map {
                            withContext(Dispatchers.IO) { async { Q.suggest(it, true).firstOrNull { i -> i.second == it } } }
                        }.mapNotNull { it.await() }.map { Tag(it.first, it.second.toTitleCase(), it.second) }
                        TransitionManager.beginDelayedTransition(binding.sliding, ChangeBounds())
                        tagAdapter.submitList((common + tags).sortedWith(compareBy({ -it.type }, Tag::name, Tag::tag)))
                    }
                    GlideApp.with(binding.button7).load(OAuth.face(item.creator_id))
                        .placeholder(R.mipmap.ic_launcher)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.button7)
                }
            })
            binding.button1.setOnClickListener {
                fun download() {
                    val item = adapter.peek(binding.pager.currentItem) ?: return
                    val filename = item.jpeg_file_name
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
                            put(MediaStore.MediaColumns.ALBUM, moeHost)
                        }
                    }
                    val target = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                    Save.save(item, target) {
                        it?.let { uri ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                values.clear()
                                values.put(MediaStore.MediaColumns.IS_PENDING, false)
                                requireContext().contentResolver.update(target, values, null, null)
                            }
                            MainApplication.instance().notifyDownloadComplete(uri, item.id, filename)
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) download() else {
                    TedPermission.with(requireContext()).setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).onGranted(binding.root) {
                        download()
                    }.check()
                }
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
            val bottomSheetBehavior = BottomSheetBehavior.from(binding.sliding)
            binding.button3.setOnClickListener {
                val open = bottomSheetBehavior.isOpen
                if (open) bottomSheetBehavior.close() else bottomSheetBehavior.open()
            }
            bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                }

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
                    previewModel.crop.value = item.id
                    val dest = File(MainApplication.instance().cacheDir, UUID.randomUUID().toString())
                    val option = UCrop.Options().apply {
                        setAllowedGestures(UCropActivity.SCALE, UCropActivity.NONE, UCropActivity.SCALE)
                        setHideBottomControls(true)
                    }
                    UCrop.of(Uri.fromFile(file), Uri.fromFile(dest))
                        .withAspectRatio(1F, 1F).withOptions(option)
                        .start(requireContext(), this@PreviewFragment, UCrop.REQUEST_CROP + 1)
                })
            }
            binding.button5.setOnClickListener {
                TedPermission.with(requireContext()).setPermissions(Manifest.permission.SET_WALLPAPER).onGranted(binding.root) {
                    val item = adapter.peek(binding.pager.currentItem) ?: return@onGranted
                    val file = File(requireContext().cacheDir, item.jpeg_file_name)
                    Save.save(item, Uri.fromFile(file)) {
                        it?.let { uri ->
                            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                                WallpaperManager.getInstance(requireContext()).setStream(stream)
                            }
                        }
                    }
                }.check()
            }
            binding.button6.setOnClickListener {
                val item = adapter.peek(binding.pager.currentItem) ?: return@setOnClickListener
                val file = File(requireContext().cacheDir, item.jpeg_file_name)
                Save.save(item, Uri.fromFile(file)) {
                    it?.let { source ->
                        lifecycleScope.launchWhenCreated {
                            val dest = File(File(MainApplication.instance().cacheDir, "shared").apply { mkdirs() }, item.jpeg_file_name)
                            UCrop.of(source, Uri.fromFile(dest)).start(requireContext(), this@PreviewFragment, UCrop.REQUEST_CROP)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                when (requestCode) {
                    UCrop.REQUEST_CROP -> {
                        val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", File(UCrop.getOutput(data!!)!!.path!!))
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, getString(R.string.app_share)))
                    }
                    UCrop.REQUEST_CROP + 1 -> {
                        if (data != null) {
                            val ow = data.getIntExtra(UCrop.EXTRA_OUTPUT_ORIGIN_WIDTH, 0)
                            val oh = data.getIntExtra(UCrop.EXTRA_OUTPUT_ORIGIN_HEIGHT, 0)
                            val w = data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_WIDTH, 0)
                            val h = data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_HEIGHT, 0)
                            val x = data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_X, 0)
                            val y = data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_Y, 0)
                            OAuth.avatar(this, OAuth.user.value!!, previewModel.crop.value!!, 1F * x / ow, 1F * (x + w) / ow, 1F * y / oh, 1F * (y + h) / oh) {
                                Toast.makeText(requireContext(), R.string.app_complete, Toast.LENGTH_SHORT).show()
                            }
                        }
                        previewModel.crop.value = 0
                    }
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class ImageHolder(private val binding: PreviewItemBinding) : RecyclerView.ViewHolder(binding.root), LifecycleOwner {
        init {
            binding.image1.setBitmapDecoderClass(GlideDecoder::class.java)
            binding.image1.setRegionDecoderClass(GlideRegionDecoder::class.java)
            binding.image1.setOnClickListener {
                val pager = FragmentPreviewBinding.bind(requireView())
                val behavior = BottomSheetBehavior.from(pager.sliding)
                if (behavior.isOpen) behavior.close()
                else if (pager.pager.currentItem + 1 < adapter.itemCount) pager.pager.setCurrentItem(pager.pager.currentItem + 1, true)
            }
            binding.image1.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onImageLoaded() {
                    binding.progress.isInvisible = true
                }
            })
        }

        @FlowPreview
        fun bind(item: JImageItem) {
            binding.progress.isVisible = true
            lifecycleScope.launchWhenCreated {
                ProgressBehavior.on(item.sample_url).asFlow().sample(1000).collectLatest {
                    binding.progress.isIndeterminate = it == 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        binding.progress.setProgress(it, true)
                    } else {
                        binding.progress.progress = it
                    }
                }
            }
            binding.image1.setImage(ImageSource.uri(item.sample_url).dimensions(item.sample_width, item.sample_height), ImageSource.uri(item.preview_url))
        }

        val lifecycle = LifecycleRegistry(this)
        override fun getLifecycle(): Lifecycle = lifecycle
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        @FlowPreview
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(PreviewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onViewAttachedToWindow(holder: ImageHolder) {
            holder.lifecycle.currentState = Lifecycle.State.CREATED
        }

        override fun onViewDetachedFromWindow(holder: ImageHolder) {
            holder.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
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

    inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        private val differ = AsyncListDiffer(AdapterListUpdateCallback(this), AsyncDifferConfig.Builder(diffCallback<Tag> { o, n -> o.tag == n.tag }).build())
        private val data get() = differ.currentList
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder = TagHolder(PreviewTagItemBinding.inflate(layoutInflater, parent, false)).apply {
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(data[bindingAdapterPosition].tag)), options.toBundle())
            }
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) = holder.bind(data[position])
        override fun getItemCount(): Int = data.size
        fun submitList(tags: List<Tag>?) = differ.submitList(tags)
    }
}
