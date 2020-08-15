package com.github.yueeng.moebooru

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

@Parcelize
data class JImageItem(
    val actual_preview_height: Int,
    val actual_preview_width: Int,
    val author: String,
    val change: Int,
    val created_at: Int,
    val creator_id: Int,
    val file_size: Int,
    val file_url: String,
    val frames_pending_string: String,
    val frames_string: String,
    val has_children: Boolean,
    val height: Int,
    val id: Int,
    val is_held: Boolean,
    val is_shown_in_index: Boolean,
    val jpeg_file_size: Int,
    val jpeg_height: Int,
    val jpeg_url: String,
    val jpeg_width: Int,
    val md5: String,
    val preview_height: Int,
    val preview_url: String,
    val preview_width: Int,
    val rating: String,
    val sample_file_size: Int,
    val sample_height: Int,
    val sample_url: String,
    val sample_width: Int,
    val score: Int,
    val source: String,
    val status: String,
    val tags: String,
    val width: Int
) : Parcelable

interface MoebooruService {
    @GET("post.json")
    fun post(
        @Query("page") page: Int = 1,
        @Query("tags") tags: String = "rating:safe",
        @Query("limit") limit: Int = 20
    ): Call<List<JImageItem>>
}

object Service {
    private val retrofit: Retrofit = Retrofit.Builder()
        .addConverterFactory(GsonConverterFactory.create())
        .client(okhttp)
        .baseUrl("https://konachan.com")
        .build()
    val instance: MoebooruService = retrofit.create(MoebooruService::class.java)
}
