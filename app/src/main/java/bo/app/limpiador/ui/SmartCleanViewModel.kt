package bo.app.limpiador.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bo.app.limpiador.data.apps.InstalledAppsReader
import bo.app.limpiador.data.db.AppDatabase
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.data.scanner.StorageStatsScanner
import bo.app.limpiador.domain.model.RiskLevel
import bo.app.limpiador.domain.model.SuggestionCategory
import bo.app.limpiador.domain.usecase.DeleteFilesUseCase
import bo.app.limpiador.domain.usecase.DeleteOutcome
import bo.app.limpiador.domain.usecase.GetSmartCleanSuggestionsUseCase
import bo.app.limpiador.domain.usecase.SmartScanEvent
import bo.app.limpiador.ui.state.CategoryUiState
import bo.app.limpiador.ui.state.SmartCleanUiState
import bo.app.limpiador.ui.state.SmartScannerStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SmartEvent {
    data class Message(val text: String) : SmartEvent
    data class Consent(val intentSender: IntentSender) : SmartEvent
    /** Lanzar StorageManager.ACTION_CLEAR_APP_CACHE (requiere acceso a todos los archivos). */
    data object ClearAppCache : SmartEvent
    data object RequestDeepAccess : SmartEvent
}

class SmartCleanViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).files()
    private val statsScanner = StorageStatsScanner(app, dao)
    private val getSuggestions = GetSmartCleanSuggestionsUseCase(app, dao, statsScanner)
    private val deleteFiles = DeleteFilesUseCase(app.contentResolver, dao)
    private val apps = InstalledAppsReader(app)

    private val _state = MutableStateFlow(SmartCleanUiState())
    val state: StateFlow<SmartCleanUiState> = _state.asStateFlow()

    private val events = Channel<SmartEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    private var scanJob: Job? = null
    private var pendingConsent: DeleteOutcome.NeedsConsent? = null
    private var pendingCache = false
    private val deletedInRun = ArrayList<FileItem>()
    private var failedInRun = 0

    init {
        refreshEnvironment()
    }

    private fun allFilesAccess() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun refreshEnvironment() {
        val v = statsScanner.volumeStats()
        _state.update {
            it.copy(
                needsDeepAccess = !allFilesAccess(),
                usageAccess = apps.hasUsageAccess(),
                totalBytes = v.totalBytes,
                freeBytes = v.freeBytes,
            )
        }
    }

    fun scan() {
        if (scanJob?.isActive == true || _state.value.deleting) return
        scanJob = viewModelScope.launch {
            try {
                getSuggestions().collect { e ->
                    when (e) {
                        is SmartScanEvent.Progress -> _state.update {
                            it.copy(
                                scanner = SmartScannerStatus.Running(
                                    e.phase, e.currentDirectory, e.scannedItemsCount, e.totalItems, e.reclaimableBytes,
                                ),
                            )
                        }
                        is SmartScanEvent.Done -> _state.update {
                            it.copy(
                                scanner = SmartScannerStatus.Finished(System.currentTimeMillis()),
                                categories = e.suggestions.filterNot { s -> s.isEmpty }.map(CategoryUiState::initial),
                            )
                        }
                    }
                }
                refreshEnvironment()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(scanner = SmartScannerStatus.Idle) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanner = SmartScannerStatus.Idle) }
                events.send(SmartEvent.Message("Error al analizar: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    private fun updateCategory(cat: SuggestionCategory, f: (CategoryUiState) -> CategoryUiState) {
        _state.update { s -> s.copy(categories = s.categories.map { if (it.category == cat) f(it) else it }) }
    }

    fun toggleEnabled(cat: SuggestionCategory) = updateCategory(cat) { c ->
        val on = !c.enabled
        // Al encender una tarjeta de "Revisar" no se marca nada: el usuario elige los archivos.
        c.copy(enabled = on)
    }

    fun toggleExpanded(cat: SuggestionCategory) = updateCategory(cat) { it.copy(expanded = !it.expanded) }

    fun toggleFile(cat: SuggestionCategory, uri: String) = updateCategory(cat) { c ->
        val sel = if (uri in c.selected) c.selected - uri else c.selected + uri
        c.copy(selected = sel, enabled = c.enabled || sel.isNotEmpty())
    }

    fun selectAll(cat: SuggestionCategory, all: Boolean) = updateCategory(cat) { c ->
        c.copy(selected = if (all) c.suggestion.uris.toSet() else emptySet(), enabled = c.enabled || all)
    }

    /** "Limpieza Segura": solo tarjetas de riesgo Seguro, encendidas, con lo marcado. */
    fun safeClean() {
        val cats = _state.value.categories.filter { it.enabled && it.suggestion.risk == RiskLevel.SAFE }
        runClean(cats)
    }

    /** Limpia una sola tarjeta (incluidas las de "Revisar"), con lo que el usuario marcó. */
    fun cleanCategory(cat: SuggestionCategory) {
        runClean(_state.value.categories.filter { it.category == cat })
    }

    private fun runClean(cats: List<CategoryUiState>) {
        if (_state.value.deleting) return
        val wantsCache = cats.any { it.isSystemAction && it.enabled }
        val files = cats.filterNot { it.isSystemAction }
            .flatMap { c -> c.suggestion.files.filter { it.item.uri in c.selected }.map { it.item } }
            .distinctBy { it.uri }
        if (files.isEmpty() && !wantsCache) {
            events.trySend(SmartEvent.Message("No hay archivos marcados"))
            return
        }
        pendingCache = wantsCache
        deletedInRun.clear()
        failedInRun = 0
        if (files.isEmpty()) {
            launchCacheClear()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            handle(deleteFiles(files))
        }
    }

    fun onConsentResult(granted: Boolean) {
        val p = pendingConsent ?: return
        pendingConsent = null
        viewModelScope.launch {
            val done = deleteFiles.afterConsent(p.pending, granted)
            deletedInRun += done.deleted
            failedInRun += done.failed.size
            if (granted && p.remaining.isNotEmpty()) handle(deleteFiles(p.remaining))
            else {
                if (!granted) failedInRun += p.remaining.size
                finish()
            }
        }
    }

    private suspend fun handle(o: DeleteOutcome) {
        when (o) {
            is DeleteOutcome.Done -> {
                deletedInRun += o.deleted
                failedInRun += o.failed.size
                finish()
            }
            is DeleteOutcome.NeedsConsent -> {
                deletedInRun += o.deletedSoFar
                pendingConsent = o
                events.send(SmartEvent.Consent(o.intentSender))
            }
        }
    }

    private suspend fun finish() {
        val gone = deletedInRun.map { it.uri }.toSet()
        val freed = deletedInRun.sumOf { it.size }
        _state.update { s ->
            s.copy(
                deleting = false,
                freedThisSession = s.freedThisSession + freed,
                categories = s.categories.mapNotNull { c ->
                    if (c.isSystemAction) return@mapNotNull c
                    val left = c.suggestion.files.filterNot { it.item.uri in gone }
                    if (left.isEmpty()) null
                    else c.copy(suggestion = c.suggestion.copy(files = left), selected = c.selected - gone)
                },
            )
        }
        refreshEnvironment()
        val msg = buildString {
            append("Liberados ${formatBytes(freed)} · ${deletedInRun.size} archivos")
            if (failedInRun > 0) append(" · $failedInRun sin eliminar")
        }
        events.send(SmartEvent.Message(msg))
        if (pendingCache) launchCacheClear()
    }

    private fun launchCacheClear() {
        pendingCache = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            events.trySend(SmartEvent.ClearAppCache)
        } else {
            events.trySend(SmartEvent.Message("Para vaciar la caché de las apps activa el Análisis profundo"))
            events.trySend(SmartEvent.RequestDeepAccess)
        }
    }

    /** Tras el diálogo del sistema que vacía la caché de todas las apps. */
    fun onAppCacheCleared(ok: Boolean) {
        if (ok) {
            val bytes = _state.value.categories.firstOrNull { it.isSystemAction }?.suggestion?.systemReclaimableBytes ?: 0
            _state.update { s ->
                s.copy(
                    categories = s.categories.filterNot { it.isSystemAction },
                    freedThisSession = s.freedThisSession + bytes,
                )
            }
            events.trySend(SmartEvent.Message("Caché de aplicaciones vaciada"))
        }
        refreshEnvironment()
    }
}
