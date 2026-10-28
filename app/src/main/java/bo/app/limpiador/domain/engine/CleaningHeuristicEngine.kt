package bo.app.limpiador.domain.engine

import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.domain.model.CleaningSuggestion
import bo.app.limpiador.domain.model.RiskLevel
import bo.app.limpiador.domain.model.SuggestedFile
import bo.app.limpiador.domain.model.SuggestionCategory
import bo.app.limpiador.domain.usecase.DuplicateGroup
import java.util.concurrent.TimeUnit

/** Estado de un APK respecto a lo instalado (lo resuelve el caso de uso con PackageManager). */
enum class ApkStatus { INSTALLED_SAME_OR_NEWER, INSTALLED_OLDER, NOT_INSTALLED, UNREADABLE }

/**
 * Puntúa cada archivo según MIME, ruta, extensión, tamaño y antigüedad. Fotos y videos están en una
 * lista blanca: [isProtected] los excluye antes de cualquier evaluación, nunca reciben puntuación.
 */
class CleaningHeuristicEngine(private val now: Long = System.currentTimeMillis()) {

    companion object {
        const val LARGE_FILE_BYTES = 100L * 1024 * 1024
        const val OBSOLETE_DOWNLOAD_DAYS = 90
        const val LARGE_FILE_IDLE_DAYS = 30
        /** Puntuación mínima para que un archivo quede marcado en "Limpieza Segura". */
        const val AUTO_SELECT_SCORE = 60

        private val visualExt = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "cr2", "nef", "arw", "tif", "tiff",
            "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v", "mts", "wmv", "flv",
        )
        private val protectedDirs = listOf("/dcim/", "/pictures/", "/movies/", "/camera/", "/screenshots/", "/screen recordings/")
        private val tempExt = setOf("tmp", "temp", "log", "bak", "cache", "old", "dmp", "chk", "part", "crdownload", "partial")
        private val inProgressExt = setOf("part", "crdownload", "partial")
        private val duplicateMimePrefixes = listOf("audio/", "text/", "application/")
    }

    /** Lista blanca estricta: cualquier foto o video, o cualquier cosa dentro de carpetas de galería. */
    fun isProtected(f: FileItem): Boolean {
        val mime = f.mimeType.orEmpty()
        if (mime.startsWith("image/") || mime.startsWith("video/")) return true
        if (f.name.substringAfterLast('.', "").lowercase() in visualExt) return true
        val p = "/" + f.path.lowercase().trimStart('/')
        return protectedDirs.any { p.contains(it) }
    }

    private fun ext(f: FileItem) = f.name.substringAfterLast('.', "").lowercase()
    private fun isInDownloads(f: FileItem) = f.path.lowercase().let { it.contains("/download/") || it.startsWith("download/") }
    private fun daysIdle(f: FileItem) = TimeUnit.MILLISECONDS.toDays((now - f.lastUsed).coerceAtLeast(0))
    private fun daysSinceModified(f: FileItem) = TimeUnit.MILLISECONDS.toDays((now - f.lastModified).coerceAtLeast(0))

    private fun isTemp(f: FileItem): Boolean {
        val e = ext(f)
        val lp = f.path.lowercase()
        return e in tempExt || f.name.endsWith("~") || lp.contains("/cache/") || lp.contains("/.trash") || lp.contains("/temp/")
    }

    /** Clasifica un archivo (sin duplicados, que necesitan hash). null = no sugerir. */
    fun classify(f: FileItem, apkStatus: (FileItem) -> ApkStatus = { ApkStatus.UNREADABLE }): Pair<SuggestionCategory, SuggestedFile>? {
        if (isProtected(f) || f.size <= 0) return null
        val e = ext(f)
        val idle = daysIdle(f)

        if (isTemp(f)) {
            // Una descarga a medias de hoy puede seguir en curso.
            if (e in inProgressExt && daysSinceModified(f) < 1) return null
            var score = 80
            if (idle > 7) score += 10
            if (e == "log" || e == "tmp" || e == "cache") score += 5
            val reason = when (e) {
                "log" -> "Registro de actividad"
                "bak", "old" -> "Copia de respaldo antigua"
                in inProgressExt -> "Descarga incompleta"
                else -> "Archivo temporal"
            }
            return SuggestionCategory.TEMP_FILE to SuggestedFile(f, score.coerceAtMost(100), reason)
        }

        if (e == "apk" || e == "xapk" || e == "apks" || f.mimeType == "application/vnd.android.package-archive") {
            val status = if (e == "apk") apkStatus(f) else ApkStatus.UNREADABLE
            var score = 55
            var reason = "Instalador"
            when (status) {
                ApkStatus.INSTALLED_SAME_OR_NEWER -> { score += 35; reason = "Ya instalada (misma versión o más nueva)" }
                ApkStatus.INSTALLED_OLDER -> { score += 5; reason = "Actualización pendiente de instalar" }
                ApkStatus.NOT_INSTALLED -> reason = "App no instalada"
                ApkStatus.UNREADABLE -> Unit
            }
            if (isInDownloads(f)) score += 5
            if (idle > 30) score += 10
            if (idle > 180) score += 5
            return SuggestionCategory.APK to SuggestedFile(f, score.coerceAtMost(100), "$reason · $idle días sin uso")
        }

        if (isInDownloads(f) && daysSinceModified(f) > OBSOLETE_DOWNLOAD_DAYS && idle > OBSOLETE_DOWNLOAD_DAYS) {
            var score = 45 + ((idle - OBSOLETE_DOWNLOAD_DAYS) / 30).toInt().coerceAtMost(25)
            if (f.size > 50L * 1024 * 1024) score += 10
            if (f.mimeType?.startsWith("audio/") == true) score -= 10
            return SuggestionCategory.OBSOLETE_DOWNLOAD to
                SuggestedFile(f, score.coerceIn(0, 90), "Descargado hace $idle días sin uso")
        }

        if (f.size >= LARGE_FILE_BYTES && idle > LARGE_FILE_IDLE_DAYS) {
            var score = 40 + (f.size / LARGE_FILE_BYTES).toInt().coerceAtMost(20)
            if (idle > 180) score += 15
            if (e in setOf("zip", "rar", "7z", "iso", "img", "bin")) score += 10
            return SuggestionCategory.LARGE_FILE to SuggestedFile(f, score.coerceIn(0, 90), "Archivo grande sin uso en $idle días")
        }
        return null
    }

    /**
     * Copias exactas: se conserva una por grupo (la que no está en Download y, entre esas, la más antigua);
     * las demás se sugieren. Grupos con fotos o videos se descartan completos.
     */
    fun duplicates(groups: List<DuplicateGroup>): List<SuggestedFile> = groups
        .filter { g -> g.files.none(::isProtected) }
        .filter { g -> g.files.any { f -> duplicateMimePrefixes.any { f.mimeType.orEmpty().startsWith(it) } || f.mimeType == null } }
        .flatMap { g ->
            val keep = g.files.sortedWith(compareBy({ isInDownloads(it) }, { it.lastModified })).first()
            g.files.filter { it.uri != keep.uri }.map { f ->
                var score = 75
                if (isInDownloads(f)) score += 10
                if (f.name.contains(Regex("""\(\d+\)|-\d+\.|copia|copy""", RegexOption.IGNORE_CASE))) score += 10
                SuggestedFile(f, score.coerceAtMost(100), "Copia de ${keep.name}")
            }
        }

    /** Consolida todo en tarjetas. Un archivo aparece en una sola, la de mayor prioridad. */
    fun build(
        files: List<FileItem>,
        duplicateGroups: List<DuplicateGroup>,
        appCacheBytes: Long?,
        apkStatus: (FileItem) -> ApkStatus,
    ): List<CleaningSuggestion> {
        val byCategory = LinkedHashMap<SuggestionCategory, MutableList<SuggestedFile>>()
        val taken = HashSet<String>()

        for (f in files) {
            val (cat, sf) = classify(f, apkStatus) ?: continue
            if (cat == SuggestionCategory.TEMP_FILE || cat == SuggestionCategory.APK) {
                byCategory.getOrPut(cat) { ArrayList() } += sf
                taken += f.uri
            }
        }
        for (sf in duplicates(duplicateGroups)) {
            if (taken.add(sf.item.uri)) byCategory.getOrPut(SuggestionCategory.DUPLICATE_DOC) { ArrayList() } += sf
        }
        for (f in files) {
            if (f.uri in taken) continue
            val (cat, sf) = classify(f, apkStatus) ?: continue
            if (cat == SuggestionCategory.OBSOLETE_DOWNLOAD || cat == SuggestionCategory.LARGE_FILE) {
                byCategory.getOrPut(cat) { ArrayList() } += sf
                taken += f.uri
            }
        }

        val result = SuggestionCategory.entries.mapNotNull { cat ->
            if (cat == SuggestionCategory.APP_CACHE) {
                return@mapNotNull appCacheBytes?.takeIf { it > 0 }?.let {
                    CleaningSuggestion(cat, RiskLevel.SAFE, emptyList(), systemReclaimableBytes = it)
                }
            }
            val list = byCategory[cat]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CleaningSuggestion(
                category = cat,
                risk = riskFor(cat),
                files = list.sortedWith(compareByDescending<SuggestedFile> { it.score }.thenByDescending { it.item.size }),
            )
        }
        return result
    }

    fun riskFor(cat: SuggestionCategory): RiskLevel = when (cat) {
        SuggestionCategory.TEMP_FILE, SuggestionCategory.APK, SuggestionCategory.DUPLICATE_DOC, SuggestionCategory.APP_CACHE -> RiskLevel.SAFE
        SuggestionCategory.OBSOLETE_DOWNLOAD, SuggestionCategory.LARGE_FILE -> RiskLevel.REVIEW
    }
}
