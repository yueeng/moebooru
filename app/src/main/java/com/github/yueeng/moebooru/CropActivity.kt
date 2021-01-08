package com.github.yueeng.moebooru

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
    private val source: Uri by lazy { intent.getParcelableExtra("source")!! }
    private val cropShare = registerForActivityResult(CropImage()) { result ->
        try {
            if (result == null) return@registerForActivityResult
            val extension = MimeTypeMap.getFileExtensionFromUrl(result.output.path!!)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", File(result.output.path!!))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
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
}