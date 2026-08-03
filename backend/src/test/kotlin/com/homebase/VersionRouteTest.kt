package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GET /version (#626) — Build-Version des Backends. Bewusst authentifiziert (die Clients zeigen
 * sie erst nach dem Login), deshalb gehört der 401-Fall zum Vertrag.
 */
class VersionRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"password123"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `GET version without token returns 401`() = testApplication {
        configureTestApplication()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/version").status)
    }

    @Test
    fun `GET version returns the build version`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = client.get("/api/v1/version") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Nicht auf einen konkreten Wert festnageln (der wandert mit der VERSION-Datei), sondern
        // auf den Vertrag: `version` ist immer da und nie leer.
        val version = body["version"]!!.jsonPrimitive.content
        assertTrue(version.isNotBlank(), "version darf nie leer sein")
        assertEquals(AppVersion.version, version)
        // `commit` ist optional (encodeDefaults = false): fehlt, wenn ohne Git-Kontext gebaut wurde.
        assertEquals(AppVersion.commit.ifEmpty { null }, body["commit"]?.jsonPrimitive?.content)
    }
}
