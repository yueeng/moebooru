package com.github.yueeng.moebooru

import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.savedstate.SavedStateRegistryOwner

class ImageDataSource(private val query: Q? = Q()) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, query!!.rating(Q.Rating.safe), limit = params.loadSize)
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    val posts = Pager(PagingConfig(20)) { ImageDataSource(defaultArgs?.getParcelable("query")) }.flow
}

class ImageViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ImageViewModel(handle, defaultArgs) as T
}
