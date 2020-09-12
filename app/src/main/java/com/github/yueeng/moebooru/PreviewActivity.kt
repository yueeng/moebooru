package com.github.yueeng.moebooru

import android.Manifest
import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.yueeng.moebooru.databinding.FragmentPreviewBinding
import com.github.yueeng.moebooru.databinding.PreviewItemBinding
import com.github.yueeng.moebooru.databinding.PreviewTagItemBinding
import com.google.android.flexbox.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.gun0912.tedpermission.TedPermission
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import java.io.File


class PreviewActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment
            ?: PreviewFragment().also { it.arguments = intent.extras }
        val saved = supportFragmentManager.findFragmentById(R.id.container) as? SavedFragment ?: SavedFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .replace(R.id.saved, saved).commit()
    }
}

class PreviewFragment : Fragment() {
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
                        val tags = withContext(Dispatchers.IO) {
                            item.tags.split(' ').map { async { Q.suggest(it, true).firstOrNull { i -> i.second == it } } }
                        }.mapNotNull { it.await() }
                            .map { Tag(it.first, it.second.toTitleCase(), it.second) }
                            .sortedWith(compareBy({ -it.type }, Tag::name, Tag::tag))
                        TransitionManager.beginDelayedTransition(binding.sliding)
                        tagAdapter.submitList(tags)
                    }
                    GlideApp.with(binding.button7).load(OAuth.face(item.creator_id))
                        .placeholder(R.mipmap.ic_launcher)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .circleCrop()
                        .into(binding.button7)
                }
            })
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
            binding.button1.setOnClickListener {
                TedPermission.with(requireContext()).setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).onGranted(binding.root) {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), moe_host).apply { mkdirs() }
                    val item = adapter.peek(binding.pager.currentItem)!!
                    Save.save(item, folder.path) {
                        it?.let { File(it) }?.let { file ->
                            val extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString())
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                            MainApplication.instance().apply {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    type = mime ?: "image/*"
                                    data = uri
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    NotificationManagerCompat.from(this@apply).let { manager ->
                                        val channel = NotificationChannel(moe_host, moe_host, NotificationManager.IMPORTANCE_DEFAULT)
                                        manager.createNotificationChannel(channel)
                                    }
                                }
                                val builder = NotificationCompat.Builder(MainApplication.instance(), moe_host)
                                    .setContentTitle(getString(R.string.app_download, getString(R.string.app_name)))
                                    .setContentText(file.name)
                                    .setAutoCancel(true)
                                    .setSmallIcon(R.drawable.ic_stat_name)
                                    .setContentIntent(PendingIntent.getActivity(this@apply, item.id, Intent.createChooser(intent, getString(R.string.app_share)), PendingIntent.FLAG_ONE_SHOT))
                                GlideApp.with(this).asBitmap().load(file).override(500, 500)
                                    .into(object : CustomTarget<Bitmap>() {
                                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                            val bigPictureStyle = NotificationCompat.BigPictureStyle()
                                                .bigPicture(resource)
                                            val notification = builder
                                                .setStyle(bigPictureStyle)
                                                .setLargeIcon(resource)
                                                .build()
                                            NotificationManagerCompat.from(this@apply).notify(item.id, notification)
                                        }

                                        override fun onLoadCleared(placeholder: Drawable?) {
                                            NotificationManagerCompat.from(this@apply).notify(item.id, builder.build())
                                        }
                                    })
                            }
                        }
                    }
                }.check()
            }
            binding.button5.setOnClickListener {
                TedPermission.with(requireContext()).setPermissions(Manifest.permission.SET_WALLPAPER).onGranted(binding.root) {
                    Save.save(adapter.peek(binding.pager.currentItem)!!, requireContext().cacheDir.path) {
                        it?.let { File(it) }?.inputStream()?.use { stream ->
                            WallpaperManager.getInstance(requireContext()).setStream(stream)
                        }
                    }
                }.check()
            }
            binding.button6.setOnClickListener {
                Save.save(adapter.peek(binding.pager.currentItem)!!, requireContext().cacheDir.path) {
                    it?.let { File(it) }?.let { source ->
                        lifecycleScope.launchWhenCreated {
                            val dest = File(File(MainApplication.instance().cacheDir, "shared").apply { mkdirs() }, source.name)
                            UCrop.of(Uri.fromFile(source), Uri.fromFile(dest)).start(requireContext(), this@PreviewFragment, UCrop.REQUEST_CROP)
                        }
                    }
                }
            }
            binding.button7.setOnClickListener {
                OAuth.login(this@PreviewFragment) {
                    if (it) {
                        val model = adapter.peek(binding.pager.currentItem)!!
                        startActivity(Intent(requireContext(), UserActivity::class.java).putExtras(bundleOf("user" to model.creator_id, "name" to model.author)))
                    }
                }
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
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class ImageHolder(private val binding: PreviewItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.image1.setBitmapDecoderClass(GlideDecoder::class.java)
            binding.image1.setRegionDecoderClass(GlideRegionDecoder::class.java)
            binding.image1.setOnClickListener {
                val pager = FragmentPreviewBinding.bind(requireView())
                val behavior = BottomSheetBehavior.from(pager.sliding)
                if (behavior.isOpen) behavior.close()
                else if (pager.pager.currentItem + 1 < adapter.itemCount) pager.pager.setCurrentItem(pager.pager.currentItem + 1, true)
            }
        }

        fun bind(item: JImageItem) {
            binding.progress.isVisible = true
            DispatchingProgressBehavior.expect(item.sample_url, object : UIonProgressListener {
                override val granualityPercentage: Float
                    get() = 1F

                override fun onProgress(bytesRead: Long, expectedLength: Long) {
                    (100 * bytesRead / expectedLength).toInt().let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            binding.progress.setProgress(it, true)
                        } else {
                            binding.progress.progress = it
                        }
                    }
                }
            })
            binding.image1.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onImageLoaded() {
                    DispatchingProgressBehavior.forget(item.sample_url)
                    binding.progress.isInvisible = true
                }
            })
            binding.image1.setImage(ImageSource.uri(item.sample_url).dimensions(item.sample_width, item.sample_height), ImageSource.uri(item.preview_url))
        }
    }

    inner class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diffCallback { old, new -> old.id == new.id }) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) = holder.bind(getItem(position)!!)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(PreviewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    class TagHolder(private val binding: PreviewTagItemBinding, click: (Int) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
            binding.root.setOnClickListener { click(bindingAdapterPosition) }
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
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder = TagHolder(PreviewTagItemBinding.inflate(layoutInflater, parent, false)) {
            startActivity(Intent(requireContext(), ListActivity::class.java).putExtra("query", Q(data[it].tag)))
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) = holder.bind(data[position])
        override fun getItemCount(): Int = data.size
        fun submitList(tags: List<Tag>?) = differ.submitList(tags)
    }
}
