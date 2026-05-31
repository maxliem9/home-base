package com.homebase.android.data.api

import com.homebase.android.data.model.*
import retrofit2.http.*

interface HomeBaseApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @GET("todos")
    suspend fun getTodos(): List<TodoDto>

    @POST("todos")
    suspend fun createTodo(@Body request: CreateTodoRequest): TodoDto

    @PUT("todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body request: UpdateTodoRequest): TodoDto

    @DELETE("todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String)
}
