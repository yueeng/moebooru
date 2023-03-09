package com.github.yueeng.moebooru

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import java.io.File
import java.util.*

class CropActivity : AppCompatActivity() {
    companion object {
        const val OPTION_SHARE = 1
        const val OPTION_AVATAR = 2
    }

    private val op: Int by lazy { intent.getIntExtra("op", 0) }
    private val id: Int by lazy { intent.getIntExtra("id", 0) }
    private val name: String by lazy { intent.getStringExtra("name") ?: getString(R.string.app_name) }
    private val source: Uri by lazy { intent.getParcelableExtraCompat("source")!! }
    private val cropShare = registerForActivityResult(CropImage()) { result ->
        try {
            if (result == null) return@registerForActivityResult
            val extension = MimeTypeMap.getFileExtensionFromUrl(result.output.path!!)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", File(result.output.path!!))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                type = mime ?: "image/$extension"
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, getString(R.string.app_share)))
            notifyImageComplete(uri, id, getString(R.string.app_name), name)
        } finally {
            finish()
        }
    }

    private val cropAvatar = registerForActivityResult(CropImage()) { data ->
        try {
            if (data == null) return@registerForActivityResult
            OAuth.avatar(this, OAuth.user.value!!, id, 1F * data.offsetX / data.originWidth, 1F * (data.offsetX + data.imageWidth) / data.originWidth, 1F * data.offsetY / data.originHeight, 1F * (data.offsetY + data.imageHeight) / data.originHeight) {
                Toast.makeText(this, R.string.app_complete, Toast.LENGTH_SHORT).show()
            }
        } finally {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (op) {
            OPTION_SHARE -> {
                val dest = File(File(cacheDir, "shared").apply { mkdirs() }, name)
                cropShare.launch(UCrop.of(source, Uri.fromFile(dest)))
            }
            OPTION_AVATAR -> {
                val dest = File(cacheDir, UUID.randomUUID().toString())
                val option = UCrop.Options().apply {
                    setAllowedGestures(UCropActivity.SCALE, UCropActivity.NONE, UCropActivity.SCALE)
                    setHideBottomControls(true)
                }
                val crop = UCrop.of(source, Uri.fromFile(dest))
                    .withAspectRatio(1F, 1F).withOptions(option)
                cropAvatar.launch(crop)
            }
            else -> finish()
        }
    }

    private fun notifyImageComplete(uri: Uri, id: Int, title: String, content: String) {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "image/$extension")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManagerCompat.from(this).let { manager ->
                val channel = NotificationChannel(moeHost, moeHost, NotificationManager.IMPORTANCE_DEFAULT)
                manager.createNotificationChannel(channel)
            }
        }
        val builder = NotificationCompat.Builder(this, moeHost)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(PendingIntent.getActivity(this, id, Intent.createChooser(intent, getString(R.string.app_share)), PendingIntentCompat.FLAG_IMMUTABLE))
        GlideApp.with(this).asBitmap().load(uri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .override(500, 500)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(resource))
                        .setLargeIcon(resource)
                    if (ActivityCompat.checkSelfPermission(this@CropActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        NotificationManagerCompat.from(this@CropActivity).notify(id, builder.build())
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (ActivityCompat.checkSelfPermission(this@CropActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        NotificationManagerCompat.from(this@CropActivity).notify(id, builder.build())
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }
}