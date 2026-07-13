package com.homebase.service

import com.homebase.db.ProjectsTable
import com.homebase.db.TimeEntriesTable
import com.homebase.db.TimeWorkTargetsTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import com.homebase.routes.userExists
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val TIMER_START_LOCKS = ConcurrentHashMap<String, Any>()
private const val PG_UNIQUE_VIOLATION = "23505"
private const val DEFAULT_INDEX_NAME = "time_work_targets_one_default"

/**
 * Owns the time-tracking domain's persistence and business rules (issue #564, following the
 * TodoService pattern of #546): projects, entries (start/stop/create/split/edit), the CSV data load
 * and the Wochensoll targets/periods with the one-default-per-period invariant (#59). Methods are
 * `suspend` + `dbQuery {}` (#549); the forecast/credits math already lives in `time/` and is untouched.
 *
 * The route keeps HTTP parsing/validation, CSV rendering and the post-commit broadcasts; in-tx faults
 * come back as typed results whose ErrorResponse.code the route maps to a status (as it did before).
 */
class TimeService {

    // ---- Projects --------------------------------------------------------

    suspend fun listProjects(): List<ProjectDto> = dbQuery {
        ProjectsTable.selectAll()
            .orderBy(ProjectsTable.archived, SortOrder.ASC)
            .orderBy(ProjectsTable.name, SortOrder.ASC)
            .map { it.toProjectDto() }
    }

    /** Caller has validated name (non-blank) + color (hex). */
    suspend fun createProject(name: String, color: String, username: String): ProjectDto = dbQuery {
        val id = UUID.randomUUID()
        ProjectsTable.insert {
            it[ProjectsTable.id] = id
            it[ProjectsTable.name] = name.trim()
            it[ProjectsTable.color] = color
            it[archived] = false
            it[createdBy] = username
            it[createdAt] = Instant.now()
        }
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
    }

    /** null = project not found (→ 404). Caller has validated any provided name/color. */
    suspend fun updateProject(id: UUID, req: UpdateProjectRequest): ProjectDto? = dbQuery {
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull() ?: return@dbQuery null
        ProjectsTable.update({ ProjectsTable.id eq id }) {
            req.name?.let { v -> it[name] = v.trim() }
            req.color?.let { v -> it[color] = v }
        }
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
    }

    /** null = project not found (→ 404). */
    suspend fun archiveProject(id: UUID, archivedTarget: Boolean): ProjectDto? = dbQuery {
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull() ?: return@dbQuery null
        ProjectsTable.update({ ProjectsTable.id eq id }) { it[archived] = archivedTarget }
        ProjectsTable.selectAll().where { ProjectsTable.id eq id }.single().toProjectDto()
    }

    // ---- Entries ---------------------------------------------------------

    suspend fun listEntries(projectId: UUID?, userId: String?, from: Instant?, to: Instant?): List<TimeEntryDto> = dbQuery {
        val query = TimeEntriesTable.selectAll()
        projectId?.let { pid -> query.andWhere { TimeEntriesTable.projectId eq pid } }
        userId?.let { uid -> query.andWhere { TimeEntriesTable.userId eq uid } }
        from?.let { f -> query.andWhere { TimeEntriesTable.startedAt greaterEq f } }
        to?.let { t -> query.andWhere { TimeEntriesTable.startedAt lessEq t } }
        query.orderBy(TimeEntriesTable.startedAt, SortOrder.DESC).map { it.toEntryDto() }
    }

    suspend fun runningAll(): List<TimeEntryDto> = dbQuery {
        TimeEntriesTable.selectAll()
            .where { TimeEntriesTable.stoppedAt.isNull() }
            .orderBy(TimeEntriesTable.userId, SortOrder.ASC)
            .map { it.toEntryDto() }
    }

    sealed interface StartResult {
        data class Ok(val stopped: TimeEntryDto?, val started: TimeEntryDto) : StartResult
        data object UserNotFound : StartResult
        data object ProjectNotFound : StartResult
        data class Conflict(val error: ErrorResponse) : StartResult
    }

    /**
     * Starts a timer for [targetUser] (the partner when it differs from [caller]). Serialized per
     * target user via a monitor + blocking transaction — a suspend call cannot cross a `synchronized`
     * monitor, so this deliberately keeps `transaction {}` instead of `dbQuery {}` inside the lock
     * (the one-running-timer-per-user invariant, #549). Caller has parsed [projectId].
     */
    suspend fun startTimer(caller: String, targetUser: String, projectId: UUID, description: String?): StartResult {
        if (targetUser != caller && !dbQuery { userExists(targetUser) }) return StartResult.UserNotFound
        // Lock on the *target* user so concurrent starts for the same person serialize.
        val startLock = TIMER_START_LOCKS.computeIfAbsent(targetUser) { Any() }
        return synchronized(startLock) {
            transaction {
                val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                    ?: return@transaction StartResult.ProjectNotFound
                if (project[ProjectsTable.archived]) {
                    return@transaction StartResult.Conflict(ErrorResponse("PROJECT_ARCHIVED", "Project is archived"))
                }
                val now = Instant.now()
                // Stop any timer that is still running for the target user.
                val stopped = TimeEntriesTable.selectAll()
                    .where { (TimeEntriesTable.userId eq targetUser) and TimeEntriesTable.stoppedAt.isNull() }
                    .forUpdate(ForUpdateOption.ForUpdate)
                    .singleOrNull()
                val stoppedDto = stopped?.let { row ->
                    val sid = row[TimeEntriesTable.id]
                    TimeEntriesTable.update({ TimeEntriesTable.id eq sid }) {
                        it[stoppedAt] = now
                        it[updatedAt] = now
                    }
                    TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq sid }.single().toEntryDto()
                }

                val id = UUID.randomUUID()
                TimeEntriesTable.insert {
                    it[TimeEntriesTable.id] = id
                    it[TimeEntriesTable.projectId] = projectId
                    it[userId] = targetUser
                    it[startedAt] = now
                    it[stoppedAt] = null
                    it[TimeEntriesTable.description] = description?.trim()?.takeIf { d -> d.isNotEmpty() }
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                val startedDto = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
                StartResult.Ok(stoppedDto, startedDto)
            }
        }
    }

    sealed interface StopResult {
        data class Ok(val entry: TimeEntryDto) : StopResult
        data object UserNotFound : StopResult
        data object NoRunningTimer : StopResult
    }

    suspend fun stopTimer(caller: String, targetUser: String): StopResult {
        if (targetUser != caller && !dbQuery { userExists(targetUser) }) return StopResult.UserNotFound
        val entry = dbQuery {
            val running = TimeEntriesTable.selectAll()
                .where { (TimeEntriesTable.userId eq targetUser) and TimeEntriesTable.stoppedAt.isNull() }
                .forUpdate(ForUpdateOption.ForUpdate)
                .singleOrNull() ?: return@dbQuery null
            val id = running[TimeEntriesTable.id]
            val now = Instant.now()
            TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
                it[stoppedAt] = now
                it[updatedAt] = now
            }
            TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto()
        } ?: return StopResult.NoRunningTimer
        return StopResult.Ok(entry)
    }

    sealed interface CreateEntryResult {
        data class Ok(val entry: TimeEntryDto) : CreateEntryResult
        data object UserNotFound : CreateEntryResult
        data object ProjectNotFound : CreateEntryResult
        data class Conflict(val error: ErrorResponse) : CreateEntryResult
    }

    /** Caller has parsed [projectId]/[started]/[stopped] and checked stopped>started. */
    suspend fun createEntry(caller: String, targetUser: String, projectId: UUID, started: Instant, stopped: Instant, description: String?): CreateEntryResult {
        if (targetUser != caller && !dbQuery { userExists(targetUser) }) return CreateEntryResult.UserNotFound
        return dbQuery {
            val project = ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                ?: return@dbQuery CreateEntryResult.ProjectNotFound
            if (project[ProjectsTable.archived]) {
                return@dbQuery CreateEntryResult.Conflict(ErrorResponse("PROJECT_ARCHIVED", "Project is archived"))
            }
            val id = UUID.randomUUID()
            val now = Instant.now()
            TimeEntriesTable.insert {
                it[TimeEntriesTable.id] = id
                it[TimeEntriesTable.projectId] = projectId
                it[userId] = targetUser
                it[startedAt] = started
                it[stoppedAt] = stopped
                it[TimeEntriesTable.description] = description?.trim()?.takeIf { d -> d.isNotEmpty() }
                it[createdAt] = now
                it[updatedAt] = now
            }
            CreateEntryResult.Ok(TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto())
        }
    }

    sealed interface SplitResult {
        data class Ok(val response: SplitTimeEntryResponse) : SplitResult
        data object NotFound : SplitResult
        data class Invalid(val error: ErrorResponse) : SplitResult
    }

    /** Caller has parsed [splitAt] and checked breakMinutes>=0. */
    suspend fun splitEntry(id: UUID, splitAt: Instant, breakMinutes: Int): SplitResult = dbQuery {
        val existing = TimeEntriesTable.selectAll()
            .where { TimeEntriesTable.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull() ?: return@dbQuery SplitResult.NotFound
        val stopped = existing[TimeEntriesTable.stoppedAt]
            ?: return@dbQuery SplitResult.Invalid(ErrorResponse("ENTRY_RUNNING", "A running timer cannot be split — stop it first"))
        val started = existing[TimeEntriesTable.startedAt]
        if (!splitAt.isAfter(started) || !stopped.isAfter(splitAt)) {
            return@dbQuery SplitResult.Invalid(ErrorResponse("INVALID_RANGE", "splitAt must lie strictly between startedAt and stoppedAt"))
        }
        // computed only after the range check — the cut is inside a real entry here, so adding the
        // break cannot overflow Instant (DateTimeException)
        val secondStart = splitAt.plusSeconds(breakMinutes * 60L)
        if (!stopped.isAfter(secondStart)) {
            return@dbQuery SplitResult.Invalid(ErrorResponse("INVALID_RANGE", "the break must end before the entry's stoppedAt"))
        }
        val now = Instant.now()
        TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
            it[stoppedAt] = splitAt
            it[updatedAt] = now
        }
        val secondId = UUID.randomUUID()
        TimeEntriesTable.insert {
            it[TimeEntriesTable.id] = secondId
            it[projectId] = existing[TimeEntriesTable.projectId]
            it[userId] = existing[TimeEntriesTable.userId]
            it[startedAt] = secondStart
            it[TimeEntriesTable.stoppedAt] = stopped
            it[description] = existing[TimeEntriesTable.description]
            it[createdAt] = now
            it[updatedAt] = now
        }
        SplitResult.Ok(
            SplitTimeEntryResponse(
                first = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto(),
                second = TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq secondId }.single().toEntryDto(),
            ),
        )
    }

    sealed interface UpdateEntryResult {
        data class Ok(val entry: TimeEntryDto) : UpdateEntryResult
        data object NotFound : UpdateEntryResult
        /** Route maps the status from error.code: NOT_FOUND→404, PROJECT_ARCHIVED→409, else 400. */
        data class Fault(val error: ErrorResponse) : UpdateEntryResult
    }

    /** Caller has parsed [newProjectId]/[newStarted]/[newStopped]. */
    suspend fun updateEntry(id: UUID, req: UpdateTimeEntryRequest, newProjectId: UUID?, newStarted: Instant?, newStopped: Instant?): UpdateEntryResult = dbQuery {
        val existing = TimeEntriesTable.selectAll()
            .where { TimeEntriesTable.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull() ?: return@dbQuery UpdateEntryResult.NotFound
        if (newProjectId != null) {
            val project = ProjectsTable.selectAll().where { ProjectsTable.id eq newProjectId }.singleOrNull()
                ?: return@dbQuery UpdateEntryResult.Fault(ErrorResponse("NOT_FOUND", "Project not found"))
            if (project[ProjectsTable.archived]) {
                return@dbQuery UpdateEntryResult.Fault(ErrorResponse("PROJECT_ARCHIVED", "Project is archived"))
            }
        }
        val effectiveStart = newStarted ?: existing[TimeEntriesTable.startedAt]
        val effectiveStop = newStopped ?: existing[TimeEntriesTable.stoppedAt]
        if (effectiveStop != null && !effectiveStop.isAfter(effectiveStart)) {
            return@dbQuery UpdateEntryResult.Fault(ErrorResponse("INVALID_RANGE", "stoppedAt must be after startedAt"))
        }
        TimeEntriesTable.update({ TimeEntriesTable.id eq id }) {
            newProjectId?.let { v -> it[projectId] = v }
            newStarted?.let { v -> it[startedAt] = v }
            newStopped?.let { v -> it[stoppedAt] = v }
            req.description?.let { v -> it[description] = v.trim().takeIf { d -> d.isNotEmpty() } }
            it[updatedAt] = Instant.now()
        }
        UpdateEntryResult.Ok(TimeEntriesTable.selectAll().where { TimeEntriesTable.id eq id }.single().toEntryDto())
    }

    /** null = entry not found (→ 404). */
    suspend fun deleteEntry(id: UUID): TimeEntryDto? = dbQuery {
        val existing = TimeEntriesTable.selectAll()
            .where { TimeEntriesTable.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull() ?: return@dbQuery null
        TimeEntriesTable.deleteWhere { TimeEntriesTable.id eq id }
        existing.toEntryDto()
    }

    // ---- CSV export data -------------------------------------------------

    /** The completed entries (filtered) + a project-id→name map the CSV renderer needs. */
    class LoadedCsv(val entries: List<CsvRow>, val projectNames: Map<UUID, String>)
    class CsvRow(val project: String, val user: String, val startedAt: Instant, val stoppedAt: Instant, val description: String)

    suspend fun loadCsvData(projectId: UUID?, from: Instant?, to: Instant?): LoadedCsv = dbQuery {
        val projectNames = ProjectsTable.selectAll()
            .associate { it[ProjectsTable.id] to it[ProjectsTable.name] }
        val query = TimeEntriesTable.selectAll()
            .andWhere { TimeEntriesTable.stoppedAt.isNotNull() }
        projectId?.let { pid -> query.andWhere { TimeEntriesTable.projectId eq pid } }
        from?.let { f -> query.andWhere { TimeEntriesTable.startedAt greaterEq f } }
        to?.let { t -> query.andWhere { TimeEntriesTable.startedAt lessEq t } }
        val entries = query.orderBy(TimeEntriesTable.startedAt, SortOrder.ASC).map { row ->
            CsvRow(
                project = projectNames[row[TimeEntriesTable.projectId]] ?: "—",
                user = row[TimeEntriesTable.userId],
                startedAt = row[TimeEntriesTable.startedAt],
                stoppedAt = row[TimeEntriesTable.stoppedAt]!!,
                description = row[TimeEntriesTable.description] ?: "",
            )
        }
        LoadedCsv(entries, projectNames)
    }

    // ---- Wochensoll targets + periods (#31/#59) --------------------------

    suspend fun listTargets(): List<WorkTargetDto> = dbQuery {
        TimeWorkTargetsTable.selectAll()
            .orderBy(
                TimeWorkTargetsTable.userId to SortOrder.ASC,
                TimeWorkTargetsTable.validFrom to SortOrder.ASC,
            )
            .map { it.toTargetDto() }
    }

    sealed interface TargetResult {
        data class Ok(val target: WorkTargetDto) : TargetResult
        data object UserNotFound : TargetResult
        /** Route maps status from error.code: DEFAULT_REQUIRED/DEFAULT_CONFLICT→409, else 404. */
        data class Fault(val error: ErrorResponse) : TargetResult
    }

    /** Caller has validated hours bounds and parsed [validFrom]. */
    suspend fun upsertTarget(userId: String, projectId: UUID, req: UpsertWorkTargetRequest, validFrom: LocalDate): TargetResult {
        val hours = req.weeklyHours
        return try {
            dbQuery {
                if (!userExists(userId)) return@dbQuery TargetResult.UserNotFound
                ProjectsTable.selectAll().where { ProjectsTable.id eq projectId }.singleOrNull()
                    ?: return@dbQuery TargetResult.Fault(ErrorResponse("NOT_FOUND", "Project not found"))

                // Everything below is scoped to the one period: a person's default project, hours sum
                // and the invariant are all per-period.
                val allUserRows = TimeWorkTargetsTable.selectAll()
                    .where { TimeWorkTargetsTable.userId eq userId }
                    .toList()
                val rows = allUserRows.filter { it[TimeWorkTargetsTable.validFrom].isEqual(validFrom) }
                // A non-base period must be created (and seeded) via POST /periods first: editing a
                // target for a period this person was never seeded into would insert a lone row, and
                // the forecast/credits would then treat that stub as the person's whole Wochensoll for
                // those weeks — silently zeroing their other projects. Allow the base period (first-time
                // setup) and a person with no targets at all (nothing to shadow — this is their first).
                if (validFrom.toString() != BASE_TARGET_PERIOD && rows.isEmpty() && allUserRows.isNotEmpty()) {
                    return@dbQuery TargetResult.Fault(
                        ErrorResponse("PERIOD_NOT_FOUND", "no Wochensoll period starts on $validFrom — create the period first"),
                    )
                }
                val existing = rows.firstOrNull { it[TimeWorkTargetsTable.projectId] == projectId }
                val defaultProjectId = rows.firstOrNull { it[TimeWorkTargetsTable.isDefault] }?.get(TimeWorkTargetsTable.projectId)
                val newHours = hours ?: existing?.get(TimeWorkTargetsTable.weeklyHours) ?: 0.0
                val sumAfter = rows.filter { it[TimeWorkTargetsTable.projectId] != projectId }
                    .sumOf { it[TimeWorkTargetsTable.weeklyHours] } + newHours

                // Invariant (#59): configured hours ⇒ exactly one default project.
                if (req.isDefault == false && defaultProjectId == projectId && sumAfter > 0) {
                    return@dbQuery TargetResult.Fault(
                        ErrorResponse(
                            "DEFAULT_REQUIRED",
                            "a default project is required while weekly hours are configured — set another project as default first",
                        ),
                    )
                }
                // First configured hours for a person without any default → this row becomes default.
                val autoDefault = req.isDefault == null && defaultProjectId == null && sumAfter > 0

                if (req.isDefault == true) {
                    TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom) and TimeWorkTargetsTable.isDefault }) {
                        it[isDefault] = false
                    }
                }
                if (existing == null) {
                    TimeWorkTargetsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[TimeWorkTargetsTable.userId] = userId
                        it[TimeWorkTargetsTable.projectId] = projectId
                        it[weeklyHours] = hours ?: 0.0
                        it[isDefault] = req.isDefault ?: autoDefault
                        it[TimeWorkTargetsTable.validFrom] = validFrom
                    }
                } else {
                    TimeWorkTargetsTable.update({ (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) and (TimeWorkTargetsTable.validFrom eq validFrom) }) {
                        hours?.let { v -> it[weeklyHours] = v }
                        req.isDefault?.let { v -> it[isDefault] = v }
                        if (autoDefault) it[isDefault] = true
                    }
                }
                TargetResult.Ok(
                    TimeWorkTargetsTable.selectAll()
                        .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.projectId eq projectId) and (TimeWorkTargetsTable.validFrom eq validFrom) }
                        .single().toTargetDto(),
                )
            }
        } catch (e: ExposedSQLException) {
            // Two concurrent isDefault=true requests race the partial unique index → 409, retry. Any
            // other SQL error re-throws and becomes a 500 (#57).
            if (e.isDefaultIndexConflict()) {
                TargetResult.Fault(ErrorResponse("DEFAULT_CONFLICT", "gleichzeitiger Default-Wechsel — bitte wiederholen"))
            } else {
                throw e
            }
        }
    }

    sealed interface PeriodResult {
        data class Ok(val targets: List<WorkTargetDto>) : PeriodResult
        data object UserNotFound : PeriodResult
        data class Conflict(val error: ErrorResponse) : PeriodResult
    }

    /** Caller has parsed [validFrom] and rejected the base period. */
    suspend fun createPeriod(userId: String, validFrom: LocalDate): PeriodResult = dbQuery {
        if (!userExists(userId)) return@dbQuery PeriodResult.UserNotFound
        val rows = TimeWorkTargetsTable.selectAll().where { TimeWorkTargetsTable.userId eq userId }.toList()
        if (rows.any { it[TimeWorkTargetsTable.validFrom].isEqual(validFrom) }) {
            return@dbQuery PeriodResult.Conflict(ErrorResponse("PERIOD_EXISTS", "a period with this start date already exists"))
        }
        // Seed from the latest period on/before the new start date. A period with nothing to seed from
        // — the person has no target valid on/before validFrom (no base row, or only later periods) —
        // must not be "created": it would insert zero rows, read back as non-existent, and a later edit
        // at this date would then be rejected by upsertTarget's period guard. Reject it honestly instead
        // of returning a success that created nothing.
        val sourceDate = rows.map { it[TimeWorkTargetsTable.validFrom] }
            .filter { !it.isAfter(validFrom) }
            .maxOrNull()
            ?: return@dbQuery PeriodResult.Conflict(
                ErrorResponse("NO_SEED_SOURCE", "no earlier Wochensoll to seed this period from — configure the base target first"),
            )
        val seed = rows.filter { it[TimeWorkTargetsTable.validFrom].isEqual(sourceDate) }
        seed.forEach { row ->
            TimeWorkTargetsTable.insert {
                it[id] = UUID.randomUUID()
                it[TimeWorkTargetsTable.userId] = userId
                it[projectId] = row[TimeWorkTargetsTable.projectId]
                it[weeklyHours] = row[TimeWorkTargetsTable.weeklyHours]
                it[isDefault] = row[TimeWorkTargetsTable.isDefault]
                it[TimeWorkTargetsTable.validFrom] = validFrom
            }
        }
        PeriodResult.Ok(
            TimeWorkTargetsTable.selectAll()
                .where { (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom) }
                .map { it.toTargetDto() },
        )
    }

    /** Returns the number of rows deleted (0 = no such period → 404). Caller rejected the base period. */
    suspend fun deletePeriod(userId: String, validFrom: LocalDate): Int = dbQuery {
        TimeWorkTargetsTable.deleteWhere {
            (TimeWorkTargetsTable.userId eq userId) and (TimeWorkTargetsTable.validFrom eq validFrom)
        }
    }
}

private fun ExposedSQLException.isDefaultIndexConflict(): Boolean {
    val cause = cause as? java.sql.SQLException ?: return false
    return cause.sqlState == PG_UNIQUE_VIOLATION &&
        cause.message.orEmpty().contains(DEFAULT_INDEX_NAME, ignoreCase = true)
}

// ---- Mappers (run inside a transaction) --------------------------------

private fun ResultRow.toProjectDto() = ProjectDto(
    id = this[ProjectsTable.id].toString(),
    name = this[ProjectsTable.name],
    color = this[ProjectsTable.color],
    archived = this[ProjectsTable.archived],
    createdBy = this[ProjectsTable.createdBy],
    createdAt = this[ProjectsTable.createdAt].toString(),
)

private fun ResultRow.toEntryDto(): TimeEntryDto {
    val started = this[TimeEntriesTable.startedAt]
    val stopped = this[TimeEntriesTable.stoppedAt]
    return TimeEntryDto(
        id = this[TimeEntriesTable.id].toString(),
        projectId = this[TimeEntriesTable.projectId].toString(),
        userId = this[TimeEntriesTable.userId],
        startedAt = started.toString(),
        stoppedAt = stopped?.toString(),
        description = this[TimeEntriesTable.description],
        durationSeconds = stopped?.let { Duration.between(started, it).seconds },
        createdAt = this[TimeEntriesTable.createdAt].toString(),
        updatedAt = this[TimeEntriesTable.updatedAt].toString(),
    )
}

private fun ResultRow.toTargetDto() = WorkTargetDto(
    userId = this[TimeWorkTargetsTable.userId],
    projectId = this[TimeWorkTargetsTable.projectId].toString(),
    weeklyHours = this[TimeWorkTargetsTable.weeklyHours],
    isDefault = this[TimeWorkTargetsTable.isDefault],
    validFrom = this[TimeWorkTargetsTable.validFrom].toString(),
)
