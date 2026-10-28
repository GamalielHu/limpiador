package bo.app.limpiador.domain.usecase

import android.net.Uri
import bo.app.limpiador.data.db.FileDao
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.data.scanner.FileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(val checksum: String, val size: Long, val files: List<FileItem>) {
    /** Bytes que se liberan dejando una sola copia. */
    val wastedBytes: Long get() = size * (files.size - 1)

    /** [files] va ordenado por fecha: el primero es el más antiguo. */
    val oldest: FileItem get() = files.first()
}

/**
 * Tres filtros, de más barato a más caro:
 * 1. mismo tamaño (consulta SQL sobre el índice),
 * 2. pre-hash de los primeros y últimos 64 KB,
 * 3. SHA-256 completo, solo si el pre-hash también coincide.
 * Los hashes quedan guardados en Room para el próximo análisis.
 */
class FindDuplicatesUseCase(private val dao: FileDao, private val hasher: FileHasher) {

    suspend operator fun invoke(
        minSize: Long = 1,
        include: (FileItem) -> Boolean = { true },
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        // El mismo archivo puede estar indexado por MediaStore y por SAF: se cuenta una sola vez.
        val candidates = dao.sizeCollisions(minSize).filter(include).distinctBy { it.path.ifEmpty { it.uri } }
        val bySize = candidates.groupBy { it.size }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var processed = 0
        val result = ArrayList<DuplicateGroup>()

        for (sameSize in bySize) {
            coroutineContext.ensureActive()
            val size = sameSize.first().size

            val withPre = sameSize.mapNotNull { f ->
                val pre = f.preHash ?: hasher.preHash(Uri.parse(f.uri), f.size)?.also { dao.setPreHash(f.uri, it) }
                pre?.let { f.copy(preHash = it) }
            }

            for (samePre in withPre.groupBy { it.preHash!! }.values.filter { it.size > 1 }) {
                val hashed = if (size <= FileHasher.FULL_COVERAGE) {
                    // El pre-hash ya leyó el archivo completo: no hace falta otra pasada.
                    samePre.map { f ->
                        if (f.checksum != f.preHash) dao.setChecksum(f.uri, f.preHash!!)
                        f.copy(checksum = f.preHash)
                    }
                } else {
                    samePre.mapNotNull { f ->
                        val sum = f.checksum ?: hasher.fullHash(Uri.parse(f.uri))?.also { dao.setChecksum(f.uri, it) }
                        sum?.let { f.copy(checksum = it) }
                    }
                }
                hashed.groupBy { it.checksum!! }
                    .filterValues { it.size > 1 }
                    .forEach { (sum, files) ->
                        result += DuplicateGroup(sum, size, files.sortedWith(compareBy({ it.lastModified }, { it.id })))
                    }
            }
            processed += sameSize.size
            onProgress(processed, total)
        }
        result.sortedByDescending { it.wastedBytes }
    }
}
