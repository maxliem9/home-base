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

    @GET("shopping")
    suspend fun getShoppingItems(): List<ShoppingItemDto>

    @POST("shopping")
    suspend fun createShoppingItem(@Body request: CreateShoppingItemRequest): ShoppingItemDto

    @PUT("shopping/{id}")
    suspend fun updateShoppingItem(@Path("id") id: String, @Body request: UpdateShoppingItemRequest): ShoppingItemDto

    @DELETE("shopping/{id}")
    suspend fun deleteShoppingItem(@Path("id") id: String)
}
