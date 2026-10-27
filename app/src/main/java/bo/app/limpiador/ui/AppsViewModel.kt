package bo.app.limpiador.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bo.app.limpiador.data.apps.AppInfo
import bo.app.limpiador.data.apps.InstalledAppsReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class AppSort(val label: String) { TOTAL("Tamaño total"), APP("App"), DATA("Datos"), CACHE("Caché"), LAST_USED("Menos usadas") }
enum class AppKind(val label: String) { USER("Instaladas"), SYSTEM("Sistema"), ALL("Todas") }

data class AppsFilter(
    val kind: AppKind = AppKind.USER,
    val sort: AppSort = AppSort.TOTAL,
    /** 0 = sin filtro; si no, solo apps sin abrir en esos días. */
    val unusedDays: Int = 0,
    val query: String = "",
)

data class AppsSummary(val count: Int, val app: Long, val data: Long, val cache: Long) {
    val total: Long get() = app + data + cache
}

sealed interface AppsLoad {
    data object Idle : AppsLoad
    data class Loading(val processed: Int, val total: Int) : AppsLoad
    data object Loaded : AppsLoad
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {

    private val reader = InstalledAppsReader(app)

    private val _all = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _filter = MutableStateFlow(AppsFilter())
    val filter: StateFlow<AppsFilter> = _filter.asStateFlow()

    private val _load = MutableStateFlow<AppsLoad>(AppsLoad.Idle)
    val load: StateFlow<AppsLoad> = _load.asStateFlow()

    private val _usageAccess = MutableStateFlow(reader.hasUsageAccess())
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    val apps: StateFlow<List<AppInfo>> = combine(_all, _filter) { all, f -> applyFilter(all, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resumen de las apps instaladas por el usuario (para el panel principal). */
    val summary: StateFlow<AppsSummary?> = combine(_all, _load) { all, l ->
        if (l != AppsLoad.Loaded) null
        else all.filter { !it.isSystem }.let { u ->
            AppsSummary(u.size, u.sumOf { it.appBytes }, u.sumOf { it.dataBytes ?: 0 }, u.sumOf { it.cacheBytes ?: 0 })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    init {
        refresh()
    }

    /** Se llama al volver a la app: el permiso o la lista de apps pudo cambiar. */
    fun onResume() {
        val had = _usageAccess.value
        _usageAccess.value = reader.hasUsageAccess()
        if (had != _usageAccess.value || _load.value == AppsLoad.Loaded) refresh(quiet = true)
    }

    fun refresh(quiet: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!quiet || _all.value.isEmpty()) _load.value = AppsLoad.Loading(0, 0)
            val list = reader.load { done, total ->
                if (!quiet || _all.value.isEmpty()) _load.value = AppsLoad.Loading(done, total)
            }
            _all.value = list
            val present = list.map { it.packageName }.toSet()
            _selection.update { it intersect present }
            _load.value = AppsLoad.Loaded
        }
    }

    fun setFilter(f: AppsFilter) {
        _filter.value = f
    }

    fun toggle(pkg: String) {
        _selection.update { if (pkg in it) it - pkg else it + pkg }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun applyFilter(all: List<AppInfo>, f: AppsFilter): List<AppInfo> {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(f.unusedDays.toLong())
        val q = f.query.trim().lowercase()
        val filtered = all.filter { a ->
            when (f.kind) {
                AppKind.USER -> !a.isSystem
                AppKind.SYSTEM -> a.isSystem
                AppKind.ALL -> true
            } && (f.unusedDays == 0 || (a.lastUsed ?: 0L) < cutoff) &&
                (q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.contains(q))
        }
        return when (f.sort) {
            AppSort.TOTAL -> filtered.sortedByDescending { it.totalBytes }
            AppSort.APP -> filtered.sortedByDescending { it.appBytes }
            AppSort.DATA -> filtered.sortedByDescending { it.dataBytes ?: 0 }
            AppSort.CACHE -> filtered.sortedByDescending { it.cacheBytes ?: 0 }
            AppSort.LAST_USED -> filtered.sortedWith(compareBy<AppInfo> { it.lastUsed ?: 0L }.thenByDescending { it.totalBytes })
        }
    }
}
