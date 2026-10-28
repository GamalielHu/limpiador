package bo.app.limpiador.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.data.scanner.FileHasher
import bo.app.limpiador.data.scanner.StorageStatsScanner
import bo.app.limpiador.domain.engine.ApkStatus
import bo.app.limpiador.domain.engine.CleaningHeuristicEngine
import bo.app.limpiador.domain.model.CleaningSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

sealed interface SmartScanEvent {
    data class Progress(
        val phase: String,
        val currentDirectory: String,
        val scannedItemsCount: Int,
        val totalItems: Int,
        val reclaimableBytes: Long,
    ) : SmartScanEvent

    data class Done(val suggestions: List<CleaningSuggestion>) : SmartScanEvent
}

/**
 * Escaneo selectivo (sin galería) -> copias exactas (hash head+tail, SHA-256 completo en colisiones)
 * -> caché de apps -> tarjetas puntuadas por [CleaningHeuristicEngine].
 */
class GetSmartCleanSuggestionsUseCase(
    private val context: Context,
    private val dao: FileDao,
    private val scanner: StorageStatsScanner = StorageStatsScanner(context, dao),
    private val findDuplicates: FindDuplicatesUseCase = FindDuplicatesUseCase(dao, FileHasher(context.contentResolver)),
) {
    private val pm: PackageManager = context.packageManager
    private val apkCache = HashMap<String, ApkStatus>()

    operator fun invoke(): Flow<SmartScanEvent> = channelFlow {
        val engine = CleaningHeuristicEngine()
        val status: (FileItem) -> ApkStatus = { apkStatus(it) }

        // 1. Índice de lo no visual, con estimación en vivo.
        var estimated = 0L
        scanner.scanNonMedia { item -> engine.classify(item, status)?.second?.item?.size ?: 0L }.collect { p ->
            estimated = p.reclaimableBytes
            send(SmartScanEvent.Progress("Buscando archivos prescindibles", p.currentDirectory, p.scannedItemsCount, p.totalItems, p.reclaimableBytes))
        }

        // 2. Copias exactas: nunca se hashean fotos ni videos.
        send(SmartScanEvent.Progress("Comparando copias exactas", "", 0, 0, estimated))
        val groups = findDuplicates(include = { !engine.isProtected(it) }) { done, total ->
            trySend(SmartScanEvent.Progress("Comparando copias exactas", "", done, total, estimated))
        }

        // 3. Caché de apps.
        send(SmartScanEvent.Progress("Midiendo caché de aplicaciones", "", 0, 0, estimated))
        val cache = scanner.appCacheBytes()

        // 4. Consolidación.
        val suggestions = engine.build(dao.nonVisualFiles(), groups, cache, status)
        send(SmartScanEvent.Done(suggestions))
    }.flowOn(Dispatchers.IO)

    /** Compara el APK con lo instalado: "huérfano" si esa versión (o una más nueva) ya está instalada. */
    private fun apkStatus(f: FileItem): ApkStatus = apkCache.getOrPut(f.uri + f.lastModified) {
        if (f.path.isEmpty()) return@getOrPut ApkStatus.UNREADABLE
        @Suppress("DEPRECATION")
        val archive = runCatching { pm.getPackageArchiveInfo(f.path, 0) }.getOrNull()
            ?: return@getOrPut ApkStatus.UNREADABLE
        @Suppress("DEPRECATION")
        val installed = runCatching { pm.getPackageInfo(archive.packageName, 0) }.getOrNull()
            ?: return@getOrPut ApkStatus.NOT_INSTALLED
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
        else @Suppress("DEPRECATION") archive.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode
        else @Suppress("DEPRECATION") installed.versionCode.toLong()
        if (installedVersion >= archiveVersion) ApkStatus.INSTALLED_SAME_OR_NEWER else ApkStatus.INSTALLED_OLDER
    }
}
