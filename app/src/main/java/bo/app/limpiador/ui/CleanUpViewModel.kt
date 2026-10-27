package bo.app.limpiador.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bo.app.limpiador.data.db.AppDatabase
import bo.app.limpiador.data.model.CategoryUsage
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.data.scanner.FileHasher
import bo.app.limpiador.data.scanner.StorageScanner
import bo.app.limpiador.data.work.IndexWorker
import bo.app.limpiador.domain.usecase.DeleteFilesUseCase
import bo.app.limpiador.domain.usecase.DeleteOutcome
import bo.app.limpiador.domain.usecase.DuplicateGroup
import bo.app.limpiador.domain.usecase.FindDuplicatesUseCase
import bo.app.limpiador.domain.usecase.FindStaleFilesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val phase: String, val processed: Int, val total: Int, val detail: String = "") : ScanState {
        /** null = indeterminado. */
        val fraction: Float? get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Ready(val indexed: Int, val finishedAt: Long) : ScanState
    data class Deleting(val count: Int) : ScanState
}

data class StorageStats(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

/** Filtro de la pantalla de inactivos: días = 0 muestra todo (lista de los más grandes). */
data class StaleFilter(
    val days: Int = 90,
    val categories: Set<FileCategory> = emptySet(),
    val residual: Boolean = false,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Consent(val intentSender: IntentSender) : UiEvent
}

class CleanUpViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).files()
    private val scanner = StorageScanner(app, dao)
    private val findDuplicates = FindDuplicatesUseCase(dao, FileHasher(app.contentResolver))
    private val findStale = FindStaleFilesUseCase(dao)
    private val deleteFiles = DeleteFilesUseCase(app.contentResolver, dao)

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _storage = MutableStateFlow(readStorage())
    val storage: StateFlow<StorageStats> = _storage.asStateFlow()

    val categoryUsage: StateFlow<List<CategoryUsage>> =
        dao.categoryUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _duplicates = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicates: StateFlow<List<DuplicateGroup>> = _duplicates.asStateFlow()

    private val _duplicateSelection = MutableStateFlow<Set<String>>(emptySet())
    val duplicateSelection: StateFlow<Set<String>> = _duplicateSelection.asStateFlow()

    private val _staleFilter = MutableStateFlow(StaleFilter())
    val staleFilter: StateFlow<StaleFilter> = _staleFilter.asStateFlow()

    private val _staleFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val staleFiles: StateFlow<List<FileItem>> = _staleFiles.asStateFlow()

    private val _staleSelection = MutableStateFlow<Set<String>>(emptySet())
    val staleSelection: StateFlow<Set<String>> = _staleSelection.asStateFlow()

    private val _staleLoading = MutableStateFlow(false)
    val staleLoading: StateFlow<Boolean> = _staleLoading.asStateFlow()

    private val _allFilesAccess = MutableStateFlow(scanner.hasAllFilesAccess())
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    private var scanJob: Job? = null
    private var staleJob: Job? = null
    private var pendingConsent: DeleteOutcome.NeedsConsent? = null
    private var deletedInRun = ArrayList<FileItem>()
    private var failedInRun = 0

    init {
        IndexWorker.schedule(app)
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { dao.count() }
            if (n > 0) {
                _scanState.value = ScanState.Ready(n, 0L)
                loadStale()
            }
        }
    }

    fun refreshEnvironment() {
        _allFilesAccess.value = scanner.hasAllFilesAccess()
        _storage.value = readStorage()
    }

    /** Escaneo completo: índice MediaStore -> duplicados -> inactivos. Nunca en el hilo principal. */
    fun scanAll() = launchScan { runIndex(scanner.scanMediaStore(), "Indexando archivos") }

    /** Análisis de una carpeta elegida con SAF (no requiere permisos de almacenamiento). */
    fun scanTree(treeUri: Uri) = launchScan { runIndex(scanner.scanTree(treeUri), "Recorriendo carpeta") }

    private fun launchScan(index: suspend () -> Unit) {
        if (scanJob?.isActive == true || _scanState.value is ScanState.Deleting) return
        scanJob = viewModelScope.launch {
            try {
                index()
                _scanState.value = ScanState.Scanning("Buscando duplicados", 0, 0)
                val groups = findDuplicates { done, total ->
                    _scanState.value = ScanState.Scanning("Buscando duplicados", done, total)
                }
                _duplicates.value = groups
                _duplicateSelection.value = emptySet()
                loadStale().join()
                _storage.value = readStorage()
                _scanState.value = ScanState.Ready(withContext(Dispatchers.IO) { dao.count() }, System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _scanState.value = ScanState.Idle
                throw e
            } catch (e: Exception) {
                _scanState.value = ScanState.Idle
                events.send(UiEvent.Message("Error al analizar: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private suspend fun runIndex(flow: kotlinx.coroutines.flow.Flow<StorageScanner.Progress>, phase: String) {
        flow.collect { p -> _scanState.value = ScanState.Scanning(phase, p.processed, p.total, p.current) }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    // ---------- Duplicados ----------

    /** Marca todas las copias menos la más antigua de cada grupo. */
    fun selectKeepOldest() {
        _duplicateSelection.value = _duplicates.value
            .flatMap { g -> g.files.drop(1).map { it.uri } }
            .toSet()
    }

    fun clearDuplicateSelection() {
        _duplicateSelection.value = emptySet()
    }

    fun toggleDuplicate(group: DuplicateGroup, file: FileItem) {
        val current = _duplicateSelection.value
        if (file.uri in current) {
            _duplicateSelection.value = current - file.uri
            return
        }
        val stillKept = group.files.count { it.uri !in current && it.uri != file.uri }
        if (stillKept == 0) {
            events.trySend(UiEvent.Message("Debe quedar al menos una copia de cada archivo"))
            return
        }
        _duplicateSelection.value = current + file.uri
    }

    fun deleteSelectedDuplicates() {
        val sel = _duplicateSelection.value
        startDelete(_duplicates.value.flatMap { g -> g.files.filter { it.uri in sel } })
    }

    // ---------- Inactivos / residuales ----------

    fun setStaleFilter(filter: StaleFilter) {
        if (filter == _staleFilter.value) return
        _staleFilter.value = filter
        _staleSelection.value = emptySet()
        loadStale()
    }

    fun openCategory(category: FileCategory) {
        setStaleFilter(StaleFilter(days = 0, categories = setOf(category)))
    }

    private fun loadStale(): Job {
        staleJob?.cancel()
        return viewModelScope.launch {
            _staleLoading.value = true
            val f = _staleFilter.value
            _staleFiles.value = if (f.residual) findStale.residual() else findStale(f.days, f.categories)
            _staleLoading.value = false
        }.also { staleJob = it }
    }

    fun toggleStale(file: FileItem) {
        _staleSelection.update { if (file.uri in it) it - file.uri else it + file.uri }
    }

    fun selectAllStale(all: Boolean) {
        _staleSelection.value = if (all) _staleFiles.value.map { it.uri }.toSet() else emptySet()
    }

    fun deleteSelectedStale() {
        val sel = _staleSelection.value
        startDelete(_staleFiles.value.filter { it.uri in sel })
    }

    // ---------- Eliminación ----------

    private fun startDelete(items: List<FileItem>) {
        if (items.isEmpty() || _scanState.value is ScanState.Deleting) return
        deletedInRun = ArrayList()
        failedInRun = 0
        viewModelScope.launch {
            _scanState.value = ScanState.Deleting(items.size)
            handleOutcome(deleteFiles(items))
        }
    }

    /** Resultado del diálogo del sistema (createDeleteRequest o RecoverableSecurityException). */
    fun onConsentResult(granted: Boolean) {
        val pending = pendingConsent ?: return
        pendingConsent = null
        viewModelScope.launch {
            val done = deleteFiles.afterConsent(pending.pending, granted)
            deletedInRun += done.deleted
            failedInRun += done.failed.size
            if (granted && pending.remaining.isNotEmpty()) {
                handleOutcome(deleteFiles(pending.remaining))
            } else {
                failedInRun += if (granted) 0 else pending.remaining.size
                finishDelete()
            }
        }
    }

    private suspend fun handleOutcome(outcome: DeleteOutcome) {
        when (outcome) {
            is DeleteOutcome.Done -> {
                deletedInRun += outcome.deleted
                failedInRun += outcome.failed.size
                finishDelete()
            }
            is DeleteOutcome.NeedsConsent -> {
                deletedInRun += outcome.deletedSoFar
                pendingConsent = outcome
                events.send(UiEvent.Consent(outcome.intentSender))
            }
        }
    }

    private suspend fun finishDelete() {
        val gone = deletedInRun.map { it.uri }.toSet()
        _duplicates.update { groups ->
            groups.map { g -> g.copy(files = g.files.filterNot { it.uri in gone }) }.filter { it.files.size > 1 }
        }
        _duplicateSelection.update { it - gone }
        _staleFiles.update { list -> list.filterNot { it.uri in gone } }
        _staleSelection.update { it - gone }
        _storage.value = readStorage()
        _scanState.value = ScanState.Ready(withContext(Dispatchers.IO) { dao.count() }, System.currentTimeMillis())
        val freed = deletedInRun.sumOf { it.size }
        val msg = buildString {
            append("Eliminados ${deletedInRun.size} archivos (${formatBytes(freed)})")
            if (failedInRun > 0) append(" · $failedInRun no se pudieron eliminar")
        }
        events.send(UiEvent.Message(msg))
    }

    @Suppress("DEPRECATION")
    private fun readStorage(): StorageStats = runCatching {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        StorageStats(st.totalBytes, st.availableBytes)
    }.getOrDefault(StorageStats(0, 0))
}
