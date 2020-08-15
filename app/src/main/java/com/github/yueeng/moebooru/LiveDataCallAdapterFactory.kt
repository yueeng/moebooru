package com.github.yueeng.moebooru

import androidx.lifecycle.LiveData
import retrofit2.*
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

internal const val UNKNOWN_CODE = -1

sealed class ApiResponse<T> {
    companion object {
        fun <T> create(response: Response<T>): ApiResponse<T> = if (response.isSuccessful) {
            val body = response.body()
            if (body == null || response.code() == 204) ApiEmptyResponse() else ApiSuccessResponse(body)
        } else ApiErrorResponse(response.code(), response.errorBody()?.string() ?: response.message())

        fun <T> create(errorCode: Int, error: Throwable): ApiErrorResponse<T> {
            return ApiErrorResponse(errorCode, error.message ?: "Unknown Error!")
        }
    }
}

class ApiEmptyResponse<T> : ApiResponse<T>()
data class ApiErrorResponse<T>(val errorCode: Int, val errorMessage: String) : ApiResponse<T>()
data class ApiSuccessResponse<T>(val body: T) : ApiResponse<T>()

class LiveDataCallAdapter<R>(private val responseType: Type) : CallAdapter<R, LiveData<ApiResponse<R>>> {
    override fun adapt(call: Call<R>): LiveData<ApiResponse<R>> = object : LiveData<ApiResponse<R>>() {
        private var isSuccess = false
        override fun onActive() {
            super.onActive()
            if (!isSuccess) enqueue()
        }

        override fun onInactive() {
            super.onInactive()
            dequeue()
        }

        private fun dequeue() {
            if (call.isExecuted) call.cancel()
        }

        private fun enqueue() {
            call.enqueue(object : Callback<R> {
                override fun onFailure(call: Call<R>, t: Throwable) {
                    postValue(ApiResponse.create(UNKNOWN_CODE, t))
                }

                override fun onResponse(call: Call<R>, response: Response<R>) {
                    postValue(ApiResponse.create(response))
                    isSuccess = true
                }
            })
        }
    }

    override fun responseType(): Type = responseType
}

class LiveDataCallAdapterFactory : CallAdapter.Factory() {
    override fun get(
        returnType: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): CallAdapter<*, *>? {
        val observableType = getParameterUpperBound(0, returnType as ParameterizedType) as? ParameterizedType
            ?: throw IllegalArgumentException("resource must be parameterized")
        return LiveDataCallAdapter<Any>(
            getParameterUpperBound(0, observableType)
        )
    }
}
