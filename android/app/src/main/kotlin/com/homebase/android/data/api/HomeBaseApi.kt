package com.homebase.android.data.api

import com.homebase.android.data.model.*
import retrofit2.http.*

interface HomeBaseApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @GET("config")
    suspend fun getConfig(): AppConfigResponse

    // --- Todos ---

    @GET("todos")
    suspend fun getTodos(): List<TodoDto>

    @POST("todos")
    suspend fun createTodo(@Body request: CreateTodoRequest): TodoDto

    @PUT("todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body request: UpdateTodoRequest): TodoDto

    @DELETE("todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String)

    // --- Todo lists ---

    @GET("todos/lists")
    suspend fun getTodoLists(): List<TodoListDto>

    @POST("todos/lists")
    suspend fun createTodoList(@Body request: CreateTodoListRequest): TodoListDto

    @PUT("todos/lists/{id}")
    suspend fun updateTodoList(@Path("id") id: String, @Body request: UpdateTodoListRequest): TodoListDto

    @DELETE("todos/lists/{id}")
    suspend fun deleteTodoList(@Path("id") id: String)

    // --- Subtasks (return the updated parent todo) ---

    @POST("todos/{id}/subtasks")
    suspend fun createSubtask(@Path("id") todoId: String, @Body request: CreateSubtaskRequest): TodoDto

    @PUT("todos/{id}/subtasks/{subtaskId}")
    suspend fun updateSubtask(
        @Path("id") todoId: String,
        @Path("subtaskId") subtaskId: String,
        @Body request: UpdateSubtaskRequest,
    ): TodoDto

    @DELETE("todos/{id}/subtasks/{subtaskId}")
    suspend fun deleteSubtask(@Path("id") todoId: String, @Path("subtaskId") subtaskId: String): TodoDto

    // --- Shopping items ---

    @GET("shopping")
    suspend fun getShoppingItems(): List<ShoppingItemDto>

    @POST("shopping")
    suspend fun createShoppingItem(@Body request: CreateShoppingItemRequest): ShoppingItemDto

    @PUT("shopping/{id}")
    suspend fun updateShoppingItem(@Path("id") id: String, @Body request: UpdateShoppingItemRequest): ShoppingItemDto

    @DELETE("shopping/{id}")
    suspend fun deleteShoppingItem(@Path("id") id: String)

    // --- Shopping lists ---

    @GET("shopping/lists")
    suspend fun getShoppingLists(): List<ShoppingListDto>

    @POST("shopping/lists")
    suspend fun createShoppingList(@Body request: CreateShoppingListRequest): ShoppingListDto

    @PUT("shopping/lists/{id}")
    suspend fun updateShoppingList(@Path("id") id: String, @Body request: UpdateShoppingListRequest): ShoppingListDto

    @DELETE("shopping/lists/{id}")
    suspend fun deleteShoppingList(@Path("id") id: String)

    // --- Notes ---

    @GET("notes")
    suspend fun getNotes(@Query("q") query: String? = null): List<NoteDto>

    @POST("notes")
    suspend fun createNote(@Body request: CreateNoteRequest): NoteDto

    @PUT("notes/{id}")
    suspend fun updateNote(@Path("id") id: String, @Body request: UpdateNoteRequest): NoteDto

    @DELETE("notes/{id}")
    suspend fun deleteNote(@Path("id") id: String)

    // --- Time tracking ---

    @GET("time/projects")
    suspend fun getProjects(): List<ProjectDto>

    @POST("time/projects")
    suspend fun createProject(@Body request: CreateProjectRequest): ProjectDto

    @PUT("time/projects/{id}")
    suspend fun updateProject(@Path("id") id: String, @Body request: UpdateProjectRequest): ProjectDto

    @PATCH("time/projects/{id}/archive")
    suspend fun archiveProject(@Path("id") id: String, @Body request: ArchiveProjectRequest): ProjectDto

    @GET("time/entries")
    suspend fun getTimeEntries(
        @Query("project_id") projectId: String? = null,
        @Query("user_id") userId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): List<TimeEntryDto>

    @POST("time/entries/start")
    suspend fun startTimer(@Body request: StartTimerRequest): TimeEntryDto

    @POST("time/entries/stop")
    suspend fun stopTimer(): TimeEntryDto

    @POST("time/entries")
    suspend fun createTimeEntry(@Body request: CreateTimeEntryRequest): TimeEntryDto

    @PUT("time/entries/{id}")
    suspend fun updateTimeEntry(@Path("id") id: String, @Body request: UpdateTimeEntryRequest): TimeEntryDto

    @DELETE("time/entries/{id}")
    suspend fun deleteTimeEntry(@Path("id") id: String)

    @GET("time/running")
    suspend fun getRunningTimer(): TimeEntryDto

    // --- Recipes ---

    @GET("recipes")
    suspend fun getRecipes(@Query("category") category: String? = null): List<RecipeDto>

    @GET("recipes/{id}")
    suspend fun getRecipe(@Path("id") id: String, @Query("servings") servings: Int? = null): RecipeDto

    @POST("recipes")
    suspend fun createRecipe(@Body request: CreateRecipeRequest): RecipeDto

    @PUT("recipes/{id}")
    suspend fun updateRecipe(@Path("id") id: String, @Body request: UpdateRecipeRequest): RecipeDto

    @DELETE("recipes/{id}")
    suspend fun deleteRecipe(@Path("id") id: String)
}
