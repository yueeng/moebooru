package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.Excludes
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import okhttp3.Cache
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.*
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

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

val okhttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .cache(Cache(MainApplication.instance().cacheDir, 1024 * 1024 * 256))
    .cookieJar(PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(MainApplication.instance())))
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

interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}

private class ProgressResponseBody internal constructor(
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
            OkHttpUrlLoader.Factory(okhttp)
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
    private val LISTENERS = mutableMapOf<String, UIonProgressListener>()
    private val PROGRESSES = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    fun forget(url: String) {
        LISTENERS.remove(url)
        PROGRESSES.remove(url)
    }

    fun expect(url: String, listener: UIonProgressListener) {
        LISTENERS[url] = listener
    }

    fun update(url: String, bytesRead: Long, contentLength: Long) {
        //System.out.printf("%s: %d/%d = %.2f%%%n", url, bytesRead, contentLength, (100f * bytesRead) / contentLength);
        val listener = LISTENERS[url] ?: return
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
        val lastProgress = PROGRESSES[key]
        return if (lastProgress == null || currentProgress != lastProgress) {
            PROGRESSES[key] = currentProgress
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
    return this.addListener(object : RequestListener<T> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<T>?, isFirstResource: Boolean): Boolean {
            finish()
            return false
        }

        override fun onResourceReady(resource: T, model: Any?, target: Target<T>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
            finish()
            return false; }

    })
}

fun bindImageFromUrl(view: ImageView, imageUrl: String?, progressBar: ProgressBar?, placeholder: Int?) {
    if (imageUrl.isNullOrEmpty()) return
    view.scaleType = ImageView.ScaleType.CENTER
    GlideApp.with(view)
        .load(imageUrl)
        .transition(DrawableTransitionOptions.withCrossFade())
        .apply { if (placeholder != null) placeholder(placeholder) }
        .apply { if (progressBar != null) progress(imageUrl, progressBar) }
        .addListener(object: RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                return false
            }

            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                view.setImageDrawable(null)
                view.scaleType = ImageView.ScaleType.FIT_CENTER
                return false
            }
        })
        .into(view)
}

fun bindImageRatio(view: ImageView, width: Int, height: Int) {
    val params: ConstraintLayout.LayoutParams = view.layoutParams as ConstraintLayout.LayoutParams
    params.dimensionRatio = "$width:$height"
    view.layoutParams = params
}