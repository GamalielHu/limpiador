package bo.app.limpiador.ui.state

import bo.app.limpiador.domain.engine.CleaningHeuristicEngine
import bo.app.limpiador.domain.model.CleaningSuggestion
import bo.app.limpiador.domain.model.RiskLevel
import bo.app.limpiador.domain.model.SuggestionCategory

sealed interface SmartScannerStatus {
    data object Idle : SmartScannerStatus
    data class Running(
        val phase: String,
        val currentDirectory: String,
        val scannedItemsCount: Int,
        val totalItems: Int,
        val reclaimableBytes: Long,
    ) : SmartScannerStatus {
        val fraction: Float? get() = if (totalItems > 0) (scannedItemsCount.toFloat() / totalItems).coerceIn(0f, 1f) else null
    }
    data class Finished(val at: Long) : SmartScannerStatus
}

data class CategoryUiState(
    val suggestion: CleaningSuggestion,
    /** Toggle de la tarjeta: si está apagado, nada de esta categoría se borra. */
    val enabled: Boolean,
    val expanded: Boolean = false,
    val selected: Set<String>,
) {
    val category: SuggestionCategory get() = suggestion.category
    val isSystemAction: Boolean get() = category == SuggestionCategory.APP_CACHE

    val selectedBytes: Long
        get() = if (isSystemAction) suggestion.systemReclaimableBytes
        else suggestion.files.filter { it.item.uri in selected }.sumOf { it.item.size }

    val selectedCount: Int get() = if (isSystemAction) 0 else selected.size

    companion object {
        /** Seguras: encendidas y con los archivos de puntuación alta ya marcados. Revisar: apagadas. */
        fun initial(s: CleaningSuggestion): CategoryUiState {
            val safe = s.risk == RiskLevel.SAFE
            return CategoryUiState(
                suggestion = s,
                enabled = safe,
                selected = if (safe) s.files.filter { it.score >= CleaningHeuristicEngine.AUTO_SELECT_SCORE }
                    .map { it.item.uri }.toSet() else emptySet(),
            )
        }
    }
}

data class SmartCleanUiState(
    val scanner: SmartScannerStatus = SmartScannerStatus.Idle,
    val categories: List<CategoryUiState> = emptyList(),
    val deleting: Boolean = false,
    val needsDeepAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val freedThisSession: Long = 0,
) {
    /** "Espacio seguro para liberar hoy": lo marcado en las tarjetas seguras encendidas. */
    val safeReclaimableBytes: Long
        get() = categories.filter { it.enabled && it.suggestion.risk == RiskLevel.SAFE }.sumOf { it.selectedBytes }

    /** Todo lo que podría liberarse si se revisa también lo marcado como "Revisar". */
    val totalReclaimableBytes: Long get() = categories.sumOf { it.suggestion.reclaimableBytes }

    val scanning: Boolean get() = scanner is SmartScannerStatus.Running
}
