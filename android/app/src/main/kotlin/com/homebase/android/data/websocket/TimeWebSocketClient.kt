package com.homebase.android.data.websocket

import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.WorkTargetDto
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class TimeWebSocketClient(
    baseUrl: String,
    okHttp: OkHttp,
) : ReconnectingWebSocketClient<TimeWebSocketClient.WsEvent>(baseUrl, okHttp) {

    sealed class WsEvent {
        data class ProjectCreated(val project: ProjectDto) : WsEvent()
        data class ProjectUpdated(val project: ProjectDto) : WsEvent()
        data class EntryCreated(val entry: TimeEntryDto) : WsEvent()
        data class EntryUpdated(val entry: TimeEntryDto) : WsEvent()
        data class EntryDeleted(val entry: TimeEntryDto) : WsEvent()
        // Wochensoll changed (#31/#55) — clients refetch targets + forecast. Carries no
        // payload: single-cell edits send the changed row, period create/delete send none,
        // and the VM refetches the whole list either way (#31 follow-up).
        data object TargetUpdated : WsEvent()
    }

    override val path = "/ws/time"

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    override fun parse(text: String): WsEvent? {
        val msg = moshi.adapter(WsPayload::class.java).fromJson(text) ?: return null
        return when (msg.type) {
            "PROJECT_CREATED" -> msg.project?.let { WsEvent.ProjectCreated(it) }
            "PROJECT_UPDATED" -> msg.project?.let { WsEvent.ProjectUpdated(it) }
            "ENTRY_CREATED" -> msg.entry?.let { WsEvent.EntryCreated(it) }
            "ENTRY_UPDATED" -> msg.entry?.let { WsEvent.EntryUpdated(it) }
            "ENTRY_DELETED" -> msg.entry?.let { WsEvent.EntryDeleted(it) }
            "TARGET_UPDATED" -> WsEvent.TargetUpdated
            else -> null
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class WsPayload(
        val type: String,
        val entry: TimeEntryDto? = null,
        val project: ProjectDto? = null,
        val target: WorkTargetDto? = null,
    )
}
