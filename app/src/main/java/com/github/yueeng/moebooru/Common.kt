@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.yueeng.moebooru

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils.copySpansFrom
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.createViewModelLazy
import androidx.lifecycle.*
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.work.*
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.Excludes
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.github.yueeng.moebooru.MoePermission.Companion.checkPermissions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.sample
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import okio.*
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

fun release(call: () -> Unit) {
    if (!BuildConfig.DEBUG) call()
}

val okCookieCache = SetCookieCache()
val okPersistor = SharedPrefsCookiePersistor(MainApplication.instance())
val okCookie = object : PersistentCookieJar(okCookieCache, okPersistor) {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        okCookieCache.addAll(cookies)
        okPersistor.saveAll(cookies)
        cookies.filter { it.matches(moeUrl.toHttpUrl()) }.firstOrNull { it.name == "user_id" }?.value?.toIntOrNull()?.let {
            OAuth.user.postValue(it)
        }
    }
}
val okDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = when {
        MoeSettings.host.value == true && hostname.endsWith(moeHost) ->
            runCatching { listOf(InetAddress.getByName(MoeSettings.ip.value)) }.getOrNull()
        else -> null
    } ?: Dns.SYSTEM.lookup(hostname)
}

val okHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
//    .writeTimeout(30, TimeUnit.SECONDS)
//    .readTimeout(60, TimeUnit.SECONDS)
//    .hostnameVerifier { _, _ -> true }
    .cache(Cache(MainApplication.instance().cacheDir, (1L shl 20) * (MoeSettings.cache.value ?: 256)))
    .cookieJar(okCookie)
    .dns(okDns)
    .addInterceptor { chain ->
        val agent = WebSettings.getDefaultUserAgent(MainApplication.instance())
        val request = chain.request().newBuilder().header("user-agent", agent).build()
        chain.proceed(request)
    }
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        ProgressBehavior.update(url, 0, 0)
        val response = chain.proceed(request)
        ProgressBehavior.update(url, 0, response.body?.contentLength() ?: 0)
        val body = ProgressResponseBody(response.body!!) { bytesRead, contentLength, _ ->
            ProgressBehavior.update(url, bytesRead, contentLength)
        }
        response.newBuilder().body(body).build()
    }
    .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
    .build()

suspend fun <T> Call.await(action: (Call, Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isCancelled) continuation.resume(action(call, response))
        }
    })
}

class ProgressResponseBody(
    private val responseBody: ResponseBody,
    private val progressListener: (bytesRead: Long, contentLength: Long, done: Boolean) -> Unit
) : ResponseBody() {
    private var bufferedSource: BufferedSource? = null

    override fun contentType(): MediaType? = responseBody.contentType()

    override fun contentLength(): Long = responseBody.contentLength()

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source()).buffer()
        }
        return bufferedSource!!
    }

    private fun source(source: Source): Source = object : ForwardingSource(source) {
        var totalBytesRead = 0L

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            val bytesRead = super.read(sink, byteCount)
            // read() returns the number of bytes read, or -1 if this source is exhausted.
            totalBytesRead += if (bytesRead != -1L) bytesRead else 0
            progressListener(totalBytesRead, contentLength(), bytesRead == -1L)
            return bytesRead
        }
    }
}

@GlideModule
@Excludes(com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule::class)
class MoeAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(okHttp))
    }
}

object ProgressBehavior {
    private val map = mutableMapOf<String, MutableLiveData<Int>>()
    private val sum = mutableMapOf<String, Int>()
    fun update(url: String, bytesRead: Long, contentLength: Long) {
        val data = synchronized(map) { map[url] } ?: return
        val progress = if (contentLength == 0L) -1 else (bytesRead * 100 / contentLength)
        data.postValue(progress.toInt())
    }

    fun on(url: String) = synchronized(map) {
        sum[url] = (sum[url] ?: 0) + 1
        map.getOrPut(url) { MutableLiveData() }
    }.asFlow().onCompletion {
        synchronized(map) {
            sum[url] = sum[url]!! - 1
            if ((sum[url] ?: 0) != 0) return@synchronized
            sum.remove(url)
            map.remove(url)
//            Log.i("PBMAPS", "${map.size}")
        }
    }

    @OptIn(FlowPreview::class)
    fun progress(lifecycleOwner: LifecycleOwner, progressBar: ProgressBar) = MutableLiveData<String>().apply {
        lifecycleOwner.lifecycleScope.launchWhenCreated {
            asFlow().collectLatest { image ->
                progressBar.isVisible = false
                progressBar.progress = 0
                progressBar.setIndeterminateSafe(true)
                if (image.isNotEmpty()) on(image).sample(500).collectLatest {
                    progressBar.isGone = it == 100
                    progressBar.setIndeterminateSafe(it == -1)
                    progressBar.setProgressCompat(it)
                }
            }
        }
    }
}

fun <TranscodeType> GlideRequest<TranscodeType>.onResourceReady(call: (resource: TranscodeType, model: Any?, target: Target<TranscodeType>?, dataSource: DataSource?, isFirstResource: Boolean) -> Boolean) = addListener(object : RequestListener<TranscodeType> {
    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<TranscodeType>?, isFirstResource: Boolean): Boolean = false
    override fun onResourceReady(resource: TranscodeType, model: Any?, target: Target<TranscodeType>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean =
        call(resource, model, target, dataSource, isFirstResource)
})

fun <TranscodeType> GlideRequest<TranscodeType>.onLoadFailed(call: (e: GlideException?, model: Any?, target: Target<TranscodeType>?, isFirstResource: Boolean) -> Boolean) = addListener(object : RequestListener<TranscodeType> {
    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<TranscodeType>?, isFirstResource: Boolean): Boolean =
        call(e, model, target, isFirstResource)

    override fun onResourceReady(resource: TranscodeType, model: Any?, target: Target<TranscodeType>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean = false
})

fun <TranscodeType> GlideRequest<TranscodeType>.onComplete(call: (model: Any?, target: Target<TranscodeType>?, isFirstResource: Boolean, succeeded: Boolean) -> Boolean) = addListener(object : RequestListener<TranscodeType> {
    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<TranscodeType>?, isFirstResource: Boolean): Boolean =
        call(model, target, isFirstResource, false)

    override fun onResourceReady(resource: TranscodeType, model: Any?, target: Target<TranscodeType>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean =
        call(model, target, isFirstResource, true)
})

class SimpleCustomTarget<T : Any>(private val call: (T) -> Unit) : CustomTarget<T>() {
    override fun onResourceReady(resource: T, transition: Transition<in T>?) = call(resource)
    override fun onLoadCleared(placeholder: Drawable?) = Unit
}

class SimpleDrawableCustomViewTarget<T : View>(view: T, private val call: (view: T, drawable: Drawable?) -> Unit) : CustomViewTarget<T, Drawable>(view) {
    override fun onLoadFailed(errorDrawable: Drawable?) = call(view, errorDrawable)
    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) = call(view, resource)
    override fun onResourceCleared(placeholder: Drawable?) = call(view, placeholder)
}

fun <T : View> GlideRequest<Drawable>.into(view: T, call: (view: T, drawable: Drawable?) -> Unit) = into(SimpleDrawableCustomViewTarget(view, call))

val random = Random(System.currentTimeMillis())

fun randomColor(alpha: Int = 0xFF, saturation: Float = 1F, value: Float = 0.5F) =
    Color.HSVToColor(alpha, arrayOf(random.nextInt(360).toFloat(), saturation, value).toFloatArray())

fun ImageView.glideUrl(url: String, placeholder: Int? = null): GlideRequest<Drawable> {
    scaleType = ImageView.ScaleType.CENTER
    return GlideApp.with(this)
        .load(url)
        .transition(DrawableTransitionOptions.withCrossFade())
        .run { if (placeholder != null) placeholder(placeholder) else this }
        .onResourceReady { _, _, _, _, _ ->
            setImageDrawable(null)
            scaleType = ImageView.ScaleType.CENTER_CROP
            false
        }
}

val ChipGroup.checkedChip: Chip? get() = this.children.mapNotNull { it as? Chip }.firstOrNull { it.isChecked }
var <V : View>BottomSheetBehavior<V>.isOpen: Boolean
    get() = state == BottomSheetBehavior.STATE_EXPANDED
    set(value) {
        state = if (value) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
    }

fun <V : View> BottomSheetBehavior<V>.open() {
    isOpen = true
}

fun <V : View> BottomSheetBehavior<V>.close() {
    isOpen = false
}

class SymbolsTokenizer(private val symbols: Set<Char>) : MultiAutoCompleteTextView.Tokenizer {
    override fun findTokenStart(text: CharSequence, cursor: Int): Int {
        var i = cursor
        while (i > 0 && !symbols.contains(text[i - 1])) i--
        while (i < cursor && text[i] == ' ') i++
        return i
    }

    override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
        var i = cursor
        val len = text.length
        while (i < len) if (symbols.contains(text[i])) return i else i++
        return len
    }

    override fun terminateToken(text: CharSequence): CharSequence {
        var i = text.length
        while (i > 0 && text[i - 1] == ' ') i--
        return when {
            i > 0 && symbols.contains(text[i - 1]) -> text
            text is Spanned -> {
                val sp = SpannableString("$text ")
                copySpansFrom(text, 0, text.length, Any::class.java, sp, 0)
                sp
            }
            else -> "$text "
        }
    }
}

val regexTitleCase = """\b[a-z]""".toRegex()
fun String.toTitleCase(vararg delimiters: String = arrayOf("_")) = delimiters.fold(this) { r, s -> r.replace(s, " ", true) }.let { word ->
    regexTitleCase.findAll(word).fold(word) { r, i ->
        r.replaceRange(i.range, i.value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
    }
}

fun Date.firstDayOfWeek(index: Int = 1, firstOfWeek: Int = Calendar.MONDAY): Date = calendar().let { calendar ->
    calendar.firstDayOfWeek = firstOfWeek
    calendar.time = this
    calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek + index - 1)
    calendar.time
}

fun Date.lastDayOfWeek(): Date = firstDayOfWeek(7)

fun Date.firstDayOfMonth(): Date = calendar().let { calendar ->
    calendar.time = this
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.time
}

fun Date.lastDayOfMonth(): Date = calendar().let { calendar ->
    calendar.time = this
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.roll(Calendar.DAY_OF_MONTH, -1)
    calendar.time
}

fun Date.firstDayOfYear(): Date = calendar().let { calendar ->
    calendar.time = this
    calendar.set(Calendar.MONTH, 0)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.time
}

fun Date.lastDayOfYear(): Date = calendar().let { calendar ->
    calendar.time = this
    calendar.set(Calendar.MONTH, 0)
    calendar.roll(Calendar.MONTH, -1)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.roll(Calendar.DAY_OF_MONTH, -1)
    calendar.time
}

class TimeSpan(val end: Calendar, val begin: Calendar) {
    val milliseconds get() = end.timeInMillis - begin.timeInMillis
    val seconds get() = milliseconds / 1000
    val minutes get() = seconds / 60
    val hours get() = minutes / 60
    val days: Int get() = (hours / 24).toInt()
    val weeks: Int get() = days / 7
    val months: Int get() = (end.year - begin.year) * 12 + (end.month - begin.month) + 1
    val year: Int get() = end.year - begin.year + 1
    val daysGreedy: Int
        get() = days + when {
            end.timeInMillis < begin.timeInMillis -> when {
                end.millisecondOfDay > begin.millisecondOfDay -> -2
                else -> -1
            }
            end.millisecondOfDay < begin.millisecondOfDay -> 2
            else -> 1
        }
    val weeksGreedy: Int
        get() = weeks + when {
            end.timeInMillis < begin.timeInMillis -> when {
                end.dayOfWeek > begin.dayOfWeek -> -2
                else -> -1
            }
            end.dayOfWeek < begin.dayOfWeek -> 2
            else -> 1
        }
}

operator fun Calendar.minus(other: Calendar): TimeSpan = TimeSpan(this, other)
val utcTimeZone: TimeZone by lazy { TimeZone.getTimeZone("UTC") }
fun calendar(zone: TimeZone? = null): Calendar = Calendar.getInstance(zone ?: utcTimeZone)
fun Calendar.year(year: Int, add: Boolean = false) = apply { if (add) add(Calendar.YEAR, year) else set(Calendar.YEAR, year) }
fun Calendar.month(month: Int, add: Boolean = false) = apply { if (add) add(Calendar.MONTH, month) else set(Calendar.MONTH, month) }
fun Calendar.day(day: Int, add: Boolean = false) = apply { if (add) add(Calendar.DAY_OF_MONTH, day) else set(Calendar.DAY_OF_MONTH, day) }
fun Calendar.hour(hour: Int, add: Boolean = false) = apply { if (add) add(Calendar.HOUR_OF_DAY, hour) else set(Calendar.HOUR_OF_DAY, hour) }
fun Calendar.minute(minute: Int, add: Boolean = false) = apply { if (add) add(Calendar.MINUTE, minute) else set(Calendar.MINUTE, minute) }
fun Calendar.second(second: Int, add: Boolean = false) = apply { if (add) add(Calendar.SECOND, second) else set(Calendar.SECOND, second) }
fun Calendar.millisecond(millisecond: Int, add: Boolean = false) = apply { if (add) add(Calendar.MILLISECOND, millisecond) else set(Calendar.MILLISECOND, millisecond) }
fun Calendar.dayOfWeek(day: Int, add: Boolean = false) = apply { if (add) add(Calendar.DAY_OF_WEEK, day) else set(Calendar.DAY_OF_WEEK, day) }
fun Calendar.dayOfWeekWithLocale(day: Int, add: Boolean = false) = dayOfWeek((firstDayOfWeek + day - 1) % 7, add)
fun Calendar.weekOfYear(week: Int, add: Boolean = false) = apply { if (add) add(Calendar.WEEK_OF_YEAR, week) else set(Calendar.WEEK_OF_YEAR, week) }
val Calendar.firstDayOfWeekWithLocale get() = copy().dayOfWeekWithLocale(1)
val Calendar.lastDayOfWeekWithLocale get() = copy().dayOfWeekWithLocale(7)
val Calendar.firstDayOfMonth get() = copy().day(1)
val Calendar.lastDayOfMonth get() = copy().month(1, true).day(-1)
val Calendar.year get() = get(Calendar.YEAR)
val Calendar.month get() = get(Calendar.MONTH)
val Calendar.day get() = get(Calendar.DAY_OF_MONTH)
val Calendar.dayOfWeek get() = get(Calendar.DAY_OF_WEEK)
val Calendar.dayOfWeekWithLocale get() = (dayOfWeek - firstDayOfWeek + 7) % 7 + 1
val Calendar.hour get() = get(Calendar.HOUR_OF_DAY)
val Calendar.minute get() = get(Calendar.MINUTE)
val Calendar.minuteOfDay get() = hour * 60 + minute
val Calendar.second get() = get(Calendar.SECOND)
val Calendar.secondOfDay get() = minuteOfDay * 60 + second
val Calendar.millisecond get() = get(Calendar.MILLISECOND)
val Calendar.millisecondOfDay get() = secondOfDay * 1000 + millisecond
val Calendar.milliseconds get() = timeInMillis
fun Calendar.format(df: DateFormat): String = df.format(time)
fun Calendar.copy(): Calendar = clone() as Calendar
fun DateFormat.tryParse(string: CharSequence): Date? = try {
    parse(string.toString())
} catch (e: Exception) {
    null
}

var DatePicker.date
    get() = calendar().year(year).month(month).day(dayOfMonth)
    set(value) = updateDate(value.year, value.month, value.day)

inline fun <reified VM : ViewModel> Fragment.sharedViewModels(
    noinline key: () -> String,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
) = createViewModelLazy(VM::class, { SharedViewModelStoreOwner(key(), this).viewModelStore }, factoryProducer = factoryProducer)

inline fun <reified VM : ViewModel> Fragment.sharedActivityViewModels(
    noinline key: () -> String,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
) = createViewModelLazy(VM::class, { SharedViewModelStoreOwner(key(), requireActivity()).viewModelStore }, factoryProducer = factoryProducer ?: { requireActivity().defaultViewModelProviderFactory })

class SharedViewModelStoreOwner(private val key: String, life: LifecycleOwner) : ViewModelStoreOwner, LifecycleEventObserver {
    companion object {
        private data class CounterViewModelStore(var count: Int = 0, val store: Lazy<ViewModelStore> = lazy { ViewModelStore() })

        private val map = mutableMapOf<String, CounterViewModelStore>()
        private fun add(key: String): Unit = synchronized(map) {
            map.getOrPut(key) { CounterViewModelStore() }.count++
        }

        private fun remove(key: String): Unit = synchronized(map) {
            val store = map[key] ?: return
            if (--store.count > 0) return
            map.remove(key)
        }
    }

    init {
        life.lifecycle.addObserver(this)
    }

    override fun getViewModelStore(): ViewModelStore = map[key]?.store?.value ?: throw IllegalArgumentException("ViewModelStore lazy error.")

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_CREATE) add(key)
        else if (event == Lifecycle.Event.ON_DESTROY) {
            remove(key)
            source.lifecycle.removeObserver(this)
        }
    }
}

class MarginItemDecoration(private val spaceHeight: Int) : RecyclerView.ItemDecoration() {
    constructor(unit: Int, spaceHeight: Float) : this(TypedValue.applyDimension(unit, spaceHeight, MainApplication.instance().resources.displayMetrics).toInt())

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) = with(outRect) {
        top = spaceHeight
        left = spaceHeight
        right = spaceHeight
        bottom = spaceHeight
    }
}

object Save {
    enum class SO {
        SAVE, WALLPAPER, CROP, SHARE
    }

    private fun fileNameEncode(path: String): String = """\/:*?"<>|""".fold(path) { r, i ->
        r.replace(i, ' ')
    }

    class SaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        val id by lazy { inputData.getInt("id", 0) }
        val url by lazy { inputData.getString("url")!! }
        val option by lazy { inputData.getInt("option", 0).let { SO.values()[it] } }
        val fileName get() = fileNameEncode(url.toHttpUrl().pathSegments.last())
        val target by lazy { File(applicationContext.cacheDir, UUID.randomUUID().toString()) }
        val notification: NotificationCompat.Builder by lazy {
            NotificationCompat.Builder(context, moeHost)
                .setContentTitle(context.getString(R.string.app_download, context.getString(R.string.app_name)))
                .setContentText(fileName)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setOngoing(true)
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(id, notification.build())

        @SuppressLint("MissingPermission")
        @OptIn(FlowPreview::class)
        override suspend fun doWork(): Result = try {
            notification.setContentTitle(applicationContext.getString(R.string.app_download, applicationContext.getString(R.string.app_name)))
                .setProgress(0, 0, true)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setOngoing(true)
            setForeground(ForegroundInfo(id, notification.build()))
            coroutineScope {
                val channel = Channel<Pair<Long, Long>>(Channel.CONFLATED)
                launch {
                    channel.consumeAsFlow().sample(500).collectLatest {
                        notification.setProgress(it.second.toInt(), it.first.toInt(), false)
                            .setContentText("${it.first.sizeString()}/${it.second.sizeString()}")
                        setForeground(ForegroundInfo(id, notification.build()))
                    }
                }
                launch {
                    okHttp.newCall(Request.Builder().url(url).build()).await { _, response ->
                        response.body?.use {
                            val body = ProgressResponseBody(it) { bytesRead, contentLength, _ ->
                                channel.trySend(bytesRead to contentLength)
                            }
                            channel.trySend(0L to body.contentLength())
                            body.byteStream().use { input ->
                                target.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    channel.close()
                }
            }
            val shared = when (option) {
                SO.SAVE -> {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.ImageColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.ImageColumns.MIME_TYPE, mime)
                        put(MediaStore.Images.ImageColumns.DESCRIPTION, "$id")
                        val del = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.ImageColumns.RELATIVE_PATH, "Pictures/$moeHost")
                            MediaStore.Images.ImageColumns.RELATIVE_PATH to "Pictures/$moeHost"
                        } else @Suppress("DEPRECATION") {
                            val picture = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                            val folder = File(picture, moeHost).apply { mkdirs() }
                            val file = File(folder, fileName)
                            put(MediaStore.Images.ImageColumns.DATA, file.path)
                            MediaStore.Images.ImageColumns.DATA to file.path
                        }
                        applicationContext.contentResolver.delete(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            "${del.first} = ? AND ${MediaStore.Images.ImageColumns.DESCRIPTION} = ?", arrayOf(del.second, "$id")
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            inputData.getString("author")?.let {
                                put(MediaStore.MediaColumns.ARTIST, it.toTitleCase())
                            }
                            put(MediaStore.MediaColumns.ALBUM, moeHost)
                        }
                    }
                    applicationContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also { uri ->
                        @Suppress("BlockingMethodInNonBlockingContext")
                        applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
                            target.inputStream().use { it.copyTo(output) }
                        }
                    }
                }
                SO.SHARE -> {
                    val file = File(File(applicationContext.cacheDir, "shared").apply { mkdirs() }, fileName).also(target::renameTo)
                    FileProvider.getUriForFile(applicationContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                }
                SO.WALLPAPER -> target.inputStream().use { stream ->
                    @Suppress("BlockingMethodInNonBlockingContext")
                    WallpaperManager.getInstance(applicationContext).setStream(stream)
                    null
                }
                SO.CROP -> {
                    applicationContext.startActivity(
                        Intent(applicationContext, CropActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("op", CropActivity.OPTION_SHARE)
                            .putExtra("id", id)
                            .putExtra("name", fileName)
                            .putExtra("source", target.toUri())
                    )
                    null
                }
            }
            if (shared != null) {
                val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(shared, mime ?: "image/$extension")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val padding = PendingIntent.getActivity(applicationContext, id, Intent.createChooser(intent, applicationContext.getString(R.string.app_share)), PendingIntentCompat.FLAG_IMMUTABLE)
                notification.setContentText(fileName)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(padding)
                suspendCancellableCoroutine { continuation ->
                    GlideApp.with(MainApplication.instance()).asBitmap().load(target).override(500, 500)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                notification.setStyle(NotificationCompat.BigPictureStyle().bigPicture(resource))
                                    .setLargeIcon(resource)
                                continuation.resume(Unit)
                            }

                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                continuation.cancel()
                            }

                            override fun onLoadCleared(placeholder: Drawable?) = Unit
                        })
                }
                NotificationManagerCompat.from(applicationContext).notify(id.inv(), notification.build())
            }
            Result.success(Data.Builder().putString("file", target.toString()).build())
        } catch (e: Exception) {
            if (option == SO.SAVE || option == SO.SHARE) {
                notification.setContentText(applicationContext.getText(R.string.app_failed))
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                NotificationManagerCompat.from(applicationContext).notify(id.inv(), notification.build())
            }
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    suspend fun check(key: String): WorkInfo.State? = withTimeout(1000) {
        val manager = WorkManager.getInstance(MainApplication.instance())
        val data = manager.getWorkInfosForUniqueWorkLiveData(key)
        suspendCancellableCoroutine { continuation ->
            val observer = object : androidx.lifecycle.Observer<List<WorkInfo>> {
                override fun onChanged(it: List<WorkInfo>?) {
                    continuation.resume(it?.firstOrNull()?.state)
                    data.removeObserver(this)
                }
            }
            continuation.invokeOnCancellation {
                data.removeObserver(observer)
            }
            data.observeForever(observer)
        }
    }

    fun AppCompatActivity.save(
        id: Int, url: String, so: SO,
        author: String? = null, anchor: View? = null
    ) {
        fun download() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManagerCompat.from(MainApplication.instance()).let { notify ->
                    val channel = NotificationChannel(moeHost, moeHost, NotificationManager.IMPORTANCE_LOW)
                    notify.createNotificationChannel(channel)
                }
            }
            val params = Data.Builder()
                .putInt("id", id)
                .putString("url", url)
                .putInt("option", so.ordinal)
            if (author != null) params.putString("author", author)
            val request = OneTimeWorkRequestBuilder<SaveWorker>().setInputData(params.build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(MainApplication.instance()).apply {
                if (so != SO.SAVE) enqueue(request) else enqueueUniqueWork("save-$id", ExistingWorkPolicy.KEEP, request)
            }
        }

        fun check() {
            lifecycleScope.launchWhenCreated {
                if (anchor == null) download() else when (check("save-${id}")) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> {
                        Toast.makeText(this@save, getString(R.string.download_running), Toast.LENGTH_SHORT).show()
                        return@launchWhenCreated
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        anchor.snack(getString(R.string.download_exists), Snackbar.LENGTH_LONG)
                            .setAnchorView(anchor)
                            .setAction(R.string.app_ok) { download() }
                            .show()
                        return@launchWhenCreated
                    }
                    else -> download()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) check() else {
            checkPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                check()
            }
        }
    }
}

fun Long.sizeString() = when {
    this == Long.MIN_VALUE || this < 0 -> "N/A"
    this < 1024L -> "$this B"
    this <= 0xfffccccccccccccL shr 40 -> "%.1f KiB".format(this.toDouble() / (0x1 shl 10))
    this <= 0xfffccccccccccccL shr 30 -> "%.1f MiB".format(this.toDouble() / (0x1 shl 20))
    this <= 0xfffccccccccccccL shr 20 -> "%.1f GiB".format(this.toDouble() / (0x1 shl 30))
    this <= 0xfffccccccccccccL shr 10 -> "%.1f TiB".format(this.toDouble() / (0x1 shl 40))
    this <= 0xfffccccccccccccL -> "%.1f PiB".format((this shr 10).toDouble() / (0x1 shl 40))
    else -> "%.1f EiB".format((this shr 20).toDouble() / (0x1 shl 40))
}

fun String.fixPixivUrl() =
    if ("""(pixiv\.net|pximg\.net)/img""".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(this)) {
        """(\d+)(_s|_m|(_big)?_p\d+)?\.\w+(\?\d+)?\z""".toRegex(RegexOption.IGNORE_CASE).find(this)?.let {
            "https://www.pixiv.net/artworks/${it.groups[1]!!.value}"
        } ?: this
    } else this

fun Context.openWeb(uri: String) = uri.fixPixivUrl().let { url ->
    startActivity(
        when (URLUtil.isValidUrl(url)) {
            true -> Intent(Intent.ACTION_VIEW, url.toUri())
            false -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, url)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

class AlphaBlackBitmapTransformation(val alpha: Int = 0x7F, val color: Int = Color.BLACK) : BitmapTransformation() {
    companion object {
        private const val VERSION = 1
        private const val ID = "com.bumptech.glide.load.resource.bitmap.AlphaBlack.$VERSION"
        private val ID_BYTES = ID.toByteArray(CHARSET)
    }

    override fun equals(other: Any?): Boolean = other is AlphaBlackBitmapTransformation

    override fun hashCode(): Int = ID.hashCode()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) = messageDigest.update(ID_BYTES)

    override fun transform(pool: BitmapPool, src: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val rect = Rect(0, 0, src.width, src.height)
        val dest = Bitmap.createBitmap(rect.width(), rect.height(), src.config)
        val canvas = Canvas(dest)
        canvas.save()
        canvas.drawColor(color)
        canvas.drawBitmap(src, rect, rect, Paint().also { it.alpha = alpha })
        canvas.restore()
        return dest
    }
}

fun <T : Any> diffCallback(call: (old: T, new: T) -> Boolean) = object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(old: T, new: T): Boolean = call(old, new)

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(old: T, new: T): Boolean = old == new
}

fun GridLayoutManager.spanSizeLookup(call: (position: Int) -> Int) = this.apply {
    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = call(position)
    }
}

data class CropImageResult(
    val output: Uri,
    val aspectRatio: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
    val originWidth: Int,
    val originHeight: Int,
)

class CropImage : ActivityResultContract<UCrop, CropImageResult?>() {
    override fun createIntent(context: Context, input: UCrop): Intent = input.getIntent(context)

    override fun parseResult(resultCode: Int, intent: Intent?): CropImageResult? = if (resultCode == Activity.RESULT_OK) {
        val uri = UCrop.getOutput(intent!!)
        val ar = intent.getFloatExtra(UCrop.EXTRA_OUTPUT_CROP_ASPECT_RATIO, 0F)
        val w = intent.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_WIDTH, 0)
        val h = intent.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_HEIGHT, 0)
        val x = intent.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_X, 0)
        val y = intent.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_Y, 0)
        val ow = intent.getIntExtra(UCrop.EXTRA_OUTPUT_ORIGIN_WIDTH, 0)
        val oh = intent.getIntExtra(UCrop.EXTRA_OUTPUT_ORIGIN_HEIGHT, 0)
        CropImageResult(uri!!, ar, w, h, x, y, ow, oh)
    } else null

}

val ViewBinding.context: Context get() = root.context
val ViewBinding.resources: Resources get() = context.resources
val RecyclerView.ViewHolder.context: Context get() = itemView.context
val RecyclerView.ViewHolder.resources: Resources get() = context.resources

fun ProgressBar.setProgressCompat(progress: Int, animate: Boolean = true) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setProgress(progress, animate) else setProgress(progress)

fun ProgressBar.setIndeterminateSafe(indeterminate: Boolean) {
    if (isIndeterminate == indeterminate) return
    if (this !is BaseProgressIndicator<*>) {
        isIndeterminate = indeterminate
        return
    }
    if (indeterminate && isVisible) {
        isInvisible = true
        isIndeterminate = indeterminate
        isInvisible = false
    } else {
        isIndeterminate = indeterminate
    }
}

suspend fun <A, B> Iterable<A>.pmap(f: suspend (A) -> B): List<B> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

fun Context.wrappers(): Sequence<ContextWrapper> = sequence {
    val wrapper = this@wrappers as? ContextWrapper ?: return@sequence
    yield(wrapper)
    yieldAll(wrapper.baseContext.wrappers())
}

inline fun <reified T : Activity> View.findActivity(): T? = context.wrappers().mapNotNull { it as? T }.firstOrNull()

val View.parents: Sequence<View>
    get() = sequence {
        when (val it = parent) {
            is View -> {
                yield(it)
                yieldAll(it.parents)
            }
        }
    }

inline fun <reified T : View> View.findParents(): Sequence<T> = parents.mapNotNull { it as? T }
inline fun <reified T : View> View.findParent(): T? = findParents<T>().firstOrNull()

val View.childrenRecursively: Sequence<View>
    get() = sequence {
        yield(this@childrenRecursively)
        if (this@childrenRecursively is ViewGroup) {
            for (child in this@childrenRecursively.children) {
                yieldAll(child.childrenRecursively)
            }
        }
    }

fun <V : View> View.findViewByViewType(clazz: Class<V>, id: Int = View.NO_ID): Sequence<View> = if (id != View.NO_ID) {
    findViewById<V>(id)?.let { sequenceOf(it) } ?: emptySequence()
} else childrenRecursively.filter { clazz.isInstance(it) }.filter { id == View.NO_ID || id == it.id }

inline fun <reified V : View> View.findViewByViewType(id: Int = View.NO_ID) = findViewByViewType(V::class.java, id)

fun Activity.snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar = window.decorView
    .let { it.findViewByViewType<CoordinatorLayout>().firstOrNull() ?: it }
    .let { Snackbar.make(it, text, duration) }

fun Activity.snack(text: Int, duration: Int = Snackbar.LENGTH_SHORT) = snack(getText(text), duration)

fun View.snack(text: CharSequence, duration: Int = Snackbar.LENGTH_SHORT): Snackbar = this
    .let { it.findParents<CoordinatorLayout>().firstOrNull() ?: it }
    .let { Snackbar.make(it, text, duration) }

fun View.snack(text: Int, duration: Int = Snackbar.LENGTH_SHORT) = snack(context.getText(text), duration)

@RequiresApi(Build.VERSION_CODES.M)
fun createProcessTextIntent(): Intent = Intent()
    .setAction(Intent.ACTION_PROCESS_TEXT)
    .setType("text/plain")

@RequiresApi(Build.VERSION_CODES.M)
fun Context.getSupportedActivities(): List<ResolveInfo> = packageManager
    .queryIntentActivities(createProcessTextIntent(), 0)

@RequiresApi(Build.VERSION_CODES.M)
fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent = createProcessTextIntent()
    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
    .setClassName(info.activityInfo.packageName, info.activityInfo.name)

@RequiresApi(Build.VERSION_CODES.M)
fun Context.initializeSupportedActivitiesMenu(menu: Menu) = try {
    getSupportedActivities().forEachIndexed { index, resolveInfo ->
        menu.add(Menu.NONE, Menu.NONE, 100 + index, resolveInfo.loadLabel(packageManager))
            .intent = createProcessTextIntentForResolveInfo(resolveInfo)
    }
} catch (e: Exception) {
    Toast.makeText(this, getString(R.string.app_process_text_error, e.localizedMessage), Toast.LENGTH_SHORT).show()
}

@RequiresApi(Build.VERSION_CODES.M)
fun View.showSupportedActivitiesMenu(tag: String) = PopupMenu(context, this).also { popup ->
    context.initializeSupportedActivitiesMenu(popup.menu)
    popup.setOnMenuItemClickListener { menu ->
        menu.intent?.let {
            context.startActivity(it.putExtra(Intent.EXTRA_PROCESS_TEXT, tag))
            true
        } ?: false
    }
    if (popup.menu.size() > 0) popup.show()
}

fun <T : Any, VH : RecyclerView.ViewHolder> PagingDataAdapter<T, VH>.peekSafe(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

val Context.appVersion: Version?
    get() = try {
        Version(packageManager.getPackageInfo(packageName, 0).versionName)
    } catch (e: Exception) {
        e.printStackTrace(); null
    }

fun AppCompatActivity.checkAppUpdate(pre: Boolean = false, compare: Boolean = false): Job = lifecycleScope.launchWhenCreated {
    val latest = runCatching {
        withContext(Dispatchers.IO) {
            val gitHub: GitHub = GitHubBuilder.fromEnvironment()
                .withConnector(OkHttpGitHubConnector(okHttp))
                .build()
            val repository = gitHub.getRepository("yueeng/moebooru")
            when (pre) {
                true -> repository.listReleases().firstOrNull()
                false -> repository.latestRelease
            }
        }
    }.getOrNull() ?: return@launchWhenCreated
    val name = if (compare) {
        val ver = appVersion ?: return@launchWhenCreated
        val online = Version.from(latest.tagName) ?: return@launchWhenCreated
        if (ver >= online) return@launchWhenCreated
        "v$ver > v$online"
    } else latest.name
    val url = runCatching {
        withContext(Dispatchers.IO) {
            latest.listAssets().firstOrNull { it.name == "app-${BuildConfig.FLAVOR}-release.apk" }?.browserDownloadUrl
        }
    }.getOrNull() ?: return@launchWhenCreated
    MaterialAlertDialogBuilder(this@checkAppUpdate)
        .setTitle(name)
        .setMessage(latest.body)
        .setPositiveButton(getString(R.string.app_download_apk)) { _, _ -> openWeb(url) }
        .setNegativeButton(R.string.app_cancel, null)
        .apply {
            if (!pre) setNeutralButton(getString(R.string.app_pre_release)) { _, _ ->
                checkAppUpdate(true)
            }
        }
        .create()
        .show()
}

fun <T : Dialog> T.show(call: T.() -> Unit): T = apply {
    setOnShowListener { call(this) }
    show()
}

val <T : AlertDialog>T.positiveButton: Button get() = getButton(AlertDialog.BUTTON_POSITIVE)
val <T : AlertDialog>T.negativeButton: Button get() = getButton(AlertDialog.BUTTON_NEGATIVE)
val <T : AlertDialog>T.neutralButton: Button get() = getButton(AlertDialog.BUTTON_NEUTRAL)

fun AppCompatTextView.setCompoundResourcesDrawables(left: Int? = null, top: Int? = null, right: Int? = null, bottom: Int? = null) = setCompoundDrawables(
    left?.let { ContextCompat.getDrawable(context, it) }?.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) },
    top?.let { ContextCompat.getDrawable(context, it) }?.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) },
    right?.let { ContextCompat.getDrawable(context, it) }?.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) },
    bottom?.let { ContextCompat.getDrawable(context, it) }?.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
)

object PendingIntentCompat {
    val FLAG_MUTABLE get() = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT)
    val FLAG_IMMUTABLE get() = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT)
}