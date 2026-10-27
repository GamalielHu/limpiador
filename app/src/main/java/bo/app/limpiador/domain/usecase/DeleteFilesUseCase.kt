package bo.app.limpiador.domain.usecase

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DeleteOutcome {
    data class Done(val deleted: List<FileItem>, val failed: List<FileItem>) : DeleteOutcome {
        val freedBytes: Long get() = deleted.sumOf { it.size }
    }

    /**
     * Scoped Storage exige que el usuario confirme. Tras lanzar [intentSender] se llama a
     * [DeleteFilesUseCase.afterConsent] con [pending]; [remaining] se reintenta después (Android 10).
     */
    data class NeedsConsent(
        val intentSender: IntentSender,
        val pending: List<FileItem>,
        val remaining: List<FileItem>,
        val deletedSoFar: List<FileItem>,
    ) : DeleteOutcome
}

class DeleteFilesUseCase(private val resolver: ContentResolver, private val dao: FileDao) {

    suspend operator fun invoke(items: List<FileItem>): DeleteOutcome = withContext(Dispatchers.IO) {
        val deleted = ArrayList<FileItem>()
        val failed = ArrayList<FileItem>()
        val (media, docs) = items.partition { it.contentUri.authority == MediaStore.AUTHORITY }

        for (f in docs) {
            val ok = runCatching { DocumentsContract.deleteDocument(resolver, f.contentUri) }.getOrDefault(false)
            if (ok) deleted += f else failed += f
        }

        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        when {
            media.isEmpty() -> Unit

            // Con acceso total (o almacenamiento legado) se borra sin diálogo del sistema.
            allFiles || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                for (f in media) if (deleteDirect(f)) deleted += f else failed += f
            }

            // Android 11+: una sola solicitud del sistema para todo el lote.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val request = MediaStore.createDeleteRequest(resolver, media.map { it.contentUri })
                dao.deleteByUris(deleted.map { it.uri })
                return@withContext DeleteOutcome.NeedsConsent(request.intentSender, media, emptyList(), deleted)
            }

            // Android 10: se borra uno a uno; el primero ajeno pide permiso con RecoverableSecurityException.
            else -> {
                for ((i, f) in media.withIndex()) {
                    try {
                        if (resolver.delete(f.contentUri, null, null) > 0 || !exists(f.contentUri)) deleted += f
                        else failed += f
                    } catch (e: RecoverableSecurityException) {
                        dao.deleteByUris(deleted.map { it.uri })
                        return@withContext DeleteOutcome.NeedsConsent(
                            e.userAction.actionIntent.intentSender,
                            listOf(f),
                            media.drop(i + 1),
                            deleted,
                        )
                    } catch (e: SecurityException) {
                        failed += f
                    }
                }
            }
        }
        dao.deleteByUris(deleted.map { it.uri })
        DeleteOutcome.Done(deleted, failed)
    }

    /** Tras la confirmación del usuario: verifica qué se borró realmente y actualiza el índice. */
    suspend fun afterConsent(pending: List<FileItem>, granted: Boolean): DeleteOutcome.Done = withContext(Dispatchers.IO) {
        if (!granted) return@withContext DeleteOutcome.Done(emptyList(), pending)
        val deleted = ArrayList<FileItem>()
        val failed = ArrayList<FileItem>()
        for (f in pending) {
            // En Android 10 el permiso concedido no borra: hay que repetir el delete.
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && exists(f.contentUri)) {
                runCatching { resolver.delete(f.contentUri, null, null) }
            }
            if (!exists(f.contentUri)) deleted += f else failed += f
        }
        dao.deleteByUris(deleted.map { it.uri })
        DeleteOutcome.Done(deleted, failed)
    }

    private fun deleteDirect(f: FileItem): Boolean {
        val rows = runCatching { resolver.delete(f.contentUri, null, null) }.getOrDefault(0)
        if (rows > 0) return true
        if (f.path.isNotEmpty()) {
            val file = File(f.path)
            if (file.exists() && file.delete()) return true
        }
        return !exists(f.contentUri)
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(true)
}
