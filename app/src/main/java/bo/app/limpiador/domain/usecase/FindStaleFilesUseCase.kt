package bo.app.limpiador.domain.usecase

import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Archivos sin modificar ni abrir en más de X días, de mayor a menor tamaño. */
class FindStaleFilesUseCase(private val dao: FileDao) {

    suspend operator fun invoke(
        days: Int,
        categories: Set<FileCategory> = emptySet(),
        limit: Int = 3000,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val cutoff = if (days <= 0) Long.MAX_VALUE
        else System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        dao.staleFiles(cutoff, categories.isEmpty(), categories.toList(), limit)
            .distinctBy { it.path.ifEmpty { it.uri } }
    }

    /** Temporales, registros, copias de respaldo, descargas a medias, miniaturas y APKs ya instalables. */
    suspend fun residual(limit: Int = 3000): List<FileItem> = withContext(Dispatchers.IO) {
        dao.residualFiles(limit).distinctBy { it.path.ifEmpty { it.uri } }
    }
}
