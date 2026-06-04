package com.homebase.android.di

import android.content.Context
import com.homebase.android.BuildConfig
import com.homebase.android.data.api.AuthInterceptor
import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.repository.AbsenceRepository
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.data.repository.NotesRepository
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.repository.TodoRepository
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.data.websocket.NotesWebSocketClient
import com.homebase.android.data.websocket.OkHttp
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.data.websocket.TodoWebSocketClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AppContainer(context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Token holder written once after login; read by the OkHttp interceptor on each request.
    // Accessed from background threads (OkHttp dispatcher) — volatile is sufficient.
    @Volatile var currentToken: String? = null

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { currentToken })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private val api: HomeBaseApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(HomeBaseApi::class.java)

    val authRepository = AuthRepository(context, api) { token -> currentToken = token }

    val configRepository = ConfigRepository(api)

    val todoRepository = TodoRepository(
        api = api,
        wsClient = TodoWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val shoppingRepository = ShoppingRepository(
        api = api,
        wsClient = ShoppingWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val notesRepository = NotesRepository(
        api = api,
        wsClient = NotesWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val timeRepository = TimeRepository(
        api = api,
        wsClient = TimeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val recipesRepository = RecipesRepository(
        api = api,
        wsClient = RecipeWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )

    val absenceRepository = AbsenceRepository(
        api = api,
        wsClient = AbsenceWebSocketClient(BuildConfig.BASE_URL, OkHttp(okHttpClient)),
    )
}
