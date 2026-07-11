package com.homebase.routes

import com.homebase.db.dbQuery
import com.homebase.db.IngredientsTable
import com.homebase.db.RecipeImagesTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val RECIPES_WS_CHANNEL = "recipes"
// LUNCH was dropped (collapsed into DINNER) — see migration V17. Clients only offer these five.
private val VALID_CATEGORIES = setOf("BREAKFAST", "DINNER", "SNACK", "DESSERT", "DRINK")

fun Route.recipeRoutes(imageConfig: ImageUploadConfig) {

    route("/recipes") {
        // List recipes, optionally filtered by ?category=. Newest first.
        get {
            val categoryFilter = call.request.queryParameters["category"]?.uppercase()
            if (categoryFilter != null && categoryFilter !in VALID_CATEGORIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "unknown category"))
                return@get
            }
            val recipes = dbQuery {
                RecipesTable.selectAll()
                    .apply { if (categoryFilter != null) andWhere { RecipesTable.category eq categoryFilter } }
                    .orderBy(RecipesTable.updatedAt, SortOrder.DESC)
                    .map { it.toRecipeDto() }
            }
            call.respond(recipes)
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
            val recipe = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()?.toRecipeDto()
            }
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@get
            }
            call.respond(if (targetServings != null) recipe.scaledTo(targetServings) else recipe)
        }

        // Download a single recipe as Markdown (?format=md, default) or PDF (?format=pdf).
        // Optional ?servings=N scales amounts exactly like the detail endpoint.
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
            val recipe = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()?.toRecipeDto()
            }
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

        // Import a recipe DRAFT from a URL by parsing the page's schema.org/Recipe JSON-LD
        // (Issue #430). The page is fetched server-side (SSRF-guarded: http(s) only, no
        // private/internal hosts, timeout + size cap), the Recipe node is extracted and mapped to
        // the editable draft shape. NOT persisted — the client pre-fills its editor; the user
        // reviews and saves via the normal POST.
        post("/import") {
            // Auth is enforced by the surrounding authenticate("auth-jwt") block; the draft is
            // not persisted, so there is no created_by to record here.
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
                ingredients = req.ingredients
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@post
            }

            val recipe = dbQuery {
                val id = UUID.randomUUID()
                val now = Instant.now()
                RecipesTable.insert {
                    it[RecipesTable.id] = id
                    it[title] = req.title.trim()
                    it[description] = req.description
                    it[servings] = req.servings ?: 1
                    it[prepTimeMinutes] = req.prepTimeMinutes
                    it[cookTimeMinutes] = req.cookTimeMinutes
                    it[category] = req.category.uppercase()
                    it[createdBy] = username
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                insertIngredients(id, req.ingredients)
                insertSteps(id, req.steps)
                RecipesTable.selectAll().where { RecipesTable.id eq id }.single().toRecipeDto()
            }

            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, appJson.encodeToString(RecipeWsMessage("RECIPE_CREATED", recipe)))
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
                ingredients = req.ingredients
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@put
            }

            val recipe = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null

                RecipesTable.update({ RecipesTable.id eq id }) {
                    req.title?.let { v -> it[title] = v.trim() }
                    req.description?.let { v -> it[description] = v }
                    req.servings?.let { v -> it[servings] = v }
                    req.prepTimeMinutes?.let { v -> it[prepTimeMinutes] = v }
                    req.cookTimeMinutes?.let { v -> it[cookTimeMinutes] = v }
                    req.category?.let { v -> it[category] = v.uppercase() }
                    it[updatedAt] = Instant.now()
                }
                // Ingredients / steps are owned by the recipe: when supplied, replace wholesale.
                req.ingredients?.let { items ->
                    IngredientsTable.deleteWhere { IngredientsTable.recipeId eq id }
                    insertIngredients(id, items)
                }
                req.steps?.let { steps ->
                    RecipeStepsTable.deleteWhere { RecipeStepsTable.recipeId eq id }
                    insertSteps(id, steps)
                }
                RecipesTable.selectAll().where { RecipesTable.id eq id }.single().toRecipeDto()
            }

            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@put
            }

            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, appJson.encodeToString(RecipeWsMessage("RECIPE_UPDATED", recipe)))
            call.respond(recipe)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val outcome = dbQuery {
                val existing = RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()
                    ?: return@dbQuery null
                val dto = existing.toRecipeDto()
                // Capture the image filenames before the cascade removes their rows so we can
                // clean up the files on disk afterwards.
                val files = RecipeImagesTable.selectAll().where { RecipeImagesTable.recipeId eq id }
                    .map { it[RecipeImagesTable.filename] }
                IngredientsTable.deleteWhere { IngredientsTable.recipeId eq id }
                RecipeStepsTable.deleteWhere { RecipeStepsTable.recipeId eq id }
                RecipesTable.deleteWhere { RecipesTable.id eq id }
                dto to files
            }
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@delete
            }
            val (deleted, files) = outcome
            files.forEach { deleteImageFile(imageConfig, it) }
            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, appJson.encodeToString(RecipeWsMessage("RECIPE_DELETED", deleted)))
            call.respond(HttpStatusCode.NoContent)
        }

        // --- Cover image --------------------------------------------------
        // A recipe has at most one cover image (recipe_images.recipe_id is UNIQUE). Recipes are
        // shared between both users, so there is no per-image owner check; any authenticated user
        // may set, view and remove it. Streaming / validation / disk plumbing is shared with the
        // note images (see ImageUploads.kt).

        // Set (or replace) a recipe's cover image. Returns the updated recipe.
        post("/{id}/images") {
            val username = call.username()
            val recipeId = call.uuidParam() ?: return@post

            val exists = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull() != null
            }
            if (!exists) {
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

            val outcome = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull()
                    ?: return@dbQuery null
                // single cover image: drop the previous one (its file is removed after commit)
                val oldFiles = RecipeImagesTable.selectAll().where { RecipeImagesTable.recipeId eq recipeId }
                    .map { it[RecipeImagesTable.filename] }
                RecipeImagesTable.deleteWhere { RecipeImagesTable.recipeId eq recipeId }
                RecipeImagesTable.insert {
                    it[RecipeImagesTable.id] = imageId
                    it[RecipeImagesTable.recipeId] = recipeId
                    it[filename] = storedName
                    it[RecipeImagesTable.originalName] = upload.originalName
                    it[RecipeImagesTable.contentType] = upload.contentType
                    it[sizeBytes] = upload.size
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                RecipesTable.update({ RecipesTable.id eq recipeId }) { it[updatedAt] = Instant.now() }
                RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.single().toRecipeDto() to oldFiles
            }
            if (outcome == null) {
                // recipe vanished between the existence check and the insert — undo the new file
                deleteImageFile(imageConfig, storedName)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@post
            }
            val (recipe, oldFiles) = outcome
            oldFiles.forEach { deleteImageFile(imageConfig, it) }

            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, appJson.encodeToString(RecipeWsMessage("RECIPE_UPDATED", recipe)))
            call.respond(HttpStatusCode.Created, recipe)
        }

        // Serve the raw image bytes. Any authenticated user may read them (recipes are shared).
        get("/{id}/images/{imageId}") {
            val recipeId = call.uuidParam() ?: return@get
            val imageId = call.uuidParam("imageId") ?: return@get

            val row = dbQuery {
                RecipeImagesTable.selectAll()
                    .where { (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId) }
                    .singleOrNull()
            }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@get
            }
            val file = imageConfig.uploadDir.resolve(row[RecipeImagesTable.filename])
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image file missing"))
                return@get
            }
            // Stored names are immutable (UUID-based), so the bytes never change.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // Don't let the browser MIME-sniff a crafted file: the declared content-type is trusted
            // as-is and never validated to be a real image.
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Hand the browser the original upload name so a download is saved as e.g. "Lasagne.jpg"
            // instead of the browser's generic fallback. Use *inline* (not attachment) so Android
            // Coil keeps rendering the image in place. Ktor encodes the value (umlauts → RFC 5987),
            // we only sanitize it. Same fix as the notes endpoint (issue #272 / PR #271).
            val downloadName = safeImageFilename(
                row[RecipeImagesTable.originalName],
                row[RecipeImagesTable.contentType],
            )
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, downloadName).toString(),
            )
            call.respond(LocalFileContent(file.toFile(), ContentType.parse(row[RecipeImagesTable.contentType])))
        }

        // Remove a recipe's cover image. Returns the updated recipe.
        delete("/{id}/images/{imageId}") {
            val recipeId = call.uuidParam() ?: return@delete
            val imageId = call.uuidParam("imageId") ?: return@delete

            val outcome = dbQuery {
                RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull()
                    ?: return@dbQuery null
                val image = RecipeImagesTable.selectAll()
                    .where { (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId) }
                    .singleOrNull() ?: return@dbQuery null
                val filename = image[RecipeImagesTable.filename]
                RecipeImagesTable.deleteWhere {
                    (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId)
                }
                RecipesTable.update({ RecipesTable.id eq recipeId }) { it[updatedAt] = Instant.now() }
                filename to RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.single().toRecipeDto()
            }
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@delete
            }
            val (filename, recipe) = outcome
            deleteImageFile(imageConfig, filename)
            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, appJson.encodeToString(RecipeWsMessage("RECIPE_UPDATED", recipe)))
            call.respond(recipe)
        }
    }

    webSocket("/ws/recipes") {
        WsSessionManager.add(RECIPES_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(RECIPES_WS_CHANNEL, this)
        }
    }
}

// --- URL import fetch + SSRF guard (Issue #430) ---------------------------------------------

/** Outcome of fetching a page for import: either the HTML body or a client-facing rejection. */
private sealed interface ImportFetch {
    data class Ok(val html: String) : ImportFetch
    data class Rejected(val code: String, val message: String) : ImportFetch
}

// Hard caps so a malicious/huge page can't exhaust the backend.
private const val IMPORT_MAX_BYTES = 5 * 1024 * 1024 // 5 MB
private val IMPORT_TIMEOUT: Duration = Duration.ofSeconds(10)

// One shared client. NEVER follow redirects automatically: a redirect could bounce an allowed
// public URL to an internal one, bypassing the SSRF host check. We resolve each hop ourselves.
private val importHttpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(IMPORT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

/**
 * Fetch [rawUrl] server-side for recipe import with SSRF protection:
 *  - http(s) scheme only
 *  - the resolved host must not be a private / loopback / link-local / multicast / wildcard address
 *  - redirects are followed manually (max 5 hops), re-validating the host every hop
 *  - connect/read timeout + a 5 MB body cap
 *
 * Returns the HTML body on success, or a [ImportFetch.Rejected] with a stable error code.
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
        // Decode using the Content-Type charset when the server declares one (some German recipe
        // sites still serve ISO-8859-1/Windows-1252 — UTF-8 alone would mangle umlauts). Fall back
        // to UTF-8 for the common case or an unknown/missing charset.
        val charset = response.headers().firstValue("content-type").orElse(null)
            ?.let { charsetFromContentType(it) }
            ?: StandardCharsets.UTF_8
        return@withContext ImportFetch.Ok(String(bytes, charset))
    }
    ImportFetch.Rejected("TOO_MANY_REDIRECTS", "Zu viele Weiterleitungen.")
}

/**
 * Pull the charset from a Content-Type header (e.g. "text/html; charset=ISO-8859-1"), or null when
 * absent / unsupported. Internal so the import-charset handling can be unit-tested without a network.
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

/** Sets `Content-Disposition: attachment; filename="…"` so the browser downloads the body. */
private fun ApplicationCall.attachmentHeader(filename: String) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
    )
}

private fun validate(
    title: String? = null,
    category: String? = null,
    servings: Int? = null,
    prepTimeMinutes: Int? = null,
    cookTimeMinutes: Int? = null,
    ingredients: List<IngredientInput>? = null
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

// Must be called inside a transaction. Ingredient order is taken from list position.
private fun insertIngredients(recipeId: UUID, items: List<IngredientInput>) {
    items.filter { it.name.isNotBlank() }.forEachIndexed { index, ing ->
        IngredientsTable.insert {
            it[id] = UUID.randomUUID()
            it[IngredientsTable.recipeId] = recipeId
            it[name] = ing.name.trim()
            it[amount] = ing.amount?.let { a -> BigDecimal.valueOf(a) }
            it[unit] = ing.unit?.takeIf { u -> u.isNotBlank() }
            it[section] = ing.section?.trim()?.takeIf { s -> s.isNotBlank() }
            it[sortOrder] = index
        }
    }
}

// Must be called inside a transaction. Step numbers are 1-based list positions.
private fun insertSteps(recipeId: UUID, steps: List<RecipeStepInput>) {
    steps.filter { it.description.isNotBlank() }.forEachIndexed { index, step ->
        RecipeStepsTable.insert {
            it[id] = UUID.randomUUID()
            it[RecipeStepsTable.recipeId] = recipeId
            it[stepNumber] = index + 1
            it[description] = step.description.trim()
        }
    }
}

/** Scales ingredient amounts so the recipe yields [targetServings] portions. */
private fun RecipeDto.scaledTo(targetServings: Int): RecipeDto {
    if (targetServings < 1 || targetServings == servings || servings < 1) return this
    val factor = targetServings.toDouble() / servings.toDouble()
    return copy(
        servings = targetServings,
        ingredients = ingredients.map { ing ->
            ing.copy(amount = ing.amount?.let { Math.round(it * factor * 1000.0) / 1000.0 })
        }
    )
}

// Loads the recipe with its ingredients + steps. Must be called inside a transaction.
private fun ResultRow.toRecipeDto(): RecipeDto {
    val recipeId = this[RecipesTable.id]
    val ingredients = IngredientsTable.selectAll()
        .where { IngredientsTable.recipeId eq recipeId }
        .orderBy(IngredientsTable.sortOrder, SortOrder.ASC)
        .map {
            IngredientDto(
                id = it[IngredientsTable.id].toString(),
                name = it[IngredientsTable.name],
                amount = it[IngredientsTable.amount]?.toDouble(),
                unit = it[IngredientsTable.unit],
                section = it[IngredientsTable.section],
                sortOrder = it[IngredientsTable.sortOrder]
            )
        }
    val steps = RecipeStepsTable.selectAll()
        .where { RecipeStepsTable.recipeId eq recipeId }
        .orderBy(RecipeStepsTable.stepNumber, SortOrder.ASC)
        .map {
            RecipeStepDto(
                id = it[RecipeStepsTable.id].toString(),
                stepNumber = it[RecipeStepsTable.stepNumber],
                description = it[RecipeStepsTable.description]
            )
        }
    val image = RecipeImagesTable.selectAll()
        .where { RecipeImagesTable.recipeId eq recipeId }
        .firstOrNull()
        ?.let {
            RecipeImageDto(
                id = it[RecipeImagesTable.id].toString(),
                recipeId = it[RecipeImagesTable.recipeId].toString(),
                originalName = it[RecipeImagesTable.originalName],
                contentType = it[RecipeImagesTable.contentType],
                sizeBytes = it[RecipeImagesTable.sizeBytes],
                createdBy = it[RecipeImagesTable.createdBy],
                createdAt = it[RecipeImagesTable.createdAt].toString()
            )
        }
    return RecipeDto(
        id = recipeId.toString(),
        title = this[RecipesTable.title],
        description = this[RecipesTable.description],
        servings = this[RecipesTable.servings],
        prepTimeMinutes = this[RecipesTable.prepTimeMinutes],
        cookTimeMinutes = this[RecipesTable.cookTimeMinutes],
        category = this[RecipesTable.category],
        ingredients = ingredients,
        steps = steps,
        image = image,
        createdBy = this[RecipesTable.createdBy],
        createdAt = this[RecipesTable.createdAt].toString(),
        updatedAt = this[RecipesTable.updatedAt].toString()
    )
}
