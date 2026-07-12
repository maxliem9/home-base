package com.homebase.routes

import com.homebase.model.*
import com.homebase.service.RecipeService
import com.homebase.ws.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

private const val RECIPES_WS_CHANNEL = "recipes"
// LUNCH was dropped (collapsed into DINNER) — see migration V17. Clients only offer these five.
private val VALID_CATEGORIES = setOf("BREAKFAST", "DINNER", "SNACK", "DESSERT", "DRINK")

/**
 * HTTP surface for the recipes domain. Handlers validate, keep the servings scaling, the Markdown/PDF
 * export rendering, the SSRF-guarded URL import and the cover-image file-I/O + multipart, call
 * [RecipeService] for all persistence, then broadcast. No handler touches a `Recipes*Table.`/
 * `dbQuery {}` (issue #565, following the TodoService pattern of #546). Broadcasts use the generic
 * SyncEnvelope via broadcastSync (#552).
 */
fun Route.recipeRoutes(imageConfig: ImageUploadConfig) {
    val service = RecipeService()

    route("/recipes") {
        // List recipes, optionally filtered by ?category=. Newest first.
        get {
            val categoryFilter = call.request.queryParameters["category"]?.uppercase()
            if (categoryFilter != null && categoryFilter !in VALID_CATEGORIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "unknown category"))
                return@get
            }
            call.respond(service.list(categoryFilter))
        }

        // Detail incl. ingredients + steps. Optional ?servings=N scales ingredient amounts.
        get("/{id}") {
            val id = call.uuidParam() ?: return@get
            val servingsParam = call.request.queryParameters["servings"]
            val targetServings = servingsParam?.toIntOrNull()
            if (servingsParam != null && (targetServings == null || targetServings < 1)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RECIPE", "servings must be >= 1"))
                return@get
            }
            val recipe = service.get(id)
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@get
            }
            call.respond(if (targetServings != null) recipe.scaledTo(targetServings) else recipe)
        }

        // Download a single recipe as Markdown (?format=md, default) or PDF (?format=pdf).
        get("/{id}/export") {
            val id = call.uuidParam() ?: return@get
            val format = (call.request.queryParameters["format"] ?: "md").lowercase()
            if (format != "md" && format != "pdf") {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_FORMAT", "format must be 'md' or 'pdf'"))
                return@get
            }
            val servingsParam = call.request.queryParameters["servings"]
            val targetServings = servingsParam?.toIntOrNull()
            if (servingsParam != null && (targetServings == null || targetServings < 1)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RECIPE", "servings must be >= 1"))
                return@get
            }
            val recipe = service.get(id)
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@get
            }
            val scaled = if (targetServings != null) recipe.scaledTo(targetServings) else recipe
            val slug = recipeSlug(scaled.title)

            if (format == "pdf") {
                call.attachmentHeader("rezept_$slug.pdf")
                call.respondBytes(buildRecipePdf(scaled), ContentType.Application.Pdf)
            } else {
                call.attachmentHeader("rezept_$slug.md")
                call.respondText(buildRecipeMarkdown(scaled), ContentType.parse("text/markdown; charset=UTF-8"))
            }
        }

        // Import a recipe DRAFT from a URL by parsing schema.org/Recipe JSON-LD (#430). Fetched
        // server-side (SSRF-guarded), mapped to the editable draft shape, NOT persisted.
        post("/import") {
            val req = call.receive<ImportRecipeRequest>()
            val url = req.url.trim()

            val html = when (val result = fetchForImport(url)) {
                is ImportFetch.Ok -> result.html
                is ImportFetch.Rejected -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.code, result.message))
                    return@post
                }
            }

            val draft = RecipeImport.fromHtml(html, sourceUrl = url)
            if (draft == null) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse("NO_RECIPE_DATA", "Auf dieser Seite wurden keine Rezeptdaten gefunden."),
                )
                return@post
            }
            call.respond(draft)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateRecipeRequest>()

            val validation = validate(
                title = req.title,
                category = req.category,
                servings = req.servings,
                prepTimeMinutes = req.prepTimeMinutes,
                cookTimeMinutes = req.cookTimeMinutes,
                ingredients = req.ingredients,
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@post
            }

            val recipe = service.create(req, username)
            WsSessionManager.broadcastSync(RECIPES_WS_CHANNEL, "RECIPE_CREATED", recipe, RecipeDto.serializer())
            call.respond(HttpStatusCode.Created, recipe)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateRecipeRequest>()

            val validation = validate(
                title = req.title,
                category = req.category,
                servings = req.servings,
                prepTimeMinutes = req.prepTimeMinutes,
                cookTimeMinutes = req.cookTimeMinutes,
                ingredients = req.ingredients,
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@put
            }

            val recipe = service.update(id, req)
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@put
            }
            WsSessionManager.broadcastSync(RECIPES_WS_CHANNEL, "RECIPE_UPDATED", recipe, RecipeDto.serializer())
            call.respond(recipe)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val outcome = service.delete(id)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@delete
            }
            outcome.files.forEach { deleteImageFile(imageConfig, it) }
            WsSessionManager.broadcastSync(RECIPES_WS_CHANNEL, "RECIPE_DELETED", outcome.recipe, RecipeDto.serializer())
            call.respond(HttpStatusCode.NoContent)
        }

        // --- Cover image (a recipe has at most one; shared between both users) ---

        post("/{id}/images") {
            val username = call.username()
            val recipeId = call.uuidParam() ?: return@post

            if (!service.exists(recipeId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@post
            }

            val upload = when (val received = call.receiveImageUpload(imageConfig)) {
                is ImageUploadResult.Rejected -> {
                    call.respondImageRejection(received.reason, imageConfig)
                    return@post
                }
                ImageUploadResult.None -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("NO_IMAGE", "no image file in request"))
                    return@post
                }
                is ImageUploadResult.Accepted -> received.upload
            }

            val imageId = UUID.randomUUID()
            val storedName = "$imageId.${ALLOWED_IMAGE_TYPES.getValue(upload.contentType)}"
            // The bytes are already streamed to a temp file; promote it to its final name.
            finalizeImageFile(imageConfig, upload.tempFile, storedName)

            val outcome = service.setCoverImage(
                recipeId, username,
                RecipeService.StoredUpload(imageId, storedName, upload.originalName, upload.contentType, upload.size),
            )
            if (outcome == null) {
                // recipe vanished between the existence check and the insert — undo the new file
                deleteImageFile(imageConfig, storedName)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@post
            }
            outcome.oldFiles.forEach { deleteImageFile(imageConfig, it) }
            WsSessionManager.broadcastSync(RECIPES_WS_CHANNEL, "RECIPE_UPDATED", outcome.recipe, RecipeDto.serializer())
            call.respond(HttpStatusCode.Created, outcome.recipe)
        }

        // Serve the raw image bytes. Any authenticated user may read them (recipes are shared).
        get("/{id}/images/{imageId}") {
            val recipeId = call.uuidParam() ?: return@get
            val imageId = call.uuidParam("imageId") ?: return@get

            val row = service.imageForDownload(recipeId, imageId)
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@get
            }
            val file = imageConfig.uploadDir.resolve(row.filename)
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image file missing"))
                return@get
            }
            // Stored names are immutable (UUID-based), so the bytes never change.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // Don't let the browser MIME-sniff a crafted file: the declared content-type is trusted
            // as-is and never validated to be a real image.
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Hand the browser the original upload name; use *inline* so Android Coil keeps rendering
            // the image in place (issue #272 / PR #271).
            val downloadName = safeImageFilename(row.originalName, row.contentType)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, downloadName).toString(),
            )
            call.respond(LocalFileContent(file.toFile(), ContentType.parse(row.contentType)))
        }

        // Remove a recipe's cover image. Returns the updated recipe.
        delete("/{id}/images/{imageId}") {
            val recipeId = call.uuidParam() ?: return@delete
            val imageId = call.uuidParam("imageId") ?: return@delete

            val outcome = service.deleteCoverImage(recipeId, imageId)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@delete
            }
            deleteImageFile(imageConfig, outcome.filename)
            WsSessionManager.broadcastSync(RECIPES_WS_CHANNEL, "RECIPE_UPDATED", outcome.recipe, RecipeDto.serializer())
            call.respond(outcome.recipe)
        }
    }

    syncChannel(RECIPES_WS_CHANNEL)
}

// --- Servings scaling + validation (pure, no DB) ------------------------------------------------

/** Scales ingredient amounts so the recipe yields [targetServings] portions. */
private fun RecipeDto.scaledTo(targetServings: Int): RecipeDto {
    if (targetServings < 1 || targetServings == servings || servings < 1) return this
    val factor = targetServings.toDouble() / servings.toDouble()
    return copy(
        servings = targetServings,
        ingredients = ingredients.map { ing ->
            ing.copy(amount = ing.amount?.let { Math.round(it * factor * 1000.0) / 1000.0 })
        },
    )
}

private fun validate(
    title: String? = null,
    category: String? = null,
    servings: Int? = null,
    prepTimeMinutes: Int? = null,
    cookTimeMinutes: Int? = null,
    ingredients: List<IngredientInput>? = null,
): ErrorResponse? = when {
    title != null && title.isBlank() -> ErrorResponse("INVALID_RECIPE", "title must not be blank")
    category != null && category.uppercase() !in VALID_CATEGORIES -> ErrorResponse("INVALID_CATEGORY", "unknown category")
    servings != null && servings < 1 -> ErrorResponse("INVALID_RECIPE", "servings must be >= 1")
    prepTimeMinutes != null && prepTimeMinutes < 0 -> ErrorResponse("INVALID_RECIPE", "prepTimeMinutes must be >= 0")
    cookTimeMinutes != null && cookTimeMinutes < 0 -> ErrorResponse("INVALID_RECIPE", "cookTimeMinutes must be >= 0")
    ingredients?.any { it.amount != null && it.amount < 0.0 } == true ->
        ErrorResponse("INVALID_INGREDIENT", "ingredient amount must be >= 0")
    else -> null
}

/** Sets `Content-Disposition: attachment; filename="…"` so the browser downloads the body. */
private fun ApplicationCall.attachmentHeader(filename: String) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
    )
}

// --- URL import fetch + SSRF guard (Issue #430; no DB, stays in the route) -----------------------

/** Outcome of fetching a page for import: either the HTML body or a client-facing rejection. */
private sealed interface ImportFetch {
    data class Ok(val html: String) : ImportFetch
    data class Rejected(val code: String, val message: String) : ImportFetch
}

// Hard caps so a malicious/huge page can't exhaust the backend.
private const val IMPORT_MAX_BYTES = 5 * 1024 * 1024 // 5 MB
private val IMPORT_TIMEOUT: Duration = Duration.ofSeconds(10)

// One shared client. NEVER follow redirects automatically: a redirect could bounce an allowed public
// URL to an internal one, bypassing the SSRF host check. We resolve each hop ourselves.
private val importHttpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(IMPORT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

/**
 * Fetch [rawUrl] server-side for recipe import with SSRF protection: http(s) only, resolved host must
 * not be private/loopback/link-local/multicast/wildcard, redirects followed manually (max 5 hops,
 * re-validating every hop), connect/read timeout + a 5 MB body cap.
 */
private suspend fun fetchForImport(rawUrl: String): ImportFetch = withContext(Dispatchers.IO) {
    var current = rawUrl
    repeat(6) { hop ->
        val uri = runCatching { URI(current) }.getOrNull()
            ?: return@withContext ImportFetch.Rejected("INVALID_URL", "Ungültige URL.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return@withContext ImportFetch.Rejected("INVALID_URL", "Nur http(s)-URLs werden unterstützt.")
        }
        val host = uri.host
            ?: return@withContext ImportFetch.Rejected("INVALID_URL", "URL ohne Host.")
        // Resolve and reject any address that points back into our own network.
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        if (addresses.isNullOrEmpty()) {
            return@withContext ImportFetch.Rejected("FETCH_FAILED", "Host konnte nicht aufgelöst werden.")
        }
        if (addresses.any { it.isBlockedForImport() }) {
            return@withContext ImportFetch.Rejected("BLOCKED_HOST", "Diese Adresse ist nicht erlaubt.")
        }

        val request = HttpRequest.newBuilder(uri)
            .timeout(IMPORT_TIMEOUT)
            .header("User-Agent", "HomeBase-RecipeImport/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .GET()
            .build()

        val response = runCatching {
            importHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }.getOrNull()
            ?: return@withContext ImportFetch.Rejected("FETCH_FAILED", "Seite konnte nicht geladen werden.")

        val status = response.statusCode()
        if (status in 300..399) {
            val location = response.headers().firstValue("location").orElse(null)
                ?: return@withContext ImportFetch.Rejected("FETCH_FAILED", "Weiterleitung ohne Ziel.")
            // Resolve relative redirects against the current URL.
            current = runCatching { uri.resolve(location).toString() }.getOrElse { location }
            response.body().close()
            return@repeat // next hop
        }
        if (status !in 200..299) {
            response.body().close()
            return@withContext ImportFetch.Rejected("FETCH_FAILED", "Seite antwortete mit HTTP $status.")
        }

        // Read the body with a hard byte cap.
        val bytes = response.body().use { it.readNBytes(IMPORT_MAX_BYTES + 1) }
        if (bytes.size > IMPORT_MAX_BYTES) {
            return@withContext ImportFetch.Rejected("TOO_LARGE", "Die Seite ist zu groß.")
        }
        // Decode using the Content-Type charset when the server declares one (some German recipe sites
        // still serve ISO-8859-1/Windows-1252). Fall back to UTF-8 for the common/unknown case.
        val charset = response.headers().firstValue("content-type").orElse(null)
            ?.let { charsetFromContentType(it) }
            ?: StandardCharsets.UTF_8
        return@withContext ImportFetch.Ok(String(bytes, charset))
    }
    ImportFetch.Rejected("TOO_MANY_REDIRECTS", "Zu viele Weiterleitungen.")
}

/**
 * Pull the charset from a Content-Type header, or null when absent/unsupported. Internal so the
 * import-charset handling can be unit-tested without a network.
 */
internal fun charsetFromContentType(contentType: String): Charset? {
    val name = Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE)
        .find(contentType)?.groupValues?.get(1)?.trim()
        ?: return null
    return runCatching { Charset.forName(name) }.getOrNull()
}

/** True for addresses we must never let the importer reach (SSRF protection). */
internal fun InetAddress.isBlockedForImport(): Boolean =
    isAnyLocalAddress ||      // 0.0.0.0 / ::
        isLoopbackAddress ||  // 127.0.0.0/8, ::1
        isLinkLocalAddress || // 169.254/16, fe80::/10 (incl. cloud metadata 169.254.169.254)
        isSiteLocalAddress || // 10/8, 172.16/12, 192.168/16, fec0::/10
        isMulticastAddress ||
        // Carrier-grade NAT 100.64.0.0/10 and IPv6 unique-local fc00::/7 aren't covered by the
        // java.net predicates — block them explicitly.
        isCgnatOrUniqueLocal()

internal fun InetAddress.isCgnatOrUniqueLocal(): Boolean {
    val a = address
    return when (a.size) {
        4 -> (a[0].toInt() and 0xFF) == 100 && ((a[1].toInt() and 0xFF) in 64..127) // 100.64.0.0/10
        16 -> (a[0].toInt() and 0xFE) == 0xFC // fc00::/7
        else -> false
    }
}
