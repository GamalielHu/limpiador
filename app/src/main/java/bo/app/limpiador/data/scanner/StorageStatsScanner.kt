package bo.app.limpiador.data.scanner

import android.app.usage.StorageStatsManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import bo.app.limpiador.data.apps.InstalledAppsReader
import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Escaneo selectivo para la limpieza inteligente: consulta MediaStore excluyendo de entrada fotos,
 * videos y carpetas de galería (filtros negativos en SQL), y mide volumen y caché de apps con
 * StorageStatsManager.
 */
class StorageStatsScanner(private val context: Context, private val dao: FileDao) {

    data class Progress(
        val currentDirectory: String,
        val scannedItemsCount: Int,
        val totalItems: Int,
        /** Estimación en vivo de lo recuperable con lo escaneado hasta ahora. */
        val reclaimableBytes: Long,
    )

    data class VolumeStats(val totalBytes: Long, val freeBytes: Long)

    private val resolver: ContentResolver = context.contentResolver
    private val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

    private companion object {
        const val BATCH = 400
        val PROTECTED_RELATIVE = listOf("DCIM/", "Pictures/", "Movies/")

        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
    }

    fun volumeStats(): VolumeStats = runCatching {
        VolumeStats(
            statsManager.getTotalBytes(StorageManager.UUID_DEFAULT),
            statsManager.getFreeBytes(StorageManager.UUID_DEFAULT),
        )
    }.getOrElse {
        @Suppress("DEPRECATION")
        val st = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        VolumeStats(st.totalBytes, st.availableBytes)
    }

    /** Suma la caché de todas las apps de usuario; null si falta "Acceso de uso". */
    suspend fun appCacheBytes(): Long? = withContext(Dispatchers.IO) {
        if (!InstalledAppsReader(context).hasUsageAccess()) return@withContext null
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        val apps = context.packageManager.getInstalledApplications(0)
        var total = 0L
        for (ai in apps) {
            coroutineContext.ensureActive()
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) continue
            total += runCatching {
                statsManager.queryStatsForPackage(ai.storageUuid, ai.packageName, user).cacheBytes
            }.getOrDefault(0L)
        }
        total
    }

    /**
     * Indexa solo archivos no visuales. [estimate] devuelve los bytes recuperables de cada archivo
     * (0 si no se sugiere) para que el progreso muestre el total en tiempo real.
     */
    fun scanNonMedia(estimate: (FileItem) -> Long): Flow<Progress> = flow {
        val stamp = System.currentTimeMillis()
        val (selection, args) = negativeSelection()
        val sort = "${MediaStore.Files.FileColumns.DATA} ASC"
        var reclaimable = 0L

        resolver.query(MediaStore.Files.getContentUri("external"), PROJECTION, selection, args, sort)?.use { c ->
            val total = c.count
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            val batch = ArrayList<FileItem>(BATCH)
            var scanned = 0
            var lastDir = ""
            emit(Progress("", 0, total, 0))
            while (c.moveToNext()) {
                coroutineContext.ensureActive()
                val path = c.getString(iData).orEmpty()
                val mime = c.getString(iMime)
                scanned++
                if (mime == null && (path.isEmpty() || !java.io.File(path).isFile)) continue
                val id = c.getLong(iId)
                val base = if (c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO) {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                } else MediaStore.Files.getContentUri("external")
                val item = FileItem(
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    id = id,
                    path = path,
                    name = c.getString(iName) ?: path.substringAfterLast('/'),
                    size = c.getLong(iSize),
                    mimeType = mime,
                    lastModified = c.getLong(iDate) * 1000L,
                    lastAccessed = null,
                    checksum = null,
                    scanStamp = stamp,
                )
                reclaimable += estimate(item)
                batch += item
                val dir = path.substringBeforeLast('/', "")
                if (batch.size >= BATCH || (dir != lastDir && scanned % 25 == 0)) {
                    if (batch.size >= BATCH) {
                        dao.upsertKeepingHashes(batch)
                        batch.clear()
                    }
                    lastDir = dir
                    emit(Progress(dir, scanned, total, reclaimable))
                }
            }
            dao.upsertKeepingHashes(batch)
            emit(Progress("", scanned, total, reclaimable))
        }
        dao.purgeNonVisualOlderThan(stamp)
    }.flowOn(Dispatchers.IO)

    /** WHERE con filtros negativos: fuera imágenes, videos y carpetas de galería. */
    private fun negativeSelection(): Pair<String, Array<String>> {
        val sb = StringBuilder()
        val args = ArrayList<String>()
        sb.append("${MediaStore.Files.FileColumns.SIZE} > 0")
        sb.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} NOT IN (?, ?)")
        args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
        args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        sb.append(" AND (${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL OR (")
        sb.append("${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'image/%' AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'video/%'))")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (dir in PROTECTED_RELATIVE) {
                val col = MediaStore.Files.FileColumns.RELATIVE_PATH
                sb.append(" AND ($col IS NULL OR $col NOT LIKE ?)")
                args += "$dir%"
            }
        } else {
            for (dir in PROTECTED_RELATIVE) {
                sb.append(" AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE ?")
                args += "%/$dir%"
            }
        }
        return sb.toString() to args.toTypedArray()
    }
}
