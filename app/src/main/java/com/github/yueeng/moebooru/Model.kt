@file:Suppress("EnumEntryName", "MemberVisibilityCanBePrivate", "unused", "FunctionName", "ObjectPropertyName", "LocalVariableName", "PropertyName")

package com.github.yueeng.moebooru

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Parcel
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.github.yueeng.moebooru.databinding.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("SimpleDateFormat")
val moeCreateTime: Calendar = Calendar.getInstance().apply {
    time = SimpleDateFormat("yyyy-MM-dd").parse(MainApplication.instance().getString(R.string.app_create_time))!!
}
val moeHost = MainApplication.instance().getString(R.string.app_host)
val moeUrl = "https://$moeHost"
val moeSummaryUrl = "$moeUrl/tag/summary.json"
val moeSummaryEtag = MainApplication.instance().getString(R.string.app_summary_etag)
const val github = "https://github.com/yueeng/moebooru"
const val release = "$github/releases"

enum class Resolution(val title: String, val resolution: Int) {
    ZERO("", 0),
    SD("SD", 720 * 480),
    HD("HD", 1280 * 720),
    R1K("1K", 1920 * 1080),
    R2K("2K", 2560 * 1440),
    R4K("4K", 4096 * 2160),
    R8K("8K", 7680 * 4320),
    R16K("16K", R8K.resolution * 4),
    R32K("32K", R16K.resolution * 4),
    R64K("64K", R32K.resolution * 4), ;

    val mpixels get() = resolution / 1000000F

    companion object {
        fun match(mpixels: Float) = values().reversed().firstOrNull { mpixels >= it.mpixels } ?: ZERO
    }
}

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
    var parent_id: Int,
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
) : Parcelable {
    val mpixels get() = width * height / 1000000F
    val resolution get() = Resolution.match(mpixels)
}

@Parcelize
open class JResult(
    val success: Boolean? = null,
    val reason: String? = null
) : Parcelable

@Parcelize
open class JRegResult(
    val response: String? = null,
    val exists: Boolean? = null,
    val name: String? = null,
    val id: Int? = null,
    val pass_hash: String? = null,
    val user_info: String? = null,
    val errors: List<String>? = null
) : JResult(), Parcelable

@Parcelize
data class ItemUser(
    var name: String? = null,
    var id: Int? = null
) : Parcelable {
    val face: String get() = "$moeUrl/data/avatars/$id.jpg"
}

@Parcelize
data class ItemPool(
    var id: Int? = null,
    var name: String? = null,
    var created_at: String? = null,
    var updated_at: String? = null,
    var user_id: Int? = null,
    var is_public: Boolean? = null,
    var post_count: Int? = null,
    var description: String? = null
) : Parcelable

@Parcelize
data class ItemPoolPost(
    var id: Int? = null,
    var pool_id: Int? = null,
    var post_id: Int? = null,
    var active: Boolean? = null,
    var sequence: String? = null,
    var next_post_id: Int? = null,
    var prev_post_id: Int? = null
) : Parcelable

@Parcelize
data class ItemVoteBy(
    @SerializedName("1") var v1: List<ItemUser>? = null,
    @SerializedName("2") var v2: List<ItemUser>? = null,
    @SerializedName("3") var v3: List<ItemUser>? = null
) : Parcelable {
    val v
        get() = listOf(3 to v3, 2 to v2, 1 to v1)
            .filter { it.second?.isNotEmpty() == true }
            .map { it.first to it.second!! }
            .toMap()
}

@Parcelize
data class ItemScore(
    var posts: List<JImageItem>? = null,
    var pools: List<ItemPool>? = null,
    var pool_posts: List<ItemPoolPost>? = null,
    var tags: Map<String, String>? = null,
    var votes: Map<String, Int>? = null,
    var voted_by: ItemVoteBy? = null,
    var vote: Int? = null
) : JResult()

@Parcelize
data class Tag(var type: Int, val name: String, val tag: String) : Parcelable {
    companion object {
        const val TYPE_UNKNOWN = -1
        const val TYPE_GENERAL = 0
        const val TYPE_ARTIST = 1
        const val TYPE_COPYRIGHT = 3
        const val TYPE_CHARACTER = 4
        const val TYPE_CIRCLE = 5
        const val TYPE_FAULTS = 6
        const val TYPE_USER = -2
        const val TYPE_SIZE = -3
        const val TYPE_CHILDREN = -4
        const val TYPE_PARENT = -5
        const val TYPE_URL = -6
        const val TYPE_CLIPBOARD = -7
        const val TYPE_DOWNLOAD = -8

        fun string(type: Int) = when (type) {
            TYPE_GENERAL -> "GENERAL"
            TYPE_ARTIST -> "ARTIST"
            TYPE_COPYRIGHT -> "COPYRIGHT"
            TYPE_CHARACTER -> "CHARACTER"
            TYPE_CIRCLE -> "CIRCLE"
            TYPE_FAULTS -> "FAULTS"
            TYPE_USER -> "USER"
            TYPE_SIZE -> "SIZE"
            TYPE_CHILDREN -> "CHILDREN"
            TYPE_PARENT -> "PARENT"
            TYPE_URL -> "WEBSITE"
            TYPE_CLIPBOARD -> "COPY"
            TYPE_DOWNLOAD -> "DOWNLOAD"
            else -> ""
        }

        fun color(type: Int, context: Context = MainApplication.instance()) = when (type) {
            0 -> ActivityCompat.getColor(context, R.color.tag_type_general)
            1 -> ActivityCompat.getColor(context, R.color.tag_type_artist)
            3 -> ActivityCompat.getColor(context, R.color.tag_type_copyright)
            4 -> ActivityCompat.getColor(context, R.color.tag_type_character)
            5 -> ActivityCompat.getColor(context, R.color.tag_type_circle)
            6 -> ActivityCompat.getColor(context, R.color.tag_type_faults)
            else -> ActivityCompat.getColor(context, R.color.tag_type_default)
        }
    }

    val string: String get() = string(type)
    val color: Int get() = color(type)
}

interface MoebooruService {
    @GET("post.json")
    suspend fun post(
        @Query("page") page: Int = 1,
        @Query("tags") tags: Q = Q(),
        @Query("limit") limit: Int = 20
    ): List<JImageItem>

    @GET("post/popular_by_day.json")
    suspend fun popular_by_day(
        @Query("day") day: Int,
        @Query("month") month: Int,
        @Query("year") year: Int
    ): List<JImageItem>

    @GET("post/popular_by_week.json")
    suspend fun popular_by_week(
        @Query("day") day: Int,
        @Query("month") month: Int,
        @Query("year") year: Int
    ): List<JImageItem>

    @GET("post/popular_by_month.json")
    suspend fun popular_by_month(
        @Query("month") month: Int,
        @Query("year") year: Int
    ): List<JImageItem>

    @GET("post/popular_recent.json")
    suspend fun popular_recent(): List<JImageItem>

    @GET("user.json")
    suspend fun user(@Query("id") id: Int): List<ItemUser>

    @GET("user.json")
    suspend fun user(@Query("name") name: String): List<ItemUser>

    @GET("user/logout.json")
    suspend fun logout(): JResult?

    @FormUrlEncoded
    @POST("user/authenticate.json")
    suspend fun login(
        @Field("user[name]") name: String,
        @Field("user[password]") pwd: String,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("url") url: String = "",
        @Field("commit") commit: String = "Login"
    ): JResult?

    @FormUrlEncoded
    @POST("user/create.json")
    suspend fun register(
        @Field("user[name]") name: String,
        @Field("user[email]") pwd: String,
        @Field("user[password]") email: String,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("user[password_confirmation]") email2: String = email
    ): JRegResult?

    @FormUrlEncoded
    @POST("user/reset_password.json")
    suspend fun reset(
        @Field("user[name]") name: String,
        @Field("user[email]") email: String,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("commit") commit: String = "Submit"
    ): JResult?

    @FormUrlEncoded
    @POST("user/update.json")
    suspend fun change_email(
        @Field("user[current_password]") pwd: String,
        @Field("user[email]") email: String,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("user[current_email]") email2: String = email,
        @Field("render[view]") render: String = "change_email",
        @Field("_method") method: String = "patch",
        @Field("commit") commit: String = "Save"
    ): JResult?

    @FormUrlEncoded
    @POST("user/update.json")
    suspend fun change_pwd(
        @Field("user[current_password]") pwd_current: String,
        @Field("user[password]") pwd: String,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("user[password_confirmation]") pwd2: String = pwd,
        @Field("render[view]") render: String = "change_password",
        @Field("_method") method: String = "patch",
        @Field("commit") commit: String = "Save"
    ): JResult?

    @FormUrlEncoded
    @POST("post/vote.json")
    suspend fun vote(
        @Field("id") id: Int,
        @Field("score") score: Int? = null,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token
    ): ItemScore?

    @FormUrlEncoded
    @POST("user/set_avatar/{id}")
    suspend fun avatar(
        @Path("id") id: Int,
        @Field("post_id") post_id: Int,
        @Field("left") left: Float,
        @Field("right") right: Float,
        @Field("top") top: Float,
        @Field("bottom") bottom: Float,
        @Field("authenticity_token") authenticity_token: String,
        @Header("X-CSRF-Token") x_csrf_token: String = authenticity_token,
        @Field("commit") commit: String = "Set avatar"
    )
}

class Service(private val service: MoebooruService) : MoebooruService by service {
    override suspend fun post(page: Int, tags: Q, limit: Int): List<JImageItem> = service.post(page, if (MoeSettings.safe.value != true) Q(tags).rating(Q.Rating.safe) else tags, limit)

    companion object {
        private val retrofit: Retrofit = Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .client(okHttp)
            .baseUrl(moeUrl)
            .build()
        val instance: MoebooruService = Service(retrofit.create(MoebooruService::class.java))
        suspend fun csrf(): String? = try {
            val home = okHttp.newCall(Request.Builder().url("$moeUrl/user/home").build()).await { _, response -> response.body?.string() }
            Jsoup.parse(home).select("meta[name=csrf-token]").attr("content")
        } catch (e: Exception) {
            null
        }
    }
}

class Q(m: Map<String, Any>? = mapOf()) : Parcelable {
    val map: MutableMap<String, Any> = (m ?: emptyMap()).toMutableMap()

    @Parcelize
    enum class Order(val value: String) : Parcelable {
        id("id"),
        id_desc("id_desc"),
        score("score"),
        score_asc("score_asc"),
        mpixels("mpixels"),
        mpixels_asc("mpixels_asc"),
        portrait("portrait"),
        landscape("landscape"),
        vote("vote"),
        random("random");

        override fun toString(): String = value
    }

    @Parcelize
    enum class Rating(val value: String) : Parcelable {
        safe("safe"),
        questionable("questionable"),
        explicit("explicit"),
        _safe("-safe"),
        _questionable("-questionable"),
        _explicit("-explicit");

        override fun toString(): String = value
    }

    @Parcelize
    class Value<out T : Any>(val op: Op = Op.eq, val v1: @RawValue T, val v2: @RawValue T? = null, val ex: String? = null) : Parcelable {
        enum class Op(val value: String) {
            eq("%s"),
            lt("<%s"),
            le("..%s"),
            ge("%s.."),
            gt(">%s"),
            bt("%s..%s"),
        }

        val v1string get() = (v1 as? Date)?.let { formatter.format(v1) } ?: "$v1"

        val v2string get() = (v2 as? Date)?.let { formatter.format(v2) } ?: v2?.let { "$v2" } ?: ""

        override fun toString(): String = String.format(op.value, v1string, v2string) + (ex?.let { ":$ex" } ?: "")

        constructor(op: Op, v: Pair<T, T?>, ex: String?) : this(op, v.first, v.second, ex)

        companion object {

            fun <T : Any> from(source: String, fn: (String) -> T): Value<T> {
                return source.split(":", limit = 2).let { sv ->
                    val v = sv[0]
                    val ex = sv.takeIf { it.size > 1 }?.let { it[1] }
                    when {
                        v.startsWith("..") -> Value(Op.le, v.substring(2).let(fn), ex = ex)
                        v.endsWith("..") -> Value(Op.ge, v.substring(0, v.length - 2).let(fn), ex = ex)
                        v.startsWith(">=") -> Value(Op.ge, v.substring(2).let(fn), ex = ex)
                        v.startsWith("<=") -> Value(Op.le, v.substring(2).let(fn), ex = ex)
                        v.startsWith(">") -> Value(Op.gt, v.substring(1).let(fn), ex = ex)
                        v.startsWith("<") -> Value(Op.lt, v.substring(1).let(fn), ex = ex)
                        v.contains("..") -> v.split("..").let { Value(Op.bt, it.first().let(fn), it.last().let(fn), ex = ex) }
                        else -> Value(Op.eq, v.let(fn), ex = ex)
                    }
                }
            }
        }
    }

    val user: String? by map
    val vote: Value<Int>? by map
    val md5: String? by map
    val rating: Rating? by map
    val source: String? by map
    val id: Value<Int>? by map
    val width: Value<Int>? by map
    val height: Value<Int>? by map
    val score: Value<Int>? by map
    val mpixels: Value<Float>? by map
    val date: Value<Date>? by map
    val order: Order? by map
    val parent: String? by map
    val pool: String? by map
    val keyword: String? by map

    fun user(user: String) = apply { map["user"] = user }

    fun vote(vote: Value<Int>) = apply { map["vote"] = vote }

    fun md5(md5: String) = apply { map["md5"] = md5 }

    fun rating(rating: Rating) = apply { map["rating"] = rating }

    fun source(source: String) = apply { map["source"] = source }

    fun id(id: Value<Int>) = apply { map["id"] = id }
    fun id(id: Int, op: Value.Op = Value.Op.eq) = apply { map["id"] = Value(op, id) }
    fun id(id: Int, id2: Int) = apply { map["id"] = Value(Value.Op.bt, id, id2) }

    fun width(width: Value<Int>) = apply { map["width"] = width }
    fun width(width: Int, op: Value.Op = Value.Op.eq) = apply { map["width"] = Value(op, width) }
    fun width(width: Int, width2: Int) = apply { map["width"] = Value(Value.Op.bt, width, width2) }

    fun height(height: Value<Int>) = apply { map["height"] = height }
    fun height(height: Int, op: Value.Op = Value.Op.eq) = apply { map["height"] = Value(op, height) }
    fun height(height: Int, height2: Int) = apply { map["height"] = Value(Value.Op.bt, height, height2) }

    fun score(score: Value<Int>) = apply { map["score"] = score }
    fun score(score: Int, op: Value.Op = Value.Op.eq) = apply { map["score"] = Value(op, score) }
    fun score(score: Int, score2: Int) = apply { map["score"] = Value(Value.Op.bt, score, score2) }

    fun mpixels(mpixels: Value<Float>) = apply { map["mpixels"] = mpixels }
    fun mpixels(mpixels: Float, op: Value.Op = Value.Op.eq) = apply { map["mpixels"] = Value(op, mpixels) }
    fun mpixels(mpixels: Int, mpixels2: Float) = apply { map["mpixels"] = Value(Value.Op.bt, mpixels, mpixels2) }

    fun date(date: Value<Date>) = apply { map["date"] = date }
    fun date(date: Date, op: Value.Op = Value.Op.eq) = apply { map["date"] = Value(op, date) }
    fun date(date: Date, date2: Date) = apply { map["date"] = Value(Value.Op.bt, date, date2) }

    fun order(order: Order) = apply { map["order"] = order }

    fun parent(parent: String) = apply { map["parent"] = parent }

    fun pool(pool: String) = apply { map["pool"] = pool }

    fun keyword(keyword: String) = apply { map["keyword"] = keyword }

    fun popular_by_day(date: Date) = order(Order.score).date(Value(Value.Op.eq, date))

    fun popular_by_week(date: Date) = order(Order.score).date(Value(Value.Op.bt, date.firstDayOfWeek(), date.lastDayOfWeek()))

    fun popular_by_month(date: Date) = order(Order.score).date(Value(Value.Op.bt, date.firstDayOfMonth(), date.lastDayOfMonth()))

    fun popular(type: String, date: Date) = when (type) {
        "day" -> popular_by_day(date)
        "week" -> popular_by_week(date)
        "month" -> popular_by_month(date)
        else -> throw IllegalArgumentException()
    }

    override fun toString(): String = map.asSequence()
        .map { it.key to "${it.value}" }
        .filter { it.second.isNotEmpty() }
        .sortedBy { it.first }
        .joinToString(" ") {
            when (it.first) {
                "keyword" -> it.second
                else -> "${it.first}:${it.second}"
            }
        }

    override fun equals(other: Any?): Boolean = when (other) {
        is Q -> this.toString() == other.toString()
        else -> super.equals(other)
    }

    override fun hashCode(): Int = this.toString().hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) = with(parcel) {
        writeInt(map.size)
        map.forEach {
            writeString(it.key)
            writeValue(it.value)
        }
    }

    constructor(source: Parcel) : this((1..source.readInt()).map { source.readString()!! to source.readValue(Q::class.java.classLoader)!! }.toMap().toMutableMap())

    constructor(source: Q?) : this(source?.map)
    constructor(source: String?) : this(source?.takeIf { it.isNotEmpty() }?.split(' ', '+')
        ?.map { it.split(':', limit = 2) }
        ?.map { list ->
            when (list.size) {
                1 -> "keyword" to list.first()
                2 -> when (list.first()) {
                    "order" -> list.first() to list.last().let { v -> Order.values().single { it.value == v } }
                    "rating" -> list.first() to list.last().let { v -> Rating.values().single { it.value == v } }
                    "id", "width", "height", "score" -> list.first() to list.last().let { v ->
                        Value.from(v) { it.toIntOrNull() ?: 0 }
                    }
                    "mpixels" -> list.first() to list.last().let { v ->
                        Value.from(v) { it.toFloatOrNull() ?: 0 }
                    }
                    "date" -> list.first() to list.last().let { v ->
                        Value.from(v) { formatter.parse(it) }
                    }
                    "vote" -> list.first() to list.last().let { v ->
                        Value.from(v) { it.toIntOrNull() ?: 0 }
                    }
                    else -> list.first() to list.last()
                }
                else -> throw  IllegalArgumentException()
            }
        }
        ?.toMap<String, Any>()
    )

    fun set(source: Q, reset: Boolean = false) = apply {
        if (reset) map.clear()
        source.map.forEach { (k, v) -> map[k] = v }
    }

    fun set(source: String, reset: Boolean = false) = set(Q(source), reset)

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Q> = object : Parcelable.Creator<Q> {
            override fun createFromParcel(source: Parcel): Q = Q(source)
            override fun newArray(size: Int): Array<Q?> = arrayOfNulls(size)
        }
        val formatter get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cheats = linkedMapOf(
            ("keyword" to (R.string.query_keyword to R.string.query_keyword_desc)),
            ("order" to (R.string.query_order to R.string.query_order_desc)),
            ("rating" to (R.string.query_rating to R.string.query_rating_desc)),
            ("vote" to (R.string.query_vote to R.string.query_vote_desc)),
            ("date" to (R.string.query_date to R.string.query_date_desc)),
            ("width" to (R.string.query_width to R.string.query_width_desc)),
            ("height" to (R.string.query_height to R.string.query_height_desc)),
            ("mpixels" to (R.string.query_mpixels to R.string.query_mpixels_desc)),
            ("id" to (R.string.query_id to R.string.query_id_desc)),
            ("user" to (R.string.query_user to R.string.query_user_desc)),
            ("score" to (R.string.query_score to R.string.query_score_desc)),
            ("md5" to (R.string.query_md5 to R.string.query_md5_desc)),
            ("source" to (R.string.query_source to R.string.query_source_desc)),
            ("parent" to (R.string.query_parent to R.string.query_parent_desc)),
            ("pool" to (R.string.query_pool to R.string.query_pool_desc))
        )

        val orders = linkedMapOf(
            (Order.id to R.string.query_order_id),
            (Order.id_desc to R.string.query_order_id_desc),
            (Order.score to R.string.query_order_score),
            (Order.score_asc to R.string.query_order_score_asc),
            (Order.mpixels to R.string.query_order_mpixels),
            (Order.mpixels_asc to R.string.query_order_mpixels_asc),
            (Order.landscape to R.string.query_order_landscape),
            (Order.portrait to R.string.query_order_portrait),
            (Order.vote to R.string.query_order_vote)
        )

        val ratings = linkedMapOf(
            (Rating.safe to R.string.query_rating_safe),
            (Rating.questionable to R.string.query_rating_questionable),
            (Rating.explicit to R.string.query_rating_explicit),
            (Rating._safe to R.string.query_rating__safe),
            (Rating._questionable to R.string.query_rating__questionable),
            (Rating._explicit to R.string.query_rating__explicit)
        )

        class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
            override fun doWork(): Result = try {
                val preferences = PreferenceManager.getDefaultSharedPreferences(MainApplication.instance())
                val etag = preferences.getString("summary-etag", moeSummaryEtag)
                val request = Request.Builder().url(moeSummaryUrl).build()
                val response = okHttp.newCall(request).execute()
                val online = response.header("ETag")
                if (online != null && online != etag) {
                    response.body?.byteStream()?.use { input ->
                        File(MainApplication.instance().filesDir, "summary.json").outputStream().use {
                            input.copyTo(it)
                        }
                        preferences.edit().putString("summary-etag", online).apply()
                    }
                }
                Result.success()
            } catch (e: Exception) {
                Result.failure()
            }
        }

        val summary: String by lazy {
            val file = File(MainApplication.instance().filesDir, "summary.json")
            val summary = if (file.exists()) file.readText() else {
                MainApplication.instance().assets.open("summary.json").bufferedReader().readText().also { summary ->
                    file.writeText(summary)
                }
            }
            WorkManager.getInstance(MainApplication.instance()).enqueue(OneTimeWorkRequestBuilder<UpdateWorker>().build())
            summary
        }

        fun tag(tag: String, top_results_only: Boolean = false): Regex {
            /*
             * We can do a few search methods:
             *
             * 1: Ordinary prefix search.
             * 2: Name search. "aaa_bbb" -> "aaa*_bbb*|bbb*_aaa*".
             * 3: Contents search; "tgm" -> "t*g*m*" -> "tagme".  The first character is still always
             * matched exactly.
             *
             * Avoid running multiple expressions.  Instead, combine these into a single one, then run
             * each part on the results to determine which type of result it is.  Always show prefix and
             * name results before contents results.
             */
            val regex_parts = ArrayList<String>()

            /* Allow basic word prefix matches.  "tag" matches at the beginning of any word
             * in a tag, eg. both "tagme" and "dont_tagme". */
            /* Add the regex for ordinary prefix matches. */
            regex_parts.add("(([^`]*_)?${Regex.escape(tag)})")

            /* Allow "fir_las" to match both "first_last" and "last_first". */
            if (tag.indexOf("_") != -1) {
                val tags = tag.split('_', limit = 2)
                val first = Regex.escape(tags[0])
                val last = Regex.escape(tags[1])
                regex_parts.add("(($first[^`]*_$last)|($last[^`]*_$first))")
            }

            /* Allow "tgm" to match "tagme".  If top_results_only is set, we only want primary results,
             * so omit this match. */
            if (!top_results_only) {
                regex_parts.add(tag.toCharArray().joinToString("", "(", ")") { "${Regex.escape("$it")}[^`]*" })
            }

            /* The space is included in the result, so the result tags can be matched with the
             * same regexes, for in reorder_search_results.
             *
             * (\d)+  match the alias ID                      1`
             * [^ ]*: start at the beginning of any alias     1`foo`bar`
             * ... match ...
             * [^`]*` all matches are prefix matches          1`foo`bar`tagme`
             * [^ ]*  match any remaining aliases             1`foo`bar`tagme`tag_me`
             */
            val regex_string = """(\d+)[^ ]*`(${regex_parts.joinToString("|")})[^`]*`[^ ]* """

            return Regex(regex_string, RegexOption.IGNORE_CASE)
        }

        val test = Regex("""(\d+)`([^`]*)`(([^ ]*)`)? ?""")
        fun suggest(word: String, top: Boolean = false): Sequence<Triple<Int, String, String>> = word.takeIf { it.isNotBlank() }?.let { _ ->
            tag(word, true).findAll(summary)
                .apply { if (!top) plus(tag(word).findAll(summary)) }
                .distinctBy { m -> m.value }
                .mapNotNull { m -> test.find(m.value)?.groups }
                .map { Triple(it[1]!!.value.toInt(), it[2]!!.value, it[3]?.value ?: "") }
        } ?: emptySequence()
    }

    fun clone(): Q = Q(this)
}

object OAuth {
    val name: MutableLiveData<String> = MutableLiveData(okCookie.loadForRequest(moeUrl.toHttpUrl()).firstOrNull { it.name == "login" }?.value ?: "")
    val user = MutableLiveData<Int>()
    val avatar = MutableLiveData<Int>()

    init {
        name.observeForever {
            if (it.isEmpty()) return@observeForever
            ProcessLifecycleOwner.get().lifecycleScope.launchWhenCreated {
                runCatching { Service.instance.user(it) }
                    .getOrNull()?.firstOrNull()?.id?.let { id -> user.postValue(id) }
            }
        }
    }

    val available: Boolean get() = !name.value.isNullOrEmpty()
    val timestamp = MutableLiveData(Calendar.getInstance().time.time / 1000)
    fun face(id: Int) = if (id > 0) "$moeUrl/data/avatars/$id.jpg?${timestamp.value}" else null
    fun avatar(fragment: Fragment, id: Int, post_id: Int, left: Float, right: Float, top: Float, bottom: Float, fn: (Int) -> Unit) {
        fragment.lifecycleScope.launchWhenCreated {
            runCatching { Service.instance.avatar(id, post_id, left, right, top, bottom, Service.csrf()!!) }
                .onSuccess {
                    timestamp.postValue(Calendar.getInstance().time.time / 1000)
                    avatar.postValue(post_id)
                    fn(post_id)
                }
        }
    }

    private fun alert(fragment: Fragment, layout: Int, title: Int, subTitle: Int = 0, subCall: (() -> Unit)? = null, call: (AlertDialog, View) -> Unit) = fragment.requireContext().let { context ->
        val view = LayoutInflater.from(context).inflate(layout, null)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(title, null)
            .setNegativeButton(R.string.app_cancel, null)
            .apply { if (subTitle != 0) setNeutralButton(subTitle) { _, _ -> subCall?.invoke() } }
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        call.invoke(this, view)
                    }
                }
            }
            .show()
    }

    fun logout(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.user_logout)
            .setPositiveButton(R.string.user_logout) { _, _ ->
                fragment.lifecycleScope.launchWhenCreated {
                    runCatching { Service.instance.logout() }
                    call?.invoke()
                }
            }
            .setNeutralButton(R.string.app_cancel, null)
            .create().show()

    fun login(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        alert(fragment, R.layout.user_login, R.string.user_login, R.string.user_register, { register(fragment) { login(fragment, call) } }) { alert, root ->
            val view = UserLoginBinding.bind(root)
            val name = view.edit1.text.toString()
            val pass = view.edit2.text.toString()
            view.indicator.isInvisible = false
            alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = false }
            fragment.lifecycleScope.launchWhenCreated {
                val result = runCatching { Service.instance.login(name, pass, Service.csrf()!!) }.getOrNull()
                view.indicator.isInvisible = true
                alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = true }
                if (result?.success == true) {
                    alert.dismiss()
                    call?.invoke()
                } else {
                    val msg = result?.reason ?: fragment.getString(R.string.app_failed)
                    Snackbar.make(view.root, msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.app_ok) {}.show()
                }
            }
        }

    fun register(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        alert(fragment, R.layout.user_register, R.string.user_register, R.string.user_login, { login(fragment, call) }) { alert, root ->
            val view = UserRegisterBinding.bind(root)
            val name = view.editName.text.toString()
            val email = view.editEmail.text.toString()
            val pwd = view.editPwd.text.toString()
            val pwd_confirm = view.editPwdConfirm.text.toString()
            if (pwd != pwd_confirm) {
                Snackbar.make(view.root, R.string.user_pwd_diff, Snackbar.LENGTH_SHORT)
                    .setAction(R.string.app_ok) {}.show()
                return@alert
            }
            view.indicator.isInvisible = false
            alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = false }
            fragment.lifecycleScope.launchWhenCreated {
                val result = runCatching { Service.instance.register(name, pwd, email, Service.csrf()!!) }.getOrNull()
                view.indicator.isInvisible = true
                alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = true }
                if (result?.success == true) {
                    alert.dismiss()
                    call?.invoke()
                } else {
                    val msg = result?.reason ?: fragment.getString(R.string.app_failed)
                    Snackbar.make(view.root, msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.app_ok) {}.show()
                }
            }
        }

    fun reset(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        alert(fragment, R.layout.user_reset, R.string.user_reset, R.string.user_login, { login(fragment, call) }) { alert, root ->
            val view = UserResetBinding.bind(root)
            val name = view.editName.text.toString()
            val email = view.editEmail.text.toString()
            view.indicator.isInvisible = false
            alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = false }
            fragment.lifecycleScope.launchWhenCreated {
                val result = runCatching { Service.instance.reset(name, email, Service.csrf()!!) }.getOrNull()
                view.indicator.isInvisible = true
                alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = true }
                if (result?.success == true) {
                    alert.dismiss()
                    call?.invoke()
                } else {
                    val msg = result?.reason ?: fragment.getString(R.string.app_failed)
                    Snackbar.make(view.root, msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.app_ok) {}.show()
                }
            }
        }

    fun changeEmail(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        alert(fragment, R.layout.user_change_email, R.string.user_change_email, R.string.user_change_pwd, { changePwd(fragment, call) }) { alert, root ->
            val view = UserChangeEmailBinding.bind(root)
            val email = view.editEmail.text.toString()
            val pwd = view.editPwd.text.toString()
            view.indicator.isInvisible = false
            alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = false }
            fragment.lifecycleScope.launchWhenCreated {
                val result = runCatching { Service.instance.change_email(pwd, email, Service.csrf()!!) }.getOrNull()
                view.indicator.isInvisible = true
                alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = true }
                if (result?.success == true) {
                    alert.dismiss()
                    call?.invoke()
                } else {
                    val msg = result?.reason ?: fragment.getString(R.string.app_failed)
                    Snackbar.make(view.root, msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.app_ok) {}.show()
                }
            }
        }

    fun changePwd(fragment: Fragment, call: (() -> Unit)? = null): Unit =
        alert(fragment, R.layout.user_change_pwd, R.string.user_change_pwd, R.string.user_change_email, { changeEmail(fragment, call) }) { alert, root ->
            val view = UserChangePwdBinding.bind(root)
            val old = view.editPwdCurrent.text.toString()
            val pwd = view.editPwd.text.toString()
            val pwd_confirm = view.editPwdConfirm.text.toString()
            if (pwd != pwd_confirm) {
                Snackbar.make(view.root, R.string.user_pwd_diff, Snackbar.LENGTH_SHORT)
                    .setAction(R.string.app_ok) {}.show()
                return@alert
            }
            view.indicator.isInvisible = false
            alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = false }
            fragment.lifecycleScope.launchWhenCreated {
                val result = runCatching { Service.instance.change_pwd(old, pwd, Service.csrf()!!) }.getOrNull()
                view.indicator.isInvisible = true
                alert.window?.decorView?.childrenRecursively?.mapNotNull { it as? TextView }?.forEach { it.isEnabled = true }
                if (result?.success == true) {
                    alert.dismiss()
                    call?.invoke()
                } else {
                    val msg = result?.reason ?: fragment.getString(R.string.app_failed)
                    Snackbar.make(view.root, msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.app_ok) {}.show()
                }
            }
        }
}
