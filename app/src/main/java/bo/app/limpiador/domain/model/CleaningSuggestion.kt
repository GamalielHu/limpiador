package bo.app.limpiador.domain.model

import bo.app.limpiador.data.model.FileItem

enum class SuggestionCategory(val title: String, val description: String) {
    TEMP_FILE("Temporales y registros", "Archivos .tmp, .log, .bak, .cache y descargas a medias"),
    APK("Instaladores APK", "Instaladores que ya están instalados o llevan tiempo sin usarse"),
    DUPLICATE_DOC("Copias exactas", "Documentos, audios y descargas repetidos; se conserva una copia"),
    OBSOLETE_DOWNLOAD("Descargas antiguas", "Archivos en Download sin modificar en más de 90 días"),
    LARGE_FILE("Archivos gigantes", "Más de 100 MB, no multimedia y sin uso reciente"),
    APP_CACHE("Caché de aplicaciones", "Archivos temporales que las apps vuelven a generar"),
}

enum class RiskLevel(val label: String) {
    /** Se puede borrar sin revisar: regenerable, copia exacta o instalador ya usado. */
    SAFE("Seguro"),
    /** Conviene mirar la lista antes: podría ser algo que el usuario quiere conservar. */
    REVIEW("Revisar"),
}

/** Un archivo sugerido con su puntuación de prescindibilidad (0 = conservar, 100 = basura segura). */
data class SuggestedFile(val item: FileItem, val score: Int, val reason: String)

data class CleaningSuggestion(
    val category: SuggestionCategory,
    val risk: RiskLevel,
    /** Ordenados por puntuación y luego por tamaño, de mayor a menor. */
    val files: List<SuggestedFile>,
    /** Solo para [SuggestionCategory.APP_CACHE]: bytes que el sistema puede liberar (no son archivos propios). */
    val systemReclaimableBytes: Long = 0,
) {
    val reclaimableBytes: Long get() = files.sumOf { it.item.size } + systemReclaimableBytes
    val uris: List<String> get() = files.map { it.item.uri }
    val paths: List<String> get() = files.map { it.item.path }
    val averageScore: Int get() = if (files.isEmpty()) 0 else files.sumOf { it.score } / files.size
    val isEmpty: Boolean get() = files.isEmpty() && systemReclaimableBytes <= 0
}
