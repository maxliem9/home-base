package com.homebase

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Arbitrary file attachments on notes (#431). Mirrors NoteImageRouteTest but for the document
// type set, and asserts the security-critical serving behaviour: every download is forced as an
// `attachment` (never inline) under the sanitized original filename, with X-Content-Type-Options.
class NoteAttachmentRouteTest {

    private suspend fun ApplicationTestBuilder.loginAndGetToken(
        username: String = "alice",
        password: String = "password123",
    ): String {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createNote(token: String, body: String): String =
        Json.parseToJsonElement(
            client.post("/api/v1/notes") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
        ).jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.uploadAttachment(
        token: String,
        noteId: String,
        bytes: ByteArray,
        contentType: String = "application/pdf",
        filename: String = "vertrag.pdf",
    ) = client.post("/api/v1/notes/$noteId/attachments") {
        bearerAuth(token)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        },
                    )
                },
            ),
        )
    }

    // minimal PDF magic bytes — content is never validated, only the declared/derived type
    private val pdfBytes = "%PDF-1.4\n%fake".toByteArray()

    @Test
    fun `upload a PDF returns the note with the embedded attachment`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Versicherung"}""")

        val response = uploadAttachment(token, noteId, pdfBytes)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val attachments = body["attachments"]!!.jsonArray
        assertEquals(1, attachments.size)
        val att = attachments[0].jsonObject
        assertEquals(noteId, att["noteId"]?.jsonPrimitive?.content)
        assertEquals("application/pdf", att["contentType"]?.jsonPrimitive?.content)
        assertEquals(pdfBytes.size, att["sizeBytes"]?.jsonPrimitive?.int)
        assertEquals("vertrag.pdf", att["originalName"]?.jsonPrimitive?.content)
        // images and attachments are distinct collections — the PDF must not pollute images
        assertTrue(body["images"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a generic octet-stream PDF is accepted via the filename extension fallback`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")

        // browsers commonly send application/octet-stream for PDFs/office docs; the extension rescues it
        val response = uploadAttachment(token, noteId, pdfBytes, contentType = "application/octet-stream", filename = "police.pdf")

        assertEquals(HttpStatusCode.Created, response.status)
        val att = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject
        // resolved to the canonical whitelisted type, not the generic octet-stream
        assertEquals("application/pdf", att["contentType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uploaded attachment is served back with the original bytes`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(token, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(pdfBytes, response.readRawBytes())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `served attachment is forced as a download under the original filename`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(
            uploadAttachment(token, noteId, pdfBytes, filename = "Mietvertrag Wohnung.pdf").bodyAsText()
        ).jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        // security-critical: arbitrary files are NEVER inline (would be stored XSS for HTML/SVG) —
        // always attachment, so the browser downloads rather than renders
        assertTrue(disposition?.startsWith("attachment") == true, "expected attachment disposition, got $disposition")
        // the original name survives (Ktor encodes the space/umlaut per RFC 5987) — closes the #272 gap
        assertTrue(disposition?.contains("Mietvertrag") == true, "missing original filename in $disposition")
    }

    @Test
    fun `served attachment filename strips a stray quote (no header breakout)`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        // a stray double-quote in the original name would otherwise let the value break out of the
        // Content-Disposition filename="…" parameter; safeAttachmentFilename strips it
        val attId = Json.parseToJsonElement(
            uploadAttachment(token, noteId, pdfBytes, filename = "ab\"c.pdf").bodyAsText()
        ).jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        val disposition = response.headers[HttpHeaders.ContentDisposition]!!
        // the header stays a single well-formed line, still an attachment, with the quote stripped so
        // the remaining name (abc.pdf) sits cleanly inside the filename parameter
        assertTrue('\r' !in disposition && '\n' !in disposition, "no CR/LF in $disposition")
        assertTrue(disposition.startsWith("attachment"), "expected attachment disposition, got $disposition")
        assertTrue(disposition.contains("abc.pdf"), "sanitized name should survive in $disposition")
    }

    @Test
    fun `notes list embeds uploaded attachments`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        uploadAttachment(token, noteId, pdfBytes)

        val list = Json.parseToJsonElement(
            client.get("/api/v1/notes") { bearerAuth(token) }.bodyAsText()
        ).jsonArray
        assertEquals(1, list.size)
        assertEquals(1, list[0].jsonObject["attachments"]!!.jsonArray.size)
    }

    @Test
    fun `delete attachment removes it from the note and the bytes are gone`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(token, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject["attachments"]!!.jsonArray.isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `deleting the note also drops its attachments`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(token, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/notes/$noteId") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun `upload of a disallowed type returns 415`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")

        // an executable/script must never be accepted, neither by declared type nor extension
        val response = uploadAttachment(token, noteId, pdfBytes, contentType = "application/x-msdownload", filename = "virus.exe")

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `upload of an HTML file is rejected (stored-XSS guard)`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")

        val response = uploadAttachment(
            token, noteId,
            "<script>alert(1)</script>".toByteArray(),
            contentType = "text/html", filename = "evil.html",
        )

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    // test config caps uploads at 1 MB
    private val maxBytes = 1024 * 1024

    @Test
    fun `upload exceeding the size limit returns 413 and leaves no file`() = testApplication {
        val uploadDir = configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")

        val tooBig = ByteArray(maxBytes + 1) { 0x10 }
        val response = uploadAttachment(token, noteId, tooBig)

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(
            Files.list(uploadDir).use { it.toList() }.isEmpty(),
            "rejected oversized upload must not leave any file in the upload dir",
        )
    }

    @Test
    fun `upload exactly at the size limit succeeds`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")

        val atLimit = ByteArray(maxBytes) { 0x10 }
        val response = uploadAttachment(token, noteId, atLimit)

        assertEquals(HttpStatusCode.Created, response.status)
        val att = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject
        assertEquals(maxBytes, att["sizeBytes"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a request without a token is rejected`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(token, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // no Authorization header / no ?token= ⇒ 401 from the JWT plugin (the download route is
        // under authenticate("auth-jwt") like every other note endpoint)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/notes/$noteId/attachments/$attId").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/notes/$noteId/attachments").status,
        )
    }

    @Test
    fun `attachment download honours the token query param`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()
        val noteId = createNote(token, """{"title":"Vertrag"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(token, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // native clients (Android) load via ?token= rather than the Authorization header — the same
        // JWT mechanism the image endpoint relies on must authenticate the attachment download too
        val response = client.get("/api/v1/notes/$noteId/attachments/$attId?token=$token")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `other user cannot fetch or upload an attachment of a private note`() = testApplication {
        configureTestApplication()
        val alice = loginAndGetToken("alice", "password123")
        val bob = loginAndGetToken("bob", "password456")
        val noteId = createNote(alice, """{"title":"Geheim","visibility":"PRIVATE"}""")
        val attId = Json.parseToJsonElement(uploadAttachment(alice, noteId, pdfBytes).bodyAsText())
            .jsonObject["attachments"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/notes/$noteId/attachments/$attId") { bearerAuth(bob) }.status,
        )
        assertEquals(HttpStatusCode.NotFound, uploadAttachment(bob, noteId, pdfBytes).status)
    }

    @Test
    fun `upload to a missing note returns 404`() = testApplication {
        configureTestApplication()
        val token = loginAndGetToken()

        val response = uploadAttachment(token, "00000000-0000-0000-0000-000000000099", pdfBytes)

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
