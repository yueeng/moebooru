@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils.copySpansFrom
import android.util.TypedValue
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.DatePicker
import android.widget.ImageView
import android.widget.MultiAutoCompleteTextView
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.createViewModelLazy
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
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
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import okio.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.ExperimentalTime

class MainApplication : Application() {
    companion object {
        private var app: WeakReference<MainApplication>? = null

        fun instance() = app!!.get()!!
    }

    init {
        app = WeakReference(this)
    }
}

fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

val okCookieCache = SetCookieCache()
val okPersistor = SharedPrefsCookiePersistor(MainApplication.instance())
val okCookie = object : PersistentCookieJar(okCookieCache, okPersistor) {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        super.saveFromResponse(url, cookies)
        okPersistor.removeAll(cookies.filter { !it.persistent })
        cookies.filter { it.matches(moe_url.toHttpUrl()) }.firstOrNull { it.name == "login" }?.value?.let {
            OAuth.name.postValue(it)
        }
    }
}

val okHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .cache(Cache(MainApplication.instance().cacheDir, 1024 * 1024 * 256))
    .cookieJar(okCookie)
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val response = chain.proceed(request)
        response.newBuilder().body(ProgressResponseBody(response.body!!, object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                DispatchingProgressBehavior.update(url, bytesRead, contentLength)
            }
        })).build()
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

interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}

private class ProgressResponseBody(
    private val responseBody: ResponseBody,
    private val progressListener: ProgressListener
) : ResponseBody() {
    private var bufferedSource: BufferedSource? = null

    override fun contentType(): MediaType? {
        return responseBody.contentType()
    }

    override fun contentLength(): Long {
        return responseBody.contentLength()
    }

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source()).buffer()
        }
        return bufferedSource!!
    }

    private fun source(source: Source): Source {
        return object : ForwardingSource(source) {
            var totalBytesRead = 0L

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                // read() returns the number of bytes read, or -1 if this source is exhausted.
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                progressListener.update(totalBytesRead, responseBody.contentLength(), bytesRead == -1L)
                return bytesRead
            }
        }
    }
}

@GlideModule
@Excludes(com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule::class)
class MtAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(okHttp)
        )
    }
}

interface UIonProgressListener {
    /**
     * Control how often the listener needs an update. 0% and 100% will always be dispatched.
     * @return in percentage (0.2 = call [.onProgress] around every 0.2 percent of progress)
     */
    val granualityPercentage: Float

    fun onProgress(bytesRead: Long, expectedLength: Long)
}

object DispatchingProgressBehavior {
    private val listeners = mutableMapOf<String, UIonProgressListener>()
    private val progresses = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    fun forget(url: String) {
        listeners.remove(url)
        progresses.remove(url)
    }

    fun expect(url: String, listener: UIonProgressListener) {
        listeners[url] = listener
    }

    fun update(url: String, bytesRead: Long, contentLength: Long) {
        //System.out.printf("%s: %d/%d = %.2f%%%n", url, bytesRead, contentLength, (100f * bytesRead) / contentLength);
        val listener = listeners[url] ?: return
        if (contentLength <= bytesRead) {
            forget(url)
        }
        if (needsDispatch(url, bytesRead, contentLength, listener.granualityPercentage)) {
            handler.post {
                listener.onProgress(bytesRead, contentLength)
            }
        }
    }

    private fun needsDispatch(key: String, current: Long, total: Long, granularity: Float): Boolean {
        if (granularity == 0F || current == 0L || total == current) {
            return true
        }
        val percent = 100F * current / total
        val currentProgress = (percent / granularity).toLong()
        val lastProgress = progresses[key]
        return if (lastProgress == null || currentProgress != lastProgress) {
            progresses[key] = currentProgress
            true
        } else {
            false
        }
    }
}

@SuppressLint("CheckResult")
fun <T> GlideRequest<T>.progress(url: String, progressBar: ProgressBar): GlideRequest<T> {
    progressBar.progress = 0
    progressBar.max = 100
    progressBar.visibility = View.VISIBLE
    val progress = WeakReference(progressBar)
    fun finish() {
        DispatchingProgressBehavior.forget(url)
        progress.get()?.visibility = View.GONE
    }
    DispatchingProgressBehavior.expect(url, object : UIonProgressListener {
        override fun onProgress(bytesRead: Long, expectedLength: Long) {
            (100 * bytesRead / expectedLength).toInt().let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progress.get()?.setProgress(it, true)
                } else {
                    progress.get()?.progress = it
                }
            }
        }

        override val granualityPercentage: Float
            get() = 1.0F
    })
    return addListener(object : RequestListener<T> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<T>?, isFirstResource: Boolean): Boolean {
            finish()
            return false
        }

        override fun onResourceReady(resource: T, model: Any?, target: Target<T>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
            finish()
            return false; }

    })
}

class GlideDecoder : ImageDecoder {
    @Throws(Exception::class)
    override fun decode(context: Context, uri: Uri): Bitmap {
        val bytes = okHttp.newCall(Request.Builder().url(uri.toString()).build()).execute().body!!.bytes()
        val bitmap = GlideApp.with(context).asBitmap().load(bytes).submit().get()
        return bitmap.copy(bitmap.config, bitmap.isMutable)
    }
}

class GlideRegionDecoder : ImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null

    @Throws(Exception::class)
    override fun init(context: Context, uri: Uri): Point {
        val bytes = okHttp.newCall(Request.Builder().url(uri.toString()).build()).execute().body!!.bytes()
        decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
        return Point(decoder!!.width, decoder!!.height)
    }

    override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap = synchronized(this) {
        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        options.inPreferredConfig = Bitmap.Config.RGB_565
        val bitmap = decoder!!.decodeRegion(rect, options)
        return bitmap ?: throw RuntimeException("Region decoder returned null bitmap - image format may not be supported")
    }

    override fun isReady(): Boolean = decoder != null && !decoder!!.isRecycled
    override fun recycle() = decoder!!.recycle()
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

val random = Random(System.currentTimeMillis())

fun randomColor(alpha: Int = 0xFF, saturation: Float = 1F, value: Float = 0.5F) =
    Color.HSVToColor(alpha, arrayOf(random.nextInt(360).toFloat(), saturation, value).toFloatArray())

fun bindImageFromUrl(view: ImageView, imageUrl: String?, progressBar: ProgressBar?, placeholder: Int?) {
    if (imageUrl.isNullOrEmpty()) return
    view.scaleType = ImageView.ScaleType.CENTER
    GlideApp.with(view)
        .load(imageUrl)
        .transition(DrawableTransitionOptions.withCrossFade())
        .apply { if (placeholder != null) placeholder(placeholder) }
        .apply { if (progressBar != null) progress(imageUrl, progressBar) }
        .onResourceReady { _, _, _, _, _ ->
            view.setImageDrawable(null)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            false
        }
        .into(view)
}

fun bindImageRatio(view: View, width: Int, height: Int) {
    val params: ConstraintLayout.LayoutParams = view.layoutParams as ConstraintLayout.LayoutParams
    params.dimensionRatio = "$width:$height"
    view.layoutParams = params
}

val ChipGroup.checkedChip: Chip? get() = this.children.mapNotNull { it as Chip }.firstOrNull { it.isChecked }
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
fun String.toTitleCase(vararg delimiters: String = arrayOf("_")) = delimiters.fold(this) { r, s -> r.replace(s, " ", true) }.let {
    regexTitleCase.findAll(it).fold(it) { r, i ->
        r.replaceRange(i.range, i.value.capitalize(Locale.getDefault()))
    }
}

fun Date.firstDayOfWeek(index: Int = 1, firstOfWeek: Int = Calendar.MONDAY): Date = Calendar.getInstance().let { c ->
    c.firstDayOfWeek = firstOfWeek
    c.time = this
    c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek + index - 1)
    c.time
}

fun Date.lastDayOfWeek(): Date = firstDayOfWeek(7)

fun Date.firstDayOfMonth(): Date = Calendar.getInstance().let { c ->
    c.time = this
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.time
}

fun Date.lastDayOfMonth(): Date = Calendar.getInstance().let { c ->
    c.time = this
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.roll(Calendar.DAY_OF_MONTH, -1)
    c.time
}

class TimeSpan(val end: Calendar, val begin: Calendar) {
    val milliseconds get() = end.timeInMillis - begin.timeInMillis
    val seconds get() = milliseconds / 1000
    val minutes get() = seconds / 60
    val hours get() = minutes / 60
    val days: Int get() = (hours / 24).toInt()
    val weeks: Int get() = days / 7
    val months: Int get() = (end.year - begin.year) * 12 + (end.month - begin.month) + 1
    val days2: Int
        get() = days + when {
            end.timeInMillis < begin.timeInMillis -> when {
                end.millisecondOfDay > begin.millisecondOfDay -> -2
                else -> -1
            }
            end.millisecondOfDay < begin.millisecondOfDay -> 2
            else -> 1
        }
    val weeks2: Int
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

fun Calendar.year(year: Int, add: Boolean = false) = apply { if (add) add(Calendar.YEAR, year) else set(Calendar.YEAR, year) }
fun Calendar.month(month: Int, add: Boolean = false) = apply { if (add) add(Calendar.MONTH, month) else set(Calendar.MONTH, month) }
fun Calendar.day(day: Int, add: Boolean = false) = apply { if (add) add(Calendar.DAY_OF_MONTH, day) else set(Calendar.DAY_OF_MONTH, day) }
fun Calendar.hour(hour: Int, add: Boolean = false) = apply { if (add) add(Calendar.HOUR_OF_DAY, hour) else set(Calendar.HOUR_OF_DAY, hour) }
fun Calendar.minute(minute: Int, add: Boolean = false) = apply { if (add) add(Calendar.MINUTE, minute) else set(Calendar.MINUTE, minute) }
fun Calendar.second(second: Int, add: Boolean = false) = apply { if (add) add(Calendar.SECOND, second) else set(Calendar.SECOND, second) }
fun Calendar.millisecond(millisecond: Int, add: Boolean = false) = apply { if (add) add(Calendar.MILLISECOND, millisecond) else set(Calendar.MILLISECOND, millisecond) }
fun Calendar.dayOfWeek(day: Int, add: Boolean = false) = apply { if (add) add(Calendar.DAY_OF_WEEK, day) else set(Calendar.DAY_OF_WEEK, day) }
fun Calendar.dayOfWeek2(day: Int, add: Boolean = false) = dayOfWeek((firstDayOfWeek + day - 1) % 7, add)
fun Calendar.weekOfYear(week: Int, add: Boolean = false) = apply { if (add) add(Calendar.WEEK_OF_YEAR, week) else set(Calendar.WEEK_OF_YEAR, week) }
val Calendar.firstDayOfWeek2 get() = cp().dayOfWeek2(1)
val Calendar.lastDayOfWeek2 get() = cp().dayOfWeek2(7)
val Calendar.firstDayOfMonth get() = cp().day(1)
val Calendar.lastDayOfMonth get() = cp().month(1, true).day(-1)
val Calendar.year get() = get(Calendar.YEAR)
val Calendar.month get() = get(Calendar.MONTH)
val Calendar.day get() = get(Calendar.DAY_OF_MONTH)
val Calendar.dayOfWeek get() = get(Calendar.DAY_OF_WEEK)
val Calendar.dayOfWeek2 get() = (dayOfWeek - firstDayOfWeek + 7) % 7 + 1
val Calendar.hour get() = get(Calendar.HOUR_OF_DAY)
val Calendar.minute get() = get(Calendar.MINUTE)
val Calendar.minuteOfDay get() = hour * 60 + minute
val Calendar.second get() = get(Calendar.SECOND)
val Calendar.secondOfDay get() = minuteOfDay * 60 + second
val Calendar.millisecond get() = get(Calendar.MILLISECOND)
val Calendar.millisecondOfDay get() = secondOfDay * 1000 + millisecond
val Calendar.milliseconds get() = timeInMillis
fun Calendar.format(df: DateFormat): String = df.format(time)
fun Calendar.cp(): Calendar = clone() as Calendar
fun DateFormat.tryParse(string: CharSequence): Date? = try {
    parse(string.toString())
} catch (e: Exception) {
    null
}

var DatePicker.date
    get() = Calendar.getInstance().year(year).month(month).day(dayOfMonth)
    set(value) = updateDate(value.year, value.month, value.day)

inline fun <reified VM : ViewModel> Fragment.sharedViewModels(
    noinline key: () -> String,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
) = createViewModelLazy(VM::class, { SharedViewModelStoreOwner(key(), this).viewModelStore }, factoryProducer)

inline fun <reified VM : ViewModel> Fragment.sharedActivityViewModels(
    noinline key: () -> String,
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
) = createViewModelLazy(VM::class, { SharedViewModelStoreOwner(key(), requireActivity()).viewModelStore }, factoryProducer)

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

fun TedPermission.Builder.onGranted(view: View, function: () -> Unit): TedPermission.Builder {
    setPermissionListener(object : PermissionListener {
        override fun onPermissionGranted() {
            function()
        }

        override fun onPermissionDenied(deniedPermissions: MutableList<String>?) {
            Snackbar.make(view, R.string.tedpermission_setting, Snackbar.LENGTH_SHORT)
                .setAction(R.string.app_go) {
                    view.context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + view.context.packageName)))
                }
                .show()
        }
    })
    return this
}

object Save {
    fun encode(path: String): String = """\/:*?"<>|""".fold(path) { r, i ->
        r.replace(i, ' ')
    }


    class SaveWorker(private val context: Context, private val params: WorkerParameters) : Worker(context, params) {
        @FlowPreview
        @ExperimentalTime
        override fun doWork(): Result = try {
            val url = params.inputData.getString("url")?.toHttpUrlOrNull() ?: throw IllegalArgumentException()
            val target = params.inputData.getString("target")?.toUri() ?: throw IllegalArgumentException()
            val response = okHttp.newCall(Request.Builder().url(url).build()).execute()
            response.body?.use {
                val progress = Data.Builder().putLong("current", 0L).putLong("length", it.contentLength())
                var last = System.currentTimeMillis()
                setProgressAsync(progress.build())
                it.byteStream().use { input ->
                    context.contentResolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output) { cur ->
                            if (last + 1000 < System.currentTimeMillis()) {
                                last = System.currentTimeMillis()
                                setProgressAsync(progress.putLong("current", cur).build())
                            }
                        }
                    }
                }
            }
            Result.success(Data.Builder().putString("file", target.toString()).build())
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString("error", e.message).build())
        }
    }

    fun save(item: JImageItem, target: Uri, call: ((Uri?) -> Unit)? = null) {
        val url = item.jpeg_url
        val params = Data.Builder().putString("url", url).putString("target", target.toString()).build()
        val request = OneTimeWorkRequestBuilder<SaveWorker>().setInputData(params).build()
        val manager = WorkManager.getInstance(MainApplication.instance()).apply { enqueue(request) }
        val info = manager.getWorkInfoByIdLiveData(request.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManagerCompat.from(MainApplication.instance()).let { notify ->
                val channel = NotificationChannel(moe_host, moe_host, NotificationManager.IMPORTANCE_DEFAULT)
                notify.createNotificationChannel(channel)
            }
        }
        val notification = NotificationCompat.Builder(MainApplication.instance(), moe_host)
            .setContentTitle(MainApplication.instance().getString(R.string.app_download, MainApplication.instance().getString(R.string.app_name)))
            .setContentText(item.jpeg_file_name)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
        info.observe(ProcessLifecycleOwner.get(), Observer {
            when (it.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    notification.setProgress(0, 0, true)
                        .setContentText("READY")
                    NotificationManagerCompat.from(MainApplication.instance()).notify(item.id, notification.build())
                }
                WorkInfo.State.RUNNING -> {
                    val current = it.progress.getLong("current", 0)
                    val length = it.progress.getLong("length", 0)
                    notification.setProgress(length.toInt(), current.toInt(), false)
                        .setContentText("${current.sizeString()}/${length.sizeString()}")
                    NotificationManagerCompat.from(MainApplication.instance()).notify(item.id, notification.build())
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED -> {
                    info.removeObservers(ProcessLifecycleOwner.get())
                    call?.invoke(it.outputData.getString("file")?.toUri())
                }
            }
        })
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

fun InputStream.copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE, progress: (Long) -> Unit): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer)
    while (bytes >= 0) {
        out.write(buffer, 0, bytes)
        bytesCopied += bytes
        progress(bytesCopied)
        bytes = read(buffer)
    }
    return bytesCopied
}

fun Context.notifyDownloadComplete(uri: Uri, id: Int, filename: String) {
    val extension = MimeTypeMap.getFileExtensionFromUrl(filename)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        type = mime ?: "image/$extension"
        data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationManagerCompat.from(this).let { manager ->
            val channel = NotificationChannel(moe_host, moe_host, NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
    }
    val builder = NotificationCompat.Builder(MainApplication.instance(), moe_host)
        .setContentTitle(getString(R.string.app_download, getString(R.string.app_name)))
        .setContentText(filename)
        .setAutoCancel(true)
        .setSmallIcon(R.drawable.ic_stat_name)
        .setContentIntent(PendingIntent.getActivity(this, id, Intent.createChooser(intent, getString(R.string.app_share)), PendingIntent.FLAG_ONE_SHOT))
    GlideApp.with(this).asBitmap().load(uri).override(500, 500)
        .into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                val bigPictureStyle = NotificationCompat.BigPictureStyle()
                    .bigPicture(resource)
                builder
                    .setStyle(bigPictureStyle)
                    .setLargeIcon(resource)
                NotificationManagerCompat.from(this@notifyDownloadComplete).notify(id, builder.build())
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                NotificationManagerCompat.from(this@notifyDownloadComplete).notify(id, builder.build())
            }
        })
}

class AlphaBlackBitmapTransformation : BitmapTransformation() {
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
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, rect, rect, Paint().apply { alpha = 0x50 })
        canvas.restore()
        return dest
    }
}

fun <T : Any> diffCallback(call: (old: T, new: T) -> Boolean) = object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(old: T, new: T): Boolean = call(old, new)

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(old: T, new: T): Boolean = old == new
}