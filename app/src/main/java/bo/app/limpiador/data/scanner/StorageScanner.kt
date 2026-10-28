package bo.app.limpiador.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Indexa el almacenamiento compartido en Room. Todo corre en Dispatchers.IO y emite progreso.
 * Los pre-hash/checksum ya calculados se conservan si el archivo no cambió (tamaño y fecha).
 */
class StorageScanner(context: Context, private val dao: FileDao) {

    data class Progress(val processed: Int, val total: Int, val current: String)

    private val resolver: ContentResolver = context.contentResolver

    private companion object {
        const val BATCH = 400
        const val MEDIA_PREFIX = "content://media/"
        /** Solo se consulta el último acceso de archivos que llevan más de esto sin modificarse. */
        val ATIME_MIN_AGE_MS = TimeUnit.DAYS.toMillis(30)

        /** Proyección mínima: solo las columnas que se usan. */
        val MEDIA_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        val DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }

    /** Acceso completo concedido (MANAGE_EXTERNAL_STORAGE o almacenamiento legado). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun scanMediaStore(): Flow<Progress> = flow {
        val stamp = System.currentTimeMillis()
        val deep = hasAllFilesAccess()
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Files.FileColumns.SIZE} > 0"
        val sort = "${MediaStore.Files.FileColumns._ID} ASC"
        val now = System.currentTimeMillis()

        resolver.query(collection, MEDIA_PROJECTION, selection, null, sort)?.use { c ->
            val total = c.count
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            val batch = ArrayList<FileItem>(BATCH)
            var processed = 0
            emit(Progress(0, total, ""))
            while (c.moveToNext()) {
                coroutineContext.ensureActive()
                val id = c.getLong(iId)
                val path = c.getString(iData).orEmpty()
                val mime = c.getString(iMime)
                // Sin MIME pueden ser carpetas: solo se aceptan si se puede comprobar que es archivo.
                if (mime == null && !(deep && path.isNotEmpty() && File(path).isFile)) {
                    processed++
                    continue
                }
                val name = c.getString(iName) ?: path.substringAfterLast('/')
                val modified = c.getLong(iDate) * 1000L
                val uri = ContentUris.withAppendedId(collectionFor(c.getInt(iType)), id)
                batch += FileItem(
                    uri = uri.toString(),
                    id = id,
                    path = path,
                    name = name,
                    size = c.getLong(iSize),
                    mimeType = mime,
                    lastModified = modified,
                    lastAccessed = if (now - modified > ATIME_MIN_AGE_MS) lastAccess(path) else null,
                    checksum = null,
                    scanStamp = stamp,
                )
                processed++
                if (batch.size >= BATCH) {
                    saveKeepingHashes(batch)
                    batch.clear()
                    emit(Progress(processed, total, path))
                }
            }
            saveKeepingHashes(batch)
            emit(Progress(processed, total, ""))
        }
        dao.purgeOlderThan(stamp, MEDIA_PREFIX)
    }.flowOn(Dispatchers.IO)

    /** Recorre un árbol elegido con el Storage Access Framework (sin permisos de almacenamiento). */
    fun scanTree(treeUri: Uri): Flow<Progress> = flow {
        val stamp = System.currentTimeMillis()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<String>().apply { add(rootId) }
        val batch = ArrayList<FileItem>(BATCH)
        var processed = 0
        emit(Progress(0, -1, ""))
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val parentId = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(children, DOC_PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(docId)
                        continue
                    }
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (size <= 0) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val path = pathFromDocId(docId)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    batch += FileItem(
                        uri = docUri.toString(),
                        id = docUri.toString().hashCode().toLong(),
                        path = path,
                        name = name,
                        size = size,
                        mimeType = mime,
                        lastModified = modified,
                        lastAccessed = null,
                        checksum = null,
                        scanStamp = stamp,
                    )
                    processed++
                    if (batch.size >= BATCH) {
                        saveKeepingHashes(batch)
                        batch.clear()
                        emit(Progress(processed, -1, path))
                    }
                }
            }
        }
        saveKeepingHashes(batch)
        emit(Progress(processed, processed, ""))
        dao.purgeOlderThan(stamp, treeUri.toString())
    }.flowOn(Dispatchers.IO)

    private suspend fun saveKeepingHashes(batch: List<FileItem>) = dao.upsertKeepingHashes(batch)

    private fun collectionFor(mediaType: Int): Uri = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    private fun lastAccess(path: String): Long? {
        if (path.isEmpty()) return null
        return runCatching {
            Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java).lastAccessTime().toMillis()
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** "primary:DCIM/a.jpg" -> "/storage/emulated/0/DCIM/a.jpg", para reconocer el mismo archivo por ambas vías. */
    private fun pathFromDocId(docId: String): String {
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', docId)
        return when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0/$rel"
            volume.isNotEmpty() && !docId.startsWith("raw:") -> "/storage/$volume/$rel"
            docId.startsWith("raw:") -> docId.removePrefix("raw:")
            else -> rel
        }
    }
}

/** Guarda el lote conservando pre-hash y checksum de los archivos que no cambiaron. */
internal suspend fun FileDao.upsertKeepingHashes(batch: List<FileItem>) {
    if (batch.isEmpty()) return
    val previous = getByUris(batch.map { it.uri }).associateBy { it.uri }
    upsertAll(batch.map { item ->
        val old = previous[item.uri]
        if (old != null && old.size == item.size && old.lastModified == item.lastModified) {
            item.copy(preHash = old.preHash, checksum = old.checksum)
        } else item
    })
}
