package com.homebase

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

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
        // Gegen die VERSION-Datei geprüft, nicht gegen AppVersion — sonst wäre der Test
        // tautologisch (beide Seiten läsen dieselbe Ressource) und bliebe grün, wenn die
        // Ressourcen-Generierung wegbräche und die API für immer "0.0.0-dev" auslieferte.
        // Das ist dieselbe Blindstelle, die schon einmal zugeschlagen hat (#9/#121, Fat-Jar).
        assertEquals(repoVersion(), body["version"]!!.jsonPrimitive.content)
        // `commit` ist optional (encodeDefaults = false): fehlt, wenn ohne Git-Kontext gebaut wurde.
        assertEquals(AppVersion.commit.ifEmpty { null }, body["commit"]?.jsonPrimitive?.content)
    }

    /** Der Inhalt der VERSION-Datei im Repo-Root — die Quelle, aus der der Build seine Version zieht. */
    private fun repoVersion(): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val version = File(d, "VERSION")
            if (version.isFile) return version.readText().trim()
            d = d.parentFile
        }
        fail("VERSION-Datei nicht gefunden ab ${System.getProperty("user.dir")}")
    }
}
