package com.github.yueeng.moebooru

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import java.util.*

@Entity(
    tableName = "tags",
    indices = [Index("tag", unique = true)]
)
data class DbTag(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "tag") var tag: String,
    @ColumnInfo(name = "name") var name: String,
    @ColumnInfo(name = "pin") var pin: Boolean = false,
    @ColumnInfo(name = "create") var create: Date = Date()
) {
    fun update(tag: String, name: String, pin: Boolean) = apply {
        this.tag = tag
        this.name = name
        this.pin = pin
        this.create = Date()
    }
}

@Dao
interface DbDao {
    @Query("SELECT * FROM tags WHERE pin = :pin ORDER BY `create` DESC")
    suspend fun tags(pin: Boolean): List<DbTag>

    @Query("SELECT * FROM tags ORDER BY `pin` DESC, `create` DESC")
    suspend fun tags(): List<DbTag>

    @Query("SELECT * FROM tags WHERE pin = :pin ORDER BY `create` DESC")
    fun pagingTags(pin: Boolean): PagingSource<Int, DbTag>

    @Query("SELECT * FROM tags ORDER BY `pin` DESC, `create` DESC")
    fun pagingTags(): PagingSource<Int, DbTag>

    @Query("SELECT * FROM tags WHERE tag = :tag LIMIT 1")
    suspend fun tag(tag: String): DbTag?

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun tag(id: Long): DbTag?

    @Insert
    suspend fun insertTag(tag: DbTag): Long

    @Update
    suspend fun updateTag(tag: DbTag)

    @Delete
    suspend fun deleteTag(tag: DbTag)
}

class DaoConverter {

    @TypeConverter
    fun dataFromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time
}

@Database(
    entities = [DbTag::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(DaoConverter::class)
abstract class Db : RoomDatabase() {
    companion object {
        private fun create(context: Context): Db = Room.databaseBuilder(context, Db::class.java, "database.db")
            .fallbackToDestructiveMigration()
            .build()

        val db: Db by lazy { create(MainApplication.instance()) }
        val tags: DbDao by lazy { db.tags() }
    }

    abstract fun tags(): DbDao
}