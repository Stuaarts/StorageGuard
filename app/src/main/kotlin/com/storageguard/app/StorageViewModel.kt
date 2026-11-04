package com.storageguard.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.StatFs
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.storageguard.app.data.*
import com.storageguard.app.storage.Coverage
import com.storageguard.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class Page { HOME, FINDINGS, SCAN, SETTINGS, HISTORY }
data class DeviceStorage(val total: Long, val free: Long)
data class AppState(
    val ready: Boolean = false,
    val preferences: Preferences = Preferences(),
    val page: Page = Page.HOME,
    val coverage: Coverage = Coverage(false, false, false),
    val device: DeviceStorage? = null,
    val groups: List<DuplicateGroup> = emptyList(),
    val protected: Map<String, String> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val progress: ScanProgress = ScanProgress(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val busy: Boolean = false,
    val scanFinished: Boolean = false,
    val restored: Boolean = false,
    val issues: List<String> = emptyList(),
    val message: String? = null,
    val sessions: List<ScanSession> = emptyList(),
    val attempts: List<CleanupAttempt> = emptyList(),
) {
    val possibleBytes get() = groups.sumOf { it.possibleBytes }
    val selectedBytes get() = groups.flatMap { it.files }.filter { it.asset.uri in selected }.sumOf { it.asset.size ?: 0 }
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StorageGuardApplication
    private val db = app.database
    private val dao = db.dao()
    private val storage = app.storage
    private val preferences = app.preferences
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()
    private val consentChannel = Channel<IntentSenderRequest>(Channel.BUFFERED)
    val consent = consentChannel.receiveAsFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = preferences.flow.first()
                dao.interruptScans(System.currentTimeMillis())
                val files = dao.files()
                val groups = files.filter { it.groupId != null && it.sha256 != null }.groupBy { it.groupId!! }
                    .map { (id, rows) -> DuplicateGroup(id, rows.map { HashedAsset(it.asset(), it.sha256!!) }) }
                    .filter { it.files.size > 1 }.sortedByDescending { it.possibleBytes }
                _state.update { it.copy(ready = true, preferences = prefs, coverage = storage.coverage(),
                    device = deviceStorage(), groups = groups, protected = defaults(groups), restored = groups.isNotEmpty(),
                    progress = ScanProgress(discovered = files.size)) }
                reconcilePending(null)
            } catch (_: Exception) {
                _state.update { it.copy(ready = true, message = "Local data could not be loaded. Try reopening the app.") }
            }
            launch { preferences.flow.collect { prefs -> _state.update { it.copy(preferences = prefs) } } }
            launch { dao.sessions().collect { sessions -> _state.update { it.copy(sessions = sessions) } } }
            launch { dao.attempts().collect { attempts -> _state.update { it.copy(attempts = attempts) } } }
        }
    }

    fun page(page: Page) { _state.update { it.copy(page = page) } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun message(text: String) { _state.update { it.copy(message = text) } }
    fun refreshCoverage() { _state.update { it.copy(coverage = storage.coverage(), device = deviceStorage()) } }
    fun welcome() = viewModelScope.launch { preferences.welcome() }
    fun permanent(value: Boolean) = viewModelScope.launch { preferences.permanent(value) }

    fun addSource(uri: Uri, tree: Boolean, readOnly: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (readOnly) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { app.contentResolver.takePersistableUriPermission(uri, flags) }
            catch (_: SecurityException) { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            preferences.addSource("${if (tree) "T" else "D"}|$uri")
            message("Location added. Start a new scan to include it.")
        } catch (_: Exception) { message("Access could not be saved. Select the location again.") }
    }

    fun removeSource(source: String) = viewModelScope.launch(Dispatchers.IO) {
        if (_state.value.busy || _state.value.running) return@launch
        val uri = Uri.parse(source.substringAfter('|'))
        app.contentResolver.persistedUriPermissions.filter { it.uri == uri }.forEach { permission ->
            try { app.contentResolver.releasePersistableUriPermission(uri,
                (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) } catch (_: Exception) { }
        }
        preferences.removeSource(source)
        invalidateFindings()
    }

    fun scan() {
        if (_state.value.running || _state.value.busy || !_state.value.ready) return
        val sources = _state.value.preferences.sources
        _state.update { it.copy(running = true, paused = false, page = Page.SCAN, groups = emptyList(),
            selected = emptySet(), protected = emptyMap(), progress = ScanProgress(), issues = emptyList(), scanFinished = false, restored = false) }
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val session = ScanSession(UUID.randomUUID().toString(), System.currentTimeMillis(),
                scope = "${storage.coverage().label}; ${sources.size} selected locations")
            try {
                db.withTransaction { dao.clearFiles(); dao.clearGroups(); dao.session(session) }
                var lastProgressAt = 0L
                val result = DuplicateScanner(storage).scan(
                    discover = { emit -> storage.discover(sources, { issue ->
                        _state.update { it.copy(issues = (it.issues + issue).distinct()) }
                    }, emit) },
                    checkpoint = { while (_state.value.paused) { delay(100); ensureActive() } },
                    onProgress = { progress ->
                        // Avoid recomposing the whole app for every 128 KiB read.
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 100 || progress.phase == "Complete") {
                            _state.update { it.copy(progress = progress) }; lastProgressAt = now
                        }
                    },
                )
                val errorCount = result.progress.issues + _state.value.issues.size
                db.withTransaction {
                    db.saveResult(session.id, result)
                    dao.session(session.copy(endedAt = System.currentTimeMillis(), status = if (errorCount > 0) "PARTIAL" else "COMPLETE",
                        discovered = result.inventory.size, errors = errorCount))
                    dao.trimSessions()
                }
                _state.update { it.copy(groups = result.groups, protected = defaults(result.groups), progress = result.progress,
                    scanFinished = true, page = Page.FINDINGS) }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { dao.session(session.copy(endedAt = System.currentTimeMillis(), status = "CANCELLED",
                    discovered = _state.value.progress.discovered, errors = _state.value.progress.issues)) }
                _state.update { it.copy(message = "Scan cancelled. Start again when you are ready.") }
            } catch (_: Exception) {
                withContext(NonCancellable) { dao.session(session.copy(endedAt = System.currentTimeMillis(), status = "FAILED")) }
                message("Scan could not finish. Check access and try again.")
            } finally { _state.update { it.copy(running = false, paused = false) } }
        }
    }

    fun pause() { if (_state.value.running) _state.update { it.copy(paused = !it.paused) } }
    fun cancel() { scanJob?.cancel() }
    fun protect(group: DuplicateGroup, uri: String) {
        if (_state.value.busy) return
        _state.update { it.copy(protected = it.protected + (group.id to uri), selected = it.selected - uri) }
    }
    fun select(group: DuplicateGroup, asset: Asset) {
        val current = _state.value
        if (current.busy || !asset.removable || asset.identity == null || current.protected[group.id] == asset.uri ||
            (asset.source == Source.DOCUMENT && !current.preferences.permanentDelete)) return
        _state.update { it.copy(selected = if (asset.uri in it.selected) it.selected - asset.uri else it.selected + asset.uri) }
    }

    fun cleanup() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.running || snapshot.selected.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var awaitingConsent = false
            try {
                val ordered = snapshot.groups.map { group ->
                    group.copy(files = group.files.sortedBy { if (it.asset.uri == snapshot.protected[group.id]) 0 else 1 })
                }
                val plan = KeepPolicy.plan(ordered, snapshot.selected)
                require(plan.none { it.target.asset.source == Source.DOCUMENT } || snapshot.preferences.permanentDelete)
                val preflight = Preflight(storage)
                // A failed group blocks the whole batch before any requests or removals happen.
                plan.forEach { preflight.verify(it) }
                val batch = UUID.randomUUID().toString()
                val media = plan.filter { it.target.asset.source == Source.MEDIA }
                val documents = plan.filter { it.target.asset.source == Source.DOCUMENT }
                for (removal in documents) {
                    val attempt = attempt(batch, removal.target.asset, "DELETE")
                    try {
                        preflight.verify(removal)
                        dao.attempts(listOf(attempt))
                        val accepted = storage.deleteDocument(removal.target.asset)
                        val exists = storage.documentExists(removal.target.asset.uri)
                        finish(attempt, if (accepted && exists == false) "DELETED" else "UNVERIFIED",
                            if (accepted && exists == false) "Provider confirmed deletion and the document is absent. Reclaimed space is unmeasured."
                            else "Document removal could not be verified. Check the provider before retrying.")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { finish(attempt, "SKIPPED", "Access, file identity or provider operation changed. Review and scan again.") }
                }
                if (media.isNotEmpty()) {
                    // Recheck after document operations and immediately before requesting Android consent.
                    media.forEach { preflight.verify(it) }
                    val request = MediaStore.createTrashRequest(app.contentResolver, media.map { Uri.parse(it.target.asset.uri) }, true)
                    dao.attempts(media.map { attempt(batch, it.target.asset, "TRASH") })
                    consentChannel.send(IntentSenderRequest.Builder(request.intentSender).build())
                    awaitingConsent = true
                } else {
                    invalidateFindings()
                    _state.update { it.copy(page = Page.HISTORY, message = "Document cleanup finished. Review each outcome below.") }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message(error.message?.takeIf { it.length < 180 } ?: "Cleanup was blocked. Scan again and review your copies.") }
            finally {
                if (!awaitingConsent) _state.update { it.copy(busy = false) }
                dao.trimAttempts()
            }
        }
    }

    fun consentResult(allowed: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        try {
            reconcilePending(allowed)
            invalidateFindings()
            _state.update { it.copy(page = Page.HISTORY, message = if (allowed) "Android request finished. Verified outcomes are listed below."
                else "Request cancelled or denied. No removal is assumed.") }
        } catch (_: Exception) { message("Could not reconcile cleanup. Reopen the app to check pending results.") }
        finally { _state.update { it.copy(busy = false) } }
    }

    private suspend fun reconcilePending(allowed: Boolean?) {
        for (attempt in dao.pending()) {
            val uri = attempt.uri ?: continue
            if (attempt.action == "TRASH") {
                when (storage.isTrashed(uri)) {
                    true -> finish(attempt, "TRASHED", "Verified in Android trash. Retention is managed by Android/provider. Space recovered is unmeasured.")
                    false -> finish(attempt, if (allowed == false) "SKIPPED" else "FAILED", "File is still present and not trashed.")
                    null -> finish(attempt, "UNVERIFIED", "File status is unavailable. Missing access is not proof of removal.")
                }
            } else {
                // On process death, an absent document alone does not prove our request deleted it.
                finish(attempt, "UNVERIFIED", "App interrupted during document cleanup. Check the provider; removal is not assumed.")
            }
        }
    }

    private fun attempt(batch: String, asset: Asset, action: String) = CleanupAttempt(UUID.randomUUID().toString(), batch,
        asset.uri, action, asset.size ?: 0, System.currentTimeMillis())
    private suspend fun finish(attempt: CleanupAttempt, status: String, detail: String) {
        dao.attempts(listOf(attempt.copy(uri = null, completedAt = System.currentTimeMillis(), status = status, detail = detail)))
    }
    private suspend fun invalidateFindings() {
        db.withTransaction { dao.clearFiles(); dao.clearGroups() }
        _state.update { it.copy(groups = emptyList(), selected = emptySet(), protected = emptyMap(), restored = false, scanFinished = false) }
    }

    fun purge() {
        if (_state.value.running || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.withTransaction { dao.clearFiles(); dao.clearGroups(); dao.clearAttempts(); dao.clearSessions() }
                app.contentResolver.persistedUriPermissions.forEach { permission ->
                    try { app.contentResolver.releasePersistableUriPermission(permission.uri,
                        (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) } catch (_: Exception) { }
                }
                preferences.clear()
                _state.update { AppState(ready = true, coverage = storage.coverage(), device = deviceStorage()) }
            } catch (_: Exception) { _state.update { it.copy(busy = false, message = "Local reset could not finish. Try again.") } }
        }
    }

    private fun defaults(groups: List<DuplicateGroup>) = groups.associate { group ->
        group.id to (group.independent.firstOrNull()?.asset?.uri ?: group.files.first().asset.uri)
    }
    private fun deviceStorage(): DeviceStorage? = try {
        StatFs(app.filesDir.absolutePath).let { DeviceStorage(it.totalBytes, it.availableBytes) }
    } catch (_: Exception) { null }
}
