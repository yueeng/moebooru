package com.github.yueeng.moebooru

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.Excludes
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.util.concurrent.TimeUnit


fun debug(call: () -> Unit) {
    if (BuildConfig.DEBUG) call()
}

val okhttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .apply { debug { addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) } }
    .build()

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

@BindingAdapter(value = ["imageUrl", "imagePlaceholder"], requireAll = false)
fun bindImageFromUrl(view: ImageView, imageUrl: String?, placeholder: Drawable?) {
    if (imageUrl.isNullOrEmpty()) return
    GlideApp.with(view.context)
        .load(imageUrl)
        .apply { if (placeholder != null) this.placeholder(placeholder) }
        .transition(DrawableTransitionOptions.withCrossFade())
        .into(view)
}

@BindingAdapter(value = ["dimensionRatioWidth", "dimensionRatioHeight"])
fun bindImageRatio(view: ImageView, width: Int, height: Int) {
    val params: ConstraintLayout.LayoutParams = view.layoutParams as ConstraintLayout.LayoutParams
    params.dimensionRatio = "$width:$height"
    view.layoutParams = params
}